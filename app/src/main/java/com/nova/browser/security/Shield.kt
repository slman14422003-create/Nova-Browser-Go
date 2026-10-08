package com.nova.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * الحماية الفورية (Shield): طبقة تعمل أثناء التصفح على كل طلب وكل تنقّل وكل نافذة، وتصدّ:
 *  1) هجمات الشبكة المحلية: صفحة عامة تحاول الوصول إلى الجهاز نفسه أو الراوتر أو أجهزة الشبكة (127.0.0.1، 192.168.x، 10.x،
 *     بصيغها الرقمية المموّهة مثل 2130706433 و0x7f.1) — وهي أساس مسح المنافذ (port scan) وهجمات DNS rebinding على الخدمات المحلية.
 *  2) التعدين الخفي (cryptojacking): نطاقات التعدين المعروفة.
 *  3) التتبع من الطرف الثالث: بكسلات التتبع ونقاط التجميع التحليلية (إضافة إلى قائمة النطاقات في Perf).
 *  4) الروابط المخادعة: https://google.com@evil.com (بيانات دخول وهمية تُخفي الوجهة الحقيقية).
 *  5) حلقات التحويل التلقائي بلا لمس من المستخدم (تحويلات خبيثة/إعلانية).
 *  6) إغراق النوافذ (alert/confirm/prompt بلا نهاية) والتنزيلات التلقائية المتتابعة (drive-by download).
 * إضافة إلى سكربت shield.js الذي يحمي ما لا يراه الجانب الأصلي (WebSocket) وينظّف روابط التتبع داخل الصفحة.
 * كل فحص يعمل في الذاكرة بلا شبكة ولا تعطيل للخيط الرئيسي، وكل استدعاء محمي حتى لا ينهار التطبيق.
 */
object Shield {
    enum class Kind { LOCAL_NET, MINER, TRACKER, SPOOF, REDIRECT, DIALOG, DOWNLOAD }

    private val counts = Array(Kind.values().size) { AtomicInteger(0) }
    private var persisted = 0
    private var conf: ConfStore? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pending = false

    /** يتغيّر عند أي حدث ليُعاد رسم الواجهات التي تعرض العدّادات (مرة كل نصف ثانية كحدّ أقصى). */
    var version by mutableIntStateOf(0); private set

    fun init(c: Context) {
        val st = ConfStore.open(c.applicationContext)
        conf = st
        persisted = st.getInt("shield_total", 0)
    }

    fun count(k: Kind): Int = counts[k.ordinal].get()
    val session: Int get() { var n = 0; for (c in counts) n += c.get(); return n }
    val total: Int get() = persisted + session

    /** تسجيل حدث: عدّاد + سجل الأمان (التتبع الروتيني يُعدّ ولا يُسجَّل كي لا يمتلئ السجل). */
    private fun hit(k: Kind, detail: String) {
        counts[k.ordinal].incrementAndGet()
        if (k != Kind.TRACKER) Security.log(label(k), detail)
        val s = session
        if (s % 10 == 0 || k != Kind.TRACKER) conf?.putInt("shield_total", persisted + s)
        if (!pending) {
            pending = true
            main.postDelayed({ pending = false; version++ }, 500)
        }
    }

    private fun label(k: Kind): String = when (k) {
        Kind.LOCAL_NET -> L("هجوم شبكة محلية")
        Kind.MINER -> L("تعدين خفي")
        Kind.TRACKER -> L("تتبع")
        Kind.SPOOF -> L("رابط مخادع")
        Kind.REDIRECT -> L("تحويل تلقائي")
        Kind.DIALOG -> L("إغراق نوافذ")
        Kind.DOWNLOAD -> L("تنزيل تلقائي")
    }

    /** ملخص مقروء لسجل الأمان وشاشة الإعدادات. */
    fun summary(): String =
        L("الحماية الفورية: ") + (if (Prefs.shield) L("مفعّلة") else L("متوقفة")) + "\n" +
            L("هجمات الشبكة المحلية: ") + count(Kind.LOCAL_NET) + "\n" +
            L("تعدين خفي: ") + count(Kind.MINER) + "\n" +
            L("متتبعات الطرف الثالث: ") + count(Kind.TRACKER) + "\n" +
            L("روابط مخادعة: ") + count(Kind.SPOOF) + "\n" +
            L("تحويلات تلقائية: ") + count(Kind.REDIRECT) + "\n" +
            L("نوافذ وتنزيلات تلقائية: ") + (count(Kind.DIALOG) + count(Kind.DOWNLOAD))

    // ───────────────────────── عناوين الشبكة ─────────────────────────

    /** يحلّل عنوان IPv4 بكل الصيغ التي يقبلها المتصفح (عشري، ست عشري، ثماني، مختصر)؛ null إن لم يكن عنواناً رقمياً. */
    private fun ipv4(h: String): Long? {
        val parts = h.split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        val nums = LongArray(parts.size)
        for (i in parts.indices) {
            val p = parts[i]
            if (p.isEmpty()) return null
            val n: Long = try {
                when {
                    p.length > 2 && (p.startsWith("0x") || p.startsWith("0X")) -> p.substring(2).toLong(16)
                    p.length > 1 && p[0] == '0' -> p.toLong(8)
                    else -> p.toLong(10)
                }
            } catch (_: NumberFormatException) { return null }
            if (n < 0) return null
            nums[i] = n
        }
        val last = nums[nums.size - 1]
        for (i in 0 until nums.size - 1) if (nums[i] > 255) return null
        val limit = when (nums.size) { 1 -> 0xFFFFFFFFL; 2 -> 0xFFFFFFL; 3 -> 0xFFFFL; else -> 255L }
        if (last > limit) return null
        var v = last
        for (i in 0 until nums.size - 1) v = v or (nums[i] shl (24 - 8 * i))
        return v
    }

    private fun privateV4(v: Long): Boolean {
        val a = ((v shr 24) and 255).toInt(); val b = ((v shr 16) and 255).toInt()
        return a == 0 || a == 10 || a == 127 ||
            (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)          // CGNAT
    }

    /** هل العنوان جهاز محلي/خاص (localhost، IP خاص، نطاقات الشبكة الداخلية)؟ */
    fun isPrivateHost(raw: String?): Boolean {
        val h = raw?.trim()?.trim('[', ']')?.trimEnd('.')?.lowercase() ?: return false
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") ||
            h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        if (h.contains(':')) {   // IPv6
            if (h == "::1" || h == "::") return true
            if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true
            if (h.startsWith("::ffff:")) {
                val t = h.removePrefix("::ffff:")
                return ipv4(t)?.let { privateV4(it) } ?: false
            }
            return false
        }
        return ipv4(h)?.let { privateV4(it) } ?: false
    }

    // ───────────────────────── الطرف الأول/الثالث ─────────────────────────
    private val multiTld = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne")

    private fun site(host: String): String {
        val p = host.lowercase().trimEnd('.').split('.')
        if (p.size <= 2) return p.joinToString(".")
        val n = p.size
        return if (p[n - 1].length == 2 && p[n - 2] in multiTld) p.subList(n - 3, n).joinToString(".") else p.subList(n - 2, n).joinToString(".")
    }

    private fun isThirdParty(page: String, req: String): Boolean = site(page) != site(req)

    // ───────────────────────── قوائم التهديد ─────────────────────────
    private val minerHosts = setOf(
        "coinhive.com", "coin-hive.com", "authedmine.com", "crypto-loot.com", "cryptoloot.pro", "webminepool.com",
        "jsecoin.com", "coinimp.com", "minero.cc", "ppoi.org", "2giga.link", "webmine.pro", "papoto.com"
    )

    /** مسارات تتبع قوية فقط (بكسل/منارة/نقطة تجميع تحليلية) كي لا نكسر وظائف المواقع. تُطبَّق على طلبات الطرف الثالث فقط. */
    private val trackerPath = Regex(
        "(?i)(/(pixel|beacon|1x1)(\\.(gif|png|jpg|php|js))?([/?#]|$)|/__utm\\.gif|/(ga|analytics|fbevents|gtm)\\.js([?#]|$)|/gtag/js([?#]|$)|" +
            "/(j/|g/)?collect([?#]|$)|/track(ing)?([/?#]|$)|/telemetry([/?#]|$))"
    )

    private val verdicts = ConcurrentHashMap<String, Int>()   // 0 مسموح، 1 محلي، 2 تعدين
    private fun hostVerdict(page: String, host: String): Int {
        val key = "$page|$host"
        verdicts[key]?.let { return it }
        val r = when {
            !isPrivateHost(page) && page.isNotEmpty() && isPrivateHost(host) -> 1
            minerHosts.any { host == it || host.endsWith(".$it") } -> 2
            else -> 0
        }
        if (verdicts.size > 4000) verdicts.clear()
        verdicts[key] = r
        return r
    }

    private fun blockedResponse(status: Int): WebResourceResponse =
        if (status == 200) WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        else WebResourceResponse("text/plain", "UTF-8", status, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** صفحة الأعلى (الأعلى مستوى) لتبويب: يُحدَّث على الخيط الرئيسي ويُقرأ من خيط الشبكة. */
    fun hostFor(url: String?): String {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return ""
        return runCatching { Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()
    }

    /**
     * يُستدعى من shouldInterceptRequest (خيط خلفي). يعيد ردّاً فارغاً لحجب الطلب أو null للسماح.
     * طلبات الصفحة الرئيسية لا تُحجب هنا (التنقّل له فحصه في shouldOverrideUrlLoading).
     */
    fun intercept(pageHost: String, url: Uri, isMainFrame: Boolean): WebResourceResponse? {
        if (!Prefs.shield || isMainFrame) return null
        return try {
            val host = url.host?.lowercase() ?: return null
            when (hostVerdict(pageHost, host)) {
                1 -> { hit(Kind.LOCAL_NET, pageHost + " → " + host); return blockedResponse(403) }
                2 -> { hit(Kind.MINER, host); return blockedResponse(403) }
            }
            if (pageHost.isNotEmpty() && !isYtLike(pageHost) && isThirdParty(pageHost, host)) {
                val path = url.encodedPath ?: ""
                if (path.length > 1 && trackerPath.containsMatchIn(path)) {
                    hit(Kind.TRACKER, host)
                    Security.countBlocked()
                    return blockedResponse(200)
                }
            }
            null
        } catch (_: Throwable) { null }
    }

    /** مواقع جوجل/يوتيوب تستخدم نقاط إحصاء خاصة بها لتشغيل الفيديو؛ لا نطبّق عليها قواعد المسارات كي لا يتعطل التشغيل. */
    private fun isYtLike(h: String): Boolean =
        h == "youtube.com" || h.endsWith(".youtube.com") || h == "youtu.be" || h.endsWith(".google.com") || h == "google.com" ||
            h.endsWith(".googlevideo.com") || h.endsWith(".ytimg.com") || h.endsWith(".gstatic.com")

    // ───────────────────────── التنقّل ─────────────────────────

    /** رابط يحمل «بيانات دخول» تشبه نطاقاً (https://bank.com@evil.com) — أسلوب تصيّد شائع يُخفي الوجهة الحقيقية. */
    fun isSpoofed(u: Uri): Boolean {
        if (!Prefs.shield) return false
        val ui = runCatching { u.userInfo }.getOrNull() ?: return false
        if (ui.isEmpty()) return false
        val bad = ui.contains('.') || ui.length > 24
        if (bad) hit(Kind.SPOOF, (u.host ?: "?"))
        return bad
    }

    private class Nav { var n = 0; var t0 = 0L }
    private val navs = WeakHashMap<Any, Nav>()

    /**
     * يمنع حلقات التحويل التلقائي: أكثر من 10 تنقّلات رئيسية خلال 5 ثوانٍ بلا أي لمسة من المستخدم.
     * تسجيل الدخول عبر OAuth يستخدم 3–5 قفزات فقط فلا يتأثر.
     */
    fun navAllowed(key: Any, gesture: Boolean, host: String?): Boolean {
        if (!Prefs.shield) return true
        val now = SystemClock.elapsedRealtime()
        val s = synchronized(navs) { navs.getOrPut(key) { Nav() } }
        if (gesture || now - s.t0 > 5000) { s.n = 0; s.t0 = now; return true }
        s.n++
        if (s.n == 11) hit(Kind.REDIRECT, host ?: "?")
        return s.n <= 10
    }

    // ───────────────────────── النوافذ والتنزيلات ─────────────────────────
    private class Win { var n = 0; var t0 = 0L }
    private val dialogWin = ConcurrentHashMap<String, Win>()
    private val downloadWin = ConcurrentHashMap<String, Win>()

    private fun within(map: ConcurrentHashMap<String, Win>, key: String, windowMs: Long, max: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (map.size > 300) map.clear()
        val w = map.getOrPut(key) { Win() }
        synchronized(w) {
            if (now - w.t0 > windowMs) { w.n = 0; w.t0 = now }
            w.n++
            return w.n <= max
        }
    }

    /** أكثر من 3 نوافذ alert/confirm/prompt خلال 10 ثوانٍ من نفس الموقع = إغراق. */
    fun dialogAllowed(host: String): Boolean {
        if (!Prefs.shield) return true
        val ok = within(dialogWin, host, 10_000, 3)
        if (!ok && dialogWin[host]?.n == 4) hit(Kind.DIALOG, host)
        return ok
    }

    /** أكثر من 3 تنزيلات خلال 6 ثوانٍ من نفس الصفحة = تنزيل تلقائي (drive-by). */
    fun downloadAllowed(host: String): Boolean {
        if (!Prefs.shield) return true
        val ok = within(downloadWin, host, 6_000, 3)
        if (!ok && downloadWin[host]?.n == 4) hit(Kind.DOWNLOAD, host)
        return ok
    }

    // ───────────────────────── سكربت الصفحة ─────────────────────────
    private val docStart by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
    private val script: String? by lazy { runCatching { Perf.asset("shield.js") }.getOrNull() }

    /** يحقن shield.js قبل أي سكربت للصفحة (كل الإطارات). */
    fun install(wv: WebView) {
        val js = script ?: return
        if (Prefs.shield && docStart) runCatching { WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*")) }
    }

    /** احتياطي للأجهزة بلا حقن مبكر. */
    fun onPageStart(wv: WebView) {
        if (Prefs.shield && !docStart) script?.let { wv.evaluateJavascript(it, null) }
    }
}
