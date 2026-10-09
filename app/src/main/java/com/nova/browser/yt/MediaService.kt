package com.nova.browser

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.WebView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL

class MediaService : Service() {
    companion object { @Volatile var instance: MediaService? = null }
    @Volatile private var stopped = false
    private var fg = true
    private val idleStop = Runnable { shutdown() }

    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this; stopped = false; fg = true
        Notif.ensureChannels(this)
        session = MediaSession(this, "NovaBrowser").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = YtMedia.control("play")
                override fun onPause() = YtMedia.control("pause")
                override fun onSeekTo(pos: Long) = YtMedia.control("seek:$pos")
                override fun onFastForward() = YtMedia.control("fwd")
                override fun onRewind() = YtMedia.control("back")
            })
            isActive = true
        }
        applySession()
        // قيود الخلفية قد ترفض بدء الخدمة في المقدمة: لا نُسقط التطبيق، بل نُنهي الخدمة بهدوء
        runCatching { ServiceCompat.startForeground(this, 2, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }.onFailure {
            stopped = true; instance = null; stopSelf()
        }
    }

    // مهلة خدمات الوسائط/المزامنة في أندرويد 15: إنهاء نظيف بدل إسقاط التطبيق
    override fun onTimeout(startId: Int, fgsType: Int) { shutdown() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val a = intent?.action ?: return START_NOT_STICKY
        if (a == "stop") shutdown() else YtMedia.control(a)
        return START_NOT_STICKY
    }

    fun refresh() = main.post {
        if (stopped || instance == null) return@post
        applySession()
        val nm = getSystemService(NotificationManager::class.java)
        val n = build()
        if (YtMedia.playing) {
            main.removeCallbacks(idleStop)
            if (fg) nm.notify(2, n)
            else runCatching { ServiceCompat.startForeground(this, 2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); fg = true }.onFailure { nm.notify(2, n) }
        } else {
            // متوقف مؤقتاً: يبقى الإشعار قابلاً للإزاحة بالسحب ولا تبقى الخدمة في المقدمة؛ وتُغلق نهائياً بعد 10 دقائق من الخمول
            nm.notify(2, n)
            if (fg) { runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH) }; fg = false }
            main.removeCallbacks(idleStop); main.postDelayed(idleStop, 10 * 60_000L)
        }
    }

    fun shutdown() {
        // نعطّل الخدمة فوراً (قبل onDestroy) كي لا يعيد أي تحديث متأخر نشر الإشعار بعد إزالته
        stopped = true; instance = null
        main.post {
            YtMedia.playing = false
            main.removeCallbacks(idleStop)
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            runCatching { getSystemService(NotificationManager::class.java).cancel(2) }
            stopSelf()
        }
    }

    // سحب التطبيق من قائمة التطبيقات الأخيرة: لا يبقى إشعار تشغيل بلا تطبيق
    override fun onTaskRemoved(rootIntent: Intent?) { shutdown(); super.onTaskRemoved(rootIntent) }

    private fun applySession() {
        val st = if (YtMedia.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
                )
                .setState(st, YtMedia.pos, if (YtMedia.playing) 1f else 0f).build()
        )
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, YtMedia.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, YtMedia.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, YtMedia.dur)
        YtMedia.art?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(md.build())
    }

    private fun pi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, MediaService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun action(icon: Int, label: String, act: String, code: Int) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi(act, code)).build()

    private fun build(): Notification {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val b = Notif.builder(this, Notif.CH_MEDIA, low = true)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(YtMedia.title.ifBlank { "YouTube" })
            .setContentText(YtMedia.artist)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setDeleteIntent(pi("stop", 9))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(YtMedia.playing)
            .addAction(action(android.R.drawable.ic_media_rew, L("رجوع 10 ثوانٍ"), "back", 1))
            .addAction(
                if (YtMedia.playing) action(android.R.drawable.ic_media_pause, L("إيقاف مؤقت"), "pause", 2)
                else action(android.R.drawable.ic_media_play, L("تشغيل"), "play", 3)
            )
            .addAction(action(android.R.drawable.ic_media_ff, L("تقديم 10 ثوانٍ"), "fwd", 4))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        YtMedia.art?.let { b.setLargeIcon(it) }
        return b.build()
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        runCatching { session.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
