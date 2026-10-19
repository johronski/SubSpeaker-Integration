package se.johronski.subspeaker

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
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

class AppServices(private val context: Context) {
    val server = SignalServer(SignalServer.PORT)
    val cast = CastController(context)
    val mic = MicMeter(context)
    val local = LocalPlayer(context)
    val sweep = SweepController(this)
    val guide = GuideController()

    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError

    init {
        Thread {
            try {
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            } catch (e: Exception) {
                _serverError.value = "Servern startade inte: ${e.message}"
            }
        }.start()
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
