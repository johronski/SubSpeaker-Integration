@file:OptIn(ExperimentalLayoutApi::class)

package se.johronski.subspeaker.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.johronski.subspeaker.AppServices
import se.johronski.subspeaker.Output
import se.johronski.subspeaker.audio.LocalPlayer
import se.johronski.subspeaker.audio.MicMeter
import se.johronski.subspeaker.audio.SignalSpec
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TroubleshootScreen(services: AppServices, setup: Setup) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by services.settings.state.collectAsState()
    val cast by services.cast.state.collectAsState()
    val local by services.local.state.collectAsState()
    val mic by services.mic.state.collectAsState()
    val sweep by services.sweep.state.collectAsState()
    val guide by services.guide.state.collectAsState()
    val readings by services.readings.collectAsState()
    val serverError by services.serverError.collectAsState()
    var serverTest by remember { mutableStateOf<String?>(null) }

    Panel("Uppspelning i telefonen") {
        Muted("Gäller bara när du spelar via telefonen. Byt metod om inget hörs.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalPlayer.Method.entries.forEach { m ->
                FilterChip(
                    selected = settings.method == m,
                    onClick = { services.settings.update { it.copy(method = m) } },
                    label = { Text(m.label) },
                )
            }
        }
        if (settings.output == Output.PHONE) {
            local.routedDevice?.let { Muted("Spelas via: $it") }
            if (local.debug.isNotEmpty()) Muted(local.debug)
            local.error?.let { Notice(it, Tone.BAD) }
        }
    }

    Panel("Mikrofonens ljudkälla") {
        Muted(
            if (services.mic.unprocessedSupported) "Telefonen stöder obearbetat ljud, som används automatiskt."
            else "Telefonen saknar obearbetat ljud – Röstigenkänning används automatiskt.",
        )
        ChoiceRow("Automatiskt (${services.micSource().label})", settings.source == null) {
            services.settings.update { it.copy(source = null) }
        }
        MicMeter.Source.entries.forEach { s ->
            ChoiceRow(s.label, settings.source == s) { services.settings.update { it.copy(source = s) } }
        }
        if (mic.running) Muted("Effekter: ${mic.effects} · ${mic.routedDevice ?: "–"}")
    }

    Panel("Chromecast och nätverk") {
        Muted("Telefonens adress: ${services.localIp() ?: "inget wifi hittat"} · port 8089")
        serverError?.let { Notice(it, Tone.BAD) }
        OutlinedButton(onClick = {
            serverTest = "Testar …"
            scope.launch { serverTest = withContext(Dispatchers.IO) { testServer(services) } }
        }) { Text("Testa telefonens ljudserver") }
        serverTest?.let { Muted(it) }
        Muted(
            "Läge: ${cast.playerState}" + (cast.latencyMs?.let { " · startade efter $it ms" } ?: ""),
        )
    }

    Panel("Testplan vid problem") {
        NumberedStep("A", "Spela 90 Hz ren ton under Lyssna på låg volym. Hörs den i både subben och Genelecen?")
        NumberedStep("B", "Starta mikrofonen under Lyssna. Är nivån stabil? Spara som \"inbyggd\".")
        NumberedStep("C", "Koppla in en annan mikrofon, välj den i raden överst och spara igen. Jämför nivåerna.")
        NumberedStep("D", "Prova ljudkällorna ovan med samma mikrofon. Notera vilken som ger stabilast nivå.")
        NumberedStep("E", "Mute-test: spara en avläsning, tryck MUTE på fjärrkontrollen och spara igen. Tystnar även Genelecen?")
        NumberedStep("F", "Tryck Dela rapport nedan och skicka den.")
    }

    Panel("Rapport") {
        Button(onClick = {
            val report = buildReport(context, services, cast, mic, readings, serverTest) +
                "\nTELEFONUPPSPELNING\nVald källa: ${settings.output.label}\nUtgång: ${local.routedDevice ?: "–"}\n" +
                "Metod: ${local.method?.label ?: settings.method.label}\nAppens nivå: ${(settings.localGain * 100).toInt()} %\n" +
                "Status: ${local.debug}\nFel: ${local.error ?: "–"}\n" +
                sweepReport(sweep) + guideReport(sweep, guide.targetBalance, guide.subBaseline)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "SubSpeaker diagnosrapport")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            context.startActivity(Intent.createChooser(send, "Dela rapport"))
        }) { Text("Dela rapport") }
        Muted("Rapporten innehåller alla mätningar och inställningar, som text.")
        OutlinedButton(
            onClick = { se.johronski.subspeaker.Export.share(context, services) },
            enabled = sweep.curves.isNotEmpty() && !sweep.busy,
        ) { Text("Exportera mätdata (fil)") }
        Muted("Fullständig mätdata med impulssvar, för noggrann analys.")
        Label("Logg")
        cast.log.takeLast(12).forEach { Muted(it) }
    }
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
    saved: List<se.johronski.subspeaker.SavedReading>,
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
                Locale.ROOT, "  %s | %s | fördröjning %.3f s | brusmarginal %.0f dB | svepskillnad %s | %s",
                it.time, it.label, it.delaySeconds, it.snrDb,
                it.repeatSpreadDb?.let { d -> String.format(Locale.ROOT, "%.1f dB", d) } ?: "–", it.device,
            ) + (it.levelStep?.let { st -> " | volymsteg $st (uppgift ${it.task})" } ?: ""),
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

private fun guideReport(sweep: se.johronski.subspeaker.SweepController.State, target: Double, baseline: Int?): String = buildString {
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
    se.johronski.subspeaker.Guide.levelBalance(sweep).toSortedMap().forEach { (k, v) ->
        appendLine(String.format(Locale.ROOT, "  Basbalans vid steg %+d: %+.1f dB", k, v))
    }
    se.johronski.subspeaker.Guide.levelAdvice(sweep, target)?.let {
        appendLine(
            String.format(
                Locale.ROOT, "  Nivå: %+d steg (mål %+.0f dB, %.2f dB/steg)%s",
                it.step, target, it.dbPerStep, it.warning?.let { w -> " – $w" } ?: "",
            ),
        )
    }
    baseline?.let { appendLine("  Subbens display i utgångsläget: $it") }
    se.johronski.subspeaker.Guide.genelecVerdict(sweep)?.let {
        appendLine(String.format(Locale.ROOT, "  Genelec: %s, %s", it.best.key, it.confidence.text))
    }
}
