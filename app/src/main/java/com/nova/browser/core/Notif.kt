package com.nova.browser

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity

/**
 * مركز الإشعارات للتطبيق كله: القنوات، حالة الإذن، فتح إعدادات النظام، وإشعار «تحديث متاح».
 * إشعارات التنزيل الجاري وتشغيل الوسائط تعمل كخدمات مقدّمة فتبقى (يحتاجها النظام)، أما «اكتمل التنزيل» و«تحديث متاح»
 * فيتحكّم بها المستخدم من الإعدادات ← الإشعارات.
 */
object Notif {
    const val CH_DL = "dl"          // تقدّم التنزيلات الجارية (هادئ)
    const val CH_DONE = "done"      // اكتمال التنزيل
    const val CH_MEDIA = "media"    // أزرار التحكم بالتشغيل
    const val CH_UPDATE = "update"  // تحديث التطبيق
    private const val ID_UPDATE = 3

    /** يُنشأ كل شيء مرة واحدة؛ إعادة الإنشاء بنفس المعرّف لا تؤثر على اختيارات المستخدم في النظام. */
    fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return   // القنوات من أندرويد 8 فقط
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_DL, L("التنزيلات الجارية"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, L("اكتمال التنزيل"), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_MEDIA, L("تشغيل الوسائط"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_UPDATE, L("تحديثات التطبيق"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** منشئ إشعار يعمل من أندرويد 6: القناة من أندرويد 8، وقبلها أولوية منخفضة بدل القناة الهادئة. */
    @Suppress("DEPRECATION")
    fun builder(c: Context, channel: String, low: Boolean = false): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, channel)
        else Notification.Builder(c).apply { if (low) setPriority(Notification.PRIORITY_LOW) }

    /** هل يحتاج الجهاز إذناً صريحاً؟ (أندرويد 13+) */
    val needsPermission get() = Build.VERSION.SDK_INT >= 33

    /** هل الإشعارات مسموحة للتطبيق (الإذن + عدم إيقافها من النظام)؟ */
    fun enabled(c: Context): Boolean {
        if (needsPermission && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching { androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled() }.getOrDefault(true)
    }

    /** إعدادات إشعارات التطبيق في النظام (تشمل القنوات). */
    fun openSystemSettings(c: Context) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { c.startActivity(i) }.onFailure {
            runCatching {
                c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** إشعار «تحديث متاح» مرة واحدة لكل إصدار. */
    fun updateAvailable(c: Context, version: String) {
        if (!Prefs.notifUpdate || !enabled(c)) return
        val conf = ConfStore.open(c)
        if (conf.getString("notif_upd_ver", "") == version) return
        conf.putString("notif_upd_ver", version)
        runCatching {
            ensureChannels(c)
            val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("upd", true)
            val pi = PendingIntent.getActivity(c, ID_UPDATE, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            c.getSystemService(NotificationManager::class.java).notify(
                ID_UPDATE,
                builder(c, CH_UPDATE).setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(L("تحديث جديد لـ Nova")).setContentText(L("الإصدار ") + version.removePrefix("v") + L(" جاهز للتنزيل"))
                    .setContentIntent(pi).setAutoCancel(true).build()
            )
        }
    }
}

/**
 * حالة الإشعارات داخل الواجهة: تُحدَّث عند العودة من إعدادات النظام، وتوفّر طلب الإذن بسلاسة.
 * إن رفض المستخدم نهائياً (النظام لا يعرض النافذة بعد رفضين) نفتح إعدادات النظام مباشرة.
 */
class NotifState(val enabled: Boolean, val request: () -> Unit)

@Composable
fun rememberNotifState(onResult: (Boolean) -> Unit = {}): NotifState {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lc = (ctx as? ComponentActivity)?.lifecycle
    DisposableEffect(lc) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lc?.addObserver(o)
        onDispose { lc?.removeObserver(o) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        Prefs.pickNotifAsked(true)
        onResult(ok)
        if (!ok) {
            val act = ctx as? Activity
            // «لا تسأل مجدداً» فعلياً: النظام لن يعرض النافذة، فنفتح الإعدادات
            if (act != null && !act.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) Notif.openSystemSettings(ctx)
        }
    }
    val enabled = remember(tick) { Notif.enabled(ctx) }
    return remember(enabled, launcher) {
        NotifState(enabled) {
            if (Notif.needsPermission && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else Notif.openSystemSettings(ctx)
        }
    }
}

/** نافذة شرح قبل طلب إذن الإشعارات (تظهر مرة واحدة عند أول حاجة: تنزيل أو تشغيل في الخلفية). */
@Composable
fun NotifPermissionDialog(onAllow: () -> Unit, onLater: () -> Unit) {
    NovaDialog(
        title = L("تفعيل الإشعارات"), icon = Icons.Default.Notifications, onDismiss = onLater,
        confirmText = L("السماح"), onConfirm = onAllow, dismissText = L("لاحقاً")
    ) {
        DialogText(L("لمتابعة التنزيلات وأزرار التشغيل في الخلفية، ومعرفة اكتمال التنزيل وتوفّر التحديثات، يحتاج Nova إلى إذن الإشعارات."))
        DialogText(L("يمكنك تخصيصها أو إيقافها لاحقاً من الإعدادات ← الإشعارات."))
    }
}
