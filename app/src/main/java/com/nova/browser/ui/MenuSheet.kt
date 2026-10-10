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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
private fun RowScope.QuickTile(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh,
        modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.4f)
    ) {
        Column(Modifier.padding(vertical = 16.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * قائمة الخيارات: الشاشة الرئيسية قصيرة (أزرار سريعة + 4 صفوف)، وكل أدوات الصفحة في صفحة فرعية واحدة
 * داخل نفس الورقة، فلا تمرير طويل ولا ازدحام.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    tab: BrowserTab, onDismiss: () -> Unit, onNewTab: () -> Unit, onFind: () -> Unit, onDesktop: () -> Unit,
    onShare: () -> Unit, onCopy: () -> Unit, onDownloads: () -> Unit, onSettings: () -> Unit, onHome: () -> Unit, onTranslate: () -> Unit = {},
    onPrint: () -> Unit = {}, onCustomTab: () -> Unit = {}, onPip: () -> Unit = {}, onExit: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val hasPage = tab.url.isNotBlank()
    var tools by remember { mutableStateOf(false) }
    fun act(a: () -> Unit) { scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); a() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = cs.background) {
        BackHandler(enabled = tools) { tools = false }
        AnimatedContent(
            targetState = tools, label = "menu",
            transitionSpec = { fadeIn(tween(Adaptive.ms(180))) togetherWith fadeOut(tween(Adaptive.ms(120))) }
        ) { isTools ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
                if (!isTools) {
                    val marked = hasPage && Library.isBookmarked(tab.url)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickTile(L("التالي"), Icons.AutoMirrored.Filled.ArrowForward, tab.canForward) { act { tab.webView?.goForward() } }
                        QuickTile(if (tab.loading) L("إيقاف") else L("تحديث"), if (tab.loading) Icons.Default.Close else Icons.Default.Refresh, hasPage) {
                            act { if (tab.loading) tab.webView?.stopLoading() else tab.webView?.reload() }
                        }
                        QuickTile(L("مشاركة"), Icons.Default.Share, hasPage) { act(onShare) }
                        QuickTile(L("المفضلة"), if (marked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, hasPage) {
                            act { toast(ctx, if (Library.toggleBookmark(tab.url, tab.title)) L("أُضيفت إلى المفضلة") else L("أُزيلت من المفضلة")) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickTile(L("المكتبة"), Icons.Default.Star, true) { act { openPanel(ctx as android.app.Activity, "library") } }
                        QuickTile(L("التنزيلات"), Icons.Default.KeyboardArrowDown, true) { act(onDownloads) }
                        QuickTile(L("الإعدادات"), Icons.Default.Settings, true) { act(onSettings) }
                        QuickTile(L("الرئيسية"), Icons.Default.Home, true) { act(onHome) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        ListRow(groupShape(0, 4), L("تبويب جديد"), null, { act(onNewTab) }) { IconCircle { Icon(Icons.Default.Add, null) } }
                        ListRow(groupShape(1, 4), L("بحث في الصفحة"), null, { act(onFind) }, enabled = hasPage) { IconCircle { Icon(Icons.Default.Search, null) } }
                        ListRow(groupShape(2, 4), L("نسخة سطح المكتب"), null, { onDesktop() }, enabled = hasPage,
                            trailing = { Switch(checked = tab.desktop, onCheckedChange = null) }) { IconCircle { Icon(Icons.Default.Build, null) } }
                        ListRow(groupShape(3, 4), L("أدوات الصفحة"), L("ترجمة • طباعة • وضع القراءة • نسخ"), { tools = true }, enabled = hasPage,
                            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }) { IconCircle { Icon(Icons.Default.Menu, null) } }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundBtn(onClick = { tools = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("رجوع")) }
                        Spacer(Modifier.width(8.dp))
                        Text(L("أدوات الصفحة"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickTile(L("الأعلى"), Icons.Default.KeyboardArrowUp, hasPage) { act { tab.webView?.let { WebSupport.scroll(it, true) } } }
                        QuickTile(L("الأسفل"), Icons.Default.KeyboardArrowDown, hasPage) { act { tab.webView?.let { WebSupport.scroll(it, false) } } }
                        QuickTile(L("وضع القراءة"), Icons.Default.Menu, hasPage) { act { tab.webView?.let { WebSupport.reader(it) } } }
                        QuickTile(L("تحديث كامل"), Icons.Default.Refresh, hasPage) { act { tab.webView?.let { WebSupport.hardReload(it) } } }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickTile(L("إخفاء العائم"), Icons.Default.Close, hasPage) { act { tab.webView?.let { WebSupport.toggleFloating(it) } } }
                        QuickTile(L("الصور"), Icons.Default.Info, hasPage) { act { tab.webView?.let { WebSupport.toggleImages(it) } } }
                        QuickTile(L("إبقاء الشاشة"), Icons.Default.Star, hasPage) { act { tab.webView?.let { WebSupport.toggleKeepOn(it) } } }
                        QuickTile(L("نسخ النص"), Icons.Default.Edit, hasPage) { act { tab.webView?.let { WebSupport.copyPageText(it) } } }
                    }
                    Spacer(Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        ListRow(groupShape(0, 4), L("نسخ الرابط"), null, { act(onCopy) }, enabled = hasPage) { IconCircle { Icon(Icons.Default.Edit, null) } }
                        ListRow(groupShape(1, 4), L("ترجمة الصفحة"), null, { act(onTranslate) }, enabled = hasPage) { IconCircle { Icon(Icons.Default.Share, null) } }
                        ListRow(groupShape(2, 4), L("طباعة / حفظ PDF"), null, { act(onPrint) }, enabled = hasPage) { IconCircle { Icon(Icons.Default.Create, null) } }
                        ListRow(groupShape(3, 4), L("فتح في Chrome"), null, { act(onCustomTab) }, enabled = hasPage) { IconCircle { Icon(Icons.Default.ExitToApp, null) } }
                    }
                    Spacer(Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        // نافذة منبثقة لأي فيديو يعمل (يوتيوب أو غيره)
                        ListRow(groupShape(0, 2), L("نافذة منبثقة"), null, { act(onPip) }, enabled = tab.mediaVideo || tab.ytPlaying) { IconCircle { Icon(Icons.Default.PlayArrow, null) } }
                        // خروج كامل: يحفظ الإعدادات ويغلق التطبيق وكل ما يعمل في الخلفية
                        ListRow(groupShape(1, 2), L("خروج"), L("إغلاق التطبيق بالكامل"), { act(onExit) }) { IconCircle { Icon(Icons.Default.Close, null) } }
                    }
                }
            }
        }
    }
}
