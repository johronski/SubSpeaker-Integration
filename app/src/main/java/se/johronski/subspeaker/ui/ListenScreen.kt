@file:OptIn(ExperimentalLayoutApi::class)

package se.johronski.subspeaker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.AppServices
import se.johronski.subspeaker.SavedReading
import se.johronski.subspeaker.audio.SignalSpec
import se.johronski.subspeaker.audio.ToneGrid
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val presetSignals: List<SignalSpec> = listOf(
    SignalSpec.Tone(90.0, warble = false),
    SignalSpec.Tone(90.0, warble = true),
    SignalSpec.Tone(40.0, warble = false),
    SignalSpec.Tone(63.0, warble = false),
    SignalSpec.Tone(125.0, warble = false),
    SignalSpec.Tone(200.0, warble = false),
    SignalSpec.Sweep(200.0, 20.0),
    SignalSpec.Steps(warble = true),
)

@Composable
fun ListenScreen(services: AppServices, setup: Setup) {
    val playback by services.playback.state.collectAsState()
    val mic by services.mic.state.collectAsState()
    val readings by services.readings.collectAsState()
    val cast by services.cast.state.collectAsState()
    if (setup.playName.isBlank()) Notice("Välj din Chromecast – tryck på raden ovan. Annars spelas inget.", Tone.WARN)

    Panel("Signaler") {
        Muted("Spelas direkt när du trycker. Stopp finns längst ner.")
        SignalChips(services, presetSignals, playback.spec)
        var customFreq by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = customFreq,
                onValueChange = { customFreq = it.replace(',', '.') },
                label = { Text("Egen frekvens, Hz") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(180.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                customFreq.toDoubleOrNull()?.takeIf { it in ToneGrid.MIN..ToneGrid.MAX }?.let { services.playback.playTone(it) }
            }) { Text("Spela") }
        }
    }

    Panel("Dippsökning") {
        Muted(
            "Stega tonen och lyssna – eller titta på mikrofonnivån nedan – var den blir svagast. " +
                "Börja med 1/12 oktav för att hitta dippen och byt till 1/24 för att ringa in den. " +
                "Ren ton visar smala dippar bäst; svajtonen sveper ±5 % och fyller i dem.",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToneGrid.DIVISIONS.forEach { d ->
                FilterChip(selected = playback.dipDiv == d, onClick = { services.playback.setDivision(d) }, label = { Text(ToneGrid.label(d)) })
            }
            FilterChip(selected = playback.dipWarble, onClick = { services.playback.setWarble(!playback.dipWarble) }, label = { Text("Svajton") })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { services.playback.stepTone(-1) }, modifier = Modifier.height(60.dp).width(80.dp)) { Text("−", fontSize = 28.sp) }
            Text(
                "${SignalSpec.fmt(playback.dipFreq)} Hz",
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).clickable { services.playback.playTone(playback.dipFreq) },
            )
            Button(onClick = { services.playback.stepTone(1) }, modifier = Modifier.height(60.dp).width(80.dp)) { Text("+", fontSize = 28.sp) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToneGrid.around(playback.dipFreq, playback.dipDiv, 6).forEach { f ->
                FilterChip(selected = f == playback.dipFreq, onClick = { services.playback.playTone(f) }, label = { Text(SignalSpec.fmt(f)) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
            Muted("Hoppa till:")
            listOf(35.0, 57.0, 70.0, 90.0, 120.0, 160.0, 215.0).forEach { f ->
                OutlinedButton(onClick = { services.playback.playTone(ToneGrid.snap(f, playback.dipDiv)) }) { Text(SignalSpec.fmt(f)) }
            }
        }

        val marked = playback.markers.any { kotlin.math.abs(it - playback.dipFreq) < 0.05 }
        OutlinedButton(onClick = { services.playback.toggleMarker(playback.dipFreq) }) {
            Text(if (marked) "Ta bort markering vid ${SignalSpec.fmt(playback.dipFreq)} Hz" else "Markera dipp vid ${SignalSpec.fmt(playback.dipFreq)} Hz")
        }
        if (playback.markers.isNotEmpty()) {
            Muted("Markerade dippar visas som streckade linjer i alla diagram. Tryck för att lyssna.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                playback.markers.forEach { m ->
                    FilterChip(selected = false, onClick = { services.playback.playTone(m) }, label = { Text("${SignalSpec.fmt(m)} Hz") })
                }
                OutlinedButton(onClick = { services.playback.clearMarkers() }) { Text("Rensa") }
            }
        }
    }

    Panel("Mikrofonnivå") {
        Muted("Visar nivån vid tonen som spelas. Jämför dB mellan tonerna för att se hur djup en dipp är.")
        if (!setup.hasMicPermission) {
            Button(onClick = setup.requestPermission) { Text("Ge appen tillgång till mikrofonen") }
            return@Panel
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                playback.spec?.measureFrequency?.let { services.mic.setFrequency(it) }
                services.mic.start(services.micSource(), services.micDevice())
            }) { Text(if (mic.running) "Starta om" else "Starta mikrofonen") }
            OutlinedButton(onClick = { services.mic.stop() }, enabled = mic.running) { Text("Stoppa") }
        }
        mic.error?.let { Notice(it, Tone.BAD) }
        val r = mic.reading
        if (r != null && mic.running) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(Locale.ROOT, "%.1f dB", r.bandDb), fontSize = 44.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Muted("vid ${SignalSpec.fmt(r.frequency)} Hz")
            }
            val stable = r.spreadDb < 1.0
            Notice(
                String.format(Locale.ROOT, "Variation senaste 5 s: %.1f dB – %s", r.spreadDb, if (stable) "stabil" else "vänta tills den lugnar sig"),
                if (stable) Tone.GOOD else Tone.WARN,
            )
            var label by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Etikett, t.ex. \"mute\"") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    services.addReading(
                        SavedReading(
                            time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()),
                            label = label.ifBlank { "${SignalSpec.fmt(r.frequency)} Hz" },
                            signal = playback.spec?.label ?: "–",
                            frequency = r.frequency,
                            bandDb = r.bandDb,
                            broadDb = r.broadDb,
                            spreadDb = r.spreadDb,
                            source = mic.source?.label ?: "",
                            device = mic.routedDevice ?: "",
                            castVolume = cast.volume,
                        ),
                    )
                    label = ""
                }) { Text("Spara") }
            }
        }
        if (readings.isNotEmpty()) {
            Label("Sparade avläsningar")
            readings.forEach { s ->
                Text(
                    String.format(Locale.ROOT, "%s  %s: %.1f dB vid %s Hz", s.time, s.label, s.bandDb, SignalSpec.fmt(s.frequency)),
                    fontSize = 15.sp,
                )
            }
            OutlinedButton(onClick = { services.clearReadings() }) { Text("Rensa") }
        }
    }
}
