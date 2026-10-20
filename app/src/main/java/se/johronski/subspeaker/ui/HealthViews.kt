package se.johronski.subspeaker.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.audio.SweepAnalyzer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** En numrerad markering i baskurvan. */
class LandscapeMark(val freq: Double, val number: Int, val actionable: Boolean)

/**
 * Förenklad baskurva utan dB-axel: zonerna Djupbas, Övergång och Övre bas,
 * numrerade problemställen och eventuellt en streckad "före"-kurva.
 */
@Composable
fun BassLandscape(
    db: DoubleArray,
    modifier: Modifier = Modifier,
    before: DoubleArray? = null,
    marks: List<LandscapeMark> = emptyList(),
    height: Dp = 170.dp,
) {
    val freqs = SweepAnalyzer.FREQUENCIES
    Canvas(modifier.fillMaxWidth().height(height)) {
        val bottomLabels = 22.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - bottomLabels
        val fMin = 20.0
        val fMax = 300.0
        fun x(f: Double) = (ln(f / fMin) / ln(fMax / fMin) * size.width).toFloat()
        val ref = freqs.indices.filter { freqs[it] in 80.0..125.0 }.map { db[it] }.average()
        val range = 15.0
        fun y(v: Double) = (top + (bottom - top) * (0.5 - (v - ref) / (2 * range)).coerceIn(0.0, 1.0)).toFloat()

        drawRect(Paper, Offset(0f, 0f), Size(x(60.0), bottom))
        drawRect(AccentSoft.copy(alpha = 0.55f), Offset(x(60.0), 0f), Size(x(140.0) - x(60.0), bottom))
        drawRect(Paper, Offset(x(140.0), 0f), Size(size.width - x(140.0), bottom))
        drawLine(
            Line, Offset(0f, y(ref)), Offset(size.width, y(ref)), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )

        fun path(c: DoubleArray): Path {
            val p = Path()
            for (i in freqs.indices) {
                val px = x(freqs[i])
                val py = y(c[i])
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            return p
        }
        before?.let {
            drawPath(
                path(it), Color(0xFF9AA3A5),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
            )
        }
        drawPath(path(db), Accent, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val text = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = 12.sp.toPx()
            color = InkMuted.toArgb()
        }
        val canvas = drawContext.canvas.nativeCanvas
        canvas.drawText("Djupbas", x(34.0), size.height - 6.dp.toPx(), text)
        canvas.drawText("Övergång", x(92.0), size.height - 6.dp.toPx(), text)
        canvas.drawText("Övre bas", x(205.0), size.height - 6.dp.toPx(), text)

        val num = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = 13.sp.toPx()
            typeface = Typeface.DEFAULT_BOLD
        }
        marks.filter { it.freq in fMin..fMax }.forEach { m ->
            val i = freqs.indices.minByOrNull { kotlin.math.abs(freqs[it] - m.freq) } ?: return@forEach
            val cx = x(m.freq)
            val cy = (y(db[i]) + 14.dp.toPx()).coerceAtMost(bottom - 12.dp.toPx())
            val color = if (m.actionable) SeriesColors[1] else InkMuted
            drawCircle(CardColor, 12.dp.toPx(), Offset(cx, cy))
            drawCircle(color, 12.dp.toPx(), Offset(cx, cy), style = Stroke(2.dp.toPx()))
            num.color = color.toArgb()
            canvas.drawText("${m.number}", cx, cy + 4.5.dp.toPx(), num)
        }
    }
}

/** Halvcirkelformad mätare från ojämnt (0) till jämnt (1). */
@Composable
fun EvenGauge(value: Double?, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        val stroke = 20.dp.toPx()
        val r = minOf(size.width / 2 - stroke, size.height - stroke - 18.dp.toPx())
        val cx = size.width / 2
        val cy = size.height - 18.dp.toPx()
        val tl = Offset(cx - r, cy - r)
        val sz = Size(2 * r, 2 * r)
        drawArc(CardAlt, 180f, 60f, false, tl, sz, style = Stroke(stroke))
        drawArc(AccentSoft, 240f, 60f, false, tl, sz, style = Stroke(stroke))
        drawArc(GoodSoft, 300f, 60f, false, tl, sz, style = Stroke(stroke))
        val text = Paint().apply {
            isAntiAlias = true
            textSize = 13.sp.toPx()
            color = InkMuted.toArgb()
        }
        val canvas = drawContext.canvas.nativeCanvas
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("Ojämnt", cx - r - stroke / 2, size.height - 2.dp.toPx(), text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("Jämnt", cx + r + stroke / 2, size.height - 2.dp.toPx(), text)
        if (value != null) {
            val a = PI * (1 - value.coerceIn(0.0, 1.0))
            val len = r - stroke * 0.2f
            val end = Offset((cx + len * cos(a)).toFloat(), (cy - len * sin(a)).toFloat())
            drawLine(Ink, Offset(cx, cy), end, 5.dp.toPx(), cap = StrokeCap.Round)
        }
        drawCircle(Ink, 10.dp.toPx(), Offset(cx, cy))
    }
}

/** Liten kurva över de senaste värdena; streckad linje = bästa hittills. */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, higherIsBetter: Boolean = true, color: Color = Good) {
    Canvas(modifier.fillMaxWidth().height(60.dp)) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).coerceAtLeast(1.0)
        fun y(v: Double): Float {
            val t = (v - lo) / span
            return ((if (higherIsBetter) 1 - t else t) * (size.height - 8.dp.toPx()) + 4.dp.toPx()).toFloat()
        }
        val step = size.width / (values.size - 1)
        val best = if (higherIsBetter) hi else lo
        drawLine(
            color, Offset(0f, y(best)), Offset(size.width, y(best)), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
        val p = Path()
        values.forEachIndexed { i, v -> if (i == 0) p.moveTo(0f, y(v)) else p.lineTo(i * step, y(v)) }
        drawPath(p, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 5.dp.toPx(), Offset(size.width, y(values.last())))
    }
}
