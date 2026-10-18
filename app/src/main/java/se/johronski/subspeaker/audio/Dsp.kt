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

/** Lågpassfilter (RBJ, andra ordningen). Två i serie med Q 0,5412 och 1,3066 ger fjärde ordningens Butterworth. */
class LowPass(freq: Double, sampleRate: Int, q: Double) {
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
        b0 = (1.0 - c) / 2.0 / a0
        b1 = (1.0 - c) / a0
        b2 = (1.0 - c) / 2.0 / a0
        a1 = -2.0 * c / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}

/**
 * Minskar samplingsfrekvensen med en heltalsfaktor efter ett åttonde ordningens
 * lågpassfilter. 48 kHz / 16 = 3 kHz räcker för analys upp till några hundra Hz.
 */
class Decimator(private val factor: Int = 16, sampleRate: Int = SignalGenerator.SAMPLE_RATE, cutoff: Double = 700.0) {
    private val filters = listOf(0.5412, 1.3066, 0.5412, 1.3066).map { LowPass(cutoff, sampleRate, it) }
    private var counter = 0

    /** Matar in ett sampel; returnerar ett nedsamplat värde var factor:e gång, annars null. */
    fun push(x: Double): Double? {
        var y = x
        for (f in filters) y = f.process(y)
        counter++
        if (counter >= factor) {
            counter = 0
            return y
        }
        return null
    }
}

/** Radix-2 FFT på plats. Längden måste vara en tvåpotens. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT-längden måste vara en tvåpotens" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
            val half = len / 2
            for (k in 0 until half) {
                val cr = cos(ang * k)
                val ci = sin(ang * k)
                var i = k
                while (i < n) {
                    val xr = re[i + half]
                    val xi = im[i + half]
                    val vr = xr * cr - xi * ci
                    val vi = xr * ci + xi * cr
                    re[i + half] = re[i] - vr
                    im[i + half] = im[i] - vi
                    re[i] += vr
                    im[i] += vi
                    i += len
                }
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
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
