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

private val LightColors = lightColorScheme(
    primary = Color(0xFF141413), onPrimary = Color(0xFFFAF9F5),
    primaryContainer = Color(0xFFF6DDD2), onPrimaryContainer = Color(0xFF5A2A1B),
    secondary = Color(0xFF3D3D3A), onSecondary = Color(0xFFFAF9F5),
    secondaryContainer = Color(0xFFE0DDD2), onSecondaryContainer = Color(0xFF141413),
    tertiary = Color(0xFFC6613F), onTertiary = Color.White,
    background = Color(0xFFFAF9F5), onBackground = Color(0xFF141413),
    surface = Color(0xFFFAF9F5), onSurface = Color(0xFF141413), onSurfaceVariant = Color(0xFF6B6A68),
    outline = Color(0xFFB0AEA5), outlineVariant = Color(0xFFE0DDD2),
    surfaceContainer = Color(0xFFF0EEE6), surfaceContainerHigh = Color(0xFFE9E6DC), surfaceContainerHighest = Color(0xFFDEDBD0)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFAF9F5), onPrimary = Color(0xFF141413),
    primaryContainer = Color(0xFF3B2A23), onPrimaryContainer = Color(0xFFF3B49C),
    secondary = Color(0xFFB0AEA5), onSecondary = Color(0xFF141413),
    secondaryContainer = Color(0xFF353330), onSecondaryContainer = Color(0xFFFAF9F5),
    tertiary = Color(0xFFD97757), onTertiary = Color.White,
    background = Color(0xFF141413), onBackground = Color(0xFFFAF9F5),
    surface = Color(0xFF141413), onSurface = Color(0xFFFAF9F5), onSurfaceVariant = Color(0xFFB0AEA5),
    outline = Color(0xFF6B6A68), outlineVariant = Color(0xFF3A3835),
    surfaceContainer = Color(0xFF1F1E1D), surfaceContainerHigh = Color(0xFF2A2927), surfaceContainerHighest = Color(0xFF353330)
)
// خط النظام الافتراضي (Roboto/Noto) بدل Serif: أوضح على الشاشات الصغيرة والقديمة، وأخف في التحميل
private val NovaTypography = Typography()

/** سمة التطبيق المشتركة بين نافذة المتصفح ونافذة اللوحات (ألوان، خط، اتجاه RTL/LTR، ألوان أشرطة النظام). */
@Composable
fun NovaTheme(activity: ComponentActivity, content: @Composable () -> Unit) {
    val dark = when (Prefs.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    SideEffect {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            // قبل أندرويد 8 لا توجد أيقونات تنقّل داكنة: شريط أسود ثابت يضمن ظهور الأزرار على أي خلفية
            navigationBarStyle = if (android.os.Build.VERSION.SDK_INT < 26) SystemBarStyle.dark(android.graphics.Color.BLACK)
                else SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
        )
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = NovaTypography) {
        // اتجاه الواجهة يتبع لغة التطبيق (العربية RTL، الإنجليزية LTR)
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides
            if (I18n.isEnglish()) androidx.compose.ui.unit.LayoutDirection.Ltr else androidx.compose.ui.unit.LayoutDirection.Rtl) {
            content()
        }
    }
}
