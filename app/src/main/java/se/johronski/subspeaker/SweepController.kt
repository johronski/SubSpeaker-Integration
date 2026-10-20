package se.johronski.subspeaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import se.johronski.subspeaker.audio.Decision
import se.johronski.subspeaker.audio.SignalServer
import se.johronski.subspeaker.audio.SweepAnalyzer
import se.johronski.subspeaker.audio.SweepRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kör en svepmätning: spela in, spela svepet, analysera. Mätningar med samma grupp medelvärdesbildas. */
class SweepController(private val services: AppServices, private val store: Store) {

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
        /** Guidens uppgift som mätningen hör till (kan skilja från group i nivåsteget). */
        val task: String = group,
        /** Subbens faktiska volymsteg från utgångsläget, i nivåsteget. */
        val levelStep: Int? = null,
        val id: String = java.util.UUID.randomUUID().toString(),
        /** Fönstrat impulssvar per svep (3 kHz), för export och vidare analys. */
        val irs: List<FloatArray> = emptyList(),
        /** Varje impulssvars första sampel räknat från inspelningens start. */
        val irStarts: List<Int> = emptyList(),
        /** Chromecastens starttid per svep (ms), eller null vid uppspelning i telefonen. */
        val latencies: List<Long?> = emptyList(),
        /** "Chromecast" eller "Telefonen". */
        val output: String = "",
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
        fun option() = Decision.Option(label, flatnessDb, perPosition)
    }

    /** Resultat av nivåkollen före mätningarna. */
    data class LevelCheck(val ok: Boolean, val text: String, val snrDb: Double, val peakDbfs: Double)

    data class State(
        val busy: Boolean = false,
        val status: String = "",
        val curves: List<Curve> = emptyList(),
        val error: String? = null,
        /** Väntetid innan svepet startar, så att man hinner gå ifrån mätplatsen. */
        val delaySeconds: Int = 10,
        /** Antal svep per mätning. Flera svep medelvärdesbildas och kontrolleras mot varandra. */
        val repeats: Int = 1,
        val check: LevelCheck? = null,
        /** Klar-andel av pågående mätning, 0–1, för förloppsindikatorn. */
        val progress: Float = 0f,
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

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Skriver mätningarna till fil i tur och ordning, utanför huvudtråden. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun measure(
        label: String,
        stage: String = "free",
        group: String = label,
        position: Int = 0,
        task: String = group,
        levelStep: Int? = null,
    ) = run(label, stage, group, position, task, levelStep)

    /** Ett svep för att kontrollera att nivån är lagom innan de riktiga mätningarna. */
    fun levelCheck() = run("Nivåkoll", "check", "check", 0, "check", null)

    private fun run(label: String, stage: String, group: String, position: Int, task: String, levelStep: Int?) {
        if (_state.value.busy) return
        val spec = SweepAnalyzer.SWEEP
        val delaySeconds = if (stage == "check") minOf(_state.value.delaySeconds, 5) else _state.value.delaySeconds
        val repeats = if (stage == "check") 1 else _state.value.repeats
        val settings = services.settings.value
        val useCast = settings.output == Output.CAST
        val source = services.micSource()
        val micDevice = services.micDevice()
        val outDevice = services.outputDevice()
        val totalMs = delaySeconds * 1000L + repeats * (spec.durationMs + 4500)
        var elapsed = 0L
        job = scope.launch {
            var recorder: SweepRecorder? = null
            fun progress() = (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)
            set { it.copy(busy = true, error = null, status = "Förbereder …", progress = 0f) }
            try {
                withContext(Dispatchers.Main) {
                    services.mic.stop()
                    services.local.stop()
                    services.playback.clearSpec()
                }
                for (s in delaySeconds downTo 1) {
                    set { it.copy(status = "Startar om $s s. Gå ifrån mätplatsen och var tyst.", progress = progress()) }
                    delay(1000)
                    elapsed += 1000
                }

                val results = mutableListOf<SweepAnalyzer.Result>()
                val latencies = mutableListOf<Long?>()
                var device = ""
                for (r in 1..repeats) {
                    val rec = SweepRecorder().also { recorder = it }
                    rec.start(source, micDevice)
                    delay(500)
                    elapsed += 500
                    val prefix = if (repeats > 1) "Svep $r av $repeats: " else ""

                    if (useCast) {
                        val url = services.signalUrl(spec.path) ?: throw IllegalStateException("Telefonen har ingen wifi-adress")
                        set { it.copy(status = "${prefix}Startar svepet på Chromecasten …") }
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
                        withContext(Dispatchers.Main) { services.local.play(spec, outDevice, settings.method, url) }
                    }

                    val total = spec.durationMs + 2500
                    var t = 0L
                    while (t < total) {
                        val left = ((total - t) / 1000).coerceAtLeast(0)
                        set { it.copy(status = "${prefix}Mäter … $left s kvar. Var tyst och stilla.", progress = progress()) }
                        delay(250)
                        t += 250
                        elapsed += 250
                        rec.error?.let { throw IllegalStateException(it) }
                    }
                    val data = rec.stop()
                    recorder = null
                    device = rec.routedDevice ?: device
                    set { it.copy(status = "${prefix}Räknar …") }
                    results += SweepAnalyzer.analyze(data)
                    latencies += if (useCast) services.cast.state.value.latencyMs else null
                    if (r < repeats) {
                        delay(1500)
                        elapsed += 1500
                    }
                }

                val snr = results.minOf { it.snrDb }
                if (stage == "check") {
                    val peak = results.maxOf { it.peakDbfs }
                    val c = when {
                        peak > -3.0 -> LevelCheck(false, "För starkt – mikrofonen överstyrs. Sänk volymen lite och kör nivåkollen igen.", snr, peak)
                        snr < 25 -> LevelCheck(false, "För svagt (brusmarginal ${snr.toInt()} dB). Höj volymen och kör nivåkollen igen.", snr, peak)
                        else -> LevelCheck(true, "Lagom nivå (brusmarginal ${snr.toInt()} dB). Du kan börja mäta.", snr, peak)
                    }
                    set { it.copy(busy = false, status = "", check = c, progress = 1f) }
                    services.signalDone()
                    return@launch
                }

                val db = SweepAnalyzer.powerAverage(results.map { it.db })
                val spread = if (results.size > 1) SweepAnalyzer.repeatSpread(results.map { it.db }) else null
                // I mikrofonkollen namnges mätningen efter mikrofonen som faktiskt användes,
                // så att två mikrofoner aldrig slås ihop.
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
                    task = if (micLabel != null) micLabel else task,
                    levelStep = levelStep,
                    irs = results.map { it.ir },
                    irStarts = results.map { it.irStart },
                    latencies = latencies,
                    output = settings.output.label,
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
                set {
                    it.copy(
                        busy = false,
                        curves = it.curves + curve,
                        status = "Klar: ${curve.label} · brusmarginal ${snr.toInt()} dB$spreadText",
                        error = warn,
                        progress = 1f,
                    )
                }
                save()
                services.signalDone()
            } catch (e: CancellationException) {
                recorder?.stop()
            } catch (e: Exception) {
                recorder?.stop()
                set { it.copy(busy = false, status = "", error = e.message ?: e.javaClass.simpleName) }
                services.signalDone()
            }
        }
    }

    /** Avbryter pågående mätning och stoppar uppspelningen. */
    fun cancel() {
        val j = job ?: return
        if (!_state.value.busy) return
        j.cancel()
        services.local.stop()
        if (services.cast.state.value.connected) services.cast.stop()
        set { it.copy(busy = false, status = "Mätningen avbröts.", error = null, progress = 0f) }
    }

    fun setDelay(seconds: Int) = set { it.copy(delaySeconds = seconds.coerceIn(0, 30)) }.also { save() }

    fun setRepeats(n: Int) = set { it.copy(repeats = n.coerceIn(1, 3)) }.also { save() }

    fun removeLast(stage: String = "free") {
        val c = _state.value.curves
        val i = c.indexOfLast { it.stage == stage }
        if (i >= 0) set { it.copy(curves = c.filterIndexed { idx, _ -> idx != i }, status = "", error = null) }
        save()
    }

    fun clear(stage: String = "free") {
        set { it.copy(curves = it.curves.filter { c -> c.stage != stage }, error = null, status = "") }
        save()
    }

    fun clearCheck() = set { it.copy(check = null) }

    fun restore(curves: List<Curve>) {
        set { it.copy(curves = curves, status = "", error = null) }
        save()
    }

    private inline fun set(f: (State) -> State) {
        _state.value = f(_state.value)
    }

    // ---------- Lagring ----------

    private fun save() {
        val s = _state.value
        val arr = JSONArray()
        s.curves.forEach { arr.put(toJson(it)) }
        val text = JSONObject().put("curves", arr).put("delay", s.delaySeconds).put("repeats", s.repeats).toString()
        io.execute { store.writeFile(FILE, text) }
    }

    private fun load(): State {
        val raw = store.readFile(FILE) ?: store.get(KEY) ?: return State()
        return try {
            val j = JSONObject(raw)
            val arr = j.optJSONArray("curves") ?: JSONArray()
            val curves = (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
            State(curves = curves, delaySeconds = j.optInt("delay", 10), repeats = j.optInt("repeats", 1))
        } catch (e: Exception) {
            State()
        }
    }

    companion object {
        private const val KEY = "sweep"
        private const val FILE = "sweep.json"

        fun toJson(c: Curve): JSONObject =
            JSONObject()
                .put("id", c.id)
                .put("label", c.label).put("stage", c.stage).put("group", c.group).put("task", c.task)
                .put("position", c.position).put("time", c.time).put("db", Store.doubles(c.db))
                .put("delay", c.delaySeconds).put("snr", c.snrDb).put("device", c.device)
                .put("spread", c.repeatSpreadDb ?: JSONObject.NULL)
                .put("levelStep", c.levelStep ?: JSONObject.NULL)
                .put("output", c.output)
                .put("irs", JSONArray().also { a -> c.irs.forEach { a.put(Store.floatsBase64(it)) } })
                .put("irStarts", Store.ints(c.irStarts))
                .put("latencies", JSONArray().also { a -> c.latencies.forEach { a.put(it ?: JSONObject.NULL) } })

        fun fromJson(o: JSONObject): Curve? {
            val n = SweepAnalyzer.FREQUENCIES.size
            val db = Store.doubles(o.optJSONArray("db"))?.takeIf { it.size == n } ?: return null
            val group = o.getString("group")
            val irsJ = o.optJSONArray("irs")
            val latJ = o.optJSONArray("latencies")
            return Curve(
                label = o.getString("label"),
                stage = o.getString("stage"),
                group = group,
                position = o.optInt("position"),
                time = o.optString("time"),
                db = db,
                delaySeconds = o.optDouble("delay", 0.0),
                snrDb = o.optDouble("snr", 0.0),
                device = o.optString("device"),
                repeatSpreadDb = if (o.isNull("spread")) null else o.optDouble("spread"),
                task = o.optString("task").ifBlank { group },
                levelStep = if (o.isNull("levelStep")) null else o.optInt("levelStep"),
                id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                irs = irsJ?.let { a -> (0 until a.length()).map { Store.floatsBase64(a.getString(it)) } } ?: emptyList(),
                irStarts = Store.ints(o.optJSONArray("irStarts")),
                latencies = latJ?.let { a -> (0 until a.length()).map { if (a.isNull(it)) null else a.getLong(it) } } ?: emptyList(),
                output = o.optString("output"),
            )
        }
    }
}
