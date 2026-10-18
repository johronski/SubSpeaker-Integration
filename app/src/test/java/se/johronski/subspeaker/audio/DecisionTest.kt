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
    fun levelStep() {
        assertEquals(0, Decision.bestLevelStep(mapOf(-2 to 2.0, 0 to 1.0, 2 to 2.0)))
        assertEquals(2, Decision.bestLevelStep(mapOf(-2 to 3.0, 0 to 2.0, 2 to 1.0)))
        assertEquals(1, Decision.bestLevelStep(mapOf(-2 to 3.0, 0 to 1.2, 2 to 1.0)))
        assertEquals(0, Decision.bestLevelStep(mapOf(-2 to 1.1, 0 to 1.0, 2 to 1.2)))
    }
}
