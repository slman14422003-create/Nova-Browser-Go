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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** عميل «ANDROID_VR» (InnerTube): يعيد روابط تنزيل مباشرة بلا تشفير توقيع ولا PO token، فلا حاجة لمكتبة فك تشفير ولا لخوادم وسيطة. */
const val YT_VR_VER = "1.60.19"
const val YT_VR_UA = "com.google.android.apps.youtube.vr.oculus/$YT_VR_VER (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
const val YT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

const val YT_IOS_UA = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
const val YT_TV_UA = "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15"

fun ytUaFor(url: String): String = when (runCatching { Uri.parse(url).getQueryParameter("c") }.getOrNull()) {
    "ANDROID_VR" -> YT_VR_UA
    "IOS" -> YT_IOS_UA
    "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> YT_TV_UA
    else -> YT_UA
}

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
    // خوادم Piped العامة. تتعطّل أو تُحجب من وقت لآخر، فالقائمة طويلة والتجربة تلقائية؛ آخر خادم نجح يُجرَّب أولاً.
    // إن تعطّلت كلها تُجلب القائمة الحيّة من piped-instances ثم تُجرَّب (انظر liveInstances).
    private val INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.nosebs.ru",
        "https://piped-api.privacy.com.de",
        "https://pipedapi.owo.si",
        "https://pipedapi.ducks.party",
        "https://pipedapi.reallyaweso.me",
        "https://pipedapi.darkness.services",
        "https://pipedapi.orangenet.cc",
        "https://pipedapi.r4fo.com",
    )
    @Volatile private var lastGood: String? = null
    @Volatile private var live: List<String>? = null

    /**
     * عميل شبكة بدقّة DNS احتياطية: أخطاء مثل «Unable to resolve host» تحدث عندما يحجب مزوّد الإنترنت/الشبكة
     * اسم النطاق أو يفشل DNS الجهاز. عند الفشل نسأل DNS-over-HTTPS (Cloudflare ثم Google بعنوان IP مباشر،
     * فلا يحتاج الطلب نفسه إلى DNS) ونتصل بالعنوان الناتج مع إبقاء اسم النطاق للشهادة (TLS/SNI).
     */
    private val client: okhttp3.OkHttpClient by lazy {
        Tls.apply(okhttp3.OkHttpClient.Builder())
            .dns(DohFallbackDns)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private object DohFallbackDns : okhttp3.Dns {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<java.net.InetAddress>>>()
        private val servers = listOf(
            "https://1.1.1.1/dns-query?type=A&name=",
            "https://8.8.8.8/resolve?type=A&name=",
            "https://dns.google/resolve?type=A&name=",
        )

        override fun lookup(hostname: String): List<java.net.InetAddress> {
            try {
                return v4first(okhttp3.Dns.SYSTEM.lookup(hostname))
            } catch (e: java.net.UnknownHostException) {
                val c = cache[hostname]
                if (c != null && System.currentTimeMillis() - c.first < 10 * 60_000L) return c.second
                val r = doh(hostname)
                if (r.isNotEmpty()) { cache[hostname] = System.currentTimeMillis() to r; return r }
                throw e
            }
        }

        private fun doh(host: String): List<java.net.InetAddress> {
            for (base in servers) {
                try {
                    val c = URL(base + java.net.URLEncoder.encode(host, "UTF-8")).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 4000; c.readTimeout = 4000
                        c.setRequestProperty("Accept", "application/dns-json")
                        if (c.responseCode !in 200..299) continue
                        val ans = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("Answer") ?: continue
                        val out = ArrayList<java.net.InetAddress>()
                        for (i in 0 until ans.length()) {
                            val o = ans.getJSONObject(i)
                            if (o.optInt("type") == 1) out.add(java.net.InetAddress.getByName(o.optString("data")))   // عنوان IPv4 نصّي: لا DNS هنا
                        }
                        if (out.isNotEmpty()) return out
                    } finally { c.disconnect() }
                } catch (_: Exception) {}
            }
            return emptyList()
        }
    }

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

    private fun httpGet(url: String, timeoutSec: Long? = null): String {
        val cl = if (timeoutSec == null) client else client.newBuilder().callTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS).build()
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", YT_UA).header("Accept", "application/json").build()
        cl.newCall(req).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(body).optString("message").take(80) }.getOrDefault("")
                throw IOException("HTTP ${r.code}" + if (msg.isNotBlank()) " — $msg" else "")
            }
            return body
        }
    }

    /** قائمة الخوادم الحيّة (تُجلب مرة واحدة فقط وعند الحاجة): كل ما له api_url من piped-instances. */
    private fun liveInstances(): List<String> {
        live?.let { return it }
        val list = runCatching {
            val arr = JSONArray(httpGet("https://piped-instances.kavin.rocks/", 8))
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("api_url").trimEnd('/').takeIf { u -> u.startsWith("https://") } }
        }.getOrDefault(emptyList())
        live = list
        return list
    }

    /** عملاء InnerTube بالترتيب: كل واحد قد ينجح حين يرفض غيره (يوتيوب يشدّد على عميل ويترك آخر). */
    private class Cl(val name: String, val ver: String, val id: String, val ua: String, val ctx: JSONObject.() -> Unit)
    private val CLIENTS = listOf(
        Cl("ANDROID_VR", YT_VR_VER, "28", YT_VR_UA) {
            put("deviceMake", "Oculus"); put("deviceModel", "Quest 3"); put("osName", "Android"); put("osVersion", "12L"); put("androidSdkVersion", 32)
        },
        Cl("IOS", "20.10.4", "5", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)") {
            put("deviceMake", "Apple"); put("deviceModel", "iPhone16,2"); put("osName", "iPhone"); put("osVersion", "18.3.2.22D82")
        },
        Cl("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "85", "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15") {
            put("clientScreen", "EMBED")
        },
    )

    /** استخراج مباشر من يوتيوب (من جوال المستخدم نفسه، فالروابط مرتبطة بعنوانه). يعيد JSON بنفس شكل Piped ليُحلَّل بـ parse(). */
    private fun innertube(id: String, c: Cl): String {
        val cl = JSONObject().put("clientName", c.name).put("clientVersion", c.ver).put("hl", "en").put("gl", "US")
        c.ctx(cl)
        val ctx = JSONObject().put("client", cl)
        if (c.name.startsWith("TVHTML5")) ctx.put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
        val body = JSONObject().put("videoId", id).put("contentCheckOk", true).put("racyCheckOk", true).put("context", ctx)
        val req = okhttp3.Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", c.ua).header("X-YouTube-Client-Name", c.id).header("X-YouTube-Client-Version", c.ver)
            .header("Origin", "https://www.youtube.com")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            val txt = r.body.string()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val j = JSONObject(txt)
            val ps = j.optJSONObject("playabilityStatus")
            val st = ps?.optString("status") ?: ""
            if (st != "OK") throw IOException("$st " + (ps?.optString("reason") ?: "").take(90))
            val sd = j.optJSONObject("streamingData") ?: throw IOException("no streamingData")
            val vs = JSONArray(); val au = JSONArray()
            sd.optJSONArray("formats")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i); val u = o.optString("url"); val m = o.optString("mimeType")
                    if (u.isBlank() || !m.startsWith("video/mp4")) continue   // بلا url = مشفّر بتوقيع (غير مدعوم)
                    vs.put(JSONObject().put("url", u).put("mimeType", m).put("quality", o.optString("qualityLabel").ifBlank { "video" }).put("videoOnly", false))
                }
            }
            sd.optJSONArray("adaptiveFormats")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i); val u = o.optString("url"); val m = o.optString("mimeType")
                    if (u.isBlank()) continue
                    when {
                        m.startsWith("video/mp4") && m.contains("avc1") ->   // H.264 فقط: يعمل مع الدمج وكل المشغّلات
                            vs.put(JSONObject().put("url", u).put("mimeType", m).put("quality", o.optString("qualityLabel").ifBlank { "video" }).put("videoOnly", true))
                        m.startsWith("audio/") -> au.put(JSONObject().put("url", u).put("mimeType", m).put("bitrate", o.optInt("bitrate", 0)))
                    }
                }
            }
            val vd = j.optJSONObject("videoDetails")
            return JSONObject().put("title", vd?.optString("title") ?: "").put("uploader", vd?.optString("author") ?: "")
                .put("duration", vd?.optString("lengthSeconds")?.toLongOrNull() ?: 0L).put("videoStreams", vs).put("audioStreams", au).toString()
        }
    }

    fun info(url: String): YtInfo {
        val id = canonical(url).substringAfter("v=")
        val errors = ArrayList<String>()
        val tried = HashSet<String>()
        fun attempt(base: String): YtInfo? {
            if (!tried.add(base)) return null
            return try {
                parse(httpGet("$base/streams/$id")).also { lastGood = base }
            } catch (e: Exception) {
                val m = e.message ?: e.javaClass.simpleName
                errors.add(base.removePrefix("https://") + ": " + m)
                YtLog.add("piped $base -> $m")
                if (lastGood == base) lastGood = null
                null
            }
        }
        var ytErr = ""
        for (c in CLIENTS) {
            try {
                val r = parse(innertube(id, c))
                if (r.videos.isNotEmpty() || r.audios.isNotEmpty()) return r
                ytErr = "${c.name}: no downloadable formats"
            } catch (e: Exception) { ytErr = "${c.name}: " + (e.message ?: e.javaClass.simpleName); YtLog.add("innertube $ytErr") }
        }
        val px = BuildConfig.YT_PROXY.trim().trimEnd('/').takeIf { it.startsWith("https://") }
        px?.let { attempt(it)?.let { r -> return r } }   // بروكسي المستخدم (Worker) أولاً
        val proxyErr = px?.let { p -> errors.firstOrNull { it.startsWith(p.removePrefix("https://")) } }
        lastGood?.let { g -> attempt(g)?.let { return it } }
        for (base in INSTANCES) attempt(base)?.let { return it }
        // كلها فشلت: نجرّب قائمة الخوادم الحيّة (حتى 8 خوادم جديدة)
        for (base in liveInstances().filter { it !in tried }.take(8)) attempt(base)?.let { return it }
        val dnsOnly = errors.isNotEmpty() && errors.all { it.contains("resolve host", true) || it.contains("hostname", true) }
        throw IOException(
            (if (dnsOnly) L("تعذّر الوصول إلى خوادم الاستخراج (فشل DNS) — جرّب شبكة أخرى أو VPN.") else L("تعذّر الاتصال بخوادم الاستخراج حالياً.")) +
                (if (ytErr.contains("LOGIN_REQUIRED")) "\n" + L("يوتيوب يشتبه بشبكتك — جرّب Wi-Fi مختلفاً أو VPN") else "") + "\nYouTube → " + ytErr + "\n" + (proxyErr?.let { "Worker → " + it.substringAfter(": ") + "\n" } ?: "") + errors.takeLast(1).joinToString("\n")
        )
    }

    private class A(val url: String, val bitrateK: Int, val ext: String)

    private fun parse(json: String): YtInfo {
        val j = JSONObject(json)
        if (j.has("error")) throw IOException(j.optString("message").ifBlank { j.optString("error") }.take(80))
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
        val seen = HashSet<String>()
        audiosAll.sortedWith(compareBy<A>({ it.ext != "m4a" }, { -it.bitrateK })).forEach { a ->
            if (!seen.add(a.ext + a.bitrateK)) return@forEach
            val m4 = a.ext == "m4a"
            audios.add(YtOpt((if (m4) "M4A AAC" else "WebM Opus") + " - ${a.bitrateK} kbps", if (m4) L("الملف الأصلي AAC بلا تحويل") else L("الملف الأصلي Opus بلا تحويل"), a.url, a.ext, if (m4) "audio/mp4" else "audio/webm"))
        }
        (aac ?: opus)?.let { src -> AudioFormats.all.forEachIndexed { idx, f -> audios.add(idx, YtOpt(f.label, f.sub, src.url, src.ext, if (src.ext == "m4a") "audio/mp4" else "audio/webm", null, f)) } }

        return YtInfo(j.optString("title"), j.optString("uploader"), videos, audios, j.optLong("duration"))
    }
}

object YtDownload {
    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "video" }

    fun start(ctx: Context, info: YtInfo, o: YtOpt, pageUrl: String? = null) {
        val base = safeName(info.title)
        val ref = "https://www.youtube.com/"
        val f = o.fmt
        if (f != null) {
            // تنزيل المصدر ثم التحويل إلى الصيغة المطلوبة (يُستدعى الـ callback على خيط خلفي)
            Downloader.startNamed(ctx, o.url, ytUaFor(o.url), ref, "$base [source].${o.ext}", o.mime) { t ->
                AudioConvert.run(ctx.applicationContext, base, info.title, info.author, t, f)
            }.also {
                it.connLimit = 1
                it.refresher = {
                    pageUrl?.let { u -> runCatching { YtFetch.info(u) }.getOrNull() }?.audios?.firstOrNull { a -> a.fmt == null && a.ext == o.ext }?.url
                }
            }
            return
        }
        if (o.audioUrl == null) {
            val single: () -> String? = {
                val fi = pageUrl?.let { runCatching { YtFetch.info(it) }.getOrNull() }
                val list = if (o.mime.startsWith("audio")) fi?.audios else fi?.videos?.filter { it.audioUrl == null }
                (list?.firstOrNull { it.label == o.label } ?: list?.firstOrNull { it.ext == o.ext })?.url
            }
            Downloader.startNamed(ctx, o.url, ytUaFor(o.url), ref, "$base.${o.ext}", o.mime).also { it.refresher = single; it.connLimit = 1 }
            return
        }
        fun fresh(): YtInfo? = pageUrl?.let { runCatching { YtFetch.info(it) }.getOrNull() }
        val vRef: () -> String? = {
            fresh()?.videos?.firstOrNull { it.label == o.label && it.audioUrl != null }?.url
        }
        val aRef: () -> String? = {
            fresh()?.audios?.firstOrNull { it.ext == "m4a" }?.url
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
        holder[0] = Downloader.startNamed(ctx, o.url, ytUaFor(o.url), ref, "$base [video].mp4", "video/mp4", finish).also { it.refresher = vRef; it.connLimit = 1 }
        holder[1] = Downloader.startNamed(ctx, o.audioUrl, ytUaFor(o.audioUrl), ref, "$base [audio].m4a", "audio/mp4", finish).also { it.refresher = aRef; it.connLimit = 1 }
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

    private fun sampleCap(f: android.media.MediaFormat): Int {
        val v = if (f.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        return if (v > 0) v.coerceIn(256 * 1024, 8 * 1024 * 1024) else 2 * 1024 * 1024
    }

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
                        val need = maxOf(sampleCap(ve.getTrackFormat(vt)), sampleCap(ae.getTrackFormat(at)))
                        val buf = ByteBuffer.allocate(need)
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
    fun pick(i: YtInfo, o: YtOpt) { YtDownload.start(ctx, i, o, url); toast(ctx, L("بدأ التنزيل — القائمة ⋮ ثم التنزيلات")); onStarted(); onDismiss() }

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
                        Text(err ?: "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 7, overflow = TextOverflow.Ellipsis)
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
