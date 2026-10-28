package com.nova.browser

import android.view.View
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * GPU/CPU load reducer, level 0..2 (0 = off):
 *  1 = weak device or power saver / medium heat : no backdrop blur, no forced layers, off-screen autoplay videos paused
 *  2 = severe heat                              : additionally 30 fps animation loops, no CSS animations/filters/shadows
 * The level follows Adaptive (thermal/battery) and LowEnd, and is pushed to every page when it starts.
 */
object GpuLite {
    private val js: String? by lazy { runCatching { Perf.asset("gpu.js") }.getOrNull() }

    fun level(): Int {
        val base = if (LowEnd.on) 1 else 0
        return maxOf(base, Adaptive.level).coerceIn(0, 2)
    }

    fun install(wv: WebView) {
        val s = js ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, "window.__novaGpuInit=" + level() + ";" + s, setOf("*")) }
        wv.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    /** Called when a page starts and whenever the heat/battery level changes. */
    fun apply(wv: WebView) {
        val n = level()
        runCatching { wv.settings.setOffscreenPreRaster(n == 0 && !LowEnd.on) }
        runCatching { wv.evaluateJavascript("window.__novaGpuSet&&window.__novaGpuSet($n)", null) }
    }
}
