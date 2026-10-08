package com.nova.browser

import android.app.Activity
import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class PendingSave(val host: String, val user: String, val pass: String, val kind: SaveKind)
class FillOffer(val tabId: Int, val host: String, val creds: List<Cred>)

/** جسر بين سكربت pw.js داخل الصفحة والتطبيق: اكتشاف نماذج الدخول والتقاط بيانات الدخول والتعبئة. */
object PasswordBridge {
    private val docStart by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
    private val listenerOk by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
    private var cached: String? = null

    private fun script(c: Context): String? {
        if (cached == null) cached = runCatching { c.assets.open("pw.js").bufferedReader().use { it.readText() } }.getOrNull()
        return cached
    }

    fun install(wv: WebView, tab: BrowserTab, h: Handlers) {
        if (!listenerOk) return
        val js = script(wv.context) ?: return
        runCatching {
            WebViewCompat.addWebMessageListener(wv, "NovaPw", setOf("*"), WebViewCompat.WebMessageListener { view, message, origin, isMain, _ ->
                if (!isMain || Prefs.pwMode != 0) return@WebMessageListener
                val host = origin.host ?: return@WebMessageListener
                val secure = origin.scheme == "https" || host == "localhost"
                if (!secure) return@WebMessageListener
                // الرسالة يجب أن تأتي من نفس النطاق المعروض فعلاً
                if (Vault.norm(host) != Vault.norm(hostOf(view.url ?: ""))) return@WebMessageListener
                val data = message.data ?: return@WebMessageListener
                if (data.length > 4096) return@WebMessageListener
                val o = runCatching { JSONObject(data) }.getOrNull() ?: return@WebMessageListener
                when (o.optString("t")) {
                    "form" -> h.onLoginForm(tab, view, Vault.norm(host))
                    "save" -> h.onCredential(Vault.norm(host), o.optString("u"), o.optString("p"))
                }
            })
            if (docStart) WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*"))
        }
    }

    /** للأجهزة التي لا تدعم الحقن المبكر. */
    fun onPageDone(wv: WebView) {
        if (listenerOk && !docStart && Prefs.pwMode == 0) script(wv.context)?.let { wv.evaluateJavascript(it, null) }
    }

    fun fill(wv: WebView, c: Cred) {
        if (Vault.norm(hostOf(wv.url ?: "")) != c.host) return   // لا نعبّئ إلا في نفس الموقع
        wv.evaluateJavascript("window.__novaFill&&window.__novaFill(${JSONObject.quote(c.user)},${JSONObject.quote(c.pass)})", null)
    }
}

@Composable
fun SavePasswordDialog(p: PendingSave, onSave: () -> Unit, onNever: () -> Unit, onDismiss: () -> Unit) {
    val upd = p.kind == SaveKind.UPDATE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (upd) L("تحديث كلمة المرور؟") else L("حفظ كلمة المرور؟")) },
        text = {
            Column {
                Text(p.host, fontWeight = FontWeight.Bold)
                if (p.user.isNotBlank()) Text(p.user)
                Spacer(Modifier.height(8.dp))
                Text(L("تُخزَّن مشفّرة على جهازك فقط."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(if (upd) L("تحديث") else L("حفظ")) } },
        dismissButton = {
            Row {
                TextButton(onClick = onNever) { Text(L("عدم الحفظ لهذا الموقع")) }
                TextButton(onClick = onDismiss) { Text(L("ليس الآن")) }
            }
        }
    )
}

@Composable
fun FillBanner(o: FillOffer, onFill: (Cred) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier.padding(horizontal = 12.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = cs.surfaceContainerHigh, tonalElevation = 6.dp, shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(L("تعبئة تلقائية"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, L("إغلاق"), Modifier.size(18.dp)) }
            }
            o.creds.take(3).forEach { c ->
                TextButton(onClick = { onFill(c) }, modifier = Modifier.fillMaxWidth()) {
                    Text(c.user.ifBlank { c.host }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun PasswordsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val activity = LocalContext.current as Activity
    var query by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf<Cred?>(null) }
    var edit by remember { mutableStateOf<Cred?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf<Cred?>(null) }
    val q = query.trim()
    val list = Vault.items.filter { q.isBlank() || it.host.contains(q, true) || it.user.contains(q, true) }.sortedBy { it.host }

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("رجوع")) }
                Spacer(Modifier.width(8.dp))
                Text(L("كلمات المرور"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                RoundBtn(onClick = { edit = Cred("", "", "", "", 0L) }) { Icon(Icons.Default.Add, L("إضافة")) }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(L("بحث")) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(L("لا توجد كلمات مرور محفوظة"), color = cs.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                        ListRow(groupShape(i, list.size), c.host, c.user.ifBlank { "—" }, { sel = c; revealed = false }) {
                            IconCircle { Icon(Icons.Default.Lock, null) }
                        }
                    }
                }
            }
        }
    }

    sel?.let { c ->
        AlertDialog(
            onDismissRequest = { sel = null }, title = { Text(c.host) },
            text = {
                Column {
                    Text(L("اسم المستخدم"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Text(c.user.ifBlank { "—" })
                    Spacer(Modifier.height(12.dp))
                    Text(L("كلمة المرور"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Text(if (revealed) c.pass else "••••••••••")
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = {
                            if (revealed) revealed = false else Auth.run(activity, L("تأكيد الهوية")) { revealed = true }
                        }) { Text(if (revealed) L("إخفاء") else L("إظهار")) }
                        TextButton(onClick = { Auth.run(activity, L("تأكيد الهوية")) { copyText(activity, c.pass) } }) { Text(L("نسخ كلمة المرور")) }
                    }
                    Row {
                        TextButton(onClick = { copyText(activity, c.user) }) { Text(L("نسخ المستخدم")) }
                        TextButton(onClick = { Auth.run(activity, L("تأكيد الهوية")) { edit = c; sel = null } }) { Text(L("تعديل")) }
                        TextButton(onClick = { confirmDel = c }) { Text(L("حذف")) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sel = null }) { Text(L("إغلاق")) } }
        )
    }

    confirmDel?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDel = null }, title = { Text(L("حذف كلمة المرور؟")) },
            text = { Text(c.host + (if (c.user.isNotBlank()) " — " + c.user else "")) },
            confirmButton = { TextButton(onClick = { Vault.delete(c.id); confirmDel = null; sel = null }) { Text(L("حذف")) } },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text(L("إلغاء")) } }
        )
    }

    edit?.let { e ->
        var host by remember(e) { mutableStateOf(e.host) }
        var user by remember(e) { mutableStateOf(e.user) }
        var pass by remember(e) { mutableStateOf(e.pass) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(if (e.id.isEmpty()) L("إضافة كلمة مرور") else L("تعديل")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it }, singleLine = true, label = { Text(L("الموقع (مثال: example.com)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(L("اسم المستخدم")) })
                    OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text(L("كلمة المرور")) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = Vault.cleanHost(host).isNotBlank() && pass.isNotEmpty(),
                    onClick = { Vault.upsert(e.id, Vault.cleanHost(host), user.trim(), pass); edit = null }
                ) { Text(L("حفظ")) }
            },
            dismissButton = { TextButton(onClick = { edit = null }) { Text(L("إلغاء")) } }
        )
    }
}
