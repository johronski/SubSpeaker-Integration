@file:OptIn(ExperimentalLayoutApi::class)

package se.johronski.subspeaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.AppServices
import java.util.Locale

@Composable
fun MeasureScreen(services: AppServices, setup: Setup) {
    val sweep by services.sweep.state.collectAsState()
    var label by rememberSaveable { mutableStateOf("normal") }
    setup.problem?.let { Notice(it, Tone.WARN) }

    Panel("Fri svepmätning") {
        Muted(
            "Mäter hela kurvan 20–300 Hz med ett svep på 8 s. Mätningar med samma namn slås ihop till ett medelvärde – " +
                "mät gärna samma namn på 3–5 platser inom en halvmeter runt lyssningsplatsen.",
        )
        Label("Namn på mätningen")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("normal", "genelec ensam", "fas 0", "fas 90", "fas 180", "fas 270").forEach { l ->
                FilterChip(selected = label == l, onClick = { label = l }, label = { Text(l) })
            }
        }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Eget namn") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SweepSettings(sweep.delaySeconds, sweep.repeats, !sweep.busy, { services.sweep.setDelay(it) }, { services.sweep.setRepeats(it) })
        Button(
            onClick = { services.sweep.measure(label) },
            enabled = setup.canMeasure && !sweep.busy,
            modifier = Modifier.fillMaxWidth().height(60.dp),
        ) { Text(if (sweep.busy) "Mäter …" else "Mät: $label", fontSize = 17.sp) }
        if (!sweep.busy && sweep.status.isNotEmpty()) Muted(sweep.status)
        sweep.error?.let { Notice(it, Tone.WARN) }

        val groups = sweep.groups
        if (groups.isNotEmpty()) {
            val best = sweep.best
            ChartWithLegend(
                services,
                groups.map { ChartCurve(it.label, it.db) },
                groups.map { g -> String.format(Locale.ROOT, "%s · ojämnhet ±%.1f dB · %d mätn.", g.label, g.flatnessDb, g.count) },
                best?.let { b -> groups.indexOfFirst { it.label == b.label } },
            )
            if (best != null) {
                val second = groups.filter { it !== best }.minByOrNull { it.flatnessDb }
                Notice(
                    "Jämnast: ${best.label}" + (second?.let {
                        String.format(Locale.ROOT, " – %.1f dB jämnare än %s", it.flatnessDb - best.flatnessDb, it.label)
                    } ?: ""),
                    Tone.GOOD,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { services.destructive("Senaste mätningen togs bort") { services.sweep.removeLast() } },
                    enabled = !sweep.busy,
                ) { Text("Ta bort senaste") }
                OutlinedButton(
                    onClick = { services.destructive("Alla fria mätningar togs bort") { services.sweep.clear() } },
                    enabled = !sweep.busy,
                ) { Text("Rensa alla") }
            }
        }
    }

    Panel("Dippen vid 120 Hz: sub eller rum?") {
        NumberedStep("1", "Mät med namnet \"normal\".")
        NumberedStep("2", "Tryck MUTE på Velodynens fjärrkontroll och kontrollera att Genelecen fortfarande spelar.")
        NumberedStep("3", "Mät på exakt samma plats med namnet \"genelec ensam\". Slå av MUTE efteråt.")
        Muted(
            "Finns dippen kvar med Genelec ensam beror den på rummet eller placeringen. " +
                "Försvinner den tar sub och Genelec ut varandra – prova då ett lägre lågpass på subben.",
        )
    }
}
