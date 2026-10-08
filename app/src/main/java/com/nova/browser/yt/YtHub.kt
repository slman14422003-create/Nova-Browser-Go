package com.nova.browser

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * حقن سكربت يوتيوب (assets/yt.js) في الـ WebView المخصّص ليوتيوب فقط (YtWeb.kt).
 *   - YtWeb   : إنشاء الـ WebView المنفصل وعميله ودورة حياته
 *   - YtHub   : جسر الوسائط + حقن yt.js مرة واحدة قبل سكربتات الصفحة (هذا الملف)
 *   - YtMedia / YtBridge / YtLog : إشعار الوسائط والخلفية والتشخيص
 *   - YtDownload : تنزيل الفيديو/الصوت
 * لا واجهة أصلية ليوتيوب: الصفحة تُعرض كما هي، والتطبيق يضيف إصلاحات وتشغيلاً أذكى فقط.
 */
object YtHub {
    private val docStart by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
    private val origins get() = YtBridge.origins

    private val raw: String? by lazy { runCatching { Perf.asset("yt.js") }.getOrNull() }

    // النص النهائي بعد استبدال الإعدادات؛ يُعاد حسابه فقط عند تغيّر إحداها
    private var built: String? = null
    private var builtKey = ""

    private val ccSize = arrayOf("s", "m", "l", "xl")
    private val ccBg = arrayOf("glass", "solid", "none")
    private val ccPos = arrayOf("b", "m", "t")

    fun ready() = raw != null

    private fun script(): String? {
        val r = raw ?: return null
        val key = "${Prefs.ytBg}|${Prefs.ytResume}|${Prefs.ytKeepRate}|${Prefs.ytHold2x}|${Prefs.ytCcSize}|${Prefs.ytCcBg}|${Prefs.ytCcPos}|${Prefs.ytNoShorts}"
        if (built == null || builtKey != key) {
            built = r.replace("__BG__", Prefs.ytBg.toString())
                .replace("__PLAY__", "{\"resume\":${Prefs.ytResume},\"keep\":${Prefs.ytKeepRate},\"hold\":${Prefs.ytHold2x}}")
                .replace("__CC__", "{\"size\":\"${ccSize[Prefs.ytCcSize.coerceIn(0, 3)]}\",\"bg\":\"${ccBg[Prefs.ytCcBg.coerceIn(0, 2)]}\",\"pos\":\"${ccPos[Prefs.ytCcPos.coerceIn(0, 2)]}\",\"off\":0}")
                .replace("__UI__", "{\"shorts\":${Prefs.ytNoShorts}}")
            builtKey = key
        }
        return built
    }

    private fun isYt(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return u.scheme == "https" && YtWeb.isYtHost(u.host)
    }

    /** يُستدعى عند إنشاء الـ WebView المخصّص: مستمع الرسائل ثم حقن السكربت قبل أي سكربت للصفحة. */
    fun install(wv: WebView, tab: BrowserTab, h: Handlers) {
        YtBridge.listen(wv, tab, h)
        val js = script() ?: return
        if (docStart) runCatching { WebViewCompat.addDocumentStartJavaScript(wv, js, origins) }
    }

    /** احتياطي للأجهزة بلا حقن مبكر. */
    fun onPageStart(wv: WebView, url: String) {
        if (docStart || !isYt(url)) return
        script()?.let { wv.evaluateJavascript(it, null) }
    }
}
