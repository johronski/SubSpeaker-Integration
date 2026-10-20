package se.johronski.subspeaker

import android.content.Context
import android.media.AudioDeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import se.johronski.subspeaker.audio.LocalPlayer
import se.johronski.subspeaker.audio.MicMeter

/** Sparar appens tillstånd på telefonen, så att inget försvinner om Android stänger appen. */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("subspeaker", Context.MODE_PRIVATE)

    /** Större data (mätningar med impulssvar) sparas som fil i stället för i inställningarna. */
    fun writeFile(name: String, text: String) {
        val f = java.io.File(context.filesDir, name)
        val tmp = java.io.File(context.filesDir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    fun readFile(name: String): String? =
        java.io.File(context.filesDir, name).takeIf { it.exists() }?.readText()

    fun get(key: String): String? = prefs.getString(key, null)

    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        fun doubles(a: DoubleArray): JSONArray = JSONArray().also { j -> a.forEach { j.put(it) } }
        fun doubles(j: JSONArray?): DoubleArray? = j?.let { DoubleArray(it.length()) { i -> it.getDouble(i) } }
        fun strings(l: Collection<String>): JSONArray = JSONArray().also { j -> l.forEach { j.put(it) } }
        fun strings(j: JSONArray?): List<String> = j?.let { List(it.length()) { i -> it.getString(i) } } ?: emptyList()
        fun ints(l: Collection<Int>): JSONArray = JSONArray().also { j -> l.forEach { j.put(it) } }
        fun ints(j: JSONArray?): List<Int> = j?.let { List(it.length()) { i -> it.getInt(i) } } ?: emptyList()

        /** Float32, little endian, Base64 – kompakt och exakt. */
        fun floatsBase64(a: FloatArray): String {
            val bb = java.nio.ByteBuffer.allocate(a.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            a.forEach { bb.putFloat(it) }
            return java.util.Base64.getEncoder().encodeToString(bb.array())
        }

        fun floatsBase64(s: String): FloatArray {
            val bb = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(s)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bb.remaining() / 4) { bb.getFloat() }
        }

        /** Ett id för en ljudenhet som håller mellan omstarter (Androids eget id gör inte det). */
        fun deviceKey(d: AudioDeviceInfo): String {
            val addr = if (android.os.Build.VERSION.SDK_INT >= 28) d.address.orEmpty() else ""
            return "${d.type}|${d.productName}|$addr"
        }
    }
}

enum class Output(val label: String) { CAST("Chromecast"), PHONE("Telefonen") }

/** Användarens val av uppspelning och mikrofon. */
data class Settings(
    val output: Output = Output.CAST,
    /** Senast anslutna Chromecast; appen ansluter automatiskt när den hittas. */
    val castName: String? = null,
    val method: LocalPlayer.Method = LocalPlayer.Method.DIRECT,
    /** null = välj automatiskt. */
    val source: MicMeter.Source? = null,
    /** null = telefonen väljer mikrofon. */
    val micKey: String? = null,
    /** null = telefonen väljer utgång. */
    val outKey: String? = null,
    val localGain: Float = 0.3f,
    val allowHighVolume: Boolean = false,
    /** Läs upp instruktioner i hälsokollen. Av som standard. */
    val voice: Boolean = false,
)

class SettingsStore(private val store: Store) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state
    val value: Settings get() = _state.value

    fun update(f: (Settings) -> Settings) {
        _state.value = f(_state.value)
        save(_state.value)
    }

    private fun load(): Settings {
        val s = store.get(KEY) ?: return Settings()
        return try {
            val j = JSONObject(s)
            Settings(
                output = enumOr(j.optString("output"), Output.CAST),
                castName = j.optString("castName").ifBlank { null },
                method = enumOr(j.optString("method"), LocalPlayer.Method.DIRECT),
                source = j.optString("source").ifBlank { null }?.let { n -> MicMeter.Source.entries.firstOrNull { it.name == n } },
                micKey = j.optString("micKey").ifBlank { null },
                outKey = j.optString("outKey").ifBlank { null },
                localGain = j.optDouble("localGain", 0.3).toFloat(),
                allowHighVolume = j.optBoolean("allowHighVolume", false),
                // Ny nyckel: rösten var på som standard i 0.8.0 och ska nu vara av tills användaren slår på den.
                voice = j.optBoolean("voiceOn", false),
            )
        } catch (e: Exception) {
            Settings()
        }
    }

    private fun save(s: Settings) {
        val j = JSONObject()
            .put("output", s.output.name)
            .put("castName", s.castName ?: "")
            .put("method", s.method.name)
            .put("source", s.source?.name ?: "")
            .put("micKey", s.micKey ?: "")
            .put("outKey", s.outKey ?: "")
            .put("localGain", s.localGain.toDouble())
            .put("allowHighVolume", s.allowHighVolume)
            .put("voiceOn", s.voice)
        store.put(KEY, j.toString())
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY = "settings"
    }
}
