package com.nova.browser

import android.content.Context
import java.io.File

/** يحفظ آخر الأعطال في ملف داخلي (يُعرض في الإعدادات ← حول) ثم يمرّر العطل للنظام كالمعتاد. */
object CrashLog {
    private var file: File? = null

    fun install(c: Context) {
        if (file != null) return
        val f = File(c.applicationContext.filesDir, "crash.txt"); file = f
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                val head = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                    "  thread=" + t.name + "  v" + BuildConfig.VERSION_NAME
                val old = if (f.exists()) f.readText().take(12000) else ""
                f.writeText(head + "\n" + sw.toString().take(6000) + "\n--- yt log ---\n" + YtLog.text().takeLast(1500) + "\n\n" + old)
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun text(): String = runCatching { file?.takeIf { it.exists() }?.readText() ?: "" }.getOrDefault("")
    fun clear() { runCatching { file?.delete() } }
}
