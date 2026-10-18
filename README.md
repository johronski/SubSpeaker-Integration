# SubSpeaker Integration

Android-app som hjälper dig att integrera en subwoofer med aktiva högtalare: rätt fas, nivå och delning, mätt med telefonens mikrofon och testsignaler som spelas via Chromecast.

Byggd för Velodyne SPL-1200R + Genelec G Four med Chromecast Audio, men principerna fungerar för de flesta system.

## Status

**v0.1 – diagnos.** Testar att grunderna fungerar på din utrustning innan själva integrationsflödet byggs:

- Spelar testsignaler via Chromecast, som styrs direkt över nätverket på samma sätt som i RadioCast (telefonen kör en liten ljudserver på wifi), eller direkt i telefonen, t.ex. via kabel från hörlursutgången.
- Mäter nivå i ett tredjedelsoktavsband med valfri mikrofon och ljudkälla.
- Sparar mätningar med etiketter och delar en diagnosrapport.

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
