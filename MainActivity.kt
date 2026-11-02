package com.example.waautoreply

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var statusLine: TextView
    private lateinit var rowNotif: TextView
    private lateinit var rowBatt: TextView
    private lateinit var rowModel: TextView
    private lateinit var btnExamples: Button
    private lateinit var btnRules: Button
    private lateinit var logView: TextView
    private lateinit var chatBox: LinearLayout
    private lateinit var mainScroll: ScrollView
    private var copyingMsg: String? = null

    private val refresher = object : Runnable {
        override fun run() { refreshAll(); ui.postDelayed(this, 2000) }
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyModel(uri)
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val green = 0xFF25A55F.toInt()

    private fun rounded(color: Int, r: Int = 16) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun card(title: String, content: LinearLayout.() -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (dark) 0xFF1E2227.toInt() else 0xFFF1F3F4.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, dp(12)) }
        }
        c.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
            setPadding(0, 0, 0, dp(8))
        })
        c.content()
        return c
    }

    private fun row(onClick: () -> Unit): TextView = TextView(this).apply {
        textSize = 15f
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    private fun EditText.onChange(f: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
        })
    }

    private fun bubble(msg: String, mine: Boolean): TextView {
        val t = TextView(this).apply {
            this.text = msg
            textSize = 15f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(
                if (mine) 0xFF1F8A4C.toInt() else if (dark) 0xFF2B3036.toInt() else 0xFFE4E6EB.toInt(), 14)
            if (mine) setTextColor(Color.WHITE)
        }
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            setMargins(0, dp(4), 0, dp(4))
        }
        chatBox.addView(t)
        return t
    }

    // ---------- status ----------
    private fun notifOk() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    private fun battOk() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    private fun modelOk() = AiClient.modelFile(this).exists()

    private fun refreshAll() {
        rowNotif.text = if (notifOk()) "✅  Notification access" else "❌  Notification access — tap to enable"
        rowBatt.text = if (battOk()) "✅  Battery: unrestricted" else "❌  Battery optimization — tap to disable"
        rowModel.text = copyingMsg ?: if (modelOk())
            "✅  AI model (${AiClient.modelFile(this).length() / 1048576} MB) — tap to replace"
        else "❌  AI model — tap to choose the .task file"

        val on = prefs.getBoolean("enabled", false)
        val (msg, color) = when {
            !on -> "⏸  Paused" to 0xFF9AA0A6.toInt()
            !notifOk() -> "⚠  Needs notification access" to 0xFFE8A200.toInt()
            !modelOk() -> "⚠  Needs the AI model" to 0xFFE8A200.toInt()
            else -> "🟢  Running — waiting for messages" to green
        }
        statusLine.text = msg
        statusLine.setTextColor(color)

        btnExamples.text = "✏️  Edit my style examples (${AiClient.parseExamples(AiClient.examplesText(this)).size})"
        btnRules.text = "🧩  Edit custom replies (${AiClient.parseRules(AiClient.rulesText(this)).size})"
        logView.text = DebugLog.get(this).lines().take(14).joinToString("\n")
    }

    // ---------- actions ----------
    private fun copyModel(uri: Uri) {
        copyingMsg = "⏳  Copying model… don't close the app"
        refreshAll()
        Thread {
            try {
                val dest = AiClient.modelFile(this)
                dest.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)!!.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                }
                copyingMsg = null
                DebugLog.add(this, "Model copied (${dest.length() / 1048576} MB)")
            } catch (e: Exception) {
                copyingMsg = "❌  Copy failed: ${e.message}"
            }
            runOnUiThread { refreshAll() }
        }.start()
    }

    private fun editExamples() {
        val et = EditText(this).apply {
            setText(AiClient.examplesText(this@MainActivity))
            minLines = 10
            gravity = Gravity.TOP
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val sv = ScrollView(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(et) }
        AlertDialog.Builder(this)
            .setTitle("Your style examples")
            .setMessage("One per line:\ntheir message => your reply")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("examples", et.text.toString()).apply()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .setNeutralButton("Reset") { _, _ ->
                prefs.edit().remove("examples").apply()
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editRules() {
        val et = EditText(this).apply {
            setText(AiClient.rulesText(this@MainActivity))
            minLines = 10
            gravity = Gravity.TOP
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val sv = ScrollView(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(et) }
        AlertDialog.Builder(this)
            .setTitle("Custom replies")
            .setMessage("One rule per line:\nword, another word => reply | other reply\nIf a message contains the word, you get that reply (one picked at random). First matching line wins.")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("rules", et.text.toString()).apply()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .setNeutralButton("Reset") { _, _ ->
                prefs.edit().remove("rules").apply()
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- UI ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "WA AI Reply"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Replies to WhatsApp messages in your style, on your phone."
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dp(2), 0, dp(14))
        })

        // 1. Master switch + status
        statusLine = TextView(this).apply { textSize = 15f; setPadding(0, dp(6), 0, 0) }
        root.addView(card("AUTO-REPLY") {
            addView(Switch(this@MainActivity).apply {
                text = "Reply automatically"
                textSize = 17f
                isChecked = prefs.getBoolean("enabled", false)
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putBoolean("enabled", on).apply()
                    if (on && (!notifOk() || !modelOk()))
                        Toast.makeText(this@MainActivity, "Finish the setup checklist below first", Toast.LENGTH_LONG).show()
                    refreshAll()
                }
            })
            addView(statusLine)
        })

        // 2. Setup checklist
        rowNotif = row { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        rowBatt = row {
            if (!battOk()) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        rowModel = row { pickModel.launch(arrayOf("*/*")) }
        root.addView(card("SETUP — tap a row to fix it") {
            addView(rowNotif); addView(rowBatt); addView(rowModel)
        })

        // 3. Who to reply to
        val allowedBox = EditText(this).apply {
            hint = "e.g. Ravi, Sai (part of the name works)"
            setText(prefs.getString("allowed", ""))
            textSize = 14f
            onChange { prefs.edit().putString("allowed", it).apply() }
        }
        val blockedBox = EditText(this).apply {
            hint = "e.g. Mom, Sir, Bank"
            setText(prefs.getString("blocked", ""))
            textSize = 14f
            onChange { prefs.edit().putString("blocked", it).apply() }
        }
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbAll = RadioButton(this).apply { text = "Everyone (private chats only)"; id = 1001 }
        val rbSel = RadioButton(this).apply { text = "Only these people:"; id = 1002 }
        group.addView(rbAll); group.addView(rbSel)
        fun applyMode(selected: Boolean) {
            allowedBox.isEnabled = selected
            allowedBox.alpha = if (selected) 1f else 0.4f
        }
        group.setOnCheckedChangeListener { _, id ->
            val sel = id == 1002
            prefs.edit().putString("mode", if (sel) "selected" else "all").apply()
            applyMode(sel)
        }
        val selected = prefs.getString("mode", "all") == "selected"
        group.check(if (selected) 1002 else 1001)
        applyMode(selected)
        root.addView(card("WHO TO REPLY TO") {
            addView(group)
            addView(allowedBox)
            addView(TextView(this@MainActivity).apply {
                text = "Never reply to (always applies):"
                textSize = 14f
                setPadding(0, dp(12), 0, 0)
            })
            addView(blockedBox)
            addView(TextView(this@MainActivity).apply {
                text = "Use the names exactly as WhatsApp shows them. Groups are always skipped."
                textSize = 12f
                alpha = 0.6f
                setPadding(0, dp(6), 0, 0)
            })
        })

        // 4. Style
        btnExamples = Button(this).apply {
            isAllCaps = false
            setOnClickListener { editExamples() }
        }
        root.addView(card("YOUR STYLE") {
            addView(TextView(this@MainActivity).apply {
                text = "The AI copies these example replies. Add more of your real replies to sound more like you."
                textSize = 13f
                setPadding(0, 0, 0, dp(8))
            })
            addView(btnExamples)
        })

        // 4b. Custom replies
        btnRules = Button(this).apply {
            isAllCaps = false
            setOnClickListener { editRules() }
        }
        root.addView(card("CUSTOM REPLIES — your own fixed answers") {
            addView(TextView(this@MainActivity).apply {
                text = "Set the exact reply for words like \"hii\" or \"thinnava\". These are instant and don't need the AI."
                textSize = 13f
                setPadding(0, 0, 0, dp(8))
            })
            addView(Switch(this@MainActivity).apply {
                text = "Use custom replies first"
                isChecked = prefs.getBoolean("rules_on", true)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("rules_on", on).apply() }
            })
            addView(Switch(this@MainActivity).apply {
                text = "Use the AI when no custom reply matches"
                isChecked = prefs.getBoolean("use_ai", true)
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("use_ai", on).apply() }
            })
            addView(btnRules)
        })

        // 5. Try it
        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; minimumHeight = dp(60) }
        val input = EditText(this).apply {
            hint = "Type a message as your friend…"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val send = Button(this).apply { text = "Send"; isAllCaps = false }
        send.setOnClickListener {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return@setOnClickListener
            input.setText("")
            bubble(q, false)
            val typing = bubble("typing…", true)
            send.isEnabled = false
            mainScroll.post { mainScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            Thread {
                val r = try {
                    kotlinx.coroutines.runBlocking { AiClient.reply(applicationContext, "tryit", q) }
                } catch (e: Throwable) { null }
                runOnUiThread {
                    typing.text = r ?: "(no reply — message too short, model not ready, or the AI chose to skip. See activity log)"
                    send.isEnabled = true
                    refreshAll()
                }
            }.start()
        }
        root.addView(card("TRY IT — test without WhatsApp") {
            addView(chatBox)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(input); addView(send)
            })
        })

        // 5b. App update (from your GitHub releases)
        val updateStatus = TextView(this).apply {
            text = "Installed: build ${Updater.installedCode(this@MainActivity)}"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        val repoBox = EditText(this).apply {
            hint = "Your GitHub repo, e.g. yourname/wa-ai-reply"
            setText(prefs.getString("repo", ""))
            textSize = 14f
            onChange { prefs.edit().putString("repo", it).apply() }
        }
        val tokenBox = EditText(this).apply {
            hint = "Access token (only if the repo is private)"
            setText(prefs.getString("token", ""))
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            onChange { prefs.edit().putString("token", it).apply() }
        }
        val btnUpdate = Button(this).apply { text = "Check for update"; isAllCaps = false }
        var found: Updater.Info? = null

        fun doCheck() {
            updateStatus.text = "Checking…"
            btnUpdate.isEnabled = false
            Thread {
                try {
                    val info = Updater.check(this@MainActivity)
                    val mine = Updater.installedCode(this@MainActivity)
                    runOnUiThread {
                        if (info.code > mine) {
                            found = info
                            updateStatus.text = "⬆  Update available: build ${info.code} (you have $mine)"
                            btnUpdate.text = "Download & install"
                        } else {
                            found = null
                            updateStatus.text = "✅  You're up to date (build $mine)"
                            btnUpdate.text = "Check for update"
                        }
                        btnUpdate.isEnabled = true
                    }
                } catch (e: Throwable) {
                    runOnUiThread { updateStatus.text = "❌  ${e.message}"; btnUpdate.isEnabled = true }
                }
            }.start()
        }

        btnUpdate.setOnClickListener {
            val f = found
            if (f == null) { doCheck(); return@setOnClickListener }
            if (!packageManager.canRequestPackageInstalls()) {
                updateStatus.text = "Allow “install unknown apps” for this app, come back, then tap again"
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            btnUpdate.isEnabled = false
            Thread {
                try {
                    val apk = Updater.download(this@MainActivity, f) { pct ->
                        runOnUiThread { updateStatus.text = "⏳  Downloading… $pct%" }
                    }
                    runOnUiThread { updateStatus.text = "Installing… confirm on the screen that appears" }
                    Updater.install(this@MainActivity, apk)
                } catch (e: Throwable) {
                    runOnUiThread { updateStatus.text = "❌  ${e.message}"; btnUpdate.isEnabled = true }
                }
            }.start()
        }

        root.addView(card("APP UPDATE — no more GitHub uploads") {
            addView(repoBox)
            addView(tokenBox)
            addView(updateStatus)
            addView(btnUpdate)
        })
        if ((prefs.getString("repo", "") ?: "").contains("/")) doCheck()

        // 6. Activity
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(card("LIVE ACTIVITY") {
            addView(logView)
            addView(Button(this@MainActivity).apply {
                text = "Clear"
                isAllCaps = false
                setOnClickListener { DebugLog.clear(this@MainActivity); refreshAll() }
            })
        })

        mainScroll = ScrollView(this).apply { addView(root) }
        setContentView(mainScroll)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
    }
}
