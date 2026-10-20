# SubSpeaker Integration

Android-app som hjälper dig att integrera en subwoofer med aktiva högtalare: rätt fas, nivå och delning, mätt med telefonens mikrofon och testsignaler som spelas via Chromecast.

Byggd för Velodyne SPL-1200R + Genelec G Four med Chromecast Audio, men principerna fungerar för de flesta system.

## Status

**v0.8 – hälsokoll och stämapparat.**

1. **Kolla:** tryck *Kolla nu* och rör telefonen långsamt runt huvudet. Appen spelar ett periodiskt rosa brus och mäter tre gånger: allt tillsammans, Genelec ensam (MUTE på subben) och, om du vill, sub ensam (Genelecerna avstängda).
2. **Diagnos:** fynden visas i ord, sorterade efter påverkan, med en förenklad baskurva. Appen skiljer på utsläckning mellan sub och Genelec, fel nivå, fel lågpass och hål som kommer från rummet.
3. **Åtgärda:** varje fynd öppnar en stämapparat som visar live hur jämn basen är. För fasen säger appen vilket läge du ska trycka härnäst, mäter alla fyra och föreslår det bästa. Lågpass och nivå justeras med direkt besked som syns från rummet. Röstguiden kan läsa upp instruktionerna.
4. **Klart:** en efterkontroll visar före och efter, och resultatet kan sparas.

I stämapparaten visar **Visa kurva** den levande frekvenskurvan (exponentiellt medelvärde: Snabb 1,5 s, Normal 4 s, Lugn 12 s). I fasguidningen sparas kurvan för varje fasläge automatiskt.

Menyn uppe till höger har uppspelning, mikrofon och volym, röstguiden, samt verktygen Live-kurva (mätljudet spelar och kurvan visas hela tiden, med frys och upp till tre jämförelser), Lyssna på toner, Fri mätning och Felsökning och export.

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
