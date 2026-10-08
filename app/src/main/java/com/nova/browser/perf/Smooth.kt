package com.nova.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/*
 * Smooth.kt — ملف السلاسة الموحّد (كان Perf.kt + Adaptive.kt + Motion.kt):
 *   Perf     : ضبط الـ WebView، حجب المتتبعات، الحماية من البصمة، حقن smooth.js
 *   Adaptive : التكيّف مع حرارة الجهاز وتوفير الطاقة
 *   NovaMotion / pressScale : مصدر واحد للحركات (يمر عبر Adaptive.ms)
 * الأسماء والواجهات كما كانت، فلا تغيير في بقية الملفات.
 */

/** تحسينات الأداء والخصوصية: حجب إعلانات/متتبعات، حماية من البصمة، سكربت السلاسة الموحّد، وضبط الـ WebView. */
object Perf {
    private lateinit var app: Context
    fun init(c: Context) { app = c.applicationContext }

    /** ترويسات الخصوصية للتحميلات التي يبدؤها التطبيق (Do Not Track + Global Privacy Control). */
    val privacyHeaders: Map<String, String>
        get() {
            val h = linkedMapOf("DNT" to "1", "Sec-GPC" to "1")
            val al = LangUtil.acceptLanguage(Prefs.siteLangCode())   // لغة المواقع المفضّلة
            if (al.isNotEmpty()) h["Accept-Language"] = al
            return h
        }

    /** قائمة النطاقات تُقرأ مرة واحدة من assets/blocklist.txt. */
    private val blocked: Set<String> by lazy {
        runCatching {
            app.assets.open("blocklist.txt").bufferedReader().useLines { ls ->
                ls.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet()
            }
        }.getOrDefault(emptySet())
    }

    /** بادئات نطاقات فرعية دالّة على إعلانات/تتبع. */
    private val trackerLabels = setOf("ads", "adserver", "adservice", "tracking", "tracker", "pixel", "telemetry", "beacon")

    // ذاكرة قرارات الحجب لكل نطاق: كل صفحة تطلب عشرات الموارد من نفس النطاقات
    private val verdicts = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun isBlocked(host: String?): Boolean {
        val h = host ?: return false
        verdicts[h]?.let { return it }
        val r = computeBlocked(h)
        if (verdicts.size > 4000) verdicts.clear()
        verdicts[h] = r
        return r
    }

    private var lastFlush = 0L
    /** حفظ الكوكيز على القرص بحدّ أقصى مرة كل 15 ثانية (الاستدعاء المتكرر يسبب تقطيعاً). */
    fun flushCookies(force: Boolean = false) {
        val n = android.os.SystemClock.elapsedRealtime()
        if (force || n - lastFlush > 15000) { lastFlush = n; android.webkit.CookieManager.getInstance().flush() }
    }

    private fun computeBlocked(host: String?): Boolean {
        val h0 = host?.lowercase() ?: return false
        if (h0.substringBefore('.') in trackerLabels && h0.contains('.')) return true
        var h = h0
        while (true) {
            if (h in blocked) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    private val empty get() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    /** يُستدعى من shouldInterceptRequest؛ يعيد null للسماح بالطلب. */
    fun intercept(url: Uri, isMainFrame: Boolean): WebResourceResponse? =
        if (Prefs.blockAds && !isMainFrame && isBlocked(url.host)) { Security.countBlocked(); empty } else null

    /** يضبط إعدادات الأداء لكل WebView جديد. */
    fun tune(wv: WebView) {
        val s = wv.settings
        s.cacheMode = WebSettings.LOAD_DEFAULT
        Wv.safeBrowsing(s)
        s.allowContentAccess = false
        s.allowFileAccess = false
        s.loadsImagesAutomatically = !Prefs.dataSaver
        s.blockNetworkImage = Prefs.dataSaver
        s.setOffscreenPreRaster(false)   // يوفر الذاكرة للتبويبات الخلفية
        s.textZoom = Prefs.zoomValues[Prefs.textZoom]
        applyDark(wv)
        wv.isScrollbarFadingEnabled = true
        wv.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        // ملاحظة: لا نستخدم LAYER_TYPE_HARDWARE؛ يضيف مخزناً خارج الشاشة ويزيد الذاكرة
    }

    // ---------- الحماية من البصمة ----------
    private val seed: Long = java.security.SecureRandom().nextLong() and 0xFFFFFFFFL

    private val fpTemplate: String? by lazy {
        runCatching { app.assets.open("privacy.js").bufferedReader().use { it.readText() } }.getOrNull()
    }
    private fun fpScript(): String? = fpTemplate?.replace("__SEED__", seed.toString())?.replace("__LANG__", Prefs.siteLangCode())

    /** الوضع الداكن للمواقع (يتبع وضع النظام الداكن). */
    fun applyDark(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            runCatching { androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, Prefs.siteDark) }
    }

    /** تطبيق حجم الخط والوضع الداكن على تبويب موجود. */
    fun applyDisplay(wv: WebView) { wv.settings.textZoom = Prefs.zoomValues[Prefs.textZoom]; applyDark(wv) }

    private val docStartSupported by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }

    /** يحقن سكربت الحماية قبل أي سكربت للصفحة (إن كان مدعوماً). */
    fun installPrivacy(wv: WebView) {
        val js = fpScript() ?: return
        if (Prefs.antiFingerprint && docStartSupported)
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*")) }
    }

    /** قراءة ملف نصي من assets. */
    fun asset(name: String): String = app.assets.open(name).bufferedReader().use { it.readText() }

    /** User-Agent الافتراضي للـ WebView: استدعاؤه مكلف (يهيّئ محرك الويب)، فيُحسب مرة واحدة فقط لا مع كل تبويب. */
    private var defUa: String? = null
    fun defaultUa(c: Context): String = defUa ?: WebSettings.getDefaultUserAgent(c.applicationContext).also { defUa = it }

    // ---------- سكربت السلاسة الموحّد (smooth.js = تحسين العرض + تحميل كسول + جلب مسبق + لافتات الكوكيز) ----------
    private val smoothJs: String? by lazy { runCatching { asset("smooth.js") }.getOrNull() }

    private fun smoothSrc(): String? {
        if (!(Prefs.fitPages || Prefs.lazyMedia || Prefs.boost || Prefs.popups)) return null
        return smoothJs?.replace("__FIT__", Prefs.fitPages.toString())?.replace("__LAZY__", Prefs.lazyMedia.toString())
            ?.replace("__BOOST__", Prefs.boost.toString())?.replace("__POP__", Prefs.popups.toString())
    }

    /** يحقن smooth.js قبل سكربتات الصفحة (مرة واحدة بدل سكربتين وتحميل كسول ثالث). */
    fun installSmooth(wv: WebView) {
        val js = smoothSrc() ?: return
        if (docStartSupported) runCatching { WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*")) }
    }

    /** احتياطي للأجهزة التي لا تدعم الحقن المبكر: يُنفَّذ عند بدء الصفحة. */
    fun onPageStart(wv: WebView) {
        if (Prefs.antiFingerprint && !docStartSupported) fpScript()?.let { wv.evaluateJavascript(it, null) }
        if (!docStartSupported) smoothSrc()?.let { wv.evaluateJavascript(it, null) }
    }

    /** تسخين محرك الويب وفحص الأمان مبكراً لتسريع أول تصفح. */
    fun warmUp(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 27) runCatching { WebView.startSafeBrowsing(ctx.applicationContext, null) }
        runCatching { defaultUa(ctx) }
    }
}

/**
 * تكيّف ذكي مع حرارة الجهاز والبطارية.
 * level: 0 = أداء كامل، 1 = مخفَّف (حرارة متوسطة أو توفير الطاقة)، 2 = أدنى استهلاك (حرارة عالية).
 * يُستخدم لتقصير الأنيميشن، تخفيض معدل التحديث إلى 60Hz، وتحرير التبويبات الخلفية.
 */
object Adaptive {
    var level by mutableIntStateOf(0); private set
    private var thermal = PowerManager.THERMAL_STATUS_NONE
    private var saver = false
    private var started = false

    private fun recompute() {
        level = if (!Prefs.adaptive) 0 else when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> 2
            thermal >= PowerManager.THERMAL_STATUS_MODERATE || saver -> 1
            else -> 0
        }
    }

    fun refresh() = recompute()

    fun init(c: Context) {
        if (started) return
        started = true
        val app = c.applicationContext
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        saver = pm.isPowerSaveMode
        // الحرارة (Thermal API) من أندرويد 10؛ قبله نكتفي بوضع توفير الطاقة
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            thermal = pm.currentThermalStatus
            runCatching { pm.addThermalStatusListener(ContextCompat.getMainExecutor(app)) { s -> thermal = s; recompute() } }
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, i: Intent?) { saver = pm.isPowerSaveMode; recompute() }
        }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        recompute()
    }

    /** مدة أنيميشن مناسبة للحالة الحالية (صفر عند الحرارة العالية أو إيقاف الحركة). */
    fun ms(base: Int): Int = when {
        !Prefs.smoothAnim -> 0
        level >= 2 -> 0
        level == 1 -> if (LowEnd.on) base * 4 / 10 else base * 6 / 10
        LowEnd.on -> (base * 5 / 10).coerceAtMost(140)   // أجهزة ضعيفة: حركة قصيرة وخفيفة بدل حذفها كلياً
        else -> base
    }

    /** عدد التبويبات الحيّة المسموح به: يقلّ كلما سخن الجهاز. */
    fun liveCap(base: Int): Int = when (level) {
        0 -> if (LowEnd.on) minOf(base, 2) else base
        1 -> (base - 1).coerceAtLeast(2)
        else -> 2
    }
}

/**
 * مصدر واحد لكل حركات التطبيق: منحنيات Material "Emphasized" (دخول سريع وهبوط ناعم)،
 * وكلها تمر عبر Adaptive.ms فتقصر أو تتوقف تلقائياً عند السخونة أو إيقاف الحركة.
 * الخصائص getters عمداً لأن Adaptive.level يتغير أثناء التشغيل.
 */
object NovaMotion {
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** لوحات ملء الشاشة (التنزيلات، الإعدادات، كلمات المرور، المكتبة). */
    val panelEnter: EnterTransition
        get() = slideInVertically(tween(Adaptive.ms(280), easing = emphasized)) { it / 12 } +
            fadeIn(tween(Adaptive.ms(200), easing = LinearOutSlowInEasing))
    val panelExit: ExitTransition
        get() = slideOutVertically(tween(Adaptive.ms(200), easing = emphasizedAccel)) { it / 14 } +
            fadeOut(tween(Adaptive.ms(150)))

    /** الشريط العلوي: ينزل من الأعلى. */
    val barEnter: EnterTransition
        get() = slideInVertically(tween(Adaptive.ms(240), easing = emphasized)) { -it } +
            fadeIn(tween(Adaptive.ms(180)))
    val barExit: ExitTransition
        get() = slideOutVertically(tween(Adaptive.ms(160), easing = emphasizedAccel)) { -it } +
            fadeOut(tween(Adaptive.ms(120)))
}

/**
 * تأثير ضغط ناعم (ينكمش قليلاً ثم يرتد بنابض). يعمل على طبقة الرسم فقط فلا يعيد التخطيط.
 * عند إيقاف الحركة لا يتحرك شيء.
 */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.94f): Modifier {
    val down by source.collectIsPressedAsState()
    val s: State<Float> = animateFloatAsState(
        if (down && Adaptive.ms(100) > 0) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press"
    )
    return this.graphicsLayer { scaleX = s.value; scaleY = s.value }
}
