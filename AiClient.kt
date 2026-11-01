package com.example.waautoreply

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device Gemma (MediaPipe), tuned for a 1B model on a 4 GB phone:
 * short prompt, few-shot examples in your own style, tiny history, capped reply length.
 */
object AiClient {
    const val MODEL_FILE = "model.task" // /sdcard/Android/data/com.example.waautoreply/files/

    // >>> EDIT THESE: replace with 15-25 REAL exchanges from your chats (keep the format). <<<
    // Placeholders below are only a guess at your style.
    private val EXAMPLES = listOf(
        "Hii" to "Hii",
        "Hello velava" to "Ha",
        "Lab gurenchii em ayna" to "Em ledhu",
        "Mari ela" to "Liteuu",
        "Not happy" to "Endhuku?",
        "Okasari call cheye ra" to "Ok",
        "Repu" to "Ok ok",
        "Undhi exam" to "Saripodhi",
        "Free fire adthunara?" to "Ha",
        "Okk take rest aythe" to "Ha",
        "Message chesava thanaki" to "Confuse aindhi",
        "Ha koni unae" to "Em vaddu le",
        "Em chesthunav" to "Em ledhu, nuvvu?",
        "Thinnava" to "Ha thinna, nuvvu?",
        "ok" to "SKIP",
        "thanks" to "SKIP"
    )

    private val SYSTEM = """
        You reply to WhatsApp messages exactly like the examples: romanized Telugu mixed with English, lowercase, very short, casual.
        Never use Telugu script. Never write more than one short sentence. Do not explain anything.
        If the message needs no reply, write SKIP.
    """.trimIndent()

    // Messages that never need a model call (saves battery and time)
    private val TRIVIAL = Regex("""^(ok+|okay|k|thanks?|thank you|thx|hmm+|👍|🙏|😂|😊|ha+|sare|sare ra)[.! ]*$""", RegexOption.IGNORE_CASE)

    private var llm: LlmInference? = null
    private val lock = Mutex()
    private val history = mutableMapOf<String, MutableList<Pair<String, String>>>()

    fun modelFile(context: Context) = File(context.getExternalFilesDir(null), MODEL_FILE)

    suspend fun init(context: Context): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (llm != null) return@withLock true
            val f = modelFile(context)
            if (!f.exists()) return@withLock false
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(f.absolutePath)
                    .setMaxTokens(512)   // prompt + reply; low to fit 4 GB RAM
                    .setMaxTopK(40)
                    .build()
                llm = LlmInference.createFromOptions(context.applicationContext, options)
                true
            } catch (e: Throwable) {   // includes out-of-memory
                llm = null
                false
            }
        }
    }

    private fun buildPrompt(turns: List<Pair<String, String>>): String {
        val ex = EXAMPLES.joinToString("\n") { "Them: ${it.first}\nMe: ${it.second}" }
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
            if (TRIVIAL.matches(text.trim())) return@withContext null
            if (!init(context)) return@withContext null
            lock.withLock {
                val turns = history.getOrPut(sender) { mutableListOf() }
                turns.add("user" to text)
                while (turns.size > 2) turns.removeAt(0)          // tiny history = faster
                while (turns.isNotEmpty() && turns[0].first != "user") turns.removeAt(0)

                val raw = try {
                    llm!!.generateResponse(buildPrompt(turns))
                } catch (e: Throwable) {
                    turns.removeAt(turns.lastIndex); return@withLock null
                }
                var out = raw.replace("<end_of_turn>", "").removePrefix("Me:").trim()
                out = out.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""   // first line only
                if (out.length > 120) out = out.take(120).substringBeforeLast(' ')
                // drop empty, SKIP, or Telugu-script / runaway output
                if (out.isEmpty() || out.startsWith("SKIP", true) || out.any { it in '\u0C00'..'\u0C7F' }) {
                    turns.removeAt(turns.lastIndex); return@withLock null
                }
                turns.add("model" to out)
                out
            }
        }
}
