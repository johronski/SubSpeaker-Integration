@file:OptIn(ExperimentalLayoutApi::class)

package se.johronski.subspeaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.Guide
import se.johronski.subspeaker.GuideController
import se.johronski.subspeaker.GuideController.Step
import se.johronski.subspeaker.SweepController
import se.johronski.subspeaker.audio.Decision
import se.johronski.subspeaker.audio.SweepAnalyzer
import java.util.Locale
import kotlin.math.abs

private val prepItems = listOf(
    "Velodyne: ställ lågpassfiltret (LPF) på ungefär 90 Hz. Det görs på subben, inte med fjärrkontrollen.",
    "Velodyne: välj den förinställning du vill använda och stäng av nattläget (NIGHT).",
    "Velodyne: kör rumskalibreringen (EQ) med Velodyne-mikrofonen på lyssningsplatsen, enligt manualen.",
    "Under Verktyg: välj uppspelning (Chromecast ansluten) och mikrofon, och ställ en låg till måttlig volym.",
    "Mikrofonen i öronhöjd på lyssningsplatsen. Ingen pratar under mätningarna.",
)

@Composable
fun GuideContent(
    guide: GuideController.State,
    sweep: SweepController.State,
    setupText: String,
    setupProblem: String?,
    micName: String,
    canMeasure: Boolean,
    onStep: (Step) -> Unit,
    onPositions: (Int) -> Unit,
    onToggleCheck: (Int) -> Unit,
    onGenelecBass: (Int) -> Unit,
    measure: (label: String, stage: String, group: String, position: Int) -> Unit,
    removeLast: (stage: String) -> Unit,
    clearStage: (stage: String) -> Unit,
    resetAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Step.entries.forEachIndexed { i, s ->
                FilterChip(
                    selected = guide.step == s,
                    onClick = { onStep(s) },
                    label = { Text("${i + 1}. ${s.title}") },
                )
            }
        }
        Muted(setupText)
        setupProblem?.let { Text(it, color = Warn) }

        when (guide.step) {
            Step.PREP -> Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Förberedelse", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Muted("Görs en gång. Det är enda gången du behöver resa dig, förutom i Genelec-steget.")
                    prepItems.forEachIndexed { i, t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onToggleCheck(i) },
                        ) {
                            Checkbox(checked = i in guide.checks, onCheckedChange = { onToggleCheck(i) })
                            Text(t)
                        }
                    }
                    Text("Genelec, båda: vilket Bass-läge står de på nu?", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Guide.GENELEC.forEach { g ->
                            FilterChip(
                                selected = guide.genelecBass == g,
                                onClick = { onGenelecBass(g) },
                                label = { Text(Guide.genelecGroup(g)) },
                            )
                        }
                    }
                    Muted("Övriga omkopplare (Low bass, Treble, Tabletop, Level) på 0.")
                    Button(onClick = { onStep(Step.MIC) }) { Text("Nästa: mikrofonkoll") }
                }
            }

            Step.MIC -> StageCard("Mikrofonkoll") {
                Muted(
                    "Mät med en mikrofon, byt mikrofon under Verktyg och mät igen på samma plats. " +
                        "Appen jämför kurvorna. Steget kan hoppas över.",
                )
                val groups = sweep.groupsFor("mic")
                MeasureButton(sweep, canMeasure, "Mät med: $micName") {
                    measure("Mikrofon · $micName", "mic", "Mikrofon · $micName", 1)
                }
                StatusLines(sweep)
                if (groups.isNotEmpty()) {
                    SweepChart(groups.map { it.db })
                    Legend(groups.map { it.label })
                }
                if (groups.size >= 2) Text(micVerdict(groups[0], groups[1]), color = Good)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { removeLast("mic") }, enabled = !sweep.busy && groups.isNotEmpty()) { Text("Ta bort senaste") }
                    Button(onClick = { onStep(Step.GENELEC) }) { Text(if (groups.isEmpty()) "Hoppa över" else "Nästa: Genelec") }
                }
            }

            Step.PHASE -> {
                val tasks = Guide.phaseTasks(guide.positions)
                TaskStage(
                    title = "Fas",
                    intro = "Fyra fasläge mäts på varje plats. Medelvärdet avgör vilket läge som ger jämnast övergång mellan sub och Genelec.",
                    stage = "phase",
                    tasks = tasks,
                    guide = guide,
                    sweep = sweep,
                    canMeasure = canMeasure,
                    onPositions = onPositions,
                    measure = measure,
                    removeLast = removeLast,
                    clearStage = clearStage,
                    verdict = {
                        Guide.phaseVerdict(sweep)?.let { v ->
                            VerdictText(v)
                            if (Guide.next(tasks, sweep.curves) == null) {
                                Text("Tryck ${Guide.bestPhase(sweep)} på fjärrkontrollen innan du går vidare.", fontWeight = FontWeight.Medium)
                            }
                        }
                    },
                    nextLabel = "Nästa: nivå",
                    onNext = { onStep(Step.LEVEL) },
                )
            }

            Step.LEVEL -> {
                val phase = Guide.bestPhase(sweep)
                val tasks = Guide.levelTasks(guide.positions, phase)
                TaskStage(
                    title = "Nivå",
                    intro = "Subnivån mäts i utgångsläget, två steg upp och två steg ner. Appen räknar fram bästa nivå.",
                    stage = "level",
                    tasks = tasks,
                    guide = guide,
                    sweep = sweep,
                    canMeasure = canMeasure,
                    onPositions = onPositions,
                    measure = measure,
                    removeLast = removeLast,
                    clearStage = clearStage,
                    verdict = {
                        val best = Guide.bestLevel(sweep)
                        if (best != null) {
                            Text(
                                "Bäst: ${if (best == 0) "utgångsläget" else Guide.levelGroup(best).removePrefix("Nivå ") + " steg"} " +
                                    "jämfört med där du började.",
                                color = Good,
                                fontWeight = FontWeight.Medium,
                            )
                            if (Guide.next(tasks, sweep.curves) == null) {
                                Text("${Guide.pressText(best - Guide.LEVELS.last())} nu.", fontWeight = FontWeight.Medium)
                            }
                        }
                    },
                    nextLabel = "Till resultat",
                    onNext = { onStep(Step.RESULT) },
                )
            }

            Step.GENELEC -> {
                val tasks = Guide.genelecTasks(guide.positions, guide.genelecBass)
                TaskStage(
                    title = "Genelec (valfritt)",
                    intro = "Jämför Genelecens basomkopplare −2 och −4 dB. Görs före fas och nivå, eftersom omkopplaren " +
                        "ändrar Genelecens nivå runt delningen. Kräver en promenad till högtalarna halvvägs. " +
                        "Hoppar du över steget behålls ${Guide.genelecGroup(guide.genelecBass)}.",
                    stage = "genelec",
                    tasks = tasks,
                    guide = guide,
                    sweep = sweep,
                    canMeasure = canMeasure,
                    onPositions = onPositions,
                    measure = measure,
                    removeLast = removeLast,
                    clearStage = clearStage,
                    verdict = {
                        Guide.genelecVerdict(sweep)?.let { v ->
                            VerdictText(v)
                            val order = Guide.genelecOrder(guide.genelecBass)
                            val bestG = order.firstOrNull { Guide.genelecGroup(it) == v.best.key }
                            if (Guide.next(tasks, sweep.curves) == null && bestG != null && bestG != order.last()) {
                                Text(
                                    "Ställ tillbaka båda Genelecerna: ${Guide.bassSwitchText(bestG)}.",
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    },
                    nextLabel = "Nästa: fas",
                    onNext = { onStep(Step.PHASE) },
                )
            }

            Step.RESULT -> StageCard("Resultat") {
                val pv = Guide.phaseVerdict(sweep)
                val lv = Guide.bestLevel(sweep)
                val gv = Guide.genelecVerdict(sweep)
                ResultRow("Fas", pv?.let { "${Guide.bestPhase(sweep)} (${it.confidence.text})" } ?: "ej mätt")
                ResultRow(
                    "Subnivå",
                    lv?.let { if (it == 0) "utgångsläget" else Guide.levelGroup(it).removePrefix("Nivå ") + " steg från utgångsläget" } ?: "ej mätt",
                )
                ResultRow(
                    "Genelec bas",
                    gv?.let { "${it.best.key} (${it.confidence.text})" } ?: "${Guide.genelecGroup(guide.genelecBass)} (ej jämfört)",
                )
                ResultRow("Lågpass på subben", "≈ 90 Hz")
                Spacer(Modifier.height(4.dp))
                Text("Bekräfta med musik", fontWeight = FontWeight.Medium)
                Muted(
                    "Spela musik med tydlig basgång, t.ex. kontrabas, orgel eller elbas. Alla toner ska höras lika tydligt, " +
                        "utan att någon sticker ut eller försvinner. Basen ska kännas som att den kommer från Genelecerna, inte från hörnet.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = resetAll, enabled = !sweep.busy) { Text("Börja om guiden") }
                }
            }
        }
    }
}

@Composable
private fun TaskStage(
    title: String,
    intro: String,
    stage: String,
    tasks: List<Guide.Task>,
    guide: GuideController.State,
    sweep: SweepController.State,
    canMeasure: Boolean,
    onPositions: (Int) -> Unit,
    measure: (String, String, String, Int) -> Unit,
    removeLast: (String) -> Unit,
    clearStage: (String) -> Unit,
    verdict: @Composable () -> Unit,
    nextLabel: String,
    onNext: () -> Unit,
) {
    StageCard(title) {
        Muted(intro)
        val started = sweep.curves.any { it.stage == stage }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mätplatser:")
            listOf(1, 3, 5).forEach { n ->
                FilterChip(
                    selected = guide.positions == n,
                    onClick = { onPositions(n) },
                    enabled = !started,
                    label = { Text("$n") },
                )
            }
        }
        val next = Guide.next(tasks, sweep.curves)
        val done = Guide.doneCount(tasks, sweep.curves)
        Text("$done av ${tasks.size} mätningar klara", fontSize = 14.sp)
        if (next != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(next.instruction, fontSize = 18.sp, modifier = Modifier.padding(12.dp))
            }
            MeasureButton(sweep, canMeasure, "Mät: ${next.group} · plats ${next.position}") {
                measure("${next.group} · plats ${next.position}", next.stage, next.group, next.position)
            }
        } else {
            Text("Alla mätningar i steget är klara.", color = Good)
        }
        StatusLines(sweep)

        val groups = sweep.groupsFor(stage)
        if (groups.isNotEmpty()) {
            SweepChart(groups.map { it.db }, bands = groups.filter { it.count > 1 }.let { g ->
                if (g.size == groups.size) g.map { it.lo to it.hi } else emptyList()
            })
            groups.forEachIndexed { i, g ->
                LegendRow(
                    i,
                    String.format(Locale.ROOT, "%s (n=%d) · avvikelse ±%.1f dB · nivå %.1f dB", g.label, g.count, g.flatnessDb, g.meanDb),
                )
            }
            if (groups.any { it.count > 1 }) Muted("Skuggat band: spridning mellan mätplatserna. Stor spridning = rummets inverkan.")
        }
        verdict()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { removeLast(stage) }, enabled = !sweep.busy && started) { Text("Mät om senaste") }
            OutlinedButton(onClick = { clearStage(stage) }, enabled = !sweep.busy && started) { Text("Börja om") }
        }
        Button(onClick = onNext) { Text(nextLabel) }
    }
}

@Composable
private fun StageCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
private fun MeasureButton(sweep: SweepController.State, canMeasure: Boolean, label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = canMeasure && !sweep.busy,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) { Text(if (sweep.busy) "Mäter …" else label, fontSize = 18.sp) }
}

@Composable
private fun StatusLines(sweep: SweepController.State) {
    if (sweep.status.isNotEmpty()) Text(sweep.status)
    sweep.error?.let { Text(it, color = Warn) }
}

@Composable
private fun Legend(labels: List<String>) {
    labels.forEachIndexed { i, l -> LegendRow(i, l) }
}

@Composable
private fun LegendRow(i: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(12.dp).background(SeriesColors[i % SeriesColors.size]))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun ResultRow(name: String, value: String) {
    Row {
        Text(name, fontWeight = FontWeight.Medium, modifier = Modifier.width(140.dp))
        Text(value)
    }
}

@Composable
private fun VerdictText(v: Decision.Verdict) {
    val color = when (v.confidence) {
        Decision.Confidence.SURE -> Good
        Decision.Confidence.LIKELY -> Warn
        Decision.Confidence.UNSURE -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        String.format(
            Locale.ROOT,
            "Jämnast: %s (±%.1f dB) – %s jämfört med %s (%.1f dB sämre).",
            v.best.key, v.best.deviation, v.confidence.text, v.runnerUp.key, v.marginDb,
        ),
        color = color,
        fontWeight = FontWeight.Medium,
    )
    if (v.confidence == Decision.Confidence.UNSURE) {
        Muted("Skillnaden är mindre än mätningens osäkerhet. Välj vilket som helst av de bästa, eller mät på fler platser.")
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
