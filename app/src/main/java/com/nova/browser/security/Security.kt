package com.nova.browser

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** الأمان والخصوصية: سجل أحداث، إزالة معرّفات التتبع، ترقية HTTPS، فحص التنزيلات وسلامة الجهاز. */
object Security {
    private var sp: SharedPreferences? = null
    private val session = AtomicInteger(0)      // متتبعات محجوبة في هذه الجلسة
    private var persisted = 0

    fun init(c: Context) {
        sp = c.getSharedPreferences("security", Context.MODE_PRIVATE)
        persisted = sp?.getInt("trackers", 0) ?: 0
    }

    // ---------- السجل ----------
    @Synchronized fun log(kind: String, detail: String) {
        val p = sp ?: return
        val t = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        val lines = (p.getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }
        val next = (lines + "$t  $kind: ${detail.take(90)}").takeLast(50)
        p.edit().putString("log", next.joinToString("\n")).apply()
    }

    fun countBlocked() {
        val n = session.incrementAndGet()
        if (n % 10 == 0) sp?.edit()?.putInt("trackers", persisted + n)?.apply()
    }

    fun summary(): String {
        val lines = (sp?.getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }
        val total = persisted + session.get()
        return (Shield.summary() + "\n\n" + L("متتبعات/إعلانات محجوبة: ") + total + L("\nتنبيهات مسجّلة: ") + (lines.size) + "\n\n") +
            (if (lines.isEmpty()) L("لا توجد أحداث أمنية.") else lines.reversed().joinToString("\n"))
    }

    fun clearLog() { sp?.edit()?.putString("log", "")?.apply() }

    // ---------- الروابط ----------
    private val trackingParams = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "mc_eid", "mc_cid",
        "igshid", "_ga", "_gl", "ref_src", "ref_url", "vero_id", "oly_enc_id", "oly_anon_id"
    )

    private fun isTracking(n: String): Boolean = n.lowercase().let { it in trackingParams || it.startsWith("utm_") }

    /** يزيل معرّفات التتبع من الرابط ويعيد نفس الكائن إن لم يتغير شيء. */
    fun cleanUrl(u: Uri): Uri {
        if (!Prefs.cleanUrls || !u.isHierarchical || u.isOpaque) return u
        val names = u.queryParameterNames
        if (names.none { isTracking(it) }) return u
        val b = u.buildUpon().clearQuery()
        names.filter { !isTracking(it) }.forEach { n -> u.getQueryParameters(n).forEach { b.appendQueryParameter(n, it) } }
        log(L("تتبع"), (L("أُزيلت معرّفات من ") + (u.host)))
        return b.build()
    }

    // ---------- التنزيلات ----------
    private val risky = Regex("""\.(apk|xapk|apks|exe|msi|bat|cmd|scr|jar|vbs|ps1|sh)\b""")
    fun isRiskyFile(url: String, contentDisposition: String?): Boolean =
        risky.containsMatchIn((url.substringBefore('?') + " " + (contentDisposition ?: "")).lowercase())

    // ---------- سلامة الجهاز والتطبيق ----------
    /** فحوصات إرشادية (heuristic) — لا تُعدّ إثباتاً قاطعاً. */
    fun deviceWarnings(c: Context): List<String> {
        val w = mutableListOf<String>()
        if (android.os.Debug.isDebuggerConnected()) w += L("مصحّح أخطاء (Debugger) متصل بالتطبيق")
        if (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) w += L("التطبيق في وضع التطوير (debuggable)")
        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk")
        if (su.any { File(it).exists() }) w += L("الجهاز يبدو مُجذَّراً (Root)")
        val tools = listOf("/data/local/tmp/frida-server", "/data/local/tmp/re.frida.server")
        if (tools.any { File(it).exists() }) w += L("أداة اعتراض/حقن (Frida) على الجهاز")
        return w
    }
}
