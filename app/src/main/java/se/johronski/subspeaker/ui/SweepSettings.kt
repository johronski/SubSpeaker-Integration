package se.johronski.subspeaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

/** Startfördröjning och antal svep per mätning. */
@Composable
fun SweepSettings(
    delaySeconds: Int,
    repeats: Int,
    enabled: Boolean,
    onDelay: (Int) -> Unit,
    onRepeats: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Vänta före start:")
            listOf(0, 5, 10, 15).forEach { s ->
                FilterChip(selected = delaySeconds == s, onClick = { onDelay(s) }, enabled = enabled, label = { Text("$s s") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Svep per mätning:")
            listOf(1, 2, 3).forEach { n ->
                FilterChip(selected = repeats == n, onClick = { onRepeats(n) }, enabled = enabled, label = { Text("$n") })
            }
        }
        Muted("Telefonen vibrerar när mätningen är klar. Med flera svep kontrollerar appen att de stämmer överens.")
    }
}
