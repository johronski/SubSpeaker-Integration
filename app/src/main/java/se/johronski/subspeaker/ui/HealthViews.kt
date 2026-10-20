@file:OptIn(ExperimentalLayoutApi::class)

package se.johronski.subspeaker.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
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

/** En kurva i kurvvisaren. */
class ExplorerCurve(
    val label: String,
    val explain: String,
    val db: DoubleArray,
    val color: Color,
    val dashed: Boolean = false,
    val thick: Boolean = false,
    val initiallyOn: Boolean = true,
)

/**
 * Detaljerade kurvor med Hz-axel. Varje kurva slås av och på med en knapp.
 * Första trycket i diagrammet väljer en frekvens; den spelas först när man trycker Spela.
 */
@androidx.compose.runtime.Composable
fun CurveExplorer(
    services: se.johronski.subspeaker.AppServices,
    curves: List<ExplorerCurve>,
    crossover: Double? = null,
    numbered: List<Pair<Double, Int>> = emptyList(),
) {
    val on = androidx.compose.runtime.remember(curves.size) {
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply { curves.forEach { put(it.label, it.initiallyOn) } }
    }
    var picked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Double?>(null) }
    val playback by services.playback.state.collectAsState()
    val ref = curves.firstOrNull()?.let { c ->
        SweepAnalyzer.FREQUENCIES.indices.filter { SweepAnalyzer.FREQUENCIES[it] in 80.0..125.0 }.map { c.db[it] }.average()
    }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        curves.forEach { c ->
            val sel = on[c.label] == true
            androidx.compose.material3.FilterChip(
                selected = sel,
                onClick = { on[c.label] = !sel },
                label = { androidx.compose.material3.Text(c.label) },
                leadingIcon = {
                    androidx.compose.foundation.layout.Spacer(
                        Modifier
                            .size(10.dp)
                            .androidxClip()
                            .androidxBg(if (sel) c.color else c.color.copy(alpha = 0.3f)),
                    )
                },
            )
        }
    }
    val visible = curves.filter { on[it.label] == true }
    SweepChart(
        visible.map { ChartCurve(it.label, it.db, color = it.color, dashed = it.dashed, thick = it.thick) },
        refDb = ref,
        crossover = crossover,
        numbered = numbered,
        markers = playback.markers,
        cursor = picked,
        onTap = { f -> picked = se.johronski.subspeaker.audio.ToneGrid.snap(f, 24) },
    )
    val p = picked
    if (p != null) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Text(
                "${se.johronski.subspeaker.audio.SignalSpec.fmt(p)} Hz",
                fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Button(onClick = { services.playback.playTone(p) }) { androidx.compose.material3.Text("Spela") }
            androidx.compose.material3.OutlinedButton(onClick = { services.playback.stop() }) { androidx.compose.material3.Text("Stopp") }
        }
    } else {
        Muted("Tryck i diagrammet för att välja en frekvens och lyssna på den.")
    }
    curves.forEach { c ->
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 6.dp).size(10.dp).androidxClip().androidxBg(c.color))
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            androidx.compose.material3.Text(
                c.label + " – " + c.explain,
                fontSize = 14.sp, lineHeight = 20.sp, color = InkMuted,
            )
        }
    }
}

private fun Modifier.androidxClip() = this.then(Modifier.clip(androidx.compose.foundation.shape.CircleShape))
private fun Modifier.androidxBg(c: Color) = this.then(Modifier.background(c))

private fun snapshotColor(label: String): Color = when (label) {
    "Fas 0" -> SeriesColors[1]
    "Fas 90" -> SeriesColors[2]
    "Fas 180" -> SeriesColors[3]
    "Fas 270" -> SeriesColors[4]
    "Jämförelse 1" -> SeriesColors[1]
    "Jämförelse 2" -> SeriesColors[2]
    else -> SeriesColors[4]
}

/**
 * Levande frekvenskurva: hastighet, frys, nollställ skala, jämförelsekurvor.
 * Tryck i diagrammet för att läsa av nivåerna vid en frekvens (inget spelas, mätljudet fortsätter).
 */
@androidx.compose.runtime.Composable
fun LiveCurvePanel(services: se.johronski.subspeaker.AppServices, live: se.johronski.subspeaker.HealthController.Live) {
    var picked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Double?>(null) }
    val playback by services.playback.state.collectAsState()
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        se.johronski.subspeaker.audio.LiveSpectrum.Speed.entries.forEach { sp ->
            androidx.compose.material3.FilterChip(
                selected = live.speed == sp,
                onClick = { services.health.setSpeed(sp) },
                label = { androidx.compose.material3.Text(sp.label) },
            )
        }
        androidx.compose.material3.FilterChip(
            selected = live.frozen,
            onClick = { services.health.toggleFreeze() },
            label = { androidx.compose.material3.Text(if (live.frozen) "Fryst" else "Frys") },
        )
    }
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Spacer(
            Modifier.size(9.dp).androidxClip().androidxBg(if (live.stable && !live.frozen) Good else Line),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        Muted(
            when {
                live.frozen -> "Kurvan är fryst"
                live.curve == null -> "Väntar på mätljudet …"
                live.stable -> "Kurvan har stabiliserat sig"
                else -> "Kurvan ändrar sig …"
            },
        )
    }
    val curves = buildList {
        live.snapshots.forEach { add(ChartCurve(it.label, it.db, color = snapshotColor(it.label))) }
        live.startCurve?.let { add(ChartCurve("Start", it, color = Color(0xFF8A9294), dashed = true)) }
        live.curve?.let { add(ChartCurve("Nu", it, color = Accent, thick = true)) }
    }
    SweepChart(
        curves,
        refDb = live.refDb,
        cursor = picked,
        markers = playback.markers,
        onTap = { f -> picked = se.johronski.subspeaker.audio.ToneGrid.snap(f, 24) },
    )
    val p = picked
    val ref = live.refDb ?: 0.0
    if (p != null) {
        val i = SweepAnalyzer.FREQUENCIES.indices.minByOrNull { kotlin.math.abs(SweepAnalyzer.FREQUENCIES[it] - p) } ?: 0
        val values = curves.reversed().joinToString(" · ") { c -> String.format(java.util.Locale.ROOT, "%s %+.1f dB", c.label, c.db[i] - ref) }
        androidx.compose.material3.Text(
            "${se.johronski.subspeaker.audio.SignalSpec.fmt(p)} Hz: $values",
            fontSize = 15.sp,
        )
    } else {
        Muted("Tryck i diagrammet för att läsa av nivån vid en frekvens.")
    }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        curves.reversed().forEach { c ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp).androidxClip().androidxBg(c.color ?: Accent))
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                androidx.compose.material3.Text(c.label + if (c.dashed) " (streckad)" else "", fontSize = 14.sp, color = InkMuted)
            }
        }
    }
    androidx.compose.material3.TextButton(onClick = { services.health.resetScale() }) { androidx.compose.material3.Text("Nollställ skala") }
}
