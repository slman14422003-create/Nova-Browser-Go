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

@Composable
fun FindBar(tab: BrowserTab) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    Surface(Modifier.imePadding().fillMaxWidth(), color = cs.background) {
        Row(Modifier.navigationBarsPadding().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = q, onValueChange = { q = it; tab.webView?.findAllAsync(it) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.weight(1f).padding(start = 12.dp).focusRequester(fr),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (q.isEmpty()) Text(L("بحث في الصفحة"), color = cs.onSurfaceVariant)
                        inner()
                    }
                }
            )
            Text(tab.findInfo, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            IconButton(onClick = { tab.webView?.findNext(false) }) { Icon(Icons.Default.KeyboardArrowUp, L("السابق")) }
            IconButton(onClick = { tab.webView?.findNext(true) }) { Icon(Icons.Default.KeyboardArrowDown, L("التالي")) }
            IconButton(onClick = { tab.webView?.clearMatches(); tab.finding = false }) { Icon(Icons.Default.Close, L("إغلاق")) }
        }
    }
}

class SitePrompt(val title: String, val message: String, val onAllow: () -> Unit, val onDeny: () -> Unit)

@Composable
fun BottomPill(
    tab: BrowserTab, tabCount: Int, editing: Boolean, setEditing: (Boolean) -> Unit,
    onGo: (String) -> Unit, onTabs: () -> Unit, onNewTab: () -> Unit, onHome: () -> Unit,
    onFind: () -> Unit, onDesktop: () -> Unit, onShare: () -> Unit, onCopy: () -> Unit,
    onDownloads: () -> Unit, onSwitch: (Int) -> Unit, onSettings: () -> Unit, onTranslate: () -> Unit = {},
    onPrint: () -> Unit = {}, onCustomTab: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val hasPage = tab.url.isNotBlank()
    var query by remember { mutableStateOf("") }          // نص الحقل أثناء الكتابة (لاقتراحات البحث)
    var fill by remember { mutableStateOf<String?>(null) } // اقتراح طُلب إدراجه في الحقل
    LaunchedEffect(editing) { if (!editing) { query = ""; fill = null } }

    Surface(Modifier.imePadding().fillMaxWidth(), shape = RectangleShape, color = cs.background) {
        Column(Modifier.navigationBarsPadding()) {
            if (editing) SuggestionsPanel(
                query = query,
                onPick = { onGo(it); setEditing(false); focus.clearFocus() },
                onFill = { fill = it }
            )
            if (tab.loading && !editing) {
                LinearProgressIndicator(progress = { tab.progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = cs.tertiary, trackColor = Color.Transparent)
            } else Spacer(Modifier.height(3.dp))

            AnimatedContent(
                targetState = editing,
                transitionSpec = { fadeIn(tween(Adaptive.ms(120))) togetherWith fadeOut(tween(Adaptive.ms(80))) },
                label = "bar"
            ) { isEditing ->
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isEditing) {
                        var field by remember { mutableStateOf(TextFieldValue(tab.url, TextRange(0, tab.url.length))) }
                        val fr = remember { FocusRequester() }
                        var got by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { fr.requestFocus() }
                        LaunchedEffect(fill) { fill?.let { f -> field = TextFieldValue(f, TextRange(f.length)); query = f; fill = null } }
                        Icon(Icons.Default.Search, null, Modifier.padding(start = 10.dp), tint = cs.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = field, onValueChange = { field = it; query = it.text }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                if (field.text.isNotBlank()) onGo(field.text)
                                setEditing(false); focus.clearFocus()
                            }),
                            modifier = Modifier.weight(1f).focusRequester(fr).onFocusChanged { if (it.isFocused) got = true else if (got) setEditing(false) },
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (field.text.isEmpty()) Text(L("ابحث أو اكتب عنوان"), color = cs.onSurfaceVariant)
                                    inner()
                                }
                            }
                        )
                        IconButton(onClick = { field = TextFieldValue(""); query = "" }) { Icon(Icons.Default.Close, L("مسح")) }
                    } else {
                        RoundBtn(onClick = { if (tab.canBack) tab.webView?.goBack() else onHome() }) {
                            Icon(if (tab.canBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Home, if (tab.canBack) L("رجوع") else L("الرئيسية"))
                        }
                        Row(
                            Modifier.weight(1f).height(48.dp).clip(CircleShape).background(cs.surfaceContainerHigh)
                                .pointerInput(Unit) {
                                    var dx = 0f; var dy = 0f
                                    detectDragGestures(
                                        onDragStart = { dx = 0f; dy = 0f },
                                        onDrag = { _, a -> dx += a.x; dy += a.y },
                                        onDragEnd = {
                                            if (dy < -60f && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress); onTabs()
                                            } else if (kotlin.math.abs(dx) > 120f) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSwitch(if (dx < 0) 1 else -1)
                                            }
                                        }
                                    )
                                }
                                .clickable { setEditing(true) }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
                        ) {
                            val insecure = tab.url.startsWith("http://")
                            Icon(
                                if (insecure) Icons.Default.Warning else if (tab.url.startsWith("https")) Icons.Default.Lock else Icons.Default.Search,
                                if (insecure) L("اتصال غير مشفّر") else null, Modifier.size(15.dp),
                                tint = if (insecure) cs.error else cs.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (!hasPage) L("ابحث أو اكتب عنوان") else hostOf(tab.url), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, color = if (hasPage) cs.onSurface else cs.onSurfaceVariant
                            )
                        }
                        RoundBtn(onClick = onTabs) {
                            Box(Modifier.size(24.dp).border(2.dp, cs.onSurface, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                                Text(if (tabCount > 99) "99+" else "$tabCount", maxLines = 1, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        RoundBtn(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, L("المزيد")) }
                        if (menu) MenuSheet(tab, { menu = false }, onNewTab, onFind, onDesktop, onShare, onCopy, onDownloads, onSettings, onHome, onTranslate, onPrint, onCustomTab)
                    }
                }
            }
        }
    }
}

@Composable
fun RoundBtn(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier.padding(horizontal = 2.dp).size(48.dp).pressScale(src, 0.9f).clip(CircleShape).background(cs.surfaceContainerHigh)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { CompositionLocalProvider(LocalContentColor provides cs.onSurface) { content() } }
}

@Composable
fun Reveal(shown: Boolean, delay: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(Adaptive.ms(320), delay.coerceAtMost(240))) + slideInVertically(tween(Adaptive.ms(320), delay.coerceAtMost(240), FastOutSlowInEasing)) { it / 8 }
    ) { content() }
}

fun groupShape(i: Int, n: Int): RoundedCornerShape {
    val big = 28.dp; val small = 6.dp
    val top = if (i == 0) big else small
    val bot = if (i == n - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bot, bottomEnd = bot)
}

@Composable
fun IconCircle(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(40.dp).clip(CircleShape).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun ListRow(
    shape: RoundedCornerShape, title: String, sub: String?, onClick: () -> Unit,
    enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier, leading: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onClick, enabled = enabled, shape = shape, color = cs.surfaceContainerHigh, interactionSource = src,
        modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).pressScale(src, 0.985f)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
        }
    }
}
