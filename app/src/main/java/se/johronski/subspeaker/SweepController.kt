package se.johronski.subspeaker

import android.media.AudioDeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.johronski.subspeaker.audio.LocalPlayer
import se.johronski.subspeaker.audio.MicMeter
import se.johronski.subspeaker.audio.SignalServer
import se.johronski.subspeaker.audio.SweepAnalyzer
import se.johronski.subspeaker.audio.SweepRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kör en svepmätning: spela in, spela svepet, analysera. Mätningar med samma etikett medelvärdesbildas. */
class SweepController(private val services: AppServices) {

    data class Curve(
        val label: String,
        val time: String,
        val db: DoubleArray,
        val delaySeconds: Double,
        val snrDb: Double,
        val device: String,
    )

    data class Group(
        val label: String,
        val count: Int,
        val db: DoubleArray,
        val meanDb: Double,
        val flatnessDb: Double,
        val minSnrDb: Double,
    )

    data class State(
        val busy: Boolean = false,
        val status: String = "",
        val curves: List<Curve> = emptyList(),
        val error: String? = null,
    ) {
        val groups: List<Group>
            get() = curves.groupBy { it.label.trim().lowercase(Locale.ROOT) }.values.map { list ->
                val avg = SweepAnalyzer.powerAverage(list.map { it.db })
                val (mean, sd) = SweepAnalyzer.stats(avg)
                Group(list.first().label.trim(), list.size, avg, mean, sd, list.minOf { it.snrDb })
            }

        /** Jämnaste gruppen mellan 40 och 150 Hz, om det finns minst två att jämföra. */
        val best: Group?
            get() = groups.takeIf { it.size >= 2 }?.minByOrNull { it.flatnessDb }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun measure(
        label: String,
        useCast: Boolean,
        source: MicMeter.Source,
        micDevice: AudioDeviceInfo?,
        outDevice: AudioDeviceInfo?,
        method: LocalPlayer.Method,
    ) {
        if (_state.value.busy) return
        val spec = SweepAnalyzer.SWEEP
        job = scope.launch {
            val recorder = SweepRecorder()
            _state.value = _state.value.copy(busy = true, error = null, status = "Förbereder …")
            try {
                withContext(Dispatchers.Main) {
                    services.mic.stop()
                    services.local.stop()
                }
                recorder.start(source, micDevice)
                delay(500)

                if (useCast) {
                    val url = services.signalUrl(spec.path) ?: throw IllegalStateException("Telefonen har ingen wifi-adress")
                    _state.value = _state.value.copy(status = "Startar svepet på Chromecasten …")
                    withContext(Dispatchers.Main) { services.cast.play(url, "Mätsvep", spec.durationMs) }
                    var waited = 0
                    while (services.cast.state.value.latencyMs == null) {
                        services.cast.state.value.lastError?.let { throw IllegalStateException(it) }
                        if (waited > 20_000) throw IllegalStateException("Chromecasten började aldrig spela")
                        delay(100)
                        waited += 100
                    }
                } else {
                    val url = "http://127.0.0.1:${SignalServer.PORT}/${spec.path}"
                    withContext(Dispatchers.Main) { services.local.play(spec, outDevice, method, url) }
                }

                val total = spec.durationMs + 2500
                var t = 0L
                while (t < total) {
                    val left = ((total - t) / 1000).coerceAtLeast(0)
                    _state.value = _state.value.copy(status = "Mäter … $left s kvar. Var tyst och stilla.")
                    delay(250)
                    t += 250
                    recorder.error?.let { throw IllegalStateException(it) }
                }

                val rec = recorder.stop()
                _state.value = _state.value.copy(status = "Räknar …")
                val r = SweepAnalyzer.analyze(rec)
                val curve = Curve(
                    label = label.ifBlank { "Mätning ${_state.value.curves.size + 1}" },
                    time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()),
                    db = r.db,
                    delaySeconds = r.delaySeconds,
                    snrDb = r.snrDb,
                    device = recorder.routedDevice ?: "",
                )
                val warn = when {
                    r.snrDb < 15 -> "Svag signal mot bruset (${r.snrDb.toInt()} dB). Höj volymen eller minska störningar och mät om."
                    r.snrDb < 25 -> "Godkänd men knapp marginal mot bruset (${r.snrDb.toInt()} dB)."
                    else -> null
                }
                _state.value = _state.value.copy(
                    busy = false,
                    curves = _state.value.curves + curve,
                    status = "Klar: ${curve.label} · brusmarginal ${r.snrDb.toInt()} dB",
                    error = warn,
                )
            } catch (e: Exception) {
                recorder.stop()
                _state.value = _state.value.copy(busy = false, status = "", error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun removeLast() {
        _state.value = _state.value.copy(curves = _state.value.curves.dropLast(1))
    }

    fun clear() {
        _state.value = State()
    }
}
