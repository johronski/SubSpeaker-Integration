@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package se.johronski.subspeaker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.johronski.subspeaker.AppServices
import se.johronski.subspeaker.audio.LocalPlayer
import se.johronski.subspeaker.audio.MicMeter
import se.johronski.subspeaker.audio.SignalServer
import se.johronski.subspeaker.audio.SignalSpec
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedReading(
    val time: String,
    val label: String,
    val signal: String,
    val frequency: Double,
    val bandDb: Double,
    val broadDb: Double,
    val spreadDb: Double,
    val source: String,
    val device: String,
    val castVolume: Double?,
)

enum class Output(val label: String) { CAST("Chromecast"), PHONE("Telefonen") }

private val presetSignals: List<SignalSpec> = listOf(
    SignalSpec.Tone(90.0, warble = false),
    SignalSpec.Tone(90.0, warble = true),
    SignalSpec.Tone(40.0, warble = false),
    SignalSpec.Tone(63.0, warble = false),
    SignalSpec.Tone(125.0, warble = false),
    SignalSpec.Tone(200.0, warble = false),
    SignalSpec.Sweep(200.0, 20.0),
    SignalSpec.Steps(warble = true),
    SignalSpec.Tone(1000.0, warble = false),
)

@Composable
fun DiagnosticsScreen(services: AppServices) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cast by services.cast.state.collectAsState()
    val mic by services.mic.state.collectAsState()
    val serverError by services.serverError.collectAsState()
    val local by services.local.state.collectAsState()

    var output by remember { mutableStateOf(Output.CAST) }
    var outputs by remember { mutableStateOf(services.local.outputDevices()) }
    var selectedOutputId by remember { mutableStateOf<Int?>(null) }
    var localGain by remember { mutableFloatStateOf(services.local.gain) }
    var method by remember { mutableStateOf(LocalPlayer.Method.DIRECT) }
    val sweep by services.sweep.state.collectAsState()
    var sweepLabel by remember { mutableStateOf("fas 0") }
    val guide by services.guide.state.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var playingSpec by remember { mutableStateOf<SignalSpec?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val playingNow = if (output == Output.PHONE) local.playing else cast.playerState == "Spelar"
    LaunchedEffect(playingNow) {
        while (playingNow) {
            now = SystemClock.elapsedRealtime()
            delay(100)
        }
    }

    var selected by remember { mutableStateOf<SignalSpec>(presetSignals.first()) }
    var customFreq by remember { mutableStateOf("") }
    var allowHighVolume by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var serverTest by remember { mutableStateOf<String?>(null) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasMicPermission = it
    }
    var devices by remember { mutableStateOf(services.mic.inputDevices()) }
    var selectedDeviceId by remember { mutableStateOf<Int?>(null) }
    var source by remember {
        mutableStateOf(
            if (services.mic.unprocessedSupported) MicMeter.Source.UNPROCESSED else MicMeter.Source.VOICE_RECOGNITION,
        )
    }
    var label by remember { mutableStateOf("") }
    val saved = remember { mutableStateListOf<SavedReading>() }

    LaunchedEffect(cast.volume) {
        if (!dragging) cast.volume?.let { sliderValue = it.toFloat() }
    }
    LaunchedEffect(selected) {
        selected.measureFrequency?.let { services.mic.setFrequency(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SubSpeaker", fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Text(
            "Guiden tar dig genom inställningen steg för steg. Under Verktyg väljer du uppspelning och mikrofon.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Guide", fontSize = 16.sp) })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Verktyg", fontSize = 16.sp) })
        }

        val micName = (devices.firstOrNull { it.id == selectedDeviceId }?.let { MicMeter.describe(it) } ?: "systemets mikrofon") +
            " (${source.label})"
        val playText = if (output == Output.CAST) {
            "Chromecast" + (if (cast.connected) ": ${cast.deviceName}" else " (inte ansluten)")
        } else {
            "Telefonen (${method.label})"
        }
        val setupProblem = when {
            !hasMicPermission -> "Ge appen tillgång till mikrofonen under Verktyg."
            output == Output.CAST && !cast.connected -> "Anslut till din Chromecast under Verktyg."
            else -> null
        }
        if (tab == 0) {
            GuideContent(
                guide = guide,
                sweep = sweep,
                setupText = "Uppspelning: $playText · Mikrofon: $micName",
                setupProblem = setupProblem,
                micName = micName,
                canMeasure = setupProblem == null,
                onStep = { services.guide.go(it) },
                onPositions = { services.guide.setPositions(it) },
                onToggleCheck = { services.guide.toggleCheck(it) },
                onGenelecBass = { services.guide.setGenelecBass(it) },
                measure = { label, stage, group, pos ->
                    services.sweep.measure(
                        label = label,
                        useCast = output == Output.CAST,
                        source = source,
                        micDevice = devices.firstOrNull { it.id == selectedDeviceId },
                        outDevice = outputs.firstOrNull { it.id == selectedOutputId },
                        method = method,
                        stage = stage,
                        group = group,
                        position = pos,
                    )
                },
                removeLast = { services.sweep.removeLast(it) },
                clearStage = { services.sweep.clear(it) },
                resetAll = {
                    listOf("mic", "phase", "level", "genelec").forEach { services.sweep.clear(it) }
                    services.guide.reset()
                },
            )
        }

        if (tab == 1) {
        // ---------- 1. Chromecast ----------
        Section("1. Uppspelning") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Output.entries.forEach { o ->
                    FilterChip(
                        selected = output == o,
                        onClick = {
                            if (o != output) {
                                services.local.stop()
                                if (cast.connected) services.cast.stop()
                            }
                            output = o
                        },
                        label = { Text(o.label) },
                    )
                }
            }
            if (output == Output.PHONE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Telefonen", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { outputs = services.local.outputDevices() }) { Text("Uppdatera utgångar") }
                }
                Text("Metod", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalPlayer.Method.entries.forEach { m ->
                        FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m.label) })
                    }
                }
                Text("Utgång", fontWeight = FontWeight.Medium)
                ChoiceRow("Systemets val", selectedOutputId == null) { selectedOutputId = null }
                outputs.forEach { d ->
                    ChoiceRow(LocalPlayer.describeOutput(d), selectedOutputId == d.id) { selectedOutputId = d.id }
                }
                Muted(
                    "Telefonens högtalare återger inte djupbas – använd en kabel från hörlursutgången " +
                        "(t.ex. USB-C-adaptern) till subbens linjeingång. Bluetooth ger fördröjning och komprimering.",
                )
            }
            if (output == Output.CAST) {
                Text(
                    when {
                        cast.connected -> "Ansluten: ${cast.deviceName}"
                        cast.connecting -> "Ansluter …"
                        cast.devices.isEmpty() -> "Söker efter Chromecast på nätverket …"
                        else -> "Välj din Chromecast:"
                    },
                    fontWeight = FontWeight.Medium,
                )
                cast.devices.forEach { d ->
                    val isCurrent = cast.connected && cast.deviceName == d.name
                    ChoiceRow(
                        d.name + (if (d.status.isNotEmpty()) "  ·  ${d.status}" else "") + (if (d.group) "  (grupp)" else ""),
                        isCurrent,
                    ) { if (!isCurrent) services.cast.connect(d) }
                }
                if (cast.connected) {
                    OutlinedButton(onClick = { services.cast.disconnect() }) { Text("Koppla från") }
                }
            }
            if (output == Output.CAST) {
                Muted("Telefonens adress: ${services.localIp() ?: "inget wifi hittat"} · port 8089")
                serverError?.let { Text(it, color = Bad) }
                OutlinedButton(onClick = {
                    serverTest = "Testar …"
                    scope.launch {
                        serverTest = withContext(Dispatchers.IO) { testServer(services) }
                    }
                }) { Text("Testa telefonens ljudserver") }
                serverTest?.let { Muted(it) }
            }

            Spacer(Modifier.height(4.dp))
            Text("Signal (−12 dBFS)", fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presetSignals.forEach { s ->
                    FilterChip(
                        selected = selected == s,
                        onClick = { selected = s },
                        label = { Text(s.label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customFreq,
                    onValueChange = { customFreq = it.replace(',', '.') },
                    label = { Text("Egen frekvens, Hz") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(170.dp),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    customFreq.toDoubleOrNull()?.takeIf { it in 10.0..1000.0 }?.let {
                        selected = SignalSpec.Tone(it, warble = false)
                    }
                }) { Text("Välj") }
            }
            Muted("Vald: ${selected.label}")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (output == Output.CAST) {
                            services.local.stop()
                            val url = services.signalUrl(selected.path)
                            if (url != null) services.cast.play(url, selected.label, selected.durationMs)
                            playingSpec = selected
                        } else {
                            if (cast.connected) services.cast.stop()
                            playingSpec = selected
                            services.local.play(
                                selected,
                                outputs.firstOrNull { it.id == selectedOutputId },
                                method,
                                "http://127.0.0.1:${SignalServer.PORT}/${selected.path}",
                            )
                        }
                    },
                    enabled = output == Output.PHONE || cast.connected,
                    modifier = Modifier.height(56.dp),
                ) { Text("Spela", fontSize = 18.sp) }
                Button(
                    onClick = {
                        services.local.stop()
                        if (cast.connected) services.cast.stop()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Bad),
                    modifier = Modifier.height(56.dp),
                ) { Text("Stopp", fontSize = 18.sp) }
            }
            if (output == Output.PHONE) {
                Text(
                    "Läge: ${if (local.playing) "Spelar" else "Stoppad"} · " +
                        "${fmtTime(local.positionMs)} / ${fmtTime(local.durationMs)}",
                )
                local.routedDevice?.let { Muted("Spelas via: $it") }
                if (local.debug.isNotEmpty()) Muted(local.debug)
                local.error?.let { Text(it, color = Bad) }
            } else {
                if (!cast.connected) Text("Tryck på din Chromecast i listan ovan först.", color = Warn)
                Text(
                    "Läge: ${cast.playerState} · ${fmtTime(cast.positionMs)} / ${fmtTime(cast.durationMs)}" +
                        (cast.latencyMs?.let { " · startade efter ${it} ms" } ?: ""),
                )
                cast.lastError?.let { Text(it, color = Bad) }
            }

            val spec = playingSpec
            if (playingNow && spec != null) {
                val basePos = if (output == Output.PHONE) local.positionMs else cast.positionMs
                val baseAt = if (output == Output.PHONE) local.positionAt else cast.positionAt
                val est = basePos + (now - baseAt).coerceIn(0L, 2000L)
                val f = spec.frequencyAt(est)
                Text(
                    if (f != null) String.format(Locale.ROOT, "%.0f Hz", f) else "paus",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Medium,
                    color = Accent,
                )
                if (output == Output.CAST) Muted("Frekvensen kan ligga upp till en halv sekund fel via Chromecast.")
            }

            Spacer(Modifier.height(4.dp))
            val cap = if (allowHighVolume) 1f else 0.6f
            if (output == Output.PHONE) {
                Text("Appens nivå: ${(localGain * 100).toInt()} %", fontWeight = FontWeight.Medium)
                Slider(
                    value = localGain.coerceIn(0f, cap),
                    onValueChange = { localGain = it; services.local.gain = it },
                    valueRange = 0f..cap,
                )
                Muted("Telefonens egen mediavolym påverkar också nivån.")
            }
            if (output == Output.CAST) Text(
                "Chromecast-volym (huvudvolym): " +
                    (cast.volume?.let { "${(it * 100).toInt()} %" } ?: "–"),
                fontWeight = FontWeight.Medium,
            )
            if (output == Output.CAST) Slider(
                value = sliderValue.coerceIn(0f, cap),
                onValueChange = { dragging = true; sliderValue = it },
                onValueChangeFinished = {
                    dragging = false
                    services.cast.setVolume(sliderValue.toDouble())
                },
                valueRange = 0f..cap,
                enabled = cast.connected,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowHighVolume, onCheckedChange = { allowHighVolume = it })
                Text("Tillåt nivå över 60 %")
            }
            Muted("Börja lågt. Djupbas på hög nivå kan skada högtalarna utan att låta särskilt starkt.")
        }

        // ---------- 2. Mikrofon ----------
        Section("2. Mikrofon") {
            if (!hasMicPermission) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Ge appen tillgång till mikrofonen")
                }
                return@Section
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ingång", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { devices = services.mic.inputDevices() }) { Text("Uppdatera") }
            }
            Muted("Koppla in mikrofonen och tryck Uppdatera.")
            ChoiceRow("Systemets val", selectedDeviceId == null) { selectedDeviceId = null }
            devices.forEach { d ->
                ChoiceRow(MicMeter.describe(d), selectedDeviceId == d.id) { selectedDeviceId = d.id }
            }

            Spacer(Modifier.height(4.dp))
            Text("Ljudkälla", fontWeight = FontWeight.Medium)
            Muted(
                if (services.mic.unprocessedSupported) {
                    "Telefonen stöder obearbetat mikrofonljud."
                } else {
                    "Telefonen anger inget stöd för obearbetat ljud – prova Röstigenkänning."
                },
            )
            MicMeter.Source.entries.forEach { s ->
                ChoiceRow(s.label, source == s) { source = s }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val dev: AudioDeviceInfo? = devices.firstOrNull { it.id == selectedDeviceId }
                    selected.measureFrequency?.let { services.mic.setFrequency(it) }
                    services.mic.start(source, dev)
                }) { Text(if (mic.running) "Starta om" else "Starta mätning") }
                OutlinedButton(onClick = { services.mic.stop() }, enabled = mic.running) { Text("Stoppa") }
            }
            mic.error?.let { Text(it, color = Bad) }
            if (mic.running) {
                Muted("Källa: ${mic.source?.label} · ${mic.effects}")
                Muted("Används: ${mic.routedDevice ?: "–"}")
            }

            val r = mic.reading
            if (r != null && mic.running) {
                Text("Nivå runt ${SignalSpec.fmt(r.frequency)} Hz", fontWeight = FontWeight.Medium)
                Text(
                    String.format(Locale.ROOT, "%.1f dB", r.bandDb),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Medium,
                )
                val stable = r.spreadDb < 1.0
                Text(
                    String.format(Locale.ROOT, "Variation senaste 5 s: %.1f dB", r.spreadDb) +
                        if (stable) " – stabil" else " – instabil",
                    color = if (stable) Good else Warn,
                )
                Muted(String.format(Locale.ROOT, "Bredband: %.1f dBFS", r.broadDb))
                Muted("Nivåerna är relativa (dBFS) och jämförbara mellan mätningar med samma mikrofon.")

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Etikett, t.ex. \"fas 90\" eller \"mute\"") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        saved += SavedReading(
                            time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()),
                            label = label.ifBlank { "mätning ${saved.size + 1}" },
                            signal = selected.label,
                            frequency = r.frequency,
                            bandDb = r.bandDb,
                            broadDb = r.broadDb,
                            spreadDb = r.spreadDb,
                            source = mic.source?.label ?: "",
                            device = mic.routedDevice ?: "",
                            castVolume = cast.volume,
                        )
                        label = ""
                    }) { Text("Spara") }
                }
            }
            if (saved.isNotEmpty()) {
                Text("Sparade mätningar", fontWeight = FontWeight.Medium)
                saved.forEach { s ->
                    Text(
                        String.format(
                            Locale.ROOT,
                            "%s  %s: %.1f dB @ %s Hz (±%.1f)",
                            s.time, s.label, s.bandDb, SignalSpec.fmt(s.frequency), s.spreadDb,
                        ),
                        fontSize = 14.sp,
                    )
                }
                OutlinedButton(onClick = { saved.clear() }) { Text("Rensa") }
            }
        }

        // ---------- 3. Svepmätning ----------
        Section("3. Svepmätning") {
            Muted(
                "Mäter hela kurvan 20–300 Hz med ett svep på 8 s via vald uppspelning (avsnitt 1) " +
                    "och vald mikrofon och ljudkälla (avsnitt 2). Mätningar med samma etikett slås ihop till ett medelvärde.",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("fas 0", "fas 90", "fas 180", "fas 270").forEach { l ->
                    FilterChip(selected = sweepLabel == l, onClick = { sweepLabel = l }, label = { Text(l) })
                }
            }
            OutlinedTextField(
                value = sweepLabel,
                onValueChange = { sweepLabel = it },
                label = { Text("Etikett") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    services.sweep.measure(
                        label = sweepLabel,
                        useCast = output == Output.CAST,
                        source = source,
                        micDevice = devices.firstOrNull { it.id == selectedDeviceId },
                        outDevice = outputs.firstOrNull { it.id == selectedOutputId },
                        method = method,
                    )
                },
                enabled = hasMicPermission && !sweep.busy && (output == Output.PHONE || cast.connected),
                modifier = Modifier.height(56.dp),
            ) { Text(if (sweep.busy) "Mäter …" else "Mät", fontSize = 18.sp) }
            if (!hasMicPermission) Text("Ge appen tillgång till mikrofonen i avsnitt 2 först.", color = Warn)
            if (output == Output.CAST && !cast.connected) Text("Anslut till Chromecast i avsnitt 1 först.", color = Warn)
            if (sweep.status.isNotEmpty()) Text(sweep.status)
            sweep.error?.let { Text(it, color = Warn) }

            val groups = sweep.groups
            if (groups.isNotEmpty()) {
                SweepChart(groups.map { it.db })
                groups.forEachIndexed { i, g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(
                            Modifier
                                .size(12.dp)
                                .background(SeriesColors[i % SeriesColors.size]),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            String.format(
                                Locale.ROOT,
                                "%s (n=%d) · jämnhet ±%.1f dB · nivå %.1f dB",
                                g.label, g.count, g.flatnessDb, g.meanDb,
                            ),
                            fontSize = 14.sp,
                        )
                    }
                }
                sweep.best?.let {
                    Text(
                        "Jämnast mellan 40 och 150 Hz: ${it.label}",
                        color = Good,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { services.sweep.removeLast() }, enabled = !sweep.busy) { Text("Ta bort senaste") }
                    OutlinedButton(onClick = { services.sweep.clear() }, enabled = !sweep.busy) { Text("Rensa") }
                }
            }
            Muted("Tips: mät varje etikett på 3–5 platser inom en halvmeter runt lyssningsplatsen för ett pålitligare medelvärde.")
        }

        // ---------- 4. Testplan ----------
        Section("4. Testplan") {
            Step("A", "Välj Chromecast, tryck på din Chromecast Audio i listan och spela 90 Hz ren ton på låg volym. Hörs den i subben och Genelecen?")
            Step("B", "Starta mätning med inbyggd mikrofon. Är nivån stabil (grön)? Spara som \"inbyggd\".")
            Step("C", "Koppla in Velodyne-mikrofonen via splittern, tryck Uppdatera, välj den och mät. Spara. Gör samma med Yamaha-mikrofonen.")
            Step("D", "Prova de tre ljudkällorna med samma mikrofon. Notera vilken som ger stabilast nivå.")
            Step("E", "Mute-test: spara en mätning, tryck MUTE på Velodynens fjärrkontroll, vänta 3 s och spara igen. Lyssna samtidigt: tystnar även Genelecen?")
            Step("F", "Volymtest: spara, tryck VOLUME + två gånger på fjärrkontrollen, spara igen. Tryck sedan VOLUME − två gånger.")
            Step("G", "Fas med svep (avsnitt 3): tryck 0 på fjärrkontrollen, välj etiketten \"fas 0\" och tryck Mät. Gör samma för 90, 180 och 270. Mät gärna varje läge på 3 platser.")
            Step("H", "Tryck Dela rapport nedan och skicka den till mig.")
        }

        // ---------- 5. Rapport ----------
        Section("5. Rapport") {
            Button(onClick = {
                val report = buildReport(context, services, cast, mic, saved, serverTest) +
                    "\nTELEFONUPPSPELNING\nVald källa: ${output.label}\nUtgång: ${local.routedDevice ?: "–"}\n" +
                    "Metod: ${local.method?.label ?: method.label}\nAppens nivå: ${(localGain * 100).toInt()} %\n" +
                    "Status: ${local.debug}\nFel: ${local.error ?: "–"}\n" +
                    sweepReport(sweep) + guideReport(sweep)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "SubSpeaker diagnosrapport")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(send, "Dela rapport"))
            }) { Text("Dela rapport") }
            Text("Logg", fontWeight = FontWeight.Medium)
            cast.log.takeLast(12).forEach { Muted(it) }
        }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
internal fun Muted(text: String) {
    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
}

@Composable
private fun Step(id: String, text: String) {
    Row {
        Text(id, fontWeight = FontWeight.Medium, color = Accent, modifier = Modifier.width(24.dp))
        Text(text)
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text)
    }
}

private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

private fun testServer(services: AppServices): String {
    val url = services.signalUrl("tone/sine/90/30/-12.wav") ?: return "Inget wifi – telefonen har ingen lokal adress"
    return try {
        val start = System.currentTimeMillis()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=0-43")
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        val code = conn.responseCode
        val head = conn.inputStream.use { it.readBytes() }
        val ms = System.currentTimeMillis() - start
        val riff = head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF"
        conn.disconnect()
        if (code == 206 && riff) "OK: servern svarar (${ms} ms, $url)" else "Oväntat svar: HTTP $code, ${head.size} byte"
    } catch (e: Exception) {
        "Fel: ${e.javaClass.simpleName}: ${e.message}"
    }
}

private fun buildReport(
    context: Context,
    services: AppServices,
    cast: se.johronski.subspeaker.cast.CastController.State,
    mic: MicMeter.State,
    saved: List<SavedReading>,
    serverTest: String?,
): String {
    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: Exception) {
        "?"
    }
    return buildString {
        appendLine("SubSpeaker diagnosrapport, version $version")
        appendLine("Tid: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())}")
        appendLine()
        appendLine("TELEFON")
        appendLine("Modell: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Obearbetat ljud stöds: ${services.mic.unprocessedSupported}")
        appendLine("Ingångar:")
        services.mic.inputDevices().forEach { appendLine("  - ${MicMeter.describe(it)}") }
        appendLine()
        appendLine("NÄTVERK")
        appendLine("Lokal adress: ${services.localIp()}")
        appendLine("Servertest: ${serverTest ?: "ej kört"}")
        appendLine("Förfrågningar till servern: ${services.server.requestCount}")
        appendLine("Senaste förfrågan: ${services.server.lastRequest ?: "–"}")
        appendLine()
        appendLine("CHROMECAST")
        appendLine("Tillgänglig: ${cast.available} ${cast.unavailableReason ?: ""}")
        appendLine("Ansluten: ${cast.connected} ${cast.deviceName ?: ""} ${cast.deviceModel ?: ""}")
        appendLine("Volym: ${cast.volume}")
        appendLine("Senaste starttid: ${cast.latencyMs?.let { "$it ms" } ?: "–"}")
        appendLine("Senaste fel: ${cast.lastError ?: "–"}")
        appendLine("Logg:")
        cast.log.forEach { appendLine("  $it") }
        appendLine()
        appendLine("MIKROFON")
        appendLine("Källa: ${mic.source?.label ?: "–"} · ${mic.effects}")
        appendLine("Enhet: ${mic.routedDevice ?: "–"}")
        appendLine("Fel: ${mic.error ?: "–"}")
        appendLine()
        appendLine("SPARADE MÄTNINGAR")
        if (saved.isEmpty()) appendLine("  (inga)")
        saved.forEach {
            appendLine(
                String.format(
                    Locale.ROOT,
                    "  %s | %s | %s | %s Hz | band %.1f dB | bred %.1f dBFS | variation %.1f dB | %s | %s | volym %s",
                    it.time, it.label, it.signal, SignalSpec.fmt(it.frequency), it.bandDb, it.broadDb,
                    it.spreadDb, it.source, it.device, it.castVolume?.let { v -> "${(v * 100).toInt()} %" } ?: "–",
                ),
            )
        }
    }
}

private fun sweepReport(sweep: se.johronski.subspeaker.SweepController.State): String = buildString {
    appendLine()
    appendLine("SVEPMÄTNINGAR")
    if (sweep.curves.isEmpty()) {
        appendLine("  (inga)")
        return@buildString
    }
    sweep.curves.forEach {
        appendLine(
            String.format(
                Locale.ROOT, "  %s | %s | fördröjning %.3f s | brusmarginal %.0f dB | %s",
                it.time, it.label, it.delaySeconds, it.snrDb, it.device,
            ),
        )
    }
    val f = se.johronski.subspeaker.audio.SweepAnalyzer.FREQUENCIES
    val idx = listOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0)
        .map { t -> f.indices.minByOrNull { kotlin.math.abs(f[it] - t) }!! }
    appendLine("  Medelkurvor (dB) vid " + idx.joinToString(" ") { SignalSpec.fmt(Math.round(f[it] * 10) / 10.0) } + " Hz:")
    sweep.groups.forEach { g ->
        appendLine(
            String.format(Locale.ROOT, "  %s (n=%d, jämnhet ±%.1f, nivå %.1f): ", g.label, g.count, g.flatnessDb, g.meanDb) +
                idx.joinToString(" ") { String.format(Locale.ROOT, "%.1f", g.db[it]) },
        )
    }
    sweep.best?.let { appendLine("  Jämnast 40–150 Hz: ${it.label}") }
}

private fun guideReport(sweep: se.johronski.subspeaker.SweepController.State): String = buildString {
    appendLine()
    appendLine("GUIDE")
    val f = se.johronski.subspeaker.audio.SweepAnalyzer.FREQUENCIES
    val idx = listOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0)
        .map { t -> f.indices.minByOrNull { kotlin.math.abs(f[it] - t) }!! }
    for (stage in listOf("mic", "phase", "level", "genelec")) {
        val groups = sweep.groupsFor(stage)
        if (groups.isEmpty()) continue
        appendLine("  [$stage]")
        groups.forEach { g ->
            appendLine(
                String.format(Locale.ROOT, "  %s (n=%d, avvikelse ±%.1f, nivå %.1f, per plats %s): ",
                    g.label, g.count, g.flatnessDb, g.meanDb,
                    g.perPosition.entries.sortedBy { it.key }.joinToString(",") { String.format(Locale.ROOT, "%d:%.1f", it.key, it.value) },
                ) + idx.joinToString(" ") { String.format(Locale.ROOT, "%.1f", g.db[it]) },
            )
        }
    }
    se.johronski.subspeaker.Guide.phaseVerdict(sweep)?.let {
        appendLine(String.format(Locale.ROOT, "  Fas: %s, %s (marginal %.1f dB)", it.best.key, it.confidence.text, it.marginDb))
    }
    se.johronski.subspeaker.Guide.bestLevel(sweep)?.let { appendLine("  Nivå: $it steg från utgångsläget") }
    se.johronski.subspeaker.Guide.genelecVerdict(sweep)?.let {
        appendLine(String.format(Locale.ROOT, "  Genelec: %s, %s", it.best.key, it.confidence.text))
    }
}
