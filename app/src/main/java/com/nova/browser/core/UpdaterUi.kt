package com.nova.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** نص حالة التحديث لصف الإعدادات. */
@Composable
fun updateStatus(): String = when (Updater.phase) {
    UpdPhase.CHECKING -> L("جارٍ الفحص…")
    UpdPhase.UP_TO_DATE -> L("أنت على أحدث إصدار")
    UpdPhase.AVAILABLE -> L("إصدار جديد") + " " + (Updater.info?.version ?: "") + " — " + L("اضغط للتنزيل")
    UpdPhase.DOWNLOADING -> L("جارٍ التنزيل…") + " " + (Updater.progress * 100).toInt() + "%"
    UpdPhase.READY -> L("جاهز — اضغط للتثبيت") + (if (Updater.error.isNotBlank()) "\n" + Updater.error else "")
    UpdPhase.INSTALLING -> L("جارٍ التثبيت…")
    UpdPhase.ERROR -> L("فشل") + ": " + Updater.error
    UpdPhase.IDLE -> L("اضغط للفحص الآن")
}

private val RE_COMMENT = Regex("<!--[\\s\\S]*?-->")
private val RE_LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val RE_BY = Regex("\\s+by\\s+@\\S+(\\s+in\\s+\\S+)?", RegexOption.IGNORE_CASE)
private val RE_URL = Regex("https?://\\S+")

/**
 * ملاحظات الإصدار من GitHub تأتي بصيغة Markdown خام (تعليقات HTML، عناوين، روابط، «by @user in …»).
 * نحوّلها إلى أسطر نظيفة قصيرة تصلح نقاطاً في النافذة، ونحذف سطر «Full Changelog» والعناوين التلقائية.
 */
fun releaseHighlights(raw: String, max: Int = 8): List<String> {
    val out = ArrayList<String>()
    for (line in RE_COMMENT.replace(raw, "").lines()) {
        var l = line.trim()
        if (l.isEmpty()) continue
        if (l.contains("Full Changelog", true) || l.contains("What's Changed", true) ||
            l.contains("New Contributors", true) || l.contains("first contribution", true)) continue
        l = l.trimStart('#', '>', '*', '-', '+', '•', ' ')
        l = RE_LINK.replace(l, "\$1")
        l = RE_BY.replace(l, "")
        l = RE_URL.replace(l, "")
        l = l.replace("**", "").replace("__", "").replace("`", "").trim()
        if (l.length < 2) continue
        out += if (l.length > 140) l.take(137) + "…" else l
        if (out.size >= max) break
    }
    return out
}

/** نافذة «يوجد تحديث» بهوية التطبيق: شارة متدرّجة، شرائح الإصدار والحجم، نقاط ما الجديد، وشريط تقدّم متدرّج. */
@Composable
fun UpdateDialog() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val i = Updater.info ?: return
    val busy = Updater.phase == UpdPhase.DOWNLOADING || Updater.phase == UpdPhase.INSTALLING
    val notes = remember(i.notes) { releaseHighlights(i.notes) }
    AlertDialog(
        onDismissRequest = { if (!busy) Updater.dismissPrompt() },
        shape = RoundedCornerShape(28.dp),
        containerColor = cs.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(cs.tertiary, lerp(cs.tertiary, cs.primaryContainer, 0.45f)))),
                    contentAlignment = Alignment.Center
                ) { UpdateGlyph(cs.onTertiary, Modifier.size(26.dp)) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(L("تحديث جديد"), fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("v" + i.version.removePrefix("v"), strong = true)
                        if (i.size > 0) Chip(fmtSize(i.size), strong = false)
                    }
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L("ما الجديد"), style = MaterialTheme.typography.labelLarge, color = cs.tertiary)
                (notes.ifEmpty { listOf(L("تحسينات وإصلاحات عامة")) }).forEach { n ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(cs.tertiary))
                        Text(n, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
                    }
                }
                if (Updater.phase == UpdPhase.DOWNLOADING) {
                    val p by animateFloatAsState(Updater.progress.coerceIn(0f, 1f), tween(Adaptive.ms(180)), label = "upd")
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(cs.outlineVariant.copy(alpha = 0.6f))) {
                            Box(
                                Modifier.fillMaxWidth(p.coerceAtLeast(0.02f)).fillMaxHeight().clip(CircleShape)
                                    .background(Brush.horizontalGradient(listOf(cs.tertiary.copy(alpha = 0.55f), cs.tertiary)))
                            )
                        }
                        Text((p * 100).toInt().toString() + "%", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
                if (Updater.error.isNotBlank()) Text(Updater.error, style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy, shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = cs.tertiary, contentColor = cs.onTertiary),
                onClick = { when (Updater.phase) { UpdPhase.READY -> Updater.install(ctx); else -> Updater.download(ctx) } }
            ) {
                Text(when (Updater.phase) {
                    UpdPhase.READY -> L("تثبيت")
                    UpdPhase.DOWNLOADING -> L("جارٍ التنزيل…")
                    UpdPhase.INSTALLING -> L("جارٍ التثبيت…")
                    else -> L("تنزيل وتثبيت")
                }, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { Updater.dismissPrompt() }) { Text(L("لاحقاً"), color = cs.onSurfaceVariant) }
        }
    )
}

@Composable
private fun Chip(text: String, strong: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = if (strong) cs.tertiary.copy(alpha = 0.16f) else cs.surfaceContainerHighest) {
        Text(
            text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (strong) cs.tertiary else cs.onSurfaceVariant
        )
    }
}

@Composable
private fun UpdateGlyph(color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height; val sw = w * 0.11f
    drawLine(color, Offset(w * 0.5f, h * 0.12f), Offset(w * 0.5f, h * 0.62f), sw, StrokeCap.Round)
    val a = Path().apply { moveTo(w * 0.28f, h * 0.42f); lineTo(w * 0.5f, h * 0.65f); lineTo(w * 0.72f, h * 0.42f) }
    drawPath(a, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(color, Offset(w * 0.2f, h * 0.88f), Offset(w * 0.8f, h * 0.88f), sw, StrokeCap.Round)
}
