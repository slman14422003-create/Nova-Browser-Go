package com.nova.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import java.lang.ref.WeakReference
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Captures the media requests the YouTube page player makes inside the WebView (valid session, PO token, signature),
 * so the same links can be downloaded by the app. No extraction server and no bot check involved.
 */
object YtSniff {
    private class Hit(val url: String, val mime: String, val itag: Int, val clen: Long, val dur: Long, val at: Long)
    private class Group(val key: String, val vid: String) { val hits = ConcurrentHashMap<Int, Hit>(); @Volatile var last = 0L }

    private val groups = ConcurrentHashMap<String, Group>()
    @Volatile var ua: String = ""
    @Volatile var view: WeakReference<WebView>? = null

    fun videoId(url: String?): String {
        val u = runCatching { Uri.parse(url ?: "") }.getOrNull() ?: return ""
        val seg = u.pathSegments
        val id = when {
            (u.host ?: "").removePrefix("www.") == "youtu.be" -> seg.firstOrNull()
            seg.size >= 2 && (seg[0] == "shorts" || seg[0] == "live" || seg[0] == "embed") -> seg[1]
            else -> u.getQueryParameter("v")
        }
        return if (id != null && Regex("[A-Za-z0-9_-]{11}").matches(id)) id else ""
    }

    private val QUALITY = mapOf(
        18 to "360p", 22 to "720p", 133 to "240p", 134 to "360p", 135 to "480p", 136 to "720p", 137 to "1080p",
        160 to "144p", 298 to "720p", 299 to "1080p", 264 to "1440p", 266 to "2160p"
    )
    private val MUXED = setOf(18, 22)
    private val AUDIO_KBPS = mapOf(139 to 48, 140 to 128, 141 to 256, 249 to 50, 250 to 70, 251 to 160)

    fun onRequest(r: WebResourceRequest, pageUrl: String? = null) {
        try {
            val u = r.url
            val host = u.host ?: return
            if (!host.endsWith(".googlevideo.com") || u.path != "/videoplayback") return
            val mime = u.getQueryParameter("mime") ?: return
            val itag = u.getQueryParameter("itag")?.toIntOrNull() ?: return
            val key = u.getQueryParameter("id") ?: return
            val b = u.buildUpon().clearQuery()
            for (name in u.queryParameterNames) {
                if (name == "range" || name == "rn" || name == "rbuf") continue
                for (v in u.getQueryParameters(name)) b.appendQueryParameter(name, v)
            }
            val now = System.currentTimeMillis()
            val g = groups.getOrPut(key) { Group(key, videoId(pageUrl)) }
            g.hits[itag] = Hit(
                b.build().toString(), mime, itag,
                u.getQueryParameter("clen")?.toLongOrNull() ?: -1L,
                u.getQueryParameter("dur")?.toDoubleOrNull()?.toLong() ?: 0L, now
            )
            g.last = now
            r.requestHeaders?.get("User-Agent")?.takeIf { it.isNotBlank() }?.let { ua = it }
            if (groups.size > 12) groups.entries.minByOrNull { it.value.last }?.let { groups.remove(it.key) }
        } catch (_: Exception) {}
    }

    /** Piped-shaped JSON for the newest playing video (captured within the last 15 minutes), or null. */
    private fun pick(vid: String): Group? =
        groups.values.filter { System.currentTimeMillis() - it.last < 15 * 60_000L && (vid.isEmpty() || it.vid.isEmpty() || it.vid == vid) }.maxByOrNull { it.last }

    private fun counts(vid: String): IntArray {
        val g = pick(vid) ?: return intArrayOf(0, 0)
        var v = 0; var a = 0
        for (h in g.hits.values) { if (h.mime.startsWith("audio/")) a++ else if (QUALITY.containsKey(h.itag)) v++ }
        return intArrayOf(v, a)
    }

    fun probe(vid: String, maxMs: Long = 9000L) {
        val w = view?.get() ?: return
        Handler(Looper.getMainLooper()).post {
            runCatching { w.evaluateJavascript("window.__novaProbe&&window.__novaProbe(" + org.json.JSONObject.quote(vid) + ")", null) }
        }
        val start = System.currentTimeMillis()
        var last = -1
        var changed = start
        while (System.currentTimeMillis() - start < maxMs) {
            try { Thread.sleep(300) } catch (_: InterruptedException) { return }
            val c = counts(vid)
            val n = c[0] * 100 + c[1]
            val now = System.currentTimeMillis()
            if (n != last) { last = n; changed = now }
            if (c[0] >= 3 && c[1] >= 1 && now - changed > 1500) break
            if (c[0] >= 1 && c[1] >= 1 && now - changed > 3500) break
            if (c[0] + c[1] == 0 && now - start > 6000) break
        }
        val c = counts(vid)
        YtLog.add("sniff vid=" + vid + " video=" + c[0] + " audio=" + c[1] + " groups=" + groups.size)
    }

    fun json(title: String, author: String, vid: String = ""): String? {
        val g = pick(vid) ?: return null
        val vs = JSONArray(); val au = JSONArray()
        var dur = 0L
        for (h in g.hits.values) {
            if (h.dur > dur) dur = h.dur
            when {
                h.mime.startsWith("audio/") -> au.put(JSONObject().put("url", h.url).put("mimeType", h.mime).put("bitrate", (AUDIO_KBPS[h.itag] ?: 128) * 1000))
                h.mime.startsWith("video/mp4") && QUALITY.containsKey(h.itag) ->
                    vs.put(JSONObject().put("url", h.url).put("mimeType", h.mime).put("quality", QUALITY[h.itag]).put("videoOnly", h.itag !in MUXED))
            }
        }
        if (au.length() == 0) {
            // Muxed progressive links (itag 18/22) carry an AAC track: use one as the audio source (M4A and MP3 conversions read its audio track).
            g.hits.values.filter { it.itag in MUXED }.minByOrNull { it.itag }?.let { h ->
                au.put(JSONObject().put("url", h.url).put("mimeType", "audio/mp4").put("bitrate", 128000))
            }
        }
        if (vs.length() == 0 && au.length() == 0) return null
        return JSONObject().put("title", title).put("uploader", author).put("duration", dur)
            .put("videoStreams", vs).put("audioStreams", au).toString()
    }
}
