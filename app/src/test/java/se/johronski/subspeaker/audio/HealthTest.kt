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

    @Test
    fun excludedSpeechIsIgnored() {
        val rec = record(0.5, 1.0, 14.0)
        // "Röst" mitt i: en kraftig 150 Hz-ton under 1,5 s.
        val start = 4 * Multitone.FRAME + 300
        val len = (1.5 * SweepAnalyzer.FS).toInt()
        for (i in 0 until len) rec[start + i] += (0.3 * kotlin.math.sin(2 * Math.PI * 150.0 * i / SweepAnalyzer.FS)).toFloat()
        val disturbed = Multitone.analyze(rec)!!
        val clean = Multitone.analyze(rec, exclude = listOf(start until start + len + SweepAnalyzer.FS))!!
        val ref = 20 * log10(0.5)
        assertTrue(kotlin.math.abs(at(disturbed.db, 150.0) - ref) > 1.0)
        assertEquals(ref, at(clean.db, 150.0), 0.3)
        assertTrue(clean.frames < disturbed.frames)
    }

    @Test
    fun handlingRumbleDoesNotHideSignal() {
        val rec = record(0.05, 1.0, 16.0)
        // Kraftigt muller kring 4–7 Hz som sveller upp och ner, som när telefonen rörs runt huvudet.
        var ph = 0.0
        for (n in rec.indices) {
            val env = 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * n / (2.0 * Multitone.FRAME))
            val f = 5.5 + 1.5 * kotlin.math.sin(2 * Math.PI * n / (7.0 * SweepAnalyzer.FS))
            ph += 2 * Math.PI * f / SweepAnalyzer.FS
            rec[n] += (0.4 * env * kotlin.math.sin(ph)).toFloat()
        }
        val res = Multitone.analyze(rec)!!
        assertTrue(res.reliable)
        assertTrue(res.frames >= 8)
        assertEquals(20 * log10(0.05), at(res.db, 100.0), 0.5)
    }

    @Test
    fun fallbackGivesUnreliableCurve() {
        // Bara brus, inget mätljud.
        val rnd = Random(9)
        val rec = FloatArray(12 * Multitone.FRAME) { (rnd.nextGaussian() * 0.001).toFloat() }
        // Utan reserv: ingen kurva om inga ramar räknas som signal. Med reserv: alltid en kurva, markerad osäker.
        val r = Multitone.analyze(rec, fallback = true)
        assertNotNull(r)
        val strict = Multitone.analyze(rec)
        if (strict == null) assertTrue(!r!!.reliable)
    }

    @Test
    fun liveCurveFollowsChanges() {
        val a = record(0.5, 0.0, 12.0)
        val b = record(0.25, 0.0, 12.0)
        val stream = a + b
        val live = LiveSpectrum(1.5)
        val step = SweepAnalyzer.FS / 3
        var n = Multitone.FRAME + 600
        var atA = 0.0
        while (n <= stream.size) {
            live.update(stream.copyOfRange(n - Multitone.FRAME - 600, n), 1.0 / 3)
            if (n in (a.size - step)..a.size) atA = at(live.curve!!, 100.0)
            n += step
        }
        // Följer först nivån i a, sedan b (6 dB lägre).
        assertEquals(20 * log10(0.5), atA, 0.4)
        assertEquals(20 * log10(0.25), at(live.curve!!, 100.0), 0.5)
        assertTrue(live.stable)
    }
}
