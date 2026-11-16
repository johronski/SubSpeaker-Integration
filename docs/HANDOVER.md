# Överlämning – SubSpeaker Integration

Senast uppdaterad 2026-11-16 kväll (rumsgeometri från planritning), version **0.9.1**. Klistra in länken till den här filen i en ny chatt för att fortsätta.

## Målet

Integrera en **Velodyne SPL-1200R** (sub) med två **Genelec G Four** så att basen blir jämn: rätt fas, nivå och delning. Appen är en Android-app som mäter med telefonens mikrofon (eller en USB-mätmikrofon) och spelar testsignaler via Chromecast.

## Utrustning och rum

- **Kedja:** Pixel 6a (Android 17) → wifi → Chromecast Audio ("Vardagsrum/Hall Chromecast") → Velodynens linjeingång → Velodynens högpassutgång (80 Hz, 6 dB/okt) → Genelec G Four via RCA. Chromecastens volym är huvudvolym.
- **Rum (från planritning 2026-11-16, se nedan):** vardagsrum + matplats ca 10,8 m (nord–syd) × 3,9 m (öst–väst), öppet via valv mot kök och mot hall/trappa – i praktiken ett L-format rum på ca 70 m². Takhöjd okänd (antagen 2,4 m). Sub i nordvästra hörnet. Genelec mot sovrumsväggen (norr), **18 cm från väggen bakom**, spelar söderut.
- **Velodyne:** fjärrkontroll med fas 0/90/180/270, VOLUME ±, MUTE (tystar bara subben – Genelecerna spelar vidare), EQ, NIGHT, förinställningar. Lågpass (LPF) ställs på subben, cirka 90 Hz.
- **Genelec DIP:** Bass **0** sedan 2026-11-14 20:15 (var −4), övrigt 0. Manualen: Bass är en hylla under 1 kHz med referens 100 Hz; −4 rekommenderas för rum med mycket efterklang, men mätningen gav bättre resultat med 0 (se nedan). Genelec anger rak frekvensgång utan reflexer ±2,5 dB 48 Hz–20 kHz (−6 dB vid 41 Hz); ingen oberoende mätning av G Four hittad.
- **Velodyne nu:** fas **270**, nivå och lågpass (~90 Hz) oförändrade.
- **Mikrofoner:** **miniDSP UMIK-1** finns sedan november 2026 och fungerar med telefonen via OTG. Telefonens inbyggda saknar obearbetad ljudkälla. Velodynens mikrofon har en tvåpolig TS-kontakt och **syns inte** via USB-C-adaptern – den kräver en CTIA-headsetsplitter.

## Vad mätningarna visat hittills

Bara en riktig rapport finns (v0.6.3, svepmätning, 3 platser):

- Mätkvalitet god: brusmarginal 42–46 dB, upprepningsskillnad 0,1–0,3 dB, Chromecastens fördröjning ≈ 1,5 s.
- **Fas 270** jämnast (avvikelse 2,8 dB mot 4,1 för 180), "trolig skillnad". Fas 90 gav en djup dipp vid 80 Hz.
- Genelec Bass −2 mot −4: ingen säker skillnad, så −4 behålls.
- Nivåsteget blev osymmetriskt (+2,3 dB upp, −0,6 dB ner), troligen missade tryck på fjärrkontrollen. Det behöver göras om.
- **Dipp runt 120 Hz** i alla mätningar. Den beror inte på väggen bakom (18 cm ger utsläckning runt 215 Hz). Den är troligen en rumsmod eller kommer från att sub och Genelec tar ut varandra. Ett test som avgör det finns i appen: mät Genelec ensam med MUTE.

**Ingen mätning med hälsokollen (0.8.x) har skickats än.** Gränser och diagnosregler är därför obekräftade.

**2026-11-14 med UMIK-1 (kalibrerad):**
- Kalibreringen kontrollerad mot 7211764.txt: rätt tecken och storlek (+0,1 … +0,8 dB).
- **Närfält bekräftar att appen mäter rätt:** Genelec 2 cm slät 50–600 Hz (stiger ~6 dB från 100 till 500 Hz, från Velodynens högpass och Bass −4), sub 1 cm plan 20–70 Hz och faller ~17 dB/okt, lågpass runt 90–95 Hz. Båda ~−6 dB runt 95–100 Hz → delningen hänger ihop. **Ingen 120 Hz-dipp i närfält, alltså är den rummet/platsen.**
- Hälsokollen 2026-10-20 (inbyggd mikrofon): allt tillsammans 1–2,5 dB *över* effektsumman 76–127 Hz → ingen utsläckning. Dippen vid 120 Hz finns i Genelec ensam.
- Platsjämförelse 17:19 gjordes av misstag med **inbyggd mikrofon** (preliminärt): soffan vänster jämnast (2,7 dB) och nästan utan 120 Hz-dipp; Nu 3,4, mitten 2 m 3,2, lampetthöjd 3,1. Hypotes: breddmod runt 114 Hz (rummet 3 m), hål ~75 cm från långväggarna.
- Inbyggd mikrofon dämpar basen kraftigt och ger lägre (bättre) ojämnhet än UMIK-1 (3,2 mot 4,0 på samma plats). Jämför aldrig mellan mikrofonerna.
- Det gamla ojämnhetstalet var okänsligt för smala djupa hål (10 dB-hål vid 85 Hz ändrade talet 2,75 → 2,69) och räknade en jämn lutning som jämn. Ersatt i 0.9.0, se *Mått*.
- Balansmålet +3 dB är rimligt: en Harman-hylla på 5–7 dB motsvarar ungefär +2,5 … +3,5 dB i appens balansmått (bandet 100–250 Hz ligger delvis på hyllan). Tidigare påstående om att +3 är för lågt var fel.

**2026-11-14 20:15–20:24, plats "trappmitt" (UMIK-1, Lugn medelvärde):**
- Upprepning på samma plats: högst 0,08 dB skillnad över 40 Hz. A/B på samma plats är pålitligt.
- **Genelec ensam, Bass 0 mot −4:** +3,5 dB 40–80 Hz, +2,7 dB 80–130, +2,2 dB 130–200, +1,1 dB 200–250, ±0,5 dB 250–600. Lutning 130–600 Hz 4,3 → 3,2 dB/okt. Hålet 85–130 Hz kvar med samma form. **Bass 0 valt.**
- **Sub på, Bass 0:** ojämnhet 2,8, lutning −0,1 dB/okt. **Utsläckning 69–78 Hz:** vid 73 Hz är summan 4,5 dB *under* Genelec ensam → fas sub/Genelec, inte rum. Hålet vid 120 Hz −4,5 dB, där bidrar subben inte.
- **Fas (Bass 0):** 0 → 3,6 (fyller 73 Hz men hål −7 dB 100–120), 90 → 3,1 (fyller 73 Hz men djupbas 40–60 Hz 2–4 dB svagare), 180 → 3,1 (hål −7 vid 120), **270 → 2,7** (hål −6,8 vid 73). 270 behålls; optimum ligger troligen mellan 270 och 0, som fjärrkontrollen inte når.
- **Nivå (fas 270) mot målet:** 25–40 Hz +1,3, 40–63 +0,2, 63–80 −3,7, 80–160 −0,5, 160–250 +3,9, 250–400 +6,1, 400–600 +11 dB. Subnivån stämmer inom basen. Kurvan i rummet stiger ~10 dB från 130 till 500 Hz; eftersom G Four är rak utan reflexer kommer det från rum/placering/högpass, inte högtalaren. Bredare kurva (över 600 Hz) behövs för att avgöra om det är en lokal puckel – görs i REW, inte i appen (analysen är nedsamplad till 3 kHz, tak ~900 Hz utan ombyggnad).
- **Lyssning:** användaren tycker att det låter bra; krispigare och luftigare än tidigare Bose Companion 20, men subben har blivit svårare att få att smälta ihop (troligen större överlapp när Genelec spelar mer ner mot 50–100 Hz med Bass 0 och Velodynens 6 dB/okt-högpass).
- Smala hål hörs mindre än lika stora toppar (Bücklein 1981, JAES), därför prioriteras inte 73 Hz-hålet för sig.

**2026-11-14 19:39, Jämförelse 3 (Genelec ensam, subben av, via Velodynens högpass):** 130–600 Hz inom ±4 dB, svag topp 330–550 Hz (+2…+4 dB, samma lutning som i närfält → högtalaren/Bass −4, inte rummet). Under 130 Hz 10–16 dB under målet (högpass 80 Hz + Bass −4 + hål 90–120 Hz). Wavelet-fil gjord (`SubSpeaker Genelec J3.txt`, GraphicEQ, bara sänkning ≤ 3,8 dB vid 330–550 Hz, 0 under 300 Hz). Rekommendation: prova Genelec Bass 0 i stället för EQ. Wavelets import ligger i AutoEq-menyn (bara hörlurar); EQ gäller bara ljud via telefonen.

**2026-11-14 18:54, platser med UMIK-1 (Live-kurva, kalibrerad):** lampetten, pianot vänster, pianot, pianot höger, soffan.
- Nya måtten (målbalans +3, hylla G = 5,5 dB): ojämnhet lampetten 3,1, pianot vänster 3,4, pianot 3,1, pianot höger 2,9, **soffan 5,5**. Soffan har lutning −5,5 dB/okt mot målet (≈ 10 dB för mycket djupbas) och en bred topp +9 dB vid 30 Hz. Det gamla måttet gav soffan 2,80, lika bra som de andra – samma fel gjorde att "soffan vänster" såg jämnast ut 17:19.
- De fyra andra platserna skiljer sig lite (0,4 dB i ojämnhet) – går inte att rangordna.
- **Alla fem har ett brett hål 85–130 Hz**, djupast runt 120 Hz. Flytt längs rummet hjälper alltså inte. Kandidater: summering sub/Genelec vid platsen, eller breddmod ~114 Hz (rummet 3 m). Toppar kan sänkas med EQ, hålet inte.
- MSV fem platser 5,9 dB², utan soffan 4,3 dB².
- Hälsokollens två kurvor (före/efter) har olika hål (135 mot 85 Hz) utan ändrad inställning – rörlig mikrofon ger viss slumpvariation.

**2026-11-16, rummets geometri (planritning 1:67, ingen ny mätning; mått uppskattade ±0,2 m):**
- **Orientering:** glasvägg/altan i väster (stort glasparti + dörr), sovrumsvägg i norr. Kök i sydost, hall och trappa i nordost; båda öppna mot vardagsrummet via valv. Öppen spis (ÖS) vid trappan.
- **Volym:** vardagsrum + matplats ca 10,8 × 3,9 m, djupet ökar till ca 7,5 m vid köket. Trappan kopplar till ev. övervåning. "Rummet 3 m" i tidigare text är för smalt.
- **Placering:** västra Genelec ca 0,9 m från glasväggen, östra ca 2,4 m, inbördes ca 1,5 m. Sub i hörnet sovrumsvägg/glasvägg, ca 0,9 m från västra Genelec.
- **Lyssningsplats:** i höjd med öppna spisen, mitt emellan Genelec – ca 3,4 m från sovrumsväggen, 1,6 m från glasväggen. Avstånd ≈ 3,3 m till varje Genelec, ≈ 3,4 m till subben (skillnad ~0,1 m).
- **Värmepump** i hallen (öppen mot rummet); avstängd vid alla mätningar.
- **Slutsatser om hålet 85–130 Hz:**
  - Inte sub/Genelec – hålet finns i Genelec ensam (20:15–20:24). (I chatten 2026-11-16 pekades utsläckning sub/Genelec felaktigt ut som huvudmisstanke innan hela överlämningen var läst.)
  - Reflex från glasväggen (västra Genelec): vid lyssningsplatsen ≈ 0,75 m omväg → hål ≈ 230 Hz, inte 120. c/(4d) ≈ 95 Hz gäller bara rakt ut från väggen. Osannolik vid lyssningsplatsen, men kan bidra på platser närmare glasväggen.
  - Breddmod: med 3,9 m bredd ligger axialmoderna ca 44, 88 och 132 Hz (inte 114); oregelbunden bredd gör dem osäkra. En breddmod ändras inte när man flyttar sig *längs* rummet – att hålet finns på alla fem platser utesluter den alltså inte. Sidledsflytt (test a) avgör.
  - Takreflex: okänd tills takhöjd och höjd på Genelecs baselement är mätta. Med 2,4 m och element 0,7–1,0 m blir hålet ca 140–170 Hz – troligen inte huvudorsak.

## Appen i dag (0.9.1)

**Startsida – Live-kurva** (`ui/LiveToolScreen.kt`, sedan 0.8.9): det användaren faktiskt använder, framför allt för att hitta lyssningsposition. Kurvan visas 20–600 Hz. Under den finns *Jämför platser*: upp till fem namngivna jämförelser (t.ex. "Soffan") med ojämnhet mot målkurvan (`BassMetrics`) som tal, en rad med lutning, NBD och största hål, och MSV mellan platserna. Den jämnaste markeras. Jämförelser från före 0.8.9 visas till 300 Hz.

**Hälsokoll – i menyn** (`HealthController`, `ui/HealthScreens.kt`):
1. *Kolla nu*: tre delar i valfri ordning med rörlig mikrofon och periodiskt rosa brus: Allt tillsammans, Genelec ensam (MUTE), Sub ensam (Genelecerna av, valfri).
2. *Diagnos* (`audio/Diagnosis.kt`): utsläckning (fas), nivå, lågpass och rumshål, med "Visa kurvor".
3. *Stämapparat*: fas (appen säger vilket läge du ska trycka och mäter alla fyra), lågpass och nivå, med levande kurva.
4. *Efterkontroll*. Allt sparas automatiskt i **Sparade resultat**, där resultat kan visas och jämföras.

**Menyn:** uppspelning, mikrofon och volym; Om SubSpeaker och måtten (0.9.0); röstguide (av som standard; mätningen bortser från tiden då rösten talar); Hälsokoll (guidad mätning); Sparade resultat; Lyssna på toner; Fri mätning (svep); Felsökning och export (rapport som text och JSON-export med alla kurvor och impulssvar).

**Mätteknik:**
- `audio/Multitone.kt`: multiton 18–680 Hz (till 0.8.8: 350 Hz), period 65 536 sampel vid 48 kHz (1,37 s), amplitud ∝ 1/√f. Analys per period efter nedsampling till 3 kHz (avskärning 1000 Hz, till 0.8.8: 700 Hz). Högpass vid 12 Hz mot hanteringsmuller. Signal och glapp bedöms inom tonbandet. 1/6-oktavsutjämning på 1/24-oktavspunkter 20–600 Hz (119 punkter; de 94 första är den gamla kurvan 20–300 Hz, så `Store.curve` fyller ut gamla sparade kurvor med NaN, som inte ritas).
- `audio/LiveSpectrum.kt`: exponentiellt medelvärde (Snabb 1,5 s, Normal 4 s, Lugn 12 s). Råläge med Hann-fönster för musik.
- `audio/SweepAnalyzer.kt`: äldre svepmätning (10–600 Hz, 8 s) med dekonvolution och impulssvar. Används i Fri mätning.
- **Mått (0.9.0, `audio/BassMetrics.kt`, förklaras i appen under Om SubSpeaker):** 25–200 Hz mot målkurvan T(f) = G/(1+(f/105)²), där G väljs så att T:s `balance` = målbalansen (+3 dB → G ≈ 5,5 dB). Felkurva e = L − T − medel. **Ojämnhet** = SD(e). **Lutning** = linje genom e mot log₂ f (dB/okt, minus = mer djupbas än målet). **NBD** = medel över halvoktavband av medel |L − bandmedel| (Olive 2004). **Största topp/hål** i e med bredd (punkter över halva värdet /24). **MSV** = medel 30–150 Hz av variansen mellan platser efter nivåjustering (medel 30–200 Hz) (Welti & Devantier 2006). SD + lutning följer Olives hörlursmodell (2018); vikterna används inte. Ojämnheten ersätter `SweepAnalyzer.deviation` överallt: diagnos, mätare, fasval, lågpass, Jämför platser, Fri mätning (där med målbalans +3). `balance` (25–63 mot 100–250 Hz) används som förut för nivåbeskedet. Testerna (`BassMetricsTest`) jämför mot en oberoende Python-version på riktiga kurvor.
- **Referenslinje (0.9.1):** i Live-kurva och i kurvvisaren (diagnos, Sparade resultat) ritas målkurvan streckad grön med band ±3 dB, 25–200 Hz, i nivå med Nu-kurvan (respektive första kurvan) via `BassMetrics.reference` – samma förskjutning som måtten. Slås av med knappen Målkurva.
- Målbalansen (+2 … +6 dB) väljs på sidan Om SubSpeaker och sparas i guiden. Exporten har `metrics` per kurva i `savedResults` (och `msvDb2` för Live-kurva) samt `healthCheck.metricsTogether/metricsAfter`.
- **Diagnosgränser (obekräftade):** utsläckning om allt tillsammans är mer än 3 dB under Genelec ensam (45–160 Hz) eller förlusten mot effektsumman är över 2,5 dB. Nivå om avvikelsen från målet är över 2 dB. Lågpass om mötesfrekvensen är över 115 eller under 60 Hz. Rumshål om dippen i Genelec ensam är mer än 6 dB. Mätare: 5 dB ojämnhet = 0, 1,5 dB = 1. Ord: under 2,5 dB "Jämnt", under 3,8 dB "OK" (samma gränser med nya måttet; de bra platserna 2026-11-14 ligger på 2,9–3,4, soffan 5,5).

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

0. **Nästa gång (2026-11-14 kväll):**
   - **Subben smälter inte ihop:** fråga hur det låter (från hörnet? dröjande/boomigt? toner som försvinner?). Prova lågpass ~80 Hz och mät om fas 270 mot 0 på samma plats.
   - **Mät takhöjd och höjd på Genelecs baselement** (användaren återkommer) → räkna takreflexen.
   - **Hålet 90–120 Hz:** geometrin beskriven 2026-11-16 (se ovan); testerna nedan gäller fortfarande. Tester med Genelec ensam i Live-kurva: (a) flytta mikrofonen i sidled bort från långväggen (t.ex. 0,4/0,75/1,2 m) – flyttar hålet sig är det breddmoden ~114 Hz / reflex c/(4d); (b) koppla ur en Genelec – försvinner hålet är det de två högtalarna mot varandra; (c) flytta en högtalare några dm i sidled – flyttar hålet sig är det reflex vid högtalaren.
   - Referenslinjen förlängs **inte** till 500 Hz: nivån sätts över 25–200 Hz och för Genelec ensam drar bristen under 130 Hz ner linjen ~10 dB, så 130–500 Hz skulle se för högt ut.

1. ~~Mät om platserna med UMIK-1~~ – gjort 2026-11-14 18:54, se ovan. **Öppen fråga till användaren:** sitter han i soffan, eller kan soffan flyttas till pianoväggen? Svaret avgör om EQ-testet (punkt 8) ska börja med toppen vid 30 Hz i soffan.
1a. **Kontrollera 0.9.0 på telefonen:** Jämför platser och Sparade resultat ska visa samma tal som ovan för 18:54 (ojämnhet soffan ≈ 5,5, pianot ≈ 3,1). Hålet 85–130 Hz: mät en plats närmare rummets mitt i sidled (breddmod?) och prova fas 0/90/180/270 i sittplatsen.
1b. **Prova på riktigt (tidigare punkt):** jämför några lyssningspositioner i Live-kurva med UMIK-1 och spara i Sparade resultat; gör också en hälsokoll med alla tre delarna. Skicka JSON-exporten. Kontrollera sedan diagnosen mot kurvorna och kalibrera gränserna.
2. **Gör om nivåsteget** och kontrollera trycken på subbens display.
3. **Städa:** flytta Fri mätning, Lyssna på toner och svepmätningen till en undermeny eller ta bort dem (svepet slutar vid 600 Hz, så kurvans översta punkt är osäker där). Ta bort död kod (`Guide`-objektet och guidedelen av `SweepController`).
4. ~~Stöd för kalibreringsfil~~ – klart i 0.8.8, tecken och storlek kontrollerade mot riktig fil 2026-11-14.
5. **Mikrofonhjälp:** visa en förklaring om TS/TRS-kontakter när bara den inbyggda mikrofonen hittas.
6. Ingen ny funktion förrän punkt 1 är gjord.
7. **Förslag: REW på datorn (efter punkt 1).** Användaren funderar på Linux Mint + REW. REW finns även för Windows/macOS, så OS-byte behövs inte för REW. Vid Mint: prova från USB (live) först, kontrollera ljud, wifi och UMIK-1 (fungerar utan drivrutin), säkerhetskopiera före installation. Uppspelning: REW behöver spela själv med exakt tidtagning – kabel från datorns hörlursutgång till Velodynens linjeingång (3,5 mm → RCA), inte Chromecast (1,5 s fördröjning). Vad REW ger: impulssvar och fas (exakt tidsinställning sub/Genelec i stället för 0/90/180/270), vattenfall (bekräftar rumsmoder vid 45, 75 och 120 Hz) och en oberoende kontroll av appen (mät samma plats med båda). **Princip:** när REW finns byggs ingen mer analys in i appen; appens roll är live-kurvan för att hitta position. Hål som det vid 120 Hz går inte att EQ:a bort – bara plats för lyssnare eller högtalare hjälper.
8. **Förslag: testa rumskorrigering med telefonen (efter punkt 1 och 7).** Syfte: se vad EQ ger innan ev. köp av WiiM Ultra. Pixel 6a saknar 3,5 mm – behövs USB-C-DAC-adapter → Velodynens linjeingång. **Inte Wavelet:** grafisk EQ bara 9 band (för grovt för rumsmoder), och import av finupplöst EQ ligger i AutoEq-menyn som bara gäller hörlurar (osäkert om USB-adapter räknas). Använd en systemomfattande parametrisk EQ (t.ex. Poweramp Equalizer). Gång: mät i REW utan EQ → REW Auto EQ, bara sänkningar, 20–200 Hz → (Claude översätter filterlistan vid behov) → mät igen och jämför. Förväntat: topparna vid 45 och 75 Hz kan tämjas; 120 Hz-hålet kan inte fyllas; korrigeringen gäller en plats, så välj plats först.
   **WiiM Ultra** kan mer än telefontestet visar: egen subutgång med digital delning och subfördröjning (ersätter Velodynens 6 dB/okt-högpass), RoomFit + manuell PEQ samtidigt (10 + 10 band per kanal). Om telefontestet ger tydlig förbättring är WiiM Ultra värd att överväga.
   **Enklare alternativ utan REW (bestämt 2026-11-14):** SubSpeaker ska *inte* generera filter (ny funktion, REW gör det bättre). I stället: användaren mäter i Live-kurva med UMIK-1 på ~5 punkter inom en halvmeter runt vald lyssningsplats, sparar och skickar JSON-exporten; Claude räknar fram PEQ-filter (frekvens, sänkning, Q – bara sänkningar, 20–200 Hz) ur medelkurvan för Poweramp Equalizer. Användaren har en **USB-C till 3,5 mm-adapter** (→ Velodynens linjeingång). Kontroll: EQ i telefonen gäller bara ljud ut genom telefonen, inte Chromecast – appen måste då spela via telefonen/adaptern, och det är okänt om Poweramps EQ påverkar appens eget AudioTrack-ljud. Prova; annars lyssna med musik eller kontrollera i REW. Bygg in filtergenerering i appen först om det visar sig användbart och görs ofta.

## Kända begränsningar

- Telefonens mikrofon är osäker i djupbasen. Nivåbeskedet bygger på absoluta nivåer.
- Velodynens lågpass och fasomkopplare är inte exakt kända, så modellen bakom diagnosen är en uppskattning.
- Gränssnittet är bara byggt och testat i kod, aldrig sett av utvecklaren på en telefon.
- Alternativet för noggrann mätning är **REW** (gratis, finns för Linux) med UMIK-1.
