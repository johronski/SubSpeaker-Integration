# Överlämning – SubSpeaker Integration

Senast uppdaterad 2026-11-14, version **0.8.11**. Klistra in länken till den här filen i en ny chatt för att fortsätta.

## Målet

Integrera en **Velodyne SPL-1200R** (sub) med två **Genelec G Four** så att basen blir jämn: rätt fas, nivå och delning. Appen är en Android-app som mäter med telefonens mikrofon (eller en USB-mätmikrofon) och spelar testsignaler via Chromecast.

## Utrustning och rum

- **Kedja:** Pixel 6a (Android 17) → wifi → Chromecast Audio ("Vardagsrum/Hall Chromecast") → Velodynens linjeingång → Velodynens högpassutgång (80 Hz, 6 dB/okt) → Genelec G Four via RCA. Chromecastens volym är huvudvolym.
- **Rum:** 3 × 10 m, takhöjd antagen 2,4 m. Sub i hörnet. Genelec längs väggen, **18 cm från väggen bakom**.
- **Velodyne:** fjärrkontroll med fas 0/90/180/270, VOLUME ±, MUTE (tystar bara subben – Genelecerna spelar vidare), EQ, NIGHT, förinställningar. Lågpass (LPF) ställs på subben, cirka 90 Hz.
- **Genelec DIP:** Bass **−4 dB** (grupp B: omkopplare 3 = 1, 4 = 0), övrigt 0. Manualen rekommenderar −4 för rum med mycket efterklang.
- **Mikrofoner:** **miniDSP UMIK-1** finns sedan november 2026 och fungerar med telefonen via OTG. Telefonens inbyggda saknar obearbetad ljudkälla. Velodynens mikrofon har en tvåpolig TS-kontakt och **syns inte** via USB-C-adaptern – den kräver en CTIA-headsetsplitter.

## Vad mätningarna visat hittills

Bara en riktig rapport finns (v0.6.3, svepmätning, 3 platser):

- Mätkvalitet god: brusmarginal 42–46 dB, upprepningsskillnad 0,1–0,3 dB, Chromecastens fördröjning ≈ 1,5 s.
- **Fas 270** jämnast (avvikelse 2,8 dB mot 4,1 för 180), "trolig skillnad". Fas 90 gav en djup dipp vid 80 Hz.
- Genelec Bass −2 mot −4: ingen säker skillnad, så −4 behålls.
- Nivåsteget blev osymmetriskt (+2,3 dB upp, −0,6 dB ner), troligen missade tryck på fjärrkontrollen. Det behöver göras om.
- **Dipp runt 120 Hz** i alla mätningar. Den beror inte på väggen bakom (18 cm ger utsläckning runt 215 Hz). Den är troligen en rumsmod eller kommer från att sub och Genelec tar ut varandra. Ett test som avgör det finns i appen: mät Genelec ensam med MUTE.

**Ingen mätning med hälsokollen (0.8.x) har skickats än.** Gränser och diagnosregler är därför obekräftade.

## Appen i dag (0.8.9)

**Startsida – Live-kurva** (`ui/LiveToolScreen.kt`, sedan 0.8.9): det användaren faktiskt använder, framför allt för att hitta lyssningsposition. Kurvan visas 20–600 Hz. Under den finns *Jämför platser*: upp till fem namngivna jämförelser (t.ex. "Soffan") med ojämnhet 30–200 Hz (`SweepAnalyzer.deviation`) som tal, och den jämnaste markeras. Jämförelser från före 0.8.9 visas till 300 Hz.

**Hälsokoll – i menyn** (`HealthController`, `ui/HealthScreens.kt`):
1. *Kolla nu*: tre delar i valfri ordning med rörlig mikrofon och periodiskt rosa brus: Allt tillsammans, Genelec ensam (MUTE), Sub ensam (Genelecerna av, valfri).
2. *Diagnos* (`audio/Diagnosis.kt`): utsläckning (fas), nivå, lågpass och rumshål, med "Visa kurvor".
3. *Stämapparat*: fas (appen säger vilket läge du ska trycka och mäter alla fyra), lågpass och nivå, med levande kurva.
4. *Efterkontroll*. Allt sparas automatiskt i **Sparade resultat**, där resultat kan visas och jämföras.

**Menyn:** uppspelning, mikrofon och volym; röstguide (av som standard; mätningen bortser från tiden då rösten talar); Hälsokoll (guidad mätning); Sparade resultat; Lyssna på toner; Fri mätning (svep); Felsökning och export (rapport som text och JSON-export med alla kurvor och impulssvar).

**Mätteknik:**
- `audio/Multitone.kt`: multiton 18–680 Hz (till 0.8.8: 350 Hz), period 65 536 sampel vid 48 kHz (1,37 s), amplitud ∝ 1/√f. Analys per period efter nedsampling till 3 kHz (avskärning 1000 Hz, till 0.8.8: 700 Hz). Högpass vid 12 Hz mot hanteringsmuller. Signal och glapp bedöms inom tonbandet. 1/6-oktavsutjämning på 1/24-oktavspunkter 20–600 Hz (119 punkter; de 94 första är den gamla kurvan 20–300 Hz, så `Store.curve` fyller ut gamla sparade kurvor med NaN, som inte ritas).
- `audio/LiveSpectrum.kt`: exponentiellt medelvärde (Snabb 1,5 s, Normal 4 s, Lugn 12 s). Råläge med Hann-fönster för musik.
- `audio/SweepAnalyzer.kt`: äldre svepmätning (10–600 Hz, 8 s) med dekonvolution och impulssvar. Används i Fri mätning.
- Mått: `SweepAnalyzer.deviation` (ojämnhet 30–200 Hz, dubbel vikt 60–130 Hz) och `balance` (25–63 Hz mot 100–250 Hz, mål +3 dB).
- **Diagnosgränser (obekräftade):** utsläckning om allt tillsammans är mer än 3 dB under Genelec ensam (45–160 Hz) eller förlusten mot effektsumman är över 2,5 dB. Nivå om avvikelsen från målet är över 2 dB. Lågpass om mötesfrekvensen är över 115 eller under 60 Hz. Rumshål om dippen i Genelec ensam är mer än 6 dB. Mätare: 5 dB ojämnhet = 0, 1,5 dB = 1. Ord: under 2,5 dB "Jämnt", under 3,8 dB "OK".

**Uppspelning:** egen Chromecast-klient (CASTV2 över TLS port 8009, Default Media Receiver, porterad från RadioCast). Telefonen kör en ljudserver (NanoHTTPD, port 8089). Klienten ansluter automatiskt igen, gör ett nytt försök om uppspelningen inte startar och slår av mute. Uppspelning i telefonen sker via AudioTrack eller ExoPlayer.

**Kalibreringsfil** (0.8.8, `audio/MicCalibration.kt`): läses in under Mikrofon i inställningarna. Den tolkar REW-formatet (frekvens och dB, med "Sens Factor" och "SERNO" i huvudet) och interpolerar i log-frekvens till kurvpunkterna. Värdet dras bort i `Multitone.curve`, `Multitone.rawCurve` och `SweepAnalyzer.analyze`. Korrigeringen är bara på när en extern mikrofon (USB eller sladd) spelar in; det kontrolleras när varje mätning startar. Sparade resultat får raden "Kalibrerad: …", och exporten innehåller `settings.micCalibration`. För jämförelser med samma mikrofon (positioner, faslägen) ändrar korrigeringen inget.

**Export (0.8.10):** JSON-exporten innehåller nu också `savedResults` (allt i Sparade resultat, med kurvor och raden "Kalibrerad: …"). Före 0.8.10 kom bara den senaste hälsokollen och svepkurvorna med, inte live-jämförelser.

**Kalibreringen kontrollerad (2026-11-14):** `correctionDbOnGrid` stämmer med UMIK-1-filen 7211764.txt (+0,47 dB vid 20 Hz, högst +0,84 dB vid 29 Hz, +0,07 dB vid 600 Hz). Tecknet är rätt: värdena dras bort.

**Mikrofonrad (0.8.11):** varje post i Sparade resultat får "Mikrofon: …" och "Kalibrerad: …"/"Okalibrerad", eftersom de första live-jämförelserna (2026-11-14 17:19) av misstag gjordes med inbyggd mikrofon utan att det syntes.

**Lagring:** SharedPreferences (inställningar, guide, hälsokoll, uppspelning) och `filesDir/sweep.json` (svepkurvor med impulssvar).

## Bygga och släppa

- GitHub Actions bygger vid varje push till `main` och kör enhetstesterna. **Höj `version.txt`** så skapas release `v<version>` med `SubSpeaker-<version>.apk`.
- Fast signeringsnyckel i repot (`app/subspeaker.keystore`, lösenord `subspeaker`). Det är en känd risk: vem som helst kan signera en APK som ser ut som en uppdatering. Installera bara från de egna releaserna.
- Ingen Android-SDK i molnmiljön. Ljudtesterna kan köras lokalt med Kotlin-kompilatorn från GitHub och en liten ersättning för JUnit: kompilera `audio/*.kt` (utom Android-beroende filer) tillsammans med testerna.
- Tag-push ger 403 genom proxyn; releaser skapas därför via `version.txt`.

## Öppna punkter, i föreslagen ordning

1. **Prova på riktigt:** jämför några lyssningspositioner i Live-kurva med UMIK-1 och spara i Sparade resultat; gör också en hälsokoll med alla tre delarna. Skicka JSON-exporten. Kontrollera sedan diagnosen mot kurvorna och kalibrera gränserna.
2. **Gör om nivåsteget** och kontrollera trycken på subbens display.
3. **Städa:** flytta Fri mätning, Lyssna på toner och svepmätningen till en undermeny eller ta bort dem (svepet slutar vid 600 Hz, så kurvans översta punkt är osäker där). Ta bort död kod (`Guide`-objektet och guidedelen av `SweepController`).
4. ~~Stöd för kalibreringsfil~~ – klart i 0.8.8, tecken och storlek kontrollerade mot riktig fil 2026-11-14.
5. **Mikrofonhjälp:** visa en förklaring om TS/TRS-kontakter när bara den inbyggda mikrofonen hittas.
6. Ingen ny funktion förrän punkt 1 är gjord.

## Kända begränsningar

- Telefonens mikrofon är osäker i djupbasen. Nivåbeskedet bygger på absoluta nivåer.
- Velodynens lågpass och fasomkopplare är inte exakt kända, så modellen bakom diagnosen är en uppskattning.
- Gränssnittet är bara byggt och testat i kod, aldrig sett av utvecklaren på en telefon.
- Alternativet för noggrann mätning är **REW** (gratis, finns för Linux) med UMIK-1.
