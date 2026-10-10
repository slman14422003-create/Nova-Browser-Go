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

/**
 * تسجيل دخول Google: إن اختار المستخدم Chrome Custom Tab يُفتح هناك بالحساب الأساسي،
 * وإلا نُكمل داخل الصفحة مع إضافة login_hint مرة واحدة (غيابه شرط، فلا تتكرر الحلقة). يعيد true إن عالجنا التنقّل.
 */
internal fun googleSignIn(v: WebView, h: Handlers, u: Uri): Boolean {
    val hinted = GoogleAccounts.withHint(u)
    if (GoogleAccounts.useChrome && GoogleAccounts.openInCustomTab(h.activity, hinted)) {
        toast(h.activity, L("فُتح تسجيل دخول Google في Chrome — الجلسة هناك منفصلة عن Nova"))
        return true
    }
    if (hinted != u) { v.loadUrl(hinted.toString(), Perf.privacyHeaders); return true }
    return false
}

/** مواقع وافق المستخدم على شهادتها في هذه الجلسة (أندرويد 6 فقط). */
private val oldAndroidCertOk = HashSet<String>()

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
fun createWebView(ctx: Context, tab: BrowserTab, h: Handlers): WebView = WebView(ctx).apply {
    tab.yt = false
    with(settings) {
        javaScriptEnabled = Prefs.js; domStorageEnabled = true; databaseEnabled = true
        mediaPlaybackRequiresUserGesture = false
        javaScriptCanOpenWindowsAutomatically = false   // لا نوافذ منبثقة بلا لمسة من المستخدم
        setSupportMultipleWindows(false)
        allowFileAccess = false
        setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }
    applyUa(this, tab.desktop)
    Perf.tune(this)
    Perf.installPrivacy(this)
    Shield.install(this)
    Perf.installSmooth(this)
    WebSupport.configure(this)
    WebCompat.install(this)
    Pwa.install(this)
    PasswordBridge.install(this, tab, h)
    WebMedia.install(this, tab, h)
    // المؤقتات عامة لكل الـ WebView: لو بقيت مجمّدة من جلسة سابقة (ON_STOP) تعلق الصفحات الجديدة، فنستأنفها مع كل صفحة جديدة
    runCatching { resumeTimers() }
    Wv.autofill(this, Prefs.pwMode == 1)
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, !Prefs.blockThirdCookies)
    setDownloadListener { u, ua, cd, mime, _ -> h.onDownload(u, ua, cd, mime, this.url) }
    setFindListener { active, total, _ -> tab.findInfo = if (total == 0) "0" else "${active + 1}/$total" }
    setOnLongClickListener {
        val r = hitTestResult
        val ex = r.extra ?: return@setOnLongClickListener false
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                choose(ctx, listOf(L("فتح في تبويب جديد") to { h.openTab(ex) }, L("نسخ الرابط") to { copyText(ctx, ex) }, L("مشاركة الرابط") to { shareText(ctx, ex) }))
                true
            }
            WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                choose(ctx, listOf(L("تنزيل الصورة") to { h.onDownload(ex, settings.userAgentString, null, null, this.url) }, L("فتح الصورة في تبويب جديد") to { h.openTab(ex) }))
                true
            }
            else -> false
        }
    }
    // أندرويد 6: النظام لا يخبرنا إن كان التنقّل بلمسة مستخدم، فنقدّر ذلك من آخر لمسة (للحماية من التحويلات التلقائية)
    var lastTouch = 0L
    setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN) lastTouch = android.os.SystemClock.uptimeMillis(); false }
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
            if (YtWeb.isYtUrl(u)) { v.post { YtWeb.swapIfNeeded(tab, u) }; return }   // وصلنا ليوتيوب (تحويل من الخادم): يُكمل في الـ WebView المخصّص
            tab.loading = true; tab.url = u; tab.shieldHost = Shield.hostFor(u); Shield.onPageStart(v); Perf.onPageStart(v); Pwa.onPageStart(v, u); if (WebCompat.loopTrip(v, tab, u)) return; WebCompat.onPageStart(v, tab, u)
        }
        override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) {
            // تنقّلات الصفحات أحادية الصفحة لا تستدعي onPageStarted
            if (u.startsWith("http")) {
                tab.url = u; tab.shieldHost = Shield.hostFor(u); tab.canBack = v.canGoBack(); tab.canForward = v.canGoForward()
            }
        }
        override fun onPageFinished(v: WebView, u: String) {
            tab.loading = false; tab.url = u
            tab.canBack = v.canGoBack(); tab.canForward = v.canGoForward()
            (v.parent as? SwipeRefreshLayout)?.isRefreshing = false
            WebSupport.onPageDone(v)
            WebCompat.onPageDone(tab)
            Perf.flushCookies()   // حفظ جلسات تسجيل الدخول (بحدّ أقصى كل 15 ثانية)
            PasswordBridge.onPageDone(v)
            Library.visit(u, v.title)   // سجل التصفح
        }
        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
            Shield.intercept(tab.shieldHost, r.url, r.isForMainFrame) ?: Perf.intercept(r.url, r.isForMainFrame)
        override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
            // منع انهيار التطبيق: نُسقط الـ WebView ونعيد إنشاءه بنفس العنوان
            (v.parent as? ViewGroup)?.removeView(v)
            runCatching { v.destroy() }
            tab.webView = null; tab.loading = false
            tab.holdLoad = !WebCompat.rendererGone(tab)   // 3 انهيارات خلال 90 ثانية: لا حلقة إعادة تحميل، صفحة بزر إعادة المحاولة
            tab.epoch++
            return true
        }
        override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: SslError) {
            val host = e.url?.let { hostOf(it) } ?: ""
            // أندرويد 6 وما قبل 7.1.1 لا يحوي جذر Let's Encrypt الحديث (ISRG Root X1) فتفشل مواقع كثيرة سليمة.
            // نسمح بقرار صريح من المستخدم لهذا الموقع فقط (ولخطأ «غير موثوقة» فقط، لا منتهية ولا مخالفة للنطاق)
            if (Build.VERSION.SDK_INT < 25 && e.primaryError == SslError.SSL_UNTRUSTED && host.isNotEmpty()) {
                if (host in oldAndroidCertOk) { h.proceed(); return }
                novaDialog(ctx).setTitle(host).setMessage(L("شهادة هذا الموقع غير معروفة لنظامك. على أندرويد 6 غالباً يكون السبب أن النظام قديم ولا يحوي شهادات حديثة، لكن قد يكون هجوماً أيضاً. تابع فقط إن كنت تثق بالموقع ولا تُدخل كلمات مرور أو بيانات بنكية."))
                    .setPositiveButton(L("متابعة (غير آمن)")) { _, _ -> oldAndroidCertOk.add(host); h.proceed() }
                    .setNegativeButton(L("إلغاء")) { _, _ -> h.cancel() }.setOnCancelListener { h.cancel() }.show()
                return
            }
            h.cancel()   // لا نتجاوز أخطاء الشهادات أبداً
            Security.log(L("شهادة"), (L("رُفض اتصال غير موثوق: ") + (e.url?.let { hostOf(it) })))
            val u = e.url ?: v.url ?: ""
            v.loadDataWithBaseURL(u, errorHtml(u, L("شهادة أمان الموقع غير صالحة — تم حظر الاتصال لحمايتك")), "text/html", "UTF-8", u)
        }
        override fun onSafeBrowsingHit(v: WebView, r: WebResourceRequest, threatType: Int, cb: SafeBrowsingResponse) {
            cb.backToSafety(true)
            Security.log("Safe Browsing", (L("حُظر موقع خطير: ") + (r.url.host)))
            toast(ctx, L("تم حظر موقع خطير وإعادتك إلى صفحة آمنة"))
        }
        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
            if (r.isForMainFrame && tab.upgradedFrom != null && r.url.toString() == tab.upgradedTo) {
                // الموقع لا يدعم HTTPS: رجوع إلى http مرة واحدة
                val orig = tab.upgradedFrom!!
                tab.noUpgradeHost = Uri.parse(orig).host; tab.upgradedFrom = null; tab.upgradedTo = null
                v.loadUrl(orig, Perf.privacyHeaders); return
            }
            if (r.isForMainFrame) {
                val u = r.url.toString()
                v.loadDataWithBaseURL(u, errorHtml(u, e.description.toString()), "text/html", "UTF-8", u)
            }
        }
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = nav(v, r.url, r.isForMainFrame, r.hasGesture())

        // أندرويد 6: هذه الدالة هي الوحيدة التي يستدعيها النظام (النسخة ذات WebResourceRequest من أندرويد 7)
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean =
            nav(v, Uri.parse(url), true, android.os.SystemClock.uptimeMillis() - lastTouch < 1500)

        private fun nav(v: WebView, u: Uri, mainFrame: Boolean, gesture: Boolean): Boolean {
            return when (u.scheme) {
                "http", "https" -> {
                    if (!mainFrame) false
                    else if (YtWeb.isYtUrl(u.toString())) { v.post { YtWeb.swapIfNeeded(tab, u.toString()) }; true }   // يوتيوب له WebView مخصّص منفصل
                    else if (Shield.isSpoofed(u)) { toast(ctx, L("تم حظر رابط مخادع يُخفي وجهته الحقيقية")); true }
                    else if (!Shield.navAllowed(v, gesture, u.host)) { toast(ctx, L("تم إيقاف تحويلات تلقائية متكررة")); true }
                    else if (GoogleAccounts.isSignInUrl(u) && googleSignIn(v, h, u)) true
                    else {
                        var t = Security.cleanUrl(u)
                        val host = t.host
                        if (Prefs.httpsFirst && t.scheme == "http" && host != null && host != tab.noUpgradeHost) {
                            val last = tab.lastUpgrade; val now = System.currentTimeMillis()
                            if (last != null && last.first == host && now - last.second < 5000) tab.noUpgradeHost = host   // حلقة تحويل
                            else {
                                tab.upgradedFrom = t.toString(); t = t.buildUpon().scheme("https").build()
                                tab.upgradedTo = t.toString(); tab.lastUpgrade = host to now
                                Security.log("HTTPS", (L("رُقّي ") + host))
                            }
                        }
                        if (t != u) { v.loadUrl(t.toString(), Perf.privacyHeaders); true } else false
                    }
                }
                null, "about", "data", "blob" -> false
                "intent" -> {
                    // الوجهة http(s) فقط (رابط بديل أو بيانات الرابط نفسه): كان الرابط يُبتلع فتبقى الخريطة معلّقة
                    // إن كانت الوجهة هي الصفحة الحالية نفسها (زر «افتح في التطبيق» في الخرائط) نتجاهلها: تحميلها ثانية يصنع حلقة تحديث
                    WebCompat.intentTarget(u.toString())?.takeIf { !WebCompat.samePage(v.url, it) }?.let { f ->
                        val fu = Uri.parse(f)
                        if (!Shield.isSpoofed(fu)) v.loadUrl(Security.cleanUrl(fu).toString(), Perf.privacyHeaders)
                    }
                    true
                }
                "tel", "mailto", "sms", "geo" -> {
                    runCatching { v.context.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                    true
                }
                else -> true
            }
        }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(v: WebView, p: Int) {
            val f = p / 100f   // نحدّث الحالة كل 5% فقط لتقليل إعادة التركيب
            if (p == 0 || p == 100 || kotlin.math.abs(f - tab.progress) >= 0.05f) tab.progress = f
            WebCompat.onProgress(tab, p)   // اكتمال التقدّم = انتهاء التحميل حتى لو تأخّر onPageFinished
        }
        override fun onReceivedIcon(v: WebView, icon: Bitmap?) {
            val u = v.url ?: return
            if (icon != null && u.startsWith("http")) Favicons.put(hostOf(u), icon)
        }
        override fun onReceivedTitle(v: WebView, t: String?) { if (!t.isNullOrBlank()) tab.title = t }
        override fun onShowCustomView(view: View, cb: CustomViewCallback) = h.showCustom(view, cb)
        override fun getDefaultVideoPoster(): Bitmap? = WebCompat.videoPoster()   // null يُسقط بعض إصدارات المحرك عند عرض الفيديو
        override fun onHideCustomView() = h.hideCustom()
        override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams) = h.chooser(cb, p)
        override fun onPermissionRequest(req: PermissionRequest) = h.permission(req)
        override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) = h.geo(origin, cb)
        override fun onJsAlert(v: WebView, url: String, msg: String, r: JsResult): Boolean {
            if (!Shield.dialogAllowed(hostOf(url))) { r.cancel(); return true }   // إغراق نوافذ
            novaDialog(ctx).setTitle(hostOf(url)).setMessage(msg.take(600)).setPositiveButton(L("حسناً")) { _, _ -> r.confirm() }
                .setOnCancelListener { r.cancel() }.show()
            return true
        }
        override fun onJsConfirm(v: WebView, url: String, msg: String, r: JsResult): Boolean {
            if (!Shield.dialogAllowed(hostOf(url))) { r.cancel(); return true }
            novaDialog(ctx).setTitle(hostOf(url)).setMessage(msg.take(600)).setPositiveButton(L("موافق")) { _, _ -> r.confirm() }
                .setNegativeButton(L("إلغاء")) { _, _ -> r.cancel() }.setOnCancelListener { r.cancel() }.show()
            return true
        }
        override fun onJsPrompt(v: WebView, url: String, message: String?, defaultValue: String?, r: JsPromptResult): Boolean {
            if (!Shield.dialogAllowed(hostOf(url))) { r.cancel(); return true }
            val et = android.widget.EditText(ctx).apply { setText(defaultValue ?: ""); setSingleLine(); setSelectAllOnFocus(true) }
            val box = android.widget.FrameLayout(ctx).apply {
                val pad = (24 * ctx.resources.displayMetrics.density).toInt()
                setPadding(pad, (8 * ctx.resources.displayMetrics.density).toInt(), pad, 0); addView(et)
            }
            novaDialog(ctx).setTitle(hostOf(url)).setMessage(message?.take(600)).setView(box)
                .setPositiveButton(L("موافق")) { _, _ -> r.confirm(et.text.toString()) }
                .setNegativeButton(L("إلغاء")) { _, _ -> r.cancel() }.setOnCancelListener { r.cancel() }.show()
            return true
        }
    }
}
