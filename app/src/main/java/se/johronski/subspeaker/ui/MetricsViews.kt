@file:OptIn(ExperimentalLayoutApi::class)

package se.johronski.subspeaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.johronski.subspeaker.AppServices
import se.johronski.subspeaker.audio.BassMetrics
import se.johronski.subspeaker.audio.Diagnosis
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

private val SV = Locale.forLanguageTag("sv")

private fun f1(v: Double) = String.format(SV, "%.1f", v).replace('-', '−')
private fun s1(v: Double) = String.format(SV, "%+.1f", v).replace('-', '−')
private fun hz(f: Double) = if (f < 100) String.format(SV, "%.0f Hz", f) else "${(f / 5).toInt() * 5} Hz"
private fun oct(w: Double) = String.format(SV, "%.2f okt", w)

/** Lutningen i ord: negativ lutning = mer djupbas än målet. */
fun slopeWords(k: Double): String = when {
    abs(k) < 0.5 -> "i nivå med målet"
    k < 0 -> "mer djupbas än målet"
    else -> "mindre djupbas än målet"
}

/** En rad med de viktigaste måtten, för listor. */
fun compactMetrics(m: BassMetrics.Result): String =
    "lutning ${s1(m.slopeDbPerOct)} dB/okt · NBD ${f1(m.nbdDb)} · hål ${s1(m.dip.db)} dB vid ${hz(m.dip.freq)}"

/** MSV i text: dB² och roten ur i dB. */
fun msvText(msv: Double): String = "${f1(msv)} dB² (≈ ${f1(sqrt(msv))} dB)"

/** Alla mått mot målkurvan som en liten tabell. */
@Composable
fun MetricsTable(m: BassMetrics.Result) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetricRow("Ojämnhet", "${f1(m.sdDb)} dB", Diagnosis.gaugeWord(m.sdDb).lowercase(SV))
        MetricRow("Lutning mot målet", "${s1(m.slopeDbPerOct)} dB/okt", slopeWords(m.slopeDbPerOct))
        MetricRow("Smala avvikelser (NBD)", "${f1(m.nbdDb)} dB", null)
        MetricRow("Största topp", "${s1(m.peak.db)} dB", "vid ${hz(m.peak.freq)}, ${oct(m.peak.widthOct)}")
        MetricRow("Största hål", "${s1(m.dip.db)} dB", "vid ${hz(m.dip.freq)}, ${oct(m.dip.widthOct)}")
    }
}

@Composable
private fun MetricRow(name: String, value: String, note: String?) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp)
            note?.let { Text(it, fontSize = 13.sp, color = InkMuted) }
        }
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Formula(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = Ink)
}

@Composable
private fun Body(text: String) {
    Text(text, fontSize = 15.sp, lineHeight = 21.sp)
}

/** Infosida: om appen, målkurvan och måtten. */
@Composable
fun AboutScreen(services: AppServices) {
    val context = LocalContext.current
    val guide by services.guide.state.collectAsState()
    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: Exception) {
        "?"
    }
    val target = guide.targetBalance
    val shelf = BassMetrics.shelfFor(target)

    Panel("SubSpeaker") {
        Body(
            "Hjälper dig att få sub och högtalare att låta som en enhet: rätt fas, nivå och delning. " +
                "Appen spelar mätljud via Chromecast eller telefonen, mäter med mikrofonen och visar vad som ska ändras.",
        )
        Muted("Version $version")
    }

    Panel("Målkurva") {
        Body(
            "Måtten jämför basen med en målkurva i stället för med en rak linje. Forskning från Harman visar att lyssnare " +
                "i snitt föredrar mer bas än en rak kurva i rummet, men att smaken varierar mycket mellan personer.",
        )
        Formula("T(f) = G / (1 + (f/105)²)")
        Body(
            "G är hyllans höjd. Den räknas fram ur målbalansen, alltså hur mycket starkare 25–63 Hz ska vara än 100–250 Hz. " +
                String.format(SV, "Nu: målbalans %+.0f dB, vilket ger G = %.1f dB.", target, shelf).replace('-', '−'),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2.0, 3.0, 4.0, 5.0, 6.0).forEach { t ->
                FilterChip(
                    selected = abs(target - t) < 0.01,
                    onClick = { services.guide.setTargetBalance(t) },
                    label = { Text(String.format(SV, "%+.0f dB", t)) },
                )
            }
        }
        Muted("+3 dB motsvarar ungefär Harmans genomsnitt. Välj mer om du gillar mycket bas, mindre om du vill ha det stramare. Målbalansen används också när appen föreslår subnivå.")
    }

    Panel("Måtten") {
        Muted("Räknas på 25–200 Hz, på kurvan utjämnad till 1/6 oktav. Felkurvan är skillnaden mot målkurvan, flyttad så att medelvärdet blir 0.")
        Formula("e = L − T − medel(L − T)")

        Label("Ojämnhet")
        Body("Hur mycket kurvan sprider sig runt målkurvan. Fångar både toppar, hål och fel lutning. Används i hälsokollen och i Jämför platser.")
        Formula("SD = standardavvikelsen av e")
        Muted("Under 2,5 dB: jämnt. Under 3,8 dB: OK. Över det: ojämnt. Skillnader på några tiondelar kan vara slump.")

        Label("Lutning mot målet")
        Body("Om balansen mellan djupbas och övre bas avviker från målet. Minus betyder mer djupbas än målet.")
        Formula("k = lutningen för en rät linje genom e mot log₂ f (dB/okt)")

        Label("Smala avvikelser (NBD)")
        Body("Fångar smala toppar och hål, som syns dåligt i ojämnheten. Varje halvoktavband jämförs med sitt eget medelvärde.")
        Formula("NBD = medel över banden av medel |L − bandets medel|")

        Label("Största topp och hål")
        Body("Där kurvan ligger längst över eller under målet, med frekvens och bredd (oktaver där avvikelsen är minst hälften så stor). Toppar går att sänka med EQ. Hål går sällan att fylla – de beror på rummet eller på att sub och högtalare tar ut varandra.")

        Label("Skillnad mellan platser (MSV)")
        Body("Visas när flera platser jämförs. Varje kurva flyttas till samma medelnivå, och sedan mäts hur olika de är. Lågt värde betyder att en gemensam EQ kan hjälpa alla platser.")
        Formula("MSV = medel över 30–150 Hz av variansen mellan platserna (dB²)")
    }

    Panel("Varför de här måtten") {
        Body(
            "Det gamla måttet mätte spridningen runt en lutande linje som anpassades efter varje mätning. " +
                "Då räknades en jämn lutning som jämn, och en plats med 10 dB för mycket djupbas kunde se bäst ut.",
        )
        Body(
            "Ojämnhet och lutning följer upplägget i Harmans modell för hörlurar, där spridningen och lutningen för felkurvan " +
                "mot målet förutsade lyssnarnas betyg väl. NBD kommer från Olives modell för högtalare. MSV kommer från Weltis arbete om flera subbar.",
        )
        Body(
            "Ingen av modellerna är prövad på just bas i små rum. Vikterna i modellerna används inte, bara måtten. " +
                "Lita mer på stora skillnader och på det du hör än på små skillnader i talen.",
        )
    }

    Panel("Källor") {
        listOf(
            "Olive, Welti & McMullin (2013). Listener preferences for in-room loudspeaker and headphone target responses. AES 135, paper 8994.",
            "Olive, Jackson, Devantier & Hunt (2009). The subjective and objective evaluation of room correction products. AES 127, paper 7960.",
            "Olive (2004). A multiple regression model for predicting loudspeaker preference using objective measurements, part II. AES 117, paper 6190.",
            "Olive, Welti & Khonsaripour (2018). A statistical model that predicts listeners' preference ratings of around-ear and on-ear headphones. AES 144, paper 9919.",
            "Welti & Devantier (2006). Low-frequency optimization using multiple subwoofers. JAES 54(5), 347–364.",
            "Bücklein (1981). The audibility of frequency response irregularities. JAES 29(3), 126–131.",
            "Fazenda, Stephenson & Goldberg (2015). Perceptual thresholds for the effects of room modes as a function of modal decay. JASA 137(3), 1088–1098.",
        ).forEach { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = InkMuted) }
    }
}
