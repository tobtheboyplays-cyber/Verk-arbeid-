# PLAYTEST REVIEW — 2026-08-27 19:45

**Kilde:** `Base Profile 2026.08.27 - 19.45.00.28.mp4`  
**Varighet:** 23,78 sekunder  
**Opptak:** 2560×1440, ca. 60 FPS, HEVC, stereo AAC  
**Gate:** GATE 0 — Tobias' live checkpoint-test  
**Status:** DELVIS VISUELT BEVIS; ikke full playtest eller runtime-gate

## Eieravklaring

Opptaket har periodisk fargeforvrengning. Tobias har bekreftet at dette bare er
et opptaksfenomen og ikke finnes i spillet. Det er derfor eksplisitt **ikke**
registrert som en modfeil og påvirker ingen visuell dom nedenfor.

## Beviselig positivt i opptaket

1. Lumbereren finner og vender seg mot et fysisk tre.
2. Øksa er synlig i hånden gjennom arbeidssekvensen.
3. CHOP leses som en sidehuggsbevegelse, ikke en ren overhead-bevegelse.
4. Torso og bein deltar i bevegelsen; klippet er ikke bare en arm på en
   statisk kropp.
5. Arbeidslyd høres som separate pulser gjennom huggesekvensen. Det er ingen
   åpenbar dobbeltlyd i dette korte opptaket.
6. Hearth-skjermen åpner og fanene `Settlement`, `Mayor` og `Journey` er
   synlige.
7. Journey-fanen kan åpnes fra den virkelige skjermen.
8. Founding Journey viser fire steg som `Done`:
   - Light the first fire
   - Raise a Lumber Camp
   - Hire a Lumberer
   - Bank the first log
9. Completion-teksten `First log delivered — the settlement is yours.` vises.
10. Settlement-panelet viser fysisk logg i `Commoner stores`, som samsvarer med
    at Journey har registrert en leveranse.

## Registrerte funn

### PT-2026-08-27-01 — blocker-tekst går utenfor Hearth-panelet

**Prioritet:** P2-HØY  
**Tidskode:** ca. 16,8 s  
**Skjerm:** Hearth → Settlement

Teksten:

`Next settler needs a tavern — travelers have nowhere to stay`

går ut gjennom høyre side av Hearth-panelet og fortsetter over verdenen. Dette
er ikke bare kosmetikk: teksten er den viktigste progresjonsforklaringen i
tilstanden, og bruddet svekker både lesbarhet og tillit til skjermen.

**Forventet resultat:** Teksten skal ligge i en avgrenset blocker-komponent med
fast innvendig marg, korrekt wrapping og maksimal høyde som er målt på engelsk
og norsk ved guiScale 2, 3 og 4. Den skal aldri tegnes utenfor sitt materialpanel.

**Bevis som kreves etter fiks:** EN/NB × guiScale 2/3/4 × 1280×720 og
1920×1080, med den lengste blocker-teksten.

### PT-2026-08-27-02 — svak vertikal separasjon i Settlement-panelet

**Prioritet:** P2  
**Tidskode:** ca. 16,8 s

Settlement-navn, `Commoner stores`, morale-raden og blocker-raden konkurrerer
om lite vertikalt rom. Ingen kontroll er bevist ubrukelig i opptaket, men
hierarkiet er trangt og blir særlig risikabelt med lengre norsk tekst.

**Forventet resultat:** Egen målt rad for title, store-label, morale og blocker.
Blocker skal kunne bruke to linjer uten å dytte inventory eller tegne over andre
komponenter.

### PT-2026-08-27-03 — CHOP recovery trenger ren slow-motion-dom

**Prioritet:** REVIEW, ikke bekreftet feil  
**Tidskode:** ca. 0–13 s

Sidehuggen, målretningen, øksa og vektforflytningen er synlige og positive. Det
korte opptaket og opptaksartefaktene er ikke tilstrekkelige til å dømme om
overgangen fra dyp lunge tilbake til neutral har et snap eller om lydens
kontaktpunkt treffer eksakt frame.

**Neste bevis:** Ren fixed-camera-film fra trekvart front og side, 50 % hastighet,
minst fem komplette huggesykluser, med lyd. Dette er visuell QA, ikke grunnlag
for å retune klippet på mistanke.

### PT-2026-08-27-04 — Journey er runtime-synlig, men ikke premium-godkjent

**Prioritet:** P2 / planlagt premium-pass  
**Tidskode:** ca. 18–23 s

Journey-fanen virker og dataene er lesbare. Opptaket beviser ikke alle skalaer,
keyboard-navigation, stale/error/skip-tilstand, norsk tekst eller restart.
Completed-kortene og bunnoppsummeringen skal derfor behandles som
runtime-synlige, ikke som ferdig visuelt godkjent.

## Hva opptaket ikke beviser

- Save/reload eller full serverrestart.
- Blessing-binding på settler eller plaque.
- Rang I–III eller faktisk Blessing-effekt.
- Multiplayer eller samtidige handlinger.
- Full lumberer-loop helt frem til leveringsøyeblikket i samme kamerabilde.
- Nøyaktig lydkontakt på huggeframen.
- UI på andre oppløsninger, skalaer eller norsk språk.
- Fravær av loggfeil, ytelsesproblem eller silent stall i en lengre økt.

## Gate-virkning

- Ingen P0 er synlig i opptaket.
- Ingen sikker P1 er synlig i opptaket.
- `PT-2026-08-27-01` går inn som høyeste konkrete P2 i premium UI-passet.
- Lumberer/CHOP og Founding Journey får status **RUNTIME-SYNLIG**, ikke
  `LOCKED` eller releasegodkjent.

