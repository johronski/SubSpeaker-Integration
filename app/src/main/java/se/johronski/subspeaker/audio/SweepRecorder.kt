package se.johronski.subspeaker.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.math.max

/** Spelar in mikrofonen och samplar ner till 3 kHz i realtid, för svepanalys. */
class SweepRecorder {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var buffer = FloatArray(SweepAnalyzer.FS * 45)
    private var count = 0

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var routedDevice: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(source: MicMeter.Source, device: AudioDeviceInfo?) {
        stop()
        error = null
        synchronized(lock) { count = 0 }
        val sr = SignalGenerator.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("Telefonen stöder inte inspelning i 48 kHz")
        val rec = AudioRecord.Builder()
            .setAudioSource(source.id)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf, sr * 2))
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Mikrofonen kunde inte initieras med källan ${source.label}")
        }
        if (device != null) rec.setPreferredDevice(device)
        val effects = mutableListOf<AudioEffect>()
        fun disable(available: Boolean, create: () -> AudioEffect?) {
            if (!available) return
            try { create()?.let { it.setEnabled(false); effects += it } } catch (_: Exception) {}
        }
        disable(AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(rec.audioSessionId) }
        disable(NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(rec.audioSessionId) }
        disable(AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(rec.audioSessionId) }
        rec.startRecording()
        running = true
        thread = Thread({
            val dec = Decimator(SweepAnalyzer.DECIMATION)
            val buf = ShortArray(sr / 10)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    for (i in 0 until n) {
                        val y = dec.push(buf[i] / 32768.0) ?: continue
                        synchronized(lock) {
                            if (count >= buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                            buffer[count++] = y.toFloat()
                        }
                    }
                    if (routedDevice == null) routedDevice = rec.routedDevice?.let { MicMeter.describe(it) }
                }
            } catch (e: Exception) {
                error = "Inspelningsfel: ${e.message}"
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
                effects.forEach { it.release() }
            }
        }, "SweepRecorder").also { it.start() }
    }

    private val lock = Any()

    /** Antal nedsamplade sampel hittills. */
    val size: Int get() = synchronized(lock) { count }

    /** Kopia av inspelningen hittills, medan den pågår. */
    fun snapshot(): FloatArray = synchronized(lock) { buffer.copyOf(count) }

    /** Stoppar och returnerar den nedsamplade inspelningen. */
    fun stop(): FloatArray {
        running = false
        thread?.join(2000)
        thread = null
        return synchronized(lock) { buffer.copyOf(count) }
    }
}
