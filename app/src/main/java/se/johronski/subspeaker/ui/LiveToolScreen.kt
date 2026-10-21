package se.johronski.subspeaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.AppServices

/** Verktyget Live-kurva: mätljudet spelar och kurvan visas hela tiden. */
@Composable
fun LiveToolScreen(services: AppServices, setup: Setup) {
    val s by services.health.state.collectAsState()
    var withSound by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { services.health.stopLiveTool() } }
    if (withSound) setup.problem?.let { Notice(it, Tone.WARN) }
    else if (!setup.hasMicPermission) Notice("Appen behöver tillgång till mikrofonen.", Tone.WARN)

    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = withSound, onClick = { withSound = true }, enabled = !s.liveTool, label = { Text("Med mätljud") })
            FilterChip(selected = !withSound, onClick = { withSound = false }, enabled = !s.liveTool, label = { Text("Utan ljud – bara lyssna") })
        }
        Muted(
            if (withSound) {
                "Appen spelar mätljudet och visar högtalarnas frekvensgång hela tiden. Välj Lugn och rör telefonen runt huvudet " +
                    "för ett medelvärde över lyssningsplatsen, eller Snabb med telefonen still när du ändrar något på subben."
            } else {
                "Appen spelar inget utan visar vad mikrofonen hör, t.ex. din egen musik eller bakgrundsbruset i rummet. " +
                    "Kurvan visar ljudets innehåll (dB relativt full skala), inte högtalarnas frekvensgång."
            },
        )
        if (!s.liveTool) {
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

    if (s.liveTool || s.live.curve != null) {
        Panel {
            LiveCurvePanel(services, s.live)
            val own = s.live.snapshots.count { it.label.startsWith("Jämförelse") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { services.health.addSnapshot() }, enabled = s.live.curve != null && own < 3) {
                    Text("Spara som jämförelse")
                }
                if (s.live.snapshots.isNotEmpty()) TextButton(onClick = { services.health.clearSnapshots() }) { Text("Rensa") }
            }
            Muted("Upp till tre jämförelser, t.ex. före och efter att du flyttat lyssningsplatsen. De finns kvar till nästa gång.")
            OutlinedButton(onClick = { services.health.saveLiveToHistory() }, enabled = s.live.curve != null) {
                Text("Spara i Sparade resultat")
            }
        }
    }
}
