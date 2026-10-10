package com.nova.browser

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** نوع الصفحة من منظور «وضع التطبيق». */
enum class SiteKind { NONE, YT, YT_VIDEO, AI }

/** هوية الموقع المعروضة في الشريط العلوي. */
class SiteInfo(val kind: SiteKind, val name: String, val host: String)

/**
 * وضع PWA لمواقع الذكاء الاصطناعي + الشريط العلوي ليوتيوب:
 * - يكتشف نوع الصفحة (يوتيوب / فيديو / ذكاء اصطناعي) لعرض الشريط العلوي المناسب.
 * - يحقن pwa.js على مواقع الذكاء الاصطناعي فقط (محاكاة standalone، مظهر تطبيق). يوتيوب لا يُحقن فيه: له WebView مخصّص.
 */
object Pwa {
    const val BAR_H = UiLayout.BAR_DP   // dp: ارتفاع الشريط العلوي (يشمل خط التقدّم) — القيمة في UiLayout

    private val aiNames = mapOf(
        "gemini.google.com" to "Gemini", "chatgpt.com" to "ChatGPT", "chat.openai.com" to "ChatGPT",
        "claude.ai" to "Claude", "perplexity.ai" to "Perplexity", "copilot.microsoft.com" to "Copilot",
        "chat.deepseek.com" to "DeepSeek", "grok.com" to "Grok", "poe.com" to "Poe", "chat.mistral.ai" to "Le Chat"
    )

    /** النطاقات التي يُحقن فيها pwa.js (الدالة داخل السكربت تضيّق google.com على udm=50 فقط). */
    val origins: Set<String> = buildSet {
        add("https://www.google.com")   // يوتيوب خارج القائمة عمداً: له WebView مخصّص (YtWeb) بلا pwa.js
        aiNames.keys.forEach { add("https://$it") }
        add("https://www.perplexity.ai")
    }

    private val js: String? by lazy {
        runCatching { Perf.asset("pwa.js") }.getOrNull()
    }

    private val docStart by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
    private val listenerOk by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }

    /** يثبّت جسر NovaPwa (مشاركة أصلية + اهتزاز) ثم يحقن pwa.js مع إعدادات التشغيل. */
    fun install(wv: WebView) {
        val base = js ?: return
        if (!Prefs.pwaMode) return
        if (listenerOk) runCatching {
            WebViewCompat.addWebMessageListener(wv, "NovaPwa", origins, WebViewCompat.WebMessageListener { view, message, _, isMain, _ ->
                if (!isMain) return@WebMessageListener
                val data = message.data ?: return@WebMessageListener
                if (data.length > 4000) return@WebMessageListener
                val o = runCatching { org.json.JSONObject(data) }.getOrNull() ?: return@WebMessageListener
                when (o.optString("t")) {
                    "share" -> { val t = o.optString("text").take(2000); if (t.isNotBlank()) runCatching { shareText(view.context, t) } }
                    "hap" -> if (Prefs.pwaHaptics) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
            })
        }
        if (docStart) {
            val cfg = "{\"v\":2,\"anim\":${Prefs.smoothAnim && Adaptive.level == 0},\"hap\":${Prefs.pwaHaptics}}"
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, base.replace("__CFG__", cfg), origins) }
        }
    }

    /**
     * احتياطي للأجهزة التي لا تدعم الحقن قبل الصفحة (DOCUMENT_START_SCRIPT): كان pwa.js لا يعمل عليها إطلاقاً.
     * يُنفَّذ عند بدء الصفحة وللنطاقات المسموحة فقط؛ السكربت نفسه يحمي من التنفيذ المزدوج (__novaPwa).
     */
    fun onPageStart(wv: WebView, url: String) {
        if (docStart || !Prefs.pwaMode) return
        val base = js ?: return
        val o = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (o.scheme != "https" || "https://${o.host}" !in origins) return
        val cfg = "{\"v\":2,\"anim\":${Prefs.smoothAnim && Adaptive.level == 0},\"hap\":${Prefs.pwaHaptics}}"
        wv.evaluateJavascript(base.replace("__CFG__", cfg), null)
    }

    /** يمرّر الصفحة (أو أكبر حاوية تمرير فيها) إلى الأعلى بحركة ناعمة — عند الضغط على اسم الموقع في الشريط. */
    fun scrollTop(wv: WebView?) { wv?.evaluateJavascript("window.__novaTop&&window.__novaTop()", null) }

    @Volatile private var lastUrl: String? = null
    @Volatile private var lastInfo: SiteInfo? = null

    /** معلومات الموقع أو null إن لم يكن ضمن وضع التطبيق. مُخزَّنة لآخر رابط لأنها تُستدعى مع كل حركة لمس. */
    fun info(url: String?): SiteInfo? {
        if (url.isNullOrEmpty()) return null
        if (url == lastUrl) return lastInfo
        val r = compute(url)
        lastUrl = url; lastInfo = r
        return r
    }

    fun kind(url: String?): SiteKind = info(url)?.kind ?: SiteKind.NONE

    private fun compute(url: String): SiteInfo? {
        val p = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (p.scheme != "https") return null
        val raw = (p.host ?: return null).lowercase()
        val h = raw.removePrefix("www.")
        if (h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com")) {
            val music = h == "music.youtube.com"
            return SiteInfo(if (isYtVideo(url)) SiteKind.YT_VIDEO else SiteKind.YT, if (music) "YouTube Music" else "YouTube", raw)
        }
        aiNames[h]?.let { return SiteInfo(SiteKind.AI, it, raw) }
        if (h.startsWith("google.") && (p.getQueryParameter("udm") == "50" || (p.path ?: "").startsWith("/ai")))
            return SiteInfo(SiteKind.AI, "Google AI Mode", raw)
        return null
    }
}
