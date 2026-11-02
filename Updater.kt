package com.example.waautoreply

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks the GitHub release named "latest" and installs the APK inside it. */
object Updater {
    class Info(val code: Long, val assetApi: String)

    fun installedCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    private fun cfg(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun token(ctx: Context) = (cfg(ctx).getString("token", "") ?: "").trim()

    private fun open(url: String, token: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "wa-ai-reply")
        c.setRequestProperty("Accept", accept)
        // the token is only sent to api.github.com, never to the download host
        if (token.isNotEmpty() && URL(url).host == "api.github.com") c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun get(url: String, token: String, accept: String): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = open(u, token, accept)
            val code = c.responseCode
            if (code in 300..399) {
                u = c.getHeaderField("Location") ?: error("Redirect without location")
                c.disconnect()
            } else return c
        }
        error("Too many redirects")
    }

    fun check(ctx: Context): Info {
        val repo = (cfg(ctx).getString("repo", "") ?: "").trim()
            .removePrefix("https://github.com/").trim('/')
        if (!repo.contains("/")) error("Enter your repo as owner/name")
        val c = get("https://api.github.com/repos/$repo/releases/tags/latest", token(ctx), "application/vnd.github+json")
        if (c.responseCode != 200)
            error("GitHub replied ${c.responseCode}. Wrong repo name, no build published yet, or a private repo needs a token.")
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val code = Regex("versionCode=(\\d+)").find(j.optString("body"))?.groupValues?.get(1)?.toLongOrNull()
            ?: error("Release has no version info")
        val assets = j.getJSONArray("assets")
        if (assets.length() == 0) error("Release has no APK yet")
        return Info(code, assets.getJSONObject(0).getString("url"))
    }

    fun download(ctx: Context, info: Info, onProgress: (Int) -> Unit): File {
        val c = get(info.assetApi, token(ctx), "application/octet-stream")
        if (c.responseCode != 200) error("Download failed (${c.responseCode})")
        val out = File(ctx.cacheDir, "update.apk")
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        }
        return out
    }

    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { i ->
                s.openWrite("update.apk", 0, apk.length()).use { o ->
                    i.copyTo(o)
                    s.fsync(o)
                }
            }
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            s.commit(pending.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> DebugLog.add(ctx, "Update installed")
            else -> DebugLog.add(ctx, "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
