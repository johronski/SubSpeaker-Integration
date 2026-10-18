package se.johronski.subspeaker.audio

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

    /**
     * Bästa volymsteg utifrån avvikelsen vid stegen -2, 0 och +2.
     * Anpassar en parabel och avrundar till helt steg inom ±3. Små skillnader ger 0.
     */
    fun bestLevelStep(m: Map<Int, Double>): Int? {
        val mm = m[-2] ?: return null
        val m0 = m[0] ?: return null
        val mp = m[2] ?: return null
        val all = listOf(mm, m0, mp)
        if ((all.max() - all.min()) < 0.3) return 0
        val a = (mp + mm - 2 * m0) / 8.0
        val b = (mp - mm) / 4.0
        if (a <= 0.01) {
            return listOf(-2 to mm, 0 to m0, 2 to mp).minBy { it.second }.first
        }
        return (-b / (2 * a)).coerceIn(-3.0, 3.0).roundToInt()
    }
}
