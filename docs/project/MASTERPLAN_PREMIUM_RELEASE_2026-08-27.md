# HEARTHSTEAD — MASTERPLAN FOR PREMIUM RELEASE

**Opprettet:** 2026-08-27  
**Status:** AKTIV HOVEDPLAN  
**Gjeldende gren:** `claude/hearthstead-settlement-mod-vbdb9n`  
**Aktivt prosjekt:** `hearthstead-neoforge/`  
**Eier:** Tobias Antonsen  
**Koordinator:** Codex  

Denne planen erstatter tidligere rekkefølgeplaner der de er i konflikt med
dagens faktiske checkpoint. Eldre dokumenter beholdes som historikk og
beslutningsspor. Visjonen i `OVERHAUL_PROGRAM.md`, D1–D17 og prosjektets
permanente regler gjelder fortsatt, men Blessings, Founding Journey, Development
Tech Tree, Job Emblems, fysisk request-logistikk og den nye QA-/
ytelsesarkitekturen er nå tatt inn i én samlet progresjon.

Ved konflikt gjelder denne styringsrekkefølgen: Tobias' nyeste eksplisitte
eieravgjørelse, eierdirektivet fra 30/30 svar,
`OWNER_DIRECTIVE_2026-08-27_TECH_TREE_EMBLEMS.md`,
`OWNER_DIRECTIVE_2026-08-27_SETTLER_CONTROL_LOGISTICS.md`,
`qa/PROTOCOL.md` og `QUALITY_STANDARD.md`, dagens kode og ferske bevis, denne
operative planen, og til slutt eldre roadmap-/handoverfiler som historikk.

---

## 1. MÅLET

Hearthstead skal bli en stabil, tydelig og visuelt helhetlig settlement-mod
som Tobias kan spille i survival og teste med venner uten å måtte kjenne
intern kode, bruke en wiki eller gjette hvorfor systemer stopper.

En release er ikke godkjent fordi den bygger. Den er godkjent når:

1. den første spilløkten har en tydelig progresjon;
2. alle systemer som presenteres som aktive faktisk virker;
3. gjenstander og ressurser flyttes chest-true uten skjult juks;
4. permanente valg overlever lagring og omstart;
5. UI, teksturer, animasjoner og lyd oppleves som samme produkt;
6. modden holder ytelsesbudsjettet med en stor settlement;
7. klient, dedicated server og flerspiller bruker autoritativ servertilstand;
8. to komplette testløp på identisk kildekode består før JAR-en kalles en
   release-kandidat.

### Nordstjernen

> Innen 10 minutter skal en ny spiller ha grunnlagt et Hearth og forstå neste
> mål. Innen 25 minutter skal den første lumbereren ha fått et fysisk Job
> Emblem, bedt om en ekte øks, arbeidet i spillerens valgte område og lagt en
> virkelig stokk i Lumber Camp-storage uten at spilleren gjør arbeidet. Derfra
> skal settlementet vokse gjennom lesbare, permanente og målbare valg — uten
> stille stopp, datatap eller unødvendig ytelseskostnad.

---

## 2. SANN STATUS VED PLANSTART

### Beviselig ferdig i dagens checkpoint

- Modden bygger til `hearthstead-0.2.0.jar`.
- Ressursvalidatoren består 927/927 kontroller.
- Norsk og engelsk har likt nøkkelsett.
- Animasjonskontrakten består, men har varsler og visuelle NO-GO-punkter som
  må lukkes før premium-godkjenning.
- Tre fysiske Blessing-segl finnes med egne modeller og teksturer.
- Shift + høyreklikk kan gi permanent Blessing til settler eller plaque.
- Warden's Oath, Hearthward og Thorned Roads har rang I–III og serverstyrte
  effekter.
- Founding Journey finnes med fire serverautoritative steg og egen Hearth-fane.
- Save-skjema, nettverk, revisjoner og autoritetskontroller er betydelig
  strammet inn.
- Nytt og utvidet testmateriale finnes for Blessings, Journey, nettverk,
  lagring, inspeksjon, raids og ytelse.
- QA-harnessen er kraftig herdet mot falske grønne resultat, prosesslekkasjer
  og farlig opprydding.

### Ikke beviselig ferdig ennå

- Tobias' live spilltest på checkpointet er ikke ferdig triagert.
- Full GameTest-discovery og alle nye tester er ikke kjørt ferdig på nøyaktig
  samme checkpoint.
- Blessing-restarttesten er ikke sluttført.
- Den lange ytelsesmatrisen er ikke sluttført etter den siste endringen.
- `full` er ikke kjørt grønt to ganger på samme fingerprint.
- `gate` er derfor ikke grønn.
- Alle aktive animasjoner er ikke sett og godkjent i en ekte klient.
- UI er ikke godkjent på guiScale 2, 3 og 4 i alle relevante skjermer.
- Rollebevegelse, kontaktlyder og enkelte arbeidslyder er ikke premium.
- En endelig ren installasjon og en venne-beta er ikke gjennomført.

### Nåværende plass i progresjonen

**Vi er i GATE 0: eierens checkpoint-spilltest.**  Ingen ny stor funksjon får
fortrenge feil som Tobias finner i den faktiske spillopplevelsen.

Første videobevis er gjennomgått i
`PLAYTEST_REVIEW_2026-08-27_1945.md`: CHOP-sidehuggen og Journey er
runtime-synlige. Opptakets fargeartefakt er eksplisitt avvist som modfeil av
Tobias. Den konkrete Hearth-feilen der tavern-blocker-teksten går utenfor
panelet er registrert som P2-HØY.

Andre videorunde og direkte eierfeedback er triagert i
`PLAYTEST_REVIEW_2026-08-27_1956.md`. Pickup, Lumberer carry-walk, Courierens
armer-foran-kroppen-pose, manglende Guard-styring, uklar rekruttering, dagens
UI og lyd er avvist. Første komplette produktscope er derfor frosset til:
House/Lodging, Lumberer, Farmer, Warehouse/Courier, Tavern/rekruttering og
grunnleggende Guard-kontroll gjennom første raid.

Tredje videorunde fra 20:11 bekrefter at dette er en reell ombygging, ikke en
polishrunde. Tavern-panelet kutter bygningsforklaringen, Innkeeper bruker en
generisk armbevegelse, Lumbererens navn/aktivitet dekker handlingen,
pickup/stow kollapser kroppen, og Journey erklærer ferdig etter første logg.
Tobias rapporterer i tillegg synlig FPS-fall ved åpning av UI. Dette logges som
P1 inntil en måling viser rotårsak og dokumenterer full gjenoppretting etter at
skjermen lukkes. Opptakets fargefeil er fortsatt eksplisitt utenfor modscope.

---

## 3. DE 39 STORE RELEASEKRAVENE

Alle krav er obligatoriske for premium release med mindre Tobias eksplisitt
flytter et krav til en senere versjon. Et krav kan bare få status GODKJENT når
beviset faktisk finnes.

### A. Første spilløkt og progresjon

**R1 — Forståelig start.** Fresh survival skal gi Hearth og tre founders på
under 10 minutter uten mining-ekspedisjon, wiki eller admin-kommandoer.

**R2 — Ekte automatisering.** Første lumberer skal kunne ansettes og levere
en ekte stokk til korrekt Hearth på under 25 minutter. Ingen direkte kall som
omgår hire-, AI-, inventory- eller leveringsflyten teller som bevis.

**R3 — Journey til første raid er sann.** Alle stegene fullføres bare av riktig
serverhendelse mot riktig settlement. Skip krever bekreftelse. Gamle verdener
migreres kontrollert, og ugyldig nåtidsdata avvises. Dagens fire Lumberer-steg
er bare et kompatibelt første kapittel, ikke ferdig onboarding.

**R4 — Ingen stille vegger.** Når spilleren mangler bygning, ressurs,
kapasitet, tillatelse eller kobling, skal UI eller verdensfeedback forklare
hva som stopper progresjonen og hva spilleren kan gjøre.

### B. Settlement, arbeid og logistikk

**R5 — Frosset yrkesroster.** Hvert aktivt yrke i den faktiske rosteren skal
kunne ansettes gjennom normal flyt, finne arbeidsstedet, utføre ekte arbeid og
legge ekte output i riktig inventory.

**R6 — All logistikk virker.** Restock, matlevering, output collection og
consolidation skal virke ende-til-ende. Ingen duplisering, tap, shuttle-loop,
evig reservasjon eller strandet last er tillatt.

**R7 — Rekruttering er chest-true.** Tavern-gate, traveler, kostnad, rabatt,
fullt hus og refusjon må bruke virkelige varer og riktig settlement. Ingen
emerald-økonomi skal finnes.

**R8 — Landsbyen er matsolvent.** En landsby på åtte med mill og bakery skal
kunne gå tre spilldøgn uten at en settler faller under den vedtatte
sultgrensen. Resultatet skal måles, ikke antas.

**R9 — Kamp og raid er ærlige.** Første raid skal være tydelig varslet,
guards skal engasjere, ammo og skade skal være reelle, repair skal koste
materialer, og et raid kan aldri kalles repelled mens aktive raiders fortsatt
er i live-listen.

### C. Blessings

**R10 — Fysisk levering.** Blessings skal være gjenstander i hånden.
Shift + høyreklikk på settler eller plaque er den tydelige hovedhandlingen.
Seglet forbrukes én gang i survival og ikke i creative.

**R11 — Permanent og begrenset effekt.** Alle tre Blessings skal ha rang
I–III, korrekt effekt og hard maksimumsgrense. Personlig og bygningsrang bruker
sterkeste rang, ikke summen. Et forsøk over rang III skal ikke forbruke seglet.

**R12 — Serverautoritet og eierskap.** Feil settlement, ugyldig mål,
utilgjengelig plaque, stale revision, replay og manipulert payload skal feile
lukket uten duplisering eller effekter på feil mål.

**R13 — Permanent lagring.** Personlig og bygnings-Blessing skal overleve
save/reload, chunk unload, klient-reconnect og full serveromstart. Ukjent,
duplisert, fremtidig eller out-of-range NBT skal settes i karantene.

**R14 — Effekt og presentasjon samsvarer.** UI-rang, tooltip, partikler, lyd,
animasjon og faktisk gameplayeffekt skal vise samme Blessing og samme rang.

### D. Teknisk integritet

**R15 — Save-sikkerhet.** Gammel støttet verden skal migrere deterministisk.
Nåværende verden må ikke tape settlers, jobs, inventory, buildings, raids,
Journey eller Blessings ved restart. Fremtidig ukjent skjema skal ikke gjettes.

**R16 — Nettverksintegritet.** Alle muterende handlinger skal validere spiller,
meny, mål, avstand, settlement, permission, revision og payload-grenser på
serveren. To spillere som handler samtidig skal konvergere til én gyldig
tilstand.

**R17 — Ingen skjulte hot loops.** Nye systemer skal være hendelsesstyrte eller
bruke begrensede indekser. Ingen global per-tick skanning av alle settlers,
bygninger, plaques eller Blessings tillates.

**R18 — Ytelsesbudsjett.** Den dedikerte standardmatrisen skal måle eksakt 1,
25, 50 og 100 settlers. Eierens referansetest med 40 og 50 fullt aktive
settlere er i tillegg bindende. Gjennomsnitt skal være under 45 ms ved 40/50;
standardmatrisen har tak 45 ms ved 25/50 og 50 ms ved 100. Median, p95/p99,
spikes, minne, prosessrester og modens differanse mot samme-seed baseline
rapporteres.

### E. Premium presentasjon

**R19 — UI i alle skalaer.** Hver aktiv skjerm skal være fullt betjenbar på
guiScale 2, 3 og 4. Ingen avkuttet knapp, overlapp, uleselig tekst, falsk
empty state eller skjult blocker tillates. Norsk og engelsk inspiseres.

**R20 — Én visuell identitet.** Stein, tre, metall, tekstil, items, settlers,
segl og UI skal følge samme palett-, material- og kontrastregler. Eksisterende
familier må vises i før/etter-par og inspiseres i spillet, ikke bare med en
pixelvalidator.

**R21 — Animasjon med fysisk mening.** Alle aktive klipp skal være nåbare på
riktig rolle og kropp, uten uønsket summering. Kontakt, vekt, verktøy,
silhuett, hold og recovery skal være synlige. SALUTE, rolle-placeholderne,
archer aim/loose, sugar-cane, guard/shield, Courierens usynlig-kasse-pose,
Lumbererens carry-walk og den kollapsende pickup/stow-posen er NO-GO-er som må
lukkes.

**R22 — Lyd på handlingen.** Impact- og arbeidslyder skal treffe kontaktframen
én gang. Ingen dobbeltlyd, intern kontaktforsinkelse eller åpenbar vanilla
stand-in godkjennes for en premium-handling. Dagens lydpakke er eksplisitt
underkjent. Film med lyd og kontrollert A/B-lytting er sluttbeviset.

### F. Release og brukerbevis

**R23 — Ærlig sertifisering.** På frosset kildekode skal `quick`, målrettede
tester, GameTests, behavior, dedicated, restart, performance, client,
playtest og visual være grønne. Deretter kreves `full` to ganger på samme
fingerprint og grønn `gate`.

**R24 — Reproduserbar leveranse.** Release-JAR skal testes fra ren installasjon
med kontrollert modliste, riktig loader, oppretting av ny verden, innlasting av
en støttet gammel verden, reconnect og restart. JAR-hash, testmanifest,
kjente begrensninger, backup-råd og rollback-JAR følger leveransen.

### G. Eierens bindende vertikale loop

**R25 — Sagaoppdrag lærer hele loopen.** Founding Journey er første kapittel,
ikke hele onboardingløpet. Den skippbare, serverautoritative oppdragskjeden skal
lede spilleren videre gjennom mat, courier, taverna, rekruttering, raidvarsel,
vaktstyring, redning, reparasjon, Blessing-valg og starten på neste raidrunde.

**R26 — Fredelig beholder hele raidspillet.** Fredelig er standardprofilen,
men slår ikke av raids, skade, redning eller reparasjon. Første reelle trussel
kommer normalt natt 4–7 og varsles 1–2 dager før. Balansert og Jernvinter skalerer
samme system i stedet for å være egne parallelle spill.

**R27 — Sult har lesbare konsekvenser.** Sult går trinnvis fra redusert
arbeidsevne til kritisk tilstand, downed/redningsvindu og mulig permanent død,
grav og sorg. Ingen overgang kan skje stille, og alle tidsverdier balanseres
med måling.

**R28 — Rekruttering krever to døgn mat.** Tavern, bolig, pris og spiseklar
mat for omtrent to døgn etter innflytting må være gyldige. Manglende mengde
vises konkret. Når alle vilkår er sanne, skal normal kandidatankomst ligge i
intervallet 2–4 spilldøgn.

**R29 — Låste økonomivalg implementeres eksakt.** Ett trekull teller som to
fuel-enheter. Fysisk ale kan gi en liten, målbar og cooldown-begrenset bonus,
men aldri være drifts- eller rekrutteringsgate. Bemanning er manuelt valgt,
og ett aktivt research-prosjekt bruker fysiske ressurser.

**R30 — Hele loopen gjentas i delt landsby.** To til fire spillere skal kunne
dele én landsby, gi få tydelige guard-ordrer, overleve raid, redde/down settlers,
reparere chest-true, velge én av tre Blessings og fullføre minst to raid →
Blessing-sykluser i samme save uten reset, duplisering eller divergerende
progresjon.

### H. Development, emblems og spillerstyrt logistikk

**R31 — Bygninger åpnes i et naturlig Development Tech Tree.** Unlocks lagres
per settlement med stabile ID-er og serverautoritet. En låst plan kan ikke
registrere bygget selv om spilleren kjenner oppskriften eller får itemet fra en
annen verden. Eksisterende Research forblir målbare effektivitetsoppgraderinger,
ikke en konkurrerende unlock-sannhet.

**R32 — Job Emblems er jobbenes fysiske autorisasjon.** Mayoren utsteder eller
selger emblems mot virkelige varer etter riktig tech-unlock. Spilleren velger
settler og workplace eksplisitt. Emblemet forbrukes/bindes bare ved autoritativ
suksess og returneres fysisk ved dismissal, retrain, death eller building
dissolve. Gratis plaque-Appoint er ikke normal survival-flyt.

**R33 — Jobb skaper aldri utstyr.** En ny worker starter uten conjured verktøy,
rustning eller ammo, oppretter en typed request og kan bare arbeide med en ekte
stack. Shift + høyreklikk med tom hånd åpner settlerens serverautoritative
inventory med bag, equipment, durability, requests og courier-reservasjoner.

**R34 — MineColonies-lik workplace/request-loop, clean-room.** Hver worker tar
input fra og legger output i linked workplace storage. En settlement-eid,
persistent request-ledger viser hva som mangler. Courieren reserverer, henter,
bærer og leverer den samme fysiske stacken mellom workplace og Warehouse;
ingen skjult teleport eller «alt i chest er output»-antakelse tillates.

**R35 — Spilleren kan styre områder og forsvar.** Work Scepter angir en
servervalidert 3D-zone for Lumberer og andre relevante jobber. Guard Command
Staff gir få, tydelige og persistente ordre: autonomous defence, stand post,
tower post, patrol route og recall. Verktøyene må være lesbare, permission-
sikre og uten chunk-force-load eller per-tick global søking.

**R36 — Første scope er en komplett tutorial til første raid.** House/Lodging,
Lumberer, Farmer, Warehouse/Courier, Tavern/rekruttering og basic Guards skal
fungere sammen før bred yrkespolish. Journey lærer tech, Mayor, emblem,
inventory, axe-request, work zone, workplace storage, courier requests,
matbuffer, neste settler, guard-ordrer og varslet første raid. Hvert steg
fullføres bare av den ekte serverhendelsen og består save/restart.

**R37 — Tech tree gir meningsfulle valg.** Utviklingen starter som én naturlig
felles stamme gjennom første raid, splitter deretter i tre tydelige
bosetningsdoktriner og senere i fem spesialiseringer. Forsvar, produksjon og
samfunn/handel skal ha synlige styrker, kostnader og opportunity cost. Aktive
doktriner kan byttes hos Mayor mot en tydelig fysisk pris og cooldown; allerede
låste bygninger og permanente unlocks slettes aldri av et strategibytte.

**R38 — Hver bygning forklares fra én sann kilde.** Alle BuildingType-er får en
kort, lokaliserbar beskrivelse samt rolle, input/output og utviklingskrav i en
felles katalog. Den korte beskrivelsen vises både på Build Plan og i Development
Tech Tree; Plaque, Handbook og tooltip kan utvide samme data uten å kopiere eller
divergere teksten.

**R39 — UI er lett nok til å være usynlig for ytelsen.** Hver hovedskjerm måles
mot identisk world-view før åpning, under åpning, steady-state og etter lukking.
Etter warm-up skal steady median frame-time øke maksimalt 5 %, p95 maksimalt
10 %, ingen modskapt frame overstige 33,3 ms, og baseline skal være tilbake
innen 30 frames etter lukking. Ingen ressurs-I/O, tekstwrapping, sortering,
snapshotbygging eller ubundet allokering får skje på hver render-frame.

---

## 4. PROGRESJONEN — GATE FOR GATE

Ingen gate hoppes over. Arbeid i en senere strøm kan forberedes parallelt,
men kan ikke kalles ferdig før alle tidligere avhengigheter er godkjent.

## GATE 0 — TOBIAS' LIVE CHECKPOINT-TEST

**Status:** PÅGÅR NÅ  
**Mål:** Finn forskjellen mellom testbevis og ekte spilleropplevelse.

### Arbeid

1. Tobias spiller checkpoint-JAR-en på en ny verden eller kopi.
2. Alle observasjoner tas imot i vanlig språk; skjermbilde eller logg legges
   ved når det finnes.
3. Hver observasjon klassifiseres:
   - **P0:** krasj, datatap, verden kan ikke lastes, sikkerhets-/dupefeil;
   - **P1:** blokkert hovedloop, feil permanent tilstand, systemet lyver,
     ubrukelig hovedskjerm eller alvorlig ytelsesfall;
   - **P2:** visuell kvalitet, lyd, animasjon, balanse eller mindre friksjon;
   - **P3:** idé til senere innhold.
4. P0 stopper alt annet. P1 løses før ny polish. P2 samles i riktig
   presentasjonsfase. P3 flyttes til Later og får ikke snike seg inn i releasen.
5. En feil reproduseres og får et forventet resultat før den endres.
6. Video, screenshots og logger lagres med tidskode; opptaksfeil som eieren
   bekrefter ikke finnes i spillet holdes utenfor moddens failure-ledger.

### Exit-krav

- Ingen kjent P0.
- Ingen utriagert observasjon fra spilltesten.
- De første 25 minuttene kan gjennomføres, eller hver blokkering har en
  reprodusert P1 med tydelig eier og fix-plan.
- Checkpointets logg og eventuelle crash reports er bevart.

---

## GATE 1 — FRYS ARBEIDSTREET OG GJENOPPRETT EN ÆRLIG BASELINE

**Mål:** Gjøre dagens store overhaul testbar uten gammel/stale evidens.

### Arbeid

1. Sikre det store checkpointet før videre fan-out: inventer alle endrede og
   nye filer, review dem og del dem i logiske, recoverable commits etter
   Blessing, Journey, raid, UI, animasjon, assets og QA-harness. Den første
   auditen målte omtrent 11 900 nye/endrede linjer og 87 endrede sporede filer;
   dette er i seg selv en recoverability-risiko.
2. Fullfør og review den avbrutte sikkerhetsmigreringen i
   `test_perf_probe_safety.py`. Ingen delvis migrert testfil får sertifisere
   ytelsesharnessen.
3. Kjør kontrakttestene for controller, builder, reaper, safe paths og tmux.
4. Bekreft at ingen gammel server, klient, port, tmux-session eller registry
   kan påvirke neste resultat.
5. Oppdater `CURRENT_STATE`, `NEXT_ACTION`, `KNOWN_FAILURES` og testdiscovery
   etter dagens faktiske kildekode.
6. Kjør billig kadens i riktig rekkefølge: validatorer, bygg, målrettede
   testpar og deretter fast headless-samling.
7. Ingen manuell sletting av `BLOCKED`/`.stale` brukes for å skape grønn
   status; bare controllerens godkjente fullflyt kan publisere ny sannhet.

### Exit-krav

- Alle harness-kontrakttester grønne på dokumenterte hashes.
- Ingen prosess-, temp-, port- eller tmux-rester.
- `quick` grønn.
- `doctor` grønn.
- `fast` grønn.
- Faktisk GameTest-discovery er frosset og dokumentert.
- Den gamle forventningen 414 er ikke blindt beholdt. Statisk audit tilsier
  sannsynligvis 416 normale tester, men tallet oppdateres bare etter én
  autoritativ Gradle-discovery med eksakt success-linje og null failure-token.
- Normal GameTest-suite og den separate active-Blessing-suiten kan ikke
  maskere eller krympe hverandre.
- Alle kjente røde resultat har en eier og reproduksjon.

---

## GATE 2 — SERTIFISER BLESSINGS OG JOURNEY-GRUNNLAGET

**Mål:** Bevise dagens nye hovedsystemer før mer ombygging lander.

### Blessing-matrise

- Alle tre Blessings × settler/plaque × rang I/II/III.
- Survival-forbruk, creative-ikke-forbruk og rang-III-ikke-forbruk.
- Personlig rang, bygningsrang og sterkeste-av-to-regelen.
- Warden-skade, Hearthward-reduksjon og Thorned Roads-slow med målte tall.
- Cross-settlement-tillatelse og avvisning.
- To samtidige spillere mot samme mål.
- Stale revision, replay, ugyldig slot, feil hånd, feil mål og feil avstand.
- Save/load, chunk unload, reconnect og full restart.
- Korrupt, fremtidig, duplisert og out-of-range NBT.
- Inspection refresh, partikler, lyd og `BLESSING_RECEIVE` uten duplikat.

### Journey-v1-matrise

- Fresh settlement går gjennom dagens fire Lumberer-steg i rekkefølge som et
  kompatibelt Journey-v1-grunnlag. Dette er ikke bevis for full tutorial.
- Feil Hearth, falsk building link, feil profession og falsk loggleveranse
  fullfører ingenting.
- Skip krever servergodkjenning og bekreftelse.
- Skip etter delvis progresjon viser korrekt historikk.
- Gammel verden migrerer til vedtatt tilstand.
- Fremtidig eller korrupt Journey-data settes i karantene.
- Settlement-grunnleggelse er atomisk ved feil på founder 1/2/3.

### Exit-krav

- Hele målrettede matrisen grønn to ganger på samme fingerprint.
- `blessing-restart` grønn og beviser data etter ekte prosessomstart.
- Restart-beviset inneholder én chunk unload/reload, to rene prosessomstarter
  og tre faktiske viewer joins med de samme autoritative identitetene.
- Transaksjonsmatrisen viser null tap/duplisering gjennom 100 gjentatte
  bindinger og 20 samtidige tospillerforsøk.
- Ingen P0/P1 fra Tobias' bruk av systemene.
- Norsk/engelsk tekst og live UI samsvarer med faktisk effekt.
- Ingen ny global tick-scan eller ubundet datastruktur.

---

## GATE 3 — BYGG DEN FØRSTE VERTIKALE SETTLEMENT-LOOPEN

**Mål:** Erstatte gratis jobb/utstyr og utydelig onboarding med én komplett,
serverautoritativ kjede som kan testes fra ny verden til første raid.

### Implementeringsslicer i bindende rekkefølge

1. **Felles kataloger:** stabile BuildingType-, Development-, Profession-,
   JobContract- og BuildingDescription-ID-er. Alle aktive profesjoner må ha en
   eksplisitt kontrakt; ingen implicit default.
2. **Development-grunnstamme:** Hearth → Mayor → House/Lodging → Lumber Camp →
   Farmhouse → Warehouse → Tavern → First Watch. Låste planer avvises på
   serveren selv om itemet finnes. Eksisterende Research forblir en separat
   effektivitetsmekanikk.
3. **Fysisk jobbautoritet:** Mayor handler Job Emblems mot ekte varer.
   Profession, workplace og emblemautoritet lagres separat. Fresh hires får
   aldri conjured tool, armor eller ammo.
4. **Ekte settler-inventory:** Shift + høyreklikk med tom hånd åpner en
   serverautoritativ container med equipment, bag, durability og requeststatus.
   Vanlig høyreklikk beholder profile/inspection.
5. **Request-grunnmur:** settlement-eid persistent ledger, typed
   equipment/input/output requests, eksakte stack-reservasjoner og fysisk
   source chest → Courier bag → target-flyt.
6. **Første Lumberer-loop:** Work Scepter med to 3D-hjørner, ekte axe-request,
   ekte durability, zone-bundet tree claim og output til Lumber Camp-storage.
7. **Warehouse/Courier-loop:** global requestliste, `Pickup now`, én fysisk logg
   i ryggsekken og levering til Warehouse uten teleport, dupe eller itemtap.
8. **Farmer/mat-loop:** Farmhouse-zone, ekte hoe/seeds, output til workplace,
   Courier-henting og en målbar to-døgns spiseklar matbuffer.
9. **Bolig/Tavern/rekruttering:** permanent femstegsoversikt med kapasitet,
   Tavern, pris, mat og traveler. Ankommet traveler krever en eksplisitt,
   idempotent Recruit-handling.
10. **First Watch:** Guard Emblem, fysisk våpenrequest og Command Staff med
    Stand Post, Patrol og senere Tower Post. Kampalarm kan overstyre midlertidig;
    vakten returnerer til ordren etterpå.
11. **Journey v2:** sju kapitler og små stabile milepæler. Bare ett tydelig mål
    vises om gangen; fullføring observerer committede gameplay-transaksjoner og
    gir aldri items, tech eller belønning gratis.
12. **Readiness-ankret første raid:** den rullede minstegrensen natt 4–7
    beholdes, men angrepsnatten kan ikke komme før den autoritative
    beredskapssjekklisten består og varslingstiden 1–2 netter er bevart.
13. **Strategisk overgang:** etter første raid peker Journey til det første
    synlige valget mellom tre doktriner. Senere åpnes fem spesialiseringer;
    dette utvider ikke tutorialen før first-raid-loopens bevis er grønt.

### Development- og presentasjonskontrakt

- Den felles stammen er lineær nok til å lære systemet uten falske valg.
- Første splitt er Defence, Production og Community/Trade.
- Senere fem spesialiseringer skal vokse naturlig fra disse og kan ha
  krysskrav; spilleren kan ikke kjøpe alt samtidig.
- En aktiv doktrine har både tallfestet fordel og reell kostnad. Bytte skjer hos
  Mayor mot fysisk betaling og cooldown; permanente unlocks slettes aldri.
- Hver node viser kort bygningsbeskrivelse, hva den gjør, hva den åpner, pris,
  prerequisites og hvilke valg den utsetter eller stenger mens doktrinen er
  aktiv.
- Samme lokaliserte korte beskrivelse vises på Build Plan-itemet. Tekst kan ikke
  hardkodes separat i to skjermer.

### Exit-krav

- Full fresh-survival Journey kan gjennomføres uten kommando, wiki eller
  creative og avsluttes først etter rapporten fra første raid.
- Ingen profession assignment lager eller teleporterer utstyr.
- Lumberer og Farmer legger output i linked workplace storage, aldri direkte i
  Hearth.
- Minst én equipment-request og én output-request fullføres av samme fysiske
  stack gjennom Courier-bagen, inkludert save/restart i `IN_TRANSIT`.
- Recruitment viser eksakte blockers og øker population én gang ved eksplisitt
  Recruit.
- Guard følger Stand/Patrol, returnerer etter alarm og beholder ordre gjennom
  restart.
- Journey, Development, Emblems, Requests, Work Zones og Guard Orders består
  migration, replay, concurrency og cross-settlement-tester.
- UI, motion, lyd og klientytelse for denne loopen består sine respektive
  premiumporter før slicen kalles spillbar.

---

## GATE 4 — BEVIS HELE CORE-LOOPEN

**Mål:** Modden skal fungere som spill, ikke bare som isolerte funksjoner.

### Hovedløp som må spilles og testes

1. Fresh survival → Hearth → tre founders.
2. Founding Journey → Lumber Camp → Lumberer → første logg.
3. Tavern-gate → traveler → chest-true rekruttering.
4. Frosset profesjonsroster → hvert yrke arbeider gjennom normal AI.
5. Warehouse/courier → alle fire ruter og alle failure paths.
6. Matkjede → åtte settlers i tre spilldøgn.
7. Watchtower/guard/archer → ammo, target acquisition, specials og forsvar.
8. Raid → telegraph, angrep, mulig skade, seier/tap, repair og Blessing-reward.
9. Save/reload midt i traveler, arbeid, levering, raid og inspeksjon.
10. Alle demo-bygninger enten virker eller er tydelig merket som ikke aktive.
11. Founding Journey fortsetter som Sagaoppdrag gjennom mat, courier, taverna,
    rekruttering, raidforberedelse, redning, reparasjon og Blessing-valg.
12. To-døgns spiseklar matgate, trekull = 2 fuel og valgfri ale-bonus virker
    eksakt som eierdirektivet fastsetter.
13. Sulttrappen når svekket → kritisk → downed/redning → mulig død/grav/sorg
    uten stille overganger.
14. Spilleren kan gi få, tydelige guard-ordrer mens vakt-AI fortsatt handler
    autonomt.
15. Raid → Blessing gjennomføres to ganger i samme save.

### Exit-krav

- Ingen silent stall i første to timer.
- Alle aktive yrker er chest-true bevist.
- Alle fire courier-ruter er bevist med konservering.
- Matsolvens og raidforløp har loggede målinger.
- Ingen kjent P0/P1 i hovedloopen.
- En faktisk klientfilm viser starten, første levering og ett kamp-/raidøyeblikk.
- Hele vertikalloopen kan gjennomføres i survival uten admin-kommando eller
  ekstern wiki.

---

## GATE 5 — SAVE, NETTVERK OG FLERSPILLER

**Mål:** Gjøre en verden trygg nok til at Tobias og venner kan investere tid i
den.

### Arbeid

- Migrer representative eldre save-fixtures og sammenlign før/etter-state.
- Restart midt i alle permanente eller reserverte operasjoner.
- Test chunk unload/reload og dimension-retur.
- Test disconnect/reconnect med åpne eller gamle skjermer.
- Test to spillere på samme settler/plaque/Hearth.
- Test to til fire spillere i én delt settlement gjennom Journey, logistikk,
  raid og Blessing.
- Test permissions og cross-settlement-handlinger.
- Fuzz/bounds-test alle nye payloads og NBT-dekodere.
- Bekreft at avvist handling ikke konsumerer item eller endrer revision.
- Dokumenter recovery dersom en save blir satt i karantene.

### Exit-krav

- Null datatap i støttede migrasjoner.
- Null itemdupe eller dobbelt commit.
- Null autoritativ handling basert bare på klientpåstand.
- Alle restart-, reconnect-, replay- og concurrency-tester grønne.
- En sikker backup- og rollback-prosedyre er prøvd, ikke bare skrevet.

---

## GATE 6 — YTELSE OG SKALERING

**Mål:** Bevise at premium-presentasjon og permanente systemer ikke gjør en stor
landsby tung.

### Rekkefølge

1. Sertifiser selve ytelsesharnessen med positive og negative mutanter.
2. Mål tom baseline-verden med samme seed og JVM-oppsett.
3. Mål eksakt 1, 25, 50 og 100 settlers.
4. Kjør i tillegg eierens aktive 40- og 50-settler-referansescenario.
5. Skill mellom idle settlement og aktivt arbeid.
6. Kjør Blessing-hot-path med 1/25/50/100 mål og alle tre Blessings.
7. Kjør plaque-søk, inspeksjonsviewers, courier, pathing og raid separat.
8. Mål hver hovedskjerm som fire faser mot identisk world-view: pre-open,
   open-transition, minst 30 sekunder steady-open og 30 frames close-recovery.
9. Skill renderkost, nettverk, tekstlayout, allokering, shader/blur og faktisk
   world-render fra hverandre; test både kald og oppvarmet første åpning.
10. Rapporter average, median, p95, p99, verste spike og minneutvikling.
11. Profilér bare dersom målingen viser et problem; optimaliser rotårsaken og
   kjør identisk matrise på nytt.

### Harde budsjetter

- 25 settlers: gjennomsnitt ≤ 45 ms.
- 50 settlers: gjennomsnitt ≤ 45 ms.
- 100 settlers: gjennomsnitt ≤ 50 ms.
- Eksakt population og settlement-medlemskap må bevises i loggen.
- Ingen prosessrester eller manglende samples tillates.
- P99 og baseline-differanse må rapporteres selv når absoluttbudsjettet består.
- Oppvarmet UI steady-state: median frame-time-regresjon ≤ 5 % og p95 ≤ 10 %
  mot identisk kameravinkel uten UI.
- Ingen modskapt open/interaction/close-frame over 33,3 ms etter warm-up.
- Etter close skal median og p95 være tilbake innen 5 % av pre-open-baseline
  senest etter 30 frames.
- Render-loopen gjør null ressurs-I/O og null autoritativ snapshotbygging.

### Premium-benchmark før sammenligningspåstand

- 50 settlers + 50 plaques, 15 minutter: median-regresjon ≤ 5 %, p95 ≤ 8 %,
  vedvarende FPS-regresjon ≤ 5 %, null idle Blessing-pakker/partikler.
- 100 settlers + 100 plaques, 30 minutter: median-regresjon ≤ 10 %, ingen
  ubundet kø/cache, og minnet vender tilbake til stabilt bånd etter GC.
- Kontrollert raid: profilerbevis uten global building-scan eller nye
  path/retry-forespørsler fra Blessing-effekten.
- Frem til disse bevisene finnes brukes formuleringen «designet for å
  overgå», aldri «bevist bedre enn referansemoddene».

### Exit-krav

- To gyldige og sammenlignbare matriser på samme fingerprint.
- Alle absolutte budsjetter består.
- Ingen ubegrenset vekst i registries, viewers, reservations eller caches.
- Ingen ny global per-tick scan.
- Hearth, Plaque, Journey, Development, Request, Settler Inventory og Tavern
  består UI-mikromatrisen uten vedvarende FPS-fall eller retained state.
- Resultat og reproduksjonskommando ligger i QA-artifacten.

---

## GATE 7 — PREMIUM UI OG TEKSTURER

**Mål:** Gjøre hele modden visuelt sammenhengende og tydeligere enn
referansene på Hearthsteads egne kjernehandlinger.

### UI-rekkefølge

1. Frys design tokens: palett, rammer, spacing, typografihierarki, ikoner,
   statusfarger og motion-timing.
2. Gjør `ui_preview --all` sann: alle aktive specs må faktisk omfattes, ikke
   bare plaque-hire-filene. Bygg alle tilstander som offline previews før
   Java-endringen.
3. Hearth shell, Journey v2 og den permanente «Neste settler»-oversikten.
4. Development Tech Tree med felles stamme, tre doktriner og fem senere
   spesialiseringer; nodekort viser fordel, pris, krav og tradeoff før valg.
5. Mayor Emblem Catalog og fysisk jobb-/workplace-binding.
6. Plaque med bygningsbeskrivelse, requirements, workplace storage, work zone,
   request/pickup-kontroller og fysisk Blessing-presentasjon.
7. Ekte Settler Inventory og separat profile/inspection med rolle, traits,
   behov, equipment, durability, requests, buffs og permanente Blessings.
8. Global Request Ledger, Warehouse/Courier og Guard Command-visning.
9. Research, Handbook og Storage.
10. HUD/varsler og look-at informasjon dersom de fortsatt gir mer verdi enn
   kompleksitet etter core-playtesten.
11. Test tom, normal, full, blokkert, manglende tillatelse, lang norsk tekst,
   lang engelsk tekst og stale-state.
12. Lukk videofunnet `PT-2026-08-27-01`: tavern-blocker skal wrappe i eget
   materialpanel og aldri tegnes over verdenen.
13. Lukk 20:11-funnene: avkuttet Tavern-beskrivelse, svak done-state,
    bakgrunnspanel som konkurrerer med aktivt innhold, for stor name/activity-
    overlay og Journey som viser en vegg i stedet for ett konkret mål.
14. Hver skjerm får en eksplisitt renderkost-review: statisk tekstlayout caches,
    lister sorteres ved snapshotendring, teksturer bindes som ressurser og
    widgets/layout gjenoppbygges aldri hver frame.

### Teksturrekkefølge

1. Palett- og materialgrunnlag.
2. Hearth/stein/tre/metall.
3. Plaque, papir, messing og segl.
4. Items og produksjonsressurser.
5. Settler-skins og tydelig yrkessilhuett.
6. Partikler, statusikoner og Blessing-effekter.

### Exit-krav

- guiScale 2/3/4 godkjent på alle aktive skjermer.
- Norsk og engelsk godkjent ved både 1280×720 og 1920×1080.
- Blessing-valget har komplett 24-capture-matrise: EN/NB × guiScale 2/3/4 ×
  begge oppløsninger, uten overlap, død kontroll eller uleselig verdi.
- Ingen avkuttet/overlappende kontroll eller falsk status.
- Alle 33 BuildingType-er viser samme korte NB/EN-beskrivelse på Build Plan og
  Development-node, med automatisk nøkkel- og wrap-validering.
- UI-ytelsesbudsjettet i Gate 6 består per hovedskjerm.
- Før/etter-par per materialfamilie.
- Side-om-side-vurdering mot navngitt referanse per hovedskjerm.
- Tobias' godkjenning av Hearth, Plaque, Settler og Blessing-visningen.
- De tre seglene gjenkjennes 3/3 i grayscale uten fargehjelp, og bindingen er
  lesbar på 4, 12 og 24 blokker etter at engangspartiklene er borte.

---

## GATE 8 — PREMIUM ANIMASJON OG LYD

**Mål:** Hver viktig rolle og handling skal leses uten tekst og føles fysisk
forankret.

### Prioritet

1. Frys et motion language: stance, anticipasjon, kontakt, hold, recovery,
   locomotion-lag, prop ownership og maksimal overlay-vekt.
2. Bygg Lumbererens CHOP, pickup/stow og laden walk på nytt. Pickup skal være en
   kort kontaktbevegelse, ikke helkroppskollaps; faktisk itemflyt skjer på
   kontakt-ticken.
3. Fjern Courierens usynlig-kasse-pose. Lasten ligger i ryggsekk, gange skaleres
   med count og vekt, normal arm-swing bevares og bare én diskret stropphånd kan
   brukes ved tung last.
4. Guard får sann main/offhand, stand, patrol, tower, alarm-override og return
   til ordre. Ingen Guard-clip godkjennes uten at kontrollen faktisk virker.
5. Tavern/Innkeeper får enten en ekte workstation-bundet handling med synlig
   prop eller en god neutral idle; generisk armvifting fjernes.
6. Farmer får fysisk såing, tending, harvest, pickup og workplace-deposit med
   ekte hoe/input/output.
7. SALUTE og permanent Blessing-mottak.
8. Guard/shield-settet får sann offhand-presentasjon; HUNTER_LOOSE får riktig
   bow-arm; ARCHER får terminal aim-hold og eget loose-event i stedet for
   sword/guard-pose.
9. Sugar-cane plant/harvest og senere Innkeeper, Scholar, Miller og Brewer får
   egne lesbare handlinger.
10. Erstatt åpenbare shear/fish/hunt-stand-ins.
11. Fjern intern lydforsinkelse når goal-koden allerede trigger på kontakt.
12. Behold delt hammerbevegelse bare der fysisk handling faktisk er lik;
   differensier med prop, tempo, lyd og kontekst.
13. Nye aktivitets-ID-er legges kun append-only etter eksisterende wireverdier.
    Salute og bow-loose er engangsevents, ikke permanente activities.
14. Serverens lydklokke og klientens phase-offset må bruke én beviselig modell;
    de kan ikke drive nesten en hel loop fra hverandre.
15. Props skal være sanne: ingen usynlig tankard, bok, veiv, åre eller feil
    redskap bare for å få et penere klipp.

### Lydstandard

- Bygg et originalt Hearthstead-bibliotek for UI, bag/stropp, tre, jord,
  verktøy, handoff, request, alert, raid og Blessing; åpenbare vanilla-stand-ins
  er bare midlertidige og kan ikke leveres.
- Hyppige handlinger får flere varianter, kontrollert pitch/volume-jitter og
  anti-repeat. Crowd-cooldown hindrer at 20–100 workers lager lydgrøt.
- Lette, tunge, tre-, metall- og tekstillaster skal ikke bruke samme pickuplyd.
- UI-cues er korte, lavmælte og kan aldri spilles per render/tick eller hver
  snapshot-refresh.
- Hver semantisk kontakt har én eier: server/gameplay-event eller autoritativ
  client one-shot. To klokker får aldri trigge samme lyd.

### Exit-krav

- Statisk animasjonskontrakt: 0 feil og ingen aktiv NO-GO.
- Hvert aktivt klipp er trigget på riktig kropp i en ekte klient.
- Verktøy og props er synlige i verifikasjonsrender.
- Ingen uønsket miksing eller arm gjennom torso i kritiske handlinger.
- Impactlyd treffer kontaktframe én gang.
- Hver kritiske lyd har A/B-bevis mot den underkjente versjonen, minst tre
  variasjoner der hendelsen repeteres ofte, og en crowd-test med 50 settlers.
- Film med lyd viser alle kritiske jobb-, kamp- og Blessing-klipp.
- Ingen stuck pose gjennom 50 repetisjoner av kritiske one-shots.

---

## GATE 9 — BALANSE, LESBARHET OG LANGSPILL

**Mål:** Gjøre systemene meningsfulle etter at de er teknisk stabile.

### Arbeid

- Mål faktisk tid til Hearth, Lumberer, Tavern, åtte settlers og første raid.
- Mål mat, effort, logistikk-throughput, recruit cost og repair cost.
- Test Blessing-rangene i tidlig, middels og raid-tung settlement.
- Unngå at én Blessing alltid er riktig valg.
- Bekreft at Thorned Roads ikke skaper pathing- eller raid-softlock.
- Bekreft at Hearthward ikke gjør settlers praktisk udødelige.
- Bekreft at Warden's Oath ikke overskrider kampens ønskede time-to-kill.
- Test nye spillere uten forklaring: UI skal svare på «hva skjer», «hvorfor»
  og «hva gjør jeg nå».
- Oppdater alle tooltips med ekte tall og vilkår.

### Exit-krav

- D1–D17 og R1–R39 har sporbare bevis eller eksplisitt godkjent utsettelse.
- Ingen progresjons-softlock i testløpet.
- Ingen dominant Blessing uten tydelig kostnad eller situasjonsulempe.
- Ingen presentert funksjon som i praksis er tom.
- Tobias godkjenner tempoet i første spilløkt og ett lengre settlement-løp.

---

## GATE 10 — RELEASE-KANDIDAT OG VENNE-BETA

**Mål:** Levere en versjon vi kan forsvare som stabil, ikke bare lovende.

### Freeze- og sertifiseringsrekkefølge

1. Frys funksjoner og del kildekoden i reviewbare integrasjonsområder.
2. Uavhengig bug-review og sikkerhetsreview.
3. `quick` og alle målrettede matriser.
4. GameTests og behavior.
5. Dedicated server.
6. Blessing restart og generell persistens.
7. Performance.
8. Client, playtest og menneskelig visual review.
9. `full` første gang.
10. Ingen kildekodeendring.
11. `full` andre gang på identisk fingerprint.
12. `gate` med green streak ≥ 2.
13. Bygg og hash release-JAR.
14. Ren installasjon og ny verden.
15. Støttet gammel verden på kopi, reconnect og serverrestart.
16. Kuratert kompatibilitetstest for rent NeoForge-oppsett, JEI/EMI,
    Xaero-markører og utpekte vanlige ytelsesmoder.
17. Tobias-test.
18. Begrenset venne-beta.

### Exit-krav

- Ingen åpen P0 eller P1.
- P2-liste er kort, ærlig og påvirker ikke save eller core loop.
- `full` ×2 og `gate` grønn på samme fingerprint.
- Ren installasjon består.
- Gammel støttet verden består på kopi.
- Release-JAR, SHA-256, manifest, changelog, quick-start, backup-råd,
  kjente begrensninger og rollback-JAR er samlet.
- Tobias har godkjent kandidatversjonen før den deles med venner.

---

## 5. PARALLELLE ARBEIDSSTRØMMER

Mange agenter kan brukes, men parallelitet er bare en fordel når filene og
ansvaret er separert.

### Strøm A — Gameplay og save

- Blessings, Journey, core loop, jobs, logistics, raid og balance.
- Eier gameplay-Java og målrettede GameTests i sin arbeidsperiode.

### Strøm B — QA, ytelse og release

- Harness, restart, dedicated, performance, artifacts og gate.
- Gjør ingen gameplayendring uten separat reproduksjon og koordinering.

### Strøm C — UI og teksturer

- Specs/previews først, deretter skjermer og assets.
- Visuell endring må ha screenshotbevis og målbar layoutkontroll.

### Strøm D — Animasjon og lyd

- Klipp, reachability, props, timing og sound contacts.
- Endrer ikke AI-/damage-tick uten koordinert test og samme commit.

### Koordinatorens ansvar

- Holder én aktiv hovedplan og én sann status.
- Fordeler disjunkt fileierskap.
- Sekvenserer delte SEAM-filer.
- Kjører integrasjonstester når ingen agent er midt i en relevant fil.
- Reviewer alle leveranser før integrasjon.
- Gir aldri «ferdig»-status uten artifact eller visuelt bevis.

### SEAM-filer — bare én skriver om gangen

- `lang/en_us.json` og `lang/nb_no.json`
- `HsUi.java`
- `SettlerAnimations.java`
- `SettlementManager.java`
- `Settlement.java`
- `HearthNetwork.java`, `PlaqueNetwork.java`, `SettlerNetwork.java`
- `validate_assets.py`
- `tools/hearthstead-qa` og felles harness-biblioteker

---

## 6. STANDARD ARBEIDSFLYT FOR HVER ENDRING

1. **Observer:** virkelig feil, måling eller vedtatt krav.
2. **Reproduser:** minste pålitelige reproduksjon og forventet resultat.
3. **Avgrens:** navngi filer, systemgrenser, risiko og rollback.
4. **Test først:** legg til eller identifiser testen som vil feile.
5. **Implementer:** minste komplette rotårsaksfiks.
6. **Lokal kontroll:** validator/bygg/målrettet test.
7. **Integrasjon:** relevant GameTest/behavior/restart/performance.
8. **Visuell kontroll:** screenshot eller film når spilleren kan se endringen.
9. **Review:** bug-, sikkerhets- eller ytelsesreview etter risiko.
10. **Dokumenter:** status, bevis, kjent begrensning og neste handling.

Ingen av følgende godtas som ferdigbevis alene:

- «Koden ser riktig ut.»
- «Det kompilerer.»
- «JSON-en er gyldig.»
- «Screenshot finnes.»
- «Agenten sa PASS.»
- «Det virket på en eldre commit.»

---

## 7. PROGRESSRAPPORTERING

Hver meningsfulle oppdatering skal bruke samme format:

- **Ferdig:** hva som faktisk er landet og bevist.
- **Status nå:** aktiv gate og prosentfri, konkret tilstand.
- **Neste:** én hovedhandling og eventuelle parallelle underoppgaver.
- **Blokkert/risiko:** hva som kan stoppe fremdrift.
- **Trenger Tobias:** bare spørsmål som faktisk endrer produktet eller krever
  en subjektiv dom.

Statusord:

- **IKKE STARTET** — ingen arbeid eller bevis.
- **PÅGÅR** — arbeid finnes, men gaten er ikke oppfylt.
- **TIL REVIEW** — implementert, venter på uavhengig kontroll.
- **TEKNISK GRØNN** — automatiske relevante tester består.
- **VISUELT GODKJENT** — sett i riktig klienttilstand og godkjent.
- **GODKJENT** — alle exit-krav og bevis finnes.
- **BLOKKERT** — konkret ytre hindring eller nødvendig eierbeslutning.

---

## 8. NÅ / NESTE / SENERE

### NÅ — forpliktet arbeid

1. Bevar og triager alle checkpoint-videoer og direkte eierobservasjoner.
2. Mål og lukk UI-FPS-fallet som P1; ingen kosmetisk forklaring godtas uten
   frame-time-bevis.
3. Fullfør QA-/ytelsesharness-migreringen og gjenopprett ærlig baseline.
4. Sertifiser Blessings og dagens Journey-v1-grunnlag uten å kalle tutorialen
   ferdig.
5. Implementer Gate 3 vertikalt: Development, Building Descriptions, Job
   Emblems, ekte Settler Inventory, Requests, Work Zones og Guard Commands.
6. Gjennomfør full Journey v2 fra fresh survival til readiness-ankret første
   raid og post-raid-valget mellom tre doktriner.
7. Bevis hele eksisterende core loop og utvid deretter til aktive yrker.
8. Sertifiser save, nettverk, flerspiller og ytelse.
9. Premium-ombygg UI, teksturer, animasjon og lyd med før/etter-bevis.
10. Full ×2, gate og release-kandidat.

### NESTE — etter godkjent release-kandidat

- Herder og Fisher slik at eksisterende Pasture/Fishery får ekte arbeid.
- Building tiers gjennom fysisk furnishing.
- Morale breakdown.
- Settlement-border visualization.
- Healer/downed-not-dead dersom rescue-loopen kan gjøres chest-true og lesbar.
- Traditions/Hearth tiers og dypere Blessing-synergier etter målte balansedata.

### SENERE — strategisk retning, ikke release-scope

- Children, school og familiesystem.
- Bard, festivaler og saga-performance.
- Caravans, NPC-settlements, rivaler og flere factions.
- Seasons, winter, sickness, wolves, kidnapping og rescue camps.
- Full nemesis-utvikling og saga chronicle.

### Eksplisitt ikke nå

- Ingen total omskriving eller nytt modprosjekt.
- Ingen builder/schematic-system der settlers konstruerer for spilleren.
- Ingen abstrakt token-/emeraldvaluta. Fysiske Job Emblems er
  jobbauthorization, ikke en usynlig økonomi.
- Ingen stor yrkesspredning før aktive yrker er premium og bevist.
- Ingen tilfeldig tuning av raid, mat eller Blessings uten måling.
- Ingen ny UI-arkitektur uten målt rotårsak, migreringsplan og bedre benchmark.

---

## 9. NESTE EKSAKTE HANDLING

1. Fullfør video-/motion-/UI-ytelsesauditene og registrer alle 20:11-funn i en
   tidskodet playtestrapport.
2. Oppdater `CURRENT_STATE` og `NEXT_ACTION` slik at ingen eldre handover peker
   mot den underkjente firestegs-Journeyen som sluttmål.
3. Fullfør `test_perf_probe_safety.py`-migreringen og kjør den billige
   baselinekjeden `quick` → `doctor` → `fast` uten å slette stale/BLOCKED for
   hånd.
4. Når baselinen er ærlig: land kun additive kataloger og tester for
   BuildingDescription + JobContract først. Ikke fjern gratis gear før den
   fysiske request-/inventory-erstatningen er klar i samme vertikale slice.
5. Bygg første spillbare Lumberer-sekvens gjennom ekte emblem, inventory,
   axe-request, Work Zone og workplace storage; verifiser den før Farmer,
   Courier, Tavern og Guard kobles på.

**Aktiv gate:** GATE 0 — TOBIAS' LIVE CHECKPOINT-TEST.  
**Neste gate:** GATE 1 — FRYS ARBEIDSTREET OG GJENOPPRETT EN ÆRLIG BASELINE.
