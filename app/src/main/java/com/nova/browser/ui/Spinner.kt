package com.nova.browser

import android.animation.ValueAnimator
import android.graphics.Canvas as GCanvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * مؤشر التحميل الموحّد للتطبيق (نمط iPhone): 12 شريطاً مستديراً يدور بخطوات منفصلة ويتلاشى ذيله.
 * المصدر الوحيد لكل مؤشرات الانتظار — لا تستخدم CircularProgressIndicator في أي مكان آخر.
 * ثلاث صيغ: مكوّن Compose (NovaSpinner)، وDrawable للـ View (IosSpinnerDrawable)، ومؤشر السحب للتحديث (NovaPullIndicator).
 */

private const val SPOKES = 12

/** شفافية الشريط: 0 = الرأس (الأغمق) … 11 = آخر الذيل (الأفتح). المنحنى يطابق شكل المؤشر الأصلي (معظم الأشرطة داكنة وطرف الذيل فاتح). */
private fun spokeAlpha(d: Int): Float = 1f - 0.88f * (d / (SPOKES - 1f)).pow(1.5f)

/** مؤشر تحميل بنمط iPhone. اللون الافتراضي يتبع لون المحتوى الحالي فيناسب الفاتح والداكن. */
@Composable
fun NovaSpinner(modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = LocalContentColor.current) {
    val t = rememberInfiniteTransition(label = "nova-spinner")
    // قيمة صحيحة تتغيّر 12 مرة في الثانية فقط: لا إعادة رسم بلا داعٍ
    val step by t.animateValue(0, SPOKES, Int.VectorConverter, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "step")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val sw = r * 0.22f
        val inner = r * 0.5f + sw / 2f
        val outer = r - sw / 2f
        val c = center
        val head = step % SPOKES
        for (i in 0 until SPOKES) {
            val d = ((head - i) % SPOKES + SPOKES) % SPOKES
            val ang = Math.toRadians(i * 30.0 - 90.0)
            val cx = cos(ang).toFloat(); val sy = sin(ang).toFloat()
            drawLine(
                color = color.copy(alpha = color.alpha * spokeAlpha(d)),
                start = Offset(c.x + cx * inner, c.y + sy * inner),
                end = Offset(c.x + cx * outer, c.y + sy * outer),
                strokeWidth = sw, cap = StrokeCap.Round
            )
        }
    }
}

/** صف انتظار: المؤشر ونص اختياري بجانبه (للقوائم والنوافذ). */
@Composable
fun NovaLoadingRow(text: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        NovaSpinner(size = 22.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (text != null) {
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** مؤشر السحب للتحديث في القوائم (يحل محل مؤشر Material الافتراضي). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaPullIndicator(state: PullToRefreshState, isRefreshing: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .graphicsLayer {
                val p = if (isRefreshing) 1f else state.distanceFraction.coerceIn(0f, 1.2f)
                translationY = p * 64.dp.toPx() - 36.dp.toPx()
                alpha = p.coerceIn(0f, 1f)
                val s = 0.6f + 0.4f * min(p, 1f)
                scaleX = s; scaleY = s
            }
            .shadow(4.dp, CircleShape)
            .background(cs.surfaceContainerHigh, CircleShape)
            .size(40.dp),
        contentAlignment = Alignment.Center
    ) { NovaSpinner(size = 20.dp, color = cs.onSurface) }
}

/** نسخة Drawable للـ Views (مؤشر SwipeRefreshLayout). تدور فقط ما دامت ظاهرة، فلا تستهلك شيئاً وهي مخفية. */
class IosSpinnerDrawable(private val color: Int) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var step = 0
    private val anim = ValueAnimator.ofInt(0, SPOKES).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            val s = (it.animatedValue as Int) % SPOKES
            if (s != step) { step = s; invalidateSelf() }
        }
    }

    override fun draw(canvas: GCanvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = min(b.width(), b.height()) / 2f * 0.5f
        val sw = r * 0.22f
        val inner = r * 0.5f + sw / 2f
        val outer = r - sw / 2f
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        paint.strokeWidth = sw
        for (i in 0 until SPOKES) {
            val d = ((step - i) % SPOKES + SPOKES) % SPOKES
            val a = (spokeAlpha(d) * 255f).toInt().coerceIn(0, 255)
            paint.color = (color and 0x00FFFFFF) or (a shl 24)
            val ang = Math.toRadians(i * 30.0 - 90.0)
            val ux = cos(ang).toFloat(); val uy = sin(ang).toFloat()
            canvas.drawLine(cx + ux * inner, cy + uy * inner, cx + ux * outer, cy + uy * outer, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun start() { if (!anim.isStarted) anim.start() }
    override fun stop() { if (anim.isStarted) anim.cancel() }
    override fun isRunning(): Boolean = anim.isRunning

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }
}

/**
 * يستبدل دائرة التحميل الافتراضية في SwipeRefreshLayout بمؤشر iPhone.
 * الدائرة هي أول ImageView يضيفه SwipeRefreshLayout لنفسه (قبل صفحة الويب)؛ إن تغيّر ذلك في إصدار لاحق يبقى المؤشر الافتراضي بلا أخطاء.
 */
fun SwipeRefreshLayout.useIosSpinner(color: Int) {
    try {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v is ImageView) {
                v.setImageDrawable(IosSpinnerDrawable(color))
                return
            }
        }
    } catch (_: Throwable) { }
}
