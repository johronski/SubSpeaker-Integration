package se.johronski.subspeaker.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Periodiskt rosa brus för mätning med rörlig mikrofon.
 *
 * Signalen är en multiton: en sinus på varje FFT-bin mellan [F_LO] och [F_HI], med amplitud
 * 1/√f (rosa) och slumpad men fast fas. Den upprepas exakt med perioden [PERIOD] sampel.
 * Varje inspelad ram med samma längd innehåller då alla toner utan läckage, oavsett var
 * ramen börjar, så Chromecastens okända fördröjning spelar ingen roll. Medelvärdet över
 * många ramar, medan telefonen rörs runt huvudet, ger ett rumsligt medelvärde.
 */
object Multitone {
    /** Period vid 48 kHz: 65 536 sampel ≈ 1,37 s. */
    const val PERIOD = 65_536

    /** Samma period efter nedsampling till [SweepAnalyzer.FS]. */
    const val FRAME = PERIOD / SweepAnalyzer.DECIMATION

    const val F_LO = 18.0
    const val F_HI = 350.0
    const val PEAK_DBFS = -4.0

    val binHz: Double = SignalGenerator.SAMPLE_RATE.toDouble() / PERIOD

    /** Bin-index (gemensamma för 48 kHz och nedsamplat) som innehåller toner. */
    val toneBins: IntRange = (kotlin.math.ceil(F_LO / binHz).toInt())..((F_HI / binHz).toInt())

    /** En period av signalen vid 48 kHz. */
    val period: FloatArray by lazy { generate() }

    private fun generate(): FloatArray {
        val n = PERIOD
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val rnd = Random(20261020)
        for (k in toneBins) {
            val f = k * binHz
            val a = 1.0 / sqrt(f)
            val ph = rnd.nextDouble() * 2 * PI
            re[k] = a * cos(ph)
            im[k] = a * sin(ph)
            re[n - k] = re[k]
            im[n - k] = -im[k]
        }
        Fft.transform(re, im, true)
        var peak = 0.0
        for (v in re) peak = maxOf(peak, abs(v))
        val g = 10.0.pow(PEAK_DBFS / 20.0) / peak
        return FloatArray(n) { (re[it] * g).toFloat() }
    }

    /** Signalen upprepad i [seconds] sekunder, med kort in- och uttoning. */
    fun render(seconds: Double): FloatArray {
        val p = period
        val n = (seconds * SignalGenerator.SAMPLE_RATE).toInt()
        val out = FloatArray(n) { p[it % PERIOD] }
        val fade = SignalGenerator.SAMPLE_RATE / 20
        for (i in 0 until minOf(fade, n / 2)) {
            val g = i.toFloat() / fade
            out[i] *= g
            out[n - 1 - i] *= g
        }
        return out
    }

    /** Högpass vid 12 Hz (fjärde ordningen) mot mullret när telefonen rörs. Signalen börjar vid 18 Hz. */
    private const val HP_HZ = 12.0

    private class HighPass(freq: Double, sampleRate: Int, q: Double) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val w0 = 2.0 * PI * freq / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val c = cos(w0)
            val a0 = 1.0 + alpha
            b0 = (1.0 + c) / 2.0 / a0
            b1 = -(1.0 + c) / a0
            b2 = (1.0 + c) / 2.0 / a0
            a1 = -2.0 * c / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    /** Filtrerar bort muller under mätljudet. Samma filter används på referensen, så kurvan påverkas inte. */
    fun highPass(x: FloatArray, from: Int): FloatArray {
        val f = listOf(0.5412, 1.3066).map { HighPass(HP_HZ, SweepAnalyzer.FS, it) }
        val out = FloatArray(x.size - from)
        for (i in out.indices) {
            var y = x[from + i].toDouble()
            for (h in f) y = h.process(y)
            out[i] = y.toFloat()
        }
        return out
    }

    /** Signalens effekt per bin efter samma nedsampling och högpass som inspelningen går igenom. */
    val refPower: DoubleArray by lazy {
        val dec = Decimator(SweepAnalyzer.DECIMATION)
        val out = ArrayList<Float>()
        val p = period
        repeat(3) { for (v in p) dec.push(v.toDouble())?.let { out.add(it.toFloat()) } }
        val hp = highPass(out.toFloatArray(), 0)
        power(hp, hp.size - FRAME)
    }

    /** Effekt per bin för en ram ur x som börjar på [start]. */
    fun power(x: FloatArray, start: Int): DoubleArray {
        val re = DoubleArray(FRAME) { x[start + it].toDouble() }
        val im = DoubleArray(FRAME)
        Fft.transform(re, im, false)
        return DoubleArray(FRAME / 2) { re[it] * re[it] + im[it] * im[it] }
    }

    data class Result(
        /** Kurva på [SweepAnalyzer.FREQUENCIES], dB (relativ). */
        val db: DoubleArray,
        /** Antal ramar med signal som ingick. */
        val frames: Int,
        /** Signal mot bakgrundsbrus i 30–200 Hz, dB, eller null om inget bakgrundsbrus fanns före signalen. */
        val snrDb: Double?,
        /** Inspelningens högsta nivå, dBFS. */
        val peakDbfs: Double,
        /** false när mätljudet inte gick att skilja från bakgrunden och alla ramar användes ändå. */
        val reliable: Boolean = true,
    )

    /**
     * Analyserar en nedsamplad inspelning. Ramar utan signal (före start, i glapp när
     * Chromecasten startar om) och ramarna på var sida om ett glapp sorteras bort.
     * Den sista ramen används inte heller, eftersom den kan vara ofullständig.
     *
     * Om signal och glapp bedöms bara inom mätljudets frekvensområde, så att mullret när
     * telefonen rörs (under 18 Hz) inte stör. Med [fallback] används alla ramar om ingen
     * ram med säker signal hittas; resultatet markeras då som osäkert.
     */
    fun analyze(
        rec: FloatArray,
        from: Int = 0,
        maxFrames: Int = Int.MAX_VALUE,
        /** Sampelintervall som inte får användas, t.ex. medan röstguiden talar. */
        exclude: List<IntRange> = emptyList(),
        fallback: Boolean = false,
    ): Result? {
        val count = (rec.size - from) / FRAME
        if (count <= 0) return null
        val x = highPass(rec, from)
        val powers = Array(count) { f -> power(x, f * FRAME) }
        val energy = DoubleArray(count) { f -> var e = 0.0; for (k in toneBins) e += powers[f][k]; e }
        val maxE = energy.max()
        if (maxE <= 0.0) return null
        // Referensnivå: 80:e percentilen, så att en enstaka smäll inte styr. Ramar under −6 dB
        // från den räknas som glapp eller delvis tomma.
        val ref80 = energy.sorted()[((count - 1) * 0.8).toInt()]
        val isSignal = BooleanArray(count) { energy[it] > ref80 * 0.25 }
        val excluded = BooleanArray(count) { f ->
            val a = from + f * FRAME
            val b = a + FRAME - 1
            exclude.any { r -> r.first <= b && r.last >= a }
        }
        // Bara ramar med signal på båda sidor, så att ramar som delvis ligger i ett glapp sorteras bort.
        var use = (1 until count - 1)
            .filter { isSignal[it - 1] && isSignal[it] && isSignal[it + 1] && !excluded[it] }
            .takeLast(maxFrames)
        var reliable = true
        if (use.isEmpty()) {
            if (!fallback) return null
            use = (0 until count).filter { !excluded[it] }.let { all -> if (all.size > 2) all.drop(1).dropLast(1) else all }.takeLast(maxFrames)
            if (use.isEmpty()) return null
            reliable = false
        }
        val firstSignal = isSignal.indexOfFirst { it }.coerceAtLeast(0)
        val quiet = (0 until firstSignal).filter { energy[it] < maxE / 100.0 && !excluded[it] }

        val pSum = DoubleArray(FRAME / 2)
        for (f in use) for (k in toneBins) pSum[k] += powers[f][k]
        val ref = refPower
        val h2 = DoubleArray(FRAME / 2) { k -> if (k in toneBins && ref[k] > 0) pSum[k] / use.size / ref[k] else 0.0 }

        val db = DoubleArray(SweepAnalyzer.FREQUENCIES.size) { i ->
            val fc = SweepAnalyzer.FREQUENCIES[i]
            val lo = fc * 2.0.pow(-1.0 / 12.0)
            val hi = fc * 2.0.pow(1.0 / 12.0)
            var sum = 0.0
            var c = 0
            for (k in toneBins) {
                val f = k * binHz
                if (f in lo..hi) { sum += h2[k]; c++ }
            }
            10.0 * log10(sum / c.coerceAtLeast(1) + 1e-20)
        }

        val snr = if (quiet.isNotEmpty() && reliable) {
            val band = toneBins.filter { it * binHz in 30.0..200.0 }
            val sig = band.sumOf { pSum[it] } / use.size
            val noise = quiet.map { f -> band.sumOf { powers[f][it] } }.average()
            10.0 * log10(sig / (noise + 1e-30))
        } else {
            null
        }
        var peak = 0.0
        for (i in from until rec.size) peak = maxOf(peak, abs(rec[i].toDouble()))
        return Result(db, use.size, snr, 20.0 * log10(peak + 1e-9), reliable)
    }
}
