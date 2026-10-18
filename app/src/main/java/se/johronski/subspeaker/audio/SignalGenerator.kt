package se.johronski.subspeaker.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Genererar testsignaler som mono-flyttal i intervallet -1..1.
 * Nivån anges som toppnivå i dBFS.
 */
object SignalGenerator {
    const val SAMPLE_RATE = 48_000

    /** Standardsteg för stegade toner, uppifrån och ner. */
    val STEP_FREQUENCIES = listOf(200.0, 160.0, 125.0, 100.0, 80.0, 63.0, 50.0, 40.0, 31.5, 25.0, 20.0)

    fun amplitude(dbfs: Double): Double = 10.0.pow(dbfs / 20.0)

    fun sine(freq: Double, seconds: Double, dbfs: Double, fadeMs: Double = 200.0): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val a = amplitude(dbfs)
        val out = FloatArray(n)
        val w = 2.0 * PI * freq / SAMPLE_RATE
        for (i in 0 until n) out[i] = (a * sin(w * i)).toFloat()
        fade(out, fadeMs)
        return out
    }

    /** Ton som svajar ±depth (andel) runt freq, rate gånger per sekund. */
    fun warble(
        freq: Double,
        seconds: Double,
        dbfs: Double,
        depth: Double = 0.05,
        rate: Double = 4.0,
        fadeMs: Double = 200.0,
    ): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val a = amplitude(dbfs)
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val f = freq * (1.0 + depth * sin(2.0 * PI * rate * t))
            phase += 2.0 * PI * f / SAMPLE_RATE
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            out[i] = (a * sin(phase)).toFloat()
        }
        fade(out, fadeMs)
        return out
    }

    /** Logaritmiskt svep från f1 till f2 (f1 > f2 ger svep nedåt). */
    fun logSweep(
        f1: Double,
        f2: Double,
        seconds: Double,
        dbfs: Double,
        fadeMs: Double = 200.0,
        sampleRate: Int = SAMPLE_RATE,
    ): FloatArray {
        val n = (seconds * sampleRate).toInt()
        val a = amplitude(dbfs)
        val out = FloatArray(n)
        val k = seconds / ln(f2 / f1)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val phase = 2.0 * PI * f1 * k * (exp(t / k) - 1.0)
            out[i] = (a * sin(phase)).toFloat()
        }
        fade(out, fadeMs, sampleRate)
        return out
    }

    /** Momentan frekvens i ett logaritmiskt svep vid tiden t sekunder. */
    fun sweepFrequencyAt(f1: Double, f2: Double, seconds: Double, t: Double): Double =
        f1 * (f2 / f1).pow(t / seconds)

    fun stepped(
        freqs: List<Double>,
        toneSeconds: Double,
        gapSeconds: Double,
        dbfs: Double,
        warble: Boolean,
    ): FloatArray {
        val tone = (toneSeconds * SAMPLE_RATE).toInt()
        val gap = (gapSeconds * SAMPLE_RATE).toInt()
        val out = FloatArray(freqs.size * (tone + gap))
        var pos = 0
        for (f in freqs) {
            val seg = if (warble) {
                warble(f, toneSeconds, dbfs, fadeMs = 50.0)
            } else {
                sine(f, toneSeconds, dbfs, fadeMs = 50.0)
            }
            seg.copyInto(out, pos)
            pos += tone + gap
        }
        return out
    }

    private fun fade(x: FloatArray, ms: Double, sampleRate: Int = SAMPLE_RATE) {
        val n = min((sampleRate * ms / 1000.0).toInt(), x.size / 2)
        if (n <= 0) return
        for (i in 0 until n) {
            val g = i.toFloat() / n
            x[i] *= g
            x[x.size - 1 - i] *= g
        }
    }
}
