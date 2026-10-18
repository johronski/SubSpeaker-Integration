package se.johronski.subspeaker.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Spelar testsignaler direkt i telefonen: inbyggd högtalare, hörlursuttag eller USB-ljud.
 *
 * Två metoder:
 * - DIRECT: AudioTrack, signalen skrivs direkt till ljudsystemet.
 * - MEDIA: ExoPlayer (samma spelare som RadioCast) läser WAV-filen från appens egen ljudserver.
 */
class LocalPlayer(private val context: Context) {

    enum class Method(val label: String) { DIRECT("Direkt"), MEDIA("ExoPlayer") }

    data class State(
        val playing: Boolean = false,
        val title: String? = null,
        val method: Method? = null,
        val positionMs: Long = 0,
        /** Tidpunkt (SystemClock.elapsedRealtime) då positionMs senast uppdaterades. */
        val positionAt: Long = 0,
        val durationMs: Long = 0,
        val routedDevice: String? = null,
        val debug: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val main = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager get() = context.getSystemService(AudioManager::class.java)

    @Volatile
    private var stopRequested = false

    @Volatile
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var media: ExoPlayer? = null

    /** Appens egen nivå, 0–1, ovanpå telefonens mediavolym. */
    @Volatile
    var gain: Float = 0.3f
        set(v) {
            field = v.coerceIn(0f, 1f)
            try { track?.setVolume(field) } catch (_: Exception) {}
            try { media?.volume = field } catch (_: Exception) {}
        }

    fun outputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }

    private fun mediaVolumeText(): String {
        val am = audioManager
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val m = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return "mediavolym $v/$m"
    }

    fun play(spec: SignalSpec, device: AudioDeviceInfo?, method: Method, serverUrl: String) {
        stop()
        stopRequested = false
        _state.value = State(
            playing = true,
            title = spec.label,
            method = method,
            durationMs = spec.durationMs,
            debug = "Startar … (${mediaVolumeText()})",
        )
        when (method) {
            Method.DIRECT -> thread = Thread({ runDirect(spec, device) }, "LocalPlayer").also { it.start() }
            Method.MEDIA -> startMedia(serverUrl, device)
        }
    }

    fun stop() {
        stopRequested = true
        thread?.join(2000)
        thread = null
        media?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        media = null
        main.removeCallbacksAndMessages(null)
        if (_state.value.playing) _state.value = _state.value.copy(playing = false)
    }

    // ---------------- Direkt (AudioTrack) ----------------

    private fun runDirect(spec: SignalSpec, device: AudioDeviceInfo?) {
        val sr = SignalGenerator.SAMPLE_RATE
        var t: AudioTrack? = null
        var written = 0L
        try {
            val mono = spec.render()
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) throw IllegalStateException("getMinBufferSize gav $minBuf")
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
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuf * 2, sr * 4 / 4)) // ≥ 0,25 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("AudioTrack initierades inte (state ${t.state})")
            if (device != null) t.setPreferredDevice(device)
            t.setVolume(gain)
            track = t

            val chunk = sr / 20 // 50 ms
            val buf = ShortArray(chunk * 2)
            var pos = 0
            var started = false
            while (pos < mono.size && !stopRequested) {
                val n = min(chunk, mono.size - pos)
                for (i in 0 until n) {
                    val v = (mono[pos + i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                    buf[2 * i] = v
                    buf[2 * i + 1] = v
                }
                val w = t.write(buf, 0, n * 2)
                if (w < 0) throw IllegalStateException("write gav felkod $w")
                written += w / 2
                pos += n
                if (!started) {
                    t.play()
                    started = true
                }
                publishDirect(t, sr, written)
            }
            while (!stopRequested && t.playbackHeadPosition < mono.size) {
                Thread.sleep(50)
                publishDirect(t, sr, written)
            }
            if (stopRequested) {
                for (step in 5 downTo 0) {
                    t.setVolume(gain * step / 5f)
                    Thread.sleep(10)
                }
            }
            t.stop()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Uppspelning misslyckades: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { t?.release() } catch (_: Exception) {}
            track = null
            _state.value = _state.value.copy(playing = false)
        }
    }

    private fun publishDirect(t: AudioTrack, sr: Int, written: Long) {
        val head = t.playbackHeadPosition.toLong()
        val ps = when (t.playState) {
            AudioTrack.PLAYSTATE_PLAYING -> "spelar"
            AudioTrack.PLAYSTATE_PAUSED -> "pausad"
            AudioTrack.PLAYSTATE_STOPPED -> "stoppad"
            else -> "läge ${t.playState}"
        }
        _state.value = _state.value.copy(
            positionMs = head * 1000 / sr,
            positionAt = android.os.SystemClock.elapsedRealtime(),
            routedDevice = t.routedDevice?.let { describeOutput(it) } ?: "okänd",
            debug = "AudioTrack $ps · skrivna ramar $written · spelade $head · ${mediaVolumeText()}",
        )
    }

    // ---------------- Mediaspelare ----------------

    private fun startMedia(url: String, device: AudioDeviceInfo?) {
        try {
            val p = ExoPlayer.Builder(context)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false,
                )
                .build()
            media = p
            p.volume = gain
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        _state.value = _state.value.copy(debug = "ExoPlayer spelar · ${mediaVolumeText()}")
                        pollMedia()
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        _state.value = _state.value.copy(playing = false, debug = "ExoPlayer klar")
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.value = _state.value.copy(
                        playing = false,
                        error = "ExoPlayer gav fel: ${error.errorCodeName} ${error.message ?: ""}",
                    )
                }
            })
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
            val note = if (device != null) " (vald utgång används inte av denna metod)" else ""
            _state.value = _state.value.copy(debug = "ExoPlayer förbereder $url$note")
        } catch (e: Exception) {
            _state.value = _state.value.copy(playing = false, error = "ExoPlayer startade inte: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun pollMedia() {
        val p = media ?: return
        try {
            _state.value = _state.value.copy(
                positionMs = p.currentPosition,
                positionAt = android.os.SystemClock.elapsedRealtime(),
                debug = "ExoPlayer ${if (p.isPlaying) "spelar" else "spelar inte"} · ${mediaVolumeText()}",
            )
        } catch (_: Exception) {
            return
        }
        if (_state.value.playing) main.postDelayed({ pollMedia() }, 500)
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
