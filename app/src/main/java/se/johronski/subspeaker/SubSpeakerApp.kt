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
import se.johronski.subspeaker.audio.LocalPlayer
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

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events

    private var undoCurves: List<SweepController.Curve>? = null
    private var undoGuide: GuideController.State? = null

    init {
        local.gain = settings.value.localGain
        speaker.enabled = settings.value.voice
        cast.preferredName = settings.value.castName
        cast.onConnected = { name -> settings.update { it.copy(castName = name) } }
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
}
