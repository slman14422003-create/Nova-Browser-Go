package com.nova.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.Collections
import java.util.WeakHashMap

/**
 * WebView مخصّص ليوتيوب فقط — منفصل تماماً عن بقية التطبيق.
 *
 * لماذا منفصل؟ يوتيوب يدير تخطيطه ومشغّله وتمريره بنفسه، وأي طبقة عامة فوقه (حجب الطلبات، تشويش البصمة،
 * pwa.js الذي يعدّل history وmatchMedia، render.js، السحب للتحديث) كانت تسبب التقطيع وإعادة التحميل وفقدان الحالة.
 * هنا لا يُركَّب شيء من ذلك: إعدادات ثابتة، عميل بسيط، وسكربت yt.js وحده.
 *
 * العزل:
 *   - التبويب يستخدم هذا الـ WebView فقط عندما يكون رابطه يوتيوب (BrowserTab.yt)، ويُبدَّل تلقائياً عند تغيّر نوع الوجهة (swapIfNeeded).
 *   - التنقل خارج يوتيوب (رابط في وصف فيديو مثلاً) يُفتح في تبويب جديد فتبقى صفحة يوتيوب وتشغيلها كما هما.
 *   - لا يُحرَّر (discard) أثناء التشغيل، وعند انهيار عملية العرض يُعاد إنشاؤه بنفس الرابط.
 */
object YtWeb {
    private val views: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())

    /** صحيح أثناء النافذة المنبثقة: كل عمليات الإنعاش/إعادة القياس المؤجّلة تتوقف كي لا تُفسد عرض المنبثقة (سبب التقطيع والصفحة الكاملة المصغّرة). */
    @Volatile var pip = false

    /** صفحات تبقى داخل الـ WebView المخصّص بجانب يوتيوب نفسه: الدخول بحساب Google والموافقة. */
    private val authHosts = setOf("accounts.google.com", "consent.google.com", "accounts.youtube.com", "consent.youtube.com")

    fun isYtHost(host: String?): Boolean {
        val h = host?.lowercase()?.removePrefix("www.") ?: return false
        return h == "youtube.com" || h.endsWith(".youtube.com") || h == "youtu.be"
    }

    fun isYtUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return (u.scheme == "https" || u.scheme == "http") && isYtHost(u.host)
    }

    private fun stays(u: Uri): Boolean = isYtHost(u.host) || (u.host?.lowercase() in authHosts)

    fun owns(v: WebView?): Boolean = v != null && views.contains(v)

    /** هل التبويب يشغّل يوتيوب الآن (ممنوع تحريره لتوفير الذاكرة)؟ */
    fun isLive(t: BrowserTab): Boolean = t.yt && (t.ytPlaying || YtMedia.owner === t)

    /**
     * يبدّل الـ WebView إن اختلف نوع الوجهة (يوتيوب ↔ عام). يعيد true إن بدأ التبديل؛ التحميل يتم عند إعادة الإنشاء
     * لأن الواجهة تنشئ الـ WebView المناسب من رابط التبويب (انظر MainActivity). آمن للاستدعاء من داخل نداءات الـ WebView عبر post.
     */
    fun swapIfNeeded(tab: BrowserTab, url: String): Boolean {
        val w = tab.webView ?: return false
        if (isYtUrl(url) == tab.yt) return false
        views.remove(w)
        (w.parent as? ViewGroup)?.removeView(w)
        runCatching { w.stopLoading(); w.destroy() }
        YtMedia.tabClosed(tab)
        tab.url = url; tab.saved = null; tab.webView = null; tab.loading = true; tab.progress = 0f
        tab.epoch++
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: Context, tab: BrowserTab, h: Handlers): WebView = WebView(ctx).apply {
        views.add(this)
        tab.yt = true
        with(settings) {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false; allowContentAccess = false
            setSupportZoom(false); builtInZoomControls = false; displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            Wv.safeBrowsing(this)
            setOffscreenPreRaster(false)
            textZoom = 100                       // حجم الخط العام لا يُطبَّق: يكسر تخطيط يوتيوب
            loadsImagesAutomatically = true; blockNetworkImage = false   // توفير البيانات لا يُطبَّق: الصور المصغّرة جزء من الواجهة
        }
        applyUa(this, tab.desktop)
        // يوتيوب له وضعه الداكن الخاص؛ التعتيم الخوارزمي يشوّه الصور المصغّرة
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false) }
        isScrollbarFadingEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
        Wv.autofill(this, false)
        WebSupport.configure(this)               // أولوية العملية + مراقبة التجمّد + إزالة X-Requested-With
        YtHub.install(this, tab, h)              // جسر الوسائط + yt.js
        CookieManager.getInstance().setAcceptCookie(true)
        // الدخول بحساب Google وموافقات يوتيوب تحتاج كوكيز بين نطاقات جوجل ويوتيوب؛ هذا الـ WebView لا يفتح إلا هذه النطاقات
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        setDownloadListener { u, ua, cd, mime, _ -> h.onDownload(u, ua, cd, mime, this.url) }
        val client = Client(tab, h)
        webViewClient = client
        setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN) client.lastTouch = android.os.SystemClock.uptimeMillis(); false }
        webChromeClient = Chrome(ctx, tab, h)
        // تسخين DNS لنطاقات التشغيل قبل أول طلب
        WebSupport.prefetchDns("m.youtube.com"); WebSupport.prefetchDns("i.ytimg.com"); WebSupport.prefetchDns("www.gstatic.com")
    }

    private class Client(val tab: BrowserTab, val h: Handlers) : WebViewClient() {
        @Volatile var lastTouch = 0L   // أندرويد 6: تقدير «بلمسة مستخدم» من آخر لمسة
        override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
            tab.loading = true; tab.url = u; tab.shieldHost = Shield.hostFor(u)
            YtHub.onPageStart(v, u); YtMedia.pageChanged(tab, u)
        }
        override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) {
            // يوتيوب صفحة أحادية (SPA): التنقل بين الفيديوهات لا يستدعي onPageStarted
            if (!u.startsWith("http")) return
            tab.url = u; tab.canBack = v.canGoBack(); tab.canForward = v.canGoForward(); YtMedia.pageChanged(tab, u)
        }
        override fun onPageFinished(v: WebView, u: String) {
            tab.loading = false; tab.url = u
            tab.canBack = v.canGoBack(); tab.canForward = v.canGoForward()
            Perf.flushCookies()
            Library.visit(u, v.title)
        }
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = nav(v, r.url, r.isForMainFrame, r.hasGesture())

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean =
            nav(v, Uri.parse(url), true, android.os.SystemClock.uptimeMillis() - lastTouch < 1500)

        private fun nav(v: WebView, u: Uri, mainFrame: Boolean, gesture: Boolean): Boolean {
            return when (u.scheme) {
                "http", "https" -> {
                    if (!mainFrame) false
                    else if (stays(u)) GoogleAccounts.isSignInUrl(u) && googleSignIn(v, h, u)
                    else {
                        // وجهة خارج يوتيوب: تبويب جديد بلمسة المستخدم فقط؛ التحويلات التلقائية تُتجاهل. صفحة يوتيوب لا تُمسّ
                        if (gesture && !Shield.isSpoofed(u)) h.openTab(Security.cleanUrl(u).toString())
                        true
                    }
                }
                null, "about", "data", "blob" -> false
                else -> true   // intent: / vnd.youtube: / market: … لا نفتح تطبيقات أخرى من هنا
            }
        }
        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
            if (!r.isForMainFrame) return
            val u = r.url.toString()
            v.loadDataWithBaseURL(u, errorHtml(u, e.description.toString()), "text/html", "UTF-8", u)
        }
        override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, e: SslError) {
            handler.cancel()   // لا نتجاوز أخطاء الشهادات أبداً
            Security.log(L("شهادة"), L("رُفض اتصال غير موثوق: ") + (e.url?.let { hostOf(it) }))
            val u = e.url ?: v.url ?: ""
            v.loadDataWithBaseURL(u, errorHtml(u, L("شهادة أمان الموقع غير صالحة — تم حظر الاتصال لحمايتك")), "text/html", "UTF-8", u)
        }
        override fun onSafeBrowsingHit(v: WebView, r: WebResourceRequest, threatType: Int, cb: SafeBrowsingResponse) {
            cb.backToSafety(true)
            Security.log("Safe Browsing", L("حُظر موقع خطير: ") + r.url.host)
        }
        override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
            // انهيار/قتل عملية العرض (غالباً ضغط ذاكرة): نُسقط هذا الـ WebView فقط ونعيد إنشاءه بنفس الرابط؛
            // «استئناف الفيديو» في yt.js يعيد المشاهد لقريب من نفس اللحظة
            YtLog.add("render process gone crash=" + d.didCrash())
            views.remove(v)
            (v.parent as? ViewGroup)?.removeView(v)
            runCatching { v.destroy() }
            YtMedia.tabClosed(tab)
            tab.webView = null; tab.loading = false
            tab.epoch++
            return true
        }
    }

    private class Chrome(val ctx: Context, val tab: BrowserTab, val h: Handlers) : WebChromeClient() {
        override fun onProgressChanged(v: WebView, p: Int) {
            val f = p / 100f   // كل 5% فقط لتقليل إعادة التركيب
            if (p == 0 || p == 100 || kotlin.math.abs(f - tab.progress) >= 0.05f) tab.progress = f
        }
        override fun onReceivedIcon(v: WebView, icon: Bitmap?) {
            val u = v.url ?: return
            if (icon != null && u.startsWith("http")) Favicons.put(hostOf(u), icon)
        }
        override fun onReceivedTitle(v: WebView, t: String?) { if (!t.isNullOrBlank()) tab.title = t }
        override fun onShowCustomView(view: View, cb: CustomViewCallback) = h.showCustom(view, cb)
        override fun onHideCustomView() = h.hideCustom()
        // يوتيوب لا يحتاج كاميرا/ميكروفون/موقع. الوحيد المسموح: معرّف الوسائط المحمية (DRM) لأفلام يوتيوب
        override fun onPermissionRequest(req: PermissionRequest) {
            val drm = req.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (drm.isNotEmpty() && isYtUrl(req.origin.toString())) req.grant(drm.toTypedArray()) else req.deny()
        }
        override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) { cb.invoke(origin, false, false) }
    }

    // ───────────── دورة الحياة: الخلفية والنافذة المنبثقة والعودة ─────────────

    /** يعلم الصفحة أنها في الخلفية/المنبثقة كي تفعّل حماية التشغيل (yt.js). */
    fun background(w: WebView?, on: Boolean) {
        w?.evaluateJavascript("window.__novaBg&&window.__novaBg($on)", null)
    }

    /**
     * بعد العودة من النافذة المنبثقة أو الخلفية قد يبقى سطح الـ WebView أسود/متجمّداً: نوقظ المؤقتات، نعيد التخطيط والرسم على
     * مراحل، نتراجع عن أنماط المنبثقة في الصفحة (آمن دائماً)، وفي منتصفها نعيد ربط السطح بإخفاء/إظهار قصير.
     */
    fun recover(w: WebView?, rebind: Boolean = false) {
        w ?: return
        for (d in longArrayOf(0L, 500L, 1200L)) w.postDelayed({
            if (pip) return@postDelayed   // دخل المستخدم المنبثقة قبل انتهاء الإنعاش: لا نتراجع عن أنماطها
            w.resumeTimers(); w.onResume(); w.requestLayout(); w.invalidate()
            w.evaluateJavascript("window.__novaPip&&window.__novaPip(false);window.dispatchEvent(new Event('resize'))", null)
            if (rebind && d == 500L && !YtMedia.playing && w.visibility == View.VISIBLE && w.isShown) {   // الإخفاء يفرّغ الفيديو الجاري: لا نفعله أثناء التشغيل
                w.visibility = View.INVISIBLE
                w.post { w.visibility = View.VISIBLE; w.invalidate() }
            }
        }, d)
    }

    /**
     * بعد الخروج من ملء الشاشة: الدوران لوضع الطول يحتاج وقتاً، ثم يُعاد قياس المشغّل وتُنعش طبقة الفيديو (yt.js → __novaRefit).
     * لا نُخفي الـ WebView هنا (الإخفاء يجعل يوتيوب يوقف الفيديو)؛ التنشيط يتم بإعادة التخطيط والرسم فقط.
     */
    fun afterFullscreen(w: WebView?) {
        w ?: return
        // سطح الفيديو يبقى أسود بعد إزالة عرض ملء الشاشة: نُعيد ربطه بإخفاء/إظهار قصير. الصفحة تعدّ نفسها ظاهرة (الحماية فعّالة)
        // فلا تُفرّغ المصدر ولا توقف التشغيل كما كان يحدث دون الحماية.
        w.postDelayed({
            if (!pip && owns(w) && w.isShown) { w.visibility = View.INVISIBLE; w.post { w.visibility = View.VISIBLE; w.invalidate() } }
        }, 200L)
        for (d in longArrayOf(0L, 350L, 800L, 1500L)) w.postDelayed({
            if (pip) return@postDelayed
            w.requestLayout(); w.invalidate()
            w.evaluateJavascript("window.__novaRefit&&window.__novaRefit(${d >= 800L})", null)
        }, d)
        heal(w)
    }

    /** يدخل ملء الشاشة: نخبر الصفحة أنها «ظاهرة» كي لا تُفرّغ مصدر الفيديو لحظة الدوران (سبب الشاشة السوداء). */
    fun onFullscreen(w: WebView?, on: Boolean) {
        w ?: return
        if (!Prefs.ytBg) return
        if (on) w.evaluateJavascript("window.__novaBg&&window.__novaBg(true)", null)
        else w.postDelayed({ background(w, false) }, 1300)   // الحماية تبقى فعّالة أثناء إعادة ربط السطح أدناه
    }

    /**
     * فحص ذاتي بعد الدوران/الخروج من ملء الشاشة: إن فقد المشغّل مصدره يُعاد تحميله من نفس الثانية، وإن بقي أسود فتُعاد الصفحة بنفس الموضع.
     * المراحل: 1.2ث (إنعاش)، 3ث (إعادة تحميل المشغّل)، 6ث (إعادة فتح الصفحة كحل أخير).
     */
    @Volatile private var healGen = 0
    fun heal(w: WebView?) {
        w ?: return
        val g = ++healGen   // استدعاءات متتابعة (دوران + خروج من ملء الشاشة) تُدمج في فحص واحد
        for ((i, d) in longArrayOf(1200L, 3000L, 6000L).withIndex()) w.postDelayed({
            if (g == healGen && !pip && owns(w)) w.evaluateJavascript("window.__novaHeal&&window.__novaHeal(${i})") { r -> if (r != null && r.length > 2) YtLog.add("heal#$i -> $r") }
        }, d)
    }

    /** بعد تدوير الشاشة: يوتيوب يترك مقاسات قديمة على الفيديو، فنعيد التخطيط ونطلب إعادة القياس على مراحل. */
    fun afterRotate(w: WebView?) {
        w ?: return
        if (pip) return
        for (d in longArrayOf(150L, 500L, 1000L, 1800L)) w.postDelayed({
            if (pip) return@postDelayed
            w.requestLayout(); w.invalidate()
            w.evaluateJavascript("window.__novaRefit&&window.__novaRefit(${d >= 1000L})", null)
        }, d)
        heal(w)
    }

    /** دخول/خروج النافذة المنبثقة. */
    fun onPip(w: WebView?, inPip: Boolean, fullscreen: Boolean) {
        pip = inPip
        w ?: return
        if (inPip) {
            w.resumeTimers(); w.onResume()
            // في ملء الشاشة المشغّل يملأ النافذة أصلاً؛ غير ذلك نُظهر الفيديو وحده
            w.evaluateJavascript((if (fullscreen) "" else "window.__novaPip&&window.__novaPip(true);") + "window.__novaBg&&window.__novaBg(true)", null)
        } else {
            background(w, false)
            recover(w, rebind = true)
        }
    }
}
