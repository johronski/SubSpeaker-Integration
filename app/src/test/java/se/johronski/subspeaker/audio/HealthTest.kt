package se.johronski.subspeaker.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class HealthTest {

    private val F = SweepAnalyzer.FREQUENCIES

    /** Spelar mätbruset genom en förstärkning, med tystnad före och brus, och samplar ner som inspelningen. */
    private fun record(gain: Double, silenceSeconds: Double, seconds: Double, filter: ((DoubleArray) -> DoubleArray)? = null): FloatArray {
        val fs = SignalGenerator.SAMPLE_RATE
        val sig = Multitone.render(seconds)
        var y = DoubleArray(sig.size) { sig[it] * gain }
        if (filter != null) y = filter(y)
        val pre = (silenceSeconds * fs).toInt()
        val rec = DoubleArray(pre + y.size)
        for (i in y.indices) rec[pre + i] = y[i]
        val rnd = Random(3)
        for (i in rec.indices) rec[i] += rnd.nextGaussian() * 0.0005
        val dec = Decimator(SweepAnalyzer.DECIMATION)
        val out = ArrayList<Float>()
        for (v in rec) dec.push(v)?.let { out.add(it.toFloat()) }
        return out.toFloatArray()
    }

    private fun at(db: DoubleArray, f: Double) = db[F.indices.minByOrNull { kotlin.math.abs(F[it] - f) }!!]

    @Test
    fun signalIsPeriodicAndLimited() {
        val p = Multitone.period
        assertEquals(Multitone.PERIOD, p.size)
        val peak = p.maxOf { kotlin.math.abs(it) }
        assertEquals(10.0.pow(Multitone.PEAK_DBFS / 20.0), peak.toDouble(), 1e-3)
        val r = Multitone.render(3.0)
        assertEquals(r[Multitone.PERIOD + 5000], r[2 * Multitone.PERIOD + 5000], 1e-6f)
    }

    @Test
    fun flatSystemGivesFlatCurveRegardlessOfStart() {
        val res = Multitone.analyze(record(0.5, 1.7, 14.0))!!
        assertTrue(res.frames >= 8)
        val ref = at(res.db, 100.0)
        assertEquals(20 * log10(0.5), ref, 0.3)
        for (f in listOf(25.0, 40.0, 63.0, 160.0, 250.0)) assertEquals(ref, at(res.db, f), 0.3)
        assertNotNull(res.snrDb)
        assertTrue(res.snrDb!! > 30)
    }

    @Test
    fun lowpassShowsAsRolloff() {
        // Enpolig lågpass vid 80 Hz: −3 dB vid 80, ungefär −7 dB vid 160.
        val a = kotlin.math.exp(-2 * Math.PI * 80.0 / SignalGenerator.SAMPLE_RATE)
        val res = Multitone.analyze(record(0.5, 1.0, 12.0) { x ->
            var s = 0.0
            DoubleArray(x.size) { s = (1 - a) * x[it] + a * s; s }
        })!!
        val low = at(res.db, 25.0)
        assertEquals(-3.0, at(res.db, 80.0) - low, 0.7)
        assertEquals(-10 * log10(1 + 4.0), at(res.db, 160.0) - low, 0.8)
    }

    /** Hjälp: kurva ur en funktion av frekvensen. */
    private fun curve(f: (Double) -> Double) = DoubleArray(F.size) { f(F[it]) }

    private fun lp(f: Double, fc: Double, order: Int) = -10 * log10(1 + (f / fc).pow(2 * order))
    private fun hp(f: Double, fc: Double, order: Int) = -10 * log10(1 + (fc / f).pow(2 * order))

    @Test
    fun cancellationIsFoundAndRoomDipIsSeparated() {
        val gen = curve { f -> hp(f, 70.0, 2) - (if (kotlin.math.abs(f - 120) < 6) 9.0 else 0.0) }
        val sub = curve { f -> lp(f, 90.0, 4) + 1.0 }
        // Tillsammans: utsläckning runt 85 Hz, där båda är ungefär lika starka.
        val together = DoubleArray(F.size) { i ->
            val p = 10.0.pow(gen[i] / 10) + 10.0.pow(sub[i] / 10)
            val cancel = if (F[i] in 70.0..105.0) 0.15 else 1.0
            10 * log10(p * cancel)
        }
        val r = Diagnosis.diagnose(together, gen, sub, targetBalance = SweepAnalyzer.balance(together))
        val keys = r.findings.map { it.key }
        assertTrue(keys.contains("cancel"))
        assertEquals("cancel", r.findings.first().key)
        assertTrue(keys.contains("room"))
        assertEquals(120.0, r.findings.first { it.key == "room" }.freq!!, 8.0)
        assertNull(r.findings.first { it.key == "room" }.action)
        assertTrue(r.good.any { it.contains("lagom") })
        assertNotNull(r.crossoverHz)
    }

    @Test
    fun goodSystemHasNothingToFix() {
        val gen = curve { f -> hp(f, 60.0, 1) }
        val sub = curve { f -> lp(f, 90.0, 4) }
        val together = DoubleArray(F.size) { i -> 10 * log10(10.0.pow(gen[i] / 10) + 10.0.pow(sub[i] / 10) + 2 * sqrt(10.0.pow(gen[i] / 10) * 10.0.pow(sub[i] / 10)) * 0.8) }
        val r = Diagnosis.diagnose(together, gen, sub, targetBalance = SweepAnalyzer.balance(together))
        assertTrue(r.actionable.isEmpty())
        assertEquals("Basen sitter ihop", r.headline)
    }

    @Test
    fun levelAndLowpassAreSuggested() {
        val gen = curve { f -> hp(f, 60.0, 1) }
        val sub = curve { f -> lp(f, 150.0, 4) + 8.0 }
        val together = DoubleArray(F.size) { i -> 10 * log10(10.0.pow(gen[i] / 10) + 10.0.pow(sub[i] / 10)) }
        val r = Diagnosis.diagnose(together, gen, sub, targetBalance = 0.0)
        val byKey = r.findings.associateBy { it.key }
        assertEquals(Diagnosis.Action.LEVEL, byKey["level"]?.action)
        assertTrue(byKey["level"]!!.title.contains("för stark"))
        assertEquals(Diagnosis.Action.LOWPASS, byKey["lowpass"]?.action)
    }

    @Test
    fun gaugeMapsUnevenness() {
        assertEquals(1.0, Diagnosis.gauge(1.0), 1e-9)
        assertEquals(0.0, Diagnosis.gauge(6.0), 1e-9)
        assertEquals("Jämnt", Diagnosis.gaugeWord(2.0))
        assertEquals("Ojämnt", Diagnosis.gaugeWord(4.5))
    }

    @Test
    fun gapInPlaybackIsSkipped() {
        val a = record(0.5, 1.0, 6.0)
        val b = record(0.5, 1.5, 6.0)
        val rec = a + b
        val res = Multitone.analyze(rec)!!
        val ref = at(res.db, 100.0)
        assertEquals(20 * log10(0.5), ref, 0.4)
        for (f in listOf(30.0, 63.0, 200.0)) assertEquals(ref, at(res.db, f), 0.4)
    }
}
