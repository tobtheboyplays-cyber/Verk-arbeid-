# PLAYTEST REVIEW — 2026-08-27 20:11

**Status:** TRIAGERT, FØRSTE SCOPE UNDERKJENT FOR PREMIUM-LEVERANSE  
**Kilde:** `Base Profile 2026.08.27 - 20.11.42.31.mp4`  
**Varighet:** 20,90 sekunder  
**Opptak:** 2560×1440, 60 FPS, HEVC, stereo AAC  
**Gate:** GATE 0 — Tobias' live checkpoint-test

## Eieravklaring

Opptakets periodiske farge-/chromaavvik er en capture-feil og finnes ifølge
Tobias ikke i spillet. Det er fortsatt eksplisitt utenfor modscope. Ingen dom i
dette dokumentet bruker de feilfargede framene som farge- eller teksturbevis.

Etter opptaket rapporterte Tobias også et FPS-fall når en Hearthstead-UI åpnes.
Dette er ikke tallfestet i videofilen og registreres derfor som en reproduserbar
P1-hypotese, ikke et oppdiktet måleresultat.

## Kort dom

Klippet bekrefter at dagens første loop er runtime-synlig, men presentasjonen
og onboardingkontrakten er ikke nær premiumkravet. Tavern-vinduet kutter
forklaringen, Innkeeper bruker en generisk pose, name/activity-overlay skjuler
arbeidet, Lumbererens pickup/stow kollapser kroppen, og Journey avsluttes etter
første logg. Sammen med rapportert UI-FPS-fall betyr dette at arbeidet er en
målbar ombygging av første loop — ikke en rask polishrunde.

## Registrerte funn

### PT-2026-08-27-09 — Tavern-panelet kutter den viktigste forklaringen

**Prioritet:** P1 onboarding/presentasjon  
**Tidskode:** ca. 3,0–4,8 s

`Requirements` viser Bell, Storage, Doors, Light og Floor, men benefit-linjen
`Enables new arrivals; hire an innkeeper to lower...` kuttes til ellipsis.
Dette er nettopp teksten som skal forklare hvorfor Tavern finnes. Panelet er
flatt og tungt av like bokser, done-state har svak visuell prioritet og
bygningsidentiteten formidles dårlig.

**Forventet:** Én kort, lokaliserbar Tavern-beskrivelse fra felles
BuildingDescription-katalog vises fullt på både Build Plan og Development-node.
Plaque-panelet kan vise en lengre benefit-tekst i en målt, wrappet blokk med
tydelig status, krav og neste handling.

**Ferdigbevis:** NB/EN, guiScale 2/3/4, 1280×720 og 1920×1080; ingen ellipsis på
kritisk progresjonstekst, ingen overlapp og samme semantiske katalogdata i Build
Plan, Development og Plaque.

### PT-2026-08-27-10 — Innkeeper har en generisk/falsk arbeidsbevegelse

**Prioritet:** P1 i Tavern-slicen  
**Tidskode:** ca. 0,5–2,2 s

Kettill står utenfor Tavern og bruker en framoverrettet armbevegelse som ikke
kommuniserer servering, rydding, mottak av traveler eller annet reelt arbeid.

**Forventet:** Innkeeper får en ekte, workstation-bundet handling med synlig
prop og autoritativ handling, eller en gjennomarbeidet neutral idle. Ingen
arbeidsclip brukes bare for å få figuren til å se travel ut.

### PT-2026-08-27-11 — Name/activity-overlay skjuler handlingen

**Prioritet:** P1 presentasjon  
**Tidskode:** ca. 8,0–11,0 s

`Colwyn`, `Lumberer` og `Felling timber` fyller store deler av sentrum og
overlapper worker, tre og øks. Teksten forklarer handlingen samtidig som den
gjør handlingen vanskeligere å se.

**Forventet:** Navn skalerer/fades med avstand. Profession og aktivitet vises
bare ved target/fokus eller inspection, med streng bredde og occlusion-regel.
Den fysiske animasjonen skal kunne leses uten stor debug-lignende tekst.

### PT-2026-08-27-12 — Pickup/stow kollapser hele kroppen

**Prioritet:** P1 i Lumberer-slicen  
**Tidskode:** ca. 11,3–12,8 s

Lumbereren går inn i en ekstrem helkropps crouch/lean ved pickup/stow. Posen
leses ikke som en hånd som tar en stack og legger den i bag; den leses som at
hele figuren faller sammen.

**Forventet:** Kort anticipasjon, én synlig håndkontakt, kontrollert hofte/kne,
vektavhengig løft, stow mot virkelig bag og ren recovery. Itemet flyttes fysisk
på kontakt-ticken og går videre til Lumber Camp-storage.

**Ferdigbevis:** Front/side/bak i normal og slow motion, 50 repetisjoner, null
arm-through-torso, stuck pose, dobbel lyd eller visuell teleport.

### PT-2026-08-27-13 — Journey avsluttes etter første logg

**Prioritet:** P1 hovedloop  
**Tidskode:** ca. 18,0–19,0 s

Founding Journey viser fire ferdige mål og konkluderer med at settlementet er
spillerens. Den har ikke lært Development, Mayor/Emblems, ekte inventory,
equipment requests, work zone, workplace storage, Courier, Farmer/mat,
bolig/Tavern, rekruttering, Guard Commands, readiness, varsling eller første
raid.

**Forventet:** Journey v2 er serverautoritativ, viser ett hovedmål om gangen og
går gjennom hele den fysiske kjeden til rapporten etter første raid. Dagens fire
steg behandles som et kompatibelt første kapittel, aldri som full tutorial.

### PT-2026-08-27-14 — Hearth-lagene konkurrerer visuelt

**Prioritet:** P1 UI  
**Tidskode:** ca. 17,0–19,0 s

Hearth-containeren forblir tydelig under et stort Mayor/Journey-panel. Tekst,
inventory, faner og forgrunnspanel konkurrerer om oppmerksomheten, og skjermen
ser ut som to grensesnitt lagt oppå hverandre.

**Forventet:** Én klar aktiv informasjonsflate med kontrollert bakgrunn,
konsekvent dybde og fokus. Underliggende innhold skal enten være bevisst
integrert eller visuelt dempet, ikke halvlesbart bak hovedoppgaven.

### PT-2026-08-27-15 — FPS faller når Hearthstead-UI åpnes

**Prioritet:** P1 ytelse  
**Kilde:** direkte eierobservasjon etter opptaket

**Hypoteser som skal måles, ikke antas:** dobbel blur/background-render,
per-frame tekstwrapping eller sortering, per-frame widget/layout-rebuild,
ressurs-/texturearbeid på rendertråden, unødvendige snapshots/pakker, eller
midlertidige allokeringer som gir GC/frame-time-spikes.

**Måleprotokoll per hovedskjerm:**

1. 30 sekunder identisk world-view før åpning.
2. Cold open og warm open separat.
3. Open-transition med individuelle frame times.
4. 30 sekunder steady-open uten input.
5. Normal interaksjon/scroll/tab-bytte.
6. Close og minst 30 frames recovery.
7. Median, p95, p99, verste frame, FPS, allokering/heap og packet-rate.

**Harde krav etter warm-up:** median frame-time-regresjon maksimalt 5 %, p95
10 %, ingen modskapt frame over 33,3 ms og baseline tilbake innen 30 frames
etter close. Render gjør ingen ressurs-I/O eller autoritativ snapshotbygging.

## Lydnotat

Det ekstraherte opptakssporet måler omtrent −34,9 LUFS integrated, 10,0 LU LRA
og −11,2 dBFS true peak. Det viser at opptaket har mye headroom/lavt nivå, men
det beviser ikke at lyddesignet er godt eller dårlig. Eierens semantiske dom om
at dagens lyder er dårlige står derfor ved lag; løsningen er nye, riktige
kilder, variasjon, crowd-budget og kontakt-synk, ikke bare å skru opp volumet.

## Nye bindende eierkrav etter opptaket

1. Hver bygning får én kort beskrivelse fra én sann NB/EN-katalog. Den skal stå
   både på Build Plan og i Development Tech Tree.
2. Development starter som én naturlig felles vei, splitter etter første raid i
   tre strategiske doktriner og senere fem spesialiseringer.
3. Valgene skal ha reell opportunity cost, eksempelvis sterkere forsvar mot
   svakere eller dyrere produksjon. Aktiv doktrine kan respec-es hos Mayor mot
   fysisk pris/cooldown; permanente unlocks slettes ikke.
4. Dagens UI, motion og lyd er underkjent og får ikke status ferdig før
   runtime-, ytelses-, visuell- og auditiv sluttgate består.

## Bevismateriale

- Originale kontaktframes og lydmåling:
  `qa/reports/playtest/20260827T201142/motion/`
- Video Interaction Mapper-scout, 21 frames og kontaktark:
  `qa/reports/playtest/20260827T201142/interaction_map/`
- `key_moments.json` kartlegger ni semantiske før/etter-øyeblikk.

Disse filene er observasjonsbevis, ikke godkjenning. Ny film skal komme fra en
frosset fingerprint og vise før/etter, normal lyd, FPS/frame-time overlay og
hele den virkelige spillkjeden.
