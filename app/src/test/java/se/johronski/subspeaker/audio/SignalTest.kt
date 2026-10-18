package se.johronski.subspeaker.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class SignalTest {

    private fun zeroCrossingFrequency(x: FloatArray, from: Int, to: Int): Double {
        var crossings = 0
        for (i in from + 1 until to) if (x[i - 1] < 0f && x[i] >= 0f) crossings++
        return crossings / ((to - from).toDouble() / SignalGenerator.SAMPLE_RATE)
    }

    @Test
    fun sineHasCorrectFrequencyAndLevel() {
        val x = SignalGenerator.sine(90.0, 2.0, -12.0)
        val f = zeroCrossingFrequency(x, 24_000, 72_000)
        assertEquals(90.0, f, 1.0)
        val peak = x.maxOf { abs(it) }
        assertEquals(SignalGenerator.amplitude(-12.0), peak.toDouble(), 0.01)
    }

    @Test
    fun warbleStaysNearCenter() {
        val x = SignalGenerator.warble(63.0, 4.0, -12.0)
        val f = zeroCrossingFrequency(x, 0, x.size)
        assertEquals(63.0, f, 2.0)
    }

    @Test
    fun sweepGoesDown() {
        val x = SignalGenerator.logSweep(200.0, 20.0, 10.0, -12.0)
        val early = zeroCrossingFrequency(x, 48_000, 96_000)
        val late = zeroCrossingFrequency(x, 384_000, 432_000)
        assertTrue("tidigt $early, sent $late", early > 100 && late < 40)
    }

    @Test
    fun wavHeaderIsValid() {
        val x = SignalGenerator.sine(90.0, 1.0, -12.0)
        val wav = WavEncoder.encodeStereo16(x)
        assertEquals(44 + x.size * 4, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, bb.getShort(22).toInt())
        assertEquals(48_000, bb.getInt(24))
        assertEquals(x.size * 4, bb.getInt(40))
    }

    @Test
    fun bandPassPassesCenterAndRejectsOctaveAway() {
        fun levelThrough(freq: Double): Double {
            val x = SignalGenerator.sine(freq, 2.0, -12.0, fadeMs = 0.0)
            val bp = BandPass(90.0, SignalGenerator.SAMPLE_RATE)
            val y = FloatArray(x.size) { bp.process(x[it].toDouble()).toFloat() }
            return Levels.rmsToDbfs(Levels.rms(y, 48_000, y.size))
        }
        assertEquals(-12.0, levelThrough(90.0), 0.3)
        assertTrue(levelThrough(45.0) < -24.0)
        assertTrue(levelThrough(180.0) < -24.0)
    }

    @Test
    fun parsesAndRejectsPaths() {
        val t = SignalSpec.parse("/tone/warble/31.5/30/-12.wav")
        assertNotNull(t)
        assertEquals("tone/warble/31.5/30/-12.wav", t!!.path)
        assertEquals(SignalSpec.Tone(90.0, false).path, SignalSpec.parse("/" + SignalSpec.Tone(90.0, false).path)!!.path)
        assertNotNull(SignalSpec.parse("sweep/200/20/60/-12.wav"))
        assertNotNull(SignalSpec.parse("steps/warble/-12.wav"))
        assertNull(SignalSpec.parse("tone/sine/90/30/0.wav")) // för hög nivå
        assertNull(SignalSpec.parse("tone/sine/5/30/-12.wav")) // för låg frekvens
        assertNull(SignalSpec.parse("../etc/passwd"))
    }

    @Test
    fun frequencyAtFollowsSignal() {
        val sw = SignalSpec.Sweep(200.0, 20.0, 60.0)
        assertEquals(200.0, sw.frequencyAt(0)!!, 0.01)
        assertEquals(63.2, sw.frequencyAt(30_000)!!, 0.1)
        assertEquals(20.0, sw.frequencyAt(60_000)!!, 0.01)
        assertNull(sw.frequencyAt(61_000))
        val st = SignalSpec.Steps(warble = true)
        assertEquals(200.0, st.frequencyAt(1000)!!, 0.0)
        assertNull(st.frequencyAt(4500))
        assertEquals(160.0, st.frequencyAt(5200)!!, 0.0)
        assertEquals(90.0, SignalSpec.Tone(90.0, false).frequencyAt(10_000)!!, 0.0)
    }
}
