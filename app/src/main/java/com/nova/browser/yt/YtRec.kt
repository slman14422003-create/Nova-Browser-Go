package com.nova.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Fallback that needs no stream links: records the audio the page is playing (MediaRecorder on the page's video element)
 * and writes it to a file as it arrives. Real-time: a 4 minute song takes 4 minutes.
 */
object YtRec {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile var active = false; private set
    private var title = "audio"
    private var uri: Uri? = null
    private var os: OutputStream? = null
    private var mime = "audio/webm"
    private var bytes = 0L

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "audio" }

    /** Starts recording the video playing in the YouTube page. Returns false when there is no page to record. */
    fun begin(ctx: Context, name: String): Boolean {
        val w: WebView = YtSniff.view?.get() ?: return false
        if (active) return false
        title = clean(name)
        ui.post {
            runCatching {
                w.evaluateJavascript("window.__novaRecStart&&window.__novaRecStart()") { r ->
                    val res = (r ?: "").trim('"')
                    if (res != "ok") toast(ctx, "Recording not possible: " + res.ifBlank { "page not ready" })
                }
            }
        }
        return true
    }

    fun stop() {
        val w = YtSniff.view?.get() ?: return
        ui.post { runCatching { w.evaluateJavascript("window.__novaRecStop&&window.__novaRecStop()", null) } }
    }

    fun onMessage(app: Context, o: JSONObject) {
        when (o.optString("k")) {
            "start" -> io.execute {
                try {
                    mime = o.optString("mime").substringBefore(';').ifBlank { "audio/webm" }
                    val ext = if (mime.contains("mp4")) "m4a" else "webm"
                    val u = Storage.create(app, "$title [recorded].$ext", mime)
                    uri = u; bytes = 0L
                    os = app.contentResolver.openOutputStream(u)
                    active = os != null
                    ui.post { toast(app, "Recording started - keep the video playing") }
                } catch (e: Throwable) { YtLog.add("rec start failed: $e"); active = false; stop() }
            }
            "data" -> { val b = o.optString("b"); if (b.isNotEmpty() && active) io.execute {
                runCatching { val d = Base64.decode(b, Base64.DEFAULT); os?.write(d); bytes += d.size } 
            } }
            "end", "err" -> io.execute { finish(app, o.optString("k") == "end") }
        }
    }

    private fun finish(app: Context, ok: Boolean) {
        val u = uri
        runCatching { os?.flush(); os?.close() }
        os = null; uri = null; active = false
        if (u == null) return
        if (!ok || bytes <= 0L) {
            runCatching { Storage.delete(app, u) }
            ui.post { toast(app, "Recording failed") }
            return
        }
        runCatching {
            Storage.finish(app, u, mime)
            Downloader.addFinished(Storage.displayName(app, u) ?: "$title.webm", u, mime, bytes)
        }
        ui.post { toast(app, "Recording saved") }
    }
}
