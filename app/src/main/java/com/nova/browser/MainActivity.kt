package com.nova.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
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

class MainActivity : ComponentActivity() {
    private var dlTrigger by mutableIntStateOf(0)
    /** رابط وصل من تطبيق آخر والتطبيق شغّال: (رقم تسلسلي, عنوان). الرقم يجعل نفس الرابط يُفتح كل مرة. */
    private var incoming by mutableStateOf<Pair<Int, String>?>(null)
    private var incomingSeq = 0
    @Volatile private var ready = false

    companion object {
        private var cleanedThisProcess = false
        /** صحيح بعد تهيئة الإعدادات والمخازن في هذه العملية (نافذة اللوحات تعتمد عليها). */
        @Volatile var initialized = false
    }

    // ---- نافذة منبثقة (Picture-in-Picture) ----
    var inPip by mutableStateOf(false)
    var pipAuto = false
    var fullscreenActive = false
    var wvProvider: () -> WebView? = { null }

    private val PIP_PLAY = "nova.pip.play"
    private val PIP_PAUSE = "nova.pip.pause"
    private val PIP_BACK = "nova.pip.back"
    private val PIP_FWD = "nova.pip.fwd"
    private var pipExitAt = 0L          // لحظة الخروج من المنبثقة (لاكتشاف الإغلاق بزر ✕ حتى لو تأخر onStop)
    private var pipPlayingShown: Boolean? = null
    private var pipReceiverOn = false

    /** أزرار التحكم داخل النافذة المنبثقة نفسها: رجوع/تشغيل-إيقاف/تقديم (لم تكن موجودة). */
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                PIP_PLAY -> YtMedia.control("play")
                PIP_PAUSE -> YtMedia.control("pause")
                PIP_BACK -> YtMedia.control("back")
                PIP_FWD -> YtMedia.control("fwd")
            }
            window.decorView.postDelayed({ refreshPip() }, 350)   // تبديل أيقونة التشغيل/الإيقاف بعد استجابة الصفحة
        }
    }

    // النافذة المنبثقة (Picture-in-Picture) من أندرويد 8؛ على أندرويد 6/7 تُهمل بصمت
    /** isInPictureInPictureMode موجودة من أندرويد 7 والمنبثقة الفعلية من 8: لا نقرأها قبل ذلك كي لا يسقط التطبيق بـ NoSuchMethodError. */
    private fun pipNowActive(): Boolean = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode

    @android.annotation.TargetApi(26)
    private fun pipAction(icon: Int, label: String, act: String, code: Int): android.app.RemoteAction {
        val pi = PendingIntent.getBroadcast(this, code, Intent(act).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, icon), label, label, pi)
    }

    @android.annotation.TargetApi(26)
    private fun pipParams(): android.app.PictureInPictureParams {
        val hasVideo = YtMedia.owner != null
        val b = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9))
        if (hasVideo) {
            val playing = YtMedia.playing
            pipPlayingShown = playing
            b.setActions(listOf(
                pipAction(android.R.drawable.ic_media_rew, L("رجوع 10 ثوانٍ"), PIP_BACK, 21),
                if (playing) pipAction(android.R.drawable.ic_media_pause, L("إيقاف مؤقت"), PIP_PAUSE, 22)
                else pipAction(android.R.drawable.ic_media_play, L("تشغيل"), PIP_PLAY, 23),
                pipAction(android.R.drawable.ic_media_ff, L("تقديم 10 ثوانٍ"), PIP_FWD, 24)
            ))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(pipAuto).setSeamlessResizeEnabled(false)
            if (hasVideo) b.setTitle(YtMedia.title.ifBlank { null }).setSubtitle(YtMedia.artist.ifBlank { null })
        }
        // انتقال أنعم: نحدّد مكان المشغّل في الصفحة ليتحوّل منه إطار المنبثقة
        if (hasVideo && !fullscreenActive) runCatching {
            val w = wvProvider()
            val r = android.graphics.Rect()
            if (w != null && w.getGlobalVisibleRect(r) && r.width() > 0) {
                val hh = minOf(r.width() * 9 / 16, r.height())
                if (hh > 0) b.setSourceRectHint(android.graphics.Rect(r.left, r.top, r.right, r.top + hh))
            }
        }
        return b.build()
    }

    fun refreshPip() { if (Build.VERSION.SDK_INT >= 26) runCatching { setPictureInPictureParams(pipParams()) } }

    /** تُستدعى عند كل حالة تشغيل جديدة: نحدّث أزرار المنبثقة فقط عندما يتغيّر تشغيل/إيقاف. */
    fun onYtPlayState() { if (pipPlayingShown != YtMedia.playing) refreshPip() }

    override fun onStart() {
        super.onStart()
        if (!pipReceiverOn) {
            val f = IntentFilter().apply { addAction(PIP_PLAY); addAction(PIP_PAUSE); addAction(PIP_BACK); addAction(PIP_FWD) }
            pipReceiverOn = runCatching { ContextCompat.registerReceiver(this, pipReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED) }.isSuccess
        }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    // أثناء أي إيقاف مؤقت للـ Activity (زر الرئيسية/المنبثق) نعلم الصفحة أنها في الخلفية قبل أن يتصرف يوتيوب
    override fun onPause() {
        super.onPause()
        if (Prefs.ytBg && YtMedia.owner != null) {
            YtLog.add("native onPause")
            YtWeb.background(wvProvider(), true)
        }
    }

    override fun onResume() {
        super.onResume()
        DefaultBrowser.refresh(this)   // قد يغيّر المستخدم الافتراضي من إعدادات النظام
        pipExitAt = 0L
        inPip = pipNowActive()   // لا نترك الحالة عالقة إن فاتنا إشعار الخروج من المنبثقة
        YtWeb.pip = inPip
        if (!inPip) { val w = wvProvider(); YtWeb.background(w, false); if (YtWeb.owns(w)) YtWeb.recover(w) }   // تنظيف أنماط المنبثقة إن بقيت
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val w = wvProvider()
        // الدخول للمنبثقة يطلق تغيير الإعدادات قبل onPictureInPictureModeChanged: لا نعتبره دوراناً (كان يعيد قياس الفيديو ويُفسد المنبثقة)
        val pipNow = inPip || pipNowActive()
        if (pipNow) YtWeb.pip = true
        if (YtWeb.owns(w)) {
            YtLog.add("config orientation=" + newConfig.orientation + " fullscreen=" + fullscreenActive + " pip=" + pipNow)
            if (!pipNow) YtWeb.afterRotate(w)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipAuto && Build.VERSION.SDK_INT < 31) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        YtWeb.pip = isInPictureInPictureMode
        pipExitAt = if (isInPictureInPictureMode) 0L else android.os.SystemClock.elapsedRealtime()
        YtLog.add("native pip=$isInPictureInPictureMode fullscreen=$fullscreenActive")
        if (isInPictureInPictureMode) refreshPip()
        // الخروج من المنبثقة بلا عودة للواجهة = أُغلقت بزر ✕ (بعض الأجهزة لا تستدعي onStop فوراً): نتحقق بعد لحظة
        if (!isInPictureInPictureMode) window.decorView.postDelayed({
            if (!isFinishing && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) onPipDismissed()
        }, 500)
        val w = wvProvider() ?: return
        if (YtWeb.owns(w)) YtWeb.onPip(w, isInPictureInPictureMode, fullscreenActive)
        else {
            if (isInPictureInPictureMode) { w.resumeTimers(); w.onResume() }
            else { w.postDelayed({ w.requestLayout(); w.invalidate() }, 150) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        enableEdgeToEdge()
        Prefs.init(this)
        GoogleAccounts.init(this)
        Adaptive.init(this)
        Library.init(this)
        Thread({ Vault.init(applicationContext) }, "nova-vault").start()   // فك التشفير (Keystore) خارج الخيط الرئيسي
        Security.init(this)
        Perf.init(this)
        Shield.init(this)
        WebEngine.init(this)
        NetKit.init(this)
        WebSupport.init(this)
        Thread({ Security.deviceWarnings(applicationContext).forEach { Security.log(L("الجهاز"), it) } }, "nova-sec").start()
        Downloader.init(this)
        Notif.ensureChannels(applicationContext)
        DefaultBrowser.refresh(this)
        initialized = true
        if (intent?.getBooleanExtra("upd", false) == true) Updater.showPrompt()
        // لا نعيد فتح الرابط عند إعادة إنشاء الـ Activity (تدوير/استعادة العملية)
        val start = if (savedInstanceState == null) DefaultBrowser.urlFrom(intent) ?: "" else ""
        if (intent?.getBooleanExtra("dl", false) == true) dlTrigger++
        // نُبقي الـ Splash ظاهرة حتى ينتهي تنظيف المؤقت وتُعرض الواجهة
        splash.setKeepOnScreenCondition { !ready }
        if (Prefs.autoClean && !cleanedThisProcess) {
            cleanedThisProcess = true
            // التنظيف يجري قبل إنشاء أي WebView كي لا تكون ملفات الكاش مفتوحة
            Thread({
                runCatching { CacheCleaner.cleanAll(applicationContext) }
                runOnUiThread { if (!isFinishing && !isDestroyed) showUi(start) else ready = true }
            }, "nova-clean").start()
        } else showUi(start)
    }

    /** عند السخونة/توفير الطاقة: تحديد معدل التحديث 60Hz (يخفض حرارة الشاشة والمعالج على شاشات 90/120Hz). */
    private fun applyRefreshCap(level: Int) {
        val want = if (Prefs.cap60 && level >= 1) 60f else 0f
        val lp = window.attributes
        if (lp.preferredRefreshRate != want) { lp.preferredRefreshRate = want; window.attributes = lp }
    }

    private fun showUi(start: String) {
        setContent {
            val lvl = Adaptive.level
            LaunchedEffect(lvl, Prefs.cap60) { applyRefreshCap(lvl) }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(4000); Updater.check(applicationContext); WebEngine.checkLatest(applicationContext) }   // بعد استقرار الواجهة
            NovaTheme(this) { BrowserApp(start, dlTrigger, inPip, incoming) }
        }
        ready = true
        // تسخين محرك الـ WebView عند أول فراغ، حتى لا يتقطع أول بحث
        android.os.Looper.myQueue().addIdleHandler { Perf.warmUp(applicationContext); if (!LowEnd.on) runCatching { WebView(applicationContext).destroy() }; false }
    }
    private fun onPipDismissed() {
        YtLog.add("native pip dismissed")
        inPip = false
        val w = wvProvider()
        if (YtWeb.owns(w)) YtWeb.background(w, false)
        YtMedia.stop(pause = true)   // إغلاق المنبثقة يوقف الفيديو ويزيل الإشعار
    }

    override fun onStop() {
        super.onStop()
        Perf.flushCookies(true)
        if (pipReceiverOn) { runCatching { unregisterReceiver(pipReceiver) }; pipReceiverOn = false }
        // onPictureInPictureModeChanged(false) يسبق onStop دائماً، فـ inPip تكون false هنا؛ نعتمد على لحظة الخروج: إن أعقبه إيقاف بلا عودة = إغلاق ✕
        val exited = pipExitAt != 0L && android.os.SystemClock.elapsedRealtime() - pipExitAt < 4000
        if (exited && !isFinishing) { pipExitAt = 0L; onPipDismissed() }
        else if (inPip && !pipNowActive()) onPipDismissed()
    }

    override fun onDestroy() {
        // الخروج من التطبيق نهائياً: لا يبقى إشعار تشغيل لفيديو لم يعد له صفحة
        if (isFinishing) YtMedia.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("dl", false)) dlTrigger++
        if (intent.getBooleanExtra("upd", false)) Updater.showPrompt()
        DefaultBrowser.urlFrom(intent)?.let { incoming = ++incomingSeq to it }   // رابط من واتساب/تيليجرام… والتطبيق مفتوح
    }
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun BrowserApp(startUrl: String, dlTrigger: Int, inPip: Boolean = false, incoming: Pair<Int, String>? = null) {
    val activity = LocalContext.current as ComponentActivity
    val cs = MaterialTheme.colorScheme
    val prefs = remember { activity.getSharedPreferences("nova", Context.MODE_PRIVATE) }
    val tabs = remember {
        mutableStateListOf<BrowserTab>().apply {
            (if (Prefs.restore) prefs.getString("tabs", "")!! else "").split("\n").filter { it.isNotBlank() }
                .forEachIndexed { i, u -> add(BrowserTab(i, if (u == "-") "" else u)) }
            if (startUrl.isNotBlank()) add(BrowserTab(size, startUrl))
            if (isEmpty()) add(BrowserTab(0, ""))
            if (Prefs.restore) prefs.getString("meta", "")!!.split(",").forEachIndexed { i, m ->
                val t = getOrNull(i) ?: return@forEachIndexed
                t.tag = m.substringBefore(':').toIntOrNull()?.coerceIn(0, 6) ?: 0; t.pinned = m.substringAfter(':', "0") == "1"
            }
        }
    }
    var nextId by remember { mutableIntStateOf(tabs.size + 1000) }
    var current by remember { mutableIntStateOf(if (startUrl.isNotBlank()) tabs.lastIndex else prefs.getInt("cur", 0).coerceIn(0, tabs.lastIndex)) }
    var showTabs by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<PendingSave?>(null) }
    var fillOffer by remember { mutableStateOf<FillOffer?>(null) }
    var ytUrl by remember { mutableStateOf<String?>(null) }
    var notifPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(dlTrigger) { if (dlTrigger > 0) openPanel(activity, "downloads") }
    val notif = rememberNotifState { ok -> if (!ok) toast(activity, L("فعّل الإشعارات من الإعدادات لمتابعة التنزيل في الخلفية")) }
    // يُعرض شرح الإذن مرة واحدة عند أول حاجة (تنزيل أو تشغيل في الخلفية)، ولا تتكرر الطلبات المتفرقة
    val askNotif = { if (Notif.needsPermission && !Notif.enabled(activity) && !Prefs.notifAsked) { Prefs.pickNotifAsked(true); notifPrompt = true } }
    // أندرويد 6–9: حفظ التنزيلات في Download/Nova يحتاج إذن التخزين (نطلبه مرة واحدة؛ إن رُفض نحفظ في مجلد التطبيق)
    var askedStorage by remember { mutableStateOf(false) }
    var afterStorage by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        val f = afterStorage; afterStorage = null; f?.invoke()
    }
    fun withStorage(block: () -> Unit) {
        if (Storage.needsPermission(activity) && !askedStorage) {
            askedStorage = true; afterStorage = block
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else block()
    }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCb by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val tab = tabs[current.coerceIn(0, tabs.lastIndex)]

    LaunchedEffect(Unit) {
        snapshotFlow { Triple(tabs.joinToString("\n") { it.url.ifBlank { "-" } }, current, tabs.joinToString(",") { it.tag.toString() + ":" + (if (it.pinned) "1" else "0") }) }
            .debounce(700).collect { (s, c, m) -> prefs.edit().putString("tabs", s).putInt("cur", c).putString("meta", m).apply() }
    }

    var fileCb by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)); fileCb = null
    }
    var sitePrompt by remember { mutableStateOf<SitePrompt?>(null) }
    var settingsMsg by remember { mutableStateOf<String?>(null) }
    val decisions = remember { mutableStateMapOf<String, Boolean>() }
    // طابور طلبات الأذونات: كان callback واحد يُستبدل عند طلب ثانٍ فلا يُجاب طلب الموقع أبداً (تحميل الخرائط اللانهائي)
    val permQueue = remember { ArrayList<Pair<List<String>, () -> Unit>>() }
    val permCur = remember { arrayOfNulls<Pair<List<String>, () -> Unit>>(1) }
    val permPump = remember { arrayOfNulls<() -> Unit>(1) }
    fun granted(p: String) = ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { m ->
        val cur = permCur[0]; permCur[0] = null
        val denied = m.filter { !it.value }.keys
        if (denied.any { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })
            settingsMsg = L("تم رفض الإذن بشكل دائم. فعّله من إعدادات التطبيق ليعمل هذا الموقع.")
        cur?.second?.invoke()
        permPump[0]?.invoke()
    }
    fun pumpPerms() {
        if (permCur[0] != null || permQueue.isEmpty()) return
        val next = permQueue.removeAt(0)
        val need = next.first.filter { !granted(it) }
        if (need.isEmpty()) { next.second(); pumpPerms(); return }
        permCur[0] = next
        runCatching { permLauncher.launch(need.toTypedArray()) }.onFailure { permCur[0] = null; next.second(); pumpPerms() }
    }
    permPump[0] = { pumpPerms() }
    fun askPerms(perms: List<String>, cb: () -> Unit) {
        if (perms.all { granted(it) }) cb() else { permQueue.add(perms to cb); pumpPerms() }
    }
    val geoWaiters = remember { HashMap<String, MutableList<GeolocationPermissions.Callback>>() }

    // آخر التبويبات المغلقة (لإعادة فتحها من شاشة التبويبات)
    val closedTabs = remember { mutableStateListOf<Pair<String, String>>() }
    fun pushClosed(t: BrowserTab) {
        if (t.url.isBlank()) return
        closedTabs.removeAll { it.first == t.url }
        closedTabs.add(0, t.url to t.title)
        while (closedTabs.size > 15) closedTabs.removeAt(closedTabs.lastIndex)
    }
    fun go(t: BrowserTab, input: String) {
        val u = normalize(input); t.url = u
        if (!YtWeb.swapIfNeeded(t, u)) t.webView?.loadUrl(u, Perf.privacyHeaders)   // تبديل بين WebView يوتيوب المخصّص والعام يعيد الإنشاء ويحمّل الرابط
    }
    fun snap(t: BrowserTab) {   // لقطة مصغّرة (40%) للتبويب الظاهر قبل مغادرته
        val w = t.webView ?: return
        if (t.url.isBlank() || w.width <= 0 || w.height <= 0 || w.visibility != View.VISIBLE) return   // مخفي تحت شاشة التبويبات: لا نلتقط لقطة فارغة
        if (Adaptive.level >= 2 && t.thumb != null) return   // وفّر المعالج عند السخونة
        if (t.ytPlaying && t.thumb != null) return           // رسم صفحة فيها فيديو يعمل مكلف ويُسبب تقطيعاً
        runCatching {
            val k = 0.4f
            val bmp = Bitmap.createBitmap((w.width * k).toInt().coerceAtLeast(1), (w.height * k).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val c = android.graphics.Canvas(bmp); c.scale(k, k); w.draw(c)
            t.thumb = bmp
        }
    }
    fun openTabs() { snap(tab); showTabs = true }
    fun newTab() { snap(tab); tabs.add(BrowserTab(nextId++)); current = tabs.lastIndex; showTabs = false; editing = true }
    fun openInNewTab(u: String) { tabs.add(BrowserTab(nextId++, u)); current = tabs.lastIndex }
    // رابط جاء من تطبيق آخر: يُفتح في التبويب الحالي إن كان فارغاً وإلا في تبويب جديد، مع إغلاق أي لوحة مفتوحة
    LaunchedEffect(incoming) {
        val u = incoming?.second ?: return@LaunchedEffect
        showTabs = false; editing = false
        val t = tabs.getOrNull(current)
        if (t != null && t.url.isBlank()) go(t, u) else openInNewTab(u)
    }
    fun dispose(t: BrowserTab) {
        YtMedia.tabClosed(t)
        t.webView?.let { w -> (w.parent as? ViewGroup)?.removeView(w); w.destroy() }
        t.webView = null
    }
    fun discard(t: BrowserTab) {   // تحرير الذاكرة مع حفظ الحالة (السجل والتمرير) لاستعادتها لاحقاً
        if (YtWeb.isLive(t)) return   // يوتيوب يعمل (صوت/منبثقة): لا نقطع التشغيل ولا نفقد حالة المشغّل
        t.webView?.let { w -> val b = android.os.Bundle(); runCatching { w.saveState(b) }; t.saved = b }
        dispose(t)
    }
    fun closeTab(i: Int) {
        pushClosed(tabs[i]); dispose(tabs[i]); tabs.removeAt(i)
        if (tabs.isEmpty()) tabs.add(BrowserTab(nextId++))
        current = current.coerceIn(0, tabs.lastIndex)
    }
    fun closeAll() {
        tabs.toList().filter { !it.pinned }.forEach { pushClosed(it); dispose(it); tabs.remove(it) }
        if (tabs.isEmpty()) tabs.add(BrowserTab(nextId++))
        current = current.coerceIn(0, tabs.lastIndex); showTabs = false
    }
    fun reopenClosed() {
        if (closedTabs.isEmpty()) return
        val u = closedTabs.removeAt(0).first
        tabs.add(BrowserTab(nextId++, u)); current = tabs.lastIndex; showTabs = false
    }
    fun duplicate(i: Int) { val o = tabs[i]; snap(o); tabs.add(i + 1, BrowserTab(nextId++, o.url).also { it.tag = o.tag }); current = i + 1; showTabs = false }
    fun closeOthers(i: Int) {
        val keep = tabs[i]
        tabs.toList().filter { it !== keep && !it.pinned }.forEach { pushClosed(it); dispose(it); tabs.remove(it) }
        current = tabs.indexOf(keep).coerceAtLeast(0)
    }
    fun closeByTag(tag: Int) {
        val cur = tabs.getOrNull(current)
        tabs.toList().filter { it.tag == tag && !it.pinned }.forEach { pushClosed(it); dispose(it); tabs.remove(it) }
        if (tabs.isEmpty()) tabs.add(BrowserTab(nextId++))
        current = (cur?.let { tabs.indexOf(it) } ?: -1).let { if (it < 0) 0 else it }
    }
    fun home(t: BrowserTab) {
        dispose(t); t.saved = null
        t.url = ""; t.title = L("تبويب جديد"); t.canBack = false; t.canForward = false; t.loading = false; t.finding = false
    }

    LaunchedEffect(Prefs.secureScreen) {
        val f = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (Prefs.secureScreen) activity.window.setFlags(f, f) else activity.window.clearFlags(f)
    }
    LaunchedEffect(Prefs.textZoom, Prefs.siteDark) { tabs.forEach { t -> t.webView?.let { Perf.applyDisplay(it) } } }
    fun printPage(t: BrowserTab) {
        val w = t.webView ?: return
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        val name = (t.title.ifBlank { "Nova" } + " - " + hostOf(t.url)).take(60)
        runCatching { pm.print(name, w.createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build()) }
    }
    fun openCustomTab(t: BrowserTab) {
        val pkg = androidx.browser.customtabs.CustomTabsClient.getPackageName(activity, null)
        if (pkg == null) { toast(activity, L("لا يوجد متصفح يدعم Custom Tabs")); return }
        val ci = androidx.browser.customtabs.CustomTabsIntent.Builder().build()
        ci.intent.setPackage(pkg)
        runCatching { ci.launchUrl(activity, Uri.parse(t.url)) }
    }
    fun translatePage(t: BrowserTab) {
        if (t.url.isBlank()) return
        val target = Prefs.siteLangCode().ifEmpty { I18n.code() }
        go(t, "https://translate.google.com/translate?sl=auto&tl=" + target + "&u=" + Uri.encode(t.url))
        toast(activity, L("سيُرسل عنوان الصفحة إلى Google Translate لترجمتها."))
    }
    LaunchedEffect(Prefs.js) { tabs.forEach { it.webView?.settings?.javaScriptEnabled = Prefs.js } }
    LaunchedEffect(Prefs.pwMode) {
        tabs.forEach { t -> t.webView?.let { Wv.autofill(it, Prefs.pwMode == 1) } }
        fillOffer = null
    }
    LaunchedEffect(tab.url) { if (fillOffer?.host != Vault.norm(hostOf(tab.url))) fillOffer = null }
    fun clearData() {
        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach { t -> t.webView?.let { it.clearCache(true); it.clearHistory(); it.clearFormData() } }
        decisions.clear(); Library.clearHistory()
        toast(activity, L("تم مسح بيانات التصفح"))
    }

    fun clearCacheNow() {
        tabs.forEach { it.webView?.clearCache(true) }
        Thread { runCatching { CacheCleaner.clean(activity.applicationContext) } }.start()
        toast(activity, L("تم مسح الذاكرة المؤقتة"))
    }
    // اللوحات (الإعدادات/التنزيلات/المكتبة) تعيش في نافذة مستقلة؛ هذا الجسر هو كل ما تطلبه من المتصفح
    DisposableEffect(Unit) {
        PanelBus.clearData = { clearData() }
        PanelBus.clearCache = { clearCacheNow() }
        PanelBus.openUrl = { u -> val t = tabs.getOrNull(current); if (t != null && t.url.isBlank()) go(t, u) else openInNewTab(u) }
        onDispose { PanelBus.clearData = null; PanelBus.clearCache = null; PanelBus.openUrl = null }
    }
    // شاشة التبويبات تغطي الصفحة بالكامل: نُخفي الـ WebView تحتها كي لا يبقى يرسم (ما لم يكن صوت يوتيوب يعمل في الخلفية)
    LaunchedEffect(showTabs, tab.id, tab.epoch) {
        if (showTabs) kotlinx.coroutines.delay(260)
        // فيديو يوتيوب يعمل/مفتوح لا يُخفى أبداً: إخفاء الـ WebView يجعل الصفحة hidden فيفرّغ يوتيوب الفيديو (emptied) ويتوقف
        val keep = (Prefs.ytBg && YtMedia.owner != null) || YtWeb.isLive(tab)
        tab.webView?.visibility = if (showTabs && !keep) View.INVISIBLE else View.VISIBLE
    }
    // عند ضغط الذاكرة: نحرر الـ WebView للتبويبات الخلفية (تُعاد عند الرجوع لها)
    DisposableEffect(Unit) {
        val cb = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    val cur = tabs.getOrNull(current)
                    tabs.toList().forEach { t -> if (t !== cur && !YtWeb.isLive(t)) { if (t.webView != null) discard(t); t.thumb = null } }
                }
            }
            override fun onConfigurationChanged(c: android.content.res.Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() {}
        }
        activity.registerComponentCallbacks(cb)
        onDispose { activity.unregisterComponentCallbacks(cb) }
    }

    // حدّ أقصى لعدد الـ WebView الحيّة حسب ذاكرة الجهاز؛ الأقدم استخداماً يُحرَّر ويُستعاد عند الرجوع
    val maxLive = remember {
        val am = activity.getSystemService(android.app.ActivityManager::class.java)
        if (LowEnd.on || am.isLowRamDevice) 2 else if (am.memoryClass >= 256) 5 else 3
    }
    LaunchedEffect(current, tabs.size, Adaptive.level) {
        val cap = Adaptive.liveCap(maxLive)
        val cur = tabs.getOrNull(current)
        cur?.lastUsed = android.os.SystemClock.elapsedRealtime()
        kotlinx.coroutines.delay(1200)   // بعد إنشاء الـ WebView الجديد
        val live = tabs.filter { it.webView != null }
        if (live.size > cap)
            live.filter { it !== cur && !YtWeb.isLive(it) }.sortedWith(compareBy({ it.pinned }, { it.lastUsed })).take(live.size - cap).forEach { discard(it) }
    }
    // عند الخروج من التطبيق: إيقاف مؤقتات الصفحات لتوفير المعالج والبطارية
    DisposableEffect(Unit) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            val w = tabs.getOrNull(current)?.webView
            val inPipNow = (activity as? MainActivity)?.inPip == true
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val ytLive = Prefs.ytBg && (YtMedia.owner != null || inPipNow)
                if (!ytLive && customView == null) YtMedia.stop()   // الخلفية معطّلة: لا إشعار تشغيل بعد الخروج
                if (ytLive) YtWeb.background(w, true)   // لا نجمّد الصفحة أثناء تشغيل يوتيوب
                else if (Prefs.pauseBg && customView == null) { w?.onPause(); w?.pauseTimers() }
            } else if (e == androidx.lifecycle.Lifecycle.Event.ON_START) {
                w?.resumeTimers(); w?.onResume()
                if (!inPipNow) YtWeb.background(w, false)
            }
        }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }

    val handlers = remember {
        Handlers(
            activity = activity,
            chooser = { cb, p ->
                fileCb?.onReceiveValue(null); fileCb = cb
                runCatching { fileLauncher.launch(p.createIntent()) }.onFailure { cb.onReceiveValue(null); fileCb = null }
                true
            },
            permission = { req ->
                activity.runOnUiThread {
                    val av = req.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    // فيديو محمي (DRM/EME): كان يُرفض فيتعطّل التشغيل؛ هذا إذن معرّف الوسائط المحمية فقط وليس كاميرا ولا ميكروفون
                    val prot = req.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }.toTypedArray()
                    if (av.isEmpty()) { if (prot.isNotEmpty()) req.grant(prot) else req.deny(); return@runOnUiThread }
                    fun perm(r: String) = if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO
                    val label = av.joinToString(L(" و")) { if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) L("الكاميرا") else L("الميكروفون") }
                    fun finish(allow: Boolean) {
                        if (!allow) { req.deny(); return }
                        askPerms(av.map { perm(it) }) {
                            val ok = av.filter { granted(perm(it)) }.toTypedArray()
                            if (ok.isEmpty() && prot.isEmpty()) req.deny() else req.grant(ok + prot)
                        }
                    }
                    val key = req.origin.toString() + "|av"
                    when (decisions[key]) {
                        true -> finish(true)
                        false -> req.deny()
                        null -> sitePrompt = SitePrompt(L("السماح بالوصول؟"), ("" + (hostOf(req.origin.toString())) + L(" يريد استخدام ") + label),
                            { decisions[key] = true; finish(true) }, { decisions[key] = false; finish(false) })
                    }
                }
            },
            geo = { origin, cb ->
                activity.runOnUiThread {
                    val key = origin + "|geo"
                    val list = geoWaiters.getOrPut(key) { ArrayList() }
                    list.add(cb)
                    if (list.size > 1 && sitePrompt != null) return@runOnUiThread   // طلب مكرر والحوار ظاهر: يُجاب كل المنتظرين معاً
                    fun done(allow: Boolean) {
                        val cbs = geoWaiters.remove(key).orEmpty()
                        if (!allow) { cbs.forEach { it.invoke(origin, false, false) }; return }
                        val perms = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        askPerms(perms) { val ok = perms.any { granted(it) }; cbs.forEach { it.invoke(origin, ok, false) } }
                    }
                    when (decisions[key]) {
                        true -> done(true)
                        false -> done(false)
                        null -> sitePrompt = SitePrompt(L("السماح بالموقع؟"), ("" + (hostOf(origin)) + L(" يريد معرفة موقعك")),
                            { decisions[key] = true; done(true) }, { decisions[key] = false; done(false) })
                    }
                }
            },
            openTab = { openInNewTab(it) },
            showCustom = { v, cb ->
                if (customView != null && customView !== v) {   // عرض ملء شاشة جديد فوق قديم: نُعلم القديم بإخفائه كي لا يتسرّب
                    customView?.let { (it.parent as? ViewGroup)?.removeView(it) }
                    runCatching { customCb?.onCustomViewHidden() }
                }
                customView = v; customCb = cb
                tabs.getOrNull(current)?.webView?.takeIf { YtWeb.owns(it) }?.let { YtWeb.onFullscreen(it, true) }
            },
            hideCustom = {
                // فصل عرض الفيديو صراحةً من حاويته ثم إنعاش المشغّل بعد الرجوع (كانت الشاشة تبقى سوداء)
                customView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
                customView = null; customCb = null
                tabs.getOrNull(current)?.webView?.takeIf { YtWeb.owns(it) }?.let { YtWeb.onFullscreen(it, false); YtWeb.afterFullscreen(it) }
            },
            onLoginForm = { t, _, host ->
                if (t === tabs.getOrNull(current)) {
                    val cs = Vault.forHost(host)
                    if (cs.isNotEmpty()) fillOffer = FillOffer(t.id, host, cs)
                    else if (GoogleAccounts.suggest && GoogleAccounts.accounts.isNotEmpty())
                        fillOffer = FillOffer(t.id, host, GoogleAccounts.accounts.map { Cred("g:$it", host, it, "", 0L) })   // بريد فقط، بلا كلمة مرور
                }
            },
            onCredential = { host, user, pass ->
                if (pass.isNotEmpty() && !Vault.isNever(host)) {
                    val k = Vault.classify(host, user, pass)
                    if (k != SaveKind.SAME) pendingSave = PendingSave(host, user, pass, k)
                }
            },
            onYtState = {
                askNotif()
                (activity as? MainActivity)?.onYtPlayState()
            },
            onDownload = { u, ua, cd, mime, ref ->
                if (!Shield.downloadAllowed(hostOf(ref ?: u))) toast(activity, L("تم حظر تنزيلات تلقائية متتابعة من الصفحة"))
                else if (u.startsWith("blob:") || u.startsWith("data:")) toast(activity, L("هذا النوع من التنزيل غير مدعوم بعد"))
                else {
                    val start = {
                        withStorage {
                            Downloader.start(activity, u, ua, cd, mime, ref)
                            toast(activity, L("بدأ التنزيل — القائمة ⋮ ثم التنزيلات"))
                            askNotif()
                        }
                        Unit
                    }
                    if (Security.isRiskyFile(u, cd)) {
                        Security.log(L("تنزيل"), (L("تحذير ملف تنفيذي من ") + (hostOf(u))))
                        novaDialog(activity).setTitle(L("ملف قد يكون خطيراً"))
                            .setMessage((L("هذا النوع من الملفات (تطبيق/ملف تنفيذي) قد يضر بجهازك. نزّله فقط من مصدر تثق به.\n\n") + (hostOf(u))))
                            .setPositiveButton(L("تنزيل")) { _, _ -> start() }.setNegativeButton(L("إلغاء"), null).show()
                    } else start()
                }
            }
        )
    }

    DisposableEffect(customView != null) {
        val c = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (customView != null) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            c.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler(enabled = tab.canBack) { tab.webView?.goBack() }
    BackHandler(enabled = tab.url.isNotBlank() && !tab.canBack) { home(tab) }   // آخر صفحة في السجل: العودة للرئيسية بدل إغلاق التطبيق
    BackHandler(enabled = tab.finding) { tab.webView?.clearMatches(); tab.finding = false }
    BackHandler(enabled = editing) { editing = false }
    BackHandler(enabled = showTabs) { showTabs = false }
    BackHandler(enabled = customView != null) {
        customView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        customCb?.onCustomViewHidden(); customView = null; customCb = null
        tabs.getOrNull(current)?.webView?.takeIf { YtWeb.owns(it) }?.let { YtWeb.onFullscreen(it, false); YtWeb.afterFullscreen(it) }
    }

    // ربط الـ Activity: مزوّد الـ WebView الحالي + تفعيل الدخول التلقائي للنافذة المنبثقة أثناء تشغيل فيديو يوتيوب
    val mainAct = activity as? MainActivity
    SideEffect {
        mainAct?.wvProvider = { tabs.getOrNull(current)?.webView }
        mainAct?.fullscreenActive = customView != null
        val want = Prefs.autoPip && ((tab.ytPlaying && isYtVideo(tab.url)) || customView != null)
        if (mainAct != null && mainAct.pipAuto != want) { mainAct.pipAuto = want; mainAct.refreshPip() }
    }

    val primaryInt = cs.primary.toArgb()
    val bgInt = cs.surfaceContainerHigh.toArgb()
    val spinnerInt = cs.onSurface.toArgb()

    // الكيبورد أثناء الكتابة داخل صفحة (مثل خانة سؤال الذكاء الاصطناعي): تنكمش الصفحة فوق الكيبورد ويختفي شريط العنوان السفلي.
    // derivedStateOf يتغيّر مرتين فقط (فتح/إغلاق) فلا إعادة تركيب أثناء حركة الكيبورد.
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    val imeVisible by remember { derivedStateOf { imeInsets.getBottom(density) > 0 } }
    val imeSettled = rememberSettledIme(imeInsets)   // ارتفاع الكيبورد بعد استقراره (تغيير واحد للـ WebView بدل ~20)
    var pageTyping by remember { mutableStateOf(false) }
    var wasBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, editing, tab.finding) {
        val blocked = editing || tab.finding
        if (imeVisible && !blocked) {
            if (wasBlocked) kotlinx.coroutines.delay(700)   // بعد إنهاء كتابة العنوان: ننتظر إغلاق الكيبورد كي لا يومض الشريط
            pageTyping = true
        } else pageTyping = false
        wasBlocked = blocked
    }

    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            // وضع التطبيق: الارتفاع يتبدّل فوراً (تحريك ارتفاع الـ WebView كل إطار يسبب تقطيعاً)، والشريط نفسه ينزلق على طبقة الرسم
            val site = if (Prefs.pwaMode && !inPip && customView == null) Pwa.info(tab.url) else null
            val barH = if (site != null) Pwa.BAR_H.dp else 0.dp
            // لون شريط الحالة يتبع لون شريط الموقع (كان خلفية سادة بينما الشريط مائل للون الهوية)
            AnimatedVisibility(visible = site != null, enter = fadeIn(tween(Adaptive.ms(160))), exit = fadeOut(tween(Adaptive.ms(120)))) { StatusBarWash() }
            Box(Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding()) {
                Box(Modifier.fillMaxSize().padding(top = barH).pageInsets({ imeSettled.value }, navInsets, pageTyping, inPip).background(cs.background)) {
                    AnimatedContent(
                        targetState = tab.id to tab.url.isBlank(),
                        transitionSpec = {
                            if (targetState.second) fadeIn(tween(Adaptive.ms(140))) togetherWith fadeOut(tween(Adaptive.ms(90)))
                            else EnterTransition.None togetherWith ExitTransition.None
                        },
                        label = "page"
                    ) { (id, blank) ->
                        val tb = tabs.firstOrNull { it.id == id }
                        if (tb != null) {
                            if (blank) {
                                StartPage(
                                    tabsCount = tabs.size,
                                    activeDl = Downloader.tasks.count { it.status == Downloader.DOWNLOADING || it.status == Downloader.PREPARING },
                                    onSearchClick = { editing = true }, onAi = { go(tb, AI_MODE_URL) }, onOpen = { go(tb, it) },
                                    onTabs = { openTabs() }, onDownloads = { openPanel(activity, "downloads") }
                                )
                            } else key(id, tb.epoch) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        // يوتيوب له WebView مخصّص منفصل (YtWeb)؛ بقية المواقع تستخدم الـ WebView العام
                                        val wv = tb.webView ?: (if (YtWeb.isYtUrl(tb.url)) YtWeb.create(ctx, tb, handlers) else createWebView(ctx, tb, handlers)).also { w ->
                                            tb.webView = w
                                            val sv = tb.saved; tb.saved = null
                                            val restored = sv != null && w.restoreState(sv) != null
                                            if (!restored) {
                                                if (tb.holdLoad) {   // انهارت العملية مراراً: صفحة بزر إعادة المحاولة بدل حلقة تحميل لا تنتهي
                                                    tb.holdLoad = false
                                                    w.loadDataWithBaseURL(tb.url, errorHtml(tb.url, L("تعطّلت عملية عرض الصفحة عدة مرات. أغلق تبويبات أخرى لتحرير الذاكرة ثم أعد المحاولة.")), "text/html", "UTF-8", tb.url)
                                                } else w.loadUrl(tb.url, Perf.privacyHeaders)
                                            }
                                        }
                                        (wv.parent as? ViewGroup)?.removeView(wv)
                                        wv.visibility = View.VISIBLE   // قد يكون أُخفي أثناء عرض شاشة التبويبات
                                        if (tb.yt) {
                                            // يوتيوب: بلا SwipeRefreshLayout (يعترض اللمس فيقطّع التمرير ويتعارض مع تمرير الصفحة الداخلي)
                                            FrameLayout(ctx).apply { addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
                                        } else SwipeRefreshLayout(ctx).apply {
                                            addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                            setOnRefreshListener { wv.reload() }
                                            setOnChildScrollUpCallback { _, _ -> wv.scrollY > 0 || wv.canScrollVertically(-1) || Pwa.kind(wv.url) != SiteKind.NONE || WebCompat.noPullRefresh(wv.url) }
                                            setColorSchemeColors(primaryInt)
                                            setProgressBackgroundColorSchemeColor(bgInt)
                                            useIosSpinner(spinnerInt)
                                        }
                                    }
                                )
                                // التبويب غير الظاهر يُوقَف مؤقتاً (يوفر المعالج والذاكرة) ويُستأنف عند ظهوره
                                DisposableEffect(tb.id, tb.epoch) {
                                    tb.webView?.onResume()
                                    onDispose { tb.webView?.onPause() }
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = site != null,
                    enter = NovaMotion.barEnter, exit = NovaMotion.barExit, modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    // آخر معلومات صالحة تبقى أثناء حركة الخروج كي لا يفرغ الشريط فجأة
                    val shown = remember { mutableStateOf(site) }
                    if (site != null) shown.value = site
                    shown.value?.let { si ->
                        SiteBar(
                            info = si, progress = tab.progress, loading = tab.loading,
                            onReload = { tab.webView?.reload() }, onShare = { shareText(activity, tab.url) }, onTitleTap = { Pwa.scrollTop(tab.webView) },
                            onPip = if (si.kind == SiteKind.YT_VIDEO) ({ mainAct?.enterPip() }) else null,
                            onDownload = if (si.kind == SiteKind.YT_VIDEO) ({ ytUrl = tab.url }) else null
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !inPip && !pageTyping, modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(tween(Adaptive.ms(160))), exit = fadeOut(tween(Adaptive.ms(90)))
                ) {
                if (tab.finding) key(tab.id) { FindBar(tab) } else BottomPill(
                    tab = tab, tabCount = tabs.size, editing = editing, setEditing = { editing = it },
                    onGo = { go(tab, it) }, onTabs = { openTabs() }, onNewTab = { newTab() }, onHome = { home(tab) },
                    onFind = { tab.findInfo = ""; tab.finding = true },
                    onDesktop = { tab.desktop = !tab.desktop; tab.webView?.let { applyUa(it, tab.desktop); it.reload() } },
                    onShare = { shareText(activity, tab.url) }, onCopy = { copyText(activity, tab.url) },
                    onDownloads = { openPanel(activity, "downloads") }, onSettings = { openPanel(activity, "settings") }, onTranslate = { translatePage(tab) }, onPrint = { printPage(tab) }, onCustomTab = { openCustomTab(tab) },
                    onSwitch = { d -> snap(tab); current = (current + d).coerceIn(0, tabs.lastIndex) }
                )
                }
            }
        }
        if (Updater.prompt) UpdateDialog()
        AnimatedVisibility(
            visible = showTabs,
            enter = fadeIn(tween(Adaptive.ms(180))) + slideInVertically(tween(Adaptive.ms(240), easing = FastOutSlowInEasing)) { it / 12 },
            exit = fadeOut(tween(Adaptive.ms(130))) + slideOutVertically(tween(Adaptive.ms(180), easing = FastOutSlowInEasing)) { it / 12 }
        ) {
            TabSwitcher(
                tabs = tabs, current = current.coerceIn(0, tabs.lastIndex),
                onSelect = { i -> if (i != current) snap(tab); current = i; showTabs = false },
                onClose = { i -> closeTab(i) }, onNew = { newTab() }, onCloseAll = { closeAll() }, onBack = { showTabs = false },
                onDuplicate = { i -> duplicate(i) }, onCloseOthers = { i -> closeOthers(i) }, onCloseTag = { g -> closeByTag(g) },
                canReopen = closedTabs.isNotEmpty(), onReopen = { reopenClosed() }
            )
        }
        fillOffer?.takeIf { it.tabId == tab.id && !inPip && !editing }?.let { o ->
            FillBanner(
                o, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
                onClose = { fillOffer = null },
                onFill = { c ->
                    if (c.pass.isEmpty()) { tab.webView?.let { PasswordBridge.fill(it, c) }; fillOffer = null }   // بريد الحساب: لا يحتاج بصمة
                    else Auth.run(activity, L("تأكيد الهوية لتعبئة كلمة المرور")) { tab.webView?.let { PasswordBridge.fill(it, c) }; fillOffer = null }
                }
            )
        }
        customView?.let { v ->
            var pipTouch by remember(v) { mutableIntStateOf(0) }
            var pipBtnShown by remember(v) { mutableStateOf(true) }
            LaunchedEffect(pipTouch) { pipBtnShown = true; kotlinx.coroutines.delay(3500); pipBtnShown = false }
            Box(
                Modifier.fillMaxSize().background(Color.Black).pointerInput(v) {
                    // نراقب اللمس دون استهلاكه: يظهر الزر مع أزرار يوتيوب ويختفي معها
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (ev.changes.any { it.pressed && !it.previousPressed }) pipTouch++
                        }
                    }
                }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK); (v.parent as? ViewGroup)?.removeView(v); addView(v) } }
                )
                // زر النافذة المنبثقة: أعلى المنتصف (أزرار يوتيوب يميناً ويساراً فلا تداخل) ويختفي تلقائياً
                if (!inPip && pipBtnShown) Surface(
                    onClick = { mainAct?.enterPip() }, shape = CircleShape, color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 10.dp)
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp), tint = Color.White); Spacer(Modifier.width(6.dp))
                        Text(L("منبثق"), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                }
            }
        }
    }

    if (notifPrompt) NotifPermissionDialog(
        onAllow = { notifPrompt = false; notif.request() },
        onLater = { notifPrompt = false }
    )
    sitePrompt?.let { p ->
        NovaDialog(
            title = p.title, icon = Icons.Default.Info, onDismiss = { sitePrompt = null; p.onDeny() },
            confirmText = L("سماح"), onConfirm = { sitePrompt = null; p.onAllow() },
            dismissText = L("رفض"), onDismissClick = { sitePrompt = null; p.onDeny() }
        ) { DialogText(p.message) }
    }
    pendingSave?.let { p ->
        SavePasswordDialog(
            p,
            onSave = { Vault.upsert("", p.host, p.user, p.pass); pendingSave = null; toast(activity, L("تم حفظ كلمة المرور")) },
            onNever = { Vault.neverSave(p.host); pendingSave = null },
            onDismiss = { pendingSave = null }
        )
    }
    LaunchedEffect(ytUrl) { if (ytUrl != null) withStorage { } }   // اطلب إذن التخزين قبل أن يختار المستخدم جودة التنزيل
    ytUrl?.let { u ->
        YtDownloadSheet(u, onDismiss = { ytUrl = null }, onStarted = {
            askNotif()
        })
    }
    settingsMsg?.let { m ->
        NovaDialog(
            title = L("الإذن مطلوب"), icon = Icons.Default.Lock, onDismiss = { settingsMsg = null },
            confirmText = L("فتح الإعدادات"), dismissText = L("لاحقاً"),
            onConfirm = {
                settingsMsg = null
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
            }
        ) { DialogText(m) }
    }
}
