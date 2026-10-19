package se.johronski.subspeaker.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Ljus, lugn nordisk palett: varmt papper, kolgrå text, dämpade naturfärger.
val Paper = Color(0xFFF5F3EE)
val CardColor = Color(0xFFFDFCFA)
val CardAlt = Color(0xFFEEEAE3)
val Line = Color(0xFFDDD8CF)
val Ink = Color(0xFF2D3436)
val InkMuted = Color(0xFF5E686B)

/** Fjordblå – huvudfärg för knappar och val. */
val Accent = Color(0xFF3F6E8C)
val AccentSoft = Color(0xFFDCE7EE)

/** Salvia – bra resultat. */
val Good = Color(0xFF4F7F5E)
val GoodSoft = Color(0xFFE1ECE3)

/** Ockra – endast verkliga varningar. */
val Warn = Color(0xFF9A6A1C)
val WarnSoft = Color(0xFFF4E8D2)

/** Lingon – stopp och fel. */
val Bad = Color(0xFFA84B43)
val BadSoft = Color(0xFFF3DEDA)

/** Kurvfärger: dämpade men tydligt olika, läsbara mot ljus bakgrund. */
val SeriesColors = listOf(
    Color(0xFF3F6E8C), // fjord
    Color(0xFFC2734F), // terrakotta
    Color(0xFF5E9270), // salvia
    Color(0xFFB08A2E), // ockra
    Color(0xFF8A6D93), // ljung
    Color(0xFF6C8A9C), // skiffer
    Color(0xFFB9787F), // rosé
    Color(0xFF7E8F4E), // mossa
)

private val colors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Ink,
    secondary = Good,
    onSecondary = Color.White,
    secondaryContainer = AccentSoft,
    onSecondaryContainer = Ink,
    tertiary = Warn,
    error = Bad,
    background = Paper,
    onBackground = Ink,
    surface = CardColor,
    onSurface = Ink,
    surfaceVariant = CardAlt,
    onSurfaceVariant = InkMuted,
    surfaceContainerLow = CardColor,
    surfaceContainer = CardColor,
    surfaceContainerHigh = CardAlt,
    surfaceContainerHighest = CardAlt,
    outline = Line,
    outlineVariant = Line,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = AccentSoft,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun SubSpeakerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}
