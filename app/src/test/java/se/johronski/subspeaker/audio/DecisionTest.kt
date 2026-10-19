package se.johronski.subspeaker.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sin

class DecisionTest {

    @Test
    fun deviationIgnoresSlopeButSeesPeaks() {
        val f = SweepAnalyzer.FREQUENCIES
        val slope = DoubleArray(f.size) { 3.0 * ln(f[it]) / ln(2.0) }
        assertEquals(0.0, SweepAnalyzer.deviation(slope), 1e-9)
        val bumpy = DoubleArray(f.size) { 6.0 * sin(ln(f[it]) * 4) }
        org.junit.Assert.assertTrue(SweepAnalyzer.deviation(bumpy) > 2.0)
    }

    @Test
    fun compareGivesConfidence() {
        val a = Decision.Option("fas 0", 1.0, mapOf(1 to 1.0, 2 to 1.2, 3 to 0.9))
        val b = Decision.Option("fas 90", 2.0, mapOf(1 to 2.1, 2 to 1.8, 3 to 2.0))
        val c = Decision.Option("fas 180", 1.1, mapOf(1 to 0.9, 2 to 1.3, 3 to 1.1))
        assertEquals(Decision.Confidence.SURE, Decision.compare(listOf(a, b))!!.confidence)
        val v = Decision.compare(listOf(a, b, c))!!
        assertEquals("fas 0", v.best.key)
        assertEquals(Decision.Confidence.UNSURE, v.confidence)
        assertNull(Decision.compare(listOf(a)))
    }

    @Test
    fun levelAdviceUsesBalanceAndSlope() {
        // Från rapporten: +1,8 dB i utgångsläget, 0,5 dB per steg (olika upp/ner).
        val adv = Decision.levelAdvice(mapOf(-2 to 1.4, 0 to 1.8, 2 to 3.3), 3.0)!!
        assertEquals(0.475, adv.dbPerStep, 0.01)
        assertEquals(3, adv.step)
        org.junit.Assert.assertNotNull(adv.warning)
        val even = Decision.levelAdvice(mapOf(-2 to -2.0, 0 to 0.0, 2 to 2.0), 3.0)!!
        assertEquals(3, even.step)
        assertNull(even.warning)
        val flat = Decision.levelAdvice(mapOf(-2 to 1.0, 0 to 1.0, 2 to 1.1), 3.0)!!
        assertEquals(0, flat.step)
        org.junit.Assert.assertNotNull(flat.warning)
    }

    @Test
    fun balanceOfFlatCurveIsZero() {
        val flat = DoubleArray(SweepAnalyzer.FREQUENCIES.size) { -30.0 }
        assertEquals(0.0, SweepAnalyzer.balance(flat), 1e-9)
    }
}
