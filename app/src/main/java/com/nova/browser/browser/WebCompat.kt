package com.nova.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

/**
 * طبقات دعم توافق الـ WebView (كل طبقة معزولة ومحمية بـ runCatching، فلا تُسقط الصفحة ولا التطبيق):
 *  1) ملفات تعريف المواقع: الخرائط تحتاج كوكيز الطرف الثالث وتعطيل التعتيم الخوارزمي (يُبطئ رسم الخريطة ويوهم بالتحميل اللانهائي).
 *  2) حارس التحميل: مؤشر «جارٍ التحميل» كان يبقى للأبد إن لم يصل onPageFinished (صفحات تبثّ طلبات بلا توقف كالخرائط).
 *  3) قاطع حلقة الانهيار: انهيار عملية العرض كان يُعيد إنشاء التبويب وتحميل نفس الصفحة فينهار ثانية بلا نهاية.
 *  4) روابط intent://: إن لم يوجد رابط بديل نستخرج الوجهة https من الرابط نفسه بدل ابتلاعه (كان يترك الخريطة معلّقة).
 *  5) دعم الفيديو: صورة تمهيدية شفافة (تمنع انهياراً في بعض إصدارات المحرك) + webcompat.js للإطارات المضمّنة وإعادة المحاولة.
 */
object WebCompat {
    private val main = Handler(Looper.getMainLooper())
    private val mapMode = WeakHashMap<WebView, Boolean>()
    private val watch = WeakHashMap<BrowserTab, Runnable>()

    // ---------- 1) ملفات تعريف المواقع ----------
    fun isMaps(url: String?): Boolean {
        val u = runCatching { Uri.parse(url ?: "") }.getOrNull() ?: return false
        val h = u.host?.lowercase() ?: return false
        val p = u.path ?: ""
        return h == "maps.google.com" || h == "maps.app.goo.gl" || h == "mapsengine.google.com" ||
            ((h.startsWith("www.google.") || h.startsWith("google.")) && p.startsWith("/maps")) ||
            (h == "goo.gl" && p.startsWith("/maps"))
    }

    /**
     * مواقع خرائط/تطبيقات ملء الشاشة: السحب لأسفل فيها يحرّك الخريطة داخل الصفحة بينما scrollY للـ WebView يبقى 0،
     * فكان «السحب للتحديث» يلتقط كل سحبة ويعيد تحميل الصفحة (وهذا سبب إعادة التحميل المتكررة عند تحريك الخريطة).
     */
    fun noPullRefresh(url: String?): Boolean {
        if (isMaps(url)) return true
        val u = runCatching { Uri.parse(url ?: "") }.getOrNull() ?: return false
        val h = (u.host ?: return false).lowercase().removePrefix("www.")
        val p = u.path ?: ""
        return h == "openstreetmap.org" || h == "waze.com" || h.endsWith(".waze.com") || h == "earth.google.com" ||
            h == "maps.apple.com" || h == "windy.com" || h == "wego.here.com" || (h == "bing.com" && p.startsWith("/maps"))
    }

    // ---------- كاشف حلقة إعادة التحميل ----------
    private class Hits { var n = 0; var t0 = 0L; var key = "" }
    private val loops = WeakHashMap<BrowserTab, Hits>()

    /**
     * 8 بدايات تحميل لنفس المسار خلال 12 ثانية = حلقة (تحويل متبادل بين الموقع والتطبيق أو تحديث متكرر).
     * عند ذلك نوقف التحميل ونعرض صفحة بزر «إعادة المحاولة» بدل حلقة لا تنتهي. يعيد true إن قطعنا الحلقة.
     */
    fun loopTrip(v: WebView, tab: BrowserTab, url: String): Boolean {
        if (!url.startsWith("http")) return false
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val key = (u.host ?: "") + (u.path ?: "")
        val now = SystemClock.elapsedRealtime()
        val h = loops.getOrPut(tab) { Hits() }
        if (h.key != key || now - h.t0 > 12_000) { h.key = key; h.n = 0; h.t0 = now }
        h.n++
        if (h.n < 8) return false
        h.n = 0; h.t0 = now
        v.post {
            runCatching { v.stopLoading() }
            tab.loading = false
            runCatching { v.loadDataWithBaseURL(url, errorHtml(url, L("توقّفت إعادة تحميل متكررة لهذه الصفحة")), "text/html", "UTF-8", url) }
        }
        return true
    }

    /** يُستدعى مع بدء كل صفحة: يضبط الإعدادات حسب الموقع ويعيدها عند مغادرته (فقط عند تغيّر الحالة). */
    fun onPageStart(v: WebView, tab: BrowserTab, url: String) {
        val maps = isMaps(url)
        if (mapMode[v] != maps) {
            mapMode[v] = maps
            runCatching {
                CookieManager.getInstance().setAcceptThirdPartyCookies(v, maps || !Prefs.blockThirdCookies)
                v.settings.setGeolocationEnabled(true)
                if (maps && WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
                    androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(v.settings, false)
                else Perf.applyDark(v)
            }
        }
        arm(v, tab)
    }

    // ---------- 2) حارس التحميل ----------
    fun onProgress(tab: BrowserTab, p: Int) { if (p >= 100) tab.loading = false }

    private fun arm(v: WebView, tab: BrowserTab) {
        watch.remove(tab)?.let { main.removeCallbacks(it) }
        val r = Runnable {
            watch.remove(tab)
            // بعد 30 ثانية: إن بلغ التقدّم 70% فالصفحة قابلة للاستخدام (تطبيقات الصفحة الواحدة تبقى تبثّ طلبات بلا نهاية)
            if (tab.loading && tab.webView === v && tab.progress >= 0.7f) {
                tab.loading = false
                (v.parent as? androidx.swiperefreshlayout.widget.SwipeRefreshLayout)?.isRefreshing = false
            }
        }
        watch[tab] = r
        main.postDelayed(r, 30_000)
    }

    fun onPageDone(tab: BrowserTab) { watch.remove(tab)?.let { main.removeCallbacks(it) } }

    // ---------- 3) قاطع حلقة الانهيار ----------
    /** يعيد true إن كان إعادة الإنشاء التلقائية آمنة؛ false بعد 3 انهيارات خلال 90 ثانية (تُعرض صفحة بزر إعادة محاولة بدل الحلقة). */
    fun rendererGone(tab: BrowserTab): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - tab.lastCrash > 90_000) tab.crashes = 0
        tab.crashes++; tab.lastCrash = now
        return tab.crashes < 3
    }

    // ---------- 4) روابط intent:// ----------
    /** وجهة http(s) من رابط intent: الرابط البديل أولاً ثم بيانات الرابط نفسه (مثل intent://maps.google.com/...#Intent;scheme=https;end). */
    fun intentTarget(raw: String): String? = runCatching {
        val i = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
        val fb = i.getStringExtra("browser_fallback_url")
        val cand = if (fb != null && (fb.startsWith("https://") || fb.startsWith("http://"))) fb else i.dataString
        cand?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }.getOrNull()

    /**
     * هل الرابطان لنفس الصفحة (نفس النطاق والمسار بغض النظر عن www والاستعلام)؟
     * صفحة الخرائط تحاول «فتح التطبيق» عبر intent:// وبياناته هي رابط الخريطة نفسه؛ تحميله ثانية = حلقة إعادة تحميل لا تنتهي.
     */
    fun samePage(a: String?, b: String?): Boolean {
        val ua = runCatching { Uri.parse(a ?: "") }.getOrNull() ?: return false
        val ub = runCatching { Uri.parse(b ?: "") }.getOrNull() ?: return false
        fun host(u: Uri) = (u.host ?: "").lowercase().removePrefix("www.")
        fun path(u: Uri) = (u.path ?: "").trimEnd('/')
        return host(ua).isNotEmpty() && host(ua) == host(ub) && path(ua) == path(ub)
    }

    // ---------- 5) دعم الفيديو ----------
    private val poster: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    fun videoPoster(): Bitmap = poster

    private val js: String? by lazy { runCatching { Perf.asset("webcompat.js") }.getOrNull() }

    fun install(wv: WebView) {
        val s = js ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, s, setOf("*")) }
    }
}
