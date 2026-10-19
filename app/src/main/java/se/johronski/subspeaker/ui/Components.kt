@file:OptIn(ExperimentalLayoutApi::class)

package se.johronski.subspeaker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.audio.Decision
import java.util.Locale

/** Ljust kort med tunn kant, utan skugga. */
@Composable
fun Panel(
    title: String? = null,
    modifier: Modifier = Modifier,
    container: Color = CardColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = Ink),
        border = BorderStroke(1.dp, Line),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Ink)
            content()
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 15.sp, lineHeight = 21.sp, color = InkMuted, modifier = modifier)
}

@Composable
fun Label(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
}

/** Ruta med färgad bakgrund för besked: bra, varning eller fel. */
@Composable
fun Notice(text: String, tone: Tone = Tone.INFO) {
    val (bg, fg) = when (tone) {
        Tone.GOOD -> GoodSoft to Good
        Tone.WARN -> WarnSoft to Warn
        Tone.BAD -> BadSoft to Bad
        Tone.INFO -> AccentSoft to Accent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Spacer(Modifier.size(8.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 15.sp, lineHeight = 21.sp, color = Ink)
    }
}

enum class Tone { GOOD, WARN, BAD, INFO }

@Composable
fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, fontSize = 16.sp)
    }
}

/** Liten etikett som anger hur säkert ett utslag är. */
@Composable
fun ConfidenceBadge(c: Decision.Confidence) {
    val (text, bg, fg) = when (c) {
        Decision.Confidence.SURE -> Triple("Säker", GoodSoft, Good)
        Decision.Confidence.LIKELY -> Triple("Trolig", AccentSoft, Accent)
        Decision.Confidence.UNSURE -> Triple("Oklar", CardAlt, InkMuted)
    }
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun VerdictText(v: Decision.Verdict, name: (String) -> String = { it }) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Jämnast: ${name(v.best.key)}", fontSize = 17.sp, fontWeight = FontWeight.Medium)
        ConfidenceBadge(v.confidence)
    }
    Muted(
        String.format(
            Locale.ROOT,
            "%.1f dB jämnare än %s.",
            v.marginDb, name(v.runnerUp.key),
        ) + if (v.confidence == Decision.Confidence.UNSURE) {
            " Skillnaden är mindre än mätningens osäkerhet – välj vilket som helst av de bästa, eller mät på fler platser."
        } else {
            ""
        },
    )
}

/** Rad i diagrammets förklaring. Tryck för att markera kurvan. */
@Composable
fun LegendRow(i: Int, text: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) CardAlt else Color.Transparent)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Spacer(Modifier.size(12.dp).clip(CircleShape).background(SeriesColors[i % SeriesColors.size]))
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NumberedStep(id: String, text: String) {
    Row {
        Text(id, fontWeight = FontWeight.Medium, color = Accent, modifier = Modifier.width(26.dp))
        Text(text, fontSize = 15.sp, lineHeight = 21.sp)
    }
}
