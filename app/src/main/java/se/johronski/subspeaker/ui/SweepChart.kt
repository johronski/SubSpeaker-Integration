package se.johronski.subspeaker.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.audio.SignalSpec
import se.johronski.subspeaker.audio.SweepAnalyzer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** En kurva i diagrammet, med valfritt spridningsband (min–max mellan mätplatser). */
class ChartCurve(
    val label: String,
    val db: DoubleArray,
    val band: Pair<DoubleArray, DoubleArray>? = null,
    /** Fast färg; annars efter ordning i [SeriesColors]. */
    val color: androidx.compose.ui.graphics.Color? = null,
    /** Streckad, t.ex. "före". */
    val dashed: Boolean = false,
    /** Lite tjockare än övriga när ingen kurva är markerad. */
    val thick: Boolean = false,
)

private const val F_MIN = 20.0
private const val F_MAX = 300.0

/**
 * Frekvenskurvor 20–300 Hz på logaritmisk axel.
 *
 * Kurvorna normaliseras gemensamt så att 0 dB är medelnivån runt 80–125 Hz; inbördes nivåskillnader behålls.
 * [highlight] ritas tjock och övriga tunna. [markers] är markerade dippar, [cursor] tonen som spelas.
 * Med [onTap] kan man trycka i diagrammet för att välja en frekvens.
 */
@Composable
fun SweepChart(
    curves: List<ChartCurve>,
    modifier: Modifier = Modifier,
    highlight: Int? = null,
    markers: List<Double> = emptyList(),
    cursor: Double? = null,
    onTap: ((Double) -> Unit)? = null,
    /** Fast nollnivå (dB före normalisering), så att skalan inte hoppar när kurvor slås av och på. */
    refDb: Double? = null,
    /** Där sub och Genelec möts, ritas som en streckad linje med etikett. */
    crossover: Double? = null,
    /** Numrerade fynd: (frekvens, nummer). */
    numbered: List<Pair<Double, Int>> = emptyList(),
) {
    val freqs = SweepAnalyzer.FREQUENCIES
    val tapModifier = if (onTap != null) {
        Modifier.pointerInput(onTap) {
            detectTapGestures { pos ->
                val left = 40.dp.toPx()
                val right = size.width - 6.dp.toPx()
                val t = ((pos.x - left) / (right - left)).coerceIn(0f, 1f)
                onTap(F_MIN * (F_MAX / F_MIN).pow(t.toDouble()))
            }
        }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.fillMaxWidth().height(250.dp).then(tapModifier)) {
        val left = 40.dp.toPx()
        val right = size.width - 6.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = size.height - 22.dp.toPx()

        // Gemensam normalisering.
        val refIdx = freqs.indices.filter { freqs[it] in 80.0..125.0 }
        val offset = refDb ?: curves.flatMap { c -> refIdx.map { c.db[it] } }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        fun n(v: Double) = v - offset
        val all = curves.flatMap { c ->
            c.db.map { n(it) } + (c.band?.let { b -> b.first.map { n(it) } + b.second.map { n(it) } } ?: emptyList())
        }
        val maxDb = if (all.isEmpty()) 12.0 else ceil((all.max() + 2) / 6) * 6
        val minDb = if (all.isEmpty()) -24.0 else maxOf(floor((all.min() - 2) / 6) * 6, maxDb - 42)

        fun x(f: Double) = (left + (ln(f / F_MIN) / ln(F_MAX / F_MIN)) * (right - left)).toFloat()
        fun y(db: Double) = (bottom - (db.coerceIn(minDb, maxDb) - minDb) / (maxDb - minDb) * (bottom - top)).toFloat()

        val tickPaint = Paint().apply {
            color = InkMuted.toArgb()
            textSize = 11.sp.toPx()
            isAntiAlias = true
        }
        val canvas = drawContext.canvas.nativeCanvas

        // Rutnät
        var d = minDb
        while (d <= maxDb + 0.01) {
            drawLine(if (d == 0.0) Line.copy(alpha = 1f) else Line.copy(alpha = 0.6f), Offset(left, y(d)), Offset(right, y(d)), 1.dp.toPx())
            tickPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(if (d > 0) "+${d.toInt()}" else "${d.toInt()}", left - 6.dp.toPx(), y(d) + 4.dp.toPx(), tickPaint)
            d += 6.0
        }
        tickPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("dB", 2.dp.toPx(), top - 2.dp.toPx(), tickPaint)
        tickPaint.textAlign = Paint.Align.CENTER
        for (f in listOf(20.0, 30.0, 40.0, 50.0, 70.0, 100.0, 150.0, 200.0, 300.0)) {
            drawLine(Line.copy(alpha = 0.6f), Offset(x(f), top), Offset(x(f), bottom), 1.dp.toPx())
            canvas.drawText("${f.toInt()}", x(f), size.height - 4.dp.toPx(), tickPaint)
        }

        // Markerade dippar
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
        markers.filter { it in F_MIN..F_MAX }.forEach { m ->
            drawLine(Bad.copy(alpha = 0.7f), Offset(x(m), top), Offset(x(m), bottom), 1.5.dp.toPx(), pathEffect = dash)
            tickPaint.color = Bad.toArgb()
            canvas.drawText(SignalSpec.fmt(m), x(m), top - 2.dp.toPx(), tickPaint)
        }
        tickPaint.color = InkMuted.toArgb()

        // Spridningsband
        curves.forEachIndexed { idx, c ->
            val (lo, hi) = c.band ?: return@forEachIndexed
            if (highlight != null && idx != highlight) return@forEachIndexed
            val path = Path()
            for (i in freqs.indices) {
                if (i == 0) path.moveTo(x(freqs[i]), y(n(hi[i]))) else path.lineTo(x(freqs[i]), y(n(hi[i])))
            }
            for (i in freqs.indices.reversed()) path.lineTo(x(freqs[i]), y(n(lo[i])))
            path.close()
            drawPath(path, (c.color ?: SeriesColors[idx % SeriesColors.size]).copy(alpha = 0.14f))
        }

        // Kurvor: markerad kurva sist så att den ligger överst.
        val order = curves.indices.sortedBy { if (it == highlight) 1 else 0 }
        for (idx in order) {
            val c = curves[idx]
            val path = Path()
            for (i in freqs.indices) {
                val px = x(freqs[i])
                val py = y(n(c.db[i]))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            val color = c.color ?: SeriesColors[idx % SeriesColors.size]
            val (w, a) = when {
                highlight == null -> (if (c.thick) 3.5.dp.toPx() else 2.5.dp.toPx()) to 1f
                idx == highlight -> 3.5.dp.toPx() to 1f
                else -> 1.5.dp.toPx() to 0.4f
            }
            drawPath(
                path, color.copy(alpha = a),
                style = Stroke(
                    width = w, join = StrokeJoin.Round, cap = StrokeCap.Round,
                    pathEffect = if (c.dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null,
                ),
            )
        }

        // Namn vid kurvornas högra ände, utan att de krockar.
        if (curves.size in 2..6) {
            val labelPaint = Paint().apply {
                textSize = 12.sp.toPx()
                isAntiAlias = true
                textAlign = Paint.Align.RIGHT
                typeface = Typeface.DEFAULT_BOLD
            }
            val halo = Paint(labelPaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4.dp.toPx()
                color = CardColor.toArgb()
            }
            val iEnd = freqs.indexOfLast { it <= 260.0 }
            val gap = 15.dp.toPx()
            val placed = curves.indices.map { it to y(n(curves[it].db[iEnd])) - 6.dp.toPx() }.sortedBy { it.second }.toMutableList()
            for (k in 1 until placed.size) {
                if (placed[k].second - placed[k - 1].second < gap) placed[k] = placed[k].first to placed[k - 1].second + gap
            }
            placed.forEach { (idx, py) ->
                val text = curves[idx].label.let { if (it.length > 16) it.take(15) + "…" else it }
                val yy = py.coerceIn(top + gap, bottom)
                canvas.drawText(text, right - 2.dp.toPx(), yy, halo)
                labelPaint.color = (curves[idx].color ?: SeriesColors[idx % SeriesColors.size]).copy(alpha = if (highlight == null || highlight == idx) 1f else 0.6f).toArgb()
                canvas.drawText(text, right - 2.dp.toPx(), yy, labelPaint)
            }
        }

        // Där sub och Genelec möts
        crossover?.takeIf { it in F_MIN..F_MAX }?.let { f ->
            drawLine(Good, Offset(x(f), top), Offset(x(f), bottom), 1.5.dp.toPx(), pathEffect = dash)
            tickPaint.color = Good.toArgb()
            tickPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("möts ${SignalSpec.fmt(Math.round(f).toDouble())} Hz", x(f), bottom - 4.dp.toPx(), tickPaint)
        }

        // Numrerade fynd längst upp
        val numPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = 11.sp.toPx()
            typeface = Typeface.DEFAULT_BOLD
            color = SeriesColors[1].toArgb()
        }
        numbered.filter { it.first in F_MIN..F_MAX }.forEach { (f, n) ->
            val cy = top + 9.dp.toPx()
            drawCircle(CardColor, 9.dp.toPx(), Offset(x(f), cy))
            drawCircle(SeriesColors[1], 9.dp.toPx(), Offset(x(f), cy), style = Stroke(1.5.dp.toPx()))
            canvas.drawText("$n", x(f), cy + 4.dp.toPx(), numPaint)
        }

        // Tonen som spelas
        cursor?.takeIf { it in F_MIN..F_MAX }?.let { f ->
            drawLine(Accent, Offset(x(f), top), Offset(x(f), bottom), 2.dp.toPx())
            tickPaint.color = Accent.toArgb()
            tickPaint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("${SignalSpec.fmt(f)} Hz", x(f), top - 2.dp.toPx(), tickPaint)
        }
    }
}
