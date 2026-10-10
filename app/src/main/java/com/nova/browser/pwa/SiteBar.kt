package com.nova.browser

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * الشريط العلوي لوضع التطبيق (تصميم مضغوط 48dp):
 * شارة متدرّجة برمز مرسوم، اسم الموقع مع قفل ونطاقه، وأزرار بلا إطارات ثقيلة.
 * فيديو يوتيوب: زر منبثق دائري + كبسولة تنزيل بلون الهوية. غيره: تحديث (يدور أثناء التحميل) ومشاركة.
 * خط التقدّم 2dp في أسفل الشريط. كل الألوان من الثيم فيتبع الفاتح والداكن.
 */
@Composable
fun SiteBar(
    info: SiteInfo, progress: Float, loading: Boolean,
    onReload: () -> Unit, onShare: () -> Unit,
    onPip: (() -> Unit)? = null, onDownload: (() -> Unit)? = null,
    onTitleTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    fun tap(a: () -> Unit): () -> Unit = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); a() }
    val wash = siteWash(cs)

    Column(modifier.fillMaxWidth().background(cs.background)) {
        Row(
            Modifier.fillMaxWidth().height(UiLayout.BAR_ROW_DP.dp).background(wash).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // شارة الهوية
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(cs.tertiary, lerp(cs.tertiary, cs.primaryContainer, 0.45f)))),
                contentAlignment = Alignment.Center
            ) {
                if (info.kind == SiteKind.AI) SparkGlyph(cs.onTertiary, Modifier.size(18.dp))
                else PlayGlyph(cs.onTertiary, cs.tertiary, Modifier.size(19.dp))
            }
            Column(
                Modifier.weight(1f).then(if (onTitleTap != null) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = tap(onTitleTap)) else Modifier),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(info.name, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Default.Lock, null, Modifier.size(10.dp), tint = cs.tertiary)
                    Text(info.host.removePrefix("www."), fontSize = 11.sp, lineHeight = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onPip != null) GlyphButton(L("منبثق"), tap(onPip)) { c -> PipGlyph(c, Modifier.size(19.dp)) }
            if (onDownload != null) DownloadPill(L("تنزيل"), tap(onDownload))
            if (onPip == null && onDownload == null) {
                val spin = if (loading && Adaptive.ms(100) > 0)
                    rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "rot").value
                else 0f
                GlyphButton(L("تحديث"), tap(onReload), tonal = false) { c -> Icon(Icons.Default.Refresh, null, Modifier.size(20.dp).graphicsLayer { rotationZ = spin }, tint = c) }
                GlyphButton(L("مشاركة"), tap(onShare), tonal = false) { c -> Icon(Icons.Default.Share, null, Modifier.size(19.dp), tint = c) }
            }
        }
        // خط التقدّم + الفاصل
        val p by animateFloatAsState(if (loading) progress.coerceIn(0.06f, 1f) else 0f, tween(Adaptive.ms(180)), label = "p")
        Box(Modifier.fillMaxWidth().height(UiLayout.PROGRESS_DP.dp)) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(cs.outlineVariant.copy(alpha = 0.7f)))
            if (p in 0.001f..0.999f)
                Box(Modifier.fillMaxWidth(p).height(2.dp).align(Alignment.BottomStart)
                    .background(Brush.horizontalGradient(listOf(cs.tertiary.copy(alpha = 0.4f), cs.tertiary))))
        }
    }
}

/** تدرّج هوية الشريط العلوي (يُستخدم أيضاً خلف شريط حالة النظام كي يتطابق اللونان). */
fun siteWash(cs: androidx.compose.material3.ColorScheme): Brush =
    Brush.horizontalGradient(listOf(cs.tertiary.copy(alpha = 0.12f), Color.Transparent))

/** امتداد خلفية الشريط خلف شريط حالة النظام: نفس الخلفية ونفس التدرّج بلا حدّ فاصل. */
@Composable
fun StatusBarWash(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(cs.background).background(siteWash(cs)))
}

@Composable
internal fun GlyphButton(desc: String, onClick: () -> Unit, tonal: Boolean = true, glyph: @Composable (Color) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick, interactionSource = src, shape = CircleShape,
        color = if (tonal) cs.surfaceContainerHigh else Color.Transparent,
        modifier = Modifier.size(36.dp).pressScale(src).semantics { contentDescription = desc }
    ) { Box(contentAlignment = Alignment.Center) { glyph(cs.onSurface) } }
}

@Composable
private fun DownloadPill(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick, interactionSource = src, shape = CircleShape, color = cs.tertiary, contentColor = cs.onTertiary,
        modifier = Modifier.height(36.dp).pressScale(src)
    ) {
        Row(Modifier.padding(start = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DownloadGlyph(cs.onTertiary, Modifier.size(18.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ───────────── رموز مرسومة (لا تحتاج مكتبة أيقونات إضافية) ─────────────

@Composable
internal fun SparkGlyph(color: Color, modifier: Modifier) = Canvas(modifier) {
    fun star(cx: Float, cy: Float, r: Float) = Path().apply {
        moveTo(cx, cy - r)
        quadraticBezierTo(cx, cy, cx + r, cy)
        quadraticBezierTo(cx, cy, cx, cy + r)
        quadraticBezierTo(cx, cy, cx - r, cy)
        quadraticBezierTo(cx, cy, cx, cy - r)
        close()
    }
    drawPath(star(size.width * 0.42f, size.height * 0.58f, size.minDimension * 0.42f), color)
    drawPath(star(size.width * 0.80f, size.height * 0.20f, size.minDimension * 0.20f), color)
}

@Composable
private fun PlayGlyph(color: Color, cut: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    drawRoundRect(color, Offset(0f, h * 0.16f), Size(w, h * 0.68f), CornerRadius(h * 0.2f))
    val t = Path().apply { moveTo(w * 0.41f, h * 0.34f); lineTo(w * 0.41f, h * 0.66f); lineTo(w * 0.68f, h * 0.5f); close() }
    drawPath(t, cut)
}

@Composable
private fun PipGlyph(color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    drawRoundRect(color, Offset(w * 0.08f, h * 0.17f), Size(w * 0.84f, h * 0.66f), CornerRadius(w * 0.14f),
        style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawRoundRect(color, Offset(w * 0.5f, h * 0.46f), Size(w * 0.32f, h * 0.25f), CornerRadius(w * 0.06f))
}

@Composable
private fun DownloadGlyph(color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height; val sw = w * 0.11f
    drawLine(color, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.5f, h * 0.62f), sw, StrokeCap.Round)
    val a = Path().apply { moveTo(w * 0.29f, h * 0.44f); lineTo(w * 0.5f, h * 0.65f); lineTo(w * 0.71f, h * 0.44f) }
    drawPath(a, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(color, Offset(w * 0.2f, h * 0.86f), Offset(w * 0.8f, h * 0.86f), sw, StrokeCap.Round)
}
