package se.johronski.subspeaker.audio

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Levande frekvenskurva med exponentiellt medelvärde, som i en realtidsanalysator.
 *
 * Varje uppdatering använder de senaste [Multitone.FRAME] sampel (en period av mätljudet, 1,37 s).
 * Eftersom mätljudet är periodiskt kan fönstret börja var som helst, så kurvan kan räknas om
 * flera gånger per sekund. Äldre värden tonar bort med tidskonstanten [tauSeconds].
 * Fönster som ligger i ett glapp eller medan röstguiden talar hoppas över.
 */
class LiveSpectrum(var tauSeconds: Double = 4.0) {

    /** true = utan mätljud: visar vad mikrofonen hör (t.ex. musik) i stället för högtalarnas svar. */
    var raw: Boolean = false

    private var avg: DoubleArray? = null
    private var level = 0.0
    private var lastCurve: DoubleArray? = null
    private var change = 10.0

    /** Senaste kurvan, eller null innan något mätljud hörts. */
    var curve: DoubleArray? = null
        private set

    /** true när kurvan slutat ändra sig nämnvärt. */
    val stable: Boolean get() = curve != null && change < 0.3

    fun reset() {
        avg = null
        level = 0.0
        lastCurve = null
        curve = null
        change = 10.0
    }

    /**
     * Uppdaterar med slutet av en inspelning. [tail] ska innehålla minst [Multitone.FRAME] sampel plus
     * några hundra för högpassfiltrets insvängning. [dtSeconds] är tiden sedan förra uppdateringen.
     * [skip] = true när fönstret överlappar tal eller annat som ska bortses från.
     */
    fun update(tail: FloatArray, dtSeconds: Double, skip: Boolean = false): Boolean {
        if (skip || tail.size < Multitone.FRAME + 300) return false
        val x = Multitone.highPass(tail, 0)
        val p = if (raw) Multitone.powerHann(x, x.size - Multitone.FRAME) else Multitone.power(x, x.size - Multitone.FRAME)
        var e = 0.0
        for (k in Multitone.toneBins) e += p[k]
        if (e <= 0.0) return false
        // Glapp (Chromecasten startar om): nästan tyst jämfört med nivån hittills. MUTE och nivåändringar är
        // betydligt mindre. Utan mätljud finns inga glapp att sortera bort.
        if (!raw && level > 0 && e < level * 0.05) return false
        level = if (level == 0.0) e else level + 0.2 * (e - level)

        val a = avg
        if (a == null) {
            avg = p.copyOf()
        } else {
            val alpha = 1.0 - exp(-dtSeconds / tauSeconds.coerceAtLeast(0.1))
            for (k in Multitone.toneBins) a[k] += alpha * (p[k] - a[k])
        }
        val c = if (raw) Multitone.rawCurve(avg!!) else Multitone.curve(avg!!)
        lastCurve?.let { prev ->
            val idx = SweepAnalyzer.FREQUENCIES.indices.filter { SweepAnalyzer.FREQUENCIES[it] in 30.0..200.0 }
            val d = sqrt(idx.sumOf { (c[it] - prev[it]) * (c[it] - prev[it]) } / idx.size)
            // Ändring per sekund, utjämnad.
            val rate = d / dtSeconds.coerceAtLeast(0.05)
            change += 0.3 * (rate - change)
        }
        lastCurve = c
        curve = c
        return true
    }

    /** Medelvärdets tidskonstant. */
    enum class Speed(val label: String, val tau: Double) {
        FAST("Snabb", 1.5),
        NORMAL("Normal", 4.0),
        SLOW("Lugn", 12.0),
    }
}
