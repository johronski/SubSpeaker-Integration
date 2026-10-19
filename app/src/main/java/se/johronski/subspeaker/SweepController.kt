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
        /** "free" för fria mätningar, annars guidens steg: "mic", "phase", "level", "genelec". */
        val stage: String,
        /** Inställningen som jämförs, t.ex. "Fas 90". Mätningar med samma grupp medelvärdesbildas. */
        val group: String,
        /** Mätplats 1–5, eller 0 för fria mätningar. */
        val position: Int,
        val time: String,
        val db: DoubleArray,
        val delaySeconds: Double,
        val snrDb: Double,
        val device: String,
        /** Största skillnad (dB, 30–200 Hz) mellan upprepade svep, eller null vid ett svep. */
        val repeatSpreadDb: Double? = null,
    )

    data class Group(
        val label: String,
        val count: Int,
        val db: DoubleArray,
        val meanDb: Double,
        val flatnessDb: Double,
        val minSnrDb: Double,
        val lo: DoubleArray,
        val hi: DoubleArray,
        val perPosition: Map<Int, Double>,
    ) {
        fun option() = se.johronski.subspeaker.audio.Decision.Option(label, flatnessDb, perPosition)
    }

    data class State(
        val busy: Boolean = false,
        val status: String = "",
        val curves: List<Curve> = emptyList(),
        val error: String? = null,
        /** Väntetid innan svepet startar, så att man hinner gå ifrån mätplatsen. */
        val delaySeconds: Int = 10,
        /** Antal svep per mätning. Flera svep medelvärdesbildas och kontrolleras mot varandra. */
        val repeats: Int = 1,
    ) {
        val groups: List<Group> get() = groupsFor("free")

        fun groupsFor(stage: String): List<Group> =
            curves.filter { it.stage == stage }
                .groupBy { it.group.trim().lowercase(Locale.ROOT) }.values.map { list ->
                    val avg = SweepAnalyzer.powerAverage(list.map { it.db })
                    val (mean, _) = SweepAnalyzer.stats(avg)
                    val n = avg.size
                    val lo = DoubleArray(n) { i -> list.minOf { it.db[i] } }
                    val hi = DoubleArray(n) { i -> list.maxOf { it.db[i] } }
                    val perPos = list.groupBy { it.position }.mapValues { (_, c) ->
                        SweepAnalyzer.deviation(SweepAnalyzer.powerAverage(c.map { it.db }))
                    }
                    Group(
                        list.first().group.trim(), list.size, avg, mean, SweepAnalyzer.deviation(avg),
                        list.minOf { it.snrDb }, lo, hi, perPos,
                    )
                }

        /** Jämnaste gruppen bland fria mätningar, om det finns minst två att jämföra. */
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
        stage: String = "free",
        group: String = label,
        position: Int = 0,
    ) {
        if (_state.value.busy) return
        val spec = SweepAnalyzer.SWEEP
        val delaySeconds = _state.value.delaySeconds
        val repeats = _state.value.repeats
        job = scope.launch {
            var recorder: SweepRecorder? = null
            _state.value = _state.value.copy(busy = true, error = null, status = "Förbereder …")
            try {
                withContext(Dispatchers.Main) {
                    services.mic.stop()
                    services.local.stop()
                }
                for (s in delaySeconds downTo 1) {
                    _state.value = _state.value.copy(status = "Startar om $s s. Gå ifrån mätplatsen och var tyst.")
                    delay(1000)
                }

                val results = mutableListOf<SweepAnalyzer.Result>()
                var device = ""
                for (r in 1..repeats) {
                    val rec = SweepRecorder().also { recorder = it }
                    rec.start(source, micDevice)
                    delay(500)
                    val prefix = if (repeats > 1) "Svep $r av $repeats: " else ""

                    if (useCast) {
                        val url = services.signalUrl(spec.path) ?: throw IllegalStateException("Telefonen har ingen wifi-adress")
                        _state.value = _state.value.copy(status = "${prefix}Startar svepet på Chromecasten …")
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
                        _state.value = _state.value.copy(status = "${prefix}Mäter … $left s kvar. Var tyst och stilla.")
                        delay(250)
                        t += 250
                        rec.error?.let { throw IllegalStateException(it) }
                    }
                    val data = rec.stop()
                    recorder = null
                    device = rec.routedDevice ?: device
                    _state.value = _state.value.copy(status = "${prefix}Räknar …")
                    results += SweepAnalyzer.analyze(data)
                    if (r < repeats) delay(1500)
                }

                val db = SweepAnalyzer.powerAverage(results.map { it.db })
                val spread = if (results.size > 1) SweepAnalyzer.repeatSpread(results.map { it.db }) else null
                val snr = results.minOf { it.snrDb }
                // I mikrofonkollen namnges mätningen efter mikrofonen som faktiskt användes,
                // så att två mikrofoner aldrig slås ihop även om "Systemets val" var valt.
                val micLabel = if (stage == "mic" && device.isNotBlank()) "Mikrofon · $device" else null
                val name = micLabel ?: label.ifBlank { "Mätning ${_state.value.curves.size + 1}" }
                val curve = Curve(
                    label = name,
                    stage = stage,
                    group = micLabel ?: group.ifBlank { name },
                    position = position,
                    time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()),
                    db = db,
                    delaySeconds = results.first().delaySeconds,
                    snrDb = snr,
                    device = device,
                    repeatSpreadDb = spread,
                )
                val warn = when {
                    snr < 15 -> "Svag signal mot bruset (${snr.toInt()} dB). Höj volymen eller minska störningar och mät om."
                    spread != null && spread > 1.5 -> String.format(
                        Locale.ROOT,
                        "Svepen skiljde %.1f dB från varandra. Något störde troligen – mät om.", spread,
                    )
                    snr < 25 -> "Godkänd men knapp marginal mot bruset (${snr.toInt()} dB)."
                    else -> null
                }
                val spreadText = spread?.let { String.format(Locale.ROOT, " · svepen skiljde %.1f dB", it) } ?: ""
                _state.value = _state.value.copy(
                    busy = false,
                    curves = _state.value.curves + curve,
                    status = "Klar: ${curve.label} · brusmarginal ${snr.toInt()} dB$spreadText",
                    error = warn,
                )
                services.signalDone()
            } catch (e: Exception) {
                recorder?.stop()
                _state.value = _state.value.copy(busy = false, status = "", error = e.message ?: e.javaClass.simpleName)
                services.signalDone()
            }
        }
    }

    fun setDelay(seconds: Int) {
        _state.value = _state.value.copy(delaySeconds = seconds.coerceIn(0, 30))
    }

    fun setRepeats(n: Int) {
        _state.value = _state.value.copy(repeats = n.coerceIn(1, 3))
    }

    fun removeLast(stage: String = "free") {
        val c = _state.value.curves
        val i = c.indexOfLast { it.stage == stage }
        if (i >= 0) _state.value = _state.value.copy(curves = c.filterIndexed { idx, _ -> idx != i })
    }

    fun clear(stage: String = "free") {
        _state.value = _state.value.copy(curves = _state.value.curves.filter { it.stage != stage }, error = null, status = "")
    }

    fun clearAll() {
        _state.value = State(delaySeconds = _state.value.delaySeconds, repeats = _state.value.repeats)
    }
}
