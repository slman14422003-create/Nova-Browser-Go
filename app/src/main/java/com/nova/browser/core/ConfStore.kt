package com.nova.browser

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * مخزن الإعدادات: ملف واحد نصّي (nova.conf) داخل filesDir بصيغة key=value.
 * - يُقرأ كاملاً مرة واحدة عند فتح التطبيق إلى الذاكرة، وكل القراءات بعدها من الرام.
 * - الكتابة مؤجّلة (300ms) وذرّية (ملف مؤقت ثم إعادة تسمية) وخارج الخيط الرئيسي.
 * - filesDir لا يمسّه CacheCleaner، فالإعدادات تبقى بعد مسح الكاش.
 * - لا تُخزَّن هنا كلمات المرور (تبقى مشفّرة في Vault عبر Android Keystore).
 */
class ConfStore private constructor(private val file: File) {
    private val map = ConcurrentHashMap<String, String>()
    private var pending: ScheduledFuture<*>? = null

    fun contains(k: String) = map.containsKey(k)
    fun getInt(k: String, d: Int): Int = map[k]?.toIntOrNull() ?: d
    fun getBoolean(k: String, d: Boolean): Boolean = when (map[k]?.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> d }
    fun putInt(k: String, v: Int) { map[k] = v.toString(); scheduleSave() }
    fun putBoolean(k: String, v: Boolean) { map[k] = v.toString(); scheduleSave() }
    fun getString(k: String, d: String): String = map[k] ?: d
    /** القيمة سطر واحد (الملف key=value): نحوّل أي أسطر جديدة إلى مسافات. */
    fun putString(k: String, v: String) { map[k] = v.replace('\n', ' ').replace('\r', ' ').trim(); scheduleSave() }

    private fun load(): Boolean {
        if (!file.exists()) return false
        runCatching {
            file.forEachLine(Charsets.UTF_8) { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val i = line.indexOf('=')
                if (i > 0) map[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
        }
        return true
    }

    @Synchronized private fun scheduleSave() {
        pending?.cancel(false)
        pending = io.schedule({ saveNow() }, 300, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun saveNow() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("# Nova Browser configuration — key=value\n")
                map.toSortedMap().forEach { (k, v) -> w.write("$k=$v\n") }
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nova-conf").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        @Volatile private var inst: ConfStore? = null

        fun open(c: Context): ConfStore = inst ?: synchronized(this) {
            inst ?: ConfStore(File(c.applicationContext.filesDir, "nova.conf")).also { s ->
                if (!s.load()) {
                    // أول تشغيل بعد الترقية: ننقل الإعدادات القديمة من SharedPreferences إلى الملف
                    val old = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    for ((k, v) in old.all) {
                        if (k == "cleanpending") continue
                        when (v) { is Boolean -> s.map[k] = v.toString(); is Int -> s.map[k] = v.toString() }
                    }
                    s.saveNow()
                }
                inst = s
            }
        }
    }
}
