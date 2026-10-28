package com.nova.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

object YtDlp {
    private const val SCHEME = "ytdlp:"
    private val clients = listOf("default", "android_vr", "tv", "ios")
    @Volatile private var ready = false
    @Volatile private var triedUpdate = false

    fun owns(url: String) = url.startsWith(SCHEME)

    private fun ref(client: String, id: String) = "$SCHEME$client|$id"

    private fun split(r: String): Pair<String, String> {
        val s = r.removePrefix(SCHEME)
        return s.substringBefore('|') to s.substringAfter('|')
    }

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "video" }

    @Synchronized
    private fun ensure(app: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(app)
        ready = true
        maybeUpdate(app, false)
    }

    private fun maybeUpdate(app: Context, force: Boolean) {
        val sp = app.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("upd", 0L) < 2 * 86_400_000L) return
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(app) }
        sp.edit().putLong("upd", now).apply()
    }

    private fun request(app: Context, url: String, client: String): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("--no-warnings")
        if (client != "default") r.addOption("--extractor-args", "youtube:player_client=$client")
        val qjs = File(app.applicationInfo.nativeLibraryDir, "libqjs.so")
        if (qjs.exists()) r.addOption("--js-runtimes", "quickjs:" + qjs.absolutePath)
        return r
    }

    private class F(val id: String, val ext: String, val h: Int, val tbr: Double, val size: Long, val v: Boolean, val a: Boolean, val vcodec: String)

    private fun has(c: String) = c.isNotEmpty() && c != "none" && c != "null"

    private fun mb(b: Long) = if (b > 0) " \u2022 %.1f MB".format(b / 1048576.0) else ""

    private fun parse(j: JSONObject, client: String): YtInfo {
        val fs = j.optJSONArray("formats") ?: throw IOException("no formats")
        val all = ArrayList<F>()
        for (i in 0 until fs.length()) {
            val f = fs.optJSONObject(i) ?: continue
            val ext = f.optString("ext")
            val proto = f.optString("protocol")
            if (ext == "mhtml" || proto.contains("m3u8")) continue
            val vc = f.optString("vcodec", "none")
            val ac = f.optString("acodec", "none")
            val size = if (f.optLong("filesize") > 0) f.optLong("filesize") else f.optDouble("filesize_approx", 0.0).toLong()
            all.add(F(f.optString("format_id"), ext, f.optInt("height"), f.optDouble("tbr", 0.0), size, has(vc), has(ac), vc))
        }
        val bestA = all.filter { !it.v && it.a && it.ext == "m4a" }.maxByOrNull { it.tbr }
            ?: all.filter { !it.v && it.a }.maxByOrNull { it.tbr }
        val videos = ArrayList<YtOpt>()
        val heights = all.filter { it.v && it.h > 0 && it.ext == "mp4" }.map { it.h }.distinct().sortedDescending()
        for (h in heights) {
            val prog = all.filter { it.v && it.a && it.ext == "mp4" && it.h == h }.maxByOrNull { it.tbr }
            val adap = all.filter { it.v && !it.a && it.ext == "mp4" && it.h == h && it.vcodec.startsWith("avc1") }.maxByOrNull { it.tbr }
            if (prog != null) {
                videos.add(YtOpt(h.toString() + "p", "MP4" + mb(prog.size), ref(client, prog.id), "mp4", "video/mp4"))
            } else if (adap != null && bestA != null && bestA.ext == "m4a") {
                videos.add(YtOpt(h.toString() + "p", "MP4" + mb(adap.size + bestA.size), ref(client, adap.id), "mp4", "video/mp4", ref(client, bestA.id)))
            }
        }
        val audios = ArrayList<YtOpt>()
        if (bestA != null) {
            val mime = if (bestA.ext == "m4a") "audio/mp4" else "audio/webm"
            audios.add(YtOpt("Original audio", bestA.ext.uppercase() + mb(bestA.size), ref(client, bestA.id), bestA.ext, mime))
            for (fm in AudioFormats.all) audios.add(YtOpt(fm.label, fm.sub, ref(client, bestA.id), fm.ext, fm.mime, null, fm))
        }
        return YtInfo(j.optString("title", "YouTube video"), j.optString("uploader"), videos, audios, j.optLong("duration"))
    }

    private fun describe(e: Throwable): String {
        val c = e.cause
        return e.javaClass.name + ": " + (e.message ?: "") + (if (c != null && c !== e) " / " + c.javaClass.name + ": " + (c.message ?: "") else "")
    }

    fun info(ctx: Context, url: String): YtInfo {
        val app = ctx.applicationContext
        try { ensure(app) } catch (e: Throwable) {
            YtLog.add("yt-dlp init failed: " + describe(e))
            throw IOException("init: " + describe(e).take(400))
        }
        var last: Throwable? = null
        for (round in 0..1) {
            for (c in clients) {
                try {
                    val r = request(app, url, c)
                    r.addOption("--dump-single-json")
                    val out = YoutubeDL.getInstance().execute(request = r, processId = null, callback = null).out
                    val info = parse(JSONObject(out), c)
                    if (info.videos.isNotEmpty() || info.audios.isNotEmpty()) return info
                    last = IOException("no usable formats")
                } catch (e: Throwable) {
                    last = e
                }
            }
            if (round == 0 && !triedUpdate) {
                triedUpdate = true
                maybeUpdate(app, true)
            } else break
        }
        throw IOException(last?.let { describe(it).take(400) } ?: "extraction failed")
    }

    private fun fetch(app: Context, url: String, client: String, fmt: String, dir: File, tag: String, onProgress: (Float) -> Unit): File {
        val r = request(app, url, client)
        r.addOption("-f", fmt)
        r.addOption("-o", File(dir, "$tag.%(ext)s").absolutePath)
        r.addOption("--no-mtime")
        r.addOption("--retries", "5")
        r.addOption("--fragment-retries", "5")
        YoutubeDL.getInstance().execute(request = r, processId = null, callback = { p, _, _ -> onProgress((p / 100f).coerceIn(0f, 1f)) })
        return dir.listFiles()?.firstOrNull { it.name.startsWith("$tag.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?: throw IOException("no output file")
    }

    private fun store(app: Context, f: File, name: String, mime: String): Uri {
        val dst = Storage.create(app, name, mime)
        try {
            val os = app.contentResolver.openOutputStream(dst) ?: throw IOException("no output")
            os.use { o -> f.inputStream().use { it.copyTo(o, 64 * 1024) } }
            Storage.finish(app, dst, mime)
            return dst
        } catch (e: Throwable) {
            runCatching { Storage.delete(app, dst) }
            throw e
        }
    }

    private fun publish(app: Context, uri: Uri, fallback: String, mime: String) {
        val size = app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        Downloader.addFinished(Storage.displayName(app, uri) ?: fallback, uri, mime, size)
    }

    fun start(ctx: Context, info: YtInfo, o: YtOpt, pageUrl: String) {
        val app = ctx.applicationContext
        Downloader.init(app)
        val base = clean(info.title)
        val (client, fid) = split(o.url)
        val audioId = o.audioUrl?.let { split(it).second }
        val fmt = o.fmt
        val shown = if (fmt != null) fmt.ext else o.ext
        val ph = Downloader.addConverting("$base.$shown")
        Downloader.beginWork()
        Thread({
            val ui = Handler(Looper.getMainLooper())
            val tmp = File(app.cacheDir, "ytdlp/" + UUID.randomUUID()).apply { mkdirs() }
            try {
                ui.post { toast(app, "Download started") }
                val steps = if (audioId != null) 2f else 1f
                val main = fetch(app, pageUrl, client, fid, tmp, "m") { p -> ph.convPct = p / steps }
                if (fmt != null) {
                    val t = DlTask(UUID.randomUUID().toString(), "", "", "", "")
                    t.uri = Uri.fromFile(main)
                    AudioConvert.run(app, base, info.title, info.author, t, fmt)
                } else if (audioId != null) {
                    val aud = fetch(app, pageUrl, client, audioId, tmp, "a") { p -> ph.convPct = 0.5f + p / steps }
                    val dst = Storage.create(app, "$base.mp4", "video/mp4")
                    try {
                        Mux.mux(app, Uri.fromFile(main), Uri.fromFile(aud), dst)
                        Storage.finish(app, dst, "video/mp4")
                        publish(app, dst, "$base.mp4", "video/mp4")
                    } catch (e: Throwable) {
                        runCatching { Storage.delete(app, dst) }
                        throw e
                    }
                } else {
                    val mime = if (o.mime.startsWith("audio")) o.mime else "video/mp4"
                    val ext = if (o.mime.startsWith("audio")) o.ext else "mp4"
                    publish(app, store(app, main, "$base.$ext", mime), "$base.$ext", mime)
                }
            } catch (e: Throwable) {
                YtLog.add("yt-dlp failed: $e")
                ui.post { toast(app, "Download failed: " + (e.message ?: "").take(120)) }
            } finally {
                runCatching { tmp.deleteRecursively() }
                Downloader.endConverting(ph)
                Downloader.work.decrementAndGet()
            }
        }, "nova-ytdlp").start()
    }
}
