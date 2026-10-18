package se.johronski.subspeaker.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import se.johronski.subspeaker.audio.SweepAnalyzer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

val SeriesColors = listOf(
    Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
    Color(0xFFD55181), Color(0xFF9085E9), Color(0xFFE66767), Color(0xFF008300),
)

/** Frekvenskurvor 20–300 Hz på logaritmisk axel. */
@Composable
fun SweepChart(
    curves: List<DoubleArray>,
    modifier: Modifier = Modifier,
    bands: List<Pair<DoubleArray, DoubleArray>> = emptyList(),
) {
    val freqs = SweepAnalyzer.FREQUENCIES
    val grid = Color(0xFF2C2C2A)
    val tick = Color(0xFF898781)
    Canvas(modifier = modifier.fillMaxWidth().height(260.dp)) {
        val left = 44f
        val bottom = size.height - 28f
        val top = 8f
        val right = size.width - 8f
        val all = curves.flatMap { it.toList() } + bands.flatMap { it.first.toList() + it.second.toList() }
        val maxDb = if (all.isEmpty()) 0.0 else ceil((all.max() + 3) / 6) * 6
        val minDb = if (all.isEmpty()) -30.0 else maxOf(floor((all.min() - 3) / 6) * 6, maxDb - 48)
        val fMin = 20.0
        val fMax = 300.0
        fun x(f: Double) = (left + (ln(f / fMin) / ln(fMax / fMin)) * (right - left)).toFloat()
        fun y(db: Double) = (bottom - (db.coerceIn(minDb, maxDb) - minDb) / (maxDb - minDb) * (bottom - top)).toFloat()

        val paint = Paint().apply {
            color = tick.toArgb()
            textSize = 26f
            isAntiAlias = true
        }
        var d = minDb
        while (d <= maxDb + 0.1) {
            drawLine(grid, Offset(left, y(d)), Offset(right, y(d)), 1f)
            paint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText("${d.toInt()}", left - 6f, y(d) + 9f, paint)
            d += 6.0
        }
        for (f in listOf(20.0, 30.0, 40.0, 50.0, 70.0, 100.0, 150.0, 200.0, 300.0)) {
            drawLine(grid, Offset(x(f), top), Offset(x(f), bottom), 1f)
            paint.textAlign = Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText("${f.toInt()}", x(f), size.height - 4f, paint)
        }
        bands.forEachIndexed { idx, (lo, hi) ->
            val path = Path()
            for (i in freqs.indices) {
                val px = x(freqs[i])
                if (i == 0) path.moveTo(px, y(hi[i])) else path.lineTo(px, y(hi[i]))
            }
            for (i in freqs.indices.reversed()) path.lineTo(x(freqs[i]), y(lo[i]))
            path.close()
            drawPath(path, SeriesColors[idx % SeriesColors.size].copy(alpha = 0.18f))
        }
        curves.forEachIndexed { idx, c ->
            val path = Path()
            for (i in freqs.indices) {
                val px = x(freqs[i])
                val py = y(c[i])
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, SeriesColors[idx % SeriesColors.size], style = Stroke(width = 5f, join = StrokeJoin.Round))
        }
    }
}
