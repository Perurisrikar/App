package com.example.waautoreply

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*
import kotlin.random.Random

class WhatsAppListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handled = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 200
    }

    companion object {
        val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
        val BLOCKED_SENDERS = setOf<String>() // add names exactly as shown in WhatsApp
        private val NOISE = Regex("""^\d+ new messages?$|^Checking for new messages|^WhatsApp Web""", RegexOption.IGNORE_CASE)
    }

    override fun onListenerConnected() {
        DebugLog.add(this, "Listener connected")
        scope.launch { AiClient.init(applicationContext) }
    }

    override fun onListenerDisconnected() {
        DebugLog.add(this, "Listener disconnected")
        requestRebind(android.content.ComponentName(this, WhatsAppListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP) return
        val n = sbn.notification
        val extras = n.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        DebugLog.add(this, "WA notif [${sbn.packageName.takeLast(4)}] from='$sender' text='${text?.take(30)}'")

        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("enabled", false)) {
            DebugLog.add(this, "-> skipped: switch is OFF"); return
        }
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) { DebugLog.add(this, "-> skipped: group summary"); return }
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) { DebugLog.add(this, "-> skipped: group chat"); return }
        if (sender == null || text == null) { DebugLog.add(this, "-> skipped: no title/text"); return }
        if (sender in BLOCKED_SENDERS || NOISE.containsMatchIn(text) || sender == "WhatsApp") {
            DebugLog.add(this, "-> skipped: blocked/noise"); return
        }

        val action = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
        if (action == null) { DebugLog.add(this, "-> skipped: no Reply button on notification"); return }

        val key = "$sender|${n.`when`}|$text"
        synchronized(handled) {
            if (handled.containsKey(key)) return
            handled[key] = true
        }

        scope.launch {
            DebugLog.add(this@WhatsAppListener, "-> asking AI...")
            val reply = AiClient.reply(applicationContext, sender, text)
            if (reply == null) { DebugLog.add(this@WhatsAppListener, "-> AI gave no reply"); return@launch }
            DebugLog.add(this@WhatsAppListener, "-> AI: '$reply'")
            delay(Random.nextLong(2000, 5000))
            sendReply(action, reply)
        }
    }

    private fun sendReply(action: Notification.Action, reply: String) {
        try {
            val inputs = action.remoteInputs
            val intent = Intent()
            val results = Bundle().apply { putCharSequence(inputs[0].resultKey, reply) }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(this, 0, intent)
            DebugLog.add(this, "-> reply SENT")
        } catch (e: Exception) {
            DebugLog.add(this, "-> send FAILED: ${e.message}")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
