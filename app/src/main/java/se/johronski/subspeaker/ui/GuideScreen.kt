@file:OptIn(ExperimentalLayoutApi::class)

package se.johronski.subspeaker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.AppServices
import se.johronski.subspeaker.Guide
import se.johronski.subspeaker.GuideController
import se.johronski.subspeaker.GuideController.Step
import se.johronski.subspeaker.SavedResult
import se.johronski.subspeaker.SweepController
import se.johronski.subspeaker.audio.SignalSpec
import se.johronski.subspeaker.audio.SweepAnalyzer
import se.johronski.subspeaker.audio.ToneGrid
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val prepItems = listOf(
    "Velodyne: ställ lågpassfiltret (LPF) på ungefär 90 Hz. Det görs på subben, inte med fjärrkontrollen.",
    "Velodyne: välj den förinställning du vill använda och stäng av nattläget (NIGHT).",
    "Velodyne: kör rumskalibreringen (EQ) med Velodyne-mikrofonen på lyssningsplatsen, enligt manualen.",
    "Raden överst: välj uppspelning och mikrofon, och ställ en låg till måttlig volym.",
    "Mikrofonen i öronhöjd på lyssningsplatsen. Ingen pratar under mätningarna.",
)

@Composable
fun GuideScreen(services: AppServices, setup: Setup) {
    val guide by services.guide.state.collectAsState()
    val sweep by services.sweep.state.collectAsState()

    // Stegknappar med förlopp.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Step.entries.forEachIndexed { i, s ->
            val p = Guide.progress(s, guide, sweep)
            val suffix = when {
                p == null -> ""
                p.complete -> " ✓"
                p.done > 0 -> " ${p.done}/${p.total}"
                else -> ""
            }
            FilterChip(
                selected = guide.step == s,
                onClick = { services.guide.go(s) },
                label = { Text("${i + 1}. ${s.title}$suffix") },
            )
        }
    }
    setup.problem?.let { Notice(it, Tone.WARN) }

    when (guide.step) {
        Step.PREP -> PrepStage(services, guide, sweep, setup)
        Step.MIC -> MicStage(services, sweep, setup)
        Step.GENELEC -> GenelecStage(services, guide, sweep, setup)
        Step.PHASE -> PhaseStage(services, guide, sweep, setup)
        Step.LEVEL -> LevelStage(services, guide, sweep, setup)
        Step.RESULT -> ResultStage(services, guide, sweep)
    }
}

@Composable
private fun PrepStage(services: AppServices, guide: GuideController.State, sweep: SweepController.State, setup: Setup) {
    Panel("Förberedelse") {
        Muted("Görs en gång. Det är enda gången du behöver resa dig, förutom i Genelec-steget.")
        prepItems.forEachIndexed { i, t ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { services.guide.toggleCheck(i) },
            ) {
                Checkbox(checked = i in guide.checks, onCheckedChange = { services.guide.toggleCheck(i) })
                Text(t, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
        Label("Genelec, båda: vilket Bass-läge står de på nu?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Guide.GENELEC.forEach { g ->
                FilterChip(
                    selected = guide.genelecBass == g,
                    onClick = { services.guide.setGenelecBass(g) },
                    label = { Text(Guide.genelecGroup(g)) },
                )
            }
        }
        Muted("Övriga omkopplare (Low bass, Treble, Tabletop, Level) på 0.")
    }
    Panel("Nivåkoll") {
        Muted("Ett kort svep som kontrollerar att volymen är lagom innan de riktiga mätningarna. Tar ungefär 15 sekunder.")
        Button(
            onClick = { services.sweep.levelCheck() },
            enabled = setup.canMeasure && !sweep.busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (sweep.busy) "Mäter …" else "Kör nivåkoll", fontSize = 17.sp) }
        sweep.check?.let { Notice(it.text, if (it.ok) Tone.GOOD else Tone.WARN) }
        sweep.error?.takeIf { !sweep.busy && sweep.check == null }?.let { Notice(it, Tone.WARN) }
    }
    Button(onClick = { services.guide.go(Step.MIC) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Nästa: mikrofonkoll") }
}

@Composable
private fun MicStage(services: AppServices, sweep: SweepController.State, setup: Setup) {
    Panel("Mikrofonkoll") {
        Muted(
            "Mät med en mikrofon. Koppla sedan in nästa mikrofon, tryck på Mikrofon i raden överst och välj den, " +
                "och mät igen på exakt samma plats. Appen jämför kurvorna. Steget kan hoppas över.",
        )
        SweepSettings(sweep.delaySeconds, sweep.repeats, !sweep.busy, { services.sweep.setDelay(it) }, { services.sweep.setRepeats(it) })
        val groups = sweep.groupsFor("mic")
        MeasureButton(sweep, setup.canMeasure, "Mät med: ${setup.micName}") {
            services.sweep.measure("Mikrofon", "mic", "Mikrofon", 1)
        }
        StatusLines(sweep)
        if (groups.isNotEmpty()) {
            ChartWithLegend(services, groups.map { ChartCurve(it.label.removePrefix("Mikrofon · ").substringAfter("– "), it.db) }, groups.map { it.label.removePrefix("Mikrofon · ") }, null)
        }
        if (groups.size >= 2) Notice(micVerdict(groups[0], groups[1]), Tone.GOOD)
        OutlinedButton(
            onClick = { services.destructive("Senaste mätningen togs bort") { services.sweep.removeLast("mic") } },
            enabled = !sweep.busy && groups.isNotEmpty(),
        ) { Text("Ta bort senaste") }
    }
    Button(onClick = { services.guide.go(Step.GENELEC) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(if (sweep.groupsFor("mic").isEmpty()) "Hoppa över" else "Nästa: Genelec")
    }
}

@Composable
private fun GenelecStage(services: AppServices, guide: GuideController.State, sweep: SweepController.State, setup: Setup) {
    val tasks = Guide.genelecTasks(guide.positions, guide.genelecBass)
    val verdict = Guide.genelecVerdict(sweep)
    TaskStage(
        services = services,
        title = "Genelec (valfritt)",
        intro = "Jämför Genelecens basomkopplare −2 och −4 dB. Görs före fas och nivå, eftersom omkopplaren " +
            "ändrar Genelecens nivå runt delningen. Kräver en promenad till högtalarna halvvägs. " +
            "Hoppar du över steget behålls ${Guide.genelecGroup(guide.genelecBass)}.",
        stage = "genelec",
        tasks = tasks,
        guide = guide,
        sweep = sweep,
        setup = setup,
        bestLabel = verdict?.best?.key,
        verdict = {
            verdict?.let { v ->
                VerdictText(v)
                val order = Guide.genelecOrder(guide.genelecBass)
                val bestG = order.firstOrNull { Guide.genelecGroup(it) == v.best.key }
                if (Guide.next(tasks, sweep.curves) == null && bestG != null && bestG != order.last()) {
                    Notice("Ställ tillbaka båda Genelecerna: ${Guide.bassSwitchText(bestG)}.", Tone.INFO)
                }
            }
        },
        nextLabel = "Nästa: fas",
        onNext = { services.guide.go(Step.PHASE) },
    )
}

@Composable
private fun PhaseStage(services: AppServices, guide: GuideController.State, sweep: SweepController.State, setup: Setup) {
    val tasks = Guide.phaseTasks(guide.positions)
    val verdict = Guide.phaseVerdict(sweep)
    TaskStage(
        services = services,
        title = "Fas",
        intro = "Fyra faslägen mäts på varje plats. Medelvärdet avgör vilket läge som ger jämnast övergång mellan sub och Genelec.",
        stage = "phase",
        tasks = tasks,
        guide = guide,
        sweep = sweep,
        setup = setup,
        bestLabel = verdict?.best?.key,
        verdict = {
            verdict?.let { v ->
                VerdictText(v)
                if (Guide.next(tasks, sweep.curves) == null) {
                    Notice("Tryck ${Guide.bestPhase(sweep)} på fjärrkontrollen innan du går vidare.", Tone.INFO)
                }
            }
        },
        nextLabel = "Nästa: nivå",
        onNext = { services.guide.go(Step.LEVEL) },
    )
}

@Composable
private fun LevelStage(services: AppServices, guide: GuideController.State, sweep: SweepController.State, setup: Setup) {
    val phase = Guide.bestPhase(sweep)
    val tasks = Guide.levelTasks(guide.positions, phase)
    val display = remember { mutableStateOf("") }
    TaskStage(
        services = services,
        title = "Nivå",
        intro = "Subnivån mäts i utgångsläget, två steg upp och två steg ner. Appen jämför subbens område " +
            "(25–63 Hz) med Genelecens (100–250 Hz) och räknar fram hur många steg som når målet.",
        stage = "level",
        tasks = tasks,
        guide = guide,
        sweep = sweep,
        setup = setup,
        bestLabel = null,
        legend = { g ->
            String.format(Locale.ROOT, "%s · basbalans %+.1f dB", g.label, SweepAnalyzer.balance(g.db))
        },
        beforeMeasure = { task -> DisplayField(task, guide, sweep, display) },
        onMeasure = { task ->
            val planned = Guide.plannedStep(task.group) ?: 0
            val disp = display.value.trim().toIntOrNull()
            var base = guide.subBaseline
            if (base == null && disp != null && sweep.curves.none { it.stage == "level" }) {
                base = disp - planned
                services.guide.setSubBaseline(base)
            }
            val actual = if (base != null && disp != null) disp - base else planned
            val group = Guide.levelGroup(actual)
            services.sweep.measure("$group · plats ${task.position}", "level", group, task.position, task.group, actual)
        },
        onClear = { services.guide.setSubBaseline(null) },
        verdict = {
            Label("Mål för basbalans")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.0, 3.0, 6.0).forEach { t ->
                    FilterChip(
                        selected = guide.targetBalance == t,
                        onClick = { services.guide.setTargetBalance(t) },
                        label = { Text(if (t == 0.0) "Rak 0 dB" else String.format(Locale.ROOT, "+%.0f dB", t)) },
                    )
                }
            }
            Muted(
                "Rak = subben lika stark som Genelecen. +3 dB är en vanlig, lätt baslyft. " +
                    "Telefonens mikrofon kan tappa lite i djupbas, så den verkliga baslyften blir något större än målet.",
            )
            val adv = Guide.levelAdvice(sweep, guide.targetBalance)
            if (adv != null) {
                Muted(
                    String.format(
                        Locale.ROOT,
                        "Basbalans i utgångsläget: %+.1f dB. Ett volymsteg ≈ %.1f dB.",
                        adv.balanceNow, adv.dbPerStep,
                    ),
                )
                Text("Rekommendation: ${Guide.stepText(adv.step)}", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Good)
                adv.warning?.let { Notice(it, Tone.WARN) }
                if (Guide.next(tasks, sweep.curves) == null) {
                    val base = guide.subBaseline
                    val now = Guide.currentLevelStep(sweep) ?: Guide.LEVELS.last()
                    Notice(
                        if (base != null) "Ställ subben så att displayen visar ${base + adv.step}."
                        else "${Guide.pressText(adv.step - now)} nu.",
                        Tone.INFO,
                    )
                }
            }
        },
        nextLabel = "Till resultat",
        onNext = { services.guide.go(Step.RESULT) },
    )
}

/** Fält för vad subbens display visar, så att missade tryck på fjärrkontrollen inte ger fel steg. */
@Composable
private fun DisplayField(task: Guide.Task, guide: GuideController.State, sweep: SweepController.State, display: MutableState<String>) {
    val planned = Guide.plannedStep(task.group) ?: 0
    val base = guide.subBaseline
    val first = sweep.curves.none { it.stage == "level" }
    LaunchedEffect(task, base) {
        display.value = if (base != null) "${base + planned}" else ""
    }
    if (base == null && !first) return
    OutlinedTextField(
        value = display.value,
        onValueChange = { v -> display.value = v.filter { it.isDigit() || it == '-' }.take(4) },
        label = {
            Text(
                if (base == null) "Vad visar subbens display nu? (valfritt)"
                else "Displayen ska visa ${base + planned} – ändra om den visar annat",
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    if (base == null) {
        Muted("Om subben visar volymen kan du skriva in den. Då räknar appen med det verkliga steget även om ett tryck inte gick fram.")
    }
}

@Composable
private fun ResultStage(services: AppServices, guide: GuideController.State, sweep: SweepController.State) {
    val pv = Guide.phaseVerdict(sweep)
    val lv = Guide.levelAdvice(sweep, guide.targetBalance)
    val gv = Guide.genelecVerdict(sweep)
    val actions = Guide.actions(guide, sweep)

    Panel("Gör så här") {
        Muted("Bocka av när inställningen är gjord.")
        actions.forEach { a ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { services.guide.toggleDone(a.key) },
            ) {
                Checkbox(checked = a.key in guide.done, onCheckedChange = { services.guide.toggleDone(a.key) })
                Text(a.text, fontSize = 16.sp, lineHeight = 22.sp)
            }
        }
        if (actions.isNotEmpty() && actions.all { it.key in guide.done }) Notice("Allt är inställt.", Tone.GOOD)
    }

    Panel("Sammanfattning") {
        ResultRow("Fas", pv?.let { "${Guide.bestPhase(sweep)}" } ?: "ej mätt", pv?.confidence)
        ResultRow("Subnivå", lv?.let { Guide.stepText(it.step) } ?: "ej mätt", null)
        ResultRow("Genelec bas", gv?.best?.key ?: "${Guide.genelecGroup(guide.genelecBass)} (ej jämfört)", gv?.confidence)
        ResultRow("Lågpass", "≈ 90 Hz (förinställt)", null)
        Button(
            onClick = {
                val lines = listOf(
                    "Fas: " + (pv?.let { "${Guide.bestPhase(sweep)} (${it.confidence.text})" } ?: "ej mätt"),
                    "Subnivå: " + (lv?.let { Guide.stepText(it.step) } ?: "ej mätt") +
                        (guide.subBaseline?.let { b -> lv?.let { " – display ${b + it.step}" } } ?: ""),
                    "Genelec: " + (gv?.best?.key ?: Guide.genelecGroup(guide.genelecBass)),
                    "Mål basbalans: " + String.format(Locale.ROOT, "%+.0f dB", guide.targetBalance),
                )
                services.guide.saveResult(
                    SavedResult(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date()), lines, Guide.resultCurve(sweep)),
                )
                services.notify("Resultatet sparat")
            },
            enabled = pv != null || lv != null,
        ) { Text("Spara resultat") }
        Muted("Sparade resultat finns kvar även om du börjar om, så att du kan jämföra efter en ändring i rummet.")
    }

    if (guide.history.isNotEmpty()) {
        var picked by remember { mutableStateOf(setOf(0)) }
        Panel("Tidigare resultat") {
            val current = Guide.resultCurve(sweep)
            val chosen = guide.history.withIndex().filter { it.index in picked && it.value.curve != null }
            val curves = buildList {
                current?.let { add(ChartCurve("Nu", it)) }
                chosen.forEach { (_, r) -> add(ChartCurve(r.date.takeLast(11), r.curve!!)) }
            }
            if (curves.isNotEmpty()) {
                val playback by services.playback.state.collectAsState()
                SweepChart(curves, highlight = if (current != null) 0 else null, markers = playback.markers)
            }
            Muted("Tryck på ett resultat för att visa det i diagrammet.")
            guide.history.forEachIndexed { i, r ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { picked = if (i in picked) picked - i else picked + i }
                        .padding(vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = i in picked, onCheckedChange = { picked = if (i in picked) picked - i else picked + i })
                        Text(r.date, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            services.destructive("Resultatet togs bort") { services.guide.removeResult(r) }
                            picked = emptySet()
                        }) { Text("Ta bort") }
                    }
                    r.lines.forEach { Muted(it, Modifier.padding(start = 48.dp)) }
                }
            }
        }
    }

    Panel("Bekräfta med musik") {
        Muted(
            "Spela musik med tydlig basgång, t.ex. kontrabas, orgel eller elbas. Alla toner ska höras lika tydligt, " +
                "utan att någon sticker ut eller försvinner. Basen ska kännas som att den kommer från Genelecerna, inte från hörnet.",
        )
    }
    OutlinedButton(
        onClick = {
            services.destructive("Guiden började om") {
                listOf("mic", "phase", "level", "genelec").forEach { services.sweep.clear(it) }
                services.guide.reset()
            }
        },
        enabled = !sweep.busy,
    ) { Text("Börja om guiden") }
}

@Composable
private fun TaskStage(
    services: AppServices,
    title: String,
    intro: String,
    stage: String,
    tasks: List<Guide.Task>,
    guide: GuideController.State,
    sweep: SweepController.State,
    setup: Setup,
    bestLabel: String?,
    verdict: @Composable () -> Unit,
    nextLabel: String,
    onNext: () -> Unit,
    legend: (SweepController.Group) -> String = { g ->
        String.format(Locale.ROOT, "%s · ojämnhet ±%.1f dB · %d mätn.", g.label, g.flatnessDb, g.count)
    },
    beforeMeasure: @Composable (Guide.Task) -> Unit = {},
    onMeasure: ((Guide.Task) -> Unit)? = null,
    onClear: () -> Unit = {},
) {
    Panel(title) {
        Muted(intro)
        val started = sweep.curves.any { it.stage == stage }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mätplatser:", fontSize = 15.sp)
            listOf(1, 3, 5).forEach { n ->
                FilterChip(
                    selected = guide.positions == n,
                    onClick = { services.guide.setPositions(n) },
                    enabled = !started,
                    label = { Text("$n") },
                )
            }
        }
        SweepSettings(sweep.delaySeconds, sweep.repeats, !sweep.busy, { services.sweep.setDelay(it) }, { services.sweep.setRepeats(it) })
        val next = Guide.next(tasks, sweep.curves)
        val done = Guide.doneCount(tasks, sweep.curves)
        Muted("$done av ${tasks.size} mätningar klara")
        if (next != null) {
            Panel(container = AccentSoft) {
                Text(next.instruction, fontSize = 18.sp, lineHeight = 25.sp)
            }
            beforeMeasure(next)
            MeasureButton(sweep, setup.canMeasure, "Mät: ${next.group} · plats ${next.position}") {
                if (onMeasure != null) {
                    onMeasure(next)
                } else {
                    services.sweep.measure("${next.group} · plats ${next.position}", next.stage, next.group, next.position)
                }
            }
        } else {
            Notice("Alla mätningar i steget är klara.", Tone.GOOD)
        }
        StatusLines(sweep)

        val groups = sweep.groupsFor(stage).let { g ->
            if (stage == "level") g.sortedBy { Guide.plannedStep(it.label) ?: 0 } else g
        }
        if (groups.isNotEmpty()) {
            val withBands = groups.all { it.count > 1 }
            ChartWithLegend(
                services,
                groups.map { ChartCurve(it.label, it.db, if (withBands) it.lo to it.hi else null) },
                groups.map(legend),
                groups.indexOfFirst { it.label == bestLabel }.takeIf { it >= 0 },
            )
            if (withBands) Muted("Skuggat band: spridning mellan mätplatserna. Brett band = rummet påverkar mycket.")
        }
        verdict()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { services.destructive("Senaste mätningen togs bort") { services.sweep.removeLast(stage) } },
                enabled = !sweep.busy && started,
            ) { Text("Ta bort senaste") }
            OutlinedButton(
                onClick = {
                    services.destructive("Stegets mätningar togs bort") {
                        services.sweep.clear(stage)
                        onClear()
                    }
                },
                enabled = !sweep.busy && started,
            ) { Text("Börja om steget") }
        }
    }
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(nextLabel) }
}

/** Diagram med tryckbar förklaring. Tryck i diagrammet spelar tonen vid den frekvensen. */
@Composable
fun ChartWithLegend(services: AppServices, curves: List<ChartCurve>, legend: List<String>, best: Int?) {
    val playback by services.playback.state.collectAsState()
    val cast by services.cast.state.collectAsState()
    val local by services.local.state.collectAsState()
    val settings by services.settings.state.collectAsState()
    var picked by remember(curves.size) { mutableStateOf<Int?>(null) }
    val playing = if (settings.output == se.johronski.subspeaker.Output.PHONE) local.playing else cast.playerState in setOf("Spelar", "Buffrar")
    val cursor = (playback.spec as? SignalSpec.Tone)?.takeIf { playing }?.freq
    SweepChart(
        curves,
        highlight = picked ?: best,
        markers = playback.markers,
        cursor = cursor,
        onTap = { f -> services.playback.playTone(ToneGrid.snap(f, 24)) },
    )
    Muted("Tryck i diagrammet för att lyssna på den frekvensen. + och − längst ner stegar tonen.")
    legend.forEachIndexed { i, text ->
        LegendRow(i, text, selected = (picked ?: best) == i) { picked = if (picked == i) null else i }
    }
}

@Composable
private fun MeasureButton(sweep: SweepController.State, canMeasure: Boolean, label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = canMeasure && !sweep.busy,
        modifier = Modifier.fillMaxWidth().height(60.dp),
    ) { Text(if (sweep.busy) "Mäter …" else label, fontSize = 17.sp) }
}

@Composable
private fun StatusLines(sweep: SweepController.State) {
    if (!sweep.busy && sweep.status.isNotEmpty()) Muted(sweep.status)
    sweep.error?.let { Notice(it, Tone.WARN) }
}

@Composable
private fun ResultRow(name: String, value: String, confidence: se.johronski.subspeaker.audio.Decision.Confidence?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 15.sp, color = InkMuted, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        confidence?.let { ConfidenceBadge(it) }
    }
}

private fun micVerdict(a: SweepController.Group, b: SweepController.Group): String {
    val f = SweepAnalyzer.FREQUENCIES
    fun norm(db: DoubleArray): DoubleArray {
        val ref = f.indices.filter { f[it] in 80.0..200.0 }.map { db[it] }.average()
        return DoubleArray(db.size) { db[it] - ref }
    }
    val na = norm(a.db)
    val nb = norm(b.db)
    val idx = f.indices.filter { f[it] in 30.0..200.0 }
    val worst = idx.maxBy { abs(na[it] - nb[it]) }
    val diff = abs(na[worst] - nb[worst])
    if (diff < 2.0) {
        return String.format(Locale.ROOT, "Mikrofonerna är överens inom %.1f dB mellan 30 och 200 Hz. Båda duger.", diff)
    }
    val low = f.indices.filter { f[it] in 25.0..50.0 }
    val aLow = low.map { na[it] }.average()
    val bLow = low.map { nb[it] }.average()
    val better = if (aLow >= bLow) a.label else b.label
    return String.format(
        Locale.ROOT,
        "Mikrofonerna skiljer upp till %.1f dB vid %.0f Hz. %s återger mest djupbas och tappar troligen minst – använd den.",
        diff, f[worst], better.removePrefix("Mikrofon · "),
    )
}
