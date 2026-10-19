package se.johronski.subspeaker.audio

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Väljer bästa inställning ur jämförande mätningar och anger hur säker skillnaden är. */
object Decision {

    enum class Confidence(val text: String) {
        SURE("säker skillnad"),
        LIKELY("trolig skillnad"),
        UNSURE("ingen säker skillnad"),
    }

    /** Ett alternativ: medelkurvans avvikelse och avvikelsen per mätplats. */
    data class Option(val key: String, val deviation: Double, val perPosition: Map<Int, Double>)

    data class Verdict(val best: Option, val runnerUp: Option, val marginDb: Double, val confidence: Confidence)

    fun compare(options: List<Option>): Verdict? {
        if (options.size < 2) return null
        val sorted = options.sortedBy { it.deviation }
        val a = sorted[0]
        val b = sorted[1]
        val margin = b.deviation - a.deviation
        val common = a.perPosition.keys intersect b.perPosition.keys
        val diffs = common.map { b.perPosition.getValue(it) - a.perPosition.getValue(it) }
        val conf = when {
            diffs.size >= 2 && margin >= 0.5 && diffs.all { it > 0 } -> Confidence.SURE
            margin >= 0.3 && (diffs.isEmpty() || diffs.count { it > 0 } * 2 > diffs.size) -> Confidence.LIKELY
            else -> Confidence.UNSURE
        }
        return Verdict(a, b, margin, conf)
    }

    data class LevelAdvice(
        /** Rekommenderat antal volymsteg från utgångsläget. */
        val step: Int,
        /** Uppskattad ändring av basbalansen per volymsteg (dB). */
        val dbPerStep: Double,
        /** Basbalans i utgångsläget (dB). */
        val balanceNow: Double,
        val warning: String?,
    )

    /**
     * Rekommenderar subnivå utifrån basbalansen (sub mot Genelec) vid olika volymsteg.
     * Ändringen per steg skattas ur mätningarna, och steget som når målbalansen väljs.
     */
    fun levelAdvice(balance: Map<Int, Double>, target: Double): LevelAdvice? {
        val b0 = balance[0] ?: return null
        val others = balance.filterKeys { it != 0 }
        if (others.isEmpty()) return null
        val slope = balance.entries.sumOf { it.key * (it.value - b0) } / balance.keys.sumOf { (it * it).toDouble() }
        // Ändring per två steg uppåt respektive nedåt, så att de kan jämföras.
        val ups = balance.filterKeys { it > 0 }.map { (k, v) -> (v - b0) / k * 2 }
        val downs = balance.filterKeys { it < 0 }.map { (k, v) -> (b0 - v) / -k * 2 }
        val up = ups.takeIf { it.isNotEmpty() }?.average()
        val down = downs.takeIf { it.isNotEmpty() }?.average()
        var warning: String? = null
        if (up != null && down != null && abs(up - down) > 1.0) {
            warning = String.format(
                Locale.ROOT,
                "Upp och ner gav olika stor ändring (+%.1f / −%.1f dB per två steg). " +
                    "Kontrollera på subbens display att alla tryck gick fram, och mät om nivåsteget.",
                up, down,
            )
        }
        if (slope < 0.2) {
            return LevelAdvice(0, slope, b0, "Basnivån ändrades knappt med volymstegen. Kontrollera att trycken på fjärrkontrollen gick fram.")
        }
        val step = ((target - b0) / slope).roundToInt().coerceIn(-6, 6)
        return LevelAdvice(step, slope, b0, warning)
    }
}
