package se.johronski.subspeaker

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Läser upp instruktioner på svenska, så att man inte behöver titta på telefonen. */
class Speaker(context: Context) {
    @Volatile
    private var ready = false
    private var lastText: String? = null
    private var lastAt = 0L

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            try {
                tts.language = Locale("sv", "SE")
            } catch (_: Exception) {
            }
        }
    }

    var enabled: Boolean = true

    /** Läser upp texten. Samma text upprepas inte inom [minGapMs]. */
    fun say(text: String, minGapMs: Long = 0) {
        if (!enabled || !ready) return
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastAt < minGapMs) return
        lastText = text
        lastAt = now
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "subspeaker")
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            tts.stop()
        } catch (_: Exception) {
        }
    }
}
