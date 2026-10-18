package se.johronski.subspeaker.audio

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

/**
 * Liten webbserver i telefonen som Chromecasten hämtar testsignalerna från.
 * Stöder Range-förfrågningar, eftersom mediaspelare ofta läser filer i delar.
 */
class SignalServer(port: Int) : NanoHTTPD(port) {

    private val cache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 4
    }

    @Volatile
    var requestCount = 0
        private set

    @Volatile
    var lastRequest: String? = null
        private set

    fun bytesFor(spec: SignalSpec): ByteArray = synchronized(cache) {
        cache.getOrPut(spec.path) { WavEncoder.encodeStereo16(spec.render()) }
    }

    override fun serve(session: IHTTPSession): Response {
        requestCount++
        val range = session.headers["range"]
        lastRequest = "${session.method} ${session.uri}${if (range != null) " ($range)" else ""}"

        val spec = SignalSpec.parse(session.uri)
            ?: return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Okänd signal"))
        val data = bytesFor(spec)
        val total = data.size.toLong()

        if (range != null && range.startsWith("bytes=")) {
            val r = range.removePrefix("bytes=").substringBefore(',').trim()
            val startStr = r.substringBefore('-')
            val endStr = r.substringAfter('-', "")
            var start: Long
            var end: Long
            if (startStr.isEmpty()) {
                val suffix = endStr.toLongOrNull() ?: 0L
                start = (total - suffix).coerceAtLeast(0)
                end = total - 1
            } else {
                start = startStr.toLongOrNull() ?: 0L
                end = endStr.toLongOrNull() ?: (total - 1)
            }
            if (end >= total) end = total - 1
            if (start > end || start >= total) {
                val resp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                resp.addHeader("Content-Range", "bytes */$total")
                return cors(resp)
            }
            val len = end - start + 1
            val stream = ByteArrayInputStream(data, start.toInt(), len.toInt())
            val resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "audio/wav", stream, len)
            resp.addHeader("Content-Range", "bytes $start-$end/$total")
            resp.addHeader("Accept-Ranges", "bytes")
            return cors(resp)
        }

        val resp = newFixedLengthResponse(Response.Status.OK, "audio/wav", ByteArrayInputStream(data), total)
        resp.addHeader("Accept-Ranges", "bytes")
        return cors(resp)
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
        r.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
        return r
    }

    companion object {
        const val PORT = 8089
    }
}
