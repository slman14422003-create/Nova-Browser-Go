package com.nova.browser

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

fun fmtSize(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = -1
    while (v >= 1024 && i < 3) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, u[i])
}

fun fmtSpeed(b: Long) = fmtSize(b) + "/s"

fun fmtEta(s: Long): String {
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
}

fun openFile(c: Context, t: DlTask) {
    runCatching {
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(t.uri?.let { Storage.shareUri(c, it) }, t.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { toast(c, L("لا يوجد تطبيق لفتح الملف")) }
}

fun shareFile(c: Context, t: DlTask) {
    val i = Intent(Intent.ACTION_SEND).setType(t.mime.ifBlank { "*/*" }).putExtra(Intent.EXTRA_STREAM, t.uri?.let { Storage.shareUri(c, it) })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    c.startActivity(Intent.createChooser(i, null))
}

private fun statusLine(t: DlTask): String {
    val tot = if (t.total > 0) fmtSize(t.total) else L("؟")
    return when (t.status) {
        Downloader.PREPARING -> L("جارٍ التحضير…")
        Downloader.DOWNLOADING -> {
            val pct = if (t.total > 0) " • ${(t.downloaded * 100 / t.total)}%" else ""
            val eta = if (t.speed > 0 && t.total > 0) " • ${fmtEta((t.total - t.downloaded) / t.speed)}" else ""
            ("" + (fmtSize(t.downloaded)) + " / " + tot + pct + " • " + (fmtSpeed(t.speed)) + eta + " • " + (t.conns) + L(" اتصال"))
        }
        Downloader.PAUSED -> (L("متوقف • ") + (fmtSize(t.downloaded)) + " / " + tot)
        Downloader.FAILED -> (L("فشل: ") + (t.error))
        else -> ("" + tot + L(" • اكتمل"))
    }
}

@Composable
fun SegmentBar(t: DlTask) {
    val cs = MaterialTheme.colorScheme
    val track = cs.surfaceContainerHighest; val fill = cs.tertiary; val gap = cs.surfaceContainerHigh
    Canvas(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        drawRect(track)
        val tot = t.total
        if (tot > 0 && t.segSnap.isNotEmpty()) {
            for (s in t.segSnap) {
                val x0 = s[0].toFloat() / tot * size.width
                val x1 = s[2].toFloat() / tot * size.width
                if (x1 > x0) drawRect(fill, Offset(x0, 0f), Size(x1 - x0, size.height))
            }
            for (s in t.segSnap) drawRect(gap, Offset(s[0].toFloat() / tot * size.width, 0f), Size(1.5f, size.height))
        } else if (tot > 0) {
            drawRect(fill, size = Size(t.downloaded.toFloat() / tot * size.width, size.height))
        }
    }
}

private fun kindOf(name: String): Int = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts" -> 1
    "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus" -> 2
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic" -> 3
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv" -> 4
    "zip", "rar", "7z", "tar", "gz" -> 5
    "apk", "apks", "xapk" -> 6
    else -> 0
}

private fun kindLabel(k: Int) = when (k) {
    1 -> L("فيديو"); 2 -> L("صوت"); 3 -> L("صورة"); 4 -> L("مستند"); 5 -> L("أرشيف"); 6 -> L("تطبيق"); else -> L("ملف")
}

@Composable
private fun kindColors(k: Int): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (k) {
        1 -> cs.primaryContainer to cs.onPrimaryContainer
        2, 5 -> cs.secondaryContainer to cs.onSecondaryContainer
        3 -> cs.tertiaryContainer to cs.onTertiaryContainer
        6 -> cs.errorContainer to cs.onErrorContainer
        else -> cs.surfaceContainerHighest to cs.onSurface
    }
}

@Composable
fun DownloadCard(t: DlTask, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val running = t.status == Downloader.DOWNLOADING || t.status == Downloader.PREPARING
    val failed = t.status == Downloader.FAILED
    val done = t.status == Downloader.DONE
    val kind = kindOf(t.name)
    val (badgeBg, badgeFg) = kindColors(kind)
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(badgeBg), contentAlignment = Alignment.Center) {
                    Text(t.name.substringAfterLast('.', "?").take(4).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = badgeFg)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name.ifBlank { hostOf(t.origUrl) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(statusLine(t), style = MaterialTheme.typography.bodySmall, color = if (failed) cs.error else cs.onSurfaceVariant)
                    if (done) Text(
                        kindLabel(kind) + " • " + hostOf(t.origUrl) + " • " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(t.createdAt)),
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, L("المزيد")) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                        DropdownMenuItem(text = { Text(L("نسخ الرابط")) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; copyText(ctx, t.origUrl) })
                        if (done) {
                            DropdownMenuItem(text = { Text(L("إزالة من القائمة")) }, leadingIcon = { Icon(Icons.Default.Close, null) }, onClick = { menu = false; Downloader.remove(t, false) })
                            DropdownMenuItem(text = { Text(L("حذف الملف")) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                        } else DropdownMenuItem(text = { Text(L("إلغاء وحذف")) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; Downloader.cancel(t) })
                    }
                }
            }
            if (!done) { Spacer(Modifier.height(12.dp)); SegmentBar(t) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                when {
                    done -> {
                        TextButton(onClick = { shareFile(ctx, t) }) { Text(L("مشاركة")) }
                        FilledTonalButton(onClick = { openFile(ctx, t) }) { Text(L("فتح")) }
                    }
                    running -> {
                        TextButton(onClick = { Downloader.cancel(t) }) { Text(L("إلغاء")) }
                        FilledTonalButton(onClick = { Downloader.pause(t) }) { Text(L("إيقاف مؤقت")) }
                    }
                    else -> {
                        TextButton(onClick = { Downloader.cancel(t) }) { Text(L("إلغاء")) }
                        FilledTonalButton(onClick = { Downloader.resume(t) }) { Text(if (failed) L("إعادة المحاولة") else L("استئناف")) }
                    }
                }
            }
        }
    }
    if (confirmDelete) NovaDialog(
        title = L("حذف الملف؟"), icon = Icons.Default.Delete, danger = true, onDismiss = { confirmDelete = false },
        confirmText = L("حذف"), onConfirm = { confirmDelete = false; Downloader.remove(t, true) }, dismissText = L("إلغاء")
    ) { DialogText(L("سيُحذف الملف من جهازك نهائياً.")) }
}

@Composable
private fun ActiveSummary(running: List<DlTask>) {
    val cs = MaterialTheme.colorScheme
    val speed = running.sumOf { it.speed }
    val known = running.filter { it.total > 0 }
    val totalB = known.sumOf { it.total }
    val progress = if (totalB > 0) (known.sumOf { it.downloaded }.toFloat() / totalB).coerceIn(0f, 1f) else 0f
    Surface(shape = RoundedCornerShape(28.dp), color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L("جارٍ تنزيل ") + running.size + L(" ملف"), style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    Text(fmtSpeed(speed) + if (totalB > 0) " • ${(progress * 100).toInt()}%" else "", style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                }
                TextButton(onClick = { Downloader.pauseAll() }) { Text(L("إيقاف الكل"), color = cs.onPrimaryContainer) }
            }
            if (totalB > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = cs.primary, trackColor = cs.onPrimaryContainer.copy(alpha = 0.15f)
                )
            }
        }
    }
}

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var filter by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val all = Downloader.tasks.toList()
    val isRun: (DlTask) -> Boolean = { it.status == Downloader.DOWNLOADING || it.status == Downloader.PREPARING }
    val isStop: (DlTask) -> Boolean = { it.status == Downloader.PAUSED || it.status == Downloader.FAILED }
    val isDone: (DlTask) -> Boolean = { it.status == Downloader.DONE }
    val running = all.filter(isRun)
    val nRun = running.size; val nStop = all.count(isStop); val nDone = all.count(isDone)
    val q = query.trim()
    val filtered = all.filter {
        (when (filter) { 1 -> isRun(it); 2 -> isDone(it); 3 -> isStop(it); else -> true }) &&
            (q.isBlank() || it.name.contains(q, true) || it.origUrl.contains(q, true))
    }
    val list = when (sort) {
        1 -> filtered.sortedBy { it.name.lowercase() }
        2 -> filtered.sortedByDescending { it.total }
        else -> filtered.sortedByDescending { it.createdAt }
    }
    val doneBytes = all.filter(isDone).sumOf { if (it.total > 0) it.total else it.downloaded }
    val subtitle = if (all.isEmpty()) null else all.size.toString() + " " + L("ملف") + " • " + fmtSize(doneBytes)

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            PanelTopBar(L("التنزيلات"), subtitle, onBack) {
                if (all.isNotEmpty()) PanelMenu { close ->
                    if (nRun > 0) DropdownMenuItem(text = { Text(L("إيقاف الكل")) }, leadingIcon = { Icon(Icons.Default.Close, null) }, onClick = { close(); Downloader.pauseAll() })
                    if (nStop > 0) DropdownMenuItem(text = { Text(L("استئناف الكل")) }, leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                        onClick = { close(); all.filter(isStop).forEach { Downloader.resume(it) } })
                    if (nDone > 0) DropdownMenuItem(text = { Text(L("مسح المكتملة من القائمة")) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { close(); confirmClear = true })
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(L("الترتيب"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    listOf(L("الأحدث أولاً"), L("الاسم"), L("الحجم")).forEachIndexed { i, n ->
                        DropdownMenuItem(text = { Text(n) }, trailingIcon = { if (sort == i) Icon(Icons.Default.Check, null) }, onClick = { close(); sort = i })
                    }
                }
            }
            if (all.isEmpty()) {
                PanelEmpty(Icons.Default.KeyboardArrowDown, L("لا توجد تنزيلات"), L("الملفات التي تنزّلها من المواقع تظهر هنا"))
            } else {
                PanelSearch(query, { query = it }, L("بحث في التنزيلات"))
                PanelChips(
                    listOf(L("الكل") + " (${all.size})", L("جارٍ") + " ($nRun)", L("مكتمل") + " ($nDone)", L("متوقف") + " ($nStop)"),
                    filter, { filter = it }
                )
                if (list.isEmpty()) PanelEmpty(Icons.Default.Search, L("لا نتائج"), modifier = Modifier.weight(1f).fillMaxWidth())
                else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (nRun > 0 && filter != 2 && q.isBlank()) item(key = "summary") { ActiveSummary(running) }
                    items(list, key = { it.id }) { DownloadCard(it, Modifier.animateItem()) }
                }
            }
        }
    }
    if (confirmClear) NovaDialog(
        title = L("مسح المكتملة من القائمة؟"), icon = Icons.Default.Delete, danger = true, onDismiss = { confirmClear = false },
        confirmText = L("مسح"), onConfirm = { confirmClear = false; all.filter(isDone).forEach { Downloader.remove(it, false) } }, dismissText = L("إلغاء")
    ) { DialogText(L("تُزال من القائمة فقط، وتبقى الملفات على جهازك.")) }
}
