package com.nova.browser

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class DownloadService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null

    private fun active() = Downloader.tasks.filter { it.status == Downloader.DOWNLOADING || it.status == Downloader.PREPARING }

    private val loop = object : Runnable {
        override fun run() {
            val act = active()
            if (act.isEmpty()) { ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf(); return }
            getSystemService(NotificationManager::class.java).notify(1, build(act))
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Downloader.init(this)
        Downloader.ensureEngine()
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:dl")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, 1, build(active()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        h.removeCallbacks(loop); h.postDelayed(loop, 1000)
        return START_NOT_STICKY
    }

    private fun build(act: List<DlTask>): Notification {
        val total = act.sumOf { maxOf(it.total, 0L) }
        val done = act.sumOf { it.downloaded }
        val speed = act.sumOf { it.speed }
        val open = Intent(this, MainActivity::class.java).putExtra("dl", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notif.builder(this, "dl", low = true).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (act.size == 1) act[0].name else ("" + (act.size) + L(" تنزيلات")))
            .setContentText("${fmtSize(done)} • ${fmtSpeed(speed)}")
            .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true)
        if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt().coerceIn(0, 1000), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onDestroy() {
        h.removeCallbacks(loop)
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
