package se.johronski.subspeaker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import se.johronski.subspeaker.audio.Decision
import kotlin.math.abs

/** Guidens steg, uppgifter och slutsatser. Mätningarna själva ligger i SweepController. */
object Guide {
    val PHASES = listOf(0, 90, 180, 270)

    /** Volymsteg i mätordning. Efter sista steget står subben på -2. */
    val LEVELS = listOf(0, 2, -2)
    val GENELEC = listOf(-2, -4)

    data class Task(val stage: String, val group: String, val position: Int, val instruction: String)

    fun place(p: Int): String = when (p) {
        1 -> "mitt på lyssningsplatsen"
        2 -> "30 cm åt vänster"
        3 -> "30 cm åt höger"
        4 -> "30 cm bakåt"
        else -> "30 cm framåt"
    }

    fun phaseGroup(ph: Int) = "Fas $ph"
    fun levelGroup(lv: Int) = when {
        lv == 0 -> "Nivå 0"
        lv > 0 -> "Nivå +$lv"
        else -> "Nivå −${-lv}"
    }
    fun genelecGroup(g: Int) = "Bass −${abs(g)} dB"

    /** Omkopplarlägen i Genelecens grupp B för ett Bass-läge. */
    fun bassSwitchText(g: Int) =
        if (g == -2) "grupp B: omkopplare 4 (−2 dB) på 1 och omkopplare 3 (−4 dB) på 0"
        else "grupp B: omkopplare 3 (−4 dB) på 1 och omkopplare 4 (−2 dB) på 0"

    private fun placeText(p: Int, positions: Int) =
        if (positions == 1) "Ställ mikrofonen i öronhöjd på lyssningsplatsen. "
        else "Ställ mikrofonen på plats $p (${place(p)}), i öronhöjd. "

    fun phaseTasks(positions: Int): List<Task> = buildList {
        for (p in 1..positions) {
            PHASES.forEachIndexed { i, ph ->
                val pre = if (i == 0) placeText(p, positions) else ""
                add(Task("phase", phaseGroup(ph), p, "${pre}Tryck $ph på fjärrkontrollen och sedan Mät."))
            }
        }
    }

    fun levelTasks(positions: Int, phase: Int?): List<Task> = buildList {
        var prev = 0
        var first = true
        for (p in 1..positions) {
            LEVELS.forEachIndexed { i, lv ->
                val sb = StringBuilder()
                if (first && phase != null) sb.append("Kontrollera att fasen står på $phase. ")
                if (i == 0) sb.append(placeText(p, positions))
                val delta = lv - prev
                when {
                    first -> sb.append("Utgångsläge: ändra inte volymen. ")
                    delta > 0 -> sb.append("Tryck VOLUME + $delta gånger. ")
                    delta < 0 -> sb.append("Tryck VOLUME − ${-delta} gånger. ")
                    else -> sb.append("Ändra inte volymen. ")
                }
                sb.append("Tryck sedan Mät.")
                add(Task("level", levelGroup(lv), p, sb.toString()))
                prev = lv
                first = false
            }
        }
    }

    /** Ordning: nuvarande läge först, sedan det andra, så att det bara krävs en promenad. */
    fun genelecOrder(current: Int) = listOf(current, GENELEC.first { it != current })

    fun genelecTasks(positions: Int, current: Int): List<Task> = buildList {
        genelecOrder(current).forEachIndexed { si, g ->
            for (p in 1..positions) {
                val sb = StringBuilder()
                if (si == 0 && p == 1) sb.append("Genelecerna står på ${genelecGroup(g)}. ")
                if (si == 1 && p == 1) {
                    sb.append("Gå till båda Genelecerna och ställ ${bassSwitchText(g)}. ")
                }
                sb.append(placeText(p, positions))
                sb.append("Tryck sedan Mät.")
                add(Task("genelec", genelecGroup(g), p, sb.toString()))
            }
        }
    }

    fun isDone(t: Task, curves: List<SweepController.Curve>) =
        curves.any { it.stage == t.stage && it.group == t.group && it.position == t.position }

    fun next(tasks: List<Task>, curves: List<SweepController.Curve>): Task? = tasks.firstOrNull { !isDone(it, curves) }

    fun doneCount(tasks: List<Task>, curves: List<SweepController.Curve>) = tasks.count { isDone(it, curves) }

    // ---------- Slutsatser ----------

    fun phaseVerdict(s: SweepController.State): Decision.Verdict? =
        Decision.compare(s.groupsFor("phase").map { it.option() })

    fun bestPhase(s: SweepController.State): Int? =
        phaseVerdict(s)?.best?.key?.removePrefix("Fas ")?.trim()?.toIntOrNull()

    fun levelMap(s: SweepController.State): Map<Int, Double> =
        s.groupsFor("level").mapNotNull { g ->
            g.label.removePrefix("Nivå").trim().replace("−", "-").replace("+", "").toIntOrNull()?.let { it to g.flatnessDb }
        }.toMap()

    fun bestLevel(s: SweepController.State): Int? = Decision.bestLevelStep(levelMap(s))

    fun genelecVerdict(s: SweepController.State): Decision.Verdict? =
        Decision.compare(s.groupsFor("genelec").map { it.option() })

    fun pressText(delta: Int): String = when {
        delta > 0 -> "Tryck VOLUME + $delta gånger"
        delta < 0 -> "Tryck VOLUME − ${-delta} gånger"
        else -> "Ändra inte volymen"
    }
}

class GuideController {
    enum class Step(val title: String) {
        PREP("Förberedelse"),
        MIC("Mikrofon"),
        GENELEC("Genelec"),
        PHASE("Fas"),
        LEVEL("Nivå"),
        RESULT("Resultat"),
    }

    data class State(
        val step: Step = Step.PREP,
        val positions: Int = 3,
        val checks: Set<Int> = emptySet(),
        /** Genelecens nuvarande Bass-läge (dB). */
        val genelecBass: Int = -4,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun go(step: Step) {
        _state.value = _state.value.copy(step = step)
    }

    fun setPositions(n: Int) {
        _state.value = _state.value.copy(positions = n.coerceIn(1, 5))
    }

    fun setGenelecBass(g: Int) {
        _state.value = _state.value.copy(genelecBass = g)
    }

    fun toggleCheck(i: Int) {
        val c = _state.value.checks
        _state.value = _state.value.copy(checks = if (i in c) c - i else c + i)
    }

    fun reset() {
        _state.value = State()
    }
}
