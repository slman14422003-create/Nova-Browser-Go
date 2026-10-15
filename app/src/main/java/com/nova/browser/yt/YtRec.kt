package com.nova.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.Executors

object YtRec {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    var phase by mutableIntStateOf(0); private set
    var startedAt by mutableLongStateOf(0L); private set
    var size by mutableLongStateOf(0L); private set

    @Volatile private var live = false
    private var app: Context? = null
    private var title = "audio"
    private var uri: Uri? = null
    private var os: OutputStream? = null
    private var mime = "audio/webm"
    private var bytes = 0L
    private var guard: Runnable? = null

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "audio" }

    private fun idOf(u: String?): String? = runCatching { YtFetch.canonical(u ?: "").substringAfter("v=") }.getOrNull()

    private fun setPhase(p: Int) { ui.post { phase = p } }

    fun begin(ctx: Context, name: String, url: String? = null): Boolean {
        val w: WebView = YtSniff.view?.get() ?: return false
        if (phase != 0) return false
        val page = idOf(w.url) ?: return false
        if (url != null && idOf(url)?.let { it != page } == true) return false
        app = ctx.applicationContext
        title = clean(name)
        phase = 1
        guard?.let { ui.removeCallbacks(it) }
        guard = Runnable { if (phase == 1) { phase = 0; stopPage() } }.also { ui.postDelayed(it, 10_000) }
        runCatching {
            w.evaluateJavascript("window.__novaRecStart&&window.__novaRecStart()") { r ->
                val res = (r ?: "").trim('"')
                if (res != "ok") {
                    phase = 0
                    toast(ctx, "Recording not possible: " + res.ifBlank { "page not ready" })
                }
            }
        }.onFailure { phase = 0 }
        return true
    }

    private fun stopPage() {
        val w = YtSniff.view?.get() ?: return
        runCatching { w.evaluateJavascript("window.__novaRecStop&&window.__novaRecStop()", null) }
    }

    fun stop() {
        if (YtSniff.view?.get() == null) {
            val a = app ?: return
            io.execute { finish(a, true) }
            return
        }
        ui.post { stopPage() }
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
                    live = os != null
                    if (live) ui.post { startedAt = System.currentTimeMillis(); size = 0L; phase = 2; toast(app, "Recording started - keep the video playing") }
                    else { runCatching { Storage.delete(app, u) }; uri = null; setPhase(0); ui.post { stopPage() } }
                } catch (e: Throwable) {
                    YtLog.add("rec start failed: $e")
                    live = false; uri = null; setPhase(0); ui.post { stopPage() }
                }
            }
            "data" -> {
                val b = o.optString("b")
                if (b.isNotEmpty() && live) io.execute {
                    runCatching {
                        val d = Base64.decode(b, Base64.DEFAULT)
                        os?.write(d)
                        bytes += d.size
                        val n = bytes
                        ui.post { size = n }
                    }
                }
            }
            "end", "err" -> io.execute { finish(app, o.optString("k") == "end") }
        }
    }

    private fun finish(ctx: Context, ok: Boolean) {
        val u = uri
        runCatching { os?.flush(); os?.close() }
        os = null; uri = null; live = false
        setPhase(0)
        if (u == null) return
        if (!ok || bytes <= 0L) {
            runCatching { Storage.delete(ctx, u) }
            ui.post { toast(ctx, "Recording failed") }
            return
        }
        runCatching {
            Storage.finish(ctx, u, mime)
            Downloader.addFinished(Storage.displayName(ctx, u) ?: "$title.webm", u, mime, bytes)
        }
        ui.post { toast(ctx, "Recording saved") }
    }
}
