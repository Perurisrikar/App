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
        val BLOCKED_SENDERS = setOf<String>("Mom", "Bank") // names exactly as shown in WhatsApp
        private val NOISE = Regex("""^\d+ new messages?$|^Checking for new messages|^WhatsApp Web""", RegexOption.IGNORE_CASE)
    }

    override fun onListenerConnected() {
        scope.launch { AiClient.init(applicationContext) } // preload so the first reply is fast
    }

    override fun onListenerDisconnected() {
        // Ask Android to reconnect if the system dropped us
        requestRebind(android.content.ComponentName(this, WhatsAppListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("enabled", false)) return
        if (sbn.packageName !in WHATSAPP) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) return

        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (sender in BLOCKED_SENDERS || NOISE.containsMatchIn(text) || sender == "WhatsApp") return

        val action = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true } ?: return

        val key = "$sender|${n.`when`}|$text"
        synchronized(handled) {
            if (handled.containsKey(key)) return
            handled[key] = true
        }

        scope.launch {
            val reply = AiClient.reply(applicationContext, sender, text) ?: return@launch
            delay(Random.nextLong(2000, 5000)) // look less robotic
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
        } catch (e: Exception) {
            // notification was dismissed or opened; nothing to do
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
