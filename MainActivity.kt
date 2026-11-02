package com.example.waautoreply

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView

    override fun onResume() {
        super.onResume()
        if (::logView.isInitialized) logView.text = DebugLog.get(this)
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyModel(uri)
    }

    private fun modelStatus(): String {
        val f = AiClient.modelFile(this)
        return if (f.exists()) "Model: ready (${f.length() / 1048576} MB)" else "Model: not added yet"
    }

    private fun copyModel(uri: Uri) {
        status.text = "Copying model... please wait, don't close the app"
        Thread {
            try {
                val dest = AiClient.modelFile(this)
                dest.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)!!.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                }
                runOnUiThread { status.text = modelStatus() + ". Now turn on the switch." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Copy failed: ${e.message}" }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "WhatsApp AI Auto-Reply"
            textSize = 22f
        })
        root.addView(Button(this).apply {
            text = "1. Grant notification access"
            setOnClickListener { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        })
        root.addView(Button(this).apply {
            text = "2. Disable battery optimization"
            setOnClickListener {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                }
            }
        })
        root.addView(Button(this).apply {
            text = "3. Choose model file (.task)"
            setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        })
        status = TextView(this).apply {
            text = modelStatus()
            setPadding(0, 16, 0, 16)
        }
        root.addView(status)
        root.addView(Switch(this).apply {
            text = "4. Auto-reply enabled"
            isChecked = prefs.getBoolean("enabled", false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("enabled", on).apply() }
        })
        root.addView(Button(this).apply {
            text = "5. Test AI (no WhatsApp needed)"
            setOnClickListener {
                logView.text = "Testing... wait up to a minute, then tap Refresh log"
                Thread {
                    val r = kotlinx.coroutines.runBlocking { AiClient.reply(applicationContext, "test", "Hii em chesthunav") }
                    DebugLog.add(this@MainActivity, "TEST result: $r")
                    runOnUiThread { logView.text = DebugLog.get(this@MainActivity) }
                }.start()
            }
        })
        root.addView(Button(this).apply {
            text = "Refresh log"
            setOnClickListener { logView.text = DebugLog.get(this@MainActivity) }
        })
        logView = TextView(this).apply {
            text = DebugLog.get(this@MainActivity)
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
