package se.johronski.subspeaker.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.min

/**
 * Spelar testsignaler direkt i telefonen: inbyggd högtalare, hörlursuttag eller USB-ljud.
 * Används när ljudet ska gå via kabel till systemet i stället för via Chromecast.
 */
class LocalPlayer(private val context: Context) {

    data class State(
        val playing: Boolean = false,
        val title: String? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val routedDevice: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile
    private var stopRequested = false

    @Volatile
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    /** Appens egen nivå, 0–1, ovanpå telefonens mediavolym. */
    @Volatile
    var gain: Float = 0.3f
        set(v) {
            field = v.coerceIn(0f, 1f)
            try { track?.setVolume(field) } catch (_: Exception) {}
        }

    fun outputDevices(): List<AudioDeviceInfo> =
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }

    fun play(spec: SignalSpec, device: AudioDeviceInfo?) {
        stop()
        stopRequested = false
        _state.value = State(playing = true, title = spec.label, durationMs = spec.durationMs)
        thread = Thread({ run(spec, device) }, "LocalPlayer").also { it.start() }
    }

    fun stop() {
        stopRequested = true
        thread?.join(2000)
        thread = null
    }

    private fun run(spec: SignalSpec, device: AudioDeviceInfo?) {
        val sr = SignalGenerator.SAMPLE_RATE
        var t: AudioTrack? = null
        try {
            val mono = spec.render()
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuf, sr * 2 * 4 / 4)) // ≈ 0,25 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (device != null) t.setPreferredDevice(device)
            t.setVolume(gain)
            track = t
            t.play()

            val chunk = sr / 20
            val buf = FloatArray(chunk * 2)
            var pos = 0
            while (pos < mono.size && !stopRequested) {
                val n = min(chunk, mono.size - pos)
                for (i in 0 until n) {
                    buf[2 * i] = mono[pos + i]
                    buf[2 * i + 1] = mono[pos + i]
                }
                var off = 0
                val total = n * 2
                while (off < total && !stopRequested) {
                    val w = t.write(buf, off, total - off, AudioTrack.WRITE_NON_BLOCKING)
                    if (w < 0) throw IllegalStateException("skrivfel $w")
                    if (w == 0) Thread.sleep(10) else off += w
                }
                pos += n
                publish(t, sr)
            }
            while (!stopRequested && t.playbackHeadPosition < mono.size) {
                Thread.sleep(50)
                publish(t, sr)
            }
            if (stopRequested) {
                // Kort nedtoning för att undvika knäpp.
                for (step in 5 downTo 0) {
                    t.setVolume(gain * step / 5f)
                    Thread.sleep(10)
                }
            }
            t.stop()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Uppspelning misslyckades: ${e.message}")
        } finally {
            try { t?.release() } catch (_: Exception) {}
            track = null
            _state.value = _state.value.copy(playing = false)
        }
    }

    private fun publish(t: AudioTrack, sr: Int) {
        _state.value = _state.value.copy(
            positionMs = t.playbackHeadPosition.toLong() * 1000 / sr,
            routedDevice = t.routedDevice?.let { describeOutput(it) },
        )
    }

    companion object {
        fun describeOutput(d: AudioDeviceInfo): String {
            val type = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Telefonens högtalare"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Lur"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Hörlurar (3,5 mm)"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset (3,5 mm)"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB-ljud"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB-tillbehör"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
                AudioDeviceInfo.TYPE_LINE_ANALOG -> "Linjeutgång"
                else -> "Typ ${d.type}"
            }
            return "$type – ${d.productName}"
        }
    }
}
