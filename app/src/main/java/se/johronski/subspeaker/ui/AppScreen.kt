@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package se.johronski.subspeaker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import se.johronski.subspeaker.AppServices
import se.johronski.subspeaker.Output
import se.johronski.subspeaker.Store
import se.johronski.subspeaker.audio.LocalPlayer
import se.johronski.subspeaker.audio.MicMeter
import se.johronski.subspeaker.audio.SignalSpec
import se.johronski.subspeaker.audio.ToneGrid
import java.util.Locale

private val tabs = listOf("Guide", "Lyssna", "Mät", "Felsökning")

/** Gemensamt för alla flikar: om mätning och uppspelning är möjlig. */
class Setup(
    val hasMicPermission: Boolean,
    val requestPermission: () -> Unit,
    val openSetup: () -> Unit,
    val micName: String,
    val playName: String,
) {
    val problem: String?
        get() = when {
            !hasMicPermission -> "Appen behöver tillgång till mikrofonen. Tryck på raden ovan."
            playName.isBlank() -> "Välj din Chromecast – tryck på raden ovan."
            else -> null
        }
    val canMeasure: Boolean get() = problem == null
}

@Composable
fun AppScreen(services: AppServices) {
    val context = LocalContext.current
    val settings by services.settings.state.collectAsState()
    val cast by services.cast.state.collectAsState()
    val local by services.local.state.collectAsState()
    val sweep by services.sweep.state.collectAsState()
    val playback by services.playback.state.collectAsState()
    val mic by services.mic.state.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSetup by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    var hasMicPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMicPermission = it }

    LaunchedEffect(Unit) {
        services.events.collect { e ->
            val r = snackbar.showSnackbar(
                e.message,
                actionLabel = if (e.undoable) "Ångra" else null,
                withDismissAction = !e.undoable,
                duration = if (e.undoable) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) services.undo()
        }
    }
    // Mikrofonmätaren lyssnar på den frekvens som spelas.
    LaunchedEffect(playback.spec) {
        playback.spec?.measureFrequency?.let { services.mic.setFrequency(it) }
    }

    val micDevice = remember(settings.micKey, showSetup) { services.micDevice() }
    val lastRouted = sweep.curves.lastOrNull()?.device?.takeIf { it.isNotBlank() } ?: mic.routedDevice
    val micName = micDevice?.let { MicMeter.shortName(it) }
        ?: ("Automatiskt" + (lastRouted?.let { " (senast: ${it.substringAfter("– ").ifBlank { it }})" } ?: ""))
    val playName = when (settings.output) {
        Output.CAST -> if (cast.connected) cast.deviceName.orEmpty() else ""
        Output.PHONE -> "Telefonen"
    }
    val setup = Setup(
        hasMicPermission = hasMicPermission,
        requestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        openSetup = { showSetup = true },
        micName = micName,
        playName = playName,
    )

    val playing = if (settings.output == Output.PHONE) local.playing else cast.playerState in setOf("Spelar", "Buffrar")

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(Paper).statusBarsPadding()) {
                Text(
                    "SubSpeaker",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp),
                )
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Paper,
                    contentColor = Ink,
                    indicator = { pos ->
                        TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = Accent)
                    },
                    divider = { HorizontalDivider(color = Line) },
                ) {
                    tabs.forEachIndexed { i, t ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = {
                                Text(
                                    t,
                                    fontSize = 15.sp,
                                    fontWeight = if (tab == i) FontWeight.Medium else FontWeight.Normal,
                                    color = if (tab == i) Ink else InkMuted,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
                SetupBar(setup, settings.output, cast.connected, cast.connecting, cast.volume, settings.localGain) { showSetup = true }
            }
        },
        bottomBar = {
            BottomPlayer(services, playing)
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (tab) {
                0 -> GuideScreen(services, setup)
                1 -> ListenScreen(services, setup)
                2 -> MeasureScreen(services, setup)
                else -> TroubleshootScreen(services, setup)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSetup) {
        SetupSheet(services, hasMicPermission, setup.requestPermission) { showSetup = false }
    }
}

/** Fast rad överst: vad som spelar, vilken mikrofon och volym. Tryck för att ändra. */
@Composable
private fun SetupBar(
    setup: Setup,
    output: Output,
    connected: Boolean,
    connecting: Boolean,
    castVolume: Double?,
    localGain: Float,
    onClick: () -> Unit,
) {
    val playOk = output == Output.PHONE || connected
    val playText = when {
        output == Output.PHONE -> "Telefonen"
        connected -> setup.playName
        connecting -> "Ansluter …"
        else -> "Välj Chromecast"
    }
    val vol = if (output == Output.CAST) castVolume?.let { "${(it * 100).toInt()} %" } ?: "–" else "${(localGain * 100).toInt()} %"
    Surface(
        color = CardColor,
        border = BorderStroke(1.dp, Line),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            StatusItem("Spelar via", playText, playOk, Modifier.weight(1.1f))
            StatusItem("Mikrofon", if (setup.hasMicPermission) setup.micName else "Ge tillgång", setup.hasMicPermission, Modifier.weight(1.1f))
            StatusItem("Volym", vol, true, Modifier.weight(0.6f))
        }
    }
}

@Composable
private fun StatusItem(title: String, value: String, ok: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(title, fontSize = 12.sp, color = InkMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(7.dp).clip(CircleShape).background(if (ok) Good else Warn))
            Spacer(Modifier.width(6.dp))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Panel nerifrån för att välja uppspelning, mikrofon och volym utan att lämna fliken. */
@Composable
private fun SetupSheet(
    services: AppServices,
    hasMicPermission: Boolean,
    requestPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val settings by services.settings.state.collectAsState()
    val cast by services.cast.state.collectAsState()
    var inputs by remember { mutableStateOf(services.mic.inputDevices()) }
    var outputs by remember { mutableStateOf(services.local.outputDevices()) }
    var sliderValue by remember { mutableFloatStateOf(cast.volume?.toFloat() ?: 0.3f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(cast.volume) { if (!dragging) cast.volume?.let { sliderValue = it.toFloat() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CardColor,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Uppspelning", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Output.entries.forEach { o ->
                    FilterChip(selected = settings.output == o, onClick = { services.setOutput(o) }, label = { Text(o.label) })
                }
            }
            if (settings.output == Output.CAST) {
                Muted(
                    when {
                        cast.connected -> "Ansluten till ${cast.deviceName}. Appen ansluter automatiskt nästa gång."
                        cast.connecting -> "Ansluter …"
                        cast.devices.isEmpty() -> "Söker efter Chromecast på nätverket …"
                        else -> "Välj din Chromecast:"
                    },
                )
                cast.devices.forEach { d ->
                    val isCurrent = cast.connected && cast.deviceName == d.name
                    ChoiceRow(d.name + (if (d.group) " (grupp)" else ""), isCurrent) { if (!isCurrent) services.cast.connect(d) }
                }
                if (cast.connected) OutlinedButton(onClick = { services.forgetCast() }) { Text("Koppla från") }
                cast.lastError?.let { Notice(it, Tone.BAD) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Utgång")
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { outputs = services.local.outputDevices() }) { Text("Uppdatera") }
                }
                ChoiceRow("Automatiskt", settings.outKey == null) { services.settings.update { it.copy(outKey = null) } }
                outputs.forEach { d ->
                    val k = Store.deviceKey(d)
                    ChoiceRow(LocalPlayer.describeOutput(d), settings.outKey == k) { services.settings.update { it.copy(outKey = k) } }
                }
                Muted("Telefonens högtalare återger inte djupbas. Använd kabel från hörlursutgången till subbens linjeingång.")
            }

            Spacer(Modifier.height(4.dp))
            val cap = if (settings.allowHighVolume) 1f else 0.6f
            if (settings.output == Output.CAST) {
                Label("Volym: " + (cast.volume?.let { "${(it * 100).toInt()} %" } ?: "–"))
                Slider(
                    value = sliderValue.coerceIn(0f, cap),
                    onValueChange = { dragging = true; sliderValue = it },
                    onValueChangeFinished = { dragging = false; services.cast.setVolume(sliderValue.toDouble()) },
                    valueRange = 0f..cap,
                    enabled = cast.connected,
                )
                Muted("Chromecastens volym är huvudvolym för hela kedjan.")
            } else {
                Label("Appens nivå: ${(settings.localGain * 100).toInt()} %")
                Slider(value = settings.localGain.coerceIn(0f, cap), onValueChange = { services.setLocalGain(it) }, valueRange = 0f..cap)
                Muted("Telefonens egen mediavolym påverkar också nivån.")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.allowHighVolume,
                    onCheckedChange = { v -> services.settings.update { it.copy(allowHighVolume = v) } },
                )
                Text("Tillåt över 60 %")
            }
            Muted("Börja lågt. Djupbas på hög nivå kan skada högtalarna utan att låta särskilt starkt.")

            HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 6.dp))
            Text("Mikrofon", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            if (!hasMicPermission) {
                Button(onClick = requestPermission) { Text("Ge appen tillgång till mikrofonen") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("Koppla in mikrofonen och tryck Uppdatera.", Modifier.weight(1f))
                    OutlinedButton(onClick = { inputs = services.mic.inputDevices() }) { Text("Uppdatera") }
                }
                ChoiceRow("Automatiskt – telefonen väljer, oftast den inkopplade", settings.micKey == null) {
                    services.settings.update { it.copy(micKey = null) }
                }
                inputs.forEach { d ->
                    val k = Store.deviceKey(d)
                    ChoiceRow(MicMeter.describe(d), settings.micKey == k) { services.settings.update { it.copy(micKey = k) } }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Klar", fontSize = 17.sp) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Fast fält längst ner: pågående mätning med Avbryt, eller signalen som spelas med Stopp. */
@Composable
private fun BottomPlayer(services: AppServices, playing: Boolean) {
    val sweep by services.sweep.state.collectAsState()
    val playback by services.playback.state.collectAsState()
    val local by services.local.state.collectAsState()
    val cast by services.cast.state.collectAsState()
    val settings by services.settings.state.collectAsState()
    val spec = playback.spec
    if (!sweep.busy && !(playing && spec != null)) return

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playing) {
        while (playing) {
            now = SystemClock.elapsedRealtime()
            delay(100)
        }
    }

    Surface(color = CardColor, border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (sweep.busy) {
                LinearProgressIndicator(
                    progress = { sweep.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Accent,
                    trackColor = CardAlt,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sweep.status, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { services.sweep.cancel() },
                        colors = ButtonDefaults.buttonColors(containerColor = Bad),
                        modifier = Modifier.height(48.dp),
                    ) { Text("Avbryt") }
                }
            } else if (spec != null) {
                val basePos = if (settings.output == Output.PHONE) local.positionMs else cast.positionMs
                val baseAt = if (settings.output == Output.PHONE) local.positionAt else cast.positionAt
                val est = basePos + (now - baseAt).coerceIn(0L, 2000L)
                val f = spec.frequencyAt(est)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (spec is SignalSpec.Tone) {
                        OutlinedButton(onClick = { services.playback.stepTone(-1) }, modifier = Modifier.size(52.dp), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                            Text("−", fontSize = 24.sp)
                        }
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (f != null) String.format(Locale.ROOT, "%s Hz", SignalSpec.fmt(ToneGrid.round(f))) else "paus",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Medium,
                            color = Accent,
                        )
                        Text(
                            when (spec) {
                                is SignalSpec.Tone -> (if (spec.warble) "Svajton" else "Ren ton") + " · steg ${ToneGrid.label(playback.dipDiv)}"
                                else -> spec.label
                            },
                            fontSize = 13.sp,
                            color = InkMuted,
                            maxLines = 1,
                        )
                    }
                    if (spec is SignalSpec.Tone) {
                        OutlinedButton(onClick = { services.playback.stepTone(1) }, modifier = Modifier.size(52.dp), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                            Text("+", fontSize = 24.sp)
                        }
                    }
                    Button(
                        onClick = { services.playback.stop() },
                        colors = ButtonDefaults.buttonColors(containerColor = Bad),
                        modifier = Modifier.height(52.dp),
                    ) { Text("Stopp") }
                }
            }
        }
    }
}

/** Gemensam rad med signalknappar som spelar direkt. */
@Composable
fun SignalChips(services: AppServices, signals: List<SignalSpec>, selected: SignalSpec?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        signals.forEach { s ->
            FilterChip(selected = selected == s, onClick = { services.playback.play(s) }, label = { Text(s.label) })
        }
    }
}
