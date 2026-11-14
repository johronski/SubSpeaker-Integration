package se.johronski.subspeaker.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Mått på baskurvan mot en målkurva, 25–200 Hz. Förklaras i appen under "Om SubSpeaker".
 *
 * - Målkurva: mjuk hylla T(f) = G / (1 + (f/105)²). G väljs så att målkurvans basbalans
 *   ([SweepAnalyzer.balance]) blir lika med användarens målbalans.
 * - Felkurva e = L − T, flyttad så att medelvärdet blir 0.
 * - Spridning (SD) och lutning för felkurvan: samma upplägg som Olives modell för hörlurar
 *   (Olive m.fl. 2016/2018), här använt på bas i rum.
 * - NBD: medel av |L − bandets medel| i halvoktavband (Olive 2004), här 25–200 Hz.
 * - Största topp och hål i felkurvan, med bredd.
 * - MSV: medelvärdet över 30–150 Hz av variansen mellan platser (Welti & Devantier 2006).
 *
 * Kurvorna ligger på [SweepAnalyzer.FREQUENCIES] (1/24 oktav, utjämnade till 1/6 oktav).
 */
object BassMetrics {
    const val F_LOW = 25.0
    const val F_HIGH = 200.0
    const val SHELF_HZ = 105.0

    /** Standardvärde när målbalansen inte är känd (samma som i guiden). */
    const val DEFAULT_TARGET_BALANCE = 3.0

    private val F = SweepAnalyzer.FREQUENCIES
    private val BAND = F.indices.filter { F[it] in F_LOW..F_HIGH }

    /** En topp eller ett hål i felkurvan: frekvens, nivå mot målet (dB) och bredd (oktaver). */
    data class Extreme(val freq: Double, val db: Double, val widthOct: Double)

    data class Result(
        /** Spridning (standardavvikelse) för felkurvan, dB. Lägre är jämnare. */
        val sdDb: Double,
        /** Felkurvans lutning, dB per oktav. Negativ = mer djupbas än målet. */
        val slopeDbPerOct: Double,
        /** Narrow band deviation, dB: smala avvikelser inom halvoktavband. */
        val nbdDb: Double,
        val peak: Extreme,
        val dip: Extreme,
        /** Målkurvans hylla, dB. */
        val shelfDb: Double,
    )

    /** Målkurvan (dB) på [SweepAnalyzer.FREQUENCIES] för en hylla på [shelfDb]. */
    fun target(shelfDb: Double): DoubleArray =
        DoubleArray(F.size) { shelfDb / (1.0 + (F[it] / SHELF_HZ).pow(2)) }

    /** Hyllan G (dB) vars målkurva har basbalansen [targetBalance] enligt [SweepAnalyzer.balance]. */
    fun shelfFor(targetBalance: Double): Double {
        var lo = -20.0
        var hi = 40.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (SweepAnalyzer.balance(target(mid)) < targetBalance) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    private val shelfCache = HashMap<Double, Double>()

    private fun shelfCached(targetBalance: Double): Double =
        synchronized(shelfCache) { shelfCache.getOrPut(targetBalance) { shelfFor(targetBalance) } }

    /** Alla mått för en kurva, eller null om kurvan saknar värden i 25–200 Hz. */
    fun analyze(db: DoubleArray, targetBalance: Double = DEFAULT_TARGET_BALANCE): Result? {
        if (db.size < F.size || BAND.any { !db[it].isFinite() }) return null
        val g = shelfCached(targetBalance)
        val t = target(g)
        val raw = BAND.map { db[it] - t[it] }
        val mean = raw.average()
        val e = raw.map { it - mean }
        val freqs = BAND.map { F[it] }

        val sd = sqrt(e.sumOf { it * it } / e.size)

        val x = freqs.map { ln(it) / ln(2.0) }
        val xm = x.average()
        val sxx = x.sumOf { (it - xm) * (it - xm) }
        val slope = x.indices.sumOf { (x[it] - xm) * e[it] } / sxx

        val iPeak = e.indices.maxBy { e[it] }
        val iDip = e.indices.minBy { e[it] }

        return Result(
            sdDb = sd,
            slopeDbPerOct = slope,
            nbdDb = nbd(db),
            peak = Extreme(freqs[iPeak], e[iPeak], width(e, iPeak)),
            dip = Extreme(freqs[iDip], e[iDip], width(e, iDip)),
            shelfDb = g,
        )
    }

    /** Spridningen (SD) mot målkurvan, eller NaN. Ersätter det gamla måttet [SweepAnalyzer.deviation]. */
    fun unevenness(db: DoubleArray, targetBalance: Double = DEFAULT_TARGET_BALANCE): Double =
        analyze(db, targetBalance)?.sdDb ?: Double.NaN

    /**
     * Narrow band deviation (dB): för varje halvoktavband från 25 Hz upp till 200 Hz,
     * medelvärdet av |L − bandets medelnivå|; sedan medel över banden.
     */
    fun nbd(db: DoubleArray): Double {
        val devs = mutableListOf<Double>()
        var lo = F_LOW
        while (lo * 2.0.pow(0.5) <= F_HIGH * 1.0001) {
            val hi = lo * 2.0.pow(0.5)
            val last = hi >= F_HIGH * 0.9999
            val v = F.indices.filter { F[it] >= lo && (if (last) F[it] <= F_HIGH else F[it] < hi) }.map { db[it] }
            if (v.isNotEmpty()) {
                val m = v.average()
                devs += v.sumOf { abs(it - m) } / v.size
            }
            lo = hi
        }
        return devs.average()
    }

    /** Bredd (oktaver): sammanhängande punkter runt [i] där e är mer än halva värdet i [i]. */
    private fun width(e: List<Double>, i: Int): Double {
        val half = e[i] / 2
        fun beyond(v: Double) = if (e[i] >= 0) v > half else v < half
        var a = i
        while (a > 0 && beyond(e[a - 1])) a--
        var b = i
        while (b < e.size - 1 && beyond(e[b + 1])) b++
        return (b - a + 1) / 24.0
    }

    /**
     * Mean spatial variance (dB²) mellan platser: varje kurva flyttas först så att medel 30–200 Hz blir 0,
     * sedan tas variansen mellan kurvorna per frekvens och medelvärdet över 30–150 Hz.
     * Null om färre än två kurvor har värden i området.
     */
    fun msv(curves: List<DoubleArray>): Double? {
        val norm = F.indices.filter { F[it] in 30.0..200.0 }
        val range = F.indices.filter { F[it] in 30.0..150.0 }
        val ok = curves.filter { c -> c.size >= F.size && norm.all { c[it].isFinite() } }
        if (ok.size < 2) return null
        val shifted = ok.map { c ->
            val m = norm.map { c[it] }.average()
            DoubleArray(c.size) { c[it] - m }
        }
        return range.map { i ->
            val v = shifted.map { it[i] }
            val m = v.average()
            v.sumOf { (it - m) * (it - m) } / v.size
        }.average()
    }
}
