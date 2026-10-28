package com.nova.browser

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteOrder

class AudioFmt(
    val label: String, val sub: String, val ext: String, val mime: String,
    val container: String, val codecArgs: List<String>, val id3: Boolean = false
)

object AudioFormats {
    private val mp3: List<AudioFmt> = listOf(128, 192, 320).map { k ->
        AudioFmt("MP3 - $k kbps", "MP3 (LAME)", "mp3", "audio/mpeg", "mp3", listOf(k.toString()))
    }
    val all: List<AudioFmt> get() = mp3
}

object Mp3Lame {
    private const val PKG = "com.naman14.androidlame"

    val available: Boolean by lazy {
        runCatching { Class.forName("$PKG.LameBuilder"); Class.forName("$PKG.AndroidLame"); true }.getOrDefault(false)
    }

    class Enc(private val obj: Any) {
        private val cls = obj.javaClass
        private val encodeM = cls.getMethod("encode", ShortArray::class.java, ShortArray::class.java, Int::class.javaPrimitiveType, ByteArray::class.java)
        private val flushM = listOf("flush", "lameFlush").firstNotNullOfOrNull { n -> runCatching { cls.getMethod(n, ByteArray::class.java) }.getOrNull() }
            ?: throw NoSuchMethodException("flush")
        private val closeM = runCatching { cls.getMethod("close") }.getOrNull()

        fun encode(l: ShortArray, r: ShortArray, n: Int, out: ByteArray): Int = encodeM.invoke(obj, l, r, n, out) as Int
        fun flush(out: ByteArray): Int = flushM.invoke(obj, out) as Int
        fun close() { closeM?.invoke(obj) }
    }

    fun create(sampleRate: Int, channels: Int, kbps: Int): Enc {
        val bc = Class.forName("$PKG.LameBuilder")
        val b = bc.getConstructor().newInstance()
        val intT = Int::class.javaPrimitiveType
        fun set(name: String, v: Int) { bc.getMethod(name, intT).invoke(b, v) }
        set("setInSampleRate", sampleRate)
        set("setOutChannels", minOf(channels, 2))
        set("setOutBitrate", kbps)
        set("setOutSampleRate", sampleRate)
        runCatching { set("setQuality", if (LowEnd.on) 7 else 5) }
        return Enc(bc.getMethod("build").invoke(b)!!)
    }
}

object AudioConvert {
    fun toMp3(app: Context, t: DlTask, kbps: Int = 192) {
        val src = t.uri ?: return
        val base = t.name.substringBeforeLast('.').ifBlank { "audio" }
        val lame = Mp3Lame.available
        val ext = if (lame) "mp3" else "m4a"
        val mime = if (lame) "audio/mpeg" else "audio/mp4"
        val ph = Downloader.addConverting("$base.$ext")
        Downloader.beginWork()
        Thread({
            val ui = Handler(Looper.getMainLooper())
            var out: Uri? = null
            try {
                if (!lame) ui.post { toast(app, Downloader.str(R.string.conv_m4a)) }
                val dst = Storage.create(app, "$base.$ext", mime)
                out = dst
                val prog: (Float) -> Unit = { p -> ph.convPct = p }
                if (lame) {
                    val os = app.contentResolver.openOutputStream(dst) ?: throw IOException("no output")
                    os.use { encode(app, src, BufferedOutputStream(it, 64 * 1024), kbps, prog) }
                } else {
                    remux(app, src, dst, prog)
                }
                Storage.finish(app, dst, mime)
                val size = app.contentResolver.openFileDescriptor(dst, "r")?.use { it.statSize } ?: -1L
                Downloader.addFinished(Storage.displayName(app, dst) ?: "$base.$ext", dst, mime, size)
                ui.post { toast(app, Downloader.str(R.string.conv_done)) }
            } catch (e: Throwable) {
                out?.let { runCatching { Storage.delete(app, it) } }
                YtLog.add("convert failed: $e")
                ui.post { toast(app, Downloader.str(R.string.conv_fail)) }
            } finally {
                Downloader.endConverting(ph)
                Downloader.work.decrementAndGet()
            }
        }, "nova-mp3").start()
    }

    private fun remux(app: Context, src: Uri, out: Uri, onProgress: (Float) -> Unit) {
        val r = app.contentResolver
        r.openFileDescriptor(src, "r")!!.use { sfd ->
            r.openFileDescriptor(out, "rw")!!.use { ofd ->
                val ex = MediaExtractor()
                var mx: android.media.MediaMuxer? = null
                var started = false
                try {
                    ex.setDataSource(sfd.fileDescriptor)
                    val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    val f = ex.getTrackFormat(ti)
                    ex.selectTrack(ti)
                    val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                    val m = if (android.os.Build.VERSION.SDK_INT >= 26) android.media.MediaMuxer(ofd.fileDescriptor, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    else android.media.MediaMuxer(Storage.pathOf(out) ?: throw IOException("no path"), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    mx = m
                    val idx = m.addTrack(f)
                    m.start(); started = true
                    val buf = java.nio.ByteBuffer.allocate(1024 * 1024)
                    val bi = MediaCodec.BufferInfo()
                    var lastUi = 0L
                    while (true) {
                        buf.clear()
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        bi.set(0, n, ex.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        m.writeSampleData(idx, buf, bi)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (dur > 0 && now - lastUi > 200) { lastUi = now; onProgress((ex.sampleTime.toFloat() / dur).coerceIn(0f, 1f)) }
                        ex.advance()
                    }
                    onProgress(1f)
                } finally {
                    if (started) runCatching { mx?.stop() }
                    runCatching { mx?.release() }
                    runCatching { ex.release() }
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun run(app: Context, base: String, title: String, artist: String, src: DlTask, fmt: AudioFmt, onProgress: (Float) -> Unit = {}): Boolean {
        val ui = Handler(Looper.getMainLooper())
        val srcUri = src.uri ?: return false
        ui.post { toast(app, Downloader.str(R.string.conv_start)) }
        val r = app.contentResolver
        var out: Uri? = null
        try {
            val kbps = fmt.codecArgs.firstOrNull()?.toIntOrNull() ?: 128
            val lame = Mp3Lame.available
            val ext = if (lame) "mp3" else "m4a"
            val mime = if (lame) fmt.mime else "audio/mp4"
            val dst = Storage.create(app, "$base.$ext", mime)
            out = dst
            if (lame) {
                val os = r.openOutputStream(dst) ?: throw IOException("no output")
                os.use { encode(app, srcUri, BufferedOutputStream(it, 64 * 1024), kbps, onProgress) }
            } else remux(app, srcUri, dst, onProgress)
            Storage.finish(app, dst, mime)
            val size = r.openFileDescriptor(dst, "r")?.use { it.statSize } ?: -1L
            Downloader.addFinished(Storage.displayName(app, dst) ?: "$base.$ext", dst, mime, size)
            Downloader.removeOnMain(src, true)
            return true
        } catch (e: Throwable) {
            out?.let { runCatching { Storage.delete(app, it) } }
            YtLog.add("mp3 convert failed: $e")
            ui.post { toast(app, Downloader.str(R.string.conv_fail)) }
            return false
        }
    }

    private fun encode(app: Context, src: Uri, os: OutputStream, kbps: Int, onProgress: (Float) -> Unit = {}) {
        val ex = MediaExtractor()
        try {
            app.contentResolver.openFileDescriptor(src, "r")!!.use { pfd ->
                ex.setDataSource(pfd.fileDescriptor)
                val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                val fmt = ex.getTrackFormat(ti)
                ex.selectTrack(ti)
                val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                var lastUi = 0L
                val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                var enc: Mp3Lame.Enc? = null
                try {
                    c.configure(fmt, null, null, 0)
                    c.start()
                    var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val info = MediaCodec.BufferInfo()
                    var mp3 = ByteArray(0)
                    var inDone = false
                    var outDone = false
                    while (!outDone) {
                        if (!inDone) {
                            val ii = c.dequeueInputBuffer(10_000)
                            if (ii >= 0) {
                                val ib = c.getInputBuffer(ii)!!
                                val n = ex.readSampleData(ib, 0)
                                if (n < 0) {
                                    c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inDone = true
                                } else {
                                    val st = ex.sampleTime
                                    c.queueInputBuffer(ii, 0, n, st, 0)
                                    ex.advance()
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (durUs > 0 && now - lastUi > 200) { lastUi = now; onProgress((st.toFloat() / durUs).coerceIn(0f, 1f)) }
                                }
                            }
                        }
                        val oi = c.dequeueOutputBuffer(info, 10_000)
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val of = c.outputFormat
                            if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else if (oi >= 0) {
                            if (info.size > 0) {
                                val e = enc ?: Mp3Lame.create(sr, ch, kbps).also { enc = it }
                                val ob = c.getOutputBuffer(oi)!!
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val frames = sb.remaining() / ch
                                val l = ShortArray(frames)
                                val rr = if (ch >= 2) ShortArray(frames) else l
                                for (i in 0 until frames) {
                                    l[i] = sb.get()
                                    if (ch >= 2) {
                                        rr[i] = sb.get()
                                        for (k in 2 until ch) sb.get()
                                    }
                                }
                                val need = (frames * 1.25).toInt() + 7200
                                if (mp3.size < need) mp3 = ByteArray(need)
                                val n = e.encode(l, rr, frames, mp3)
                                if (n < 0) throw IOException("lame $n")
                                if (n > 0) os.write(mp3, 0, n)
                            }
                            c.releaseOutputBuffer(oi, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                        }
                    }
                    val e = enc ?: throw IOException("no audio")
                    if (mp3.size < 7200) mp3 = ByteArray(7200)
                    val n = e.flush(mp3)
                    if (n > 0) os.write(mp3, 0, n)
                    os.flush()
                    onProgress(1f)
                } finally {
                    runCatching { c.stop() }
                    runCatching { c.release() }
                    runCatching { enc?.close() }
                }
            }
        } finally {
            runCatching { ex.release() }
        }
    }
}
