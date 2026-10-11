package com.nova.browser

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/**
 * جسر بين نافذة المتصفح (WebView) ونافذة اللوحات. الاثنتان في نفس العملية، فلا حاجة لأي تسلسل:
 * اللوحات تطلب فقط ما يحتاج الـ WebView (مسح البيانات، فتح رابط) والمتصفح ينفّذه.
 */
object PanelBus {
    var clearData: (() -> Unit)? = null
    var clearCache: (() -> Unit)? = null
    var openUrl: ((String) -> Unit)? = null
}

/** يفتح لوحة (الإعدادات/التنزيلات/المكتبة/كلمات المرور) في نافذة مستقلة بلا أي WebView. */
fun openPanel(a: Activity, kind: String) {
    val i = Intent(a, PanelActivity::class.java)
        .putExtra(PanelActivity.KIND, kind)
        .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)   // لا يُعدّ ذلك «مغادرة» فلا تُفعَّل النافذة المنبثقة التلقائية للفيديو
    a.startActivity(i, ActivityOptions.makeCustomAnimation(a, R.anim.nova_panel_in, R.anim.nova_hold).toBundle())
}

/**
 * نافذة اللوحات الأصلية: Compose فقط، بلا WebView ولا صفحات. عند فتحها يتوقف رسم صفحة المتصفح تماماً
 * (نافذتها في الخلفية)، فلا تتراكم الواجهات فوق فيديو يوتيوب كما كان يحدث عندما كانت كلها طبقات داخل نافذة واحدة.
 */
class PanelActivity : ComponentActivity() {
    companion object { const val KIND = "panel" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // إن أُعيد إنشاء هذه النافذة وحدها بعد موت العملية فالإعدادات غير مهيّأة: نعود للمتصفح
        if (!MainActivity.initialized) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish(); return
        }
        val kind = intent.getStringExtra(KIND) ?: "settings"
        setContent {
            LaunchedEffect(Prefs.secureScreen) {
                val f = WindowManager.LayoutParams.FLAG_SECURE
                if (Prefs.secureScreen) window.setFlags(f, f) else window.clearFlags(f)
            }
            NovaTheme(this) {
                PanelHost(kind) { finish() }
                // «التحقق من تحديث الآن» من الإعدادات: النافذة كانت تُرسم في الشاشة الرئيسية فقط (خلف هذه النافذة)، فلا يراها المستخدم إلا بعد العودة للرئيسية
                if (Updater.prompt) UpdateDialog()
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(R.anim.nova_hold, R.anim.nova_panel_out)
    }
}

@Composable
private fun PanelHost(kind: String, close: () -> Unit) {
    var passwords by remember { mutableStateOf(kind == "passwords") }
    var library by remember { mutableStateOf(kind == "library") }
    // «المكتبة» تُفتح من داخل الإعدادات عبر Library.show
    val libShow = Library.show || library
    DisposableEffect(Unit) { onDispose { Library.show = false } }

    // الإعدادات هي الأساس إلا إن فُتحت التنزيلات/المكتبة/كلمات المرور مباشرة
    val base = if (kind == "downloads") "downloads" else "settings"
    BackHandler(enabled = passwords) { passwords = false; if (kind == "passwords") close() }
    BackHandler(enabled = libShow) { Library.show = false; library = false; if (kind == "library") close() }

    Box(Modifier.fillMaxSize().imePadding()) {
        if (base == "downloads") DownloadsScreen(onBack = close)
        else if (kind == "settings") SettingsScreen(
            onBack = close,
            onClearData = { PanelBus.clearData?.invoke() ?: clearDataGlobal() },
            onClearCache = { PanelBus.clearCache?.invoke() },
            onPasswords = { passwords = true },
            overlayOpen = passwords || libShow
        ) else if (kind == "passwords" || kind == "library") {
            // فتح مباشر لكلمات المرور/المكتبة: خلفية فارغة تحتها
            Box(Modifier.fillMaxSize())
        }
        AnimatedVisibility(visible = passwords, enter = NovaMotion.panelEnter, exit = NovaMotion.panelExit) {
            PasswordsScreen(onBack = { passwords = false; if (kind == "passwords") close() })
        }
        AnimatedVisibility(visible = libShow, enter = NovaMotion.panelEnter, exit = NovaMotion.panelExit) {
            LibraryScreen(
                onBack = { Library.show = false; library = false; if (kind == "library") close() },
                onOpen = { u -> PanelBus.openUrl?.invoke(u); close() }
            )
        }
    }
}

/** احتياطي إن لم يكن المتصفح مسجَّلاً في الجسر: يمسح ما لا يحتاج WebView محدداً. */
private fun clearDataGlobal() {
    android.webkit.CookieManager.getInstance().removeAllCookies(null)
    android.webkit.CookieManager.getInstance().flush()
    android.webkit.WebStorage.getInstance().deleteAllData()
    Library.clearHistory()
}
