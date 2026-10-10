package com.nova.browser

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

const val YT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

/** هل الرابط صفحة فيديو يوتيوب (مشاهدة/شورتس/بث)؟ */
fun isYtVideo(u: String): Boolean {
    val p = runCatching { Uri.parse(u) }.getOrNull() ?: return false
    val h = (p.host ?: return false).removePrefix("www.")
    val path = p.path ?: ""
    return (h == "youtu.be" && path.length > 1) ||
        ((h == "youtube.com" || h == "m.youtube.com" || h == "music.youtube.com") &&
            (path == "/watch" || path.startsWith("/shorts/") || path.startsWith("/live/")))
}

/** خيار تنزيل: audioUrl غير فارغ = فيديو بلا صوت يُدمج مع هذا الصوت بعد التنزيل. */
class YtOpt(val label: String, val sub: String, val url: String, val ext: String, val mime: String, val audioUrl: String? = null, val fmt: AudioFmt? = null)
class YtInfo(val title: String, val author: String, val videos: List<YtOpt>, val audios: List<YtOpt>, val seconds: Long = 0L)

/**
 * جلب صيغ يوتيوب عبر واجهة Piped العامة (بديل عن مكتبة NewPipeExtractor).
 *
 * السبب: NewPipeExtractor تستدعي داخلياً URLDecoder.decode(String, Charset) — تابع
 * موجود بأندرويد من إصدار 13 فقط، وغير مغطّى بالديسوغرينغ (Core Library Desugaring لا
 * يشمل حزمة java.net.URLDecoder)، فيتعطّل التنزيل على كل جهاز أقدم من أندرويد 13.
 *
 * الحل الجذري الأبسط: حذف المكتبة بالكامل من المشروع. خادم Piped (مفتوح المصدر) هو من
 * يقوم بفك تشفير روابط الفيديو وحساب التوقيع على خادمه هو، ويرجّع روابط تنزيل جاهزة
 * ومباشرة. الهاتف فقط يرسل طلب HTTP ويقرأ JSON عادي (org.json، مدمج بأندرويد منذ البداية
 * بلا أي اعتماد على تابع حديث) — صفر تعقيد فك تشفير على الجهاز، فيعمل من أندرويد 7 فصاعداً.
 */
object YtFetch {
    // عدّة خوادم عامة بالترتيب: إذا فشل أول واحد (تعطّل/إبطاء) يجرَّب التالي تلقائياً.
    private val INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.r4fo.com",
    )

    /** يستخرج معرّف الفيديو ويبني رابط watch نظيفاً (يتخلص من list/pp/start_radio وروابط m. وshorts). */
    fun canonical(u: String): String {
        val p = Uri.parse(u)
        val h = (p.host ?: "").removePrefix("www.")
        val seg = p.pathSegments
        val id = when {
            h == "youtu.be" -> seg.firstOrNull()
            seg.size >= 2 && (seg[0] == "shorts" || seg[0] == "live" || seg[0] == "embed") -> seg[1]
            else -> p.getQueryParameter("v")
        }
        require(id != null && Regex("[A-Za-z0-9_-]{11}").matches(id)) { "رابط فيديو غير صالح" }
        return "https://www.youtube.com/watch?v=$id"
    }

    private fun get(base: String, id: String): String {
        val c = URL("$base/streams/$id").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", YT_UA)
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    fun info(url: String): YtInfo {
        val id = canonical(url).substringAfter("v=")
        var lastErr: Exception? = null
        for (base in INSTANCES) {
            try { return parse(get(base, id)) } catch (e: Exception) { lastErr = e }
        }
        throw lastErr ?: IOException(L("تعذّر الاتصال بخادم الاستخراج"))
    }

    private class A(val url: String, val bitrateK: Int, val ext: String)

    private fun parse(json: String): YtInfo {
        val j = JSONObject(json)
        val audioArr = j.optJSONArray("audioStreams") ?: JSONArray()
        val videoArr = j.optJSONArray("videoStreams") ?: JSONArray()

        val audiosAll = ArrayList<A>()
        for (i in 0 until audioArr.length()) {
            val o = audioArr.getJSONObject(i)
            val url = o.optString("url"); if (url.isBlank()) continue
            val mime = o.optString("mimeType")
            val ext = when {
                mime.contains("mp4") -> "m4a"
                mime.contains("webm") -> "webm"
                else -> continue
            }
            audiosAll.add(A(url, o.optInt("bitrate", 0) / 1000, ext))
        }
        val aac = audiosAll.filter { it.ext == "m4a" }.maxByOrNull { it.bitrateK }
        val opus = audiosAll.filter { it.ext == "webm" }.maxByOrNull { it.bitrateK }

        fun qnum(s: String) = Regex("(\\d+)").find(s)?.value?.toIntOrNull() ?: 0
        val videos = ArrayList<YtOpt>()
        for (i in 0 until videoArr.length()) {
            val o = videoArr.getJSONObject(i)
            val url = o.optString("url"); if (url.isBlank()) continue
            if (!o.optString("mimeType").contains("mp4")) continue   // نكتفي بـ MP4 لأفضل توافق مع كل الأجهزة
            val quality = o.optString("quality")
            val videoOnly = o.optBoolean("videoOnly", true)
            when {
                !videoOnly -> videos.add(YtOpt("$quality MP4", L("فيديو + صوت جاهز"), url, "mp4", "video/mp4"))
                aac != null -> videos.add(YtOpt("$quality MP4", L("فيديو + صوت (دمج تلقائي)"), url, "mp4", "video/mp4", aac.url))
            }
        }
        videos.sortByDescending { qnum(it.label) }

        val audios = ArrayList<YtOpt>()
        if (aac != null) audios.add(YtOpt("M4A • ${aac.bitrateK} kbps", L("الملف الأصلي AAC بلا تحويل"), aac.url, "m4a", "audio/mp4"))
        if (opus != null) audios.add(YtOpt("WebM Opus • ${opus.bitrateK} kbps", L("الملف الأصلي Opus بلا تحويل"), opus.url, "webm", "audio/webm"))
        // صيغ محوَّلة بعد التنزيل (فارغة حالياً — انظر AudioConvert)
        (aac ?: opus)?.let { src -> AudioFormats.all.forEach { f -> audios.add(YtOpt(f.label, f.sub, src.url, src.ext, if (src.ext == "m4a") "audio/mp4" else "audio/webm", null, f)) } }

        return YtInfo(j.optString("title"), j.optString("uploader"), videos, audios, j.optLong("duration"))
    }
}

object YtDownload {
    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "video" }

    fun start(ctx: Context, info: YtInfo, o: YtOpt) {
        val base = safeName(info.title)
        val ref = "https://www.youtube.com/"
        val f = o.fmt
        if (f != null) {
            // تنزيل المصدر ثم التحويل إلى الصيغة المطلوبة (يُستدعى الـ callback على خيط خلفي)
            Downloader.startNamed(ctx, o.url, YT_UA, ref, "$base [source].${o.ext}", o.mime) { t ->
                AudioConvert.run(ctx.applicationContext, base, info.title, info.author, t, f)
            }
            return
        }
        if (o.audioUrl == null) {
            Downloader.startNamed(ctx, o.url, YT_UA, ref, "$base.${o.ext}", o.mime)
            return
        }
        val holder = arrayOfNulls<DlTask>(2)
        val left = AtomicInteger(2)
        val finish: (DlTask) -> Unit = {
            if (left.decrementAndGet() == 0) {
                Downloader.work.incrementAndGet()   // يُبقي إشعار «جارٍ المعالجة» حيّاً حتى انتهاء الدمج
                Thread {
                    try { runMux(ctx.applicationContext, base, holder[0]!!, holder[1]!!) } finally { Downloader.work.decrementAndGet() }
                }.start()
            }
        }
        holder[0] = Downloader.startNamed(ctx, o.url, YT_UA, ref, "$base [video].mp4", "video/mp4", finish)
        holder[1] = Downloader.startNamed(ctx, o.audioUrl, YT_UA, ref, "$base [audio].m4a", "audio/mp4", finish)
    }

    private fun runMux(app: Context, base: String, v: DlTask, a: DlTask) {
        val ui = Handler(Looper.getMainLooper())
        ui.post { toast(app, L("جارٍ دمج الصوت والفيديو…")) }
        val r = app.contentResolver
        var out: Uri? = null
        try {
            val dst = Storage.create(app, "$base.mp4", "video/mp4")
            out = dst
            Mux.mux(app, v.uri ?: throw IOException("no video"), a.uri ?: throw IOException("no audio"), dst)
            Storage.finish(app, dst, "video/mp4")
            val size = r.openFileDescriptor(dst, "r")?.use { it.statSize } ?: -1L
            Downloader.addFinished(Storage.displayName(app, dst) ?: "$base.mp4", dst, "video/mp4", size)
            Downloader.removeOnMain(v, true); Downloader.removeOnMain(a, true)
        } catch (e: Throwable) {
            out?.let { runCatching { Storage.delete(app, it) } }
            ui.post { toast(app, L("فشل الدمج — الملفان محفوظان منفصلَين")) }
        }
    }
}

/** دمج مسار فيديو (H.264) مع مسار صوت (AAC) في ملف MP4 بلا إعادة ترميز. */
object Mux {
    private fun track(ex: MediaExtractor, prefix: String): Int =
        (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith(prefix) == true }

    fun mux(ctx: Context, video: Uri, audio: Uri, out: Uri) {
        val r = ctx.contentResolver
        r.openFileDescriptor(video, "r")!!.use { vfd ->
            r.openFileDescriptor(audio, "r")!!.use { afd ->
                r.openFileDescriptor(out, "rw")!!.use { ofd ->
                    val ve = MediaExtractor(); val ae = MediaExtractor()
                    var mx: MediaMuxer? = null
                    var started = false
                    try {
                        ve.setDataSource(vfd.fileDescriptor); ae.setDataSource(afd.fileDescriptor)
                        val vt = track(ve, "video/"); val at = track(ae, "audio/")
                        ve.selectTrack(vt); ae.selectTrack(at)
                        // MediaMuxer(FileDescriptor) يحتاج أندرويد 8؛ قبله نستخدم المسار (التنزيل على أندرويد 6–9 ملف عادي دائماً)
                        val m = if (android.os.Build.VERSION.SDK_INT >= 26) MediaMuxer(ofd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        else MediaMuxer(Storage.pathOf(out) ?: throw java.io.IOException("no path"), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        mx = m
                        val vi = m.addTrack(ve.getTrackFormat(vt)); val ai = m.addTrack(ae.getTrackFormat(at))
                        m.start(); started = true
                        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
                        val bi = MediaCodec.BufferInfo()
                        while (true) {
                            val vtm = ve.sampleTime; val atm = ae.sampleTime
                            if (vtm < 0 && atm < 0) break
                            val useV = atm < 0 || (vtm in 0L..atm)    // تداخل المسارين حسب الزمن
                            val ex = if (useV) ve else ae
                            buf.clear()
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) break
                            val key = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            bi.set(0, n, ex.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            m.writeSampleData(if (useV) vi else ai, buf, bi)
                            ex.advance()
                        }
                    } finally {
                        if (started) runCatching { mx?.stop() }
                        runCatching { mx?.release() }
                        ve.release(); ae.release()
                    }
                }
            }
        }
    }
}

// ───────────── الواجهة ─────────────

private fun fmtDur(sec: Long): String = when {
    sec <= 0L -> ""
    sec >= 3600L -> "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
    else -> "%d:%02d".format(sec / 60, sec % 60)
}

/** شريحة صغيرة (الصيغة / «الأفضل»). */
@Composable
private fun DlPill(text: String, accent: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = if (accent) cs.tertiary.copy(alpha = 0.16f) else cs.surfaceContainerHighest) {
        Text(
            text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = if (accent) cs.tertiary else cs.onSurfaceVariant, fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** تبويب «فيديو / صوت»: نفس شكل شرائح الخيارات في بقية التطبيق. */
@Composable
private fun DlTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = if (selected) cs.tertiary else cs.surfaceContainerHigh, modifier = modifier.clip(CircleShape).clickable(onClick = onClick)) {
        Text(
            label, Modifier.padding(vertical = 11.dp), textAlign = TextAlign.Center, maxLines = 1, style = MaterialTheme.typography.labelLarge,
            color = if (selected) cs.onTertiary else cs.onSurface, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtDownloadSheet(url: String, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<YtInfo?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }   // 0 فيديو، 1 صوت
    // معرّف الفيديو معروف فوراً من الرابط: الصورة المصغّرة تظهر قبل انتهاء جلب الصيغ
    val vid = remember(url) { runCatching { YtFetch.canonical(url).substringAfter("v=") }.getOrNull() }
    LaunchedEffect(url, attempt) {
        info = null; err = null
        val r = withContext(Dispatchers.IO) { runCatching { YtFetch.info(url) } }
        r.onSuccess { info = it }.onFailure { err = it.message ?: it.javaClass.simpleName }
    }
    fun pick(i: YtInfo, o: YtOpt) { YtDownload.start(ctx, i, o); toast(ctx, L("بدأ التنزيل — القائمة ⋮ ثم التنزيلات")); onStarted(); onDismiss() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(L("تنزيل من يوتيوب"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            val i = info

            // بطاقة الفيديو: صورة مصغّرة + العنوان + القناة + المدة
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))) {
                    if (vid != null) NetImage("https://i.ytimg.com/vi/$vid/hqdefault.jpg", Modifier.fillMaxSize())
                    val d = fmtDur(i?.seconds ?: 0L)
                    if (d.isNotEmpty()) Text(
                        d, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (i != null) {
                        Text(i.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (i.author.isNotBlank()) Text(i.author, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else if (err == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NovaSpinner(size = 20.dp, color = cs.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(L("جارٍ جلب معلومات الفيديو…"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }

            when {
                err != null -> {
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.errorContainer.copy(alpha = 0.35f)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = cs.error)
                            Spacer(Modifier.width(8.dp))
                            Text(L("تعذّر جلب الفيديو"), color = cs.error, fontWeight = FontWeight.Bold)
                        }
                        Text(err ?: "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Row(
                            Modifier.clip(CircleShape).background(cs.tertiary).clickable { attempt++ }.padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp), tint = cs.onTertiary)
                            Spacer(Modifier.width(6.dp))
                            Text(L("إعادة المحاولة"), color = cs.onTertiary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                i == null -> {
                    Spacer(Modifier.height(20.dp))
                    NovaLoadingRow(L("جارٍ جلب الصيغ المتاحة…"))
                    Spacer(Modifier.height(20.dp))
                }
                else -> {
                    if (i.videos.isEmpty() && i.audios.isEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(L("لا توجد صيغ قابلة للتنزيل لهذا الفيديو (قد يكون بثاً مباشراً)"), color = cs.onSurfaceVariant)
                    } else {
                        val showVideo = i.audios.isEmpty() || (tab == 0 && i.videos.isNotEmpty())
                        Spacer(Modifier.height(16.dp))
                        if (i.videos.isNotEmpty() && i.audios.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DlTab(L("فيديو") + " (${i.videos.size})", showVideo, Modifier.weight(1f)) { tab = 0 }
                                DlTab(L("صوت فقط") + " (${i.audios.size})", !showVideo, Modifier.weight(1f)) { tab = 1 }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        val list = if (showVideo) i.videos else i.audios
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            list.forEachIndexed { n, o ->
                                ListRow(
                                    groupShape(n, list.size), o.label, o.sub, { pick(i, o) },
                                    trailing = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (showVideo && n == 0) DlPill(L("الأفضل"), accent = true)
                                            if (o.fmt == null) DlPill(o.ext.uppercase())
                                        }
                                    }
                                ) { IconCircle { Icon(Icons.Default.KeyboardArrowDown, null) } }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        L("للاستخدام الشخصي فقط — احترم حقوق صاحب المحتوى وشروط الخدمة."), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(12.dp)
                    )
                }
            }
        }
    }
}
