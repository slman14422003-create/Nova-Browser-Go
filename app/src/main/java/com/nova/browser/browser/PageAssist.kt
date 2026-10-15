package com.nova.browser

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Two page helpers injected before page scripts:
 *  - modern.js : polyfills for newer web APIs, so pages built for current browsers still run on an old WebView engine
 *  - assist.js : load organiser for weak devices (defers off-screen work, lazy frames, passive listeners, pauses hidden animations)
 */
object PageAssist {
    private val modern: String? by lazy { runCatching { Perf.asset("modern.js") }.getOrNull() }
    private val assist: String? by lazy { runCatching { Perf.asset("assist.js") }.getOrNull() }

    fun install(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        modern?.let { runCatching { WebViewCompat.addDocumentStartJavaScript(wv, it, setOf("*")) } }
        assist?.let { runCatching { WebViewCompat.addDocumentStartJavaScript(wv, "var __novaLow=" + LowEnd.on + ";" + it, setOf("*")) } }
    }
}
