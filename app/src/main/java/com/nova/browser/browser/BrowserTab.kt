package com.nova.browser

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

class BrowserTab(val id: Int, startUrl: String = "") {
    var upgradedFrom: String? = null        // رابط http الأصلي إذا رُقّي إلى https
    var upgradedTo: String? = null
    var noUpgradeHost: String? = null
    var lastUpgrade: Pair<String, Long>? = null
    var url by mutableStateOf(startUrl)
    var title by mutableStateOf(L("تبويب جديد"))
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var desktop by mutableStateOf(Prefs.desktop)
    var finding by mutableStateOf(false)
    var findInfo by mutableStateOf("")
    var saved: android.os.Bundle? = null      // حالة الصفحة عند تحرير الـ WebView لتوفير الذاكرة
    var lastUsed = 0L
    var ytPlaying by mutableStateOf(false)
    var mediaPlaying by mutableStateOf(false)   // فيديو/صوت بصوت يعمل في هذا التبويب (غير يوتيوب)
    var mediaVideo by mutableStateOf(false)     // الوسائط الحالية فيديو (تصلح للنافذة المنبثقة)
    var thumb by mutableStateOf<Bitmap?>(null)   // معاينة مصغّرة للصفحة تظهر في شاشة التبويبات
    var tag by mutableIntStateOf(0)        // علامة لونية: 0 بلا، 1..6 ألوان
    var pinned by mutableStateOf(false)    // تبويب مثبّت (لا يُحرَّر ولا يُغلق بـ"إغلاق الكل")
    var epoch by mutableIntStateOf(0)   // يزيد عند انهيار عملية العرض لإعادة إنشاء الـ WebView
    var webView: WebView? = null
    var crashes = 0                      // انهيارات عملية العرض المتتابعة (قاطع الحلقة في WebCompat)
    var lastCrash = 0L
    var holdLoad = false                 // أعد الإنشاء بصفحة خطأ بدل تحميل الرابط (بعد انهيارات متكررة)
    @Volatile var shieldHost: String = ""   // نطاق الصفحة الحالية (يقرؤه فحص الطلبات من خيط الشبكة)
    var yt = false                       // هذا التبويب يعرض يوتيوب في الـ WebView المخصّص (YtWeb) لا العام
}

class Handlers(
    val activity: ComponentActivity,
    val chooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    val permission: (PermissionRequest) -> Unit,
    val geo: (String, GeolocationPermissions.Callback) -> Unit,
    val openTab: (String) -> Unit,
    val showCustom: (View, WebChromeClient.CustomViewCallback) -> Unit,
    val hideCustom: () -> Unit,
    val onDownload: (String, String?, String?, String?, String?) -> Unit,
    val onLoginForm: (BrowserTab, WebView, String) -> Unit = { _, _, _ -> },
    val onCredential: (String, String, String) -> Unit = { _, _, _ -> },
    val onYtState: (BrowserTab) -> Unit = {},
    val onMedia: (BrowserTab) -> Unit = {},        // تغيّرت حالة وسائط تبويب عام (WebMedia)
    val onMediaPlay: (BrowserTab) -> Unit = {}     // بدأ تشغيل بصوت: أوقف الباقي
)
