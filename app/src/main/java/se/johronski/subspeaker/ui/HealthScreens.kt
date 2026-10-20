@file:OptIn(ExperimentalLayoutApi::class)

package se.johronski.subspeaker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.AppServices
import se.johronski.subspeaker.HealthController
import se.johronski.subspeaker.HealthController.Screen
import se.johronski.subspeaker.audio.Diagnosis
import java.util.Locale

/** Hälsokollens flöde: Start → Kolla → Diagnos → Åtgärda → Klart. */
@Composable
fun HealthFlow(services: AppServices, setup: Setup) {
    val s by services.health.state.collectAsState()
    BackHandler(enabled = s.screen != Screen.START) { services.health.back() }
    when (s.screen) {
        Screen.START -> StartScreen(services, s, setup)
        Screen.CHECK -> CheckScreen(services, s, after = false)
        Screen.AFTER -> CheckScreen(services, s, after = true)
        Screen.DIAGNOSIS -> DiagnosisScreen(services, s)
        Screen.TUNE -> when (s.tune) {
            Diagnosis.Action.PHASE -> PhaseTuner(services, s)
            Diagnosis.Action.LOWPASS -> LowpassTuner(services, s)
            Diagnosis.Action.LEVEL -> LevelTuner(services, s)
            null -> DiagnosisScreen(services, s)
        }
        Screen.DONE -> DoneScreen(services, s)
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, color: Color = Accent, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = Modifier.fillMaxWidth().height(58.dp),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Kicker(text: String, color: Color = InkMuted) {
    Text(text, fontSize = 14.sp, color = color, fontWeight = FontWeight.Medium)
}

@Composable
private fun Title(text: String, size: Int = 28) {
    Text(text, fontSize = size.sp, lineHeight = (size + 6).sp, fontWeight = FontWeight.Medium)
}

// ---------- Start ----------

@Composable
private fun StartScreen(services: AppServices, s: HealthController.State, setup: Setup) {
    Page {
        Spacer(Modifier.height(16.dp))
        Title("Hur låter basen?", 34)
        Text(
            "En kort koll tar reda på om sub och Genelec spelar bra ihop – och vad du i så fall ska ändra.",
            fontSize = 17.sp, lineHeight = 25.sp, color = InkMuted,
        )
        setup.problem?.let { p ->
            Box(Modifier.clickable { if (!setup.hasMicPermission) setup.requestPermission() else setup.openSetup() }) {
                Notice(p, Tone.WARN)
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Button(
                onClick = { services.health.startCheck() },
                enabled = setup.canMeasure,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier
                    .size(244.dp)
                    .border(14.dp, AccentSoft, CircleShape)
                    .padding(14.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Kolla nu", fontSize = 28.sp, fontWeight = FontWeight.Medium)
                    Text("ungefär 1 minut", fontSize = 14.sp)
                }
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.Top) {
                Spacer(Modifier.size(8.dp).clip(CircleShape).background(Accent))
                Spacer(Modifier.width(12.dp))
                Text(
                    "Sätt dig på din vanliga plats. Du håller telefonen i handen och rör den långsamt runt huvudet medan den lyssnar.",
                    fontSize = 15.sp, lineHeight = 21.sp,
                )
            }
        }
        if (s.lastDate != null && s.report != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { services.health.openLast() }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Senaste kollen · ${s.lastDate}", fontSize = 14.sp, color = InkMuted)
                    Text(s.lastSummary ?: "", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Text("›", fontSize = 22.sp, color = InkMuted)
            }
        } else if (s.report != null) {
            TextButton(onClick = { services.health.openLast() }) { Text("Visa senaste diagnosen") }
        }
    }
}

// ---------- Kollen ----------

@Composable
private fun CheckScreen(services: AppServices, s: HealthController.State, after: Boolean) {
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker(if (after) "Efterkontroll" else "Hälsokoll")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { services.health.cancel() }) { Text("Avbryt", color = Bad, fontSize = 15.sp) }
        }
        if (!after) {
            HealthController.Part.entries.forEach { p ->
                val done = p.ordinal < s.part.ordinal
                val current = p == s.part
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (current) AccentSoft else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (current) Accent else if (done) Good else Color.Transparent)
                            .border(1.5.dp, if (current || done) Color.Transparent else InkMuted, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (done) "✓" else "${p.ordinal + 1}", fontSize = 14.sp, color = if (current || done) Color.White else InkMuted)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(p.title, fontSize = 16.sp, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal)
                        Text(if (current && s.measuring) "lyssnar nu" else p.hint, fontSize = 13.sp, color = InkMuted)
                    }
                }
            }
        }

        if (s.measuring) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.size(200.dp),
                    color = Accent,
                    trackColor = CardAlt,
                    strokeWidth = 8.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.status, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
            }
            Text(
                "Rör telefonen långsamt runt huvudet",
                fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "i öronhöjd, ungefär en armlängd · var tyst under tiden",
                fontSize = 16.sp, color = InkMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            when (s.levelOk) {
                true -> Notice("Ljudet hörs tydligt – bra nivå", Tone.GOOD)
                false -> Notice("Mätljudet är svagt. Höj volymen lite.", Tone.WARN)
                null -> {}
            }
            if (s.elapsedS >= 10) {
                OutlinedButton(onClick = { services.health.finishEarly() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text("Visa det som hunnits mäta")
                }
                Muted("Kurvan visas med en varning om mätningen blev kort eller svag.")
            }
        } else {
            s.error?.let { Notice(it, Tone.WARN) }
            // Varning för den del som just mättes, med möjlighet att mäta om den.
            val prev = HealthController.Part.entries.getOrNull(s.part.ordinal - 1)
            if (!after && prev != null) {
                s.warnings[prev.name]?.let { w ->
                    Notice("${prev.title}: $w", Tone.WARN)
                    TextButton(onClick = { services.health.remeasure(prev) }) { Text("Mät om ${prev.title.lowercase(Locale.ROOT)}") }
                }
            }
            when {
                after || (s.part == HealthController.Part.TOGETHER) -> {
                    BigButton(if (s.error != null) "Försök igen" else "Mät") {
                        if (after) services.health.startAfter() else services.health.measurePart()
                    }
                }
                s.part == HealthController.Part.GENELEC -> {
                    Panel(container = AccentSoft) {
                        Kicker("Nu", Accent)
                        Text("Tryck MUTE på Velodynens fjärrkontroll.", fontSize = 20.sp, lineHeight = 28.sp)
                        Muted("Genelecerna fortsätter spela – bara subben tystnar.")
                    }
                    BigButton("Gjort – mät") { services.health.measurePart() }
                }
                s.part == HealthController.Part.SUB -> {
                    Panel(container = AccentSoft) {
                        Kicker("Nu", Accent)
                        Text("Slå av MUTE. Stäng sedan av båda Genelecerna.", fontSize = 20.sp, lineHeight = 28.sp)
                        Muted("Då hör appen subben ensam och kan bedöma lågpasset. Det kräver en promenad – du kan hoppa över.")
                    }
                    BigButton("Gjort – mät") { services.health.measurePart() }
                    OutlinedButton(onClick = { services.health.skipSub() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Text("Hoppa över – slå bara av MUTE")
                    }
                }
            }
        }
    }
}

// ---------- Diagnos ----------

@Composable
private fun DiagnosisScreen(services: AppServices, s: HealthController.State) {
    val rep = s.report ?: return
    val numbered = rep.findings.mapIndexed { i, f -> i + 1 to f }
    var why by remember { mutableStateOf<String?>(null) }
    var showCurves by remember { mutableStateOf(false) }
    Page {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Kicker("Hälsokoll klar")
            Title(rep.headline)
        }
        if (s.warnings.isNotEmpty()) {
            Panel(container = WarnSoft) {
                Text("Osäkra mätningar", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                s.warnings.forEach { (k, w) ->
                    val name = when (k) {
                        "TOGETHER" -> "Allt tillsammans"
                        "GENELEC" -> "Genelec ensam"
                        "SUB" -> "Sub ensam"
                        else -> "Efterkontrollen"
                    }
                    Text("$name: $w", fontSize = 15.sp, lineHeight = 21.sp)
                    HealthController.Part.entries.firstOrNull { it.name == k }?.let { part ->
                        TextButton(onClick = { services.health.remeasure(part) }) {
                            Text("Mät om ${name.lowercase(Locale.ROOT)}" + if (part == HealthController.Part.GENELEC) " (tryck MUTE först)" else if (part == HealthController.Part.SUB) " (Genelecerna av)" else "")
                        }
                    }
                }
                Muted("Diagnosen bygger på dessa kurvor. Höj volymen lite och gör om kollen för ett säkrare resultat.")
            }
        }
        if (s.genelecOff) {
            Panel(container = WarnSoft) {
                Text("Slå på Genelecerna igen innan du fortsätter.", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                OutlinedButton(onClick = { services.health.genelecBackOn() }) { Text("Gjort") }
            }
        }
        Panel {
            BassLandscape(
                s.together ?: return@Panel,
                marks = numbered.filter { it.second.freq != null && it.second.freq!! < 300 }
                    .map { (n, f) -> LandscapeMark(f.freq!!, n, f.action != null) },
            )
            TextButton(onClick = { showCurves = !showCurves }) { Text(if (showCurves) "Dölj kurvor ▴" else "Visa kurvor ▾") }
            if (showCurves) {
                CurveExplorer(
                    services,
                    healthCurves(s, after = false),
                    crossover = rep.crossoverHz,
                    numbered = numbered.mapNotNull { (n, f) -> f.freq?.let { it to n } },
                )
            }
        }
        numbered.forEach { (n, f) ->
            val resolved = f.key in s.resolved
            Panel(container = if (f.action == null) CardAlt else CardColor) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (f.action != null) SeriesColors[1] else InkMuted, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$n", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (f.action != null) SeriesColors[1] else InkMuted)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(f.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Muted(f.detail)
                        if (why == f.key && f.why != null) Text(f.why, fontSize = 15.sp, lineHeight = 21.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 38.dp)) {
                    if (f.action != null) {
                        if (resolved) {
                            Text("Åtgärdad ✓", color = Good, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 10.dp))
                            TextButton(onClick = { services.health.startTune(f.action) }) { Text("Gör igen") }
                        } else {
                            Button(onClick = { services.health.startTune(f.action) }, modifier = Modifier.height(44.dp)) { Text("Åtgärda") }
                        }
                    }
                    if (f.why != null) {
                        TextButton(onClick = { why = if (why == f.key) null else f.key }) { Text(if (why == f.key) "Dölj" else "Varför?") }
                    }
                }
            }
        }
        rep.good.forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("✓", color = Good, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.width(10.dp))
                Text(g, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        BigButton(if (s.resolved.isNotEmpty()) "Gör efterkontroll" else if (rep.actionable.isEmpty()) "Klart" else "Gör efterkontroll ändå") {
            if (rep.actionable.isEmpty() && s.resolved.isEmpty()) services.health.home() else services.health.startAfter()
        }
        TextButton(onClick = { services.health.startCheck() }, modifier = Modifier.fillMaxWidth()) { Text("Gör om hela kollen") }
    }
}

// ---------- Stämapparaten ----------

@Composable
private fun TunerHeader(services: AppServices, s: HealthController.State) {
    val actionable = s.report?.actionable ?: emptyList()
    val idx = actionable.indexOfFirst { it.action == s.tune }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { services.health.leaveTune() }) { Text("‹ Diagnos", fontSize = 15.sp) }
        Spacer(Modifier.weight(1f))
        if (idx >= 0) Kicker("Åtgärd ${idx + 1} av ${actionable.size}")
    }
    s.error?.let { Notice(it, Tone.BAD) }
}

@Composable
private fun PhaseTuner(services: AppServices, s: HealthController.State) {
    val live = s.live
    Page {
        TunerHeader(services, s)
        Title("Hitta bästa fasen")
        Panel(container = AccentSoft) {
            when {
                s.phaseBest != null -> {
                    Kicker("Klart", Accent)
                    Text("Tryck ${s.phaseBest} på fjärrkontrollen – det var jämnast.", fontSize = 20.sp, lineHeight = 28.sp)
                }
                s.phasePrompt != null && s.phaseCountdown > 0 -> {
                    Kicker("Nu", Accent)
                    Text("Tryck ${s.phasePrompt} på fjärrkontrollen", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    Muted("Appen mäter om ${s.phaseCountdown} s")
                }
                s.phasePrompt != null -> {
                    Kicker("Mäter", Accent)
                    Text("Fas ${s.phasePrompt} – håll tyst", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
                else -> {
                    Kicker("Förbered", Accent)
                    Text("Ställ telefonen i öronhöjd på din plats, t.ex. mot nackstödet. Ta fjärrkontrollen.", fontSize = 18.sp, lineHeight = 25.sp)
                    Muted("Appen säger vilket läge du ska trycka.")
                }
            }
        }
        Panel {
            EvenGauge(live.unevenDb?.let { Diagnosis.gauge(it) })
            Text(
                live.unevenDb?.let { Diagnosis.gaugeWord(it) } ?: "Lyssnar …",
                fontSize = 28.sp, fontWeight = FontWeight.Medium,
                color = if (live.unevenDb != null && live.unevenDb < 2.5) Good else Ink,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 90, 180, 270).forEach { ph ->
                val r = s.phaseResults[ph]
                val best = s.phaseBest == ph
                val now = s.phasePrompt == ph
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (best) GoodSoft else CardColor)
                        .border(if (best || now) 2.dp else 1.dp, if (best) Good else if (now) Accent else Line, RoundedCornerShape(16.dp))
                        .padding(vertical = 14.dp),
                ) {
                    Text("$ph", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Box(Modifier.width(44.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(CardAlt)) {
                        if (r != null) {
                            Box(
                                Modifier
                                    .width((44 * Diagnosis.gauge(r)).toInt().coerceAtLeast(4).dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (best) Good else SeriesColors[5]),
                            )
                        }
                    }
                    Text(
                        when {
                            best -> "bäst"
                            r != null -> Diagnosis.gaugeWord(r).lowercase(Locale.ROOT)
                            now -> "nu"
                            else -> "–"
                        },
                        fontSize = 12.sp,
                        color = if (best) Good else InkMuted,
                        fontWeight = if (best) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
        BigButton(s.phaseBest?.let { "Klart – subben står på $it" } ?: "Klart", enabled = s.phaseBest != null) { services.health.finishTune() }
        Row {
            TextButton(onClick = { services.health.startPhaseGuide() }) { Text("Mät om") }
            Spacer(Modifier.weight(1f))
            VoiceToggle(services)
        }
    }
}

@Composable
private fun LowpassTuner(services: AppServices, s: HealthController.State) {
    val (word, sub) = services.health.lowpassWord(s.live)
    val good = word == "Bättre" || word == "Bäst hittills"
    val bad = word == "Sämre"
    Column(
        Modifier
            .fillMaxSize()
            .background(if (good) GoodSoft else if (bad) WarnSoft else Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TunerHeader(services, s)
        Text(
            "Lämna telefonen på din plats. Gå till subben och vrid lågpassratten långsamt nedåt. Skärmen syns från rummet.",
            fontSize = 17.sp, lineHeight = 24.sp,
        )
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (good || bad) {
                Text("▲", fontSize = 90.sp, color = if (good) Good else Warn, modifier = if (bad) Modifier.rotate(180f) else Modifier)
            }
            Text(word, fontSize = 64.sp, lineHeight = 70.sp, fontWeight = FontWeight.Medium, color = if (good) Color(0xFF2F5A3C) else if (bad) Warn else Ink, textAlign = TextAlign.Center)
            Text(sub, fontSize = 22.sp, textAlign = TextAlign.Center)
        }
        Panel {
            Muted("Senaste halvminuten")
            Sparkline(s.live.history.map { -it }, higherIsBetter = true)
            Muted("Streckad linje = bäst hittills. Vrider du för långt sjunker kurvan – vrid då tillbaka.")
        }
        BigButton("Klart") { services.health.finishTune() }
        VoiceToggle(services)
    }
}

@Composable
private fun LevelTuner(services: AppServices, s: HealthController.State) {
    val bal = s.live.balanceDb
    val (word, sub) = bal?.let { services.health.levelWord(it) } ?: ("Lyssnar" to "")
    val ok = word == "Lagom"
    Page {
        TunerHeader(services, s)
        Title("Ställ in subbens nivå")
        Muted("Ställ telefonen i öronhöjd på din plats. Tryck VOLUME + eller − på fjärrkontrollen, ett steg i taget, och vänta på besked.")
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(word, fontSize = 52.sp, fontWeight = FontWeight.Medium, color = if (ok) Good else Ink, textAlign = TextAlign.Center)
            Text(sub, fontSize = 18.sp, textAlign = TextAlign.Center)
        }
        Panel {
            // Mätare: mitten = målet, ±6 dB till kanterna.
            val d = bal?.let { (it - services.health.target).coerceIn(-6.0, 6.0) }
            Box(Modifier.fillMaxWidth().height(36.dp)) {
                Box(Modifier.fillMaxWidth().height(12.dp).align(Alignment.Center).clip(RoundedCornerShape(6.dp)).background(CardAlt))
                Box(Modifier.width(60.dp).height(12.dp).align(Alignment.Center).clip(RoundedCornerShape(6.dp)).background(GoodSoft))
                if (d != null) {
                    Box(
                        Modifier
                            .fillMaxWidth(((d + 6) / 12).toFloat().coerceIn(0.02f, 1f))
                            .height(36.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(Modifier.width(6.dp).height(36.dp).clip(RoundedCornerShape(3.dp)).background(Ink))
                    }
                }
            }
            Row {
                Muted("för svag")
                Spacer(Modifier.weight(1f))
                Muted("lagom")
                Spacer(Modifier.weight(1f))
                Muted("för stark")
            }
        }
        BigButton("Klart") { services.health.finishTune() }
        VoiceToggle(services)
    }
}

@Composable
private fun VoiceToggle(services: AppServices) {
    val settings by services.settings.state.collectAsState()
    OutlinedButton(
        onClick = { services.setVoice(!settings.voice) },
        border = BorderStroke(1.dp, if (settings.voice) Good else Line),
    ) { Text(if (settings.voice) "Röstguide: på" else "Röstguide: av", color = Ink) }
}

// ---------- Klart ----------

@Composable
private fun DoneScreen(services: AppServices, s: HealthController.State) {
    val before = s.report
    val after = s.afterReport
    val improved = if (before != null && after != null) before.unevenDb - after.unevenDb else 0.0
    Page {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Kicker("Efterkontroll klar", Good)
            Title(
                when {
                    after != null && after.actionable.isEmpty() -> "Basen sitter ihop"
                    improved >= 0.5 -> "Jämnare än förut"
                    else -> "Ingen tydlig förbättring"
                },
                30,
            )
            Muted(
                when {
                    improved >= 0.5 -> String.format(Locale.ROOT, "Övergången mellan sub och Genelec är %.1f dB jämnare än före.", improved)
                    improved <= -0.5 -> "Det blev lite ojämnare. Prova att gå tillbaka till den tidigare inställningen."
                    else -> "Skillnaden mot före är liten."
                },
            )
        }
        s.warnings["AFTER"]?.let { Notice("Efterkontrollen: $it", Tone.WARN) }
        s.after?.let { a ->
            Panel {
                BassLandscape(a, before = s.together, height = 150.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Muted("- - Före")
                    Text("— Efter", color = Accent, fontSize = 15.sp)
                }
                var showCurves by remember { mutableStateOf(false) }
                TextButton(onClick = { showCurves = !showCurves }) { Text(if (showCurves) "Dölj kurvor ▴" else "Visa kurvor ▾") }
                if (showCurves) CurveExplorer(services, healthCurves(s, after = true), crossover = after?.crossoverHz)
            }
        }
        Panel {
            if (s.changes.isEmpty()) Muted("Inga inställningar ändrades.")
            s.changes.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = Good, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Text(c.substringBefore(":"), fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(c.substringAfter(":").trim(), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            after?.findings?.filter { it.action == null }?.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("i", color = InkMuted, fontWeight = FontWeight.Bold, modifier = Modifier.width(12.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(f.title, fontSize = 15.sp, color = InkMuted)
                }
            }
            after?.actionable?.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("!", color = Warn, fontWeight = FontWeight.Bold, modifier = Modifier.width(12.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Kvar: ${f.title}", fontSize = 15.sp)
                }
            }
        }
        Panel(container = AccentSoft) {
            Text("Lyssna med musik", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(
                "Välj något med tydlig basgång. Alla toner ska höras lika tydligt, och basen ska kännas som att den kommer från Genelecerna.",
                fontSize = 15.sp, lineHeight = 21.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { services.health.saveResult() }, modifier = Modifier.weight(1f).height(54.dp)) { Text("Spara", fontSize = 16.sp) }
            OutlinedButton(onClick = { services.health.startCheck() }, modifier = Modifier.weight(1f).height(54.dp)) { Text("Kolla igen", fontSize = 16.sp) }
        }
        TextButton(onClick = { services.health.home() }, modifier = Modifier.fillMaxWidth()) { Text("Till start") }
    }
}

/** Hälsokollens kurvor med fasta färger och förklaringar i vardagsord. */
private fun healthCurves(s: HealthController.State, after: Boolean): List<ExplorerCurve> = buildList {
    if (after) {
        s.after?.let { add(ExplorerCurve("Efter", "allt tillsammans efter ändringarna", it, Accent, thick = true)) }
        s.together?.let { add(ExplorerCurve("Före", "allt tillsammans före ändringarna", it, Color(0xFF8A9294), dashed = true)) }
    } else {
        s.together?.let { add(ExplorerCurve("Tillsammans", "det du hör till vardags, sub och Genelec", it, Accent, thick = true)) }
    }
    s.genelec?.let { add(ExplorerCurve("Genelec ensam", "det som hörs med MUTE på subben", it, SeriesColors[1], initiallyOn = !after)) }
    s.sub?.let { add(ExplorerCurve("Sub ensam", "det som hörs med Genelecerna avstängda", it, SeriesColors[2], initiallyOn = !after)) }
}
