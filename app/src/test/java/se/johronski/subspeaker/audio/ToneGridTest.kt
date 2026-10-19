package se.johronski.subspeaker.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneGridTest {
    @Test
    fun stepsFollowOctaveFractions() {
        var f3 = 20.0
        repeat(3) { f3 = ToneGrid.step(f3, 3, 1) }
        assertEquals(40.0, f3, 0.0)
        // 1/12 oktav från 120 Hz: ≈ 127 och ≈ 113
        val up = ToneGrid.step(ToneGrid.snap(120.0, 12), 12, 1)
        val down = ToneGrid.step(ToneGrid.snap(120.0, 12), 12, -1)
        assertTrue(up > 120 && up < 135)
        assertTrue(down < 120 && down > 105)
        // Upp och ner igen hamnar på samma ställe trots avrundning.
        val f = ToneGrid.snap(117.0, 24)
        assertEquals(f, ToneGrid.step(ToneGrid.step(f, 24, 1), 24, -1), 0.0)
    }

    @Test
    fun aroundStaysInRange() {
        val list = ToneGrid.around(20.0, 12, 6)
        assertTrue(list.first() >= ToneGrid.MIN)
        assertTrue(list.zipWithNext().all { (a, b) -> b > a })
        assertEquals(13, ToneGrid.around(120.0, 24, 6).size)
        assertEquals(400.0, ToneGrid.step(400.0, 3, 1), 0.0)
    }
}
