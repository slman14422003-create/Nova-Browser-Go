package com.nova.browser

import android.app.SearchManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * المتصفح الافتراضي + استقبال الروابط من التطبيقات الأخرى.
 * - الطلب عبر RoleManager (نافذة النظام الرسمية)، وإن لم تتوفر نفتح إعدادات التطبيقات الافتراضية.
 * - urlFrom يحوّل أي Intent وارد (فتح رابط / مشاركة نص / بحث) إلى عنوان http(s) آمن، أو null لتجاهله.
 */
object DefaultBrowser {
    var isDefault by mutableStateOf(false); private set

    private val urlRe = Regex("https?://[^\\s<>\"]+")
    private const val MAX_LEN = 8192

    fun refresh(c: Context) {
        isDefault = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = c.getSystemService(RoleManager::class.java)
                rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && rm.isRoleHeld(RoleManager.ROLE_BROWSER)
            } else {
                // قبل أندرويد 10 لا يوجد RoleManager: نسأل النظام من يفتح http افتراضياً
                val ri = c.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")), PackageManager.MATCH_DEFAULT_ONLY)
                ri?.activityInfo?.packageName == c.packageName
            }
        }.getOrDefault(false)
    }

    /** يُرجع Intent طلب الدور، أو null إن لم يكن متاحاً (أو كان التطبيق هو الافتراضي أصلاً). */
    fun requestIntent(c: Context): Intent? = runCatching {
        if (Build.VERSION.SDK_INT < 29) return null   // نفتح إعدادات التطبيقات الافتراضية بدلاً من ذلك
        val rm = c.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_BROWSER) || rm.isRoleHeld(RoleManager.ROLE_BROWSER)) null
        else rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
    }.getOrNull()

    fun openSystemSettings(c: Context) {
        runCatching { c.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .recoverCatching { c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** العنوان المطلوب فتحه من هذا الـ Intent (بعد التطبيع)، أو null. */
    fun urlFrom(i: Intent?): String? {
        i ?: return null
        val raw: String = when (i.action) {
            Intent.ACTION_VIEW -> {
                val d: Uri = i.data ?: return null
                if (d.scheme != "http" && d.scheme != "https") return null
                d.toString()
            }
            Intent.ACTION_SEND -> {
                val t = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (t.isEmpty()) return null
                urlRe.find(t)?.value ?: t   // نص بلا رابط ← يُبحث عنه
            }
            Intent.ACTION_WEB_SEARCH -> i.getStringExtra(SearchManager.QUERY)?.trim().orEmpty().ifEmpty { return null }
            else -> return null
        }
        if (raw.length > MAX_LEN) return null
        return normalize(raw)
    }
}
