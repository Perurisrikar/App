package com.example.waautoreply

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(ctx: Context, msg: String) {
        val p = ctx.applicationContext.getSharedPreferences("dbg", Context.MODE_PRIVATE)
        val lines = (p.getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()
        lines.add("${fmt.format(Date())} $msg")
        while (lines.size > 40) lines.removeAt(0)
        p.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences("dbg", Context.MODE_PRIVATE).edit().remove("log").apply()
    }

    fun get(ctx: Context): String {
        val s = ctx.applicationContext.getSharedPreferences("dbg", Context.MODE_PRIVATE).getString("log", "") ?: ""
        return if (s.isBlank()) "(log empty)" else s.split("\n").reversed().joinToString("\n")
    }
}
