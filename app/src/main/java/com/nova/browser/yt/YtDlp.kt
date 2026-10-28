package com.nova.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object YtDlp {
    private const val SCHEME = "ytdlp:"
    private val clients = listOf("android_vr", "tv", "ios", "default")
    @Volatile private var ready = false
    @Volatile private var triedUpdate = false
    @Volatile private var updater: Thread? = null

    fun owns(url: String) = url.startsWith(SCHEME)

    private fun log(m: String) = YtLog.add("yt-dlp: $m")

    private fun ref(client: String, id: String) = "$SCHEME$client|$id"

    private fun split(r: String): Pair<String, String> {
        val s = r.removePrefix(SCHEME)
        return s.substringBefore('|') to s.substringAfter('|')
    }

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "video" }

    @Synchronized
    private fun ensure(app: Context) {
        if (!ready) {
            YoutubeDL.getInstance().init(app)
            ready = true
            log("ready, qjs=" + File(app.applicationInfo.nativeLibraryDir, "libqjs.so").exists())
        }
        maybeUpdate(app, false)
    }

    /** قناة nightly تصلح تغييرات يوتيوب قبل stable؛ اسم الـ API يختلف بين إصدارات المكتبة فيُبحث عنه بالانعكاس (وإلا stable). */
    private fun nightlyChannel(): Any? = runCatching {
        val cls = Class.forName("com.yausername.youtubedl_android.YoutubeDL\$UpdateChannel")
        cls.declaredClasses.firstOrNull { it.simpleName == "NIGHTLY" }?.getField("INSTANCE")?.get(null)
            ?: cls.fields.firstOrNull { it.name.trimStart('_') == "NIGHTLY" }?.get(null)
            ?: cls.getField("Companion").get(null).let { c ->
                c.javaClass.methods.firstOrNull { it.name.contains("NIGHTLY", true) && it.parameterCount == 0 }?.invoke(c)
            }
    }.getOrNull()

    private fun runUpdate(app: Context): String {
        val yt = YoutubeDL.getInstance()
        nightlyChannel()?.let { ch ->
            try {
                val m = yt.javaClass.methods.first { it.name == "updateYoutubeDL" && it.parameterCount == 2 }
                return "nightly: " + m.invoke(yt, app, ch)
            } catch (e: Throwable) { log("nightly update failed: " + brief(e.cause ?: e)) }
        }
        return "stable: " + yt.updateYoutubeDL(app)
    }

    /** التحديث في خيط خلفي: كان يعمل داخل ensure() بلا مهلة، فإن كان GitHub بطيئاً/محجوباً بقي الاستخراج معلّقاً. */
    @Synchronized
    private fun maybeUpdate(app: Context, force: Boolean): Thread? {
        updater?.let { if (it.isAlive) return it }
        val sp = app.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && (now - sp.getLong("upd", 0L) < 2 * 86_400_000L || now - sp.getLong("try", 0L) < 30 * 60_000L)) return null
        sp.edit().putLong("try", now).apply()
        val th = Thread({
            try {
                val r = runUpdate(app)
                log("update ok: $r")
                sp.edit().putLong("upd", System.currentTimeMillis()).apply()
            } catch (e: Throwable) { log("update failed: " + brief(e)) }
        }, "nova-ytdlp-update").apply { isDaemon = true; start() }
        updater = th
        return th
    }

    private fun brief(e: Throwable): String {
        val msgs = generateSequence(e) { c -> c.cause?.takeIf { it !== c } }.mapNotNull { it.message }.joinToString("\n")
        val lines = msgs.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val key = lines.filter { it.startsWith("ERROR") || it.contains("HTTP Error") || it.contains("Sign in") }.distinct()
        return (if (key.isNotEmpty()) key else lines.take(3)).joinToString("\n").take(500).ifBlank { e.javaClass.simpleName }
    }

    /** يشغّل yt-dlp مع حارس: يقتل العملية إن لم يصل أي ناتج/تقدّم خلال المهلة (كان يعلق عند 0% بلا نهاية). */
    private fun exec(req: YoutubeDLRequest, startMs: Long, idleMs: Long, totalMs: Long, onProgress: ((Float) -> Unit)? = null): String {
        val pid = UUID.randomUUID().toString()
        val t0 = SystemClock.elapsedRealtime()
        val last = AtomicLong(t0)
        val got = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        val killed = AtomicBoolean(false)
        val dog = Thread {
            while (!done.get()) {
                try { Thread.sleep(1500) } catch (_: InterruptedException) { return@Thread }
                val now = SystemClock.elapsedRealtime()
                val limit = if (got.get()) idleMs else startMs
                if (now - last.get() > limit || now - t0 > totalMs) {
                    killed.set(true)
                    runCatching { YoutubeDL.getInstance().destroyProcessById(pid) }
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            return YoutubeDL.getInstance().execute(request = req, processId = pid, callback = { p, _, _ ->
                last.set(SystemClock.elapsedRealtime())
                if (p > 0f) got.set(true)
                onProgress?.invoke((p / 100f).coerceIn(0f, 1f))
            }).out
        } catch (e: Throwable) {
            if (killed.get()) throw IOException(Downloader.str(R.string.dl_err_stalled), e)
            throw e
        } finally { done.set(true); dog.interrupt() }
    }

    private fun request(app: Context, url: String, client: String, ipv4: Boolean = true): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("--no-warnings")
        r.addOption("--socket-timeout", "20")
        r.addOption("--extractor-retries", "1")
        if (ipv4) r.addOption("--force-ipv4")
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

    fun info(ctx: Context, url: String): YtInfo {
        val app = ctx.applicationContext
        Downloader.init(app)
        try { ensure(app) } catch (e: Throwable) {
            log("init failed: " + brief(e))
            throw IOException("init: " + brief(e))
        }
        updater?.takeIf { it.isAlive }?.let { runCatching { it.join(15_000) } }
        val deadline = SystemClock.elapsedRealtime() + 80_000L
        val errs = ArrayList<String>()
        for (round in 0..1) {
            for (c in clients) {
                if (SystemClock.elapsedRealtime() > deadline) break
                try {
                    val r = request(app, url, c)
                    r.addOption("--dump-single-json")
                    val out = exec(r, 55_000L, 55_000L, 55_000L)
                    val js = out.lineSequence().lastOrNull { it.trimStart().startsWith("{") } ?: throw IOException("no json")
                    val info = parse(JSONObject(js), c)
                    if (info.videos.isNotEmpty() || info.audios.isNotEmpty()) { log("info ok client=$c"); return info }
                    errs.add("$c: no usable formats")
                } catch (e: Throwable) {
                    val b = brief(e)
                    log("info $c failed: $b")
                    errs.add("$c: $b")
                }
            }
            if (round == 0 && !triedUpdate && SystemClock.elapsedRealtime() < deadline) {
                triedUpdate = true
                maybeUpdate(app, true)?.let { runCatching { it.join(40_000) } }
            } else break
        }
        // الاحتياط: استخراج الصفحة نفسها (روابط من مشغّل WebView تحمل PO token) ثم InnerTube/Piped
        val fb = runCatching { YtFetch.info(url) }
        fb.getOrNull()?.let { log("info via backup extractor"); return it }
        throw IOException(
            errs.distinct().take(4).joinToString("\n").take(600) + "\n— " +
                (fb.exceptionOrNull()?.message ?: "").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(160)
        )
    }

    private class Attempt(val client: String, val fmt: String, val ipv4: Boolean)

    private fun fetch(app: Context, url: String, a: Attempt, dir: File, tag: String, onProgress: (Float) -> Unit): File {
        val r = request(app, url, a.client, a.ipv4)
        r.addOption("-f", a.fmt)
        r.addOption("-o", File(dir, "$tag.%(ext)s").absolutePath)
        r.addOption("--no-mtime")
        r.addOption("--newline")
        r.addOption("--retries", "3")
        r.addOption("--fragment-retries", "3")
        exec(r, 120_000L, 45_000L, 6 * 3600_000L, onProgress)
        return dir.listFiles()?.firstOrNull { it.name.startsWith("$tag.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?: throw IOException("no output file")
    }

    private fun attempts(client: String, id: String, generic: String): List<Attempt> {
        val l = ArrayList<Attempt>()
        l.add(Attempt(client, id, true))
        clients.filter { it != client }.forEach { l.add(Attempt(it, generic, true)) }
        l.add(Attempt(client, generic, false))   // آخر محاولة بلا --force-ipv4 (شبكات IPv6 فقط)
        return l
    }

    private fun tryFetch(app: Context, url: String, tries: List<Attempt>, dir: File, tag: String, onProgress: (Float) -> Unit): File {
        val errs = ArrayList<String>()
        var all403 = true
        for (a in tries) {
            try {
                dir.listFiles()?.filter { it.name.startsWith("$tag.") }?.forEach { it.delete() }
                log("fetch client=${a.client} ipv4=${a.ipv4} fmt=${a.fmt}")
                return fetch(app, url, a, dir, tag, onProgress)
            } catch (e: Throwable) {
                val b = brief(e)
                log("fetch ${a.client} failed: $b")
                if (!b.contains("403")) all403 = false
                errs.add("${a.client}: $b")
            }
        }
        val head = if (all403) Downloader.str(R.string.dl_err_403) + "\n\n" else ""
        throw IOException(head + errs.distinct().take(4).joinToString("\n").take(600))
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

    private fun hOf(o: YtOpt) = Regex("(\\d+)").find(o.label)?.value?.toIntOrNull() ?: 0

    private fun pickBackup(fi: YtInfo, o: YtOpt): YtOpt? {
        val f = o.fmt
        if (f != null) return fi.audios.firstOrNull { it.fmt != null && it.fmt.label == f.label } ?: fi.audios.firstOrNull { it.fmt != null }
        if (o.mime.startsWith("audio")) return fi.audios.firstOrNull { it.fmt == null } ?: fi.audios.firstOrNull()
        val h = hOf(o)
        return fi.videos.firstOrNull { hOf(it) == h } ?: fi.videos.filter { hOf(it) in 1..h }.maxByOrNull { hOf(it) } ?: fi.videos.lastOrNull()
    }

    /** عند فشل yt-dlp (غالباً 403 بلا PO token): نفس الخيار عبر مسار الصفحة/WebView ثم محرك التنزيل المدمج. */
    private fun backup(app: Context, o: YtOpt, pageUrl: String, ph: DlTask): Boolean {
        ph.phase = Downloader.str(R.string.dl_phase_backup); ph.indet = true
        val fi = runCatching { YtFetch.info(pageUrl) }.onFailure { log("backup info failed: " + (it.message ?: "").take(160)) }.getOrNull() ?: return false
        val opt = pickBackup(fi, o) ?: return false
        log("backup engine: " + opt.label)
        Handler(Looper.getMainLooper()).post { runCatching { YtDownload.start(app, fi, opt, pageUrl) }.onFailure { log("backup start failed: $it") } }
        return true
    }

    fun start(ctx: Context, info: YtInfo, o: YtOpt, pageUrl: String) {
        val app = ctx.applicationContext
        Downloader.init(app)
        val base = clean(info.title)
        val (client, fid) = split(o.url)
        val audioId = o.audioUrl?.let { split(it).second }
        val fmt = o.fmt
        val shown = if (fmt != null) fmt.ext else o.ext
        val h = o.label.removeSuffix("p").toIntOrNull() ?: 4320
        val audioOnly = fmt != null || o.mime.startsWith("audio")
        val np = "[protocol!*=m3u8]"   // m3u8 يحتاج ffmpeg وهو غير موجود
        val gMain = if (audioOnly) "ba[ext=m4a]$np/ba$np" else if (audioId != null) "bv[ext=mp4][vcodec^=avc1][height<=$h]$np/bv[height<=$h]$np" else "b[ext=mp4][height<=$h]$np/b[height<=$h]$np/b$np"
        val ph = Downloader.addConverting("$base.$shown")
        ph.phase = Downloader.str(R.string.dl_phase_prepare); ph.indet = true
        Downloader.beginWork()
        Thread({
            val ui = Handler(Looper.getMainLooper())
            val tmp = File(app.cacheDir, "ytdlp/" + UUID.randomUUID()).apply { mkdirs() }
            var handedOff = false
            try {
                val steps = if (audioId != null || fmt != null) 2f else 1f
                val mainLabel = Downloader.str(if (audioOnly) R.string.dl_phase_audio else R.string.dl_phase_video)
                val main = tryFetch(app, pageUrl, attempts(client, fid, gMain), tmp, "m") { p ->
                    if (p > 0f) { ph.indet = false; ph.phase = mainLabel }
                    ph.convPct = p / steps
                }
                if (fmt != null) {
                    val t = DlTask(UUID.randomUUID().toString(), "", "", "", "")
                    t.uri = Uri.fromFile(main)
                    ph.phase = Downloader.str(R.string.conv_progress); ph.indet = false; ph.convPct = 0.5f
                    val ok = AudioConvert.run(app, base, info.title, info.author, t, fmt) { p -> ph.convPct = 0.5f + p / 2f }
                    if (!ok) {
                        val ext = main.extension.ifBlank { "m4a" }
                        val mime = if (ext == "m4a") "audio/mp4" else "audio/webm"
                        publish(app, store(app, main, "$base.$ext", mime), "$base.$ext", mime)
                        ui.post { toast(app, Downloader.str(R.string.dl_saved_original)) }
                    }
                } else if (audioId != null) {
                    val audLabel = Downloader.str(R.string.dl_phase_audio)
                    ph.phase = audLabel; ph.indet = true; ph.convPct = 0.5f
                    val aud = tryFetch(app, pageUrl, attempts(client, audioId, "ba[ext=m4a]$np/ba$np"), tmp, "a") { p ->
                        if (p > 0f) ph.indet = false
                        ph.convPct = 0.5f + p / steps
                    }
                    ph.phase = Downloader.str(R.string.dl_phase_merge); ph.indet = true
                    val dst = Storage.create(app, "$base.mp4", "video/mp4")
                    try {
                        Mux.mux(app, Uri.fromFile(main), Uri.fromFile(aud), dst)
                        Storage.finish(app, dst, "video/mp4")
                        publish(app, dst, "$base.mp4", "video/mp4")
                    } catch (e: Throwable) {
                        runCatching { Storage.delete(app, dst) }
                        throw IOException("merge failed: " + brief(e), e)
                    }
                } else {
                    val ext = if (audioOnly) main.extension else "mp4"
                    val mime = if (!audioOnly) "video/mp4" else if (ext == "m4a") "audio/mp4" else "audio/webm"
                    publish(app, store(app, main, "$base.$ext", mime), "$base.$ext", mime)
                }
                ui.post { toast(app, Downloader.str(R.string.conv_done)) }
            } catch (e: Throwable) {
                val msg = (e.message ?: e.javaClass.simpleName).take(700)
                YtLog.add("yt-dlp failed: $msg")
                handedOff = runCatching { backup(app, o, pageUrl, ph) }.getOrDefault(false)
                if (!handedOff) {
                    Downloader.addFailed("$base.$shown", msg, pageUrl) {
                        start(app, info, o, pageUrl)
                    }
                    ui.post { toast(app, Downloader.str(R.string.dl_failed_title)) }
                }
            } finally {
                runCatching { tmp.deleteRecursively() }
                Downloader.endConverting(ph)
                Downloader.work.decrementAndGet()
            }
        }, "nova-ytdlp").start()
    }
}
