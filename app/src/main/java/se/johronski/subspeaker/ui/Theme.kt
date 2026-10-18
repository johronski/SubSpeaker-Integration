package se.johronski.subspeaker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF2A78D6)
val Good = Color(0xFF1BAF7A)
val Warn = Color(0xFFEDA100)
val Bad = Color(0xFFE34948)

private val colors = darkColorScheme(
    primary = Accent,
    secondary = Good,
    tertiary = Warn,
    error = Bad,
    background = Color(0xFF121212),
    surface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFF262626),
    onPrimary = Color.White,
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
)

@Composable
fun SubSpeakerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
