package com.nova.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * يولّد PO token محلياً (بدون خادم): WebView مخفي بأصل youtube.com يشغّل BotGuard ثم يصدر توكناً مرتبطاً بـ visitorData (GVS)
 * وآخر مرتبطاً بمعرّف الفيديو (player). تُمرَّر إلى yt-dlp عبر --extractor-args.
 */
object PoToken {
    class Tokens(val vid: String, val visitor: String, val gvs: String, val player: String, val at: Long)

    private val lock = Any()
    @Volatile private var cache: Tokens? = null

    class Bridge(private val ok: (String) -> Unit, private val bad: (String) -> Unit) {
        @JavascriptInterface fun done(json: String) = ok(json)
        @JavascriptInterface fun fail(msg: String) = bad(msg)
    }

    fun get(app: Context, videoId: String): Tokens? = synchronized(lock) {
        cache?.let { if (it.vid == videoId && System.currentTimeMillis() - it.at < 20 * 60_000L) return it }
        val vd = visitorData() ?: run { YtLog.add("po-token: no visitor data"); return null }
        val out = mint(app, listOf(vd, videoId)) ?: return null
        if (out.size < 2) return null
        Tokens(videoId, vd, out[0], out[1], System.currentTimeMillis()).also { cache = it; YtLog.add("po-token: minted (" + out[0].length + "/" + out[1].length + " chars)") }
    }

    private fun mint(app: Context, ids: List<String>): List<String>? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<List<String>?>(null)
        val err = AtomicReference("")
        val holder = AtomicReference<WebView?>(null)
        val started = AtomicBoolean(false)
        val ui = Handler(Looper.getMainLooper())
        ui.post {
            try {
                val html = app.assets.open("potoken.html").bufferedReader().use { it.readText() }
                val wv = WebView(app)
                holder.set(wv)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.addJavascriptInterface(Bridge(
                    { j ->
                        runCatching {
                            val a = JSONArray(j)
                            result.set((0 until a.length()).map { a.getString(it) })
                        }.onFailure { err.set(it.toString()) }
                        latch.countDown()
                    },
                    { m -> err.set(m); latch.countDown() }
                ), "Android")
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String) {
                        if (started.compareAndSet(false, true)) v.evaluateJavascript("run(" + JSONArray(ids).toString() + ")", null)
                    }
                }
                wv.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "utf-8", null)
            } catch (e: Throwable) { err.set(e.toString()); latch.countDown() }
        }
        val finished = latch.await(40, TimeUnit.SECONDS)
        ui.post { runCatching { holder.get()?.stopLoading(); holder.get()?.destroy() } }
        if (!finished) YtLog.add("po-token: timeout")
        else if (result.get() == null) YtLog.add("po-token: failed " + err.get().take(160))
        return result.get()
    }

    private fun visitorData(): String? {
        YtSniff.visitor.takeIf { it.isNotBlank() }?.let { return it }
        val w = YtSniff.view?.get()
        if (w != null) {
            val latch = CountDownLatch(1)
            val res = AtomicReference("")
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    w.evaluateJavascript("(function(){try{return ytcfg.get('VISITOR_DATA')||''}catch(e){return ''}})()") { v ->
                        res.set(runCatching { JSONArray("[" + (v ?: "null") + "]").optString(0) }.getOrDefault("").takeIf { it != "null" }.orEmpty())
                        latch.countDown()
                    }
                }.onFailure { latch.countDown() }
            }
            latch.await(3, TimeUnit.SECONDS)
            if (res.get().isNotBlank()) return res.get()
        }
        return runCatching {
            val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20250101.00.00","hl":"en","gl":"US"}}}"""
            val req = okhttp3.Request.Builder().url("https://youtubei.googleapis.com/youtubei/v1/visitor_id?prettyPrint=false")
                .header("User-Agent", YT_UA).header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            Tls.apply(okhttp3.OkHttpClient.Builder()).connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { r ->
                    JSONObject(r.body.string()).optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }
                }
        }.getOrNull()
    }
}
