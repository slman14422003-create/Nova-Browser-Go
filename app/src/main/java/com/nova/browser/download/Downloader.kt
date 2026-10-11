package com.nova.browser

import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** جزء من الملف: [start..end] والمؤشر pos = البايت التالي المطلوب كتابته */
class Seg(val start: Long, end0: Long) {
    @Volatile var end: Long = end0
    @Volatile var pos: Long = start
    @Volatile var busy = false
    val remaining: Long get() = end - pos + 1
}

class DlTask(
    val id: String, var url: String, val origUrl: String, val ua: String, val referer: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    var name by mutableStateOf("")
    var mime by mutableStateOf("")
    var status by mutableIntStateOf(Downloader.PREPARING)
    var total by mutableLongStateOf(-1L)
    var downloaded by mutableLongStateOf(0L)
    var speed by mutableLongStateOf(0L)
    var conns by mutableIntStateOf(0)
    var error by mutableStateOf("")
    var segSnap by mutableStateOf<List<LongArray>>(emptyList())
    var uri: Uri? = null
    var resumable = true
    val segs = CopyOnWriteArrayList<Seg>()
    val lock = Any()
    @Volatile var stopReason = 0   // 1 = إيقاف مؤقت، 2 = إلغاء
    val active = AtomicInteger(0)
    @Volatile var channel: FileChannel? = null
    var lastBytes = 0L
    var lastTime = 0L
    var forcedName: String? = null                    // اسم ملف مفروض (مثل تنزيلات يوتيوب)
    @Volatile var onDone: ((DlTask) -> Unit)? = null  // يُستدعى عند الاكتمال (لا يُحفظ بعد إعادة التشغيل)
}

object Downloader {
    const val PREPARING = 0
    const val DOWNLOADING = 1
    const val PAUSED = 2
    const val DONE = 3
    const val FAILED = 4
    private const val MIN_SPLIT = 256 * 1024L

    lateinit var app: Context
    val tasks = mutableStateListOf<DlTask>()
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private var inited = false
    private var lastSave = 0L

    fun init(c: Context) {
        if (inited) return
        inited = true
        app = c.applicationContext
        load()   // قراءة القائمة فقط عند الإقلاع (تظهر في الصفحة الرئيسية)؛ المحرك يعمل عند أول تنزيل
    }

    @Volatile private var engineOn = false

    /** مهام معالجة بعد التنزيل (دمج/تحويل): تُبقي خدمة الإشعار حيّة كي لا يختفي الإشعار وتُقتل العملية أثناء الدمج. */
    val work = AtomicInteger(0)

    /** تشغيل كسول لمحرك التنزيل: قنوات الإشعارات ومؤقّت التقدّم (كل 400ms) لا يعملان إلا حين يبدأ تنزيل فعلي. */
    @Synchronized
    fun ensureEngine() {
        if (engineOn) return
        engineOn = true
        System.setProperty("http.maxConnections", "32")
        Notif.ensureChannels(app)
        Executors.newSingleThreadScheduledExecutor()
            .scheduleWithFixedDelay({ runCatching { tick() } }, 400, 400, TimeUnit.MILLISECONDS)
    }

    private fun startService() {
        ensureEngine()
        runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }
    }

    // ───────────── واجهة الاستخدام ─────────────
    fun start(c: Context, url: String, ua: String?, cd: String?, mime: String?, referer: String?) {
        init(c)
        val t = DlTask(UUID.randomUUID().toString(), url, url, ua ?: "", referer ?: "")
        t.name = URLUtil.guessFileName(url, cd, mime)
        t.mime = mime ?: ""
        tasks.add(0, t)
        startService()
        pool.execute { prepare(t, cd) }
    }

    /** تنزيل باسم ونوع محددين مع استدعاء عند الاكتمال (يُستخدم لتنزيل يوتيوب والدمج). */
    fun startNamed(
        c: Context, url: String, ua: String, referer: String, name: String, mime: String,
        onDone: ((DlTask) -> Unit)? = null
    ): DlTask {
        init(c)
        val t = DlTask(UUID.randomUUID().toString(), url, url, ua, referer)
        t.forcedName = name; t.name = name; t.mime = mime; t.onDone = onDone
        tasks.add(0, t)
        startService()
        pool.execute { prepare(t, null) }
        return t
    }

    /** إضافة ملف مكتمل (ناتج الدمج) إلى قائمة التنزيلات. */
    fun addFinished(name: String, uri: Uri, mime: String, size: Long) {
        val t = DlTask(UUID.randomUUID().toString(), uri.toString(), uri.toString(), "", "")
        t.name = name; t.mime = mime; t.uri = uri; t.total = size; t.downloaded = size
        t.status = DONE; t.resumable = false
        main.post { tasks.add(0, t); save() }
        notifyDone(t)
    }

    fun removeOnMain(t: DlTask, deleteFile: Boolean) { main.post { remove(t, deleteFile) } }

    fun pause(t: DlTask) {
        if (t.status == DOWNLOADING || t.status == PREPARING) { t.stopReason = 1; t.status = PAUSED }
    }

    fun pauseAll() = tasks.toList().forEach { pause(it) }

    fun resume(t: DlTask) {
        if (t.status != PAUSED && t.status != FAILED) return
        t.status = PREPARING; t.error = ""
        startService()
        pool.execute {
            while (t.active.get() > 0) Thread.sleep(50)
            if (t.stopReason == 2) { finish(t); return@execute }
            if (t.status == PAUSED) return@execute
            try {
                t.stopReason = 0
                if (t.uri == null) { prepare(t, null); return@execute }
                if (!t.resumable) buildSegs(t)
                run(t)
            } catch (e: Exception) { fail(t, e.message ?: L("خطأ")) }
        }
    }

    fun cancel(t: DlTask) {
        if (t.active.get() > 0 || t.status == PREPARING) t.stopReason = 2
        else { deleteFile(t); tasks.remove(t); save() }
    }

    fun remove(t: DlTask, deleteFile: Boolean) {
        if (deleteFile) deleteFile(t)
        tasks.remove(t); save()
    }

    // ───────────── التحضير: فحص الخادم + إنشاء الملف الفارغ + التقسيم ─────────────
    private class Probe(val url: String, val total: Long, val resumable: Boolean, val mime: String, val cd: String?)

    private fun prepare(t: DlTask, cd: String?) {
        try {
            val p = probe(t)
            t.url = p.url; t.total = p.total; t.resumable = p.resumable
            if (p.mime.isNotBlank()) t.mime = p.mime
            t.name = t.forcedName ?: URLUtil.guessFileName(p.url, p.cd ?: cd, t.mime.ifBlank { null })
            createSink(t)
            openSink(t)
            if (t.total > 0) runCatching { Os.ftruncate(sinkFd[t.id]!!.fileDescriptor, t.total) }
            buildSegs(t)
            save()
            if (t.stopReason != 0) { finish(t); return }
            run(t)
        } catch (e: Exception) { fail(t, e.message ?: L("خطأ")) }
    }

    private val sinkFd = HashMap<String, ParcelFileDescriptor>()

    private fun open(t: DlTask, url: String, range: String?): HttpURLConnection {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) throw IOException("unsupported scheme")
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15000; c.readTimeout = 20000; c.useCaches = false
        c.setRequestProperty("Accept-Encoding", "identity")
        if (t.ua.isNotBlank()) c.setRequestProperty("User-Agent", t.ua)
        if (t.referer.isNotBlank()) c.setRequestProperty("Referer", t.referer)
        val ck = CookieManager.getInstance().getCookie(url)
        if (!ck.isNullOrBlank()) c.setRequestProperty("Cookie", ck)
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    private fun connect(t: DlTask, start: String, range: String?): Pair<HttpURLConnection, String> {
        var u = start
        repeat(8) {
            val c = open(t, u, range)
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location"); c.disconnect()
                if (loc == null) throw IOException("redirect")
                val next = URL(URL(u), loc).toString()
                // التحويل لا يخرج من http/https (file: وcontent: وftp:)، ولا يتراجع من https إلى http (تنزيل غير مشفّر بلا علم المستخدم)
                if (!(next.startsWith("https://") || (next.startsWith("http://") && u.startsWith("http://")))) throw IOException("blocked redirect")
                u = next
            } else return c to u
        }
        throw IOException("too many redirects")
    }

    private fun probe(t: DlTask): Probe {
        val (c, u) = connect(t, t.url, "bytes=0-0")
        try {
            val code = c.responseCode
            val mime = (c.contentType ?: "").substringBefore(';').trim()
            val cd = c.getHeaderField("Content-Disposition")
            if (code == 206) {
                val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                return Probe(u, total, total > 0, mime, cd)
            }
            if (code in 200..299) return Probe(u, c.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L, false, mime, cd)   // getContentLengthLong يحتاج أندرويد 7
            throw IOException("HTTP $code")
        } finally { c.disconnect() }
    }

    private fun createSink(t: DlTask) {
        val uri = try { Storage.create(app, t.name, t.mime) } catch (e: IOException) { throw IOException(L("تعذّر إنشاء الملف")) }
        t.uri = uri
        Storage.displayName(app, uri)?.let { t.name = it }
    }

    private fun openSink(t: DlTask) {
        val pfd = app.contentResolver.openFileDescriptor(t.uri!!, "rw") ?: throw IOException(L("تعذّر فتح الملف"))
        sinkFd[t.id] = pfd
        t.channel = ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel
    }

    fun connsFor(total: Long): Int {
        val auto = when {
            total < 1_000_000 -> 1
            total < 8_000_000 -> 4
            total < 64_000_000 -> 8
            else -> 16
        }
        return minOf(auto, if (Prefs.maxConns > 0) Prefs.maxConns else if (LowEnd.on) 6 else 16)   // اتصالات أقل = رام ومعالج أقل على الأجهزة الضعيفة
    }

    private fun buildSegs(t: DlTask) {
        t.segs.clear()
        if (!t.resumable || t.total <= 0) {
            t.segs.add(Seg(0, if (t.total > 0) t.total - 1 else Long.MAX_VALUE / 4)); return
        }
        val n = connsFor(t.total); val part = t.total / n
        for (i in 0 until n) {
            val s = i * part
            t.segs.add(Seg(s, if (i == n - 1) t.total - 1 else s + part - 1))
        }
    }

    // ───────────── التشغيل: عدة اتصالات + سرقة العمل ─────────────
    private fun run(t: DlTask) {
        if (t.channel == null) openSink(t)
        t.segs.forEach { it.busy = false }
        t.lastBytes = downloadedOf(t); t.lastTime = SystemClock.elapsedRealtime(); t.speed = 0
        t.status = DOWNLOADING
        val n = if (t.resumable && t.total > 0) connsFor(t.total) else 1
        t.active.set(n)
        repeat(n) { pool.execute { worker(t) } }
    }

    private fun worker(t: DlTask) {
        try {
            val ch = t.channel!!
            while (t.stopReason == 0) {
                val seg = claim(t) ?: break
                try { fetch(t, seg, ch) } catch (e: Exception) { t.error = e.message ?: L("خطأ"); seg.busy = false; break }
                seg.busy = false
            }
        } catch (e: Exception) { t.error = e.message ?: L("خطأ") }
        finally { if (t.active.decrementAndGet() == 0) finish(t) }
    }

    private fun claim(t: DlTask): Seg? = synchronized(t.lock) { claimLocked(t) }

    /** عامل انتهى → يأخذ جزءاً حراً، وإلا يقسم أكبر جزء متبقٍ إلى نصفين ويأخذ النصف الثاني (مثل IDM) */
    private fun claimLocked(t: DlTask): Seg? {
        val free = t.segs.firstOrNull { !it.busy && it.remaining > 0 }
        if (free != null) { free.busy = true; return free }
        if (!t.resumable) return null
        val v = t.segs.filter { it.busy }.maxByOrNull { it.remaining } ?: return null
        if (v.remaining < 2 * MIN_SPLIT) return null
        val oldEnd = v.end
        val mid = v.pos + (oldEnd - v.pos) / 2
        v.end = mid
        val n = Seg(mid + 1, oldEnd)
        n.busy = true; t.segs.add(n)
        return n
    }

    private fun fetch(t: DlTask, seg: Seg, ch: FileChannel) {
        var fails = 0
        val buf = ByteArray(128 * 1024)
        while (seg.remaining > 0 && t.stopReason == 0) {
            val before = seg.pos
            var c: HttpURLConnection? = null
            try {
                val range = if (t.resumable) "bytes=${seg.pos}-${seg.end}" else null
                val (conn, _) = connect(t, t.url, range)
                c = conn
                val code = conn.responseCode
                if (t.resumable && code != 206) throw IOException("HTTP $code")
                if (!t.resumable && code !in 200..299) throw IOException("HTTP $code")
                conn.inputStream.use { ins ->
                    while (t.stopReason == 0) {
                        val n = ins.read(buf)
                        if (n < 0) {
                            if (!t.resumable) { seg.end = seg.pos - 1; if (t.total <= 0) t.total = seg.pos }
                            break
                        }
                        val allowed = minOf(n.toLong(), seg.end - seg.pos + 1).toInt()
                        if (allowed <= 0) break
                        val bb = ByteBuffer.wrap(buf, 0, allowed)
                        var p = seg.pos
                        while (bb.hasRemaining()) p += ch.write(bb, p)
                        seg.pos = p
                        if (allowed < n) break
                    }
                }
                if (seg.pos == before && seg.remaining > 0 && t.stopReason == 0) throw IOException("no data")
                fails = 0
            } catch (e: IOException) {
                if (t.stopReason != 0) return
                if (!t.resumable || ++fails > 6) throw e
                Thread.sleep(400L * fails)
            } finally { c?.disconnect() }
        }
    }

    // ───────────── الإنهاء والحالة ─────────────
    private fun finish(t: DlTask) {
        runCatching { t.channel?.close() }
        t.channel = null; sinkFd.remove(t.id)
        t.speed = 0; t.conns = 0
        updateProgress(t)
        when (t.stopReason) {
            2 -> { deleteFile(t); main.post { tasks.remove(t); save() } }
            1 -> { t.status = PAUSED; save() }
            else -> {
                val complete = t.segs.isNotEmpty() && t.segs.all { it.remaining <= 0 } &&
                    (t.total <= 0 || downloadedOf(t) >= t.total)
                if (complete) markDone(t) else fail(t, t.error.ifBlank { L("فشل التنزيل") })
            }
        }
    }

    private fun markDone(t: DlTask) {
        t.uri?.let { u ->
            runCatching { Storage.finish(app, u, t.mime) }
        }
        if (t.total <= 0) t.total = downloadedOf(t)
        t.downloaded = t.total; t.segSnap = emptyList(); t.status = DONE
        save()
        val cb = t.onDone
        if (cb == null) notifyDone(t)
        else { work.incrementAndGet(); pool.execute { try { runCatching { cb(t) } } finally { work.decrementAndGet() } } }
    }

    private fun notifId(t: DlTask) = 100 + (t.id.hashCode() and 0x3fffffff) % 100000

    private fun notifyDone(t: DlTask) {
        if (!Prefs.notifDone || !Notif.enabled(app)) return
        ensureEngine()
        runCatching {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(t.uri?.let { Storage.shareUri(app, it) }, t.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val pi = PendingIntent.getActivity(app, notifId(t), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            app.getSystemService(NotificationManager::class.java).notify(
                notifId(t),
                Notif.builder(app, Notif.CH_DONE).setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(L("اكتمل التنزيل")).setContentText(t.name)
                    .setCategory(Notification.CATEGORY_STATUS).setShowWhen(true).setWhen(System.currentTimeMillis())
                    .setContentIntent(pi).setAutoCancel(true).build()
            )
        }
    }

    /** فشل التنزيل كان صامتاً تماماً: الآن إشعار يفتح قائمة التنزيلات لإعادة المحاولة. */
    private fun notifyFail(t: DlTask) {
        if (!Prefs.notifDone || !Notif.enabled(app)) return
        ensureEngine()
        runCatching {
            val open = Intent(app, MainActivity::class.java).putExtra("dl", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(app, notifId(t), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            app.getSystemService(NotificationManager::class.java).notify(
                notifId(t),
                Notif.builder(app, Notif.CH_DONE).setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(L("فشل التنزيل")).setContentText(t.name)
                    .setCategory(Notification.CATEGORY_ERROR)
                    .setContentIntent(pi).setAutoCancel(true).build()
            )
        }
    }

    private fun fail(t: DlTask, msg: String) { t.error = msg; t.status = FAILED; t.speed = 0; save(); notifyFail(t) }

    private fun deleteFile(t: DlTask) { runCatching { t.uri?.let { Storage.delete(app, it) } } }

    fun downloadedOf(t: DlTask): Long =
        t.segs.sumOf { (minOf(it.pos, it.end + 1) - it.start).coerceAtLeast(0L) }

    private fun updateProgress(t: DlTask) {
        t.downloaded = downloadedOf(t)
        t.segSnap = if (t.total > 0 && t.resumable)
            t.segs.map { longArrayOf(it.start, minOf(it.end, t.total - 1), minOf(it.pos, it.end + 1)) }
        else emptyList()
        t.conns = t.segs.count { it.busy && it.remaining > 0 }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        var any = false
        for (t in tasks.toList()) {
            if (t.status != DOWNLOADING) continue
            any = true
            val d = downloadedOf(t)
            val dt = (now - t.lastTime).coerceAtLeast(1L)
            val inst = (d - t.lastBytes) * 1000 / dt
            t.speed = (t.speed * 6 + inst * 4) / 10
            t.lastBytes = d; t.lastTime = now
            updateProgress(t)
        }
        if (any && now - lastSave > 2500) { lastSave = now; save() }
    }

    // ───────────── الحفظ والاستعادة ─────────────
    @Synchronized
    fun save() {
        val arr = JSONArray()
        for (t in tasks.toList()) {
            arr.put(JSONObject().apply {
                put("id", t.id); put("url", t.url); put("orig", t.origUrl); put("ua", t.ua); put("ref", t.referer)
                put("name", t.name); put("mime", t.mime); put("uri", t.uri?.toString() ?: "")
                put("total", t.total); put("res", t.resumable); put("created", t.createdAt)
                put("st", if (t.status == DONE || t.status == FAILED) t.status else PAUSED)
                put("segs", JSONArray().apply { t.segs.forEach { put(JSONArray().put(it.start).put(it.end).put(it.pos)) } })
            })
        }
        app.getSharedPreferences("dl", Context.MODE_PRIVATE).edit().putString("tasks", arr.toString()).apply()
    }

    private fun load() {
        val s = app.getSharedPreferences("dl", Context.MODE_PRIVATE).getString("tasks", null) ?: return
        runCatching {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = DlTask(o.getString("id"), o.getString("url"), o.getString("orig"), o.getString("ua"), o.getString("ref"), o.getLong("created"))
                t.name = o.getString("name"); t.mime = o.getString("mime")
                o.getString("uri").takeIf { it.isNotEmpty() }?.let { t.uri = Uri.parse(it) }
                t.total = o.getLong("total"); t.resumable = o.getBoolean("res"); t.status = o.getInt("st")
                val sg = o.getJSONArray("segs")
                for (j in 0 until sg.length()) {
                    val a = sg.getJSONArray(j)
                    t.segs.add(Seg(a.getLong(0), a.getLong(1)).also { it.pos = a.getLong(2) })
                }
                if (t.status == DONE) t.downloaded = t.total else updateProgress(t)
                tasks.add(t)
            }
        }
    }
}
