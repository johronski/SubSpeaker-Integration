package se.johronski.subspeaker.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Mäter mikrofonnivå i ett tredjedelsoktavsband runt vald frekvens, samt bredbandsnivå.
 * Ger en ny avläsning var 0,5 s.
 */
class MicMeter(private val context: Context) {

    enum class Source(val id: Int, val label: String) {
        UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, "Obearbetad"),
        VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "Röstigenkänning"),
        MIC(MediaRecorder.AudioSource.MIC, "Standard"),
    }

    data class Reading(
        val bandDb: Double,
        val broadDb: Double,
        val spreadDb: Double,
        val frequency: Double,
    )

    data class State(
        val running: Boolean = false,
        val reading: Reading? = null,
        val source: Source? = null,
        val routedDevice: String? = null,
        val effects: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile
    private var frequency = 90.0

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val unprocessedSupported: Boolean
        get() = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    private val audioManager: AudioManager
        get() = context.getSystemService(AudioManager::class.java)

    fun inputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource && it.type !in HIDDEN_INPUTS }
            .toList()

    fun setFrequency(f: Double) {
        frequency = f.coerceIn(10.0, 2000.0)
    }

    @SuppressLint("MissingPermission")
    fun start(source: Source, device: AudioDeviceInfo?) {
        stop()
        val sr = SignalGenerator.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            _state.value = State(error = "Telefonen stöder inte inspelning i 48 kHz")
            return
        }
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source.id)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuf, sr)) // ≈ 0,5 s
                .build()
        } catch (e: Exception) {
            _state.value = State(error = "Kunde inte öppna mikrofonen: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            _state.value = State(error = "Mikrofonen kunde inte initieras med källan ${source.label}")
            return
        }
        if (device != null) rec.setPreferredDevice(device)

        val effects = mutableListOf<AudioEffect>()
        val effectNotes = mutableListOf<String>()
        fun disable(name: String, available: Boolean, create: () -> AudioEffect?) {
            if (!available) return
            try {
                create()?.let {
                    it.setEnabled(false)
                    effects += it
                    effectNotes += "$name av"
                }
            } catch (e: Exception) {
                effectNotes += "$name: ${e.message}"
            }
        }
        disable("AGC", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(rec.audioSessionId) }
        disable("Brusreducering", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(rec.audioSessionId) }
        disable("Ekosläckning", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(rec.audioSessionId) }

        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            effects.forEach { it.release() }
            _state.value = State(error = "Inspelningen startade inte: ${e.message}")
            return
        }

        running = true
        _state.value = State(
            running = true,
            source = source,
            effects = if (effectNotes.isEmpty()) "Inga effekter aktiva" else effectNotes.joinToString(", "),
        )

        thread = Thread({
            val block = sr / 10
            val buf = ShortArray(block)
            var filterFreq = frequency
            var bp = BandPass(filterFreq, sr)
            var bandSum = 0.0
            var broadSum = 0.0
            var count = 0
            var blocks = 0
            var warmup = 3 // släng första 0,3 s
            val history = ArrayDeque<Double>()
            while (running) {
                val n = rec.read(buf, 0, block)
                if (n <= 0) continue
                if (frequency != filterFreq) {
                    filterFreq = frequency
                    bp = BandPass(filterFreq, sr)
                    bandSum = 0.0; broadSum = 0.0; count = 0; blocks = 0; warmup = 3
                    history.clear()
                }
                for (i in 0 until n) {
                    val x = buf[i] / 32768.0
                    val y = bp.process(x)
                    bandSum += y * y
                    broadSum += x * x
                }
                count += n
                if (warmup > 0) {
                    warmup--
                    bandSum = 0.0; broadSum = 0.0; count = 0
                    continue
                }
                blocks++
                if (blocks >= 5) {
                    val band = Levels.rmsToDbfs(sqrt(bandSum / count))
                    val broad = Levels.rmsToDbfs(sqrt(broadSum / count))
                    history.addLast(band)
                    while (history.size > 10) history.removeFirst()
                    val spread = (history.maxOrNull() ?: band) - (history.minOrNull() ?: band)
                    val routed = rec.routedDevice?.let { describe(it) }
                    _state.value = _state.value.copy(
                        reading = Reading(band, broad, spread, filterFreq),
                        routedDevice = routed,
                    )
                    bandSum = 0.0; broadSum = 0.0; count = 0; blocks = 0
                }
            }
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            effects.forEach { it.release() }
        }, "MicMeter").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        _state.value = _state.value.copy(running = false)
    }

    companion object {
        /** Ingångar som inte är mikrofoner: telefoni, intern ljudström, ekoreferens. */
        private val HIDDEN_INPUTS = setOf(AudioDeviceInfo.TYPE_TELEPHONY, 25, 28)

        fun describe(d: AudioDeviceInfo): String {
            val type = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Inbyggd mikrofon"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset (3,5 mm)"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB-enhet"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB-tillbehör"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                AudioDeviceInfo.TYPE_TELEPHONY -> "Telefoni"
                else -> "Typ ${d.type}"
            }
            val name = d.productName?.toString().orEmpty()
            val addr = if (android.os.Build.VERSION.SDK_INT >= 28 && !d.address.isNullOrBlank()) " [${d.address}]" else ""
            return "$type – $name$addr"
        }
    }
}
