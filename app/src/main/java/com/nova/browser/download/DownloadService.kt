package com.nova.browser

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat

/**
 * خدمة التنزيل (إشعار التقدّم في شريط الإشعارات).
 *  - يظهر الإشعار فوراً (أندرويد 12+ كان يؤخّره ~10 ثوانٍ) ويحمل أزرار «إيقاف مؤقت» و«إلغاء».
 *  - بعد «إيقاف مؤقت» يبقى إشعار قابل للإزاحة بزر «استئناف» بدل أن يختفي كل شيء.
 *  - أثناء دمج/تحويل ملفات يوتيوب يبقى إشعار «جارٍ المعالجة» (كانت الخدمة تتوقف فيختفي الإشعار).
 *  - أندرويد 15: مهلة خدمات dataSync (6 ساعات) تُعالَج بدل أن تُسقط التطبيق.
 */
class DownloadService : Service() {
    companion object {
        const val ACT_PAUSE = "nova.dl.pause"
        const val ACT_RESUME = "nova.dl.resume"
        const val ACT_CANCEL = "nova.dl.cancel"
        private const val ID_PROGRESS = 1
        private const val ID_PAUSED = 4
        /** التنزيلات التي أوقفها المستخدم من الإشعار في هذه الجلسة (لزر الاستئناف). */
        private val pausedIds = java.util.Collections.synchronizedSet(HashSet<String>())
    }

    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    @Volatile private var stopped = false
    private var userPaused = false
    private var lastStartId = 0   // stopSelf(id) لا يُنهي الخدمة إن وصل تنزيل جديد بعد قرار الإيقاف (سباق نادر كان يترك التنزيل بلا إشعار)

    private fun nm() = getSystemService(NotificationManager::class.java)
    private fun active() = Downloader.tasks.filter { it.status == Downloader.DOWNLOADING || it.status == Downloader.PREPARING }
    private fun busy() = Downloader.work.get() > 0

    private val loop = object : Runnable {
        override fun run() {
            if (stopped) return
            val act = active()
            if (act.isEmpty() && !busy()) { finishService(); return }
            runCatching { nm().notify(ID_PROGRESS, build(act)) }
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Downloader.init(this)
        Downloader.ensureEngine()
        Notif.ensureChannels(this)
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:dl")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopped = false
        lastStartId = startId
        when (intent?.action) {
            ACT_PAUSE -> {
                val now = active()
                now.forEach { pausedIds.add(it.id) }
                if (now.isNotEmpty()) userPaused = true
                Downloader.pauseAll()
            }
            ACT_RESUME -> {
                val ids = synchronized(pausedIds) { pausedIds.toList().also { pausedIds.clear() } }
                Downloader.tasks.toList().filter { it.id in ids && it.status == Downloader.PAUSED }.forEach { Downloader.resume(it) }
            }
            ACT_CANCEL -> active().forEach { Downloader.cancel(it) }
        }
        runCatching { nm().cancel(ID_PAUSED) }
        // الاستدعاء إلزامي خلال 5 ثوانٍ من startForegroundService؛ فشله (قيود الخلفية) لا يُسقط التطبيق
        val ok = runCatching {
            ServiceCompat.startForeground(this, ID_PROGRESS, build(active()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)   // النوع يُطبَّق من أندرويد 10 فقط
        }.isSuccess
        if (!ok) { stopped = true; stopSelf(startId); return START_NOT_STICKY }
        h.removeCallbacks(loop); h.postDelayed(loop, 1000)
        return START_NOT_STICKY
    }

    /** يُستدعى عند نفاد مهلة الخدمة (أندرويد 15+): نُنهي الخدمة فوراً وإلا يُنهي النظام التطبيق. التنزيل يكمل ما دامت العملية حيّة. */
    override fun onTimeout(startId: Int, fgsType: Int) { finishService() }
    override fun onTimeout(startId: Int) { finishService() }

    private fun finishService() {
        if (stopped) return
        stopped = true
        h.removeCallbacks(loop)
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }   // الثابت الأصلي يحتاج أندرويد 7
        runCatching { nm().cancel(ID_PROGRESS) }
        if (userPaused) postPaused()
        stopSelf(lastStartId)
    }

    private fun contentIntent(): PendingIntent {
        val open = Intent(this, MainActivity::class.java).putExtra("dl", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionOf(icon: Int, label: String, action: String, code: Int, foreground: Boolean = false): Notification.Action {
        val i = Intent(this, DownloadService::class.java).setAction(action)
        val pi = if (foreground && Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun postPaused() {
        if (!Notif.enabled(this)) return
        val n = Downloader.tasks.count { it.id in pausedIds && it.status == Downloader.PAUSED }
        if (n == 0) return
        runCatching {
            nm().notify(
                ID_PAUSED,
                Notif.builder(this, Notif.CH_DL, low = true).setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(L("التنزيلات متوقفة مؤقتاً"))
                    .setContentText(if (n == 1) L("تنزيل واحد في الانتظار") else "$n" + L(" تنزيلات في الانتظار"))
                    .setCategory(Notification.CATEGORY_STATUS).setShowWhen(false)
                    .setContentIntent(contentIntent()).setAutoCancel(false).setOngoing(false)
                    .addAction(actionOf(android.R.drawable.ic_media_play, L("استئناف"), ACT_RESUME, 12, foreground = true))
                    .build()
            )
        }
    }

    private fun build(act: List<DlTask>): Notification {
        val total = act.sumOf { maxOf(it.total, 0L) }
        val done = act.sumOf { it.downloaded }
        val speed = act.sumOf { it.speed }
        val n = act.size
        val b = Notif.builder(this, Notif.CH_DL, low = true).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                when {
                    n == 0 -> L("جارٍ معالجة الملفات…")
                    n == 1 -> act[0].name
                    else -> "$n" + L(" تنزيلات")
                }
            )
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent()).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (n == 0) {
            b.setProgress(0, 0, true)
            return b.build()
        }
        b.setContentText(if (total > 0) "${fmtSize(done)} / ${fmtSize(total)} • ${fmtSpeed(speed)}" else "${fmtSize(done)} • ${fmtSpeed(speed)}")
        if (total > 0) {
            val pct = (done * 1000 / total).toInt().coerceIn(0, 1000)
            b.setProgress(1000, pct, false).setSubText("${pct / 10}%")
        } else b.setProgress(0, 0, true)
        b.addAction(actionOf(android.R.drawable.ic_media_pause, L("إيقاف مؤقت"), ACT_PAUSE, 10))
        b.addAction(actionOf(android.R.drawable.ic_menu_close_clear_cancel, L("إلغاء"), ACT_CANCEL, 11))
        return b.build()
    }

    override fun onDestroy() {
        stopped = true
        h.removeCallbacksAndMessages(null)
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
