# Hearthstead — Demo Recovery Master Plan

**Status:** Planlagt, ikke startet  
**Planreview:** Tre separate kontrollrunder fullført 29. august 2026  
**Dato:** 29. august 2026  
**Målplattform:** NeoForge 1.21.1 / Java 21  
**Aktiv modul:** `hearthstead-neoforge/`  
**Demoens sluttpunkt:** Spilleren har gjennomført og evaluert det første raidet  
**Spillertekst:** Engelsk  
**Intern plan og QA:** Norsk eller engelsk etter hva som gir tydeligst bevis

---

## 1. Beslutningen som styrer alt

Hearthstead-demoen skal ikke være «hele den fremtidige modden i liten
oppløsning». Den skal være én ferdig, sammenhengende og morsom vertikal
opplevelse fra første innlogging til Aftermath etter første raid.

Demoen er bare ferdig når hele reisen fungerer uten administrative kommandoer,
uten at spilleren må kjenne systemene på forhånd, uten at varer teleporteres,
uten uforklarlige arbeidstopp, uten gammel UI, og uten at automatiske tester
skjuler feil som er synlige i spillet.

Dette dokumentet er gjennomføringskontrakten. Nye ideer registreres, men de får
ikke bryte arbeidsrekkefølgen eller utvide demoen før den gjeldende porten er
grønn.

### Ærlig kvalitetsløfte

Ingen seriøs plan kan garantere at programvare er bokstavelig feilfri. Denne
planen lover derfor ikke «perfekt fordi vi sier det». Den gjør noe bedre:

- hver viktig påstand får et målbart godkjenningskrav;
- hver feil skal først reproduseres og deretter bevises borte;
- automatisk testing, faktisk spilltesting og visuell vurdering holdes adskilt;
- en del kan ikke kalles ferdig mens en nødvendig test er manglende, gammel,
  tvetydig eller rød;
- demoen installeres først når den eksakte JAR-en som er testet er den samme
  JAR-en spilleren åpner.

Resultatet skal være en demo vi har grunn til å stole på, ikke en demo vi håper
er god.

---

## 2. Faktisk utgangspunkt — må ikke pyntes på

Snapshot ved opprettelsen av planen:

| Område | Faktisk status |
|---|---|
| Aktiv gren | `claude/hearthstead-settlement-mod-vbdb9n` |
| Commit | `b338883281fc470919285ec4d2f3867b99a426b5` |
| Remote-avvik | 20 commits foran remote |
| Arbeidskopi | 754 endrede oppføringer: 281 modifiserte, 472 utrackede, 1 slettet |
| Sporede endringer | ca. 38 301 innsettinger og 4 719 slettinger i 281 filer |
| Siste komplette autoritative GameTest | 552 av 600 bestått; 48 feil |
| Ny grønn full-run | Finnes ikke |
| `qa/reports/latest.json` | Mangler |
| QA-status | `.stale` finnes; ingen aktiv QA-prosess ved plansnapshot |
| Seneste lette kandidatsjekk | Bygg gikk langt nok til ressurskontroll; assets 995/995; animasjonskontroll PASS med 3 advarsler, men ingen fullført `quick: PASS` |
| Godkjent JAR | Ingen |

Konsekvensen er viktig: Det finnes mange verdifulle systemer og forbedringer i
arbeidskopien, men den kan ikke behandles som en trygg hovedversjon ennå. Første
arbeidspakke er derfor gjenoppretting og etablering av én autoritativ baseline,
ikke ny funksjonsbygging.

### Kjente bevis, ikke antakelser

- Farmer har i ekte playtest rapportert `nothing workable inside the confirmed
  zone target Farmhouse` mens moden hvete var synlig i den bekreftede sonen.
- Courier/Carrier og andre settlers har hatt upålitelig dørpassering.
- Lumberer har tidligere stoppet/spunnet etter ett tre og har hatt fysiske
  problemer i hente-, sekk- og bæreflyten.
- UI har i playtest falt til omtrent 20 FPS, hatt overlapping, gamle skjermer,
  flimrende ikoner og utilstrekkelig Development-zoom.
- Carrier-animasjonen ble senere positivt vurdert, men den faktiske
  leveringssløyfen og dørpasseringen er ikke godkjent.
- Lumbererens beste sekke-/arbeidsanimasjoner er valgt som kvalitetsstandard,
  men hele rollen er ikke runtime-godkjent.
- Guard-, Archer-, raid-, rekrutterings-, Development-, Blessing- og
  Journey-systemer finnes som store kandidater, men er ikke bevist som én
  sammenhengende demo.
- De siste kandidatkontrollene fant fortsatt 39 planlagte/ikke implementerte
  animasjonsklipp, en faktisk prop-hånd-konflikt i `HUNTER_LOOSE` og to
  kontekstavhengige prop-klipp. Disse trenger ikke blokkere demoen hvis de ikke
  kan nås i demoen, men da skal de være eksplisitt skjult eller låst.

---

## 3. Definisjon av riktig demo

### 3.1 Spillerreisen som skal være komplett

1. **Første innlogging**
   - Spilleren mottar nøyaktig én Hearthstead Handbook.
   - Boken forklarer kort hva Hearthstead er og viser første konkrete handling.
   - Full inventory håndteres uten tap; boken kan hentes igjen på en tydelig
     måte.

2. **Hearth og grunnleggelse**
   - Spilleren lager og plasserer Hearth med korrekte, synlige oppskrifter.
   - Settlement opprettes én gang og overlever lagring/restart.
   - Mayor er visuelt gjenkjennelig og Hearth viser neste handling uten støy.

3. **Development og kunnskap**
   - Development åpnes fra Hearth, aldri Mayor.
   - Den tidlige stammen er lineær og forståelig frem til første raid.
   - Hover på en node viser krav, pris, belønning og Build Plan-oppskrift.
   - Research/unlock lærer settlementet den virkelige Build Plan-oppskriften.

4. **Første bygning og plaque-loop**
   - Spilleren lager Build Plan, setter den i plaque og bygger et fysisk gyldig
     rom.
   - Plaque forklarer hva som mangler, hvorfor bygningen er ugyldig og hva
     spilleren må gjøre.
   - Ingen plan/plaque/gyldig rom betyr ingen bygning.

5. **Bolig og rekruttering**
   - Boligkapasitet er tydelig.
   - Tavern introduserer nye settlers på en forståelig måte.
   - Spilleren kan se hvorfor en kandidat kommer, venter, blir med eller går.

6. **Jobbtildeling med Emblem**
   - Hver jobb har et fysisk Emblem.
   - Når riktig Emblem gis til valgt settler og riktig bygning er aktiv, blir
     personen ansatt direkte. Ingen separat, forvirrende Hire-knapp.
   - Bygningens Staff-seksjon sier hvilken rolle den trenger og hvordan
     Emblemet brukes.
   - Jobbkortet viser 2–3 viktige attributes og enkelt hva de gjør. Det skal
     ikke foreslå hvilken settler spilleren «bør» velge.

7. **Lumberer**
   - Starter uten gratis øks.
   - Lager synlig forespørsel, får fysisk verktøy, arbeider i bekreftet 3D-sone,
     feller flere trær uten å spinne/stoppes, samler alle relevante drops,
     bruker sekken fysisk og leverer til Lumber Camp storage.

8. **Warehouse og Courier**
   - Warehouse trenger Courier og forklarer dette.
   - Spilleren kan åpne en tydelig Request Queue.
   - Courier velger én ekte forespørsel, henter eksakte varer fra riktig
     workplace storage, bærer dem fysisk og leverer dem til korrekt destinasjon.
   - Forespørselen blir først ferdig når varen faktisk er satt inn.

9. **Farmer**
   - Starter uten gratis hoe.
   - Work Scepter velger to hjørner og separat høyde for et tydelig 3D-volum.
   - Maks sonevolum styres av level/capacity, slik at level 1 ikke dekker et
     urimelig stort område.
   - Farmer finner, planter, høster og leverer gyldige mål i hele det bekreftede
     volumet, også ved lovlige høydeforskjeller.

10. **Forsvar**
    - Spilleren får minst én melee Guard og én Archer gjennom ekte bygninger,
      Emblems og utstyrsforespørsler.
    - Begge starter uten gratis våpen, armor eller arrows.
    - Guard Orders fungerer og forklarer Hold Here, Defend Hearth og Patrol.
    - Archer har tydelig Tower Post/posisjonering og ammo-status.

11. **Varsling og første raid**
    - Readiness forklarer alle mangler før spilleren kan erklære seg klar.
    - Raidet varsles med tid, retning og mål, og bruker en lagret plan som ikke
      rulles på nytt ved reload.
    - Fiendene har lett forståelige roller og minst én navngitt, minneverdig
      Captain.
    - Guard og Archer samarbeider, prioriterer trusler og unngår at alle låser
      seg på samme ufarlige mål.
    - XP gis én gang til riktig forsvarer med synlig og hørbar feedback.
    - Raidet er krevende, lesbart og vinnbart med den forberedelsen demoen har
      lært spilleren.

12. **Aftermath**
    - Resultat, tap, belønning og hva som åpnes videre blir lagret og vist.
    - Demoen får en tydelig avslutning og et troverdig løfte om videre
      utviklingsretninger.

### 3.2 Demoens aktive innhold

Følgende bygninger/roller er releasekritiske:

| Bygning/system | Rolle/formål |
|---|---|
| Hearth | Settlement, Handbook/Journey, Development, readiness og raidstatus |
| House/Lodging | Bolig og kapasitet |
| Lumber Camp | Lumberer og workplace storage |
| Warehouse | Courier, request queue og fysisk logistikk |
| Farmhouse | Farmer, 3D Work Zone og workplace storage |
| Tavern | Innkeeper/rekruttering og ny settler-flyt |
| Barracks | Melee Guard |
| Watchtower | Archer, ammo og Tower Post |

Følgende roller er releasekritiske: `LUMBERER`, `COURIER`, `FARMER`,
`INNKEEPER`, `GUARD` og `ARCHER`.

### 3.3 Bevisst utenfor demoens releaseomfang

Disse elementene skal bevares i kildekoden/backloggen, men skal ikke kunne
ødelegge eller forsinke demoens første vertikale reise:

- alle senere produksjonsyrker og bygninger som Baker, Smith, Miner, Tanner,
  Hunter, Fisher, Herder og øvrige 25-profesjonsløp;
- full etter-raid-økonomi og alle spesialiseringsgrener;
- vogner, større sekker og full tool-upgrade-progresjon;
- flere raidtyper, kampanjer og endgame;
- omfattende Blessing-progresjon utover det som eventuelt brukes i demoens
  avsluttende teaser;
- ikke-godkjente animasjoner for roller demoen ikke viser.

Hvis slikt innhold allerede finnes, skal det enten være utilgjengelig, merket
som fremtidig eller isolert bak en låst node. Det skal ikke presenteres som
ferdig.

### 3.4 Avklaring mot gammel QA-spesifikasjon

Den eksisterende releaseprotokollen inneholder enkelte post-Journey-krav,
blant annet en permanent Doctrine-handling etter første raid. Tobias har senere
presisert at riktig demo først skal være en enjoyable playthrough til første
raid, mens større veivalg kommer etter at demoen er bevist.

Før tester endres skal dette registreres som en eksplisitt
spesifikasjonskorreksjon i Quality Ledger. Ingen test skal slettes eller
svaknes stille. Etter korrigeringen er demoens releasegrense Aftermath; en
Doctrine-visning kan brukes som teaser, men et fullverdig post-raid-system er
ikke en demo-blokker.

---

## 4. Ikke-forhandlingsbare designregler

### 4.1 Verdenssannhet og autoritet

- Serveren er autoritativ for settlement, inventory, arbeid, requests,
  ansettelser, utvikling, combat, XP og raid.
- Klienten presenterer og forhåndsviser; den får aldri gjennomføre samme
  transaksjon en gang til.
- Ingen gyldig plan + plaque + rom = ingen bygning.
- Ingen fysisk vare = ingen levering, produksjon, ammo, verktøy eller utstyr.
- Ingen item-teleportering, duplisering eller stille tap.
- Claims, requests, Work Zones, jobb, orders og raidstatus overlever restart.
- Avbrudd skal enten fullføre atomisk eller rulle trygt tilbake. Aldri halv
  gameplay-state med full visuell animasjon.

### 4.2 Enkel overflate, avansert bakgrunn

Spilleren skal alltid få svar på fire spørsmål:

1. Hva skjer nå?
2. Hvorfor skjer det eller hvorfor er det blokkert?
3. Hva trenger systemet?
4. Hva kan jeg gjøre som neste konkrete handling?

Den avanserte simuleringen skal ikke bli et avansert skjermbilde. Intern
claiming, reservations, capacity, pathing, weight, stamina, threat scoring og
prioritering kan være sofistikert, men den synlige forklaringen skal være kort,
presis og handlingsrettet.

### 4.3 Attributes

- Alle attributes vises på første side av settlerarket.
- Format er tall, eksempel `Strength 25 / 100`; aldri prikker.
- Farge skal hjelpe lesing uten å være eneste signal.
- Hover forklarer konkrete bonusintervaller.
- Hvert attribute skal ha faktisk gameplay-effekt og automatisk test.
- Hvert demo-yrke viser bare 2–3 viktige attributes i en enkel jobbseksjon.
- Eksempel Lumberer:
  - **Strength:** færre økseslag og høyere bærekapasitet.
  - **Stamina:** holder høyere fart med vekt over lengre tid.
  - **Focus eller Coordination:** raskere målvalg og færre avbrutte sekvenser.
- Attributes skal endre arbeidshastighet/kvalitet, ikke introdusere en plagsom
  «Daily work left»-stopper. En sliten arbeider jobber saktere på en lesbar
  måte.

### 4.4 Bygninger skal gi fysisk mening

- Hver obligatorisk blokk skal brukes, representere en ekte funksjon eller
  fjernes som krav.
- Lumber Camps crafting table skal bare være påkrevd hvis Lumberer faktisk kan
  bruke den, eksempelvis til å lage en wooden axe av virkelige logs/sticks når
  reglene tillater det.
- En slik selvdrift kan ikke skape materialer eller ignorere requests:
  komponentene må finnes, kapasiteten må stemme, crafting må være autoritativ
  og animasjonen må vise den virkelige oppskriften.
- Chest/barrel i workplace er lagringsstedet arbeideren leverer til; Courier
  henter derfra.
- Staff-seksjonen skal alltid samsvare med faktisk arbeid som kan utføres.

### 4.5 UI

- Én original Hearthstead-designstandard brukes på alle demoskjermene.
- Warm, tydelig, fargerik og Minecraft-kompatibel; ikke en kopi av andre mods.
- Ingen flimring, unødvendig glow, overlapp, skjulte knapper eller døde
  kontroller.
- Core-info må være lesbar uten scrolling på standard settlerside.
- Scroll brukes bare for sekundærdetaljer eller lister som faktisk trenger det.
- Development skal zoome langt nok ut til at stammen og neste veivalg kan
  forstås samtidig.
- Hver interaktiv node/ikon har tooltip og tydelig hover/focus/disabled-state.
- Modal skal blokkere input til UI under den.
- Engelsk tekst testes på alle støttede GUI scales; norsk lokalisering får ikke
  gjøre layouten utestbar, men demoens spillerflate er engelsk.

### 4.6 Animasjon

- Lumbererens godkjente detaljnivå er minimumsstandarden for alle demoroller.
- Alle kroppsbøy går mot oppgaven/lasten, aldri bakover mot ryggen.
- Sekk som settes ned får en fast world transform og følger ikke personen.
- Props og inventory-state er synkronisert med kontaktframe.
- En arbeider som bærer på ryggen skal ikke late som den holder samme last
  foran seg.
- Handlinger filmes som komplette state-overganger, ikke bare pene
  stillbilder.
- Hver rolle skal ha personlighet i idle, walk, work, carry, blocked og
  recovery, uten at lesbarhet ofres.

### 4.7 Lyd

- Lydene skal bygges på nytt som et jordnært, varmt og responsivt Hearthstead-
  lydbilde.
- Bare egne eller tydelig kompatibelt lisensierte kilder brukes; alle kilder
  føres i lisensregister.
- Lyd trigges på autoritativ handling/kontakt, har variasjon og anti-spam.
- Pickup, drop, arbeid, chest, crafting, assignment, request, success, failure,
  warning, combat, XP og raid trenger distinkte, men balanserte signaler.
- Subtitles, attenuation, volumkategori og multiplayer-avspilling er del av
  godkjenningen.

---

## 5. Prioritet og arbeidsrekkefølge

### Nå — må gjennomføres i denne rekkefølgen

1. **G0: Recovery og én autoritativ kilde**
2. **G1: Fersk baseline og feilregister**
3. **G2: Dør/pathing som felles P0**
4. **G3: Work Zone + Farmer P0**
5. **G4: Lumberer kontinuerlig loop**
6. **G5: Requests + Courier + fysisk logistikk**
7. **G6: Plaque, Development, Emblem, rekruttering og verktøyflyt**
8. **G10A: Frys Guard/Archer/raidets state-, authority- og snapshotkontrakter**
9. **G7: UI-plattform, ytelse og alle demoskjermene**
10. **G8: Rolle- og interaksjonsanimasjoner**
11. **G9: Lydretning og implementasjon**
12. **G10B: Integrer, balanser og sluttgodkjenn Guard/Archer/første raid**
13. **G11: Handbook/Journey-integrasjon og full onboarding**
14. **G12: Stabilisering, ekte playthrough og release**

Gate-ID-ene beskriver workstreams, mens listen over er den faktiske
kjørerekkefølgen. G10 er bevisst delt i to: G10A stabiliserer combatdata og
autoritet før UI, animasjon og lyd bygges mot dem; G10B setter de ferdige
presentasjonslagene sammen, balanserer raidet og kjører full sluttgodkjenning.
Dette fjerner den sirkulære avhengigheten der UI ellers måtte bygges før
combat-state var stabilt.

### Neste — først etter godkjent demo

- post-raid Doctrine-balansereise og tre ekte spillestiler;
- vogn, sekkeoppgraderinger og tool-tier-progresjon;
- produksjonskjeder og de øvrige yrkene;
- mer rekrutteringsdybde, Blessings og flere raidfamilier.

### Senere

- full modkampanje, endgame, bred multiplayer-skala, kompatibilitetspakker og
  ekstern wiki.

---

## 6. Gate G0 — Recovery og én autoritativ kilde

### Spillerutfall

Ingen direkte spillerendring. Denne porten hindrer at riktig UI eller systemer
forsvinner igjen når en gammel JAR, remote-gren eller lokal fil tar over.

### Oppgaver

1. Bekreft at ingen QA-, Gradle-, Minecraft- eller filskriveprosess bruker
   prosjektet.
2. Lag en tidsstemplet, verifisert backup av hele arbeidskopien før endring.
3. Registrer SHA-256, størrelse og plassering for backupen.
4. Lag en uforanderlig recovery-gren/tag fra gjeldende `HEAD`.
5. Legg alle relevante utrackede source/assets/tests/docs under versjonskontroll
   i en separat integrasjonscheckpoint. Ikke ta med byggoutput, caches, videoer
   eller hemmeligheter.
6. Generer en manifestliste over alle 754 arbeidskopioppføringer og klassifiser:
   - source som skal beholdes;
   - test/QA som skal beholdes;
   - assets som skal beholdes;
   - dokumentasjon som skal konsolideres;
   - generert output som skal ignoreres;
   - ukjent som krever individuell kontroll.
7. Sammenlign tre sannhetskilder:
   - lokal source;
   - siste kjente gode checkpoint/JAR;
   - JAR-en som ligger i aktiv CurseForge-profil.
8. Sammenlign JAR-innhold, klasselister, ressurser og hashes. En nyere filversjon
   velges ikke automatisk; den må også være den mest komplette og testbare.
9. Etabler én canonical integration branch og skriv commit/hash inn i
   `CURRENT_STATE.md`.
10. Flytt motstridende/stale statusnotater til historikk eller merk dem tydelig.
    Quality Ledger beholder beslutningshistorikk, men aktiv status skal ligge i
    ett kort, sant sammendrag.
11. Avklar demoens Aftermath-slutt som formell spesifikasjonskorreksjon før
    release-testene justeres.
12. Registrer også Tobias sin nyere Work Scepter-beslutning som en eksplisitt
    spesifikasjonskorreksjon: to block-hit hjørner etterfølges av et separat,
    tydelig høydevalg. Eldre QA-tekst som bare sier «two-corner/full-height»
    må oppdateres gjennom Quality Ledger og nye tester, aldri stilltiende.

### Godkjenningskrav

- Ingen source-, asset- eller testfil finnes bare som en ukjent lokal fil.
- Canonical commit, branch, backuphash og aktiv JAR-hash er dokumentert.
- Recovery fra backup er kontrollert på en separat temp-lokasjon uten å
  overskrive arbeidskopien.
- Frossen gammel prototype er ikke en del av builden.
- Bare én Hearthstead-JAR kan lastes fra aktiv profil.
- En identitetsstreng i log/UI kan koble kjørende mod til eksakt commit og
  JAR-hash.
- Ingen funksjonsimplementasjon starter før denne porten er signert.

### Stoppregel

Hvis ukjente filer, flere aktive JAR-er eller kilde/JAR-avvik ikke kan
forklares, stopper videre bygging. Vi gjetter ikke hvilken versjon som er best.

---

## 7. Gate G1 — Fersk baseline og feilregister

### Mål

Erstatt historiske påstander med ett ferskt, reproducerbart bilde av den
canonical arbeidskopien.

### Oppgaver

1. Kjør QA doctor/sanity gjennom det godkjente verktøyet.
2. Kjør `tools/hearthstead-qa quick` uten sourceendringer.
3. Reparer bare den første faktiske compile-/asset-/animation-feilen dersom
   quick er rød; kjør på nytt etter hver faktiske årsak.
4. Når quick er grønn, kjør `fast` eller autoritativ `gametest` for alle 600
   tester.
5. Opprett et nytt failure ledger fra ferske artifacts, ikke fra forventet
   oppførsel.
6. For hver av de historiske 48 feilene registreres én status:
   - fortsatt reproducerbar;
   - bestått i ny full suite;
   - erstattet av gyldig spesifikasjonskorreksjon;
   - testen nås ikke på grunn av tidligere hard feil;
   - testfixture er falsk og trenger separat dokumentert reparasjon.
7. Knytt hver feil til subsystem, reproduksjons-ID, første dårlige state,
   sannsynlig rotårsak og eier-work package.
8. Kjør ingen parallelle QA-suiter og rediger aldri mens `full` kjører.

### Godkjenningskrav

- Fersk `quick` er PASS.
- Alle 600 GameTests er faktisk nådd eller suite-stopperen er dokumentert som
  øverste P0.
- Hver rød test har artifact, reproduksjon og subsystemeier.
- Ingen test er slettet, skippet, gjort slappere eller gitt lengre timeout for
  å få grønt.
- Baselinefingerprint er lagret og alle senere målinger peker til den.

---

## 8. Gate G2 — Felles dør- og pathingmotor

### Hvorfor først

Courier, Farmer, Lumberer, Guard og vanlige settlers deler navigasjon.
Rolle-spesifikke omveier vil skjule rotårsaken og produsere nye feil. Dører
skal derfor løses én gang i felles AI/pathing.

### Undersøkelse

- Reproduser lukket/åpen vanilla-dør, dobbeldør, dør i smal korridor, inn/ut av
  workplace, trapdoor/fence gate hvis de støttes, høydeforskjell ved terskel,
  to settlers i motsatt retning og dør som lukkes under overgang.
- Logg path result, requested node, door state, collision shape, OpenDoorGoal-
  state, timeout og stop reason.
- Skill mellom «ingen rute finnes», «rute finnes men dør åpnes ikke»,
  «kollisjonen frigjøres ikke» og «goal blir preemptet».

### Implementasjonskontrakt

- Én felles door traversal-policy i SettlerEntity/navigation.
- Settler åpner døren ved faktisk kontakt, passerer helt og lukker den når det
  er trygt.
- Ingen permanent forced-open dør.
- Ingen spinning dersom døren er blokkert; tydelig bounded retry og blocker.
- To settlers skal ikke deadlocke evig i samme åpning.
- Raider-/forsvarsregler må ikke utilsiktet gi fiender samme adgang som
  sivile arbeidere.

### Testmatrise

- alle demoroller gjennom samme dør begge veier;
- lagret/reloadet dørtilstand;
- blocked, destroyed og replaced dør;
- to entities samtidig;
- workplace eneste inngang;
- multiplayer-observasjon uten klient/server-avvik.

### Godkjenningskrav

- 100 påfølgende inn/ut-passeringer per demorolle uten permanent stopp.
- Ingen entity står og spinner mer enn definert recovery-vindu.
- Døren ender lukket når det er riktig og åpnes ikke på avstand.
- Ferske path/door GameTests og behavior-thrash-analyse er grønne.
- Faktisk klientvideo viser troverdig kontakt, passering og fortsettelse.

---

## 9. Gate G3 — Work Scepter, 3D-soner og Farmer

### Spillerutfall

Spilleren velger et begripelig, begrenset 3D-arbeidsområde. Farmer finner alle
lovlige oppgaver innenfor sonen, arbeider kontinuerlig og forklarer reelle
blokkeringer.

### Work Scepter-flyt

1. Scepter bindes til riktig Lumber Camp eller Farmhouse.
2. Første block-hit setter hjørne A i X/Z.
3. Andre block-hit setter hjørne B i X/Z.
4. Eget tredje valg setter høyde/topplag på en tydelig måte.
5. Transparent preview viser hele volumet, mål og block count.
6. Confirm skriver én ny serverautoritativ revisjon.
7. Cancel og Preview endrer aldri lagret state.

### Sonebegrensning

- Maks X/Z, høyde og totalt volum kommer fra en sentral capacity-funksjon.
- Level 1 har et nyttig, men begrenset område.
- Senere level/utstyr kan øke kapasiteten uten å endre lagringsformat.
- For stor, invertert, unloaded, cross-settlement eller stale sone avslås uten
  world mutation og uten force-loading.
- Hele boksen må ligge innenfor settlementets tillatte sfære.

### Farmer-state machine

`IDLE_NO_ZONE → SCAN_BUDGETED → CLAIM_TARGET → PATH_TO_CONTACT →
WORK_ANTICIPATION → AUTHORITATIVE_CONTACT → COLLECT → STOW/CARRY →
DEPOSIT → RELEASE_CLAIM → NEXT_TARGET`

Måltyper prioriteres deterministisk:

1. moden avling som kan høstes;
2. tom, gyldig farmland med tilgjengelig riktig seed;
3. dirt/grass som lovlig kan tills hvis hoe finnes;
4. ground item med gyldig provenance fra Farmerens egen handling;
5. deposit dersom inventory/capacity krever det.

### P0-diagnosepunkter

- Normaliser min/max X/Y/Z før query.
- Skann alle Y-lag i bekreftet volum, ikke bare bakkenivå eller bygningens Y.
- Skill crop block fra farmland-underlaget.
- Valider crop age og seedmapping for hver støttet crop.
- Ikke krev at target ligger i Farmhouse-rommet; det skal ligge i Farm Work
  Zone og knyttes til riktig Farmhouse.
- Mål claim, path contact cell, LOS/rekkevidde og handlingens faktiske block.

### Godkjenningskrav

- Den ekte playtest-reproduksjonen med moden hvete går fra blocker til høsting.
- Farmer planter, høster og leverer minst tre komplette sykluser uten hjelp.
- Fungerer på tillatt høydeforskjell og aldri utenfor sonen.
- Ingen gratis hoe/seed, itemtap, duplisering eller replay etter avbrudd.
- Level 1 oversize-sone avslås med klar forklaring og tillatt størrelse.
- Save/reload bevarer sone, revisjon, inventory og trygg claim-recovery.
- Tydelige statusmeldinger skiller `No work zone`, `Need hoe`, `Need seeds`,
  `No mature crops`, `Storage full` og `Path blocked`.

---

## 10. Gate G4 — Lumberer som komplett golden-standard-loop

### Mål

Lumberer skal ikke bare ha en pen sekkeanimasjon. Hele arbeidskjeden skal være
effektiv, fysisk riktig, kontinuerlig og recovery-sikker.

### Autoritativ state machine

`REQUEST_AXE → RECEIVE/EQUIP → SCAN_ZONE → CLAIM_TREE → PATH → LIMB → CHOP →
TREE_FALL/COMMIT → PLACE_BAG/FRAME → COLLECT_ALL_OWNED_DROPS → STOW →
PICK_UP_LOAD → LADEN_WALK/TURN → DEPOSIT → RELEASE → NEXT_TREE`

### Rotårsaker som skal bevises eller forkastes

- stale tree claim etter første tre;
- target som fortsatt peker til fjernet log;
- en siste log/drop uten reachable contact cell;
- state callback som ikke slipper animation/activity;
- route retry som mangler bounded recovery;
- sekkentity som er child/attachment og derfor følger Lumberer;
- inventory/capacity som setter loop i umulig mellomstate;
- workzone/height-filter som gjør neste tre usynlig.

### Fysisk sekke- og henteatferd

- Sekken settes ned ved en valid, synlig posisjon.
- World anchor lagres; sekken følger aldri personen mens den ligger.
- Lumberer bøyer seg frem, tar opp ett synlig item, viser stow-kontakt og går
  videre.
- Etter alle planlagte pickups går personen tilbake til samme sekk.
- Oppløft og belastet walk har fremoverlent tyngde, riktig armplassering og
  ingen krypende gait.
- Kapasitet bestemmes av Strength, sekk-tier og item weight/stack-regel.

### Selvcrafting av wooden axe

Dette bygges bare hvis det forenkler demoen uten å bryte fysisk økonomi:

- Lumber Camp må ha crafting table.
- Nødvendige logs/planks/sticks må finnes i workplace storage.
- Request Queue viser hvorfor Lumberer venter og at self-craft er mulig.
- Settler legger synlige ingredienser på bordet, arbeider/slår, og resultatet
  oppstår på kontaktframe.
- Serveren bruker de eksakte materialene én gang.
- Bedre økser kommer fortsatt gjennom normal logistikk/progresjon.

Hvis denne loopen gjør onboarding mer komplisert, brukes vanlig fysisk
verktøylevering i demoen og self-craft beholdes som senere forbedring.

### Godkjenningskrav

- Minst fem trær på rad i samme session og tre nye etter save/reload.
- Ingen spinning, uforklarlig idle eller claimlekkasje.
- Alle forventede drops kan redegjøres for i world, bag, Lumberer-inventory og
  workplace storage.
- Flyttet drop, sealed route, full storage, ødelagt tre og avbrudd recovery
  gir ingen tap/duplikat.
- Komplett animasjonsfilm inkluderer chop → set down → pickup/stow → return →
  lift → laden walk + turn → deposit → next tree.

G4-filmen godkjenner at state-rekkefølgen, world anchors, props og inventory-
handoff er fysisk sammenhengende. Den endelige motion-, silhouette- og polish-
godkjenningen skjer i G8 etter at alle demorollene bruker samme
animasjonsprosess. Endrer G8 kontakttiming eller props, skal G4-regresjonene og
hele filmen kjøres på nytt.

---

## 11. Gate G5 — Requests, Courier og fysisk logistikk

### Én requestmodell

Alle tool-, equipment-, food-, ammo- og materialbehov bruker samme
serverautoritative requestkontrakt:

- request UUID;
- requester UUID og settlement UUID;
- eksakt item/predicate, ønsket antall og levert antall;
- prioritet med begrunnelse;
- source reservation;
- assigned Courier;
- state/revision;
- opprettet/oppdatert tidspunkt;
- blocker/stop reason;
- transaksjonsledger for hver flytting.

### State machine

`OPEN → SOURCE_FOUND → RESERVED → COURIER_ASSIGNED → PATH_TO_SOURCE →
PHYSICAL_PICKUP → IN_TRANSIT → PATH_TO_DESTINATION → PHYSICAL_DEPOSIT →
RECONCILE → COMPLETE`

Feilgrener:

`WAITING_FOR_STOCK`, `SOURCE_BLOCKED`, `DESTINATION_BLOCKED`,
`DESTINATION_FULL`, `REQUESTER_GONE`, `COURIER_REASSIGNED`, `CANCELLED_SAFE`.

### Request Queue UI

Hver rad viser kun det spilleren trenger først:

- ikon + item + antall;
- hvem/hvilken bygning som trenger det;
- kort status;
- neste handling ved blokkering.

Sekundærdetaljer som request ID, reservation og rute ligger i tooltip/debug.

### Fysisk bag-to-chest-unloading

1. Courier setter sekken ved brystet/chesten.
2. Bøyer seg frem og henter det faktiske itemet.
3. Itemtype er synlig og gjenkjennelig.
4. Fri arm åpner chest; lid og interaksjon er synkronisert.
5. Itemet flyttes inn og slippes på kontaktframe.
6. Chest lukkes naturlig.
7. Sekvensen gjentas for faktisk planlagt antall eller tydelig stack-bundle.
8. Inventory commit skjer deterministisk én gang per synlig transaksjon.
9. Courier forlater først etter reconcile.

### Kapasitetsregel

Før implementasjon dokumenteres en enkel og inspiserbar formel, eksempel:

`carry units = base bag capacity + Strength bonus + bag tier bonus`

Hver itemkategori får en enhetsvekt eller en tydelig stackregel. UI viser ikke
alle interne tall, men settlerarket forklarer at Strength øker hvor mye som kan
bæres. Visible cycles må stemme med faktisk flyttet mengde; en animasjon kan
vise et lesbart bundle bare når bundle-størrelsen vises tydelig.

### Godkjenningskrav

- Workplace storage → Courier → destination bevarer type, count og components.
- Ingen request blir ferdig før insertion faktisk lykkes.
- Partial/full chest, partial stack, flere itemtyper, manglende requester,
  ødelagt chest, blocked door, reassignment og restart er dekket.
- To Couriers kan ikke levere samme reservation.
- Request Queue stemmer med serverdata etter reconnect.
- Ingen per-frame inventory scan; requestarbeid er event-/cooldown-drevet.
- Minst tre komplette ekte ruter: axe til Lumberer, hoe/seeds til Farmer og
  weapon/arrows til forsvar.

G5 godkjenner logistikkens fysiske og autoritative rekkefølge. G8 eier den
endelige kvalitetsgodkjenningen av bag/chest-kroppsarbeidet. Enhver senere
endring av contact frame eller visible stack-representasjon må derfor kjøre
G5s conservation- og interruption-matrise på nytt.

---

## 12. Gate G6 — Plaque, Development, Emblem og rekruttering

### Plaque og Build Plan

- Blank plaque åpner ingen falsk byggeskjerm.
- Riktig klikk med lært Build Plan setter planen og forbruker den nøyaktig én
  gang.
- Plaque skanner room og viser requirements med tydelig met/unmet-status.
- Ingen blinkende build-plan-render, lysflimmer eller stale preview.
- Ugyldig room sier eksakt hva som mangler.
- Gyldig building registreres én gang og bruker Settlement som sannhetskilde.
- Break/rebuild, save/reload og room som blir ugyldig er fail-safe.

### Development-stamme til første raid

Canonical rekkefølge i eksisterende kandidat:

1. Settlement Charter
2. Shelter
3. Timber Rights → Lumber Camp + Lumberer
4. Stores and Roads → Warehouse + Courier
5. Cultivated Ground → Farmhouse + Farmer
6. Home → House/Lodging
7. Hospitality → Tavern + Innkeeper
8. First Watch → Barracks + Guard
9. Arm the Watch → Watchtower + Archer
10. First Raid Aftermath

For hver node må vi validere:

- prerequisitt;
- quest/objective;
- fysisk pris;
- atomic betaling;
- build plans/job emblems som blir lært;
- oppskrift som vises etter unlock;
- save/network-ID som ikke baseres på enum ordinal;
- hover/tooltip og locked/researchable/unlocked-state;
- ingen node som belønner et system som ikke kan brukes.

### Emblem og direkte ansettelse

- Mayor/Hearth gir kun Emblems som settlementet har lært.
- Emblem er fysisk, non-stackable hvis det er nødvendig for sikker provenance.
- Shift-right-click/bruk på valgt settler validerer building, kapasitet,
  settlement, profession og revision.
- Ved success: item forbrukes én gang, jobb settes, buildingstaff oppdateres og
  request for manglende utstyr oppstår.
- Ved avslag: ingen mutation; spilleren får What/Why/Next.
- Full inventory ved mottak gir safe drop/claim, aldri tap.

### Terminologi og døde arbeidsflyter

Prosjektet inneholder fortsatt eldre språk og UI-nøkler som «Hire», selv om
den låste produktbeslutningen er at et fysisk Emblem ansetter direkte. Før G6
kan godkjennes skal alle player-reachable screens, tooltips, Journey-steg,
språkfiler, nettverkshandlinger og tester auditeres:

- `Hire` beholdes bare som intern historisk/testteknisk term dersom spilleren
  aldri ser eller bruker en separat Hire-flyt;
- gammel Hire-tab, assign-liste eller knapp fjernes/deaktiveres hvis den kan
  nås;
- **Work Scepter** er det eneste navnet på soneverktøyet;
- **Building Plaque** er det eneste navnet på bygningsmåleren;
- eldre omtale av «receptor» må enten kartlegges til riktig eksisterende item
  i en dokumentert migrasjon eller fjernes som foreldet ord; et nytt item skal
  ikke oppfinnes bare for å bevare et uklart navn;
- Handbook og recipes skal bruke de samme navnene som inventory og tooltips.

### Rekruttering

- Tavern er en nødvendig fysisk gate.
- Mayor er tydelig identifisert visuelt.
- Kandidatens arrival/wait/join/leave-state overlever restart.
- Pris og eventuell Innkeeper-effekt er tydelig og atomisk.
- Ubetalbar kandidat blir ikke gratis ansatt og blir ikke stående evig.
- UI forklarer hvordan flere settlers kommer uten ekstern guide.

### Godkjenningskrav

- Hele unlock → craft plan → plaque → valid building → obtain emblem → assign
  → equipment request går gjennom i survival.
- Ingen separat Hire-handling kreves etter Emblem.
- Staff viser riktig rolle på alle syv demobygninger.
- Build requirements bruker bare blokker som faktisk har mening.
- Fersk save/reload midt i hver hovedstate gir samme resultat.

---

## 13. Gate G7 — UI-plattform, ytelse og full demoskjerm-overhaul

### Før redesign

UI skal først profileres. Vi optimaliserer den faktiske hot pathen, ikke bare
utseendet.

Registrer for hver skjerm:

- åpningstid separat fra steady-state;
- frame-time p50/p95/max og slow-frame-andel;
- allocations per frame;
- layout/rebuilds per frame;
- text wrapping, tooltip og item rendering calls;
- network snapshots/packets per second;
- settlement/entity scans initiert fra render;
- cache invalidation og revision changes.

### Teknisk ytelseskontrakt

- Render skal være read-only mot et immutable UI snapshot.
- Data oppdateres ved server revision/event eller bounded intervall, ikke ved
  full scan hver frame.
- Layout, wrapped text, node geometry, ikoner og tooltip-innhold caches på
  input/revision/gui-scale/language.
- Ingen stream/list/map-allokering i hot render loops når data er uendret.
- Development culler noder/linjer utenfor viewport.
- Scroll/pan/zoom endrer transform, ikke bygger hele datamodellen på nytt.
- Plaque/building render bruker stabil cache og invalidasjon, ikke blinkende
  rescan.
- Modal og hover trigger ikke nettverkspolling.

### Målbar ytelsesport

På native Windows-klient, etter warm-up:

- p95 frame-time for hvert UI-state ≤ 33,3 ms;
- ingen gjentatte UI-forårsakede frames over 100 ms;
- UI steady-state p95 maks 35 % dårligere enn samme sessions world-baseline;
- slow-frame-andel stiger maks 10 prosentpoeng;
- hver state har minst 180 samples;
- åpningstransisjon måles separat og kan ikke skjules i lang idle-window.

Xvfb/software rendering brukes til relativ regresjon, aldri som bevis for
native GPU-ytelse.

### Hearthstead design system

Definer én sentral token-/componentplattform:

- palette med semantiske statefarger;
- text styles og typografihierarki;
- 4/8-baserte spacingregler tilpasset Minecraft-piksler;
- panel, card, button, tab, list row, badge, tooltip, modal, progress,
  requirement, item row og empty/error components;
- normal/hover/focus/pressed/disabled/selected/blocked/success/warning states;
- kontrast og ikke-fargeavhengige ikoner/labels;
- korte, konsistente motion-regler uten glow/flimmer.

### Skjerm-for-skjerm-kontrakt

#### Handbook

- velkomst, innholdsfortegnelse og kontekststyrte neste steg;
- oppskrifter viser faktiske items og oppdateres fra canonical data;
- body/index scroll uten overlap; tydelig page navigation.

#### Hearth

- settlement summary, Journey/Next Step, Development, readiness og Mayor på
  ett rolig kommandosenter;
- bare kritiske blockers får høy prioritet;
- ingen gammel/ny UI-miks.

#### Development

- lesbar stamme og fremtidig splitt;
- langt zoom-out, smooth pan/zoom, zoom-to-fit og reset;
- tooltip/popup med navn, kort beskrivelse, quest, kostnad, reward og Build
  Plan-oppskrift;
- tydelig locked, available, affordable, completed og future-state;
- nodeklinjer skal støtte forståelse, ikke dekorere tilfeldig.

#### Plaque/building

- byggingsnavn/status, requirements, purpose og Staff;
- rolle + Emblem-forklaring;
- inventory/production bare når bygningen faktisk bruker det;
- ingen irrelevant blokk eller knapp.

#### Settler sheet + inventory

- navn, jobb, current task, blocker og alle numeric attributes på første side;
- 2–3 jobbrelevante attributes med enkle effekter;
- blessings på samme hovedside uten egen overflødig tab;
- core-siden passer uten scrolling ved standard profile;
- Shift-right-click med tom hånd åpner ekte serverautoritativ inventory.

#### Requests/Storage

- requestkø, stock og blocker er forskjellige konsepter og vises forskjellig;
- faktisk kilde/destinasjon og neste handling er lett å forstå.

#### Guard Orders

- Hold Here, Defend Hearth, Patrol 2–8 punkter og Clear;
- markeringsrekkefølge uten bokstavstøy;
- preview/undo/confirm og truthful per-guard status.

#### Blessings og Emblem

- fysisk item og permanent mottaker forklares;
- ingen «Who should receive it»-anbefaling; spilleren velger basert på klare
  effects/attributes.

### Responsiv testmatrise

- 1280×720 GUI scale 3 som minimumsprofil;
- 1920×1080 GUI scale 2/3/4;
- liten og ultrawide profil;
- engelsk og lengste relevante lokaliseringstekster;
- keyboard, mouse, scroll, hover og click-through;
- tom, normal, full, blocked, stale og error state på hver skjerm.

### Godkjenningskrav

- Alle demoskjermene bruker samme designsystem og ingen gammel skjerm kan nås.
- Ingen core handling krever at spilleren gjetter eller åpner ekstern wiki.
- Alle knapper har runtime-test, ikke bare visuelt mockup.
- Ingen overlapp, clipping, skjult slot, flicker eller dead control i matrisen.
- Frame-time-porten er grønn på eksakt candidate JAR.
- Tobias vurderer de viktigste skjermene tidlig fra tools/mockups, men endelig
  godkjenning skjer i Minecraft.
- Etter G10B kjøres alle Guard Orders-, readiness-, raid- og Aftermath-
  skjermtester samt hele frame-time-matrisen på nytt. G7 er ikke endelig
  godkjent dersom combatintegrasjonen har endret snapshot, layout eller
  oppdateringsfrekvens.

---

## 14. Gate G8 — Animasjoner og fysiske interaksjoner

### Arbeidsmetode per klipp

1. Definer gameplay-event og hvem som eier timing.
2. Definer states: start, anticipation, pre-contact, contact, post-contact,
   recovery og overgang.
3. Definer prop, hånd, world anchor, chest orientation og faktisk inventory
   commit.
4. Lag deterministisk preview fra alle nødvendige retninger.
5. Avvis clipping, bakoverbøy, foot slide, pop og prop-follow.
6. Integrer med runtime state.
7. Film full overgang i ekte game med minst 30 FPS.
8. Gjør separat semantisk vurdering: ser handlingen fysisk riktig ut?

`hearthstead-animation-director` brukes som obligatorisk implementasjons- og
avvisningsprosess når arbeidet starter.

### Obligatoriske demorollefamilier

| Rolle | Obligatorisk synlig familie |
|---|---|
| Lumberer | request/equip, search, chop, bag down, pickup, stow, bag up, laden walk/turn, deposit, next task |
| Farmer | request/equip, till, plant, water hvis ekte system, harvest, pickup/stow, carry, deposit |
| Courier | request select, door traversal, source pickup, bag carry, bag down, item-by-item/bundle unload, recovery |
| Innkeeper | grounded tavern idle, greet/serve/assignment feedback uten dekorativ fake work |
| Guard | unarmed request, equip, idle sentry, follow/defend, patrol, melee anticipation/contact/recovery, XP |
| Archer | unarmed request, bow/ammo equip, tower idle, acquire, aim, loose/contact, reload/starvation, recovery |
| Raider | skirmisher locomotion/attack, brute locomotion/attack, captain identity, hit/death/loot/retreat der det brukes |

### Minecraft Movie-inspirert crafting — original løsning

Referansen brukes kun som lesbarhetsprinsipp: ingrediensene er fysisk synlige,
arbeidet har ett tydelig kontaktøyeblikk, og resultatet kommer som en payoff.
Ingen filmasset, eksakt scene eller beskyttet animasjon kopieres.

Hearthstead-sekvens:

1. Settler reserverer ekte ingredients.
2. Ingredients vises på crafting table i korrekt lesbar gruppe.
3. Hender/verktøy arbeider over bordet.
4. En tydelig strike/press/contact fullfører craft.
5. Inputs forbrukes og output opprettes én gang server-side.
6. Output vises, tas opp og leveres til ekte inventory/storage.
7. Avbrudd før commit returnerer reservation; etter commit kan callback ikke
   spille transaksjonen igjen.

### Godkjenningskrav

- Alle støttede retninger og vanlige kameraer leser riktig.
- Ingen frame på kontaktark viser ryggradsbøy bakover ved pickup/load.
- Ground bag/item/chest holder world-posisjon uten actor-follow.
- Visible item type og faktisk overført type stemmer.
- Full/full-partial/empty og interruption-state er visuelt og autoritativt
  konsistente.
- Multiplayer-observatør ser samme handling uten dobbel playback.

---

## 15. Gate G9 — Ny lydretning

### Før assets

Lag sound-event-audit med:

- event ID;
- kallsted og autoritativ trigger;
- nåværende fil/lisens;
- timing mot animasjon;
- volum/kategori/attenuation;
- subtitle;
- variasjoner/cooldown;
- problem og ny retning;
- runtime- og multiplayer-test.

### Prioritet

**P0 clarity:** request success/failure, assignment, pickup/drop, chest,
craft contact, work contact/completion, warning, combat contact, XP og raid
resolution.

**P1 character:** Lumberer, Farmer, Courier, Guard, Archer og raider-spesifikke
material-/bevegelsesvarianter.

**P2 atmosphere:** settlement ambience, tavern life, distant watch og
after-raid ro.

### Lydkvalitet

- jordnær fremfor syntetisk/generisk;
- korte transienter på handling, lavere og mykere loops;
- 3–5 variasjoner for gjentatt arbeid der det er nyttig;
- pitch/volume randomization innen små, kontrollerte grenser;
- cooldown/de-dup på repeated target checks;
- balansert mot footsteps og vanilla UI;
- ingen clipping, store loudness-jumps eller lyd fra feil posisjon.

### Godkjenningskrav

- Lisens-/attribusjonsregister er komplett før en fil tas inn.
- Hver P0-lyd spilles én gang på korrekt event og aldri på mislykket retry.
- Synlig kontakt og lydtransient er innen to videoframes.
- Subtitles finnes og er meningsfulle.
- Native Windows-client med faktisk output/loopback brukes; null OpenAL/Xvfb
  kan ikke godkjenne lyd.
- Tobias får korte A/B-klipp for retning før hele biblioteket ferdigstilles.

---

## 16. Gate G10 — Guard, Archer og et tilfredsstillende første raid

### G10A — kontrakten før presentasjonsarbeidet

G10A utføres etter G6 og før G7. Den skal stabilisere, med tester og tydelige
snapshotkontrakter:

- Guard/Archer employment, equipment, ammo og order authority;
- readinessmodell og blockerdata;
- raidplan, participants, objective og lifecycle;
- threat assignment og damage/kill/XP authority;
- saveformat/revisions og alle UI-relevante felter;
- animation- og sound-event-ID-er med autoritativ contact timing.

G10A trenger ikke ha sluttpolert UI, animation eller lyd, men de tekniske
state-overgangene må være representative og stabile nok til at G7–G9 ikke
bygges mot gjetninger. Dersom kontrakten må endres senere, markeres alle
avhengige G7–G9-rader stale og kjøres på nytt.

### G10B — integrasjon, balanse og sluttgodkjenning

G10B utføres etter G7, G8 og G9. Da kobles den stabile combatkontrakten til den
ferdige UI-en, animasjonene og lydene før alle raidscenarioene og native
godkjenningsradene kjøres.

### Readiness før raid

Spilleren kan ikke erklære seg klar før følgende er sant og forklart:

- minst én aktiv melee Guard i gyldig Barracks;
- minst én aktiv Archer i gyldig Watchtower;
- minst fem forskjellige levende settlementmedlemmer;
- minst fem forskjellige, gyldige fysiske bed heads i registrerte
  House/Lodging-bygninger;
- begge har fysisk mottatt påkrevd våpen;
- Guard har definert minimum armor hvis demo-balansen krever det;
- Archer har bow og minst åtte arrows i bounded quiver;
- orders/post er gyldige;
- settlement har de bygningene/objective som raidet kan angripe;
- ingen uavklarte P0 equipment requests.

### Guardkommandoer

- **Hold Here:** fast posisjon med tydelig radius og return after combat.
- **Defend Hearth:** følger trusselen rundt spiller/Hearth innen bound, ikke
  blindt etter spilleren overalt.
- **Patrol:** 2–8 punkter i nummerert rekkefølge, preview/undo/confirm.
- **Clear:** fjerner order uten å slette jobb/equipment.
- Archer har separat Tower Post og lyver ikke om unsupported ordre.

### Kampfordeling

En sentral threat coordinator scorer fiender basert på:

- angriper spiller/sivil;
- trussel mot raid objective;
- fienderolle og forsvarerens counter;
- avstand og line of sight;
- hvor mange allies som allerede har committed;
- overkill-estimat og target stickiness;
- forsvarerens order/post.

Dette skal gi samarbeid uten perfekt «hive mind». Guard/Archer får hver sin
target reservation med kort levetid og kan bytte ved reell høyere trussel.

### Fienderoller i eksisterende kandidat

- **Skirmisher:** 18 HP, høyere fart; Archer har fordel mot vanlig
  Skirmisher.
- **Brute:** 30 HP, lavere fart; melee Guard har fordel mot vanlig Brute.
- **Named Captain:** nøytral mot class bonus, tydelig navn/silhuett/lyd og
  raidets minneverdige anker.

Tallene beholdes bare dersom faktisk playtest viser riktig difficulty. De er
startpunkt, ikke fasit.

### Raidstruktur

1. Lagret raidplan opprettes én gang etter readiness.
2. Omen/varsel forteller tidspunkt, retning og sannsynlig mål.
3. Spilleren får meningsfull forberedelsestid.
4. Første raid starter med navngitt Captain, én Brute og tre Skirmishers som
   eksisterende kandidat, med mindre måling viser at dette er ulesbart eller
   ubalansert.
5. Fiender har forskjellige approach- og pressure-roller.
6. Objective-pressure gjør raidet mer enn vanlig mob cleanup.
7. Guards forsvarer sivile/spiller/objective i riktig prioritet.
8. Terminal win/loss oppstår én gang; alle participant UUID-er avsluttes.
9. XP, reward, losses og aftermath lagres.
10. Guards returnerer til riktig ordre etter kamp.

### Difficulty-port

Kjør minst disse scenarioene uten å endre data underveis:

- korrekt forberedt spiller som deltar aktivt;
- korrekt forberedt spiller som hovedsakelig observerer guards;
- én mindre utstyrsfeil for å kontrollere at raidet blir merkbart vanskeligere,
  men ikke teknisk ødelagt;
- spiller down/retreat;
- Guard eller Archer faller;
- save/reload etter warning og etter raidstart.

### XP og feedback

- damage credit og kill credit er serverautoritative og idempotente.
- XP gis bare ved gyldig credit, én gang.
- lyd, visuell cue og attribute/rank-endring stemmer.
- ingen XP-farming fra replayed callback, summoned shell eller samme death.

### Godkjenningskrav

- To nødvendige forsvarere kan skaffes uten creative/admin.
- Guard Orders og Archer Post fungerer i ekte klient.
- Begge slår/skyter bare med fysisk utstyr/ammo.
- Ingen dogpile-thrashing eller lang idle med gyldig trussel.
- Named Captain er tydelig gjenkjennelig i navn, bevegelse og lyd.
- Raidet oppleves krevende og forståelig i minst tre gjennomspillinger, ikke
  bare teknisk bestått én gang.
- Outcome og aftermath overlever restart.

---

## 17. Gate G11 — Handbook, Journey og onboarding

### Handbook-livssyklus

- Hver spiller får én handbook på første eligible join i world/server.
- Persistent per-player receipt hindrer duplikat på reconnect, death,
  dimension change og reload.
- Full inventory gir tydelig safe drop/claim/retry.
- Boken kommer før Hearth forventes brukt.
- Innhold og recipes kommer fra canonical data der mulig, slik at teksten ikke
  blir feil etter balanseendringer.

### Journey-prinsipp

Journey lærer bare systemer som faktisk fungerer. Et steg fullføres av ekte
serverhendelse, ikke ved at skjermen åpnes eller en fake teller endres.

Hvert steg inneholder:

- kort mål;
- hvorfor det betyr noe;
- én konkret neste handling;
- blocker-aware hjelp;
- link/knapp til relevant Hearth/Development/building/settler-visning;
- faktisk completion-event.

### Canonical Journey

1. Les handbook og lag Hearth.
2. Found settlement.
3. Åpne Development og lær Timber Rights.
4. Lag plaque/Build Plan og godkjenn Lumber Camp.
5. Sett Work Zone.
6. Tildel Lumberer med Emblem.
7. Løs axe-request og se første ekte log-deposit.
8. Lær/build Warehouse og tildel Courier.
9. Åpne Request Queue og se første fysiske delivery.
10. Lær/build Farmhouse, sett sone og tildel Farmer.
11. Løs hoe/seed og se første crop-deposit.
12. Bygg bolig og Tavern; forstå rekruttering.
13. Rekrutter til minst fem levende settlers, sørg for minst fem gyldige beds
    og bygg Barracks/Watchtower.
14. Tildel/equip Guard og Archer.
15. Bruk Guard Orders/Tower Post.
16. Oppnå og erklær readiness.
17. Motta warning og forsvar settlementet.
18. Åpne Aftermath og avslutt demoen.

### Godkjenningskrav

- Ny SP-spiller og førstegangs MP-spiller får nøyaktig én bok.
- Alle recipes og teksten stemmer med eksakt candidate build.
- Ingen Journey-step kan bli ferdig før gameplay-resultatet faktisk er sant.
- Reconnect/restart midt i hvert hovedsteg fortsetter riktig.
- En helt ny spiller kan gjennomføre uten ekstern forklaring; friksjon logges,
  ikke bortforklares.

---

## 18. Gate G12 — Stabilisering og release

### 18.1 Automatisk kandidatport

På frossen sourcefingerprint:

1. `doctor`
2. `quick`
3. `fast` / alle 600 GameTests + behavior analysis
4. dedicated server + restart
5. blessing/workzone/relevant persistence-suiter
6. performance 1/25/50/100 settlers
7. client/playtest/visual under kontrollert miljø
8. `full`
9. `full` en gang til uten sourceendring
10. `gate` med samme fingerprint og `green_streak >= 2`

Hvis én sjekk er rød, starter streak på nytt etter faktisk rettelse.

Den dedikerte serverytelsesporten bruker QA-protokollens faste budsjetter:

- 1 settler er rapporteringsbaseline;
- 25 settlers: average MSPT ≤ 45 ms;
- 50 settlers: average MSPT ≤ 45 ms;
- 100 settlers: average MSPT ≤ 50 ms;
- median rapporteres, men erstatter aldri average-gaten.

### 18.2 Ekte klientport

Automatisering kan bare produsere en Candidate. Den eksakte JAR-en installeres
i den eksakte CurseForge-profilen og testes i native Windows-client med vanlig
spillerinput.

Krav:

- kandidat-JAR SHA-256 registrert før installasjon;
- forrige JAR sikkerhetskopiert;
- nøyaktig én Hearthstead-JAR i modsmappen;
- installert SHA-256 = kandidat-SHA-256;
- logg viser canonical build identity og riktig game directory;
- ekte GPU-frame windows for alle demoskjermene;
- faktisk output-enhet og lydopptak;
- save/reload og helt ny relaunch;
- singleplayer og multiplayer.

Den eksisterende native input-/release-client-gaten er dokumentert som under
fail-closed sikkerhetsreparasjon, og `tools/hearthstead-qa live` er eksplisitt
deaktivert til tracked-process-kontrakten er bevist. Før demoen kan få status
APPROVED må denne infrastrukturen være reparert og uavhengig kontrollert etter
`qa/RELEASE_CLIENT_GATE.md`. Hvis den fortsatt er blokkert kan exact JAR bare
kalles **CANDIDATE**, uansett hvor gode skjermbilder, videoer eller automatiske
tester er.

### 18.3 Tre obligatoriske sluttplaythroughs

#### A. Fresh survival — ingen progresjonskommandoer

Første join til Aftermath med en spiller som følger bare in-game-informasjon.
Mål: onboarding, balanse, forståelse og enjoyment.

#### B. Adversarial/recovery

Full inventory, full/ødelagt chest, blokkert dør, ugyldig Work Zone, worker
reassigned, save/reload mid-transfer, mangel på equipment, Guard faller og
raid reload. Mål: ingen tap, duplikat, softlock eller løgn i UI.

#### C. Multiplayer

To spillere med samtidige UI/settler interactions, reconnect, inventory-
autoritet, requestlevering, raid og persistence. Mål: ingen client-server
duplisering, feil spillerautoritet eller dobbel animasjon/lyd.

### 18.4 Visuell og semantisk godkjenning

For alle player-visible transitions lagres:

- video med timestamp;
- contact sheet med start/anticipation/contact/recovery;
- forventet handling;
- faktisk serverstate før/etter;
- PASS/FAIL med konkret grunn;
- Tobias sin vurdering av de viktigste UI-, animasjons-, lyd- og raidmomentene.

En pen mockup, screenshot eller enkelt klipp er ikke nok. Hele state-overgangen
må godkjennes.

### 18.5 Releasepakke

- `hearthstead-<version>-demo.jar` fra canonical commit;
- SHA-256-fil;
- build identity og changelog;
- kjent-begrensningsliste uten skjulte P0/P1-feil;
- kompatibel backup av save før installasjon;
- rollback-JAR;
- endelig QA-manifest og playthrough-rapport;
- kort spillerinstruks: Minecraft/NeoForge-versjon, installasjon og hvordan
  starte demoen.

### Endelig Definition of Done

Demoen er **APPROVED** bare når alle følgende er sanne samtidig:

- én autoritativ source/JAR;
- 600/600 GameTests og all obligatorisk QA grønn;
- to full-runs samme fingerprint;
- ingen åpen P0 eller P1 i demoens reise;
- hele Journey til Aftermath gjennomført i fresh survival;
- Lumberer, Farmer og Courier har flere sammenhengende arbeidsloops;
- dører er stabile for alle demoroller;
- UI-ytelse og layout passer målprofilene;
- alle nødvendige UI-knapper fungerer;
- animasjonene er godkjent i full bevegelse og riktige state-overganger;
- P0-lyd er godkjent i native mix;
- Guard + Archer fungerer med fysisk utstyr og smart targetfordeling;
- første raid er teknisk terminalt, lesbart, krevende og morsomt;
- save/reload og multiplayer er bestått;
- installert JAR-hash matcher den testede kandidaten;
- Tobias har spilt exact candidate og ingen releaseblokkende observasjon står
  åpen.

---

## 19. Tverrgående testmatrise

Hvert subsystem skal vurderes mot disse klassene. «Ikke relevant» må
begrunnes; tom celle er ikke pass.

| Testklasse | Hva den skal bevise |
|---|---|
| Deterministisk logic | Costs, capacity, attributes, priorities, state transitions og idempotens |
| GameTest | Ekte Minecraft-serververden, blocks/entities/inventory og normal AI |
| Behavior trace | Ingen thrash, starvation, stuck-loop eller falsk fremdrift |
| Dedicated server | Ingen client classload; boot, save og restart |
| Persistence/migration | Samme UUID/state etter reload og compatibility med støttet demo-save |
| Multiplayer authority | Riktig spiller/settlement/revision; ingen dobbel commit |
| Performance | Bounded scans, MSPT, packets, allocations og native frame-time |
| Visual | Layout, prop, retning, clipping, motion og feedback |
| Audio | Kontakt-timing, variasjon, volum, subtitle og distance |
| Recovery | Full/blocked/destroyed/stale/interrupted/reassigned |
| Fresh survival | Systemet kan oppdages, forstås og fullføres uten cheats |

---

## 20. Observability som skal gjøre feil raske å løse

Releasebuild skal være ren, men QA-build trenger strukturert diagnostikk.

### Worker trace

- settler UUID, profession og building UUID;
- activity/state og state-enter-tick;
- target/claim/request UUID;
- path result og contact position;
- Work Zone revision/bounds;
- inventory/bag/storage count før/etter;
- stop reason og next retry tick.

### Item conservation ledger

For hver fysiske flytting:

- transaction UUID;
- item ID, count og data components;
- source inventory/slot;
- carrier/bag-state;
- destination inventory/slot;
- commit tick;
- resultat eller rollbackgrunn.

Summen før og etter må stemme. Ledger er diagnostikk, ikke en alternativ
inventory-sannhet.

### UI trace

- screen/state/revision;
- open/close/transition;
- layout/cache rebuild reason;
- network refresh reason;
- frame sample window;
- input consumed/blocked;
- stale snapshot avvist.

### Raid trace

- raid plan/revision og participant UUID-er;
- threat score per committed target;
- reservation/dogpile count;
- damage/kill credit og XP transaction;
- objective state;
- terminal outcome og persistence receipt.

---

## 21. Risikoregister og mottiltak

| Risiko | Alvor | Mottiltak |
|---|---:|---|
| Lokal/remote/JAR-versjoner blandes | P0 | G0 manifest, canonical commit, build identity, hashkontroll og én aktiv JAR |
| 472 utrackede filer mistes | P0 | Verifisert full backup og checkpoint før endring |
| Testsuite er grønn på feil fixture | P0 | Reproduser live-feil, fixture-review, fresh survival og semantic QA |
| System repareres rolle-spesifikt | P0 | Delte rotårsaker som door/path/request løses sentralt |
| Item dupliseres/tapes ved callback/retry | P0 | Transaction UUID, server authority, reservation, reconcile og interruption tests |
| UI ser fin ut, men faller til 20 FPS | P0 | Baseline først, immutable snapshots/caching, native frame gate |
| Mockup fungerer, knapper gjør ingenting | P0 | Runtime input/authority-test per kontroll |
| Animationscreenshot skjuler feil overgang | P1 | Full motion + contact sheet + serverstate proof |
| Lydene har uklar lisens | P0 release | CC0/avklart lisens og komplett register før import |
| Scope creep til 25 yrker | P0 schedule | Lås demoens seks roller; resten utilgjengelig/future |
| Gamle statusdokumenter motsier hverandre | P1 | Én active status; Ledger for historikk; beslutningskorreksjoner eksplisitt |
| Saveformat brytes av stor state-endring | P0 | Versionert schema, migration tests og backup |
| Native-klient godkjennes på annen JAR | P0 | SHA-256 før/etter installasjon + runtime identity |
| Guards virker i tests, men raidet er kjedelig | P1 | Tre difficulty-playthroughs og Tobias-godkjenning |
| Alt forsøkes samtidig | P0 | Én gate og én factual failure om gangen; ingen ny feature ved rød quick |

---

## 22. Fast arbeidsloop når arbeidet starter igjen

For hver oppgave brukes denne korte, obligatoriske loopen:

1. **Definer:** konkret spillerutfall, scope og testbar Done.
2. **Inspiser:** dagens kode, data, test, artifact og live reproduksjon.
3. **Research:** bare målrettet official/API/clean-room research som trengs.
4. **Hypotese:** skriv første dårlige state og sannsynlig årsak.
5. **Plan:** minste originale løsning som reparerer systemet, ikke symptomet.
6. **Implementer:** kun avtalt arbeidsområde; ingen tilfeldig opprydding.
7. **Målrettet verifisering:** exact regression + boundaries/recovery.
8. **Quick/Fast:** kjør via `tools/hearthstead-qa`, én suite av gangen.
9. **Player-visible QA:** faktisk runtime, video/frame/audio/authority etter type.
10. **Oppdater ledger:** hva som er bevist, hva som fortsatt er kandidat.
11. **Neste gate:** gå videre bare når gjeldende akseptansekriterier er sanne.

### Stoppregler

- Ingen nye funksjoner mens quick er rød.
- Ingen «fixed» uten at den opprinnelige reproduksjonen er grønn.
- Ingen «done» på compile-only.
- Ingen JAR-installasjon før candidate gate.
- Ingen redigering mens full-run kjører.
- Ingen parallelle QA-suiter.
- Ingen testsletting, skip eller timeoutinflasjon for å få grønt.
- Ingen dekompilering/kopiering av MineColonies, Tektopia eller andre mods.
- Ingen sletting/flytting av Tobias sine originalvideoer.
- Ingen bred redesign eller nytt sideinnhold før demoporten som eier området.

---

## 23. Rapportformat etter hver gate

Hver gate avsluttes med en kort, sann rapport:

1. **Levert spillerutfall**
2. **Rotårsak/forskningsgrunnlag**
3. **Filer og systemer endret**
4. **Tester som faktisk ble kjørt**
5. **Native/visuell/lyd-verifisering**
6. **Bevisartifact og source/JAR fingerprint**
7. **Åpne begrensninger og risiko**
8. **Neste godkjente gate**

Statusordene er låst:

- **UNSTARTED:** ikke arbeidet med.
- **IN PROGRESS:** implementasjon eller diagnostikk pågår.
- **CANDIDATE:** automatiske og nødvendige interne kontroller er grønne.
- **APPROVED:** exact JAR er godkjent i den påkrevde native/playthrough-porten.
- **BLOCKED:** konkret ekstern beslutning/tilgang mangler; ikke synonym for
  «vanskelig».

---

## 24. Første handling når credits/arbeid gjenopptas

Det skal ikke startes med Farmer, UI eller raid selv om disse er synlige
problemer. Første økt utfører bare Gate G0:

1. frys og backup arbeidskopien;
2. inventer de 754 endringene;
3. checkpoint alle relevante utrackede filer;
4. sammenlign lokal source, kjørende JAR og siste backup;
5. velg og dokumenter canonical integration commit;
6. kontroller at recovery faktisk virker;
7. registrer Aftermath som demoens formelle sluttpunkt;
8. rapporter G0-resultatet før G1-baseline kjøres.

Først når G0 er godkjent kan vi trygt reparere de faktiske feilene uten å
risikere at den riktige versjonen forsvinner eller at en gammel JAR blir testet
på nytt.

---

## 25. Kort styringsoversikt

| Gate | Resultat | Avhengig av | Port til neste |
|---|---|---|---|
| G0 | Én trygg canonical versjon | Ingen | Backup + manifest + hash + recovery |
| G1 | Fersk sann baseline | G0 | Quick green + alle 600 nådd/registrert |
| G2 | Dører/pathing stabilt | G1 | Alle demoroller passerer uten thrash |
| G3 | Farmer + 3D Work Zone | G2 | Flere ekte farmloops + recovery |
| G4 | Lumberer golden loop | G2/G3 sonekontrakt | Fem trær + reload + fysisk film |
| G5 | Requests/Courier/logistikk | G2 | Conservation + tre ekte leveringsruter |
| G6 | Progression/recruitment | G3–G5 | Survival unlock→jobb→request |
| G10A | Stabil combat-/raidkontrakt | G5–G6 | Authority, persistence, readiness og snapshots bevist |
| G7 | Samlet, rask UI | G6 + G10A datakontrakter | Layout + controls + native frame gate |
| G8 | Premium demoroleanimasjon | G3–G7 states | Full motion + semantic/authority QA |
| G9 | Ny godkjent lydretning | G8 timing | Native mix + lisens + sync |
| G10B | Guard/Archer/første raid integrert | G7–G10A | 3 raidtakes + terminal persistence + UI-retest |
| G11 | Handbook/Journey | Alle gameplaygater | Ny spiller fullfører uten ekstern hjelp |
| G12 | Godkjent demo-JAR | Alle | 2× full + native SP/MP + hashmatch |

**Hovedregel:** Vi flytter ikke bare oppgaver mot høyre. Vi flytter bevis. En
gate er ikke grønn fordi mye kode er skrevet; den er grønn når spilleren kan
gjøre det gatebeskrivelsen lover og bevisene peker på den eksakte JAR-en.
