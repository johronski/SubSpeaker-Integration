package se.johronski.subspeaker.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/** Kurvan går till 600 Hz sedan 0.8.9. */
class RangeTest {

    @Test
    fun gridReaches600AndKeepsOldPointsAsPrefix() {
        val f = SweepAnalyzer.FREQUENCIES
        assertTrue(f.last() >= 600.0 && f.last() < 610.0)
        // De 94 första punkterna är desamma som i den gamla kurvan 20–300 Hz.
        for (i in 0 until 94) assertTrue(Math.abs(f[i] - 20.0 * Math.pow(2.0, i / 24.0)) < 1e-9)
        assertTrue(f[93] <= 300.0 && f[94] > 300.0)
    }

    @Test
    fun multitoneIsFlatTo600Hz() {
        val sig = Multitone.render(8.0)
        val dec = Decimator(SweepAnalyzer.DECIMATION)
        val out = ArrayList<Float>()
        for (v in sig) dec.push(v.toDouble())?.let { out.add(it.toFloat()) }
        val r = Multitone.analyze(out.toFloatArray())!!
        val f = SweepAnalyzer.FREQUENCIES
        val vals = f.indices.filter { f[it] in 20.0..605.0 }.map { r.db[it] }
        val spread = vals.max() - vals.min()
        assertTrue("spridning $spread dB", spread < 0.5)
    }
}
