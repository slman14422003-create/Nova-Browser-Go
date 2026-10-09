package com.nova.browser

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class UpdPhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, INSTALLING, ERROR }

class UpdateInfo(val version: String, val notes: String, val size: Long, val apkUrl: String?, val page: String, val sha256: String = "")

/** تحديث من داخل التطبيق: فحص GitHub Releases ← تنزيل APK بشريط تقدّم ← تثبيت عبر PackageInstaller (يتطلب نفس مفتاح التوقيع). */
object Updater {
    var phase by mutableStateOf(UpdPhase.IDLE); private set
    var info by mutableStateOf<UpdateInfo?>(null); private set
    var progress by mutableFloatStateOf(0f); private set
    var error by mutableStateOf(""); private set
    /** هل تُعرض نافذة «يوجد تحديث» الآن. */
    var prompt by mutableStateOf(false); private set

    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
    private fun apkFile(c: Context) = File(File(c.cacheDir, "update").apply { mkdirs() }, "nova-update.apk")

    // ───────── حماية التحديث ─────────
    // ملف التحديث لا يُنزَّل إلا من GitHub عبر https، ويُتحقق من حجمه وبصمته (إن وفّرتها GitHub) ومن أنه حزمة التطبيق نفسه
    // وموقّعة بمفتاحه قبل تسليمه لمثبّت النظام؛ أي اختلاف يحذف الملف ويوقف التحديث.
    private val trustedHosts = listOf("github.com", "githubusercontent.com")
    private fun trustedUrl(u: String?): Boolean {
        if (u.isNullOrBlank()) return false
        val p = runCatching { Uri.parse(u) }.getOrNull() ?: return false
        val h = p.host?.lowercase() ?: return false
        return p.scheme == "https" && trustedHosts.any { h == it || h.endsWith(".$it") }
    }

    /** يعيد null إن كان الملف سليماً، وإلا سبب الرفض. */
    @Suppress("DEPRECATION")
    private fun verifyApk(c: Context, f: File): String? = try {
        val pm = c.packageManager
        // أندرويد 9+: signingInfo؛ قبله: signatures (الواجهة الجديدة غير موجودة على أندرويد 6–8)
        val flag = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val arch = pm.getPackageArchiveInfo(f.absolutePath, flag)
        if (arch == null) "invalid package"
        else if (arch.packageName != c.packageName) "package mismatch"
        else {
            val installed = pm.getPackageInfo(c.packageName, flag)
            val mine = if (android.os.Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
            val theirs = if (android.os.Build.VERSION.SDK_INT >= 28) arch.signingInfo?.apkContentsSigners else arch.signatures
            // فشل مغلق: إن لم نستطع قراءة توقيع الملف المنزَّل نرفضه بدل قبوله (النظام يفحص عند التثبيت أيضاً لكنه خط دفاع ثانٍ)
            if (theirs == null || theirs.isEmpty()) "signature unreadable"
            else if (mine != null && mine.isNotEmpty() && mine.none { m -> theirs.any { it == m } }) "signature mismatch" else null
        }
    } catch (_: Throwable) { null }   // تعذّر الفحص المسبق: يبقى فحص النظام عند التثبيت

    private fun reject(f: File, why: String) {
        runCatching { f.delete() }
        error = why; phase = UpdPhase.ERROR
    }

    /** يقارن الإصدارات رقماً رقماً (1.10.0 أحدث من 1.9.0). */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun dismissPrompt() { prompt = false }
    fun showPrompt() { if (info != null) prompt = true }

    /** فحص التحديث؛ التلقائي (force=false) يحترم الإعداد وفاصل 12 ساعة ولا يُظهر أخطاء. */
    fun check(c: Context, force: Boolean = false) {
        val repo = BuildConfig.UPDATE_REPO
        if (phase == UpdPhase.CHECKING || phase == UpdPhase.DOWNLOADING || phase == UpdPhase.INSTALLING) return
        if (repo.isBlank()) { if (force) { error = "no repo"; phase = UpdPhase.ERROR }; return }
        val app = c.applicationContext
        val sp = app.getSharedPreferences("updater", Context.MODE_PRIVATE)
        if (!force) {
            if (!Prefs.autoUpdate) return
            if (System.currentTimeMillis() - sp.getLong("last", 0L) < 12L * 3600_000L) return
        }
        phase = UpdPhase.CHECKING; error = ""
        Thread({
            try {
                val cn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                cn.connectTimeout = 8000; cn.readTimeout = 8000
                cn.setRequestProperty("Accept", "application/vnd.github+json")
                if (cn.responseCode != 200) throw java.io.IOException("HTTP " + cn.responseCode)
                val j = JSONObject(cn.inputStream.bufferedReader().use { it.readText() })
                cn.disconnect()
                val tag = j.optString("tag_name")
                var apk: String? = null; var size = 0L; var sha = ""
                val assets = j.optJSONArray("assets")
                if (assets != null) {
                    var fallback: JSONObject? = null; var best: JSONObject? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i); val n = a.optString("name").lowercase()
                        if (!n.endsWith(".apk")) continue
                        if (fallback == null) fallback = a
                        if (best == null && n.contains("release")) best = a
                    }
                    (best ?: fallback)?.let {
                        apk = it.optString("browser_download_url").takeIf { u -> trustedUrl(u) }
                        size = it.optLong("size")
                        sha = it.optString("digest").lowercase().removePrefix("sha256:").takeIf { d -> Regex("[0-9a-f]{64}").matches(d) } ?: ""
                    }
                }
                sp.edit().putLong("last", System.currentTimeMillis()).apply()
                val newer = tag.isNotBlank() && isNewer(tag, BuildConfig.VERSION_NAME)
                ui {
                    if (newer) {
                        info = UpdateInfo(tag.removePrefix("v"), j.optString("body"), size, apk, j.optString("html_url"), sha)
                        phase = if (apkFile(app).exists() && apkFile(app).length() == size && size > 0) UpdPhase.READY else UpdPhase.AVAILABLE
                        prompt = true
                        Notif.updateAvailable(app, info?.version ?: tag)
                    } else { info = null; phase = UpdPhase.UP_TO_DATE }
                }
            } catch (e: Exception) {
                ui { if (force) { error = e.message ?: "error"; phase = UpdPhase.ERROR } else phase = UpdPhase.IDLE }
            }
        }, "nova-update").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** تنزيل الـ APK إلى كاش التطبيق ثم التثبيت تلقائياً. */
    fun download(c: Context) {
        val i = info ?: return
        val app = c.applicationContext
        val url = i.apkUrl
        if (url.isNullOrBlank() || !trustedUrl(url)) { // لا ملف APK موثوق في الإصدار: نفتح صفحة الإصدار
            runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(i.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        if (phase == UpdPhase.DOWNLOADING) return
        phase = UpdPhase.DOWNLOADING; progress = 0f; error = ""
        Thread({
            try {
                val f = apkFile(app); val tmp = File(f.parentFile, "nova-update.part")
                val cn = URL(url).openConnection() as HttpURLConnection
                cn.connectTimeout = 10000; cn.readTimeout = 20000; cn.instanceFollowRedirects = true
                if (cn.responseCode != 200) throw java.io.IOException("HTTP " + cn.responseCode)
                // بعد التحويلات: يجب أن نبقى على https وعلى نطاقات GitHub
                if (!trustedUrl(cn.url.toString())) { cn.disconnect(); throw java.io.IOException("untrusted redirect") }
                val total = (cn.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: 0L).takeIf { it > 0 } ?: i.size
                var done = 0L; var lastUi = 0L
                val md = MessageDigest.getInstance("SHA-256")
                cn.inputStream.use { ins -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        if (i.size > 0 && done + n > i.size + 1024) throw java.io.IOException("size mismatch")   // أكبر من المعلن: ملف مختلف
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (total > 0 && now - lastUi > 250) { lastUi = now; val p = (done.toFloat() / total).coerceIn(0f, 1f); ui { progress = p } }
                    }
                } }
                cn.disconnect()
                val got = md.digest().joinToString("") { b -> "%02x".format(b) }
                if ((i.size > 0 && done != i.size) || (i.sha256.isNotEmpty() && got != i.sha256)) {
                    tmp.delete()
                    ui { error = "checksum mismatch"; phase = UpdPhase.ERROR }
                    return@Thread
                }
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) throw java.io.IOException("rename failed")
                val bad = verifyApk(app, f)
                if (bad != null) { ui { reject(f, bad) }; return@Thread }
                ui { progress = 1f; phase = UpdPhase.READY; install(app) }
            } catch (e: Exception) {
                ui { error = e.message ?: "error"; phase = UpdPhase.ERROR }
            }
        }, "nova-update-dl").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** يثبّت الملف المنزَّل عبر PackageInstaller؛ يطلب إذن «التثبيت من هذا المصدر» أولاً إن لزم. */
    fun install(c: Context) {
        val app = c.applicationContext
        val f = apkFile(app)
        if (!f.exists()) { error = "file missing"; phase = UpdPhase.AVAILABLE; return }
        verifyApk(app, f)?.let { reject(f, it); return }
        if (android.os.Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {   // قبل أندرويد 8 لا يوجد إذن لكل تطبيق
            error = L("فعّل «السماح بالتثبيت من هذا المصدر» ثم اضغط تثبيت")
            phase = UpdPhase.READY
            runCatching { app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        phase = UpdPhase.INSTALLING; error = ""
        Thread({
            try {
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("nova.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                    val intent = Intent(app, UpdateReceiver::class.java).setPackage(app.packageName)
                    val pend = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    s.commit(pend.intentSender)
                }
            } catch (e: Exception) {
                ui { error = e.message ?: "error"; phase = UpdPhase.READY }
            }
        }, "nova-update-install").start()
    }

    /** نتيجة جلسة التثبيت من UpdateReceiver. عند النجاح يُعاد تشغيل التطبيق من النظام. */
    fun onInstallResult(status: Int, msg: String?) {
        ui {
            if (status == PackageInstaller.STATUS_SUCCESS) { phase = UpdPhase.IDLE; error = "" }
            else { error = msg ?: ("code " + status); phase = UpdPhase.READY }
        }
    }
}
