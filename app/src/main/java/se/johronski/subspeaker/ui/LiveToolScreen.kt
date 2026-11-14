package se.johronski.subspeaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.AppServices
import se.johronski.subspeaker.HealthController
import se.johronski.subspeaker.audio.BassMetrics
import java.util.Locale

/**
 * Live-kurva, appens startsida: mätljudet spelar och kurvan visas hela tiden.
 * Under kurvan finns sparade platser/inställningar med namn och ett tal för ojämnheten,
 * så att man kan leta lyssningsposition och se vilken som är jämnast.
 */
@Composable
fun LiveToolScreen(services: AppServices, setup: Setup) {
    val s by services.health.state.collectAsState()
    var withSound by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { services.health.stopLiveTool() } }
    if (withSound) setup.problem?.let { Notice(it, Tone.WARN) }
    else if (!setup.hasMicPermission) Notice("Appen behöver tillgång till mikrofonen.", Tone.WARN)

    if (s.liveTool || s.live.curve != null) {
        Panel {
            LiveCurvePanel(services, s.live)
        }
        if (!s.liveRaw) ComparePanel(services, s.live)
    }

    Panel {
        if (!s.liveTool) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = withSound, onClick = { withSound = true }, label = { Text("Med mätljud") })
                FilterChip(selected = !withSound, onClick = { withSound = false }, label = { Text("Utan ljud – bara lyssna") })
            }
            Muted(
                if (withSound) {
                    "Appen spelar mätljudet och visar frekvensgången 20–600 Hz hela tiden. Rör telefonen långsamt runt huvudet " +
                        "med Lugn för ett medelvärde över platsen, eller håll den still med Snabb när du ändrar något på subben."
                } else {
                    "Appen spelar inget utan visar vad mikrofonen hör, t.ex. din egen musik. " +
                        "Kurvan visar ljudets innehåll (dB relativt full skala), inte högtalarnas frekvensgång."
                },
            )
            Button(
                onClick = { services.health.startLiveTool(withSound) },
                enabled = if (withSound) setup.canMeasure else setup.hasMicPermission,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Starta", fontSize = 17.sp) }
        } else {
            Button(
                onClick = { services.health.stopLiveTool() },
                colors = ButtonDefaults.buttonColors(containerColor = Bad),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Stoppa", fontSize = 17.sp) }
        }
        s.error?.let { Notice(it, Tone.BAD) }
    }
}

/** Mått mot målkurvan, samma som hälsokollen använder. null om kurvan saknar värden i 25–200 Hz. */
private fun metrics(db: DoubleArray, target: Double): BassMetrics.Result? = BassMetrics.analyze(db, target)

private fun fmtDb(v: Double) = String.format(Locale.forLanguageTag("sv"), "%.1f dB", v)

/** Platser och inställningar att jämföra: namn, ojämnhet och vilken som är jämnast. */
@Composable
private fun ComparePanel(services: AppServices, live: HealthController.Live) {
    var name by remember { mutableStateOf("") }
    val guide by services.guide.state.collectAsState()
    val target = guide.targetBalance
    val own = live.snapshots.filter { it.label.startsWith("Jämförelse") }
    val full = own.size >= HealthController.MAX_COMPARE
    val now = live.curve?.let { metrics(it, target) }

    // Rader: Nu + sparade, med ojämnhet. Den jämnaste av de sparade markeras.
    val rows = own.map { Triple(it, metrics(it.db, target), snapshotColor(it.label)) }
    val best = rows.filter { it.second != null }.minByOrNull { it.second!!.sdDb }?.first?.label
    val msv = BassMetrics.msv(own.map { it.db })

    Panel(title = "Jämför platser") {
        Muted(
            "Ojämnhet = spridning mot målkurvan 25–200 Hz, samma mått som hälsokollen. Lägre är jämnare: under 2,5 dB jämnt, " +
                "under 3,8 dB OK. Skillnader på några tiondelar kan vara slump. Under namnet: lutning mot målet, smala avvikelser (NBD) " +
                "och största hål. Förklaring under Om SubSpeaker i menyn.",
        )
        CompareRow(
            color = Accent,
            name = "Nu",
            value = now?.sdDb,
            note = if (!live.stable) "kurvan ändrar sig" else null,
            detail = now?.let { compactMetrics(it) },
            bold = true,
        )
        rows.forEach { (snap, m, color) ->
            CompareRow(
                color = color,
                name = snap.name,
                value = m?.sdDb,
                note = if (snap.label == best && rows.size > 1) "jämnast" else null,
                detail = m?.let { compactMetrics(it) },
                onRemove = { services.health.removeSnapshot(snap.label) },
            )
        }
        msv?.let {
            Muted("Skillnad mellan de sparade platserna (MSV): ${msvText(it)}. Lågt värde betyder att en gemensam EQ kan hjälpa alla platser.")
        }
        if (!full) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                label = { Text("Namn, t.ex. Soffan eller 50 cm fram") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (live.curve != null) { services.health.addSnapshot(name); name = "" }
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { services.health.addSnapshot(name); name = "" },
                enabled = live.curve != null && !full,
            ) { Text("Spara som jämförelse") }
            if (own.isNotEmpty()) TextButton(onClick = { services.health.clearSnapshots() }) { Text("Rensa alla") }
        }
        if (full) Muted("Högst ${HealthController.MAX_COMPARE} jämförelser. Ta bort en för att spara en ny.")
        Muted("Jämförelserna finns kvar till nästa gång. Vänta tills punkten vid kurvan är grön innan du sparar.")
        OutlinedButton(onClick = { services.health.saveLiveToHistory() }, enabled = live.curve != null) {
            Text("Spara allt i Sparade resultat")
        }
    }
}

@Composable
private fun CompareRow(
    color: Color,
    name: String,
    value: Double?,
    note: String?,
    detail: String? = null,
    bold: Boolean = false,
    onRemove: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal)
            note?.let { Text(it, fontSize = 13.sp, color = if (it == "jämnast") Good else InkMuted) }
            detail?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = InkMuted) }
        }
        Text(value?.let { fmtDb(it) } ?: "–", fontSize = 18.sp, fontWeight = FontWeight.Medium)
        if (onRemove != null) {
            TextButton(onClick = onRemove) { Text("Ta bort") }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}
