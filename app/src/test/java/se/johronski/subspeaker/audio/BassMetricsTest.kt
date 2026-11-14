package se.johronski.subspeaker.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class BassMetricsTest {

    private val F = SweepAnalyzer.FREQUENCIES

    /** Kurva från exporten 2026-11-14 18:54 (UMIK-1), 20–305 Hz, resten saknas. */
    private fun curve(s: String): DoubleArray {
        val v = s.split(",").map { it.toDouble() }
        return DoubleArray(F.size) { if (it < v.size) v[it] else Double.NaN }
    }

    private val LAMPETTE = curve("-45.711,-43.528,-42.596,-43.203,-43.459,-44.100,-44.806,-44.992,-45.658,-46.529,-47.848,-49.249,-50.005,-49.892,-47.882,-45.716,-44.251,-41.925,-41.191,-40.367,-40.267,-41.225,-42.133,-45.742,-47.828,-47.310,-44.466,-41.383,-40.590,-40.222,-40.967,-43.588,-45.441,-45.155,-44.008,-43.288,-43.029,-44.091,-45.677,-47.120,-47.617,-47.431,-46.529,-45.506,-44.559,-43.248,-42.486,-42.317,-42.840,-44.647,-46.890,-50.215,-52.998,-51.364,-48.381,-46.835,-46.575,-46.721,-47.538,-48.495,-49.462,-51.407,-53.599,-53.770,-52.698,-51.932,-51.295,-50.639,-49.126,-47.466,-45.987,-45.498,-46.304,-47.849,-50.820,-53.691,-54.304,-52.973,-50.194,-49.299,-48.809,-47.512,-47.291,-46.282,-44.371,-43.874,-42.633,-41.125,-41.645,-42.415,-43.535,-44.663,-45.113,-42.961,-41.665")
    private val PIANO_L = curve("-41.036,-39.597,-39.365,-40.023,-39.743,-39.823,-40.212,-40.467,-41.574,-43.026,-43.404,-42.553,-40.720,-39.877,-39.065,-38.678,-38.996,-39.286,-40.074,-41.672,-42.814,-45.257,-46.282,-46.392,-45.533,-43.185,-41.893,-41.138,-41.149,-41.768,-42.734,-43.209,-42.919,-42.580,-42.800,-43.282,-43.525,-43.338,-43.425,-44.529,-45.664,-49.381,-51.430,-51.691,-51.357,-49.907,-48.534,-47.337,-47.254,-48.542,-50.124,-52.070,-51.903,-48.844,-47.158,-46.381,-46.442,-47.466,-48.783,-49.609,-50.292,-50.926,-51.359,-52.212,-51.335,-46.331,-44.000,-42.525,-41.996,-42.638,-44.031,-45.971,-48.720,-51.719,-52.033,-50.709,-47.715,-45.588,-43.969,-43.324,-44.189,-45.192,-43.098,-41.831,-40.997,-41.069,-42.680,-44.451,-47.086,-47.054,-47.604,-45.306,-41.467,-40.478,-40.593")
    private val PIANO = curve("-40.306,-39.131,-38.941,-39.551,-39.280,-39.123,-39.209,-39.192,-39.579,-40.100,-40.385,-40.625,-40.426,-40.689,-40.342,-39.973,-40.152,-40.240,-40.704,-41.637,-42.212,-43.171,-43.456,-43.402,-43.225,-42.309,-41.390,-40.797,-40.858,-41.627,-43.084,-44.769,-44.676,-44.243,-44.294,-44.228,-43.672,-42.039,-41.690,-41.614,-41.820,-43.143,-43.677,-44.739,-46.265,-48.749,-51.431,-53.124,-52.378,-51.247,-50.791,-50.777,-50.481,-50.153,-50.186,-49.682,-48.966,-48.099,-47.691,-47.739,-48.192,-49.026,-49.369,-49.667,-49.390,-46.700,-45.319,-44.869,-44.659,-44.777,-45.289,-45.103,-45.177,-46.411,-47.408,-47.927,-48.156,-47.679,-43.669,-42.423,-41.873,-39.544,-37.470,-37.260,-37.459,-38.256,-40.965,-42.209,-42.347,-43.530,-44.769,-44.589,-43.256,-43.224,-42.918")
    private val PIANO_R = curve("-41.675,-39.942,-39.392,-40.210,-39.925,-39.809,-39.900,-39.920,-40.669,-41.946,-42.993,-43.679,-43.363,-42.769,-42.348,-42.001,-42.130,-42.275,-42.441,-42.703,-42.583,-42.125,-41.781,-41.096,-40.974,-40.635,-40.248,-39.889,-40.056,-41.007,-42.697,-45.748,-46.797,-47.080,-47.207,-46.654,-44.973,-42.017,-41.244,-40.446,-40.339,-41.380,-42.045,-43.519,-44.916,-46.683,-48.661,-51.282,-52.826,-51.827,-50.245,-48.732,-47.796,-47.429,-47.764,-48.471,-49.116,-49.376,-49.623,-50.086,-51.087,-52.571,-52.029,-50.866,-49.778,-48.283,-48.071,-48.840,-49.331,-48.718,-47.895,-47.208,-46.853,-47.192,-48.297,-48.065,-47.505,-46.959,-46.276,-46.578,-47.199,-47.537,-47.435,-45.858,-44.097,-44.117,-44.218,-44.392,-45.939,-45.122,-45.299,-44.967,-43.422,-42.831,-43.002")
    private val SOFA = curve("-41.520,-39.724,-38.552,-38.242,-38.086,-38.111,-38.624,-38.973,-39.712,-39.959,-39.384,-38.278,-36.696,-36.494,-36.396,-36.443,-37.199,-37.512,-37.668,-38.057,-38.487,-39.930,-40.818,-43.172,-43.602,-42.173,-40.656,-38.601,-37.903,-37.461,-37.730,-39.039,-39.990,-40.973,-41.802,-42.815,-44.318,-47.687,-49.262,-48.677,-48.128,-48.027,-48.200,-48.699,-48.919,-49.487,-50.353,-51.783,-53.491,-54.017,-53.214,-52.257,-51.871,-51.824,-52.063,-53.251,-53.842,-54.011,-53.917,-53.001,-53.164,-53.821,-54.957,-56.182,-55.787,-53.702,-53.064,-53.375,-54.091,-53.726,-52.270,-51.462,-50.825,-50.926,-51.721,-52.408,-52.955,-53.236,-54.217,-54.337,-53.557,-53.543,-52.687,-51.067,-51.662,-51.656,-50.866,-51.258,-51.474,-51.448,-50.874,-49.454,-48.879,-48.859,-49.300")

    @Test
    fun shelfMatchesTargetBalance() {
        val g = BassMetrics.shelfFor(3.0)
        assertEquals(5.543, g, 0.01)
        assertEquals(3.0, SweepAnalyzer.balance(BassMetrics.target(g)), 1e-6)
        assertEquals(0.0, BassMetrics.shelfFor(0.0), 1e-6)
    }

    @Test
    fun targetCurveScoresZero() {
        val t = BassMetrics.target(BassMetrics.shelfFor(3.0))
        val r = BassMetrics.analyze(DoubleArray(F.size) { t[it] - 40.0 }, 3.0)!!
        assertEquals(0.0, r.sdDb, 1e-9)
        assertEquals(0.0, r.slopeDbPerOct, 1e-9)
    }

    @Test
    fun tiltIsSeenAsSlope() {
        // Den gamla ojämnheten kunde inte se en jämn lutning. Den nya ska se den.
        val t = BassMetrics.target(BassMetrics.shelfFor(3.0))
        val tilted = DoubleArray(F.size) { t[it] - 3.0 * ln(F[it] / 70.0) / ln(2.0) }
        val r = BassMetrics.analyze(tilted, 3.0)!!
        assertEquals(-3.0, r.slopeDbPerOct, 1e-6)
        assertTrue(r.sdDb > 2.5)
        val line = DoubleArray(F.size) { -3.0 * ln(F[it] / 70.0) / ln(2.0) }
        assertTrue(SweepAnalyzer.deviation(line) < 0.01)
    }

    @Test
    fun narrowDipRaisesNbdAndIsFound() {
        val t = BassMetrics.target(BassMetrics.shelfFor(3.0))
        val dipped = t.copyOf()
        val i = F.indices.minBy { abs(F[it] - 85.0) }
        dipped[i] -= 10.0
        dipped[i - 1] -= 3.0
        dipped[i + 1] -= 3.0
        val a = BassMetrics.analyze(t, 3.0)!!
        val b = BassMetrics.analyze(dipped, 3.0)!!
        assertTrue(b.nbdDb > a.nbdDb + 0.2)
        assertEquals(F[i], b.dip.freq, 1e-9)
        assertEquals(1.0 / 24.0, b.dip.widthOct, 1e-9)
    }

    @Test
    fun missingValuesGiveNull() {
        val c = PIANO.copyOf()
        c[30] = Double.NaN
        assertNull(BassMetrics.analyze(c))
        assertTrue(BassMetrics.unevenness(c).isNaN())
    }

    /** Värdena är räknade med en oberoende Python-version av samma formler. */
    @Test
    fun realMeasurementsMatchReference() {
        val p = BassMetrics.analyze(PIANO, 3.0)!!
        assertEquals(3.0540, p.sdDb, 0.001)
        assertEquals(-1.2969, p.slopeDbPerOct, 0.001)
        assertEquals(1.0475, p.nbdDb, 0.001)
        assertEquals(77.72, p.dip.freq, 0.01)
        assertEquals(-8.0628, p.dip.db, 0.001)
        assertEquals(0.375, p.dip.widthOct, 1e-9)

        val s = BassMetrics.analyze(SOFA, 3.0)!!
        assertEquals(5.5358, s.sdDb, 0.001)
        assertEquals(-5.4517, s.slopeDbPerOct, 0.001)
        assertEquals(1.4946, s.nbdDb, 0.001)
        assertEquals(29.97, s.peak.freq, 0.01)
        assertEquals(9.0861, s.peak.db, 0.001)
        assertEquals(0.625, s.peak.widthOct, 1e-9)

        // Soffan: den gamla ojämnheten såg den som jämn, den nya som klart ojämnast.
        assertTrue(SweepAnalyzer.deviation(SOFA) < SweepAnalyzer.deviation(PIANO))
        assertTrue(s.sdDb > p.sdDb + 2.0)
    }

    @Test
    fun referenceFollowsCurveLevel() {
        val ref = BassMetrics.reference(PIANO, 3.0)!!
        val band = F.indices.filter { F[it] in 25.0..200.0 }
        // Medel av kurva minus referens är 0 i området, och ojämnheten är spridningen runt referensen.
        assertEquals(0.0, band.map { PIANO[it] - ref[it] }.average(), 1e-9)
        val sd = kotlin.math.sqrt(band.map { (PIANO[it] - ref[it]).let { d -> d * d } }.average())
        assertEquals(BassMetrics.analyze(PIANO, 3.0)!!.sdDb, sd, 1e-9)
        assertTrue(ref[0].isNaN())
        assertTrue(ref[F.indices.first { F[it] > 210.0 }].isNaN())
    }

    @Test
    fun spatialVariance() {
        val all = listOf(LAMPETTE, PIANO_L, PIANO, PIANO_R, SOFA)
        assertEquals(5.859, BassMetrics.msv(all)!!, 0.001)
        // Samma kurva på olika nivå är samma svar.
        assertEquals(0.0, BassMetrics.msv(listOf(PIANO, DoubleArray(F.size) { PIANO[it] + 6.0 }))!!, 1e-9)
        assertNull(BassMetrics.msv(listOf(PIANO)))
        assertNotNull(BassMetrics.msv(listOf(PIANO, SOFA)))
    }
}
