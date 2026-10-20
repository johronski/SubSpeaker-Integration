package se.johronski.subspeaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import se.johronski.subspeaker.audio.Diagnosis
import se.johronski.subspeaker.audio.Multitone
import se.johronski.subspeaker.audio.SignalServer
import se.johronski.subspeaker.audio.SignalSpec
import se.johronski.subspeaker.audio.SweepAnalyzer
import se.johronski.subspeaker.audio.SweepRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hälsokollen: mät med rörlig mikrofon, ställ diagnos, åtgärda med stämapparaten och gör en efterkontroll.
 */
class HealthController(private val services: AppServices, private val store: Store, private val speaker: Speaker) {

    enum class Screen { START, CHECK, DIAGNOSIS, TUNE, AFTER, DONE }

    enum class Part(val title: String, val hint: String) {
        TOGETHER("Allt tillsammans", "sub och Genelec spelar"),
        GENELEC("Genelec ensam", "du trycker MUTE från soffan"),
        SUB("Sub ensam", "stäng av Genelecerna · kan hoppas över"),
    }

    /** Senaste värdena från stämapparaten. */
    data class Live(
        val db: DoubleArray? = null,
        val unevenDb: Double? = null,
        val balanceDb: Double? = null,
        /** Ojämnhet per ny ram, senaste ~40 s. */
        val history: List<Double> = emptyList(),
        val balanceHistory: List<Double> = emptyList(),
        val listening: Boolean = false,
    )

    data class State(
        val screen: Screen = Screen.START,
        val part: Part = Part.TOGETHER,
        /** Mätning pågår i kollen eller efterkontrollen. */
        val measuring: Boolean = false,
        val progress: Float = 0f,
        val status: String = "",
        val levelOk: Boolean? = null,
        val error: String? = null,
        val together: DoubleArray? = null,
        val genelec: DoubleArray? = null,
        val sub: DoubleArray? = null,
        val report: Diagnosis.Report? = null,
        /** Fynd som användaren åtgärdat. */
        val resolved: Set<String> = emptySet(),
        val changes: List<String> = emptyList(),
        /** Påminnelse om att slå på Genelecerna efter "sub ensam". */
        val genelecOff: Boolean = false,
        val tune: Diagnosis.Action? = null,
        val live: Live = Live(),
        // Fasguidning
        val phaseResults: Map<Int, Double> = emptyMap(),
        val phasePrompt: Int? = null,
        val phaseCountdown: Int = 0,
        val phaseBest: Int? = null,
        // Nivå: balansen när stämapparaten öppnades
        val levelStart: Double? = null,
        // Efterkontroll
        val after: DoubleArray? = null,
        val afterReport: Diagnosis.Report? = null,
        val lastDate: String? = null,
        val lastSummary: String? = null,
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var phaseJob: Job? = null
    private var recorder: SweepRecorder? = null

    val target: Double get() = services.guide.state.value.targetBalance

    private fun set(f: (State) -> State) {
        _state.value = f(_state.value)
    }

    // ---------- Kollen ----------

    fun startCheck() {
        stopAll()
        set {
            it.copy(
                screen = Screen.CHECK, part = Part.TOGETHER, together = null, genelec = null, sub = null,
                report = null, resolved = emptySet(), changes = emptyList(), after = null, afterReport = null,
                error = null, genelecOff = false,
            )
        }
        measure(Part.TOGETHER)
    }

    /** Mäter nästa del (efter att användaren tryckt MUTE eller stängt av Genelecerna). */
    fun measurePart() = measure(_state.value.part)

    fun skipSub() {
        stopAll()
        diagnose()
    }

    private fun measure(part: Part, after: Boolean = false) {
        stopAll()
        val needed = 10
        set { it.copy(measuring = true, progress = 0f, error = null, levelOk = null, status = "Startar mätljudet …") }
        speaker.say(if (after) "Efterkontroll. Rör telefonen långsamt runt huvudet." else "Rör telefonen långsamt runt huvudet.")
        job = scope.launch {
            try {
                val rec = startListening()
                val started = System.currentTimeMillis()
                var result: Multitone.Result? = null
                while (isActive) {
                    delay(500)
                    rec.error?.let { throw IllegalStateException(it) }
                    val data = rec.snapshot()
                    val r = Multitone.analyze(data)
                    val frames = r?.frames ?: 0
                    val left = ((needed - frames).coerceAtLeast(0) * Multitone.FRAME / SweepAnalyzer.FS.toDouble()).toInt() + 1
                    val ok = r?.snrDb?.let { it >= 20 }
                    set {
                        it.copy(
                            progress = (frames.toFloat() / needed).coerceIn(0f, 1f),
                            status = if (frames == 0) "Väntar på mätljudet …" else "$left s kvar",
                            levelOk = ok ?: it.levelOk,
                        )
                    }
                    if (frames >= needed) { result = r; break }
                    if (System.currentTimeMillis() - started > 45_000) {
                        throw IllegalStateException("Hörde för lite av mätljudet. Kontrollera uppspelning och volym och försök igen.")
                    }
                }
                stopListening()
                val r = result ?: return@launch
                if (r.peakDbfs > -1.0) {
                    set { it.copy(measuring = false, error = "Mikrofonen överstyrdes. Sänk volymen lite och försök igen.") }
                    return@launch
                }
                services.signalDone()
                if (after) {
                    val s = _state.value
                    val rep = Diagnosis.diagnose(r.db, s.genelec ?: r.db, s.sub, target)
                    set { it.copy(measuring = false, after = r.db, afterReport = rep, screen = Screen.DONE) }
                    speaker.say("Klart.")
                    save()
                    return@launch
                }
                when (part) {
                    Part.TOGETHER -> {
                        set { it.copy(measuring = false, together = r.db, part = Part.GENELEC, status = "") }
                        speaker.say("Bra. Tryck MUTE på fjärrkontrollen.")
                    }
                    Part.GENELEC -> {
                        set { it.copy(measuring = false, genelec = r.db, part = Part.SUB, status = "") }
                        speaker.say("Bra. Slå av MUTE. Stäng sedan av båda Genelecerna, eller hoppa över.")
                    }
                    Part.SUB -> {
                        set { it.copy(measuring = false, sub = r.db, genelecOff = true, status = "") }
                        diagnose()
                        speaker.say("Klart. Slå på Genelecerna igen.")
                    }
                }
            } catch (e: CancellationException) {
                stopListening()
            } catch (e: Exception) {
                stopListening()
                set { it.copy(measuring = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun diagnose() {
        val s = _state.value
        val t = s.together ?: return
        val g = s.genelec ?: return
        val rep = Diagnosis.diagnose(t, g, s.sub, target)
        set { it.copy(screen = Screen.DIAGNOSIS, report = rep, measuring = false) }
        save()
    }

    fun genelecBackOn() = set { it.copy(genelecOff = false) }

    fun cancel() {
        stopAll()
        set { it.copy(measuring = false, screen = if (it.report != null) Screen.DIAGNOSIS else Screen.START, status = "") }
    }

    // ---------- Stämapparaten ----------

    fun startTune(action: Diagnosis.Action) {
        stopAll()
        set {
            it.copy(
                screen = Screen.TUNE, tune = action, live = Live(listening = true),
                phaseResults = emptyMap(), phasePrompt = null, phaseBest = null, levelStart = null, error = null,
            )
        }
        when (action) {
            Diagnosis.Action.PHASE -> speaker.say("Ställ telefonen i öronhöjd på din plats.")
            Diagnosis.Action.LOWPASS -> speaker.say("Lämna telefonen på din plats. Gå till subben och vrid lågpassratten långsamt nedåt.")
            Diagnosis.Action.LEVEL -> speaker.say("Ställ telefonen i öronhöjd på din plats och använd volymknapparna på fjärrkontrollen.")
        }
        job = scope.launch {
            try {
                val rec = startListening()
                var lastFrames = 0
                var lastWord: String? = null
                while (isActive) {
                    delay(400)
                    rec.error?.let { throw IllegalStateException(it) }
                    ensurePlaying()
                    val size = rec.size
                    val frames = size / Multitone.FRAME
                    if (frames == lastFrames) continue
                    lastFrames = frames
                    val data = rec.snapshot()
                    val from = (data.size - 5 * Multitone.FRAME).coerceAtLeast(0)
                    val r = Multitone.analyze(data, from, 3) ?: continue
                    val uneven = SweepAnalyzer.deviation(r.db)
                    val bal = SweepAnalyzer.balance(r.db)
                    set {
                        it.copy(
                            live = it.live.copy(
                                db = r.db, unevenDb = uneven, balanceDb = bal,
                                history = (it.live.history + uneven).takeLast(30),
                                balanceHistory = (it.live.balanceHistory + bal).takeLast(30),
                            ),
                            levelStart = it.levelStart ?: bal,
                        )
                    }
                    // Röst för lågpass och nivå när beskedet ändras.
                    val word = when (action) {
                        Diagnosis.Action.LOWPASS -> lowpassWord(_state.value.live).first
                        Diagnosis.Action.LEVEL -> levelWord(bal).first
                        Diagnosis.Action.PHASE -> null
                    }
                    if (word != null && word != lastWord) {
                        speaker.say(word, 4000)
                        lastWord = word
                    }
                }
            } catch (e: CancellationException) {
                stopListening()
            } catch (e: Exception) {
                stopListening()
                set { it.copy(error = e.message ?: e.javaClass.simpleName, live = it.live.copy(listening = false)) }
            }
        }
        if (action == Diagnosis.Action.PHASE) startPhaseGuide()
    }

    /** Guidar genom de fyra faslägena: säger vilket läge som ska tryckas, väntar och mäter. */
    fun startPhaseGuide() {
        phaseJob?.cancel()
        set { it.copy(phaseResults = emptyMap(), phaseBest = null, phasePrompt = null) }
        phaseJob = scope.launch {
            try {
                delay(3000)
                val results = mutableMapOf<Int, Double>()
                for (ph in listOf(0, 90, 180, 270)) {
                    speaker.say("Tryck $ph.")
                    for (c in 4 downTo 1) {
                        set { it.copy(phasePrompt = ph, phaseCountdown = c) }
                        delay(1000)
                    }
                    set { it.copy(phaseCountdown = 0) }
                    val rec = recorder ?: return@launch
                    val mark = rec.size
                    while (rec.size < mark + 5 * Multitone.FRAME) delay(250)
                    val data = rec.snapshot()
                    val r = Multitone.analyze(data, mark, 3)
                    if (r != null) results[ph] = SweepAnalyzer.deviation(r.db)
                    set { it.copy(phaseResults = results.toMap()) }
                }
                val best = results.minByOrNull { it.value }?.key ?: return@launch
                set { it.copy(phasePrompt = null, phaseBest = best) }
                speaker.say("Klart. Tryck $best. Det var jämnast.")
            } catch (_: CancellationException) {
            }
        }
    }

    /** Ord för lågpass-stämapparaten: (rubrik, underrad). */
    fun lowpassWord(l: Live): Pair<String, String> {
        val h = l.history
        if (h.size < 3) return "Lyssnar" to "vrid långsamt"
        val now = h.takeLast(2).average()
        val before = h.dropLast(2).takeLast(2).average()
        val best = h.min()
        return when {
            now <= best + 0.15 && h.size >= 5 -> "Bäst hittills" to "stanna här, eller fortsätt lite"
            now < before - 0.2 -> "Bättre" to "fortsätt lite till"
            now > before + 0.2 -> "Sämre" to "vrid tillbaka"
            else -> "Vrid långsamt" to "inget har ändrats"
        }
    }

    /** Ord för nivå-stämapparaten: (rubrik, underrad). */
    fun levelWord(balance: Double): Pair<String, String> {
        val d = balance - target
        return when {
            d > 1.0 -> "Sänk subben" to String.format(Locale.ROOT, "%.0f dB för stark – tryck VOLUME −", d)
            d < -1.0 -> "Höj subben" to String.format(Locale.ROOT, "%.0f dB för svag – tryck VOLUME +", -d)
            else -> "Lagom" to "basen är i balans"
        }
    }

    /** Avslutar stämapparaten och markerar fyndet som åtgärdat. */
    fun finishTune() {
        val s = _state.value
        val action = s.tune ?: return
        val (key, change) = when (action) {
            Diagnosis.Action.PHASE -> "cancel" to (s.phaseBest?.let { "Fas: $it" } ?: "Fas: justerad")
            Diagnosis.Action.LOWPASS -> "lowpass" to "Lågpass: justerat"
            Diagnosis.Action.LEVEL -> "level" to (
                s.levelStart?.let { st ->
                    s.live.balanceDb?.let { now -> String.format(Locale.ROOT, "Subvolym: %+.0f dB", now - st) }
                } ?: "Subvolym: justerad"
                )
        }
        stopAll()
        set { it.copy(screen = Screen.DIAGNOSIS, tune = null, resolved = it.resolved + key, changes = (it.changes.filterNot { c -> c.substringBefore(":") == change.substringBefore(":") } + change)) }
        save()
    }

    fun leaveTune() {
        stopAll()
        set { it.copy(screen = Screen.DIAGNOSIS, tune = null) }
    }

    // ---------- Efterkontroll ----------

    fun startAfter() {
        set { it.copy(screen = Screen.AFTER) }
        measure(Part.TOGETHER, after = true)
    }

    fun saveResult() {
        val s = _state.value
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
        val lines = s.changes.ifEmpty { listOf("Inga ändringar") } +
            (s.afterReport?.findings?.filter { it.action == null }?.map { it.title } ?: emptyList())
        services.guide.saveResult(SavedResult(date, lines, s.after ?: s.together))
        set { it.copy(lastDate = date.substringBefore(" "), lastSummary = summary(s)) }
        save()
        services.notify("Resultatet sparat")
    }

    fun summary(s: State): String {
        val rep = s.afterReport ?: s.report ?: return ""
        val fixed = s.resolved.size
        val word = if (rep.actionable.isEmpty()) "Bra" else "${rep.actionable.size} saker kvar"
        return if (fixed > 0) "$word · $fixed åtgärdade" else word
    }

    fun home() {
        stopAll()
        set { it.copy(screen = Screen.START) }
    }

    /** Bakåtknappen. Returnerar false om appen ska stängas. */
    fun back(): Boolean {
        return when (_state.value.screen) {
            Screen.START -> false
            Screen.CHECK, Screen.AFTER -> { cancel(); true }
            Screen.TUNE -> { leaveTune(); true }
            Screen.DIAGNOSIS, Screen.DONE -> { home(); true }
        }
    }

    // ---------- Ljud in och ut ----------

    private var playStarted = 0L

    private suspend fun startListening(): SweepRecorder {
        withContext(Dispatchers.Main) {
            services.mic.stop()
            services.local.stop()
            services.playback.clearSpec()
        }
        val rec = SweepRecorder()
        rec.start(services.micSource(), services.micDevice())
        recorder = rec
        play()
        return rec
    }

    private suspend fun play() {
        val spec = SignalSpec.Noise(60.0)
        val settings = services.settings.value
        withContext(Dispatchers.Main) {
            if (settings.output == Output.CAST) {
                val url = services.signalUrl(spec.path) ?: throw IllegalStateException("Telefonen har ingen wifi-adress")
                services.cast.play(url, "Mätljud", spec.durationMs)
            } else {
                services.local.play(spec, services.outputDevice(), settings.method, "http://127.0.0.1:${SignalServer.PORT}/${spec.path}")
            }
        }
        playStarted = System.currentTimeMillis()
    }

    /** Startar om mätljudet när filen tagit slut, så att stämapparaten kan gå hur länge som helst. */
    private suspend fun ensurePlaying() {
        val elapsed = System.currentTimeMillis() - playStarted
        val settings = services.settings.value
        val idle = if (settings.output == Output.CAST) {
            services.cast.state.value.playerState == "Vilar"
        } else {
            !services.local.state.value.playing
        }
        if (elapsed > 61_000 || (idle && elapsed > 8_000)) play()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun stopListening() {
        recorder?.stop()
        recorder = null
        // Telefonens spelare (ExoPlayer) måste stoppas från huvudtråden.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) services.local.stop() else main.post { services.local.stop() }
        if (services.cast.state.value.connected) services.cast.stop()
    }

    private fun stopAll() {
        phaseJob?.cancel()
        phaseJob = null
        job?.cancel()
        job = null
        stopListening()
        speaker.stop()
        set { it.copy(live = it.live.copy(listening = false), measuring = false) }
    }

    // ---------- Lagring ----------

    private fun save() {
        val s = _state.value
        val j = JSONObject()
            .put("together", s.together?.let { Store.doubles(it) } ?: JSONObject.NULL)
            .put("genelec", s.genelec?.let { Store.doubles(it) } ?: JSONObject.NULL)
            .put("sub", s.sub?.let { Store.doubles(it) } ?: JSONObject.NULL)
            .put("after", s.after?.let { Store.doubles(it) } ?: JSONObject.NULL)
            .put("resolved", Store.strings(s.resolved))
            .put("changes", Store.strings(s.changes))
            .put("lastDate", s.lastDate ?: JSONObject.NULL)
            .put("lastSummary", s.lastSummary ?: JSONObject.NULL)
        store.put(KEY, j.toString())
    }

    private fun load(): State {
        val raw = store.get(KEY) ?: return State()
        return try {
            val j = JSONObject(raw)
            fun arr(k: String) = if (j.isNull(k)) null else Store.doubles(j.optJSONArray(k))?.takeIf { it.size == SweepAnalyzer.FREQUENCIES.size }
            State(
                together = arr("together"),
                genelec = arr("genelec"),
                sub = arr("sub"),
                after = arr("after"),
                resolved = Store.strings(j.optJSONArray("resolved")).toSet(),
                changes = Store.strings(j.optJSONArray("changes")),
                lastDate = if (j.isNull("lastDate")) null else j.optString("lastDate"),
                lastSummary = if (j.isNull("lastSummary")) null else j.optString("lastSummary"),
            ).let { s ->
                val t = s.together
                val g = s.genelec
                if (t != null && g != null) s.copy(report = Diagnosis.diagnose(t, g, s.sub, services.guide.state.value.targetBalance)) else s
            }
        } catch (e: Exception) {
            State()
        }
    }

    /** Öppna senaste diagnosen igen. */
    fun openLast() {
        val s = _state.value
        if (s.after != null && s.afterReport == null) {
            val g = s.genelec
            if (g != null) set { it.copy(afterReport = Diagnosis.diagnose(s.after, g, s.sub, target)) }
        }
        set { it.copy(screen = if (it.report != null) Screen.DIAGNOSIS else Screen.START) }
    }

    private companion object {
        const val KEY = "health"
    }
}
