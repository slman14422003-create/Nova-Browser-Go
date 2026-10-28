package com.nova.browser

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * فيديو/صوت أي صفحة غير يوتيوب (يوتيوب له YtMedia):
 *  - يعرف التطبيق أن فيديو يعمل فيُفعَّل زر/دخول النافذة المنبثقة (PiP) بأزرار تحكم تعمل فعلاً؛
 *  - تشغيل واحد في كل مرة: بدء فيديو بصوت في تبويب يوقف غيره (تبويبات أخرى ويوتيوب) فلا تتعارض الأصوات؛
 *  - عند الخروج/إغلاق التبويب تُصفَّر الحالة كي لا تبقى أزرار تحكم لصفحة لم تعد موجودة.
 */
object WebMedia {
    private val listenerOk by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
    private val docStart by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
    private var script: String? = null

    /** التبويب صاحب آخر وسائط مسموعة. */
    @Volatile var owner: BrowserTab? = null
    @Volatile var playing = false
    @Volatile var video = false
    @Volatile var title = ""

    fun install(wv: WebView, tab: BrowserTab, h: Handlers) {
        if (!listenerOk || !docStart) return
        val js = script ?: runCatching { Perf.asset("media.js") }.getOrNull()?.also { script = it } ?: return
        runCatching {
            WebViewCompat.addWebMessageListener(wv, "NovaMedia", setOf("*"), WebViewCompat.WebMessageListener { _, message, _, isMain, _ ->
                val data = message.data ?: return@WebMessageListener
                if (data.length > 2000) return@WebMessageListener
                val o = runCatching { JSONObject(data) }.getOrNull() ?: return@WebMessageListener
                if (o.optString("t") != "state") return@WebMessageListener
                onState(tab, o, isMain, h)
            })
            WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*"))
        }
    }

    private fun onState(tab: BrowserTab, o: JSONObject, isMain: Boolean, h: Handlers) {
        if (o.optBoolean("gone")) {
            if (!isMain) return   // إطار إعلان/مضمَّن أُغلق: الفيديو الرئيسي قد يكون ما زال يعمل
            if (owner === tab) reset()
            tab.mediaPlaying = false; tab.mediaVideo = false
            h.onMedia(tab)
            return
        }
        val nowPlaying = o.optBoolean("playing")
        if (nowPlaying) {
            owner = tab; playing = true
            video = o.optBoolean("video"); title = o.optString("title")
            tab.mediaPlaying = true; tab.mediaVideo = video
            if (o.optBoolean("audible", true)) h.onMediaPlay(tab)
        } else if (owner === tab) {
            playing = false; tab.mediaPlaying = false   // نُبقي owner/video كي تعمل أزرار «تشغيل» في النافذة المنبثقة
        } else return
        h.onMedia(tab)
    }

    /** أمر تحكم لفيديو التبويب صاحب الوسائط: play/pause/back/fwd/seek:ms. */
    fun control(a: String) {
        val w = owner?.webView ?: return
        w.post {
            // الصفحة قد تكون مجمّدة (onPause/pauseTimers) فنوقظها؛ وقد تكون دُمّرت: لا نُسقط التطبيق
            runCatching {
                if (a != "pause") { w.resumeTimers(); w.onResume() }
                w.evaluateJavascript("window.__novaMediaCtl&&window.__novaMediaCtl(${JSONObject.quote(a)})", null)
            }
        }
    }

    /** يوقف كل وسائط صفحة (يُستدعى من الخيط الرئيسي). */
    fun pauseAll(w: WebView?) {
        if (w == null) return
        runCatching { w.evaluateJavascript("window.__novaMediaCtl&&window.__novaMediaCtl('pauseall')", null) }
    }

    fun reset() { owner = null; playing = false; video = false; title = "" }

    fun tabClosed(tab: BrowserTab) {
        if (owner === tab) reset()
        tab.mediaPlaying = false; tab.mediaVideo = false
    }
}
