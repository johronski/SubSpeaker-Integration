package se.johronski.subspeaker.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.random.Random

/** Kodar mono-flyttal till 16-bitars stereo-WAV med samma signal i båda kanalerna. */
object WavEncoder {
    const val HEADER_SIZE = 44

    fun encodeStereo16(samples: FloatArray, sampleRate: Int = SignalGenerator.SAMPLE_RATE, seed: Int = 1): ByteArray {
        val channels = 2
        val dataSize = samples.size * channels * 2
        val buf = ByteBuffer.allocate(HEADER_SIZE + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2)
        buf.putShort((channels * 2).toShort())
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        val rnd = Random(seed)
        for (s in samples) {
            // TPDF-dither, ±1 LSB
            val d = (rnd.nextFloat() - rnd.nextFloat())
            val v = (s * 32767f + d).roundToInt().coerceIn(-32768, 32767).toShort()
            buf.putShort(v)
            buf.putShort(v)
        }
        return buf.array()
    }
}
