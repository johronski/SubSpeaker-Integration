package se.johronski.subspeaker.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class SweepTest {

    @Test
    fun fftMatchesDft() {
        val n = 64
        val x = DoubleArray(n) { sin(2 * PI * 5 * it / n) + 0.3 * cos(2 * PI * 11 * it / n) + 0.1 * it / n }
        val re = x.copyOf()
        val im = DoubleArray(n)
        Fft.transform(re, im, false)
        for (k in 0 until n) {
            var dr = 0.0
            var di = 0.0
            for (t in 0 until n) {
                dr += x[t] * cos(-2 * PI * k * t / n)
                di += x[t] * sin(-2 * PI * k * t / n)
            }
            assertEquals(dr, re[k], 1e-9)
            assertEquals(di, im[k], 1e-9)
        }
        Fft.transform(re, im, true)
        for (t in 0 until n) assertEquals(x[t], re[t], 1e-9)
    }

    /** Simulerar en inspelning: fördröjning, valfritt filter, brus. Returnerar nedsamplad inspelning. */
    private fun simulate(delaySeconds: Double, gain: Double, filter: ((DoubleArray) -> DoubleArray)?): FloatArray {
        val fs = SignalGenerator.SAMPLE_RATE
        val sp = SweepAnalyzer.SWEEP
        val s = SignalGenerator.logSweep(sp.f1, sp.f2, sp.seconds, sp.dbfs)
        val delay = (delaySeconds * fs).toInt()
        var y = DoubleArray(s.size) { s[it] * gain }
        if (filter != null) y = filter(y)
        val rec = DoubleArray(delay + s.size + 3 * fs)
        for (i in y.indices) rec[delay + i] += y[i]
        val rnd = Random(1)
        for (i in rec.indices) rec[i] += rnd.nextGaussian() * 0.003
        val dec = Decimator(SweepAnalyzer.DECIMATION)
        val out = ArrayList<Float>()
        for (v in rec) dec.push(v)?.let { out.add(it.toFloat()) }
        return out.toFloatArray()
    }

    private fun at(r: SweepAnalyzer.Result, f: Double): Double {
        val fr = SweepAnalyzer.FREQUENCIES
        return r.db[fr.indices.minByOrNull { kotlin.math.abs(fr[it] - f) }!!]
    }

    @Test
    fun flatResponseAndDelayAreFound() {
        val r = SweepAnalyzer.analyze(simulate(3.217, 0.5, null))
        assertEquals(3.217, r.delaySeconds, 0.01)
        assertTrue("brusmarginal ${r.snrDb}", r.snrDb > 30)
        for (f in listOf(30.0, 50.0, 100.0, 200.0, 290.0)) {
            assertEquals("vid $f Hz", -6.0, at(r, f), 0.7)
        }
    }

    @Test
    fun lowPassIsMeasured() {
        val fs = SignalGenerator.SAMPLE_RATE.toDouble()
        val lp: (DoubleArray) -> DoubleArray = { x ->
            val k = tan(PI * 100.0 / fs)
            val b = k / (1 + k)
            val a1 = (k - 1) / (k + 1)
            val y = DoubleArray(x.size)
            var x1 = 0.0
            var y1 = 0.0
            for (i in x.indices) {
                y[i] = b * x[i] + b * x1 - a1 * y1
                x1 = x[i]
                y1 = y[i]
            }
            y
        }
        val r = SweepAnalyzer.analyze(simulate(1.0, 0.5, lp))
        assertEquals(-9.0, at(r, 100.0), 0.8)
        assertEquals(-13.0, at(r, 200.0), 0.9)
        assertEquals(-6.3, at(r, 30.0), 0.8)
    }

    @Test
    fun powerAverageAndStats() {
        val a = DoubleArray(SweepAnalyzer.FREQUENCIES.size) { 0.0 }
        val b = DoubleArray(SweepAnalyzer.FREQUENCIES.size) { -100.0 }
        val avg = SweepAnalyzer.powerAverage(listOf(a, b))
        assertEquals(-3.01, avg[0], 0.05)
        val (mean, sd) = SweepAnalyzer.stats(a)
        assertEquals(0.0, mean, 1e-9)
        assertEquals(0.0, sd, 1e-9)
    }
}
