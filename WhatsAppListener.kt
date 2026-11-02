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

    // ---- Loop / spam guard ----
    private val guardLock = Any()
    private val replyTimes = mutableMapOf<String, ArrayDeque<Long>>()
    private val globalTimes = ArrayDeque<Long>()
    private val lastIncoming = mutableMapOf<String, Pair<String, Long>>()
    private val ownReplies = mutableMapOf<String, ArrayDeque<String>>()

    /** Returns a reason to skip, or null if replying is allowed (and records the reply slot). */
    private fun checkGuard(sender: String, text: String): String? {
        val now = System.currentTimeMillis()
        synchronized(guardLock) {
            // 1. our own reply echoing back as a notification
            if (ownReplies[sender]?.contains(text.trim()) == true) return "echo of my own reply"
            // 2. same message repeated within 60 s (spam / bot loop)
            val last = lastIncoming[sender]
            lastIncoming[sender] = text to now
            if (last != null && last.first.equals(text, true) && now - last.second < REPEAT_WINDOW_MS) return "same message repeated"
            // 3. minimum gap and per-sender cap
            val times = replyTimes.getOrPut(sender) { ArrayDeque() }
            while (times.isNotEmpty() && now - times.first() > 10 * 60_000) times.removeFirst()
            if (times.isNotEmpty() && now - times.last() < MIN_GAP_MS) return "too soon after last reply"
            if (times.size >= MAX_PER_SENDER_10MIN) return "too many replies to this sender"
            // 4. global hourly cap
            while (globalTimes.isNotEmpty() && now - globalTimes.first() > 60 * 60_000) globalTimes.removeFirst()
            if (globalTimes.size >= MAX_PER_HOUR) return "hourly limit reached"
            times.addLast(now); globalTimes.addLast(now)
            return null
        }
    }

    private fun rememberOwn(sender: String, reply: String) {
        synchronized(guardLock) {
            val q = ownReplies.getOrPut(sender) { ArrayDeque() }
            q.addLast(reply.trim())
            while (q.size > 5) q.removeFirst()
        }
    }

    companion object {
        const val MIN_GAP_MS = 4_000L
        const val REPEAT_WINDOW_MS = 8_000L
        const val MAX_PER_SENDER_10MIN = 25
        const val MAX_PER_HOUR = 120
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

        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        fun names(k: String) = (cfg.getString(k, "") ?: "").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val who = sender.lowercase()
        if (cfg.getString("mode", "all") == "selected" && names("allowed").none { who.contains(it) }) {
            DebugLog.add(this, "-> skipped: not in your allowed list"); return
        }
        if (names("blocked").any { who.contains(it) }) { DebugLog.add(this, "-> skipped: blocked contact"); return }

        val action = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
        if (action == null) { DebugLog.add(this, "-> skipped: no Reply button on notification"); return }

        val key = "$sender|${n.`when`}|$text"
        synchronized(handled) {
            if (handled.containsKey(key)) return
            handled[key] = true
        }

        val blocked = checkGuard(sender, text)
        if (blocked != null) { DebugLog.add(this, "-> skipped: $blocked"); return }

        scope.launch {
            DebugLog.add(this@WhatsAppListener, "-> asking AI...")
            val reply = AiClient.reply(applicationContext, sender, text)
            if (reply == null) { DebugLog.add(this@WhatsAppListener, "-> AI gave no reply"); return@launch }
            DebugLog.add(this@WhatsAppListener, "-> AI: '$reply'")
            rememberOwn(sender, reply)
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
