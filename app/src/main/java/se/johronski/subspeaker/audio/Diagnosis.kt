package se.johronski.subspeaker.audio

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Ställer diagnos ur hälsokollens tre mätningar: allt tillsammans, Genelec ensam (MUTE på subben)
 * och, om den gjorts, sub ensam. Alla kurvor ligger på [SweepAnalyzer.FREQUENCIES].
 */
object Diagnosis {

    enum class Action { PHASE, LOWPASS, LEVEL }

    data class Finding(
        val key: String,
        val title: String,
        val detail: String,
        /** Åtgärd som kan lösa det, eller null om inställningar inte hjälper. */
        val action: Action?,
        /** 3 = störst påverkan, 0 = bara information. */
        val impact: Int,
        val freq: Double? = null,
        /** Förklaring som visas under "Varför?". */
        val why: String? = null,
    )

    data class Report(
        val findings: List<Finding>,
        /** Bra saker som inte behöver åtgärdas. */
        val good: List<String>,
        /** Jämnhet (dB, lägre är bättre) för allt tillsammans. */
        val unevenDb: Double,
        val balanceDb: Double,
        /** Frekvensen där sub och Genelec möts, om sub ensam är mätt. */
        val crossoverHz: Double?,
    ) {
        val actionable: List<Finding> get() = findings.filter { it.action != null }

        val headline: String
            get() = when (actionable.size) {
                0 -> "Basen sitter ihop"
                1 -> "Bra grund – en sak kan bli bättre"
                2 -> "Bra grund – två saker kan bli bättre"
                else -> "${actionable.size} saker kan bli bättre"
            }
    }

    private val F = SweepAnalyzer.FREQUENCIES

    private fun idx(lo: Double, hi: Double) = F.indices.filter { F[it] in lo..hi }

    private fun fmtHz(f: Double) = "${(f / 5).roundToInt() * 5} Hz"

    private fun pow10(db: Double) = 10.0.pow(db / 10.0)

    /** Löpande medelvärde (i dB) över ±[halfOct] oktav, som jämförelse för smala dippar. */
    fun trend(db: DoubleArray, halfOct: Double = 0.25): DoubleArray = DoubleArray(db.size) { i ->
        val lo = F[i] * 2.0.pow(-halfOct)
        val hi = F[i] * 2.0.pow(halfOct)
        val v = F.indices.filter { F[it] in lo..hi }.map { db[it] }
        v.average()
    }

    /** Djupaste smala dippen i [lo, hi] mot sin omgivning: (frekvens, djup i dB, negativt). */
    fun deepestDip(db: DoubleArray, lo: Double, hi: Double): Pair<Double, Double>? {
        val t = trend(db)
        return idx(lo, hi).map { F[it] to db[it] - t[it] }.minByOrNull { it.second }
    }

    /** Där sub och Genelec möts: första frekvensen över 40 Hz där Genelecen blir starkare än subben. */
    fun crossover(sub: DoubleArray, gen: DoubleArray): Double? {
        val r = idx(40.0, 250.0)
        for (j in 1 until r.size) {
            val a = sub[r[j - 1]] - gen[r[j - 1]]
            val b = sub[r[j]] - gen[r[j]]
            if (a >= 0 && b < 0) {
                val t = a / (a - b)
                return F[r[j - 1]] * (F[r[j]] / F[r[j - 1]]).pow(t)
            }
        }
        return null
    }

    fun diagnose(together: DoubleArray, genelec: DoubleArray, sub: DoubleArray?, targetBalance: Double): Report {
        val findings = mutableListOf<Finding>()
        val good = mutableListOf<String>()
        val uneven = SweepAnalyzer.deviation(together)
        val balance = SweepAnalyzer.balance(together)

        // 1. Utsläckning: med subben på blir det svagare än med Genelec ensam,
        //    eller (med sub ensam mätt) klart svagare än effektsumman av dem.
        val region = idx(45.0, 160.0)
        val worst = region.minByOrNull { together[it] - genelec[it] }
        val worstDrop = worst?.let { together[it] - genelec[it] } ?: 0.0
        var lossDb = 0.0
        if (sub != null) {
            val overlap = region.filter { abs(sub[it] - genelec[it]) < 10.0 }
            if (overlap.isNotEmpty()) {
                lossDb = overlap.map { i ->
                    10.0 * log10(pow10(sub[i]) + pow10(genelec[i])) - together[i]
                }.average()
            }
        }
        if (worst != null && (worstDrop < -3.0 || lossDb > 2.5)) {
            val f = F[worst]
            findings += Finding(
                key = "cancel",
                title = "Sub och Genelec tar ut varandra runt ${fmtHz(f)}",
                detail = if (worstDrop < -3.0) {
                    String.format(Locale.ROOT, "Med subben på blir det %.0f dB svagare där än med Genelec ensam. Löses troligen med fasen.", -worstDrop)
                } else {
                    "De spelar delvis i otakt vid övergången. Löses troligen med fasen."
                },
                action = Action.PHASE,
                impact = 3,
                freq = f,
                why = "Subben står längre bort och dess filter fördröjer ljudet. När ljudet från sub och Genelec " +
                    "kommer i otakt vid samma frekvens tar de delvis ut varandra. Fasomkopplaren förskjuter subben i tid.",
            )
        }

        // 2. Nivå: subbens område mot Genelecens.
        val diff = balance - targetBalance
        if (abs(diff) > 2.0) {
            findings += Finding(
                key = "level",
                title = if (diff > 0) {
                    String.format(Locale.ROOT, "Djupbasen är %.0f dB för stark", diff)
                } else {
                    String.format(Locale.ROOT, "Djupbasen är %.0f dB för svag", -diff)
                },
                detail = if (diff > 0) "Sänk subbens volym med fjärrkontrollen." else "Höj subbens volym med fjärrkontrollen.",
                action = Action.LEVEL,
                impact = 2,
            )
        } else {
            good += "Djupbasen är lagom stark"
        }

        // 3. Lågpass: var sub och Genelec möts.
        val xo = sub?.let { crossover(it, genelec) }
        if (xo != null) {
            when {
                xo > 115.0 -> findings += Finding(
                    key = "lowpass",
                    title = "Subben spelar för högt upp",
                    detail = "Sub och Genelec möts först vid ${fmtHz(xo)}. Vrid ner lågpassratten på subben.",
                    action = Action.LOWPASS,
                    impact = 2,
                    freq = xo,
                    why = "När subben spelar högt upp hörs det var den står, och den överlappar Genelecen i ett brett område där de lätt tar ut varandra.",
                )
                xo < 60.0 -> findings += Finding(
                    key = "lowpass",
                    title = "Glapp mellan sub och Genelec",
                    detail = "De möts redan vid ${fmtHz(xo)}. Vrid upp lågpassratten på subben.",
                    action = Action.LOWPASS,
                    impact = 2,
                    freq = xo,
                )
                else -> good += "Sub och Genelec möts vid ${fmtHz(xo)}"
            }
        }

        // 4. Hål i Genelecens område som finns även med Genelec ensam: rummet.
        deepestDip(genelec, 95.0, 280.0)?.takeIf { it.second < -6.0 }?.let { (f, depth) ->
            findings += Finding(
                key = "room",
                title = "Hål vid ${fmtHz(f)} – från rummet",
                detail = "Finns även med Genelec ensam. Inställningar på subben hjälper inte.",
                action = null,
                impact = 0,
                freq = f,
                why = String.format(
                    Locale.ROOT,
                    "Hålet är %.0f dB djupt och finns när bara Genelecerna spelar. Det beror på hur ljudet studsar mellan väggar, " +
                        "golv och tak. Det kan bli mindre om högtalarna eller lyssningsplatsen flyttas en bit, eller med akustikdämpning.",
                    -depth,
                ),
            )
        }

        // 5. Svacka i subbens område när subben spelar ensam: subbens placering.
        sub?.let { s ->
            deepestDip(s, 25.0, 75.0)?.takeIf { it.second < -8.0 }?.let { (f, _) ->
                findings += Finding(
                    key = "subroom",
                    title = "Svacka vid ${fmtHz(f)} i djupbasen",
                    detail = "Beror på var subben står i rummet. Velodynens egen rumskorrigering kan minska den.",
                    action = null,
                    impact = 0,
                    freq = f,
                )
            }
        }

        return Report(findings.sortedByDescending { it.impact }, good, uneven, balance, xo)
    }

    // ---------- Stämapparaten ----------

    /** Visarens läge 0–1 ur jämnheten (dB): 5 dB eller mer = 0, 1,5 dB eller mindre = 1. */
    fun gauge(unevenDb: Double): Double = ((5.0 - unevenDb) / 3.5).coerceIn(0.0, 1.0)

    fun gaugeWord(unevenDb: Double): String = when {
        unevenDb < 2.5 -> "Jämnt"
        unevenDb < 3.8 -> "OK"
        else -> "Ojämnt"
    }
}
