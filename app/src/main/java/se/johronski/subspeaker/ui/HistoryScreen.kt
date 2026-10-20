package se.johronski.subspeaker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.AppServices
import se.johronski.subspeaker.SavedResult

private fun colorFor(name: String, hasAfter: Boolean): Pair<Color, Boolean> = when {
    name == "Efter" || name == "Nu" || name == "Kurva" -> Accent to false
    name == "Tillsammans" -> if (hasAfter) Color(0xFF8A9294) to true else Accent to false
    name == "Genelec ensam" -> SeriesColors[1] to false
    name == "Sub ensam" -> SeriesColors[2] to false
    name == "Jämförelse 1" -> SeriesColors[1] to false
    name == "Jämförelse 2" -> SeriesColors[2] to false
    else -> SeriesColors[4] to false
}

private fun explain(name: String): String = when (name) {
    "Efter" -> "allt tillsammans efter ändringarna"
    "Tillsammans" -> "sub och Genelec, vid kollen"
    "Genelec ensam" -> "med MUTE på subben"
    "Sub ensam" -> "med Genelecerna avstängda"
    "Nu" -> "kurvan när den sparades"
    else -> "sparad jämförelse"
}

/** Sparade resultat: lista, detaljer med kurvor och jämförelse av två resultat. */
@Composable
fun HistoryScreen(services: AppServices) {
    val guide by services.guide.state.collectAsState()
    val history = guide.history
    var open by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(listOf<String>()) }
    BackHandler(enabled = open != null) { open = null }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val item = history.firstOrNull { it.id == open }
        if (item != null) {
            TextButton(onClick = { open = null }) { Text("‹ Alla resultat", fontSize = 15.sp) }
            Text(item.date, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Muted(item.source)
            Panel {
                item.lines.forEach { Text(it, fontSize = 15.sp, lineHeight = 21.sp) }
            }
            if (item.curves.isNotEmpty()) {
                Panel {
                    val hasAfter = item.curves.containsKey("Efter")
                    CurveExplorer(
                        services,
                        item.curves.map { (name, db) ->
                            val (c, dashed) = colorFor(name, hasAfter)
                            ExplorerCurve(name, explain(name), db, c, dashed = dashed, thick = !dashed && c == Accent)
                        },
                    )
                }
            }
            OutlinedButton(onClick = {
                services.destructive("Resultatet togs bort") { services.guide.removeResult(item) }
                open = null
            }) { Text("Ta bort", color = Bad) }
            return@Column
        }

        Text("Sparade resultat", fontSize = 28.sp, fontWeight = FontWeight.Medium)
        if (history.isEmpty()) {
            Muted("Inga sparade resultat än. Varje hälsokoll sparas här automatiskt, och i Live-kurva kan du spara kurvan.")
            return@Column
        }
        Muted("Tryck på ett resultat för att se kurvorna. Markera två för att jämföra dem.")

        val chosen = picked.mapNotNull { id -> history.firstOrNull { it.id == id } }.filter { it.curve != null }
        if (chosen.size == 2) {
            Panel {
                Text("Jämförelse", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                CurveExplorer(
                    services,
                    chosen.mapIndexed { i, r ->
                        ExplorerCurve(
                            r.date, r.source + (r.lines.firstOrNull()?.let { " · $it" } ?: ""),
                            r.curve!!, if (i == 0) Accent else SeriesColors[1], thick = i == 0,
                        )
                    },
                )
                TextButton(onClick = { picked = emptyList() }) { Text("Avmarkera") }
            }
        }

        history.forEach { r -> HistoryRow(r, r.id in picked, { open = r.id }) { on ->
            picked = if (on) (picked + r.id).takeLast(2) else picked - r.id
        } }
    }
}

@Composable
private fun HistoryRow(r: SavedResult, selected: Boolean, onOpen: () -> Unit, onSelect: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = onSelect, enabled = r.curve != null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.date, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Muted(r.source + (r.lines.firstOrNull()?.let { " · $it" } ?: ""))
            r.lines.drop(1).filter { !it.startsWith("Fynd") && !it.startsWith("Info") }.take(2).forEach {
                Text(it, fontSize = 14.sp, color = InkMuted)
            }
        }
        Text("›", fontSize = 22.sp, color = InkMuted, modifier = Modifier.padding(horizontal = 8.dp))
    }
}
