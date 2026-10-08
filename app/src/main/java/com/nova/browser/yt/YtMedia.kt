package com.nova.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL

/** سجل تشخيص تشغيل يوتيوب (يُعرض في الإعدادات ويمكن نسخه). */
object YtLog {
    private val lines = ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    @Synchronized fun add(m: String) {
        lines.addLast(fmt.format(java.util.Date()) + "  " + m)
        while (lines.size > 160) lines.removeFirst()
    }
    @Synchronized fun text(): String = lines.joinToString("\n")
    @Synchronized fun clear() = lines.clear()
}

/** حالة تشغيل يوتيوب الحالية + أوامر التحكم من إشعار الوسائط/شاشة القفل/Now Bar. */
object YtMedia {
    @Volatile var playing = false
    @Volatile var title = ""
    @Volatile var artist = ""
    @Volatile var art: Bitmap? = null
    @Volatile var pos = 0L
    @Volatile var dur = 0L
    var owner: BrowserTab? = null
    private var wvRef: WeakReference<WebView>? = null
    private var artUrl = ""

    fun control(a: String) {
        val w = wvRef?.get() ?: return
        w.post {
            // الصفحة قد تكون مجمّدة (onPause/pauseTimers) فنوقظها قبل تنفيذ الأمر
            if (a != "pause") { w.resumeTimers(); w.onResume() }
            w.evaluateJavascript("window.__novaYtCtl&&window.__novaYtCtl(${JSONObject.quote(a)})", null)
        }
    }

    fun update(ctx: Context, tab: BrowserTab, wv: WebView, o: JSONObject) {
        owner = tab; wvRef = WeakReference(wv)
        playing = o.optBoolean("playing"); pos = o.optLong("pos"); dur = o.optLong("dur")
        title = o.optString("title"); artist = o.optString("artist")
        val url = o.optString("art")
        if (url.isNotBlank() && url != artUrl && isTrustedArt(url)) {
            artUrl = url
            Thread {
                runCatching {
                    val c = URL(url).openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }
                    val bmp = c.getInputStream().use { BitmapFactory.decodeStream(it) }
                    if (bmp != null) {
                        val k = 512f / maxOf(bmp.width, bmp.height)
                        art = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
                        MediaService.instance?.refresh()
                    }
                }
            }.start()
        }
        val svc = MediaService.instance
        if (svc != null) svc.refresh()
        else if (playing && title.isNotBlank())
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MediaService::class.java)) }
    }

    // نجلب الصور من خوادم يوتيوب/جوجل فقط (لا نطلب روابط عشوائية جاءت من الصفحة)
    private fun isTrustedArt(u: String): Boolean {
        val h = runCatching { java.net.URI(u) }.getOrNull()?.takeIf { it.scheme == "https" }?.host ?: return false
        return h.endsWith("ytimg.com") || h.endsWith("ggpht.com") || h.endsWith("googleusercontent.com")
    }

    fun stop() { playing = false; owner = null; MediaService.instance?.shutdown() }
    fun pageChanged(tab: BrowserTab, url: String) { if (owner === tab && !isYtVideo(url)) stop() }
    fun tabClosed(tab: BrowserTab) { if (owner === tab) stop() }
}

/** جسر MEDIA في yt.js: يستقبل حالة التشغيل والعنوان والصورة. السكربت نفسه يحقنه YtHub. */
object YtBridge {
    val origins = setOf("https://m.youtube.com", "https://www.youtube.com", "https://music.youtube.com", "https://youtube.com")
    private val listenerOk by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }

    fun listen(wv: WebView, tab: BrowserTab, h: Handlers) {
        if (!listenerOk) return
        runCatching {
            WebViewCompat.addWebMessageListener(wv, "NovaYt", origins, WebViewCompat.WebMessageListener { view, message, _, isMain, _ ->
                if (!isMain) return@WebMessageListener
                val data = message.data ?: return@WebMessageListener
                if (data.length > 6000) return@WebMessageListener
                val o = runCatching { JSONObject(data) }.getOrNull() ?: return@WebMessageListener
                val type = o.optString("t")
                if (type == "log") { YtLog.add(o.optString("m")); return@WebMessageListener }
                if (type != "state") return@WebMessageListener
                tab.ytPlaying = o.optBoolean("playing")
                if (!isYtVideo(view.url ?: "")) return@WebMessageListener   // لا إشعار لمعاينات الصفحة الرئيسية
                YtMedia.update(view.context.applicationContext, tab, view, o)
                h.onYtState(tab)
            })
        }
    }
}
