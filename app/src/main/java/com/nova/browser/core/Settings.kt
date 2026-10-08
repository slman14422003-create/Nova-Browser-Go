package com.nova.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** إعدادات التطبيق (محفوظة، وقابلة للقراءة من أي مكان كحالة Compose) */
object Prefs {
    private var sp: ConfStore? = null
    var engine by mutableIntStateOf(0); private set
    var theme by mutableIntStateOf(0); private set          // 0 نظام، 1 فاتح، 2 داكن
    var desktop by mutableStateOf(false); private set
    var js by mutableStateOf(true); private set
    var restore by mutableStateOf(true); private set
    var maxConns by mutableIntStateOf(0); private set       // 0 = تلقائي
    var autoClean by mutableStateOf(true); private set      // تنظيف المؤقت عند كل تشغيل
    var blockAds by mutableStateOf(true); private set       // حجب الإعلانات والمتتبعات
    var dataSaver by mutableStateOf(false); private set     // عدم تحميل الصور
    var popups by mutableStateOf(true); private set         // إخفاء لافتات الموافقة على الكوكيز وفتح قفل التمرير
    var lazyMedia by mutableStateOf(true); private set      // تحميل كسول للصور
    var httpsFirst by mutableStateOf(true); private set     // ترقية http إلى https
    var cleanUrls by mutableStateOf(true); private set      // إزالة معرّفات التتبع من الروابط
    var blockThirdCookies by mutableStateOf(true); private set
    var secureScreen by mutableStateOf(false); private set  // منع لقطات الشاشة
    var antiFingerprint by mutableStateOf(true); private set // الحماية من البصمة
    var shield by mutableStateOf(true); private set         // الحماية الفورية من الهجمات والتتبع (Shield)
    var lang by mutableIntStateOf(0); private set           // لغة التطبيق: 0 تلقائي، 1 عربية، 2 English
    var siteLang by mutableIntStateOf(0); private set       // لغة المواقع (فهرس في siteLangs)
    var textZoom by mutableIntStateOf(1); private set       // فهرس في zoomValues
    var siteDark by mutableStateOf(false); private set      // الوضع الداكن للمواقع
    val siteLangs = listOf("" to "تلقائي", "ar" to "العربية", "en" to "English", "fr" to "Français", "es" to "Español",
        "de" to "Deutsch", "tr" to "Türkçe", "ru" to "Русский", "fa" to "فارسی", "ur" to "اردو", "id" to "Indonesia")
    val zoomValues = listOf(85, 100, 115, 130)
    fun siteLangCode() = siteLangs.getOrNull(siteLang)?.first ?: ""
    var pauseBg by mutableStateOf(true); private set        // إيقاف الصفحات عند الخروج من التطبيق
    var pwMode by mutableIntStateOf(0); private set         // كلمات المرور: 0 مدمج، 1 تعبئة النظام (Samsung Pass…)، 2 معطّل
    var autoPip by mutableStateOf(true); private set        // نافذة منبثقة تلقائياً لفيديو يوتيوب عند الخروج
    var ytBg by mutableStateOf(true); private set           // متابعة تشغيل يوتيوب في الخلفية
    var autoUpdate by mutableStateOf(true); private set     // فحص التحديثات تلقائياً
    var adaptive by mutableStateOf(true); private set       // التكيف مع حرارة الجهاز/توفير الطاقة
    var smoothAnim by mutableStateOf(true); private set     // الأنيميشن
    var cap60 by mutableStateOf(true); private set          // تحديد 60Hz عند السخونة
    var fitPages by mutableStateOf(true); private set       // تحسين عرض الصفحات (render.js)
    var pwaMode by mutableStateOf(true); private set        // وضع التطبيق (PWA) ليوتيوب ومواقع الذكاء الاصطناعي
    var ytCcSize by mutableIntStateOf(1); private set       // حجم الترجمة في يوتيوب: 0 صغير 1 عادي 2 كبير 3 كبير جداً
    var ytCcBg by mutableIntStateOf(0); private set         // خلفية الترجمة: 0 زجاجية 1 غامقة 2 بلا
    var ytCcPos by mutableIntStateOf(0); private set        // موضع الترجمة: 0 أسفل 1 وسط 2 أعلى
    var ytNoShorts by mutableStateOf(false); private set    // إخفاء شورتس من صفحات يوتيوب
    var ytResume by mutableStateOf(true); private set       // استئناف فيديوهات يوتيوب من حيث توقفت
    var ytKeepRate by mutableStateOf(true); private set      // تذكّر سرعة التشغيل بين الفيديوهات
    var ytHold2x by mutableStateOf(true); private set        // ضغط مطوّل على المشغّل = تسريع ×2 مؤقتاً
    var pwaHaptics by mutableStateOf(true); private set     // اهتزاز خفيف على أزرار صفحات وضع التطبيق
    var boost by mutableStateOf(true); private set          // تسريع التنقل: جلب مسبق + تسخين DNS + إيقاف فيديو خارج الشاشة
    var suggest by mutableStateOf(true); private set        // اقتراحات البحث أثناء الكتابة
    var lite by mutableIntStateOf(0); private set           // الوضع الخفيف للأجهزة الضعيفة: 0 تلقائي، 1 تشغيل، 2 إيقاف

    val engines = listOf(
        "Google" to "https://www.google.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "Brave" to "https://search.brave.com/search?q=",
        "Ecosia" to "https://www.ecosia.org/search?q="
    )

    fun init(c: Context) {
        val p = ConfStore.open(c); sp = p   // كل الإعدادات من ملف nova.conf دفعة واحدة إلى الرام
        LowEnd.init(c)
        val heavy = !LowEnd.device           // الأجهزة الضعيفة: ميزات الحقن الثقيلة معطّلة افتراضياً (يمكن تفعيلها يدوياً)
        lite = p.getInt("lite", 0).coerceIn(0, 2)
        engine = p.getInt("engine", 0).coerceIn(0, engines.lastIndex)
        theme = p.getInt("theme", 0); desktop = p.getBoolean("desktop", false)
        js = p.getBoolean("js", true); restore = p.getBoolean("restore", true); maxConns = p.getInt("maxc", 0)
        autoClean = p.getBoolean("autoclean", true); blockAds = p.getBoolean("blockads", true)
        dataSaver = p.getBoolean("saver", false); lazyMedia = p.getBoolean("lazy", true); popups = p.getBoolean("popups", true)
        httpsFirst = p.getBoolean("https1", true); cleanUrls = p.getBoolean("cleanurl", true)
        blockThirdCookies = p.getBoolean("c3p", true); secureScreen = p.getBoolean("secscr", false)
        antiFingerprint = p.getBoolean("antifp", heavy); shield = p.getBoolean("shield", true); pauseBg = p.getBoolean("pausebg", true)
        lang = p.getInt("lang", 0); siteLang = p.getInt("sitelang", 0).coerceIn(0, siteLangs.lastIndex)
        textZoom = p.getInt("zoom", 1).coerceIn(0, zoomValues.lastIndex); siteDark = p.getBoolean("sitedark", false)
        pwMode = if (p.contains("pwmode")) p.getInt("pwmode", 0).coerceIn(0, 2) else defaultPwMode(c)
        autoPip = p.getBoolean("autopip", true); ytBg = p.getBoolean("ytbg", true)
        autoUpdate = p.getBoolean("autoupd", true); adaptive = p.getBoolean("adaptive", true)
        smoothAnim = p.getBoolean("anim", true); cap60 = p.getBoolean("cap60", true)
        fitPages = p.getBoolean("fitpages", true); pwaMode = p.getBoolean("pwa", true); pwaHaptics = p.getBoolean("pwahap", true);
        ytCcSize = p.getInt("yccsize", 1).coerceIn(0, 3); ytCcBg = p.getInt("yccbg", 0).coerceIn(0, 2); ytCcPos = p.getInt("yccpos", 0).coerceIn(0, 2)
        ytNoShorts = p.getBoolean("ytnoshorts", false)
        ytResume = p.getBoolean("ytresume", true); ytKeepRate = p.getBoolean("ytkeeprate", true); ytHold2x = p.getBoolean("ythold2x", true)
        boost = p.getBoolean("boost", heavy); suggest = p.getBoolean("suggest", true)
    }

    /** إن كانت خدمة تعبئة (Samsung Pass مثلاً) مفعّلة في النظام نبدأ بها تلقائياً، وإلا نستخدم المدير المدمج. */
    private fun defaultPwMode(c: Context): Int = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 26) return@runCatching 0   // خدمات التعبئة في النظام من أندرويد 8
        val am = c.getSystemService(android.view.autofill.AutofillManager::class.java)
        if (am != null && am.isEnabled && am.hasEnabledAutofillServices()) 1 else 0
    }.getOrDefault(0)
    fun pickEngine(v: Int) { engine = v; sp?.putInt("engine", v) }
    fun pickTheme(v: Int) { theme = v; sp?.putInt("theme", v) }
    fun pickDesktop(v: Boolean) { desktop = v; sp?.putBoolean("desktop", v) }
    fun pickJs(v: Boolean) { js = v; sp?.putBoolean("js", v) }
    fun pickRestore(v: Boolean) { restore = v; sp?.putBoolean("restore", v) }
    fun pickMaxConns(v: Int) { maxConns = v; sp?.putInt("maxc", v) }
    fun pickAutoClean(v: Boolean) { autoClean = v; sp?.putBoolean("autoclean", v) }
    fun pickBlockAds(v: Boolean) { blockAds = v; sp?.putBoolean("blockads", v) }
    fun pickDataSaver(v: Boolean) { dataSaver = v; sp?.putBoolean("saver", v) }
    fun pickHttpsFirst(v: Boolean) { httpsFirst = v; sp?.putBoolean("https1", v) }
    fun pickCleanUrls(v: Boolean) { cleanUrls = v; sp?.putBoolean("cleanurl", v) }
    fun pickThirdCookies(v: Boolean) { blockThirdCookies = v; sp?.putBoolean("c3p", v) }
    fun pickAntiFingerprint(v: Boolean) { antiFingerprint = v; sp?.putBoolean("antifp", v) }
    fun pickShield(v: Boolean) { shield = v; sp?.putBoolean("shield", v) }
    fun pickLang(v: Int) { lang = v; sp?.putInt("lang", v) }
    fun pickSiteLang(v: Int) { siteLang = v; sp?.putInt("sitelang", v) }
    fun pickTextZoom(v: Int) { textZoom = v; sp?.putInt("zoom", v) }
    fun pickSiteDark(v: Boolean) { siteDark = v; sp?.putBoolean("sitedark", v) }
    fun pickPauseBg(v: Boolean) { pauseBg = v; sp?.putBoolean("pausebg", v) }
    fun pickSecureScreen(v: Boolean) { secureScreen = v; sp?.putBoolean("secscr", v) }
    fun pickPwMode(v: Int) { pwMode = v; sp?.putInt("pwmode", v) }
    fun pickAutoPip(v: Boolean) { autoPip = v; sp?.putBoolean("autopip", v) }
    fun pickYtBg(v: Boolean) { ytBg = v; sp?.putBoolean("ytbg", v) }
    fun pickAutoUpdate(v: Boolean) { autoUpdate = v; sp?.putBoolean("autoupd", v) }
    fun pickLite(v: Int) { lite = v; sp?.putInt("lite", v); Adaptive.refresh() }
    fun pickAdaptive(v: Boolean) { adaptive = v; sp?.putBoolean("adaptive", v); Adaptive.refresh() }
    fun pickSmoothAnim(v: Boolean) { smoothAnim = v; sp?.putBoolean("anim", v) }
    fun pickCap60(v: Boolean) { cap60 = v; sp?.putBoolean("cap60", v) }
    fun pickFitPages(v: Boolean) { fitPages = v; sp?.putBoolean("fitpages", v) }
    fun pickPwaMode(v: Boolean) { pwaMode = v; sp?.putBoolean("pwa", v) }
    fun pickYtResume(v: Boolean) { ytResume = v; sp?.putBoolean("ytresume", v) }
    fun pickYtKeepRate(v: Boolean) { ytKeepRate = v; sp?.putBoolean("ytkeeprate", v) }
    fun pickYtHold2x(v: Boolean) { ytHold2x = v; sp?.putBoolean("ythold2x", v) }
    fun pickYtCcSize(v: Int) { ytCcSize = v; sp?.putInt("yccsize", v) }
    fun pickYtCcBg(v: Int) { ytCcBg = v; sp?.putInt("yccbg", v) }
    fun pickYtCcPos(v: Int) { ytCcPos = v; sp?.putInt("yccpos", v) }
    fun pickYtNoShorts(v: Boolean) { ytNoShorts = v; sp?.putBoolean("ytnoshorts", v) }
    fun pickPwaHaptics(v: Boolean) { pwaHaptics = v; sp?.putBoolean("pwahap", v) }
    fun pickBoost(v: Boolean) { boost = v; sp?.putBoolean("boost", v) }
    fun pickSuggest(v: Boolean) { suggest = v; sp?.putBoolean("suggest", v) }
    fun pickPopups(v: Boolean) { popups = v; sp?.putBoolean("popups", v) }
    fun pickLazyMedia(v: Boolean) { lazyMedia = v; sp?.putBoolean("lazy", v) }

    /** قراءة مبكرة (قبل Prefs.init) لتقرير التنظيف أثناء الـ Splash. */
    fun autoCleanEnabled(c: Context) = ConfStore.open(c).getBoolean("autoclean", true)
}

private class RowSpec(
    val title: String, val sub: String?, val icon: ImageVector, val onClick: () -> Unit,
    val trailing: (@Composable () -> Unit)? = null
)

@Composable
private fun Group(title: String, rows: List<RowSpec>) {
    val cs = MaterialTheme.colorScheme
    Text(title, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEachIndexed { i, r ->
            ListRow(groupShape(i, rows.size), r.title, r.sub, r.onClick, trailing = r.trailing) { IconCircle { Icon(r.icon, null) } }
        }
    }
}

@Composable
private fun ChoiceDialog(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, o ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onSelect(i); onDismiss() }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) { RadioButton(selected = i == selected, onClick = null); Spacer(Modifier.width(12.dp)); Text(o) }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(L("إغلاق")) } }
    )
}

private fun autofillStatus(c: Context): String {
    val svc = android.provider.Settings.Secure.getString(c.contentResolver, "autofill_service") ?: ""
    return when {
        svc.isBlank() -> L("لا توجد خدمة تعبئة مفعّلة في النظام")
        svc.contains("samsung", true) -> "Samsung Pass"
        else -> svc.substringBefore('/').substringAfterLast('.')
    }
}

/** يفتح اختيار خدمة التعبئة التلقائية في النظام (حيث تُفعّل Samsung Pass أو غيرها). */
private fun openAutofillSettings(c: Context) {
    val pm = c.packageManager
    val pkg = listOf("com.samsung.android.samsungpassautofill", "com.samsung.android.samsungpass")
        .firstOrNull { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }
    val i = Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:" + (pkg ?: "android")))
    runCatching { c.startActivity(i) }.onFailure { runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onClearData: () -> Unit, onClearCache: () -> Unit = {}, onPasswords: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    var dialog by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val roleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { DefaultBrowser.refresh(ctx) }
    LaunchedEffect(Unit) { DefaultBrowser.refresh(ctx) }
    var cacheSize by remember { mutableStateOf("…") }
    var cacheTick by remember { mutableIntStateOf(0) }
    var gEmail by remember { mutableStateOf("") }
    var gErr by remember { mutableStateOf(false) }
    val warnings = remember { Security.deviceWarnings(ctx) }
    LaunchedEffect(cacheTick) {
        cacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CacheCleaner.format(CacheCleaner.size(ctx)) }
    }
    val themeNames = listOf(L("تلقائي (حسب النظام)"), L("فاتح"), L("داكن"))
    val langNames = listOf(L("تلقائي"), "العربية", "English")
    val zoomNames = listOf(L("صغير"), L("عادي"), L("كبير"), L("كبير جداً"))
    val connOpts = listOf(0, 4, 8, 16)
    val connNames = listOf(L("تلقائي (حتى 16)"), "4", "8", "16")
    val ccSizeNames = listOf(L("صغير"), L("عادي"), L("كبير"), L("كبير جداً"))
    val ccBgNames = listOf(L("زجاجية"), L("غامقة"), L("بلا خلفية"))
    val ccPosNames = listOf(L("أسفل"), L("وسط"), L("أعلى"))
    val liteNames = listOf(L("تلقائي") + (if (LowEnd.device) " (" + L("جهازك ضعيف: مفعّل") + ")" else " (" + L("جهازك قوي: مُعطّل") + ")"), L("مفعّل دائماً"), L("معطّل"))
    val pwNames = listOf(L("مدمج في المتصفح"), L("خدمة النظام (Samsung Pass وغيرها)"), L("متوقف"))

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("رجوع")) }
                Spacer(Modifier.width(8.dp))
                Text(L("الإعدادات"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Group(L("عام"), listOf(
                    RowSpec(L("المتصفح الافتراضي"),
                        if (DefaultBrowser.isDefault) L("Nova هو متصفحك الافتراضي — اضغط لتغيير الإعدادات") else L("افتح الروابط من واتساب وبقية التطبيقات عبر Nova"),
                        if (DefaultBrowser.isDefault) Icons.Default.Check else Icons.Default.Star,
                        { val ri = DefaultBrowser.requestIntent(ctx); if (ri != null) runCatching { roleLauncher.launch(ri) }.onFailure { DefaultBrowser.openSystemSettings(ctx) } else DefaultBrowser.openSystemSettings(ctx) }),
                    RowSpec(L("محرك البحث"), Prefs.engines[Prefs.engine].first, Icons.Default.Search, { dialog = "engine" }),
                    RowSpec(L("المظهر"), themeNames[Prefs.theme], Icons.Default.Star, { dialog = "theme" }),
                    RowSpec(L("المكتبة"), L("المفضلة") + " " + Library.bookmarks.size + " • " + L("السجل") + " " + Library.history.size, Icons.Default.Favorite, { Library.show = true }),
                    RowSpec(L("نسخة سطح المكتب افتراضياً"), L("للتبويبات الجديدة"), Icons.Default.Build, { Prefs.pickDesktop(!Prefs.desktop) },
                        { Switch(checked = Prefs.desktop, onCheckedChange = null) })
                ))
                Group(L("التصفح والخصوصية"), listOf(
                    RowSpec("JavaScript", L("تعطيله قد يكسر بعض المواقع"), Icons.Default.Check, { Prefs.pickJs(!Prefs.js) },
                        { Switch(checked = Prefs.js, onCheckedChange = null) }),
                    RowSpec(L("استعادة التبويبات"), L("عند فتح التطبيق"), Icons.Default.Refresh, { Prefs.pickRestore(!Prefs.restore) },
                        { Switch(checked = Prefs.restore, onCheckedChange = null) }),
                    RowSpec(L("حجب الإعلانات والمتتبعات"), L("يسرّع الصفحات ويوفر البيانات"), Icons.Default.Check, { Prefs.pickBlockAds(!Prefs.blockAds) },
                        { Switch(checked = Prefs.blockAds, onCheckedChange = null) }),
                    RowSpec(L("مسح بيانات التصفح"), L("الكوكيز والذاكرة المؤقتة والسجل"), Icons.Default.Delete, { dialog = "clear" })
                ))
                Group(L("كلمات المرور وتسجيل الدخول"), listOf(
                    RowSpec(L("كلمات المرور المحفوظة"), Vault.items.size.toString(), Icons.Default.Lock, { onPasswords() }),
                    RowSpec(L("وضع التعبئة التلقائية"), pwNames[Prefs.pwMode.coerceIn(0, 2)], Icons.Default.Person, { dialog = "pwmode" }),
                    RowSpec(L("خدمة التعبئة في النظام"), autofillStatus(ctx), Icons.Default.Settings, { openAutofillSettings(ctx) })
                ))
                Group(L("حساب Google"), listOf(
                    RowSpec(L("حسابات Google"),
                        if (GoogleAccounts.accounts.isEmpty()) L("أضف بريدك ليُقترح عند طلب تسجيل الدخول") else GoogleAccounts.primary,
                        Icons.Default.Person, { gEmail = ""; gErr = false; dialog = "gacct" }),
                    RowSpec(L("اقتراح الحساب عند تسجيل الدخول"), L("يعرض بريدك فوق الصفحة عندما يطلب الموقع تسجيل الدخول"), Icons.Default.Check,
                        { GoogleAccounts.pickSuggest(!GoogleAccounts.suggest) }, { Switch(checked = GoogleAccounts.suggest, onCheckedChange = null) }),
                    RowSpec(L("تسجيل دخول Google عبر Chrome"), L("تفتح Google كثيراً صفحة الدخول بخطأ داخل المتصفحات المضمّنة؛ هذا الخيار يفتحها في Chrome Custom Tab"), Icons.Default.Lock,
                        { GoogleAccounts.pickUseChrome(!GoogleAccounts.useChrome) }, { Switch(checked = GoogleAccounts.useChrome, onCheckedChange = null) })
                ))
                Group(L("يوتيوب"), listOf(
                    RowSpec(L("متابعة التشغيل في الخلفية"), L("مع أزرار التحكم في الإشعار وشاشة القفل"), Icons.Default.PlayArrow, { Prefs.pickYtBg(!Prefs.ytBg) },
                        { Switch(checked = Prefs.ytBg, onCheckedChange = null) }),
                    RowSpec(L("نافذة منبثقة تلقائية"), L("عند الخروج من التطبيق أثناء تشغيل فيديو"), Icons.Default.Share, { Prefs.pickAutoPip(!Prefs.autoPip) },
                        { Switch(checked = Prefs.autoPip, onCheckedChange = null) }),
                    RowSpec(L("استئناف الفيديو"), L("يكمل فيديوهات يوتيوب الطويلة من حيث توقفت. يُطبَّق على التبويبات الجديدة"), Icons.Default.PlayArrow, { Prefs.pickYtResume(!Prefs.ytResume) },
                        { Switch(checked = Prefs.ytResume, onCheckedChange = null) }),
                    RowSpec(L("تذكّر سرعة التشغيل"), L("تبقى السرعة التي اخترتها في الفيديوهات التالية. يُطبَّق على التبويبات الجديدة"), Icons.Default.PlayArrow, { Prefs.pickYtKeepRate(!Prefs.ytKeepRate) },
                        { Switch(checked = Prefs.ytKeepRate, onCheckedChange = null) }),
                    RowSpec(L("ضغط مطوّل للتسريع ×2"), L("اضغط مطوّلاً على المشغّل لتسريع مؤقت، وعند الرفع تعود السرعة. يُطبَّق على التبويبات الجديدة"), Icons.Default.PlayArrow, { Prefs.pickYtHold2x(!Prefs.ytHold2x) },
                        { Switch(checked = Prefs.ytHold2x, onCheckedChange = null) }),
                    RowSpec(L("حجم الترجمة"), ccSizeNames[Prefs.ytCcSize.coerceIn(0, 3)], Icons.Default.Edit, { dialog = "yccsize" }),
                    RowSpec(L("خلفية الترجمة"), ccBgNames[Prefs.ytCcBg.coerceIn(0, 2)], Icons.Default.Edit, { dialog = "yccbg" }),
                    RowSpec(L("موضع الترجمة"), ccPosNames[Prefs.ytCcPos.coerceIn(0, 2)], Icons.Default.Edit, { dialog = "yccpos" }),
                    RowSpec(L("إخفاء Shorts"), L("يخفي أرفف ومقاطع شورتس من قوائم يوتيوب. يُطبَّق على الصفحات الجديدة"), Icons.Default.Close, { Prefs.pickYtNoShorts(!Prefs.ytNoShorts) },
                        { Switch(checked = Prefs.ytNoShorts, onCheckedChange = null) }),
                    RowSpec(L("سجل التشخيص"), L("يساعد في معرفة سبب توقف الفيديو (انسخه وأرسله)"), Icons.Default.Info, { dialog = "ytlog" })
                ))
                Group(L("اللغة والعرض"), listOf(
                    RowSpec(L("لغة التطبيق"), langNames[Prefs.lang.coerceIn(0, 2)], Icons.Default.Settings, { dialog = "lang" }),
                    RowSpec(L("لغة المواقع"), Prefs.siteLangs[Prefs.siteLang].let { if (it.first.isEmpty()) L(it.second) else it.second }, Icons.Default.Search, { dialog = "sitelang" }),
                    RowSpec(L("حجم الخط في المواقع"), zoomNames[Prefs.textZoom], Icons.Default.Edit, { dialog = "zoom" }),
                    RowSpec(L("الوضع الداكن للمواقع"), L("يتبع وضع النظام الداكن"), Icons.Default.Star, { Prefs.pickSiteDark(!Prefs.siteDark) },
                        { Switch(checked = Prefs.siteDark, onCheckedChange = null) })
                ))
                Group(L("الأمان"), listOf(
                    RowSpec(L("الحماية الفورية"),
                        (if (Shield.version >= 0) L("تصدّي لهجمات الشبكة المحلية والتعدين الخفي والتتبع والروابط المخادعة وإغراق النوافذ — تم صدّ ") + Shield.total else ""),
                        Icons.Default.Lock, { Prefs.pickShield(!Prefs.shield) },
                        { Switch(checked = Prefs.shield, onCheckedChange = null) }),
                    RowSpec(L("HTTPS أولاً"), L("ترقية الروابط إلى اتصال مشفّر مع رجوع تلقائي عند عدم الدعم"), Icons.Default.Lock, { Prefs.pickHttpsFirst(!Prefs.httpsFirst) },
                        { Switch(checked = Prefs.httpsFirst, onCheckedChange = null) }),
                    RowSpec(L("الحماية من البصمة"), L("توحيد وتشويش قيم Canvas وWebGL والصوت والجهاز (يُطبَّق على التبويبات الجديدة)"), Icons.Default.Lock, { Prefs.pickAntiFingerprint(!Prefs.antiFingerprint) },
                        { Switch(checked = Prefs.antiFingerprint, onCheckedChange = null) }),
                    RowSpec(L("إزالة معرّفات التتبع"), L("utm و fbclid و gclid وغيرها من الروابط"), Icons.Default.Check, { Prefs.pickCleanUrls(!Prefs.cleanUrls) },
                        { Switch(checked = Prefs.cleanUrls, onCheckedChange = null) }),
                    RowSpec(L("حظر كوكيز الطرف الثالث"), L("قد تتأثر بعض مواقع تسجيل الدخول المشترك"), Icons.Default.Info, { Prefs.pickThirdCookies(!Prefs.blockThirdCookies) },
                        { Switch(checked = Prefs.blockThirdCookies, onCheckedChange = null) }),
                    RowSpec(L("حماية الشاشة"), L("منع لقطات الشاشة وتسجيلها داخل التطبيق"), Icons.Default.Lock, { Prefs.pickSecureScreen(!Prefs.secureScreen) },
                        { Switch(checked = Prefs.secureScreen, onCheckedChange = null) }),
                    RowSpec(L("سجل الأمان"), L("التهديدات والمتتبعات المحجوبة"), Icons.Default.Info, { dialog = "seclog" }),
                    RowSpec(L("فحص سلامة الجهاز"), if (warnings.isEmpty()) L("لا مؤشرات مقلقة") else warnings.joinToString(" • "), Icons.Default.Warning, {})
                ))
                Group(L("الأداء والذاكرة المؤقتة"), listOf(
                    RowSpec(L("الوضع الخفيف (أجهزة ضعيفة / Android Go)"), liteNames[Prefs.lite.coerceIn(0, 2)] + " • " + L("حركات أقصر وتبويبات حيّة أقل واتصالات تنزيل أقل"), Icons.Default.Settings, { dialog = "lite" }),
                    RowSpec(L("مسح الكاش عند كل تشغيل"), L("يُحذف الكاش كلياً عند فتح التطبيق؛ تبقى كلمات المرور وإعدادات المواقع وتسجيلات الدخول"), Icons.Default.Refresh, { Prefs.pickAutoClean(!Prefs.autoClean) },
                        { Switch(checked = Prefs.autoClean, onCheckedChange = null) }),
                    RowSpec(L("تحسين عرض الصفحات"), L("يضبط الصور والأكواد على عرض الشاشة ويمنع التمرير الأفقي (يُطبَّق على التبويبات الجديدة)"), Icons.Default.Settings, { Prefs.pickFitPages(!Prefs.fitPages) },
                        { Switch(checked = Prefs.fitPages, onCheckedChange = null) }),
                    RowSpec(L("تسريع التنقل"), L("جلب مسبق لروابط الموقع عند اللمس، تسخين DNS، وإيقاف الفيديوهات الصامتة خارج الشاشة (يُطبَّق على التبويبات الجديدة)"), Icons.Default.PlayArrow, { Prefs.pickBoost(!Prefs.boost) },
                        { Switch(checked = Prefs.boost, onCheckedChange = null) }),
                    RowSpec(L("اقتراحات البحث"), L("تظهر أثناء الكتابة من المفضلة والسجل ومن محرك البحث المختار (يُرسل ما تكتبه إليه)"), Icons.Default.Search, { Prefs.pickSuggest(!Prefs.suggest) },
                        { Switch(checked = Prefs.suggest, onCheckedChange = null) }),
                    RowSpec(L("وضع التطبيق (PWA)"), L("شريط علوي وتجربة تطبيق ليوتيوب ومواقع الذكاء الاصطناعي (يُطبَّق على التبويبات الجديدة)"), Icons.Default.Star, { Prefs.pickPwaMode(!Prefs.pwaMode) },
                        { Switch(checked = Prefs.pwaMode, onCheckedChange = null) }),
                    RowSpec(L("اهتزاز الأزرار في وضع التطبيق"), L("لمسة اهتزاز خفيفة عند ضغط الأزرار داخل يوتيوب ومواقع الذكاء الاصطناعي"), Icons.Default.Notifications, { Prefs.pickPwaHaptics(!Prefs.pwaHaptics) },
                        { Switch(checked = Prefs.pwaHaptics, onCheckedChange = null) }),
                    RowSpec(L("محرك العرض"), WebEngine.summary(), Icons.Default.Refresh,
                        { if (WebEngine.outdated) WebEngine.openStore(ctx) else WebEngine.checkLatest(ctx, true) }),
                    RowSpec(L("مسح الذاكرة المؤقتة الآن"), (L("الحجم الحالي: ") + cacheSize), Icons.Default.Delete, { dialog = "cache" }),
                    RowSpec(L("إيقاف الصفحات في الخلفية"), L("يوفر المعالج والبطارية عند الخروج من التطبيق (يوقف الصوت أيضاً)"), Icons.Default.Refresh, { Prefs.pickPauseBg(!Prefs.pauseBg) },
                        { Switch(checked = Prefs.pauseBg, onCheckedChange = null) }),
                    RowSpec(L("إخفاء لافتات الكوكيز"), L("يخفي نوافذ الموافقة المعروفة ويفتح قفل التمرير الذي تسببه (يُطبَّق على الصفحات الجديدة)"), Icons.Default.Info, { Prefs.pickPopups(!Prefs.popups) },
                        { Switch(checked = Prefs.popups, onCheckedChange = null) }),
                    RowSpec(L("تحميل كسول للصور"), L("تحميل الصور عند الاقتراب منها فقط"), Icons.Default.KeyboardArrowDown, { Prefs.pickLazyMedia(!Prefs.lazyMedia) },
                        { Switch(checked = Prefs.lazyMedia, onCheckedChange = null) }),
                    RowSpec(L("توفير البيانات"), L("عدم تحميل الصور (يُطبَّق على التبويبات الجديدة)"), Icons.Default.Info, { Prefs.pickDataSaver(!Prefs.dataSaver) },
                        { Switch(checked = Prefs.dataSaver, onCheckedChange = null) })
                ))
                Group(L("الحرارة والسلاسة"), listOf(
                    RowSpec(L("التكيف مع حرارة الجهاز"), L("يخفّف الحركة ويحرّر التبويبات الخلفية عند السخونة أو توفير الطاقة"), Icons.Default.Warning, { Prefs.pickAdaptive(!Prefs.adaptive) },
                        { Switch(checked = Prefs.adaptive, onCheckedChange = null) }),
                    RowSpec(L("تحديد 60Hz عند السخونة"), L("يقلل استهلاك الشاشة والمعالج"), Icons.Default.Refresh, { Prefs.pickCap60(!Prefs.cap60) },
                        { Switch(checked = Prefs.cap60, onCheckedChange = null) }),
                    RowSpec(L("حركات الواجهة"), L("إيقافها يجعل التنقل فورياً ويوفر الطاقة"), Icons.Default.Star, { Prefs.pickSmoothAnim(!Prefs.smoothAnim) },
                        { Switch(checked = Prefs.smoothAnim, onCheckedChange = null) })
                ))
                Group(L("التنزيلات"), listOf(
                    RowSpec(L("الحد الأقصى للاتصالات"), connNames[connOpts.indexOf(Prefs.maxConns).coerceAtLeast(0)], Icons.Default.KeyboardArrowDown, { dialog = "conns" }),
                    RowSpec(L("مكان الحفظ"), "Download/Nova", Icons.Default.Info, {})
                ))
                Group(L("حول"), listOf(
                    RowSpec("Nova Go", L("الإصدار ") + BuildConfig.VERSION_NAME, Icons.Default.Star, {}),
                    RowSpec(L("التحديث التلقائي"), L("فحص الإصدارات الجديدة من GitHub كل 12 ساعة"), Icons.Default.Refresh, { Prefs.pickAutoUpdate(!Prefs.autoUpdate) },
                        { Switch(checked = Prefs.autoUpdate, onCheckedChange = null) }),
                    RowSpec(L("التحقق من تحديث الآن"), updateStatus(), Icons.Default.Info, {
                        when (Updater.phase) {
                            UpdPhase.AVAILABLE -> Updater.showPrompt()
                            UpdPhase.READY -> Updater.install(ctx)
                            else -> Updater.check(ctx, true)
                        }
                    })
                ))
            }
        }
    }

    when (dialog) {
        "ytlog" -> AlertDialog(
            onDismissRequest = { dialog = null }, title = { Text(L("سجل التشخيص")) },
            text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(YtLog.text().ifBlank { L("لا يوجد سجل بعد") }, style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { copyText(ctx, YtLog.text()) }) { Text(L("نسخ")) } },
            dismissButton = { Row {
                TextButton(onClick = { YtLog.clear() ; dialog = null }) { Text(L("مسح السجل")) }
                TextButton(onClick = { dialog = null }) { Text(L("إغلاق")) }
            } }
        )
        "gacct" -> AlertDialog(
            onDismissRequest = { dialog = null }, title = { Text(L("حسابات Google")) },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    GoogleAccounts.accounts.toList().forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { GoogleAccounts.choosePrimary(a) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = a == GoogleAccounts.primary, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(a, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { GoogleAccounts.remove(a) }) { Icon(Icons.Default.Delete, L("حذف")) }
                        }
                    }
                    OutlinedTextField(
                        value = gEmail, onValueChange = { gEmail = it; gErr = false }, singleLine = true,
                        label = { Text(L("البريد الإلكتروني")) }, isError = gErr,
                        supportingText = if (gErr) ({ Text(L("بريد غير صالح")) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Text(
                        L("يُحفظ البريد فقط على جهازك. لا تُخزَّن كلمة مرور Google، ولا تنتقل جلسة Google إلى Nova."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { if (GoogleAccounts.add(gEmail)) { gEmail = ""; gErr = false } else gErr = true }) { Text(L("إضافة")) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(L("إغلاق")) } }
        )
        "lite" -> ChoiceDialog(L("الوضع الخفيف (أجهزة ضعيفة / Android Go)"), liteNames, Prefs.lite.coerceIn(0, 2), { Prefs.pickLite(it) }) { dialog = null }
        "pwmode" -> ChoiceDialog(L("وضع التعبئة التلقائية"), pwNames, Prefs.pwMode.coerceIn(0, 2), { Prefs.pickPwMode(it) }) { dialog = null }
        "lang" -> ChoiceDialog(L("لغة التطبيق"), langNames, Prefs.lang.coerceIn(0, 2), { Prefs.pickLang(it) }) { dialog = null }
        "sitelang" -> ChoiceDialog(L("لغة المواقع"), Prefs.siteLangs.map { if (it.first.isEmpty()) L(it.second) else it.second }, Prefs.siteLang, { Prefs.pickSiteLang(it) }) { dialog = null }
        "zoom" -> ChoiceDialog(L("حجم الخط في المواقع"), zoomNames, Prefs.textZoom, { Prefs.pickTextZoom(it) }) { dialog = null }
        "yccsize" -> ChoiceDialog(L("حجم الترجمة"), ccSizeNames, Prefs.ytCcSize.coerceIn(0, 3), { Prefs.pickYtCcSize(it) }) { dialog = null }
        "yccbg" -> ChoiceDialog(L("خلفية الترجمة"), ccBgNames, Prefs.ytCcBg.coerceIn(0, 2), { Prefs.pickYtCcBg(it) }) { dialog = null }
        "yccpos" -> ChoiceDialog(L("موضع الترجمة"), ccPosNames, Prefs.ytCcPos.coerceIn(0, 2), { Prefs.pickYtCcPos(it) }) { dialog = null }
        "engine" -> ChoiceDialog(L("محرك البحث"), Prefs.engines.map { it.first }, Prefs.engine, { Prefs.pickEngine(it) }) { dialog = null }
        "theme" -> ChoiceDialog(L("المظهر"), themeNames, Prefs.theme, { Prefs.pickTheme(it) }) { dialog = null }
        "conns" -> ChoiceDialog(L("الحد الأقصى للاتصالات"), connNames, connOpts.indexOf(Prefs.maxConns).coerceAtLeast(0), { Prefs.pickMaxConns(connOpts[it]) }) { dialog = null }
        "seclog" -> AlertDialog(
            onDismissRequest = { dialog = null }, title = { Text(L("سجل الأمان")) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text(Security.summary(), style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(L("إغلاق")) } },
            dismissButton = { TextButton(onClick = { Security.clearLog(); dialog = null }) { Text(L("مسح السجل")) } }
        )
        "cache" -> AlertDialog(
            onDismissRequest = { dialog = null }, title = { Text(L("مسح الذاكرة المؤقتة؟")) },
            text = { Text(L("سيتم حذف ملفات الكاش المؤقتة فقط. قد يبطؤ تحميل الصفحات المرة القادمة قليلاً.")) },
            confirmButton = { TextButton(onClick = { dialog = null; onClearCache(); cacheTick++ }) { Text(L("مسح")) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(L("إلغاء")) } }
        )
        "clear" -> AlertDialog(
            onDismissRequest = { dialog = null }, title = { Text(L("مسح بيانات التصفح؟")) },
            text = { Text(L("سيتم حذف الكوكيز والذاكرة المؤقتة وسجل التبويبات. لن تُحذف التنزيلات.")) },
            confirmButton = { TextButton(onClick = { dialog = null; onClearData() }) { Text(L("مسح")) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(L("إلغاء")) } }
        )
    }
}
