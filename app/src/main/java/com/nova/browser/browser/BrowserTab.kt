package com.nova.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.flow.debounce
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.app.ActivityCompat
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.activity.SystemBarStyle
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.net.URLEncoder

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
