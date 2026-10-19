package se.johronski.subspeaker.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Räknar fram en frekvenskurva ur en inspelning av ett logaritmiskt svep.
 *
 * Inspelningen och referenssvepet jämförs i frekvensplanet (regulariserad division),
 * vilket ger rummets impulssvar. Toppen i impulssvaret visar fördröjningen, så Chromecastens
 * okända buffring spelar ingen roll. Impulssvaret fönstras och görs om till en kurva med
 * 1/6-oktavsutjämning.
 */
object SweepAnalyzer {
    const val DECIMATION = 16
    const val FS = SignalGenerator.SAMPLE_RATE / DECIMATION // 3000 Hz

    /** Mätsvepet: 10–600 Hz på 8 s. */
    val SWEEP = SignalSpec.Sweep(10.0, 600.0, 8.0, -12.0)

    /** Kurvans frekvenser: 20–300 Hz i 1/24-oktavssteg. */
    val FREQUENCIES: DoubleArray = run {
        val steps = (24 * ln(300.0 / 20.0) / ln(2.0)).toInt()
        DoubleArray(steps + 1) { 20.0 * 2.0.pow(it / 24.0) }
    }

    data class Result(
        val db: DoubleArray,
        val delaySeconds: Double,
        val snrDb: Double,
    )

    fun analyze(recording: FloatArray, sweep: SignalSpec.Sweep = SWEEP): Result {
        val ref = SignalGenerator.logSweep(sweep.f1, sweep.f2, sweep.seconds, sweep.dbfs, sampleRate = FS)
        if (recording.size < ref.size) throw IllegalArgumentException("Inspelningen är kortare än svepet")

        var n = 1
        while (n < recording.size + ref.size) n = n shl 1
        val sRe = DoubleArray(n)
        val sIm = DoubleArray(n)
        val rRe = DoubleArray(n)
        val rIm = DoubleArray(n)
        for (i in ref.indices) sRe[i] = ref[i].toDouble()
        for (i in recording.indices) rRe[i] = recording[i].toDouble()
        Fft.transform(sRe, sIm, false)
        Fft.transform(rRe, rIm, false)

        var maxS2 = 0.0
        for (k in 0 until n) {
            val p = sRe[k] * sRe[k] + sIm[k] * sIm[k]
            if (p > maxS2) maxS2 = p
        }
        val eps = 1e-3 * maxS2
        for (k in 0 until n) {
            val sr = sRe[k]
            val si = sIm[k]
            val rr = rRe[k]
            val ri = rIm[k]
            val den = sr * sr + si * si + eps
            rRe[k] = (rr * sr + ri * si) / den
            rIm[k] = (ri * sr - rr * si) / den
        }
        Fft.transform(rRe, rIm, true)
        val h = rRe

        var d = 0
        var peak = 0.0
        for (i in 0 until n) {
            val v = abs(h[i])
            if (v > peak) { peak = v; d = i }
        }

        val pre = (0.05 * FS).toInt()
        val post = (0.5 * FS).toInt()
        val start = (d - pre).coerceAtLeast(0)
        val end = (d + post).coerceAtMost(n)
        val seg = DoubleArray(end - start) { h[start + it] }
        val tp = (0.01 * FS).toInt().coerceAtMost(seg.size / 2)
        val tq = (0.1 * FS).toInt().coerceAtMost(seg.size / 2)
        for (i in 0 until tp) seg[i] *= 0.5 - 0.5 * cos(PI * i / tp)
        for (i in 0 until tq) seg[seg.size - tq + i] *= 0.5 + 0.5 * cos(PI * i / tq)

        val m = 8192
        val fRe = DoubleArray(m)
        val fIm = DoubleArray(m)
        for (i in seg.indices) if (i < m) fRe[i] = seg[i]
        Fft.transform(fRe, fIm, false)
        val binHz = FS.toDouble() / m
        val power = DoubleArray(m / 2 + 1) { fRe[it] * fRe[it] + fIm[it] * fIm[it] }

        val db = DoubleArray(FREQUENCIES.size) { idx ->
            val fc = FREQUENCIES[idx]
            val lo = fc * 2.0.pow(-1.0 / 12.0)
            val hi = fc * 2.0.pow(1.0 / 12.0)
            var sum = 0.0
            var cnt = 0
            var k = (lo / binHz).toInt()
            while (k * binHz <= hi && k < power.size) {
                if (k * binHz >= lo) { sum += power[k]; cnt++ }
                k++
            }
            10.0 * log10(sum / cnt.coerceAtLeast(1) + 1e-20)
        }

        // Brus: en del av impulssvaret där rummets efterklang har klingat av.
        val nStart = d + (0.7 * FS).toInt()
        val nEnd = d + (1.2 * FS).toInt()
        val noise = if (nEnd < n) {
            (nStart until nEnd).map { h[it] }
        } else {
            val a = (d - (1.5 * FS).toInt()).coerceAtLeast(0)
            val b = (d - (0.5 * FS).toInt()).coerceAtLeast(a + 1)
            (a until b).map { h[it] }
        }
        val noisePow = noise.sumOf { it * it } / noise.size.coerceAtLeast(1)
        val segPow = seg.sumOf { it * it }
        val snr = 10.0 * log10(segPow / (noisePow * seg.size + 1e-30))

        return Result(db, d.toDouble() / FS, snr)
    }

    /** Effektmedelvärde av flera kurvor (dB). */
    fun powerAverage(curves: List<DoubleArray>): DoubleArray {
        val size = curves.first().size
        return DoubleArray(size) { i ->
            val mean = curves.sumOf { 10.0.pow(it[i] / 10.0) } / curves.size
            10.0 * log10(mean)
        }
    }

    /**
     * Avvikelse (dB, viktat RMS) från en rak trendlinje över fLow–fHigh på logaritmisk frekvensaxel.
     * Området runt delningen (60–130 Hz) väger dubbelt. En jämn lutning straffas alltså inte,
     * men toppar, dalar och trappsteg mellan sub och högtalare gör det.
     */
    fun deviation(db: DoubleArray, fLow: Double = 30.0, fHigh: Double = 200.0): Double {
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        val idx = FREQUENCIES.indices.filter { FREQUENCIES[it] in fLow..fHigh }
        fun w(f: Double) = if (f in 60.0..130.0) 2.0 else 1.0
        for (i in idx) {
            val f = FREQUENCIES[i]
            val x = ln(f) / ln(2.0)
            val wi = w(f)
            sw += wi; sx += wi * x; sy += wi * db[i]; sxx += wi * x * x; sxy += wi * x * db[i]
        }
        val den = sw * sxx - sx * sx
        val b = if (den != 0.0) (sw * sxy - sx * sy) / den else 0.0
        val a = (sy - b * sx) / sw
        var acc = 0.0
        for (i in idx) {
            val f = FREQUENCIES[i]
            val r = db[i] - (a + b * ln(f) / ln(2.0))
            acc += w(f) * r * r
        }
        return sqrt(acc / sw)
    }

    /**
     * Basbalans (dB): effektmedel 25–63 Hz (subbens område) minus 100–250 Hz (Genelecens område).
     * Positivt värde betyder att basen ligger över Genelecen.
     */
    fun balance(db: DoubleArray): Double {
        fun pav(lo: Double, hi: Double): Double {
            val v = FREQUENCIES.indices.filter { FREQUENCIES[it] in lo..hi }.map { 10.0.pow(db[it] / 10.0) }
            return 10.0 * log10(v.average())
        }
        return pav(25.0, 63.0) - pav(100.0, 250.0)
    }

    /** Största skillnad (dB) mellan upprepade kurvor i området 30–200 Hz. */
    fun repeatSpread(curves: List<DoubleArray>): Double =
        FREQUENCIES.indices.filter { FREQUENCIES[it] in 30.0..200.0 }.maxOf { i ->
            curves.maxOf { it[i] } - curves.minOf { it[i] }
        }

    /** Medelnivå och standardavvikelse (dB) i ett frekvensområde. */
    fun stats(db: DoubleArray, fLow: Double = 40.0, fHigh: Double = 150.0): Pair<Double, Double> {
        val vals = FREQUENCIES.indices.filter { FREQUENCIES[it] in fLow..fHigh }.map { db[it] }
        val mean = vals.average()
        val sd = sqrt(vals.sumOf { (it - mean) * (it - mean) } / vals.size)
        return mean to sd
    }
}
