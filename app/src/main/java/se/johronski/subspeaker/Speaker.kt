package se.johronski.subspeaker

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Läser upp instruktioner på svenska, så att man inte behöver titta på telefonen.
 * Meddelar när talet är slut, så att mätningen kan bortse från tiden då rösten hördes.
 */
class Speaker(context: Context) {
    @Volatile
    private var ready = false
    private var lastText: String? = null
    private var lastAt = 0L
    private val ids = AtomicInteger()
    private val onEnd = ConcurrentHashMap<String, () -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            try {
                tts.language = Locale("sv", "SE")
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finish(utteranceId)

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finish(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
                })
            } catch (_: Exception) {
            }
        }
    }

    private fun finish(id: String?) {
        id?.let { onEnd.remove(it)?.invoke() }
    }

    @Volatile
    var enabled: Boolean = false

    /**
     * Läser upp texten. Samma text upprepas inte inom [minGapMs].
     * Returnerar true om talet startade; då anropas [whenDone] när det är slut eller avbröts.
     */
    fun say(text: String, minGapMs: Long = 0, whenDone: (() -> Unit)? = null): Boolean {
        if (!enabled || !ready) return false
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastAt < minGapMs) return false
        lastText = text
        lastAt = now
        val id = "u${ids.incrementAndGet()}"
        if (whenDone != null) onEnd[id] = whenDone
        return try {
            val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (r != TextToSpeech.SUCCESS) {
                onEnd.remove(id)
                false
            } else {
                true
            }
        } catch (_: Exception) {
            onEnd.remove(id)
            false
        }
    }

    fun stop() {
        try {
            tts.stop()
        } catch (_: Exception) {
        }
    }
}
