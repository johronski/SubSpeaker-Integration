package se.johronski.subspeaker.audio

import java.util.Locale

/**
 * Beskriver en testsignal. Varje signal har en URL-väg som Chromecasten hämtar,
 * t.ex. "tone/sine/90/30/-12.wav".
 */
sealed class SignalSpec {
    abstract val label: String
    abstract val seconds: Double
    abstract val dbfs: Double

    /** Frekvens som mikrofonmätaren ska lyssna på, eller null för svep. */
    abstract val measureFrequency: Double?
    abstract val path: String
    abstract fun render(): FloatArray

    val durationMs: Long get() = (seconds * 1000).toLong()

    /** Frekvensen som spelas efter ms millisekunder, eller null i en paus eller efter slutet. */
    open fun frequencyAt(ms: Long): Double? = null

    data class Tone(
        val freq: Double,
        val warble: Boolean,
        override val seconds: Double = 30.0,
        override val dbfs: Double = -12.0,
    ) : SignalSpec() {
        override val label: String
            get() = "${fmt(freq)} Hz ${if (warble) "svajton" else "ren ton"} (${seconds.toInt()} s)"
        override val measureFrequency: Double get() = freq
        override val path: String
            get() = "tone/${if (warble) "warble" else "sine"}/${fmt(freq)}/${fmt(seconds)}/${fmt(dbfs)}.wav"

        override fun render(): FloatArray =
            if (warble) SignalGenerator.warble(freq, seconds, dbfs) else SignalGenerator.sine(freq, seconds, dbfs)

        override fun frequencyAt(ms: Long): Double? = if (ms in 0..durationMs) freq else null
    }

    data class Sweep(
        val f1: Double,
        val f2: Double,
        override val seconds: Double = 60.0,
        override val dbfs: Double = -12.0,
    ) : SignalSpec() {
        override val label: String get() = "Svep ${fmt(f1)}→${fmt(f2)} Hz (${seconds.toInt()} s)"
        override val measureFrequency: Double? get() = null
        override val path: String get() = "sweep/${fmt(f1)}/${fmt(f2)}/${fmt(seconds)}/${fmt(dbfs)}.wav"
        override fun render(): FloatArray = SignalGenerator.logSweep(f1, f2, seconds, dbfs)

        override fun frequencyAt(ms: Long): Double? =
            if (ms in 0..durationMs) SignalGenerator.sweepFrequencyAt(f1, f2, seconds, ms / 1000.0) else null
    }

    data class Steps(
        val warble: Boolean,
        override val dbfs: Double = -12.0,
    ) : SignalSpec() {
        override val seconds: Double get() = SignalGenerator.STEP_FREQUENCIES.size * 5.0
        override val label: String
            get() = "Stegade ${if (warble) "svajtoner" else "toner"} 200→20 Hz"
        override val measureFrequency: Double? get() = null
        override val path: String get() = "steps/${if (warble) "warble" else "sine"}/${fmt(dbfs)}.wav"
        override fun render(): FloatArray =
            SignalGenerator.stepped(SignalGenerator.STEP_FREQUENCIES, 4.0, 1.0, dbfs, warble)

        override fun frequencyAt(ms: Long): Double? {
            if (ms < 0) return null
            val idx = (ms / 5000).toInt()
            if (idx >= SignalGenerator.STEP_FREQUENCIES.size || ms % 5000 >= 4000) return null
            return SignalGenerator.STEP_FREQUENCIES[idx]
        }
    }

    /** Periodiskt rosa brus (multiton) för hälsokoll och stämapparat. */
    data class Noise(override val seconds: Double = 60.0) : SignalSpec() {
        override val dbfs: Double get() = Multitone.PEAK_DBFS
        override val label: String get() = "Mätbrus (${seconds.toInt()} s)"
        override val measureFrequency: Double? get() = null
        override val path: String get() = "noise/${fmt(seconds)}.wav"
        override fun render(): FloatArray = Multitone.render(seconds)
    }

    companion object {
        fun fmt(v: Double): String =
            if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)

        /** Tolkar en URL-väg. Returnerar null om vägen är ogiltig eller utanför säkra gränser. */
        fun parse(path: String): SignalSpec? {
            val p = path.trim('/').removeSuffix(".wav").split('/')
            return try {
                when (p.firstOrNull()) {
                    "tone" -> {
                        if (p.size != 5) return null
                        val warble = when (p[1]) { "warble" -> true; "sine" -> false; else -> return null }
                        val f = p[2].toDouble()
                        val s = p[3].toDouble()
                        val db = p[4].toDouble()
                        if (f !in 10.0..1000.0 || s !in 1.0..120.0 || db !in -60.0..-3.0) return null
                        Tone(f, warble, s, db)
                    }
                    "sweep" -> {
                        if (p.size != 5) return null
                        val f1 = p[1].toDouble()
                        val f2 = p[2].toDouble()
                        val s = p[3].toDouble()
                        val db = p[4].toDouble()
                        if (f1 !in 10.0..1000.0 || f2 !in 10.0..1000.0 || f1 == f2) return null
                        if (s !in 1.0..120.0 || db !in -60.0..-3.0) return null
                        Sweep(f1, f2, s, db)
                    }
                    "noise" -> {
                        if (p.size != 2) return null
                        val sec = p[1].toDouble()
                        if (sec !in 5.0..120.0) return null
                        Noise(sec)
                    }
                    "steps" -> {
                        if (p.size != 3) return null
                        val warble = when (p[1]) { "warble" -> true; "sine" -> false; else -> return null }
                        val db = p[2].toDouble()
                        if (db !in -60.0..-3.0) return null
                        Steps(warble, db)
                    }
                    else -> null
                }
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
