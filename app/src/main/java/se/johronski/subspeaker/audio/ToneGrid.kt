package se.johronski.subspeaker.audio

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Tonrutnät för dippsökning: frekvenser i jämna bråkdelar av en oktav räknat från 20 Hz,
 * samma rutnät som svepanalysen använder (1/24 oktav).
 */
object ToneGrid {
    const val BASE = 20.0
    const val MIN = 15.0
    const val MAX = 400.0

    /** Antal steg per oktav som går att välja. */
    val DIVISIONS = listOf(3, 6, 12, 24)

    private fun index(f: Double, div: Int): Long = (div * ln(f / BASE) / ln(2.0)).roundToLong()

    private fun at(k: Long, div: Int): Double = round(BASE * 2.0.pow(k.toDouble() / div))

    /** Avrundar: hela Hz från 50 Hz och uppåt, annars en decimal. */
    fun round(f: Double): Double = if (f >= 50.0) Math.round(f).toDouble() else Math.round(f * 10) / 10.0

    /** Närmaste frekvens i rutnätet. */
    fun snap(f: Double, div: Int): Double = at(index(f, div), div).coerceIn(MIN, MAX)

    /** Nästa frekvens uppåt (dir = 1) eller nedåt (dir = -1). */
    fun step(f: Double, div: Int, dir: Int): Double {
        val next = at(index(f, div) + dir, div)
        return if (next < MIN || next > MAX) f else next
    }

    /** Rutnätets frekvenser runt f, count steg åt varje håll. */
    fun around(f: Double, div: Int, count: Int): List<Double> {
        val k = index(f, div)
        return ((k - count)..(k + count)).map { at(it, div) }.filter { it in MIN..MAX }.distinct()
    }

    fun label(div: Int) = "1/$div okt"
}
