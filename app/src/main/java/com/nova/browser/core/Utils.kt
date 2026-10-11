package com.nova.browser

import android.app.AlertDialog
import android.content.*
import android.os.Build
import android.webkit.*
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import java.net.URLEncoder

fun normalize(input: String): String {
    val t = input.trim()
    return when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        t.contains(".") && !t.contains(" ") -> "https://$t"
        else -> Prefs.engines[Prefs.engine].second + URLEncoder.encode(t, "UTF-8")
    }
}

fun hostOf(u: String): String = runCatching { java.net.URI(u).host?.removePrefix("www.") }.getOrNull() ?: u

fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()

/**
 * نسخ إلى الحافظة. sensitive=true (كلمات المرور): يُعلَّم المحتوى حساساً فلا يظهر في معاينة الحافظة ولا في سجل لوحة المفاتيح،
 * ويُمسح تلقائياً بعد 30 ثانية إن بقي كما هو (كان يبقى نصاً صريحاً تقرؤه أي لوحة مفاتيح/تطبيق مراقب للحافظة).
 */
fun copyText(c: Context, t: String, sensitive: Boolean = false) {
    val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("url", t)
    if (sensitive && android.os.Build.VERSION.SDK_INT >= 24) runCatching {   // ClipDescription.setExtras من أندرويد 7
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    cm.setPrimaryClip(clip)
    toast(c, L("تم النسخ"))
    if (sensitive) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        runCatching {
            val cur = cm.primaryClip?.getItemAt(0)?.text?.toString()
            if (cur == null || cur == t) cm.clearPrimaryClip()
        }
    }, 30_000)
}

fun shareText(c: Context, t: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t) }
    c.startActivity(Intent.createChooser(i, null))
}

/** منشئ حوار أصلي بهوية Nova (حواف مدوّرة وألوان التطبيق) بدل شكل النظام الافتراضي. */
fun novaDialog(c: Context): AlertDialog.Builder {
    val dark = when (Prefs.theme) {
        1 -> false
        2 -> true
        else -> (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    return AlertDialog.Builder(android.view.ContextThemeWrapper(c, if (dark) R.style.Theme_Nova_Dialog_Dark else R.style.Theme_Nova_Dialog_Light))
}

fun choose(c: Context, items: List<Pair<String, () -> Unit>>) {
    novaDialog(c).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
}

fun errorHtml(url: String, desc: String): String {
    // كل ما يأتي من الشبكة يُشفَّر قبل وضعه في الصفحة. الرابط يُحفظ في سمة data (مشفّرة) ويُقرأ بالسكربت،
    // بدل حقنه داخل onclick كما كان — فالرابط الخبيث لم يعد يستطيع كسر السمة وتنفيذ سكربت بأصل الموقع.
    val u = android.text.TextUtils.htmlEncode(url)
    val d = android.text.TextUtils.htmlEncode(desc)
    val dir = if (I18n.isEnglish()) "ltr" else "rtl"
    return """
<html data-nova-err><head><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<style>
:root{color-scheme:light dark}body{font-family:sans-serif;display:flex;flex-direction:column;align-items:center;
justify-content:center;height:100vh;margin:0;padding:24px;text-align:center;direction:$dir}
h2{margin:8px}p{opacity:.65;word-break:break-all;margin:4px}
button{margin-top:22px;padding:12px 30px;border:0;border-radius:24px;background:#3D5AFE;color:#fff;font-size:16px}
</style></head><body data-u="$u"><div style="font-size:56px">📡</div><h2>${L("تعذّر فتح الصفحة")}</h2>
<p>$d</p><p>$u</p>
<button id="r">${L("إعادة المحاولة")}</button>
<script>document.getElementById('r').onclick=function(){location.replace(document.body.getAttribute('data-u'))}</script></body></html>"""
}
