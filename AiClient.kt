package com.example.waautoreply

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device Gemma (MediaPipe). Style examples live in the app (editable on the main screen).
 * For every incoming message the most similar examples are picked and shown to the model.
 */
object AiClient {
    const val MODEL_FILE = "model.task"
    private const val EXAMPLES_IN_PROMPT = 10

    // Format: their message => your reply   (one per line). Editable inside the app.
    val DEFAULT_EXAMPLES = """
        Hii => Hii
        Hi => Hii
        Hello velava => Ha
        Em chesthunav => Em ledhu, nuvvu?
        Thinnava => Ha thinna, nuvvu?
        Lab gurenchii em ayna => Em ledhu
        Mari ela => Liteuu
        Not happy => Endhuku?
        Okasari call cheye ra => Ok
        Repu => Ok ok
        Undhi exam => Saripodhi
        Free fire adthunara? => Ha
        Okk take rest aythe => Ha
        Message chesava thanaki => Confuse aindhi
        Ha koni unae => Em vaddu le
        Avnhaa => Ok le
        Enthe kadha => Ha
        Vanasthalipuram => Oh ok
        Send chesthadhi anta => Oh ok
        Pakka => Ardham kale
        Ravali inka => Em undhi antha important kadhu ga
        Hmm ala anaku ra => Chaduvuthunnava mari
        Akka velthundhi repu so busy => Ok ok
        Nuvv => Ha
        Anthey na => Ha
        Awunaa => Ha
        Start chesina => Okk
        Cheppeyyi => Ok
        Andhuke chesina => Oh ok
        Rep bus lo chuduvv leyy => Sare
        Sarey sarey chadhuvukoo => Sare
        Nycc => Thanksss
        Padukunnav gah => Appatike lechina
        Nijam ahh => Ha
        EDC ahh => Ha
        Ela unnav => Bagunna ra, nuvvu?
        Ekkada unnav => Intlo unna ra
        Call cheyi => Ippudu busy ra, taruvata chestha
        Repu vastava => Ha vastha ra
        Happy birthday => Thankss ra
        Good morning => Good morning
        Good night => Good night ra
        Sorry => Parledhu ra
        Em cheppu => Em ledhu, nuvvu cheppu
        Bore kodthundhi => Naku kuda ra
        Help cheyava => Enti cheppu
        Free ah => Ha free ne, cheppu
        Busy ah => Konchem busy ra
        Padukunnava => Inka ledhu ra
        Bayataki veldhama => Ippudu kadhu ra, taruvata
        Tiffin chesava => Ha chesa, nuvvu?
        Notes unnaya => Undhi ra, pampistha
        Assignment ayyinda => Inka kale, nuvvu?
        Class unda => Undhi ra
        Mark entha => Telvadhu inka
    """.trimIndent()

    // Custom replies: keyword(s) => reply | another reply   (one rule per line, first match wins).
    // Checked BEFORE the AI. Whole-word match, not case sensitive. Several replies = one picked at random.
    val DEFAULT_RULES = """
        hii, hi, hello, hey, hlo => Hii | Hii ra | Ha cheppu
        em chesthunav, em chestunnav, em chesthunnav => Em ledhu, nuvvu? | Em ledhu ra | Em ledhu le
        thinnava, tinnava => Ha thinna, nuvvu? | Thinna ra, nuvvu?
        ela unnav, ela unnavu, ela unnaru => Bagunna ra, nuvvu? | Bagunna, nuvvu?
        ekkada unnav, ekkada unnavu => Intlo unna ra | Ikkade unna
        call cheyi, call cheyava, call chey => Ippudu busy ra, taruvata chestha | Taruvata chestha ra
        busy ha, busy aa, busy ah => Konchem busy ra | Ha busy ra, taruvata matladadham
        repu vastava, repu vastunnava => Ha vastha ra | Vastha ra
        happy birthday, hbd => Thankss ra | Thank you ra
        good morning, gm => Good morning | Good morning ra
        good night, gn => Good night | Good night ra
        thanks, thank you, thx => Welcome ra | Parledhu ra
        sorry => Parledhu ra | Em parledhu
        bye => Bye ra | Taruvata matladadham
    """.trimIndent()

    class Rule(val keywords: List<String>, val replies: List<String>)

    fun rulesText(context: Context): String =
        context.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("rules", null) ?: DEFAULT_RULES

    fun parseRules(raw: String): List<Rule> = raw.lines().mapNotNull { l ->
        val i = l.indexOf("=>")
        if (i <= 0) null else {
            val keys = l.substring(0, i).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val outs = l.substring(i + 2).split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (keys.isEmpty() || outs.isEmpty()) null else Rule(keys, outs)
        }
    }

    /** Returns a custom reply if a rule matches, else null. */
    fun customReply(context: Context, text: String): String? {
        val cfg = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (!cfg.getBoolean("rules_on", true)) return null
        val t = text.lowercase().trim()
        for (r in parseRules(rulesText(context))) {
            val hit = r.keywords.any { k ->
                Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(k) + "(\$|[^\\p{L}\\p{N}])").containsMatchIn(t)
            }
            if (hit) return r.replies.random()
        }
        return null
    }

    private val SYSTEM = """
        You reply to WhatsApp messages exactly like the examples: romanized Telugu mixed with English, lowercase, very short, casual.
        Never use Telugu script. Usually answer in 1-4 words. Copy the wording of the closest example. Never invent new words.
        If the message needs no reply, write SKIP.
    """.trimIndent()

    private val TRIVIAL = Regex("""^(ok+|okay|k|thanks?|thank you|thx|hmm+|👍|🙏|😂|😊|ha+|sare|sare ra)[.! ]*$""", RegexOption.IGNORE_CASE)

    private var llm: LlmInference? = null
    private val lock = Mutex()
    private val history = mutableMapOf<String, MutableList<Pair<String, String>>>()

    fun modelFile(context: Context) = File(context.getExternalFilesDir(null), MODEL_FILE)

    // ---------- examples ----------
    fun examplesText(context: Context): String =
        context.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("examples", null) ?: DEFAULT_EXAMPLES

    fun parseExamples(raw: String): List<Pair<String, String>> = raw.lines().mapNotNull { l ->
        val i = l.indexOf("=>")
        if (i <= 0) null else {
            val a = l.substring(0, i).trim()
            val b = l.substring(i + 2).trim()
            if (a.isEmpty() || b.isEmpty()) null else a to b
        }
    }

    private fun words(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9\\u0C00-\\u0C7F]+")).filter { it.isNotEmpty() }.toSet()

    private fun bigrams(s: String): Set<String> {
        val t = s.lowercase().filter { it.isLetterOrDigit() }
        return if (t.length < 2) setOf(t) else (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
    }

    private fun dice(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * a.intersect(b).size / (a.size + b.size)

    private fun score(q: String, ex: String) = 0.6 * dice(bigrams(q), bigrams(ex)) + 0.4 * dice(words(q), words(ex))

    /** Most similar examples last (closest to the question). */
    private fun pickExamples(context: Context, text: String): List<Pair<String, String>> =
        parseExamples(examplesText(context)).sortedBy { score(text, it.first) }.takeLast(EXAMPLES_IN_PROMPT)

    // ---------- model ----------
    suspend fun init(context: Context): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (llm != null) return@withLock true
            val f = modelFile(context)
            if (!f.exists()) { DebugLog.add(context, "Model file missing"); return@withLock false }
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(f.absolutePath)
                    .setMaxTokens(640)
                    .setMaxTopK(40)
                    .build()
                llm = LlmInference.createFromOptions(context.applicationContext, options)
                DebugLog.add(context, "Model loaded OK")
                true
            } catch (e: Throwable) {
                llm = null
                DebugLog.add(context, "Model load FAILED: ${e.javaClass.simpleName} ${e.message?.take(150)}")
                false
            }
        }
    }

    private fun generate(prompt: String): String {
        val opts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(3)
            .setTemperature(0.2f)
            .build()
        val session = LlmInferenceSession.createFromOptions(llm!!, opts)
        try {
            session.addQueryChunk(prompt)
            return session.generateResponse()
        } finally {
            session.close()
        }
    }

    private fun buildPrompt(turns: List<Pair<String, String>>, examples: List<Pair<String, String>>): String {
        val ex = examples.joinToString("\n") { "Them: ${it.first}\nMe: ${it.second}" }
        val sb = StringBuilder()
        turns.forEachIndexed { i, (role, text) ->
            val content = if (i == 0) "$SYSTEM\n\nExamples:\n$ex\n\nNow reply to this.\nThem: $text\nMe:" else text
            sb.append("<start_of_turn>${if (role == "user") "user" else "model"}\n$content<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    /** Returns reply text, or null if nothing should be sent. */
    suspend fun reply(context: Context, sender: String, text: String): String? =
        withContext(Dispatchers.Default) {
            customReply(context, text)?.let {
                DebugLog.add(context, "Custom reply matched")
                return@withContext it
            }
            if (!context.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("use_ai", true))
                return@withContext null
            if (TRIVIAL.matches(text.trim())) return@withContext null
            if (!init(context)) return@withContext null
            val examples = pickExamples(context, text)
            lock.withLock {
                val turns = history.getOrPut(sender) { mutableListOf() }
                turns.add("user" to text)
                while (turns.size > 2) turns.removeAt(0)
                while (turns.isNotEmpty() && turns[0].first != "user") turns.removeAt(0)

                val raw = try {
                    generate(buildPrompt(turns, examples))
                } catch (e: Throwable) {
                    DebugLog.add(context, "Generation FAILED: ${e.javaClass.simpleName} ${e.message?.take(150)}")
                    turns.removeAt(turns.lastIndex); return@withLock null
                }
                var out = raw.replace("<end_of_turn>", "").removePrefix("Me:").trim()
                out = out.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
                DebugLog.add(context, "Model raw: ${raw.take(80)}")
                if (out.length > 120) out = out.take(120).substringBeforeLast(' ')
                if (out.isEmpty() || out.startsWith("SKIP", true) || out.any { it in '\u0C00'..'\u0C7F' }) {
                    turns.removeAt(turns.lastIndex); return@withLock null
                }
                turns.add("model" to out)
                out
            }
        }
}
