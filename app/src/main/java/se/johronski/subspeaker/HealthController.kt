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
import se.johronski.subspeaker.audio.LiveSpectrum
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
        // Levande kurva
        val curve: DoubleArray? = null,
        val stable: Boolean = false,
        /** Kurvan när stämapparaten eller verktyget startade, som jämförelse. */
        val startCurve: DoubleArray? = null,
        val snapshots: List<Snapshot> = emptyList(),
        /** Låst nollnivå för diagrammet. */
        val refDb: Double? = null,
        val frozen: Boolean = false,
        val speed: LiveSpectrum.Speed = LiveSpectrum.Speed.NORMAL,
    )

    /** En sparad kurva att jämföra med. */
    data class Snapshot(val label: String, val db: DoubleArray)

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
        /** Varningar per mätning ("TOGETHER", "GENELEC", "SUB", "AFTER") när kurvan är osäker. */
        val warnings: Map<String, String> = emptyMap(),
        /** Sekunder sedan mätningen startade, för knappen "Visa det som hunnits mäta". */
        val elapsedS: Int = 0,
        /** Verktyget "Live-kurva" i menyn är igång. */
        val liveTool: Boolean = false,
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var phaseJob: Job? = null
    private var recorder: SweepRecorder? = null

    @Volatile
    private var stopEarly = false

    // Röstguiden hörs i mikrofonen. Sampel från då rösten talar och en sekund efter används inte.
    private val spoken = mutableListOf<IntRange>()
    private var speechFrom: Int? = null
    private var speechId = 0

    /** Läser upp texten och markerar tiden så att mätningen bortser från den. */
    private fun say(text: String, minGapMs: Long = 0) {
        val rec = recorder
        val id = synchronized(spoken) { ++speechId }
        val start = rec?.size
        val started = speaker.say(text, minGapMs) {
            synchronized(spoken) {
                val from = speechFrom
                if (id == speechId && from != null && recorder === rec && rec != null) {
                    spoken += from until rec.size + SweepAnalyzer.FS
                    speechFrom = null
                }
            }
        }
        if (started && start != null) synchronized(spoken) { if (speechFrom == null) speechFrom = start }
    }

    private val spectrum = LiveSpectrum()

    private fun ref(c: DoubleArray): Double =
        SweepAnalyzer.FREQUENCIES.indices.filter { SweepAnalyzer.FREQUENCIES[it] in 80.0..125.0 }.map { c[it] }.average()

    /** Uppdaterar den levande kurvan med de senaste 1,37 s. */
    private fun updateLive(rec: SweepRecorder, dt: Double) {
        val size = rec.size
        val a = size - Multitone.FRAME
        val skip = a < 0 || excluded().any { it.first <= size - 1 && it.last >= a }
        if (!spectrum.update(rec.tail(Multitone.FRAME + 600), dt, skip)) return
        val c = spectrum.curve ?: return
        val stable = spectrum.stable
        set {
            if (it.live.frozen) {
                it
            } else {
                it.copy(
                    live = it.live.copy(
                        curve = c,
                        stable = stable,
                        startCurve = it.live.startCurve ?: if (stable) c else null,
                        refDb = it.live.refDb ?: ref(c),
                    ),
                )
            }
        }
    }

    fun setSpeed(sp: LiveSpectrum.Speed) {
        spectrum.tauSeconds = sp.tau
        set { it.copy(live = it.live.copy(speed = sp)) }
    }

    fun toggleFreeze() = set { it.copy(live = it.live.copy(frozen = !it.live.frozen)) }

    fun resetScale() = set { s -> s.copy(live = s.live.copy(refDb = s.live.curve?.let { ref(it) })) }

    /** Sparar nuvarande kurva som jämförelse (högst tre i verktyget). */
    fun addSnapshot() = set { s ->
        val c = s.live.curve ?: return@set s
        val own = s.live.snapshots.filter { it.label.startsWith("Jämförelse") }
        val n = (1..3).firstOrNull { i -> own.none { it.label == "Jämförelse $i" } } ?: return@set s
        s.copy(live = s.live.copy(snapshots = s.live.snapshots + Snapshot("Jämförelse $n", c)))
    }

    fun clearSnapshots() = set { it.copy(live = it.live.copy(snapshots = emptyList())) }

    private fun shifted(ex: List<IntRange>, base: Int): List<IntRange> =
        ex.map { r -> (r.first - base).coerceAtLeast(0)..(if (r.last == Int.MAX_VALUE) Int.MAX_VALUE else r.last - base) }

    // ---------- Verktyget Live-kurva ----------

    fun startLiveTool() {
        stopAll()
        spectrum.reset()
        val speed = _state.value.live.speed
        spectrum.tauSeconds = speed.tau
        set { it.copy(liveTool = true, live = Live(listening = true, speed = speed), error = null) }
        job = scope.launch {
            try {
                val rec = startListening()
                var last = System.currentTimeMillis()
                while (isActive) {
                    delay(300)
                    rec.error?.let { throw IllegalStateException(it) }
                    ensurePlaying()
                    val now = System.currentTimeMillis()
                    updateLive(rec, (now - last) / 1000.0)
                    last = now
                }
            } catch (e: CancellationException) {
                stopListening()
            } catch (e: Exception) {
                stopListening()
                set { it.copy(error = e.message ?: e.javaClass.simpleName, live = it.live.copy(listening = false)) }
            }
        }
    }

    fun stopLiveTool() {
        stopAll()
        set { it.copy(liveTool = false) }
    }

    /** Stoppar ljud och mätning om hälsokollen är mitt i något, t.ex. när ett verktyg öppnas. */
    fun pauseAudio() {
        when (_state.value.screen) {
            Screen.TUNE -> leaveTune()
            Screen.CHECK, Screen.AFTER -> if (_state.value.measuring) cancel()
            else -> {}
        }
    }

    /** Intervall som mätningen ska bortse från just nu. */
    private fun excluded(): List<IntRange> = synchronized(spoken) {
        spoken.toList() + (speechFrom?.let { listOf(it until Int.MAX_VALUE) } ?: emptyList())
    }

    val target: Double get() = services.guide.state.value.targetBalance

    private fun set(f: (State) -> State) {
        _state.value = f(_state.value)
    }

    // ---------- Kollen ----------

    fun startCheck() {
        stopAll()
        single = false
        set {
            it.copy(
                screen = Screen.CHECK, part = Part.TOGETHER, together = null, genelec = null, sub = null,
                report = null, resolved = emptySet(), changes = emptyList(), after = null, afterReport = null,
                error = null, genelecOff = false, warnings = emptyMap(),
            )
        }
        measure(Part.TOGETHER)
    }

    /** Mäter nästa del (efter att användaren tryckt MUTE eller stängt av Genelecerna). */
    fun measurePart() = measure(_state.value.part)

    /** Avslutar pågående mätning och visar det som hunnits mäta, med varning. */
    fun finishEarly() {
        stopEarly = true
    }

    @Volatile
    private var single = false

    /** Mäter om en tidigare del, t.ex. efter en varning. Från diagnosen går appen tillbaka dit efteråt. */
    fun remeasure(part: Part) {
        single = _state.value.screen == Screen.DIAGNOSIS
        set { it.copy(part = part, screen = Screen.CHECK) }
        measure(part)
    }

    fun skipSub() {
        stopAll()
        diagnose()
    }

    private fun measure(part: Part, after: Boolean = false) {
        stopAll()
        val needed = 10
        set { it.copy(measuring = true, progress = 0f, error = null, levelOk = null, status = "Startar mätljudet …") }
        stopEarly = false
        job = scope.launch {
            try {
                val rec = startListening()
                say(if (after) "Efterkontroll. Rör telefonen långsamt runt huvudet." else "Rör telefonen långsamt runt huvudet.")
                val started = System.currentTimeMillis()
                var result: Multitone.Result? = null
                var timedOut = false
                while (isActive) {
                    delay(500)
                    rec.error?.let { throw IllegalStateException(it) }
                    val data = rec.snapshot()
                    val r = Multitone.analyze(data, exclude = excluded())
                    val frames = r?.frames ?: 0
                    val left = ((needed - frames).coerceAtLeast(0) * Multitone.FRAME / SweepAnalyzer.FS.toDouble()).toInt() + 1
                    val ok = r?.snrDb?.let { it >= 20 }
                    val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt()
                    set {
                        it.copy(
                            progress = (frames.toFloat() / needed).coerceIn(0f, 1f),
                            status = if (frames == 0) "Väntar på mätljudet …" else "$left s kvar",
                            levelOk = ok ?: it.levelOk,
                            elapsedS = elapsed,
                        )
                    }
                    if (frames >= needed) { result = r; break }
                    if (stopEarly || elapsed > 30) {
                        timedOut = true
                        result = Multitone.analyze(data, exclude = excluded(), fallback = true)
                        break
                    }
                }
                stopListening()
                val r = result ?: throw IllegalStateException(
                    "Ingen kurva kunde räknas fram – mätningen blev för kort. Kontrollera uppspelning och volym och försök igen.",
                )
                val key = if (after) "AFTER" else part.name
                val warn = buildList {
                    val secs = (r.frames * Multitone.FRAME / SweepAnalyzer.FS.toDouble()).toInt()
                    when {
                        !r.reliable -> add("Mätljudet gick inte att skilja från bakgrundsljudet – kurvan är mest brus.")
                        timedOut && r.frames < needed -> add("Bara $secs s mätljud hördes – kurvan är osäker.")
                    }
                    r.snrDb?.takeIf { it < 15 }?.let { add("Svagt mätljud (${it.toInt()} dB över bakgrunden) – kurvan är osäker.") }
                    if (r.peakDbfs > -1.0) add("Mikrofonen överstyrdes – kurvan kan vara fel. Sänk volymen lite.")
                }.joinToString(" ")
                set { it.copy(warnings = if (warn.isEmpty()) it.warnings - key else it.warnings + (key to warn)) }
                services.signalDone()
                if (after) {
                    val s = _state.value
                    val rep = Diagnosis.diagnose(r.db, s.genelec ?: r.db, s.sub, target)
                    set { it.copy(measuring = false, after = r.db, afterReport = rep, screen = Screen.DONE) }
                    speaker.say("Klart.")
                    save()
                    return@launch
                }
                if (single) {
                    single = false
                    set {
                        when (part) {
                            Part.TOGETHER -> it.copy(together = r.db)
                            Part.GENELEC -> it.copy(genelec = r.db)
                            Part.SUB -> it.copy(sub = r.db, genelecOff = true)
                        }.copy(measuring = false, status = "")
                    }
                    diagnose()
                    say(if (part == Part.SUB) "Klart. Slå på Genelecerna igen." else "Klart.")
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
                screen = Screen.TUNE, tune = action, live = Live(listening = true, speed = it.live.speed),
                phaseResults = emptyMap(), phasePrompt = null, phaseBest = null, levelStart = null, error = null,
            )
        }
        spectrum.reset()
        spectrum.tauSeconds = _state.value.live.speed.tau
        job = scope.launch {
            try {
                val rec = startListening()
                when (action) {
                    Diagnosis.Action.PHASE -> say("Ställ telefonen i öronhöjd på din plats.")
                    Diagnosis.Action.LOWPASS -> say("Lämna telefonen på din plats. Gå till subben och vrid lågpassratten långsamt nedåt.")
                    Diagnosis.Action.LEVEL -> say("Ställ telefonen i öronhöjd på din plats och använd volymknapparna på fjärrkontrollen.")
                }
                var lastFrames = 0
                var lastWord: String? = null
                var last = System.currentTimeMillis()
                while (isActive) {
                    delay(300)
                    rec.error?.let { throw IllegalStateException(it) }
                    ensurePlaying()
                    val now = System.currentTimeMillis()
                    updateLive(rec, (now - last) / 1000.0)
                    last = now
                    val size = rec.size
                    val frames = size / Multitone.FRAME
                    if (frames == lastFrames) continue
                    lastFrames = frames
                    val data = rec.tail(5 * Multitone.FRAME)
                    val base = size - data.size
                    val r = Multitone.analyze(data, 0, 3, shifted(excluded(), base)) ?: continue
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
                        say(word, 4000)
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
        set {
            it.copy(
                phaseResults = emptyMap(), phaseBest = null, phasePrompt = null,
                live = it.live.copy(snapshots = it.live.snapshots.filterNot { s -> s.label.startsWith("Fas ") }),
            )
        }
        phaseJob = scope.launch {
            try {
                delay(3000)
                val results = mutableMapOf<Int, Double>()
                for (ph in listOf(0, 90, 180, 270)) {
                    say("Tryck $ph.")
                    for (c in 4 downTo 1) {
                        set { it.copy(phasePrompt = ph, phaseCountdown = c) }
                        delay(1000)
                    }
                    set { it.copy(phaseCountdown = 0) }
                    val rec = recorder ?: return@launch
                    val mark = rec.size
                    while (rec.size < mark + 5 * Multitone.FRAME) delay(250)
                    val data = rec.snapshot()
                    val r = Multitone.analyze(data, mark, 3, excluded())
                    if (r != null) results[ph] = SweepAnalyzer.deviation(r.db)
                    set {
                        it.copy(
                            phaseResults = results.toMap(),
                            live = if (r != null) it.live.copy(snapshots = it.live.snapshots.filterNot { s -> s.label == "Fas $ph" } + Snapshot("Fas $ph", r.db)) else it.live,
                        )
                    }
                }
                val best = results.minByOrNull { it.value }?.key ?: return@launch
                set { it.copy(phasePrompt = null, phaseBest = best) }
                say("Klart. Tryck $best. Det var jämnast.")
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
        synchronized(spoken) {
            spoken.clear()
            speechFrom = null
        }
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
            .put("warnings", JSONObject().also { w -> s.warnings.forEach { (k, v) -> w.put(k, v) } })
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
                warnings = j.optJSONObject("warnings")?.let { w -> w.keys().asSequence().associateWith { k -> w.getString(k) } } ?: emptyMap(),
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
