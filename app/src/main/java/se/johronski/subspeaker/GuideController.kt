package se.johronski.subspeaker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
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
        curves.any { it.stage == t.stage && it.task == t.group && it.position == t.position }

    fun next(tasks: List<Task>, curves: List<SweepController.Curve>): Task? = tasks.firstOrNull { !isDone(it, curves) }

    fun doneCount(tasks: List<Task>, curves: List<SweepController.Curve>) = tasks.count { isDone(it, curves) }

    // ---------- Slutsatser ----------

    fun phaseVerdict(s: SweepController.State): Decision.Verdict? =
        Decision.compare(s.groupsFor("phase").map { it.option() })

    fun bestPhase(s: SweepController.State): Int? =
        phaseVerdict(s)?.best?.key?.removePrefix("Fas ")?.trim()?.toIntOrNull()

    /** Basbalans (sub mot Genelec) per volymsteg. */
    fun levelBalance(s: SweepController.State): Map<Int, Double> =
        s.groupsFor("level").mapNotNull { g ->
            g.label.removePrefix("Nivå").trim().replace("−", "-").replace("+", "").toIntOrNull()
                ?.let { it to se.johronski.subspeaker.audio.SweepAnalyzer.balance(g.db) }
        }.toMap()

    fun levelAdvice(s: SweepController.State, target: Double): Decision.LevelAdvice? =
        Decision.levelAdvice(levelBalance(s), target)

    fun stepText(step: Int): String = when {
        step == 0 -> "utgångsläget"
        step > 0 -> "+$step steg från utgångsläget"
        else -> "−${-step} steg från utgångsläget"
    }

    fun genelecVerdict(s: SweepController.State): Decision.Verdict? =
        Decision.compare(s.groupsFor("genelec").map { it.option() })

    fun pressText(delta: Int): String = when {
        delta > 0 -> "Tryck VOLUME + $delta gånger"
        delta < 0 -> "Tryck VOLUME − ${-delta} gånger"
        else -> "Ändra inte volymen"
    }

    /** Planerat volymsteg för en uppgift i nivåsteget, t.ex. "Nivå +2" → 2. */
    fun plannedStep(group: String): Int? =
        group.removePrefix("Nivå").trim().replace("−", "-").replace("+", "").toIntOrNull()

    /** Subbens volymsteg efter den senaste nivåmätningen (där subben står nu). */
    fun currentLevelStep(s: SweepController.State): Int? =
        s.curves.lastOrNull { it.stage == "level" }?.let { it.levelStep ?: plannedStep(it.group) }

    data class Progress(val done: Int, val total: Int) {
        val complete: Boolean get() = total > 0 && done >= total
    }

    /** Hur långt varje steg i guiden har kommit, för stegknapparna. */
    fun progress(step: GuideController.Step, g: GuideController.State, s: SweepController.State): Progress? = when (step) {
        GuideController.Step.PREP -> Progress(g.checks.size, PREP_ITEMS)
        GuideController.Step.MIC -> s.groupsFor("mic").size.let { if (it == 0) null else Progress(it, it) }
        GuideController.Step.GENELEC -> genelecTasks(g.positions, g.genelecBass).let { Progress(doneCount(it, s.curves), it.size) }
        GuideController.Step.PHASE -> phaseTasks(g.positions).let { Progress(doneCount(it, s.curves), it.size) }
        GuideController.Step.LEVEL -> levelTasks(g.positions, null).let { Progress(doneCount(it, s.curves), it.size) }
        GuideController.Step.RESULT -> null
    }

    const val PREP_ITEMS = 5

    /** Något att göra på resultatsidan, som användaren bockar av. */
    data class Action(val key: String, val text: String)

    fun actions(g: GuideController.State, s: SweepController.State): List<Action> = buildList {
        bestPhase(s)?.let { add(Action("phase", "Fas: tryck $it på fjärrkontrollen.")) }
        levelAdvice(s, g.targetBalance)?.let { adv ->
            val base = g.subBaseline
            val now = currentLevelStep(s) ?: 0
            val text = if (base != null) {
                "Subvolym: ställ subben så att displayen visar ${base + adv.step} (${stepText(adv.step)})."
            } else {
                "Subvolym: ${pressText(adv.step - now).replaceFirstChar { it.uppercase() }} (${stepText(adv.step)})."
            }
            add(Action("level", text))
        }
        val gv = genelecVerdict(s)
        val order = genelecOrder(g.genelecBass)
        val bestG = gv?.let { v -> order.firstOrNull { genelecGroup(it) == v.best.key } }
        when {
            bestG == null -> add(Action("genelec", "Genelec: behåll ${genelecGroup(g.genelecBass)}, ingen ändring."))
            gv != null && s.curves.any { it.stage == "genelec" } && bestG != order.last() ->
                add(Action("genelec", "Genelec: ställ båda på ${genelecGroup(bestG)} – ${bassSwitchText(bestG)}."))
            else -> add(Action("genelec", "Genelec: låt dem stå på ${genelecGroup(bestG)}."))
        }
        add(Action("lpf", "Lågpass på subben: låt stå på ungefär 90 Hz (förinställt i guiden, inte uppmätt)."))
    }

    /** Kurvan som bäst visar den valda inställningen, för att spara och jämföra senare. */
    fun resultCurve(s: SweepController.State): DoubleArray? {
        phaseVerdict(s)?.let { v -> s.groupsFor("phase").firstOrNull { it.label == v.best.key }?.let { return it.db } }
        s.groupsFor("level").firstOrNull { plannedStep(it.label) == 0 }?.let { return it.db }
        return s.groupsFor("phase").firstOrNull()?.db ?: s.groups.firstOrNull()?.db
    }
}

/** Ett sparat slutresultat, för att kunna jämföra med senare inställningar. */
data class SavedResult(
    val date: String,
    val lines: List<String>,
    /** Kurvor med namn, t.ex. "Tillsammans", "Genelec ensam", "Sub ensam", "Efter". */
    val curves: Map<String, DoubleArray> = emptyMap(),
    /** "Hälsokoll" eller "Live-kurva". */
    val source: String = "Hälsokoll",
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    /** Huvudkurvan: efter ändringarna om det finns, annars allt tillsammans. */
    val curve: DoubleArray?
        get() = curves["Efter"] ?: curves["Tillsammans"] ?: curves.values.firstOrNull()
}

class GuideController(private val store: Store) {
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
        /** Önskad basbalans (dB): sub 25–63 Hz mot Genelec 100–250 Hz. */
        val targetBalance: Double = 3.0,
        /** Vad subbens display visade i utgångsläget, om användaren angav det. */
        val subBaseline: Int? = null,
        /** Avbockade åtgärder på resultatsidan. */
        val done: Set<String> = emptySet(),
        val history: List<SavedResult> = emptyList(),
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state

    private fun set(f: (State) -> State) {
        _state.value = f(_state.value)
        save()
    }

    fun go(step: Step) = set { it.copy(step = step) }

    fun setPositions(n: Int) = set { it.copy(positions = n.coerceIn(1, 5)) }

    fun setTargetBalance(t: Double) = set { it.copy(targetBalance = t) }

    fun setGenelecBass(g: Int) = set { it.copy(genelecBass = g) }

    fun setSubBaseline(v: Int?) = set { it.copy(subBaseline = v) }

    fun toggleCheck(i: Int) = set { it.copy(checks = if (i in it.checks) it.checks - i else it.checks + i) }

    fun toggleDone(key: String) = set { it.copy(done = if (key in it.done) it.done - key else it.done + key) }

    fun saveResult(r: SavedResult) = set { it.copy(history = (listOf(r) + it.history).take(30)) }

    /** Lägger till eller uppdaterar ett resultat med samma id (överst om det är nytt). */
    fun upsertResult(r: SavedResult) = set { st ->
        val i = st.history.indexOfFirst { it.id == r.id }
        st.copy(history = if (i >= 0) st.history.toMutableList().also { it[i] = r } else (listOf(r) + st.history).take(30))
    }

    fun removeResult(r: SavedResult) = set { it.copy(history = it.history.filterNot { h -> h.id == r.id }) }

    /** Börjar om guiden men behåller sparade resultat. */
    fun reset() = set { State(history = it.history, genelecBass = it.genelecBass) }

    fun restore(s: State) = set { s }

    private fun save() {
        val s = _state.value
        val hist = JSONArray()
        s.history.forEach { r ->
            val cj = JSONObject()
            r.curves.forEach { (k, v) -> cj.put(k, Store.doubles(v)) }
            hist.put(
                JSONObject().put("id", r.id).put("date", r.date).put("source", r.source)
                    .put("lines", Store.strings(r.lines)).put("curves", cj),
            )
        }
        store.put(
            KEY,
            JSONObject()
                .put("step", s.step.name)
                .put("positions", s.positions)
                .put("checks", Store.ints(s.checks))
                .put("genelecBass", s.genelecBass)
                .put("target", s.targetBalance)
                .put("baseline", s.subBaseline ?: JSONObject.NULL)
                .put("done", Store.strings(s.done))
                .put("history", hist)
                .toString(),
        )
    }

    private fun load(): State {
        val raw = store.get(KEY) ?: return State()
        return try {
            val j = JSONObject(raw)
            val hist = j.optJSONArray("history") ?: JSONArray()
            State(
                step = Step.entries.firstOrNull { it.name == j.optString("step") } ?: Step.PREP,
                positions = j.optInt("positions", 3),
                checks = Store.ints(j.optJSONArray("checks")).toSet(),
                genelecBass = j.optInt("genelecBass", -4),
                targetBalance = j.optDouble("target", 3.0),
                subBaseline = if (j.isNull("baseline")) null else j.optInt("baseline"),
                done = Store.strings(j.optJSONArray("done")).toSet(),
                history = (0 until hist.length()).map { i ->
                    val o = hist.getJSONObject(i)
                    val cj = o.optJSONObject("curves")
                    val curves = if (cj != null) {
                        cj.keys().asSequence().mapNotNull { k -> Store.doubles(cj.optJSONArray(k))?.let { k to it } }.toMap()
                    } else {
                        // Äldre format: en enda kurva.
                        (if (o.isNull("curve")) null else Store.doubles(o.optJSONArray("curve")))?.let { mapOf("Kurva" to it) } ?: emptyMap()
                    }
                    SavedResult(
                        date = o.optString("date"),
                        lines = Store.strings(o.optJSONArray("lines")),
                        curves = curves,
                        source = o.optString("source").ifBlank { "Hälsokoll" },
                        id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    )
                },
            )
        } catch (e: Exception) {
            State()
        }
    }

    private companion object {
        const val KEY = "guide"
    }
}
