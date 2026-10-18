package se.johronski.subspeaker.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Bandpassfilter (RBJ, 0 dB toppförstärkning). Q ≈ 4,3 motsvarar ungefär en tredjedelsoktav. */
class BandPass(freq: Double, sampleRate: Int, q: Double = 4.3) {
    private val b0: Double
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
        val a0 = 1.0 + alpha
        b0 = alpha / a0
        b2 = -alpha / a0
        a1 = -2.0 * cos(w0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}

object Levels {
    /** Nivå i dBFS relativt en sinus med full skala (RMS · √2). */
    fun rmsToDbfs(rms: Double): Double = 20.0 * log10(rms * sqrt(2.0) + 1e-12)

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from).coerceAtLeast(1))
    }
}
