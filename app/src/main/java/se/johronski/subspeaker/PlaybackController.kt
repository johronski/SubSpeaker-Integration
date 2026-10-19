package se.johronski.subspeaker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import se.johronski.subspeaker.audio.SignalServer
import se.johronski.subspeaker.audio.SignalSpec
import se.johronski.subspeaker.audio.ToneGrid

/**
 * Spelar testsignaler via vald utgång (Chromecast eller telefonen) och håller reda på
 * tonen i dippsökningen och markerade dippar.
 */
class PlaybackController(private val services: AppServices, private val store: Store) {

    data class State(
        /** Senast startade signal. Spelar den eller inte avgörs av utgångens eget läge. */
        val spec: SignalSpec? = null,
        val dipFreq: Double = ToneGrid.snap(120.0, 12),
        val dipDiv: Int = 12,
        val dipWarble: Boolean = false,
        /** Frekvenser som användaren markerat som dippar. */
        val markers: List<Double> = emptyList(),
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state

    val canPlay: Boolean
        get() = services.settings.value.output == Output.PHONE || services.cast.state.value.connected

    /** Spelar något just nu (inklusive buffring på Chromecasten). */
    val isPlaying: Boolean
        get() = if (services.settings.value.output == Output.PHONE) {
            services.local.state.value.playing
        } else {
            services.cast.state.value.playerState in setOf("Spelar", "Buffrar")
        }

    fun play(spec: SignalSpec) {
        _state.value = _state.value.copy(spec = spec)
        if (!canPlay || services.sweep.state.value.busy) return
        if (services.settings.value.output == Output.CAST) {
            services.local.stop()
            services.signalUrl(spec.path)?.let { services.cast.play(it, spec.label, spec.durationMs) }
        } else {
            if (services.cast.state.value.connected) services.cast.stop()
            services.local.play(
                spec,
                services.outputDevice(),
                services.settings.value.method,
                "http://127.0.0.1:${SignalServer.PORT}/${spec.path}",
            )
        }
    }

    fun stop() {
        services.local.stop()
        if (services.cast.state.value.connected) services.cast.stop()
    }

    /** Tonen i dippsökningen: 60 s så att man hinner lyssna och flytta sig. */
    fun tone(f: Double = _state.value.dipFreq) = SignalSpec.Tone(f, _state.value.dipWarble, seconds = 60.0)

    fun playTone(f: Double) {
        update { it.copy(dipFreq = ToneGrid.round(f).coerceIn(ToneGrid.MIN, ToneGrid.MAX)) }
        play(tone())
    }

    fun stepTone(dir: Int) = playTone(ToneGrid.step(_state.value.dipFreq, _state.value.dipDiv, dir))

    fun setDivision(div: Int) = update { it.copy(dipDiv = div, dipFreq = ToneGrid.snap(it.dipFreq, div)) }

    fun setWarble(w: Boolean) {
        val wasTone = isPlaying && _state.value.spec is SignalSpec.Tone
        update { it.copy(dipWarble = w) }
        if (wasTone) play(tone())
    }

    fun clearSpec() {
        _state.value = _state.value.copy(spec = null)
    }

    fun toggleMarker(f: Double) = update {
        val has = it.markers.any { m -> kotlin.math.abs(m - f) < 0.05 }
        it.copy(markers = if (has) it.markers.filterNot { m -> kotlin.math.abs(m - f) < 0.05 } else (it.markers + f).sorted())
    }

    fun clearMarkers() = update { it.copy(markers = emptyList()) }

    private fun update(f: (State) -> State) {
        _state.value = f(_state.value)
        val s = _state.value
        store.put(
            KEY,
            JSONObject()
                .put("dipFreq", s.dipFreq)
                .put("dipDiv", s.dipDiv)
                .put("dipWarble", s.dipWarble)
                .put("markers", Store.doubles(s.markers.toDoubleArray()))
                .toString(),
        )
    }

    private fun load(): State {
        val s = store.get(KEY) ?: return State()
        return try {
            val j = JSONObject(s)
            val div = j.optInt("dipDiv", 12).takeIf { it in ToneGrid.DIVISIONS } ?: 12
            State(
                dipFreq = j.optDouble("dipFreq", 120.0).coerceIn(ToneGrid.MIN, ToneGrid.MAX),
                dipDiv = div,
                dipWarble = j.optBoolean("dipWarble", false),
                markers = Store.doubles(j.optJSONArray("markers"))?.toList() ?: emptyList(),
            )
        } catch (e: Exception) {
            State()
        }
    }

    private companion object {
        const val KEY = "playback"
    }
}
