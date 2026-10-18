package se.johronski.subspeaker.cast

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CastController(context: Context) {

    data class State(
        val available: Boolean = false,
        val unavailableReason: String? = null,
        val connected: Boolean = false,
        val deviceName: String? = null,
        val deviceModel: String? = null,
        val volume: Double? = null,
        val playerState: String = "–",
        val title: String? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val latencyMs: Long? = null,
        val lastError: String? = null,
        val log: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val castContext: CastContext? = try {
        @Suppress("DEPRECATION")
        CastContext.getSharedInstance(context.applicationContext)
    } catch (e: Exception) {
        _state.value = State(unavailableReason = "Chromecast-stöd saknas: ${e.message}")
        null
    }

    private var session: CastSession? = null
    private var client: RemoteMediaClient? = null
    private var loadStartedAt = 0L
    private var waitingForPlay = false

    init {
        if (castContext != null) _state.value = _state.value.copy(available = true)
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = updateStatus()
    }

    private val progressListener = RemoteMediaClient.ProgressListener { progressMs, durationMs ->
        _state.value = _state.value.copy(positionMs = progressMs, durationMs = durationMs)
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, sessionId: String) = attach(s, "Ansluten")
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = attach(s, "Återansluten")
        override fun onSessionEnded(s: CastSession, error: Int) = detach("Frånkopplad (kod $error)")
        override fun onSessionStartFailed(s: CastSession, error: Int) = log("Anslutning misslyckades (kod $error)")
        override fun onSessionResumeFailed(s: CastSession, error: Int) = log("Återanslutning misslyckades (kod $error)")
        override fun onSessionSuspended(s: CastSession, reason: Int) = log("Anslutningen pausad (kod $reason)")
        override fun onSessionStarting(s: CastSession) = log("Ansluter …")
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, sessionId: String) {}
    }

    fun onResume() {
        val ctx = castContext ?: return
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        ctx.sessionManager.currentCastSession?.let { if (it.isConnected) attach(it, "Ansluten") }
    }

    fun onPause() {
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
    }

    private fun attach(s: CastSession, msg: String) {
        if (session === s && client != null) return
        client?.unregisterCallback(mediaCallback)
        client?.removeProgressListener(progressListener)
        session = s
        client = s.remoteMediaClient?.also {
            it.registerCallback(mediaCallback)
            it.addProgressListener(progressListener, 500)
        }
        val dev = s.castDevice
        _state.value = _state.value.copy(
            connected = true,
            deviceName = dev?.friendlyName,
            deviceModel = dev?.modelName,
            volume = safeVolume(s),
        )
        log("$msg till ${dev?.friendlyName ?: "okänd enhet"} (${dev?.modelName ?: "okänd modell"})")
        updateStatus()
    }

    private fun detach(msg: String) {
        client?.unregisterCallback(mediaCallback)
        client?.removeProgressListener(progressListener)
        client = null
        session = null
        _state.value = _state.value.copy(connected = false, deviceName = null, deviceModel = null, volume = null, playerState = "–")
        log(msg)
    }

    fun play(url: String, title: String, durationMs: Long) {
        val c = client
        if (c == null) {
            log("Inte ansluten till någon Chromecast")
            return
        }
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            putString(MediaMetadata.KEY_ARTIST, "SubSpeaker")
            putString(MediaMetadata.KEY_ALBUM_TITLE, "Testsignaler")
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("audio/wav")
            .setMetadata(meta)
            .setStreamDuration(durationMs)
            .build()
        val req = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .build()
        loadStartedAt = SystemClock.elapsedRealtime()
        waitingForPlay = true
        _state.value = _state.value.copy(title = title, latencyMs = null, lastError = null, positionMs = 0, durationMs = durationMs)
        log("Laddar: $title")
        c.load(req).setResultCallback { result ->
            if (!result.status.isSuccess) {
                val err = "Laddning misslyckades: kod ${result.status.statusCode} ${result.status.statusMessage ?: ""}"
                _state.value = _state.value.copy(lastError = err)
                waitingForPlay = false
                log(err)
            }
        }
    }

    fun stop() {
        client?.stop()
        waitingForPlay = false
        log("Stopp")
    }

    fun setVolume(v: Double) {
        val s = session ?: return
        try {
            s.volume = v.coerceIn(0.0, 1.0)
            _state.value = _state.value.copy(volume = v)
        } catch (e: Exception) {
            log("Kunde inte ändra volym: ${e.message}")
        }
    }

    fun refreshVolume() {
        val s = session ?: return
        _state.value = _state.value.copy(volume = safeVolume(s))
    }

    private fun safeVolume(s: CastSession): Double? = try { s.volume } catch (e: Exception) { null }

    private fun updateStatus() {
        val c = client ?: return
        val ms = c.mediaStatus
        val ps = ms?.playerState ?: MediaStatus.PLAYER_STATE_UNKNOWN
        val name = when (ps) {
            MediaStatus.PLAYER_STATE_IDLE -> "Vilar"
            MediaStatus.PLAYER_STATE_PLAYING -> "Spelar"
            MediaStatus.PLAYER_STATE_PAUSED -> "Pausad"
            MediaStatus.PLAYER_STATE_BUFFERING -> "Buffrar"
            MediaStatus.PLAYER_STATE_UNKNOWN -> "Okänt"
            else -> "Läge $ps"
        }
        var latency = _state.value.latencyMs
        if (ps == MediaStatus.PLAYER_STATE_PLAYING && waitingForPlay) {
            latency = SystemClock.elapsedRealtime() - loadStartedAt
            waitingForPlay = false
            log("Spelar efter $latency ms")
        }
        var err = _state.value.lastError
        if (ps == MediaStatus.PLAYER_STATE_IDLE && ms?.idleReason == MediaStatus.IDLE_REASON_ERROR) {
            err = "Chromecasten rapporterade fel vid uppspelning"
            if (waitingForPlay) log(err)
            waitingForPlay = false
        }
        _state.value = _state.value.copy(playerState = name, latencyMs = latency, lastError = err, volume = session?.let { safeVolume(it) })
    }

    private fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        _state.value = _state.value.copy(log = (_state.value.log + "$t $msg").takeLast(40))
    }
}
