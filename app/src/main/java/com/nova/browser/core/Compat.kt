package com.nova.browser

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.WebSettings
import androidx.core.app.ActivityManagerCompat

/**
 * كشف الأجهزة الضعيفة (Android Go / رام قليلة / أندرويد 7.1 وأقدم).
 * - device: حكم تلقائي على الجهاز نفسه (يُحسب مرة واحدة).
 * - on:     الوضع الخفيف الفعلي = اختيار المستخدم (تلقائي/تشغيل/إيقاف) فوق حكم الجهاز.
 * في الوضع الخفيف: حركات أقصر، عدد أقل من الصفحات الحيّة، وإعدادات افتراضية أخف (بصمة/جلب مسبق معطّلان).
 */
object LowEnd {
    @Volatile var device = false; private set
    @Volatile private var inited = false

    fun init(c: Context) {
        if (inited) return
        inited = true
        runCatching {
            val am = c.applicationContext.getSystemService(ActivityManager::class.java)
            val lowRam = am != null && ActivityManagerCompat.isLowRamDevice(am)
            val heap = am?.memoryClass ?: 256
            device = lowRam || heap <= 128 || Build.VERSION.SDK_INT <= 25 || Runtime.getRuntime().availableProcessors() <= 2
        }
    }

    val on: Boolean get() = when (Prefs.lite) { 1 -> true; 2 -> false; else -> device }
}

/** إشعارات تعمل من أندرويد 6: القنوات والـ Builder ذو القناة موجودان من أندرويد 8 فقط. */
object Notif {
    fun channel(c: Context, id: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT < 26) return
        c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    @Suppress("DEPRECATION")
    fun builder(c: Context, channel: String, low: Boolean = false): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, channel)
        else Notification.Builder(c).apply { if (low) setPriority(Notification.PRIORITY_LOW) }
}

/** استدعاءات WebView/View التي لا توجد قبل أندرويد 8 (استدعاؤها مباشرة يُسقط التطبيق على أندرويد 6/7 بـ NoSuchMethodError). */
object Wv {
    fun safeBrowsing(s: WebSettings) { if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true) }

    fun autofill(v: View, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 26)
            v.importantForAutofill = if (on) View.IMPORTANT_FOR_AUTOFILL_YES else View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
}
