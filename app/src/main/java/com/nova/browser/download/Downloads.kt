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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun DownloadCard(t: DlTask, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val running = t.status == Downloader.DOWNLOADING || t.status == Downloader.PREPARING
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(t.name.substringAfterLast('.', "?").take(4).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(statusLine(t), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, L("المزيد")) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                        if (t.status == Downloader.DONE) {
                            DropdownMenuItem(text = { Text(L("إزالة من القائمة")) }, onClick = { menu = false; Downloader.remove(t, false) })
                            DropdownMenuItem(text = { Text(L("حذف الملف")) }, onClick = { menu = false; Downloader.remove(t, true) })
                        } else DropdownMenuItem(text = { Text(L("إلغاء وحذف")) }, onClick = { menu = false; Downloader.cancel(t) })
                    }
                }
            }
            if (t.status != Downloader.DONE) { Spacer(Modifier.height(12.dp)); SegmentBar(t) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                when {
                    t.status == Downloader.DONE -> {
                        TextButton(onClick = { shareFile(ctx, t) }) { Text(L("مشاركة")) }
                        FilledTonalButton(onClick = { openFile(ctx, t) }) { Text(L("فتح")) }
                    }
                    running -> {
                        TextButton(onClick = { Downloader.cancel(t) }) { Text(L("إلغاء")) }
                        FilledTonalButton(onClick = { Downloader.pause(t) }) { Text(L("إيقاف مؤقت")) }
                    }
                    else -> {
                        TextButton(onClick = { Downloader.cancel(t) }) { Text(L("إلغاء")) }
                        FilledTonalButton(onClick = { Downloader.resume(t) }) { Text(L("استئناف")) }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("رجوع")) }
                Text(L("التنزيلات"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (Downloader.tasks.any { it.status == Downloader.DOWNLOADING }) TextButton(onClick = { Downloader.pauseAll() }) { Text(L("إيقاف الكل")) }
            }
            if (Downloader.tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(L("لا توجد تنزيلات"), color = cs.onSurfaceVariant) }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(Downloader.tasks, key = { it.id }) { DownloadCard(it, Modifier.animateItem()) }
                }
            }
        }
    }
}
