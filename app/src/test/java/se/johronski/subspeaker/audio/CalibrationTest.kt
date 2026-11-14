package se.johronski.subspeaker.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {

    /** Utdrag i samma form som miniDSP:s filer för UMIK-1 (påhittade värden). */
    private val umik = """
        "Sens Factor =-1.378dB, AGain =18dB, SERNO: 7001234"
        10.054	-2.10
        20.000	-0.60
        40.000	-0.20
        80.000	0.00
        160.000	0.10
        320.000	0.05
        1000.0	0.00
        20000.0	-1.50
    """.trimIndent()

    @Test
    fun parsesUmikHeaderAndPoints() {
        val c = Calibration.parse(umik, "7001234_90deg.txt")
        assertEquals("7001234", c.serial)
        assertEquals(-1.378, c.sensDb!!, 1e-9)
        assertEquals(8, c.freqs.size)
        assertTrue(c.coversBand)
    }

    @Test
    fun interpolatesInLogFrequency() {
        val c = Calibration.parse(umik, "x")
        assertEquals(-0.6, c.at(20.0), 1e-9)
        // Mitt emellan 20 och 40 Hz i log-skala är √800 ≈ 28,28 Hz.
        assertEquals(-0.4, c.at(Math.sqrt(800.0)), 1e-9)
        // Utanför filen: ändvärdet.
        assertEquals(-2.1, c.at(5.0), 1e-9)
    }

    @Test
    fun acceptsCommaSemicolonCommentsAndPhaseColumn() {
        val text = "* REW-export\n﻿20,1.0,12.5\n200;3.0;0\n\nnågot annat\n"
        val c = Calibration.parse(text, "x")
        assertArrayEquals(doubleArrayOf(20.0, 200.0), c.freqs, 1e-9)
        assertArrayEquals(doubleArrayOf(1.0, 3.0), c.db, 1e-9)
        assertNull(c.serial)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFileWithoutData() {
        Calibration.parse("hej\npå dig\n", "x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFileWithoutBass() {
        Calibration.parse("1000 0\n2000 1\n", "x")
    }

    @Test
    fun applySubtractsOnlyWhenActive() {
        val curve = DoubleArray(SweepAnalyzer.FREQUENCIES.size) { 0.0 }
        MicCalibration.active = null
        MicCalibration.apply(curve)
        assertTrue(curve.all { it == 0.0 })

        val c = Calibration.parse("10 2.0\n1000 2.0\n", "x")
        try {
            MicCalibration.active = c
            MicCalibration.apply(curve)
            assertTrue(curve.all { Math.abs(it + 2.0) < 1e-9 })
        } finally {
            MicCalibration.active = null
        }
    }
}
