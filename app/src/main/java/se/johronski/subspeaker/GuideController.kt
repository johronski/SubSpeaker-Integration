package se.johronski.subspeaker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Ett sparat slutresultat, för att kunna jämföra med senare inställningar. */
data class SavedResult(
    val date: String,
    val lines: List<String>,
    /** Kurvor med namn, t.ex. "Tillsammans", "Genelec ensam", "Sub ensam", "Efter". */
    val curves: Map<String, DoubleArray> = emptyMap(),
    /** "Hälsokoll" eller "Live-kurva". */
    val source: String = "Hälsokoll",
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    /** Huvudkurvan: efter ändringarna om det finns, annars allt tillsammans. */
    val curve: DoubleArray?
        get() = curves["Efter"] ?: curves["Tillsammans"] ?: curves.values.firstOrNull()
}

/**
 * Sparade resultat och målbalansen. (Namnet är kvar från den gamla stegvisa guiden,
 * som är borttagen; lagringsnyckeln "guide" behålls så att sparade resultat finns kvar.)
 */
class GuideController(private val store: Store) {
    data class State(
        /** Önskad basbalans (dB): sub 25–63 Hz mot Genelec 100–250 Hz. */
        val targetBalance: Double = 3.0,
        val history: List<SavedResult> = emptyList(),
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state

    private fun set(f: (State) -> State) {
        _state.value = f(_state.value)
        save()
    }

    fun setTargetBalance(t: Double) = set { it.copy(targetBalance = t) }

    fun saveResult(r: SavedResult) = set { it.copy(history = (listOf(r) + it.history).take(30)) }

    /** Lägger till eller uppdaterar ett resultat med samma id (överst om det är nytt). */
    fun upsertResult(r: SavedResult) = set { st ->
        val i = st.history.indexOfFirst { it.id == r.id }
        st.copy(history = if (i >= 0) st.history.toMutableList().also { it[i] = r } else (listOf(r) + st.history).take(30))
    }

    fun removeResult(r: SavedResult) = set { it.copy(history = it.history.filterNot { h -> h.id == r.id }) }

    fun restore(s: State) = set { s }

    private fun save() {
        val s = _state.value
        val hist = JSONArray()
        s.history.forEach { r ->
            val cj = JSONObject()
            r.curves.forEach { (k, v) -> cj.put(k, Store.doubles(v)) }
            hist.put(
                JSONObject().put("id", r.id).put("date", r.date).put("source", r.source)
                    .put("lines", Store.strings(r.lines)).put("curves", cj),
            )
        }
        store.put(
            KEY,
            JSONObject()
                .put("target", s.targetBalance)
                .put("history", hist)
                .toString(),
        )
    }

    private fun load(): State {
        val raw = store.get(KEY) ?: return State()
        return try {
            val j = JSONObject(raw)
            val hist = j.optJSONArray("history") ?: JSONArray()
            State(
                targetBalance = j.optDouble("target", 3.0),
                history = (0 until hist.length()).map { i ->
                    val o = hist.getJSONObject(i)
                    val cj = o.optJSONObject("curves")
                    val curves = if (cj != null) {
                        cj.keys().asSequence().mapNotNull { k -> Store.curve(cj.optJSONArray(k))?.let { k to it } }.toMap()
                    } else {
                        // Äldre format: en enda kurva.
                        (if (o.isNull("curve")) null else Store.curve(o.optJSONArray("curve")))?.let { mapOf("Kurva" to it) } ?: emptyMap()
                    }
                    SavedResult(
                        date = o.optString("date"),
                        lines = Store.strings(o.optJSONArray("lines")),
                        curves = curves,
                        source = o.optString("source").ifBlank { "Hälsokoll" },
                        id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    )
                },
            )
        } catch (e: Exception) {
            State()
        }
    }

    private companion object {
        const val KEY = "guide"
    }
}
