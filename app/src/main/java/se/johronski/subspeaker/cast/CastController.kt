package se.johronski.subspeaker.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Styr en Chromecast direkt över nätverket (samma metod som RadioCast),
 * utan Googles Cast-bibliotek.
 */
class CastController(context: Context) {

    data class Device(val name: String, val host: String, val port: Int, val group: Boolean, val status: String) {
        val key: String get() = "$host:$port"
    }

    data class State(
        val available: Boolean = true,
        val unavailableReason: String? = null,
        val searching: Boolean = false,
        val devices: List<Device> = emptyList(),
        val connecting: Boolean = false,
        val connected: Boolean = false,
        val deviceName: String? = null,
        val deviceModel: String? = null,
        val volume: Double? = null,
        val playerState: String = "–",
        val title: String? = null,
        val positionMs: Long = 0,
        /** Tidpunkt (SystemClock.elapsedRealtime) då positionMs senast uppdaterades. */
        val positionAt: Long = 0,
        val durationMs: Long = 0,
        val latencyMs: Long? = null,
        val lastError: String? = null,
        val log: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val discovery = CastDiscovery(context, object : CastDiscovery.Listener {
        override fun onFound(name: String, host: String, port: Int, id: String, status: String, group: Boolean) {
            val d = Device(name, host, port, group, status)
            val list = _state.value.devices.filterNot { it.key == d.key } + d
            _state.value = _state.value.copy(devices = list.sortedBy { it.name.lowercase(Locale.ROOT) })
        }

        override fun onError(message: String) {
            log(message)
        }
    })

    @Volatile
    private var client: CastClient? = null

    @Volatile
    private var loadStartedAt = 0L

    @Volatile
    private var waitingForPlay = false

    fun onResume() {
        _state.value = _state.value.copy(searching = true)
        discovery.start()
    }

    fun onPause() {
        discovery.stop()
        _state.value = _state.value.copy(searching = false)
    }

    fun connect(device: Device) {
        _state.value = _state.value.copy(connecting = true, lastError = null)
        log("Ansluter till ${device.name} (${device.host})")
        io.execute {
            client?.close()
            val c = CastClient(device.host, device.port, listener)
            try {
                c.connect()
                client = c
                c.prepare()
                _state.value = _state.value.copy(
                    connecting = false,
                    connected = true,
                    deviceName = device.name,
                    deviceModel = if (device.group) "högtalargrupp" else "Chromecast",
                )
                log("Ansluten till ${device.name}")
            } catch (e: Exception) {
                c.close()
                client = null
                _state.value = _state.value.copy(connecting = false, connected = false, lastError = "Kunde inte ansluta: ${e.message}")
                log("Anslutning misslyckades: ${e.message}")
            }
        }
    }

    fun disconnect() {
        val c = client ?: return
        io.execute {
            try { c.stopReceiver() } catch (_: Exception) {}
            c.close()
        }
        client = null
        _state.value = _state.value.copy(connected = false, deviceName = null, deviceModel = null, playerState = "–")
        log("Frånkopplad")
    }

    fun play(url: String, title: String, durationMs: Long) {
        val c = client
        if (c == null) {
            log("Inte ansluten till någon Chromecast")
            return
        }
        loadStartedAt = SystemClock.elapsedRealtime()
        waitingForPlay = true
        _state.value = _state.value.copy(
            title = title, latencyMs = null, lastError = null, positionMs = 0,
            positionAt = SystemClock.elapsedRealtime(), durationMs = durationMs,
        )
        log("Laddar: $title")
        io.execute {
            try {
                c.play(url, title, "audio/wav", "BUFFERED")
                pollPosition()
            } catch (e: Exception) {
                waitingForPlay = false
                _state.value = _state.value.copy(lastError = "Uppspelning misslyckades: ${e.message}")
                log("Uppspelning misslyckades: ${e.message}")
            }
        }
    }

    fun stop() {
        val c = client ?: return
        waitingForPlay = false
        io.execute {
            try { c.stopMedia() } catch (e: Exception) { log("Stopp misslyckades: ${e.message}") }
        }
        log("Stopp")
    }

    fun setVolume(v: Double) {
        val c = client ?: return
        val level = v.coerceIn(0.0, 1.0)
        _state.value = _state.value.copy(volume = level)
        io.execute {
            try { c.setVolume(level) } catch (e: Exception) { log("Kunde inte ändra volym: ${e.message}") }
        }
    }

    private fun pollPosition() {
        main.removeCallbacksAndMessages(null)
        val tick = object : Runnable {
            override fun run() {
                val c = client ?: return
                val st = _state.value.playerState
                if (st == "Vilar" && !waitingForPlay) return
                io.execute { try { c.requestMediaStatus() } catch (_: Exception) {} }
                main.postDelayed(this, 1000)
            }
        }
        main.postDelayed(tick, 1000)
    }

    private val listener = object : CastClient.Listener {
        override fun onMessage(text: String) {}

        override fun onDisconnected(reason: String) {
            client = null
            _state.value = _state.value.copy(connected = false, playerState = "–")
            log(reason)
        }

        override fun onPlayerState(state: String) {
            val name = when (state) {
                "PLAYING" -> "Spelar"
                "BUFFERING" -> "Buffrar"
                "PAUSED" -> "Pausad"
                "IDLE" -> "Vilar"
                else -> state
            }
            var latency = _state.value.latencyMs
            if (state == "PLAYING" && waitingForPlay) {
                latency = SystemClock.elapsedRealtime() - loadStartedAt
                waitingForPlay = false
                log("Spelar efter $latency ms")
            }
            _state.value = if (state == "PLAYING" && latency != _state.value.latencyMs) {
                // Uppspelningen har just börjat: positionen räknas från nu.
                _state.value.copy(playerState = name, latencyMs = latency, positionMs = 0, positionAt = SystemClock.elapsedRealtime())
            } else {
                _state.value.copy(playerState = name, latencyMs = latency)
            }
        }

        override fun onVolume(level: Double) {
            _state.value = _state.value.copy(volume = level)
        }

        override fun onPosition(seconds: Double) {
            _state.value = _state.value.copy(positionMs = (seconds * 1000).toLong(), positionAt = SystemClock.elapsedRealtime())
        }
    }

    private fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        _state.value = _state.value.copy(log = (_state.value.log + "$t $msg").takeLast(40))
    }
}
