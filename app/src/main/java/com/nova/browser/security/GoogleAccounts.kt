package com.nova.browser

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * حسابات Google المضافة إلى المتصفح (البريد فقط — لا كلمات مرور ولا رموز دخول).
 *
 * ما تفعله فعلاً:
 *  1) تقترح البريد في نماذج تسجيل الدخول عند ظهور حقل الدخول (FillBanner).
 *  2) تضيف login_hint إلى صفحات accounts.google.com فيُملأ البريد مسبقاً.
 *  3) خيار فتح تسجيل دخول Google في Chrome Custom Tab (Google ترفض كثيراً الدخول داخل WebView).
 *
 * ما لا تفعله: لا تنقل جلسة Google إلى WebView ولا تدخل تلقائياً إلى المواقع — لا يسمح أندرويد/Google بذلك.
 */
object GoogleAccounts {
    val accounts = mutableStateListOf<String>()
    var primary by mutableStateOf(""); private set
    var suggest by mutableStateOf(true); private set      // اقتراح البريد في نماذج الدخول
    var useChrome by mutableStateOf(false); private set   // فتح تسجيل دخول Google في Custom Tab

    private var sp: ConfStore? = null
    private val emailRe = Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val signInPaths = listOf("/signin", "/v3/signin", "/ServiceLogin", "/o/oauth2", "/AccountChooser", "/InteractiveLogin")

    fun init(c: Context) {
        val p = ConfStore.open(c); sp = p
        accounts.clear()
        p.getString("gacc", "").split(',').map { it.trim().lowercase() }.filter { isValid(it) }.distinct().forEach { accounts.add(it) }
        primary = p.getString("gprimary", "").lowercase().takeIf { it in accounts } ?: accounts.firstOrNull().orEmpty()
        suggest = p.getBoolean("gsuggest", true)
        useChrome = p.getBoolean("gchrome", false)
    }

    fun isValid(email: String) = email.length <= 120 && emailRe.matches(email.trim())

    private fun save() {
        sp?.putString("gacc", accounts.joinToString(","))
        sp?.putString("gprimary", primary)
    }

    /** يضيف الحساب؛ false إن كان البريد غير صالح. */
    fun add(raw: String): Boolean {
        val e = raw.trim().lowercase()
        if (!isValid(e)) return false
        if (e !in accounts) accounts.add(e)
        if (primary.isEmpty()) primary = e
        save()
        return true
    }

    fun remove(email: String) {
        accounts.remove(email)
        if (primary == email) primary = accounts.firstOrNull().orEmpty()
        save()
    }

    fun choosePrimary(email: String) { if (email in accounts) { primary = email; save() } }
    fun pickSuggest(v: Boolean) { suggest = v; sp?.putBoolean("gsuggest", v) }
    fun pickUseChrome(v: Boolean) { useChrome = v; sp?.putBoolean("gchrome", v) }

    /** هل هذا الرابط صفحة تسجيل دخول Google؟ */
    fun isSignInUrl(u: Uri): Boolean {
        if (u.scheme != "https" || u.host != "accounts.google.com") return false
        val path = u.path ?: return false
        return signInPaths.any { path.startsWith(it) }
    }

    /** يضيف login_hint بالحساب الأساسي إن لم يكن موجوداً (يعيد نفس الكائن إن لم يتغير شيء). */
    fun withHint(u: Uri): Uri {
        if (primary.isEmpty() || !u.isHierarchical || u.getQueryParameter("login_hint") != null) return u
        return u.buildUpon().appendQueryParameter("login_hint", primary).build()
    }

    /** يفتح الرابط في Chrome Custom Tab؛ false إن لم يوجد متصفح يدعمه. */
    fun openInCustomTab(a: Activity, u: Uri): Boolean {
        val pkg = CustomTabsClient.getPackageName(a, null) ?: return false
        val ci = CustomTabsIntent.Builder().build()
        ci.intent.setPackage(pkg)
        return runCatching { ci.launchUrl(a, u) }.isSuccess
    }
}
