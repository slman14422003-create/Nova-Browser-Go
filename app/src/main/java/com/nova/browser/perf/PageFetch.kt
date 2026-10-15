package com.nova.browser

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.WeakHashMap

object PageFetch {
    private val main = Handler(Looper.getMainLooper())
    private val stall = WeakHashMap<WebView, Runnable>()
    private val tried = WeakHashMap<WebView, Pair<String, Long>>()
    private val transient = setOf(WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO, WebViewClient.ERROR_HOST_LOOKUP)

    private fun recently(v: WebView, url: String): Boolean {
        val t = tried[v] ?: return false
        return t.first == url && SystemClock.elapsedRealtime() - t.second < 30_000L
    }

    private fun mark(v: WebView, url: String) { tried[v] = url to SystemClock.elapsedRealtime() }

    private fun cancel(v: WebView) { stall.remove(v)?.let { main.removeCallbacks(it) } }

    fun onStart(v: WebView, tab: BrowserTab, url: String) {
        cancel(v)
        if (!Prefs.boost || !url.startsWith("http")) return
        val r = Runnable {
            stall.remove(v)
            if (tab.webView === v && tab.loading && tab.progress < 0.1f && WebSupport.online && !recently(v, url)) {
                mark(v, url)
                runCatching { v.stopLoading(); v.loadUrl(url, Perf.privacyHeaders) }
            }
        }
        stall[v] = r
        main.postDelayed(r, 12_000)
    }

    fun onDone(v: WebView) {
        cancel(v)
        applyCache(v)
    }

    fun onError(v: WebView, url: String, code: Int): Boolean {
        if (!Prefs.boost || code !in transient || !WebSupport.online || recently(v, url)) return false
        mark(v, url)
        main.postDelayed({ runCatching { v.loadUrl(url, Perf.privacyHeaders) } }, 600)
        return true
    }

    fun applyCache(wv: WebView) {
        val s = wv.settings
        if (s.cacheMode == WebSettings.LOAD_NO_CACHE) return
        s.cacheMode = if (WebSupport.online) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
    }
}
