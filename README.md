# SubSpeaker Integration

Android-app som hjälper dig att integrera en subwoofer med aktiva högtalare: rätt fas, nivå och delning, mätt med telefonens mikrofon och testsignaler som spelas via Chromecast.

Byggd för Velodyne SPL-1200R + Genelec G Four med Chromecast Audio, men principerna fungerar för de flesta system.

## Status

**v0.7.** Appen har fyra flikar:

- **Guide** – steg för steg: förberedelse med nivåkoll, mikrofonkoll, Genelecens basomkopplare, fas och subnivå. Resultatsidan visar vad du ska ställa in, som en lista att bocka av, och resultatet kan sparas för att jämföras senare.
- **Lyssna** – testsignaler som spelas direkt vid tryck, dippsökning i 1/3–1/24 oktav, markerade dippar och mikrofonnivå.
- **Mät** – fria svepmätningar med namn, t.ex. för att jämföra "normal" med "genelec ensam".
- **Felsökning** – uppspelningsmetod, ljudkälla, servertest och rapport.

Raden överst visar uppspelning, mikrofon och volym och öppnar en panel för att ändra dem. Längst ner finns en fast spelare med Stopp, eller Avbryt under en mätning. Inställningar och mätningar sparas på telefonen, och appen ansluter automatiskt till senast använda Chromecast. Tryck i ett diagram för att lyssna på den frekvensen.

## Installera

1. Öppna [Releases](../../releases) på telefonen och ladda ner senaste `SubSpeaker-x.y.z.apk`.
2. Öppna filen. Första gången frågar Android om webbläsaren eller filhanteraren får installera appar – tillåt det.
3. Play Protect kan varna eftersom appen inte kommer från Play Butik. Välj att installera ändå.

Telefonen och Chromecasten måste vara på samma wifi. Gästnätverk och routrar med klientisolering blockerar ofta Chromecast.

## Bygga

GitHub Actions bygger och testar vid varje push. När `version.txt` får ett nytt versionsnummer skapas automatiskt en release med APK.

APK:n signeras med en fast nyckel som ligger i repot (`app/subspeaker.keystore`), så att nya versioner kan installeras ovanpå den gamla. Nyckeln är offentlig, vilket räcker för en hobbyapp men inte skyddar mot att någon annan signerar en version.

Lokalt: `./gradlew assembleRelease` (kräver Android SDK och JDK 17).

## Design

Se [docs/design.md](docs/design.md).
