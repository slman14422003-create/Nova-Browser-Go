package com.nova.browser

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

// ألوان السبلاش نفسها: الخطوة الأولى تبدأ بنفس الخلفية والأيقونة كي يبدو الانتقال استمراراً للسبلاش
private val SplashBg = Color(0xFFFAF9F5)
private val SplashInk = Color(0xFF141413)
private val SplashSpark = Color(0xFFC6613F)

private const val S_WELCOME = 0
private const val S_LANG = 1
private const val S_THEME = 2
private const val S_SEARCH = 3
private const val S_PRIVACY = 4
private const val S_ACCESS = 5
private const val S_ENGINE = 6
private const val S_DONE = 7

/** شعار Nova (نفس هندسة splash_icon.xml) مرسوم بالكانفس ليمكن تحريكه. */
@Composable
fun NovaMark(modifier: Modifier, ring: Color, spark: Color, ringProgress: Float = 1f, sparkScale: Float = 1f, sparkRotate: Float = 0f) {
    Canvas(modifier) {
        val k = size.minDimension / 288f
        drawArc(
            color = ring, startAngle = -70f, sweepAngle = -310f * ringProgress, useCenter = false,
            topLeft = Offset(81f * k, 81f * k), size = Size(126f * k, 126f * k),
            style = Stroke(width = 13.5f * k, cap = StrokeCap.Round)
        )
        drawCircle(ring.copy(alpha = ring.alpha * ringProgress), radius = 10.2f * k, center = Offset(188.55f * k, 99.45f * k))
        val star = Path().apply {
            moveTo(144f * k, 96f * k)
            cubicTo(148.5f * k, 123f * k, 165f * k, 139.5f * k, 192f * k, 144f * k)
            cubicTo(165f * k, 148.5f * k, 148.5f * k, 165f * k, 144f * k, 192f * k)
            cubicTo(139.5f * k, 165f * k, 123f * k, 148.5f * k, 96f * k, 144f * k)
            cubicTo(123f * k, 139.5f * k, 139.5f * k, 123f * k, 144f * k, 96f * k)
            close()
        }
        val c = Offset(144f * k, 144f * k)
        rotate(sparkRotate, c) { scale(sparkScale, c) { drawPath(star, spark) } }
    }
}

@Composable
fun Onboarding(onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val layoutDir = LocalLayoutDirection.current
    var step by remember { mutableIntStateOf(S_WELCOME) }
    var forward by remember { mutableStateOf(true) }
    fun go(n: Int) { val t = n.coerceIn(S_WELCOME, S_DONE); forward = t > step; step = t }
    BackHandler(enabled = step > S_WELCOME) { go(step - 1) }

    val onSplash = step == S_WELCOME
    val bg by animateColorAsState(if (onSplash) SplashBg else cs.background, tween(Adaptive.ms(450)), label = "onb-bg")
    val ink by animateColorAsState(if (onSplash) SplashInk else cs.onBackground, tween(Adaptive.ms(450)), label = "onb-ink")
    val topA by animateFloatAsState(if (step in S_LANG until S_DONE) 1f else 0f, tween(Adaptive.ms(250)), label = "onb-top")

    CompositionLocalProvider(LocalContentColor provides ink) {
        Column(Modifier.fillMaxSize().background(bg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp).alpha(topA), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in S_LANG until S_DONE) {
                        val on by animateColorAsState(if (step >= i) cs.tertiary else cs.outlineVariant, tween(Adaptive.ms(300)), label = "seg")
                        Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(on))
                    }
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onDone, enabled = topA > 0.5f) { Text(L("تخطي"), color = cs.onSurfaceVariant) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val fwd = targetState > initialState
                        val s = (if (fwd) 1 else -1) * (if (layoutDir == LayoutDirection.Rtl) -1 else 1)
                        (slideInHorizontally(tween(Adaptive.ms(380), easing = FastOutSlowInEasing)) { it / 4 * s } +
                            fadeIn(tween(Adaptive.ms(300), Adaptive.ms(60)))) togetherWith
                            (slideOutHorizontally(tween(Adaptive.ms(300), easing = FastOutSlowInEasing)) { -it / 4 * s } +
                                fadeOut(tween(Adaptive.ms(180))))
                    },
                    label = "onb-step"
                ) { s ->
                    val back = { go(s - 1) }
                    val next = { go(s + 1) }
                    when (s) {
                        S_WELCOME -> WelcomeStep(next)
                        S_LANG -> LangStep(back, next)
                        S_THEME -> ThemeStep(back, next)
                        S_SEARCH -> SearchStep(back, next)
                        S_PRIVACY -> PrivacyStep(back, next)
                        S_ACCESS -> AccessStep(back, next)
                        S_ENGINE -> EngineStep(forward, back, next)
                        else -> DoneStep(back, onDone)
                    }
                }
            }
        }
    }
}

// ───────────────────────── الخطوات ─────────────────────────

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    var phase by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(if (Adaptive.ms(100) == 0) 0L else 350L); phase = true }
    val t by animateFloatAsState(if (phase) 1f else 0f, tween(Adaptive.ms(800), easing = FastOutSlowInEasing), label = "welcome")
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        NovaMark(
            Modifier.align(Alignment.Center).size(288.dp).graphicsLayer {
                val sc = 1f - 0.22f * t
                scaleX = sc; scaleY = sc
                translationY = -with(density) { 110.dp.toPx() } * t
            },
            ring = SplashInk, spark = SplashSpark
        )
        Column(
            Modifier.align(Alignment.Center).offset(y = 70.dp).padding(horizontal = 32.dp).graphicsLayer { alpha = t; translationY = (1f - t) * 30f },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Nova Go", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(L("متصفح سريع وخفيف وآمن — لنجهّزه لك في دقيقة"), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = SplashInk.copy(alpha = 0.62f))
        }
        Button(
            onClick = onNext, enabled = t > 0.8f,
            colors = ButtonDefaults.buttonColors(containerColor = SplashInk, contentColor = SplashBg, disabledContainerColor = SplashInk, disabledContentColor = SplashBg),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).fillMaxWidth().height(56.dp).graphicsLayer { alpha = t }
        ) {
            Text(L("ابدأ الإعداد"), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
        }
    }
}

@Composable
private fun LangStep(onBack: () -> Unit, onNext: () -> Unit) {
    OnbPage(
        title = L("اختر لغتك"), sub = L("يمكنك تغييرها لاحقاً من الإعدادات"),
        badge = { Text("文A", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary) },
        footer = { OnbFooter(false, L("التالي"), onBack, onNext) }
    ) {
        val opts = listOf(0 to L("تلقائي (حسب النظام)"), 1 to "العربية", 2 to "English")
        opts.forEachIndexed { i, (v, name) ->
            OnbRise(i) { OnbChoice(Prefs.lang == v, name, null, { Prefs.pickLang(v) }) }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ThemeStep(onBack: () -> Unit, onNext: () -> Unit) {
    OnbPage(
        title = L("اختر المظهر"), sub = L("يتغيّر التطبيق فوراً"),
        badge = { Text("◐", fontSize = 28.sp, color = MaterialTheme.colorScheme.tertiary) },
        footer = { OnbFooter(true, L("التالي"), onBack, onNext) }
    ) {
        OnbRise(0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OnbThemeCard(0, L("حسب النظام"), Modifier.weight(1f))
                OnbThemeCard(1, L("فاتح"), Modifier.weight(1f))
                OnbThemeCard(2, L("داكن"), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SearchStep(onBack: () -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    OnbPage(
        title = L("محرك البحث"), sub = L("اختر المحرك الذي يبحث به شريط العنوان"),
        badge = { Icon(Icons.Default.Search, null, tint = cs.tertiary) },
        footer = { OnbFooter(true, L("التالي"), onBack, onNext) }
    ) {
        Prefs.engines.forEachIndexed { i, e ->
            OnbRise(i) {
                OnbChoice(Prefs.engine == i, e.first, null, { Prefs.pickEngine(i) }) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        Text(e.first.take(1), fontWeight = FontWeight.Bold, color = cs.onSurface)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PrivacyStep(onBack: () -> Unit, onNext: () -> Unit) {
    OnbPage(
        title = L("خصوصيتك أولاً"), sub = L("فعّل الحماية التي تناسبك"),
        badge = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.tertiary) },
        footer = { OnbFooter(true, L("التالي"), onBack, onNext) }
    ) {
        OnbRise(0) { OnbSwitch(L("حجب الإعلانات والمتتبعات"), L("صفحات أسرع وأقل تتبعاً"), Prefs.blockAds) { Prefs.pickBlockAds(it) } }
        Spacer(Modifier.height(10.dp))
        OnbRise(1) { OnbSwitch(L("الحماية من البصمة"), L("تقلّل قدرة المواقع على التعرّف عليك"), Prefs.antiFingerprint) { Prefs.pickAntiFingerprint(it) } }
        Spacer(Modifier.height(10.dp))
        OnbRise(2) { OnbSwitch(L("ترقية الروابط إلى HTTPS"), L("اتصال مشفّر كلما أمكن"), Prefs.httpsFirst) { Prefs.pickHttpsFirst(it) } }
        Spacer(Modifier.height(10.dp))
        OnbRise(3) { OnbSwitch(L("إزالة معرّفات التتبع من الروابط"), L("روابط أنظف عند الفتح والمشاركة"), Prefs.cleanUrls) { Prefs.pickCleanUrls(it) } }
    }
}

@Composable
private fun AccessStep(onBack: () -> Unit, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val notif = rememberNotifState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { DefaultBrowser.refresh(ctx) }
    val lc = (ctx as? ComponentActivity)?.lifecycle
    DisposableEffect(lc) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) DefaultBrowser.refresh(ctx) }
        lc?.addObserver(o)
        onDispose { lc?.removeObserver(o) }
    }
    OnbPage(
        title = L("الإشعارات والمتصفح الافتراضي"), sub = L("اختياري — يمكنك تخطيه"),
        badge = { Icon(Icons.Default.Notifications, null, tint = cs.tertiary) },
        footer = { OnbFooter(true, L("التالي"), onBack, onNext) }
    ) {
        if (Notif.needsPermission) {
            OnbRise(0) { OnbAction(L("إشعارات التنزيل والتحديثات"), L("إشعار عند اكتمال كل ملف"), notif.enabled, L("السماح")) { notif.request() } }
            Spacer(Modifier.height(10.dp))
        }
        OnbRise(1) {
            OnbAction(L("اجعل Nova متصفحك الافتراضي"), L("تُفتح الروابط من التطبيقات الأخرى هنا"), DefaultBrowser.isDefault, L("تعيين")) {
                val i = DefaultBrowser.requestIntent(ctx)
                if (i != null) runCatching { launcher.launch(i) }.onFailure { DefaultBrowser.openSystemSettings(ctx) }
                else DefaultBrowser.openSystemSettings(ctx)
            }
        }
    }
}

@Composable
private fun EngineStep(autoAdvance: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { WebEngine.checkLatest(ctx, true); asked = true }
    val info = WebEngine.info
    // 0 جارٍ الفحص، 1 يوجد تحديث، 2 محدّث، 3 تعذّر الفحص
    val state = when {
        !asked || WebEngine.checking -> 0
        info == null || WebEngine.latestMajor <= 0 -> 3
        WebEngine.behind >= 1 -> 1
        else -> 2
    }
    LaunchedEffect(state) { if (autoAdvance && (state == 2 || state == 3)) { delay(1500); onNext() } }
    OnbPage(
        title = L("محرك العرض (WebView)"),
        sub = when (state) { 1 -> L("اختياري — يمكنك المتابعة بدون تحديث"); else -> L("نتأكد أن محرك العرض بأحدث إصدار") },
        badge = { Icon(Icons.Default.Refresh, null, tint = cs.tertiary) },
        footer = { OnbFooter(true, if (state == 1) L("لاحقاً") else L("التالي"), onBack, onNext) }
    ) {
        OnbRise(0) {
            Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (state) {
                        0 -> {
                            CircularProgressIndicator(strokeWidth = 3.dp, color = cs.tertiary)
                            Spacer(Modifier.height(14.dp))
                            Text(L("جارٍ فحص محرك العرض…"), color = cs.onSurfaceVariant)
                        }
                        1 -> {
                            Text(L("يتوفر تحديث لمحرك العرض"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("${L("الإصدار الحالي")} ${info?.major ?: 0}  →  ${L("الأحدث")} ${WebEngine.latestMajor}", color = cs.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Text(L("تحديثه يحسّن السرعة والتوافق مع المواقع"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { WebEngine.openStore(ctx) }, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                Text(L("تحديث الآن"), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        2 -> {
                            OnbCheckBadge()
                            Spacer(Modifier.height(12.dp))
                            Text(L("محرك العرض محدّث"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        else -> {
                            Icon(Icons.Default.Info, null, tint = cs.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Text(L("تعذّر فحص محرك العرض الآن"), fontWeight = FontWeight.SemiBold)
                            Text(L("يمكنك تحديثه لاحقاً من الإعدادات"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneStep(onBack: () -> Unit, onFinish: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ringP = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        ringP.animateTo(1f, tween(Adaptive.ms(900), easing = FastOutSlowInEasing))
        spin.animateTo(360f, tween(Adaptive.ms(900), easing = FastOutSlowInEasing))
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            NovaMark(Modifier.size(200.dp), ring = cs.onBackground, spark = cs.tertiary, ringProgress = ringP.value, sparkScale = 0.6f + 0.4f * ringP.value, sparkRotate = spin.value)
            Spacer(Modifier.height(8.dp))
            OnbRise(3) { Text(L("كل شيء جاهز"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            OnbRise(4) { Text(L("استمتع بتصفح سريع وآمن"), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center) }
        }
        OnbFooter(false, L("ابدأ التصفح"), onBack, onFinish)
    }
}

// ───────────────────────── مكوّنات مشتركة ─────────────────────────

@Composable
private fun OnbPage(title: String, sub: String, badge: @Composable () -> Unit, footer: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { on = true }
    val pop by animateFloatAsState(if (on) 1f else 0.6f, spring(dampingRatio = 0.5f, stiffness = 300f), label = "badge")
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.size(64.dp).graphicsLayer { scaleX = pop; scaleY = pop }.clip(CircleShape).background(cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) { badge() }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            content()
            Spacer(Modifier.height(16.dp))
        }
        footer()
    }
}

@Composable
private fun OnbFooter(canBack: Boolean, nextLabel: String, onBack: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (canBack) {
            RoundBtn(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Spacer(Modifier.width(12.dp))
        }
        Button(onClick = onNext, shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f).height(56.dp)) {
            Text(nextLabel, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
        }
    }
}

/** ظهور متتابع: كل عنصر يصعد ويتلاشى للداخل بعد سابقه بقليل. */
@Composable
private fun OnbRise(i: Int, content: @Composable () -> Unit) {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { on = true }
    val a by animateFloatAsState(if (on) 1f else 0f, tween(durationMillis = Adaptive.ms(380), delayMillis = Adaptive.ms(70 * i + 90), easing = FastOutSlowInEasing), label = "rise")
    Box(Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 40f }) { content() }
}

@Composable
private fun OnbCheckDot(selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val s by animateFloatAsState(if (selected) 1f else 0f, tween(Adaptive.ms(200)), label = "dot")
    Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, cs.outline.copy(alpha = 0.5f * (1f - s)), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = s }.clip(CircleShape).background(cs.tertiary), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.White)
        }
    }
}

@Composable
private fun OnbCheckBadge() {
    val cs = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { on = true }
    val s by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = 250f), label = "check")
    Box(Modifier.size(56.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(cs.tertiary), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Check, null, Modifier.size(30.dp), tint = Color.White)
    }
}

@Composable
private fun OnbChoice(selected: Boolean, title: String, sub: String?, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val container by animateColorAsState(if (selected) cs.primaryContainer else cs.surfaceContainerHigh, tween(Adaptive.ms(220)), label = "choice-bg")
    val edge by animateColorAsState(if (selected) cs.tertiary else Color.Transparent, tween(Adaptive.ms(220)), label = "choice-edge")
    Surface(
        onClick = onClick, interactionSource = src, shape = RoundedCornerShape(22.dp), color = container,
        border = BorderStroke(2.dp, edge), modifier = Modifier.fillMaxWidth().pressScale(src, 0.98f)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            OnbCheckDot(selected)
        }
    }
}

@Composable
private fun OnbSwitch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    Surface(
        onClick = { onChange(!checked) }, interactionSource = src, shape = RoundedCornerShape(22.dp), color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().pressScale(src, 0.985f)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun OnbAction(title: String, sub: String, done: Boolean, actionLabel: String, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(22.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            if (done) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = cs.tertiary)
                    Spacer(Modifier.width(4.dp))
                    Text(L("تم"), color = cs.tertiary, fontWeight = FontWeight.SemiBold)
                }
            } else FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun OnbThemeCard(mode: Int, label: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val selected = Prefs.theme == mode
    val edge by animateColorAsState(if (selected) cs.tertiary else cs.outlineVariant, tween(Adaptive.ms(220)), label = "theme-edge")
    val light = Color(0xFFFAF9F5); val dark = Color(0xFF141413)
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.clip(shape).clickable { Prefs.pickTheme(mode) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(116.dp).clip(shape).border(2.dp, edge, shape)) {
            when (mode) {
                1 -> Box(Modifier.fillMaxSize().background(light))
                2 -> Box(Modifier.fillMaxSize().background(dark))
                else -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(light))
                    Box(Modifier.weight(1f).fillMaxHeight().background(dark))
                }
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(44.dp).height(10.dp).clip(CircleShape).background(cs.tertiary))
                Box(Modifier.width(30.dp).height(6.dp).clip(CircleShape).background(Color(0xFFB0AEA5)))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Spacer(Modifier.height(4.dp))
    }
}
