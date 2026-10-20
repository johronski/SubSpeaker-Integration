package se.johronski.subspeaker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import se.johronski.subspeaker.audio.SweepAnalyzer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exporterar all mätdata som en JSON-fil: kurvor, impulssvar och tidsinformation,
 * så att mätningarna kan analyseras vidare utanför appen.
 */
object Export {

    fun buildJson(context: Context, services: AppServices): JSONObject {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val sweep = services.sweep.state.value
        val guide = services.guide.state.value
        val settings = services.settings.value
        val spec = SweepAnalyzer.SWEEP
        return JSONObject()
            .put("format", "subspeaker-export")
            .put("formatVersion", 1)
            .put("appVersion", version)
            .put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(Date()))
            .put("phone", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put(
                "settings",
                JSONObject()
                    .put("output", settings.output.label)
                    .put("castDevice", services.cast.state.value.deviceName ?: JSONObject.NULL)
                    .put("castVolume", services.cast.state.value.volume ?: JSONObject.NULL)
                    .put("method", settings.method.label)
                    .put("micSource", services.micSource().label)
                    .put("genelecBass", guide.genelecBass)
                    .put("subDisplayBaseline", guide.subBaseline ?: JSONObject.NULL)
                    .put("targetBalance", guide.targetBalance),
            )
            .put(
                "analysis",
                JSONObject()
                    .put("sweep", JSONObject().put("f1", spec.f1).put("f2", spec.f2).put("seconds", spec.seconds).put("dbfs", spec.dbfs))
                    .put("sampleRate", SweepAnalyzer.FS)
                    .put("recordingRate", se.johronski.subspeaker.audio.SignalGenerator.SAMPLE_RATE)
                    .put("decimation", SweepAnalyzer.DECIMATION)
                    .put("decimationFilter", "4 x RBJ biquad lowpass 700 Hz (Q 0.5412, 1.3066, 0.5412, 1.3066)")
                    .put("irWindow", "peak −50 ms … +500 ms, Hann taper 10 ms in, 100 ms out")
                    .put("irEncoding", "float32 little endian, base64")
                    .put("dbSmoothing", "1/6 octave, power average")
                    .put("frequencies", Store.doubles(SweepAnalyzer.FREQUENCIES)),
            )
            .put("markers", Store.doubles(services.playback.state.value.markers.toDoubleArray()))
            .put(
                "healthCheck",
                services.health.state.value.let { h ->
                    JSONObject()
                        .put("method", "periodic pink multitone, moving microphone, 1/6 octave, power average over frames")
                        .put("together", h.together?.let { Store.doubles(it) } ?: JSONObject.NULL)
                        .put("genelecAlone", h.genelec?.let { Store.doubles(it) } ?: JSONObject.NULL)
                        .put("subAlone", h.sub?.let { Store.doubles(it) } ?: JSONObject.NULL)
                        .put("after", h.after?.let { Store.doubles(it) } ?: JSONObject.NULL)
                        .put("changes", Store.strings(h.changes))
                        .put("findings", JSONArray().also { a -> h.report?.findings?.forEach { f -> a.put(f.title) } })
                },
            )
            .put("curves", JSONArray().also { a -> sweep.curves.forEach { a.put(SweepController.toJson(it)) } })
    }

    /** Skriver filen och öppnar Androids delningsmeny. */
    fun share(context: Context, services: AppServices) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = "subspeaker-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".json"
        val file = File(dir, name)
        file.writeText(buildJson(context, services).toString(1))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "SubSpeaker mätdata")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Exportera mätdata"))
    }
}
