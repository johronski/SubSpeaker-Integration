# Appdesign

## Syfte
Guidad integration av subwoofer och aktiva högtalare. Användaren sitter i lyssningspositionen och styr subben med fjärrkontrollen; appen spelar signaler via Chromecast, mäter med mikrofon och visar vad som ska ändras.

## Utrustning (referenssystem)
- Telefon → wifi → Chromecast Audio → Velodyne SPL-1200R (linjeingång) → subbens högpassutgång (80 Hz, 6 dB/okt) → Genelec G Four.
- Chromecastens volym är huvudvolym. Velodynens volym ändrar subnivån.
- Fjärrkontroll: fas 0/90/180/270 direkt, volym ±, mute, EQ (rumskalibrering), nattläge, förinställningar 1–4. Lågpassfrekvensen ställs på subben.
- Genelec G Four: grupp A (Low bass −6/−4/−2, Tabletop), B (Level +10, Bass −6/−4/−2), C (LED off, Treble −4/−2/+2).

## Flöde
0. Engångsförberedelse: Genelec Bass −2 dB, övriga 0. Velodyne lågpass ≈ 90 Hz, neutral förinställning, nattläge av, kör rumskalibrering.
1. Start: välj Chromecast och mikrofon, mikrofon i öronhöjd, referensnivå.
2. Mikrofontest: signal, stabil nivå, jämförelse mellan två mikrofoner, närfältskontroll.
3. Fas: 90 Hz ton, användaren trycker igenom fyra lägen, appen mäter och väljer starkast.
4. Nivå och jämnhet: slinga av svajtoner 40–150 Hz, stapelvy uppdateras medan användaren justerar subnivån.
5. Bekräfta med musik.
6. Resultat: inställningar, logg, före/efter.

Fördjupning: jämförelse av förinställningar och Genelec-lägen, USB-mikrofon, rumsmodell.

## Principer
- Mikrofonen jämför nivå vid samma frekvens (pålitligt även med enkel mikrofon). Jämförelser mellan frekvenser är vägledande utan kalibrerad mikrofon.
- Långa, oavbrutna signaler så att Chromecastens buffring bara märks vid start.
- Huvudvolymen låses under mätning och begränsas relativt en referensnivå.
- Stor text, få knappar, synlig stoppknapp, skärmen hålls vaken.

## Faser
- v0.1 Diagnos (Chromecast, mikrofoner, mute, volym, fas – manuellt).
- v0.2–0.4 Guidat flöde steg 0–4.
- v0.5+ Resultat, logg, förinställningar, fördjupning.
