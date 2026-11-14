package se.johronski.subspeaker

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.net.ConnectivityManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import se.johronski.subspeaker.audio.Calibration
import se.johronski.subspeaker.audio.LocalPlayer
import se.johronski.subspeaker.audio.MicCalibration
import se.johronski.subspeaker.audio.MicMeter
import se.johronski.subspeaker.audio.SignalServer
import se.johronski.subspeaker.cast.CastController
import java.net.Inet4Address

class SubSpeakerApp : Application() {
    lateinit var services: AppServices
        private set

    override fun onCreate() {
        super.onCreate()
        services = AppServices(this)
    }
}

/** En sparad avläsning från mikrofonmätaren. */
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

/** Ett kort meddelande längst ner, eventuellt med Ångra. */
data class UiEvent(val message: String, val undoable: Boolean = false)

class AppServices(private val context: Context) {
    val store = Store(context)
    val settings = SettingsStore(store)
    val server = SignalServer(SignalServer.PORT)
    val cast = CastController(context)
    val mic = MicMeter(context)
    val local = LocalPlayer(context)
    val sweep = SweepController(this, store)
    val guide = GuideController(store)
    val playback = PlaybackController(this, store)
    val speaker = Speaker(context)
    val health = HealthController(this, store, speaker)

    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError

    private val _readings = MutableStateFlow<List<SavedReading>>(emptyList())
    val readings: StateFlow<List<SavedReading>> = _readings

    private val _calibration = MutableStateFlow(loadCalibration())

    /** Inläst kalibreringsfil, eller null. Används bara när en extern mikrofon spelar in, se [refreshCalibration]. */
    val calibration: StateFlow<Calibration?> = _calibration

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events

    private var undoCurves: List<SweepController.Curve>? = null
    private var undoGuide: GuideController.State? = null

    init {
        local.gain = settings.value.localGain
        speaker.enabled = settings.value.voice
        cast.preferredName = settings.value.castName
        cast.onConnected = { name -> settings.update { it.copy(castName = name) } }
        refreshCalibration()
        Thread {
            try {
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            } catch (e: Exception) {
                _serverError.value = "Servern startade inte: ${e.message}"
            }
        }.start()
    }

    fun notify(message: String) {
        _events.tryEmit(UiEvent(message))
    }

    /** Kör en raderande åtgärd och erbjuder Ångra. */
    fun destructive(message: String, action: () -> Unit) {
        undoCurves = sweep.state.value.curves
        undoGuide = guide.state.value
        action()
        _events.tryEmit(UiEvent(message, undoable = true))
    }

    fun undo() {
        undoCurves?.let { sweep.restore(it) }
        undoGuide?.let { guide.restore(it) }
        undoCurves = null
        undoGuide = null
    }

    fun addReading(r: SavedReading) {
        _readings.value = _readings.value + r
    }

    fun clearReadings() {
        _readings.value = emptyList()
    }

    // ---------- Kalibreringsfil för mätmikrofon ----------

    private fun loadCalibration(): Calibration? {
        val text = store.readFile(CAL_FILE) ?: return null
        return try {
            Calibration.parse(text, store.get(CAL_NAME_KEY) ?: "Kalibreringsfil")
        } catch (_: Exception) {
            null
        }
    }

    /** Läser in en kalibreringsfil. Returnerar ett felmeddelande, eller null om det gick bra. */
    fun importCalibration(uri: android.net.Uri): String? {
        return try {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Kalibreringsfil"
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                // En kalibreringsfil är några tiotal kB; större är fel fil.
                val bytes = input.readBytes()
                if (bytes.size > 2_000_000) return "Filen är för stor för att vara en kalibreringsfil."
                String(bytes, Charsets.UTF_8)
            } ?: return "Filen gick inte att öppna."
            val cal = Calibration.parse(text, name)
            store.writeFile(CAL_FILE, text)
            store.put(CAL_NAME_KEY, name)
            _calibration.value = cal
            refreshCalibration()
            null
        } catch (e: IllegalArgumentException) {
            e.message
        } catch (e: Exception) {
            "Filen gick inte att läsa: ${e.message}"
        }
    }

    fun clearCalibration() {
        java.io.File(context.filesDir, CAL_FILE).delete()
        _calibration.value = null
        refreshCalibration()
    }

    /** Mikrofonen som spelar in: användarens val, annars den inkopplade (Android tar den före den inbyggda). */
    fun effectiveMic(): AudioDeviceInfo? =
        micDevice() ?: mic.inputDevices().firstOrNull { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }

    /** true om inspelningen görs med en inkopplad mikrofon (USB eller sladd), dvs. en som kalibreringsfilen kan gälla. */
    fun externalMicInUse(): Boolean = effectiveMic()?.type in EXTERNAL_MICS

    /**
     * Slår på korrigeringen om en fil finns och en extern mikrofon används, annars av.
     * Anropas när en mätning startar, så att den inbyggda mikrofonen aldrig korrigeras med fel fil.
     */
    fun refreshCalibration() {
        MicCalibration.active = _calibration.value?.takeIf { externalMicInUse() }
    }

    // ---------- Val av enheter ----------

    fun micDevice(): AudioDeviceInfo? =
        settings.value.micKey?.let { k -> mic.inputDevices().firstOrNull { Store.deviceKey(it) == k } }

    fun outputDevice(): AudioDeviceInfo? =
        settings.value.outKey?.let { k -> local.outputDevices().firstOrNull { Store.deviceKey(it) == k } }

    /** Ljudkällan som används: användarens val, annars obearbetat om telefonen stöder det. */
    fun micSource(): MicMeter.Source =
        settings.value.source
            ?: if (mic.unprocessedSupported) MicMeter.Source.UNPROCESSED else MicMeter.Source.VOICE_RECOGNITION

    fun setOutput(o: Output) {
        if (o != settings.value.output) {
            local.stop()
            if (cast.state.value.connected) cast.stop()
        }
        settings.update { it.copy(output = o) }
    }

    fun setLocalGain(g: Float) {
        local.gain = g
        settings.update { it.copy(localGain = g) }
    }

    fun setVoice(on: Boolean) {
        speaker.enabled = on
        settings.update { it.copy(voice = on) }
    }

    fun forgetCast() {
        cast.preferredName = null
        settings.update { it.copy(castName = null) }
        cast.disconnect()
    }

    /** Kort vibration när en mätning är klar, så att man vet att man kan gå tillbaka. */
    fun signalDone() {
        try {
            val v = context.getSystemService(android.os.Vibrator::class.java) ?: return
            v.vibrate(android.os.VibrationEffect.createOneShot(400, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    /** Telefonens IPv4-adress på det aktiva nätverket (wifi). */
    fun localIp(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    fun signalUrl(path: String): String? = localIp()?.let { "http://$it:${SignalServer.PORT}/$path" }

    private companion object {
        const val CAL_FILE = "mic_calibration.txt"
        const val CAL_NAME_KEY = "micCalibrationName"
        val EXTERNAL_MICS = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
    }
}
