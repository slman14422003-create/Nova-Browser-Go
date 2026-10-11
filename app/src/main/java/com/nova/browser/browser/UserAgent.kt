package com.nova.browser

import android.content.*
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

private val UA_VERSION_RE = Regex("Version/\\S+ ")
private val UA_ANDROID_RE = Regex("Android [^;)]+; [^)]*\\)")
private val UA_CHROME_RE = Regex("Chrome/(\\d+)\\.[\\d.]+")
const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

fun applyUa(wv: WebView, desktop: Boolean) {
    val s = wv.settings
    s.userAgentString = if (desktop) WebEngine.desktopUa()
    else reduceUa(Perf.defaultUa(wv.context).replace("; wv", "").replace(UA_VERSION_RE, ""))
    WebEngine.applyMetadata(wv, desktop)
    s.useWideViewPort = true
    s.loadWithOverviewMode = true   // الصفحات الأعرض من الشاشة (بلا viewport) تُصغَّر لتناسب العرض
}

/** مواقع يوتيوب: التمرير فيها يتم داخل عناصر الصفحة (وليس scrollY للـ WebView) فيجب تعطيل السحب للتحديث عليها. */
fun isYtHost(u: String?): Boolean {
    val h = runCatching { Uri.parse(u ?: "").host }.getOrNull()?.removePrefix("www.") ?: return false
    return h == "youtube.com" || h.endsWith(".youtube.com") || h == "youtu.be"
}

/** وكيل مستخدم مختصر مثل Chrome: بلا موديل الجهاز ولا رقم الإصدار الكامل، فيتطابق مع ملايين المستخدمين (يقلّل التفرّد). */
fun reduceUa(ua: String): String {
    if (!Prefs.antiFingerprint) return ua
    return ua.replace(UA_ANDROID_RE, "Android 10; K)").replace(UA_CHROME_RE, "Chrome/\$1.0.0.0")
}
