package se.johronski.subspeaker.audio

import kotlin.math.ln

/**
 * Kalibreringsfil för en mätmikrofon (miniDSP UMIK-1/UMIK-2, Dayton iMM-6C m.fl.).
 *
 * Formatet är det REW använder: rader med frekvens (Hz) och avvikelse (dB), åtskilda med blanksteg,
 * tabb, komma eller semikolon. Eventuell fas i tredje kolumnen ignoreras. Rader som inte börjar med
 * ett tal (t.ex. `"Sens Factor =-1.378dB, SERNO: 7001234"` eller `* kommentar`) hoppas över,
 * men känslighet och serienummer plockas ut om de finns.
 *
 * Värdena är mikrofonens egen avvikelse, så korrigeringen är att **dra bort** dem från det uppmätta.
 */
class Calibration(
    val name: String,
    val freqs: DoubleArray,
    val db: DoubleArray,
    val serial: String? = null,
    val sensDb: Double? = null,
) {
    /** Avvikelse vid [f], linjärt interpolerad i log-frekvens. Utanför filens område används ändvärdet. */
    fun at(f: Double): Double {
        if (f <= freqs.first()) return db.first()
        if (f >= freqs.last()) return db.last()
        var lo = 0
        var hi = freqs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (freqs[mid] <= f) lo = mid else hi = mid
        }
        val t = (ln(f) - ln(freqs[lo])) / (ln(freqs[hi]) - ln(freqs[lo]))
        return db[lo] + t * (db[hi] - db[lo])
    }

    /** Korrektionen på [SweepAnalyzer.FREQUENCIES]. */
    val onGrid: DoubleArray by lazy { DoubleArray(SweepAnalyzer.FREQUENCIES.size) { at(SweepAnalyzer.FREQUENCIES[it]) } }

    /** Största och minsta avvikelse inom kurvans område, för att visa hur mycket filen ändrar. */
    val rangeInBand: Pair<Double, Double> get() = onGrid.min() to onGrid.max()

    /** true om filen täcker hela kurvans område. */
    val coversBand: Boolean get() = freqs.first() <= 20.5 && freqs.last() >= SweepAnalyzer.FREQUENCIES.last()

    /** Kort beskrivning, t.ex. "7001234_90deg.txt, serienr 7001234 (−0,4 … +0,2 dB i 20–600 Hz)". */
    fun describe(): String {
        val (lo, hi) = rangeInBand
        return buildString {
            append(name)
            serial?.let { append(", serienr $it") }
            append(String.format(java.util.Locale.ROOT, " (%+.1f … %+.1f dB i 20–%d Hz)", lo, hi, SweepAnalyzer.F_TOP.toInt()))
        }
    }

    companion object {
        private val number = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")
        private val sensRegex = Regex("""Sens\s*Factor\s*=\s*([+-]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val serialRegex = Regex("""SERNO\s*:\s*([\w-]+)""", RegexOption.IGNORE_CASE)

        /** Läser en kalibreringsfil. Kastar [IllegalArgumentException] med en begriplig förklaring om den inte går att använda. */
        fun parse(text: String, name: String): Calibration {
            val pts = sortedMapOf<Double, Double>()
            var sens: Double? = null
            var serial: String? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim().trimStart('﻿')
                if (line.isEmpty()) continue
                val parts = line.split(Regex("""[\s,;]+""")).filter { it.isNotEmpty() }
                val f = parts.getOrNull(0)?.takeIf { number.matches(it) }?.toDouble()
                val d = parts.getOrNull(1)?.takeIf { number.matches(it) }?.toDouble()
                if (f != null && d != null) {
                    if (f > 0 && d.isFinite() && f.isFinite()) pts[f] = d
                } else {
                    sensRegex.find(line)?.let { sens = it.groupValues[1].toDoubleOrNull() }
                    serialRegex.find(line)?.let { serial = it.groupValues[1] }
                }
            }
            require(pts.size >= 2) { "Filen innehåller inga rader med frekvens och dB. Välj kalibreringsfilen (.txt) för din mikrofon." }
            val freqs = pts.keys.toDoubleArray()
            val db = pts.values.toDoubleArray()
            require(freqs.last() >= 100.0 && freqs.first() <= 100.0) {
                "Filen täcker inte basområdet (den går från ${freqs.first().toInt()} till ${freqs.last().toInt()} Hz)."
            }
            require(db.all { it in -40.0..40.0 }) { "Filen har orimligt stora värden (över ±40 dB). Är det rätt fil?" }
            return Calibration(name, freqs, db, serial, sens)
        }
    }
}

/**
 * Kalibreringen som gäller just nu. Sätts av appen när en fil är inläst **och** en extern mikrofon används,
 * så att den inbyggda mikrofonen aldrig korrigeras med en annan mikrofons fil. null = ingen korrigering.
 */
object MicCalibration {
    @Volatile
    var active: Calibration? = null

    /** Drar bort mikrofonens avvikelse från en kurva på [SweepAnalyzer.FREQUENCIES] (på plats) och returnerar den. */
    fun apply(curve: DoubleArray): DoubleArray {
        val c = active ?: return curve
        if (curve.size != SweepAnalyzer.FREQUENCIES.size) return curve
        val k = c.onGrid
        for (i in curve.indices) curve[i] -= k[i]
        return curve
    }
}
