# PLAN — LOGISTICS-OVERHAUL: logistikk som leses, velger rett og er kul å se på

Kilde: eierens ordre 2026-08-26 — «Overhaul courier systemet og lagringen osv lag et kjempe kult system så lag de slik at logistikken gir mening.» OVERHAUL, ikke omstart: de fire rutene, prioritetsstigen, Mode-maskinen, reservasjonsledgeren, den deriverte varehusindeksen og vektklampen BEHOLDES — de er anti-MineColonies-riktige (CourierWorkGoal.java:44–64). Tre mangler rettes: stopp som kan LESES (ANKER_ANALYSE.md:62–63), jobbvalg som gir MENING (first-hit-skannet, :1645–1690), og et teater som gjør arbeidet FILMBART.

## Systemet etter overhaulen
Behov oppstår som i dag (Production-underskudd, mat-terskel, surplus over keep-back) pluss to nye kilder: deklarerte behovstabeller (SupplyNeeds — Architects Study først, lukker P9/BALANCE_PITCH.md:150) og fraktkassa. Jobber PLUKKES med score INNENFOR hvert prioritetstrinn — stigen CRAFTER_RESTOCK → FOOD → COLLECTION → CONSOLIDATION består urørt: `score = alvorlighet / (1 + avstand/16)`, alvorlighet STARVED=8 (0 input eller `Production.starvedForFuel()` — endelig konsumert), LOW=4, SURPLUS=2, TIDY=1; aging: +1 alvorlighet per hoppet skannesyklus, cap 8. Ingen kø, ingen request-objekter — beslutningen lever ett skann. Full destinasjon scores 0 og setter grunn — aldri en tur dømt ved avgang. HVERT stopp setter en typet, synced StopReason VED SIDEN AV debug-strengen (recordRouteFailure har sju kallsteder utenfor courieren — strengen røres ikke). Levering på 32-ticks-metronomen instrumenteres med lyd og gest. Kjerra (smedvare) hever DATA_CARRY_CAPACITY 8→24 og vektbudsjett 16→48 — sømmen er ferdig (SettlerEntity.java:86–89, clamp 1..512), redskapet RENDRES (Banished-lærdommen: kapasitet er et redskap, ikke et tall).

## Slices (hver spillbar og filmbar alene)

**S1 — LESBAR STANS (anti-synden først).** Enum `logistics/StopReason` (WAITING_INPUT, CHEST_FULL, HEARTH_FULL, NO_WAREHOUSE_SPACE, RESERVED_BY_OTHER, RESTING_AFTER_FAIL, NO_PATH, NONE) settes overalt der courieren i dag skriver debug-strengen ELLER returnerer stille null (findFoodJob:1776–1782 forbys å tie). Synced byte på SettlerEntity, oppdatert maks hvert 20. tick. Courier: ikon + én diegetisk linje ved blikk/crouch («Hviler etter feilet tur til Smia — kista full (14s)», backoff-nedtelling synlig). Bygning: statuslampe på plaketten — grønn/gul/rød med årsak, drevet av tally + starvedForFuel, null ny skanning. Ingen ruteendring; hele suiten grønn uendret. **Akseptkriterium (må filmes): 30-sekunders-flyten** — se stille courier → les årsak → gå til bygget → rød lampe → tøm kista → grønn lampe, courier i bevegelse. Uten wiki, kommando eller meny.

**S2 — BESTE JOBB + FORSYNING.** Scoren over erstatter listerekkefølgen i findRestockJob/findCollectionJob/varehusvalg — innenfor trinnene, stigen består. starvedForFuel gir STARVED. Ny FORSYNING-kilde i restock-trinnet via `logistics/SupplyNeeds` (deklarert per bygningstype; Architects Study: 4 papir + prosjektmateriell); henter aldri varehuset under gulv 8 per vare. Filmbart: courieren velger den sultne smia to blokker unna over surplus på andre siden av kartet; første papirleveranse til studiet.

**S3 — TEATERET + KJERRA.** Lyd/partikkel/håndgest per stack på metronomen (SORT_PERIOD=32/SORT_MOVE_TICK=16; i dag én playSound, :1589). Lasteplass-sekker ved output-kister over keep-back (1 sekk per 8 over, maks 4) og synlig fyllgrad i varehuset — ALT ren klientrender av tally+revision (WarehouseStorage.java:53), aldri egne containere. Kjerre-item: kapasitet 24/budsjett 48, rendres i hendene ved tung last, perLoad aldri <1 (anti-KF-023). Filmbart: sekkehaugene ved bakeriet som krymper stack for stack; kjerra som triller til varehuset.

**S4 — FRAKTBOKA + FRAKTKASSA.** `logistics/RouteLog` (ring-buffer per rute: turer, gåticks, vekt — telemetri fra FAKTISKE turer, aldri simulering, aldri persistert som sannhet). Fraktboka-fane i StorageScreen: read-only, verste rute først, courier-minutter/dag = turer × gåticks — hver rad peker på blokker spilleren kan flytte. Fraktkasse-blokk: spillerens drop-kiste som konsolideringsruten tømmer (spilleren som node — MineColonies' mest elskede mekanikk, chest-true). Filmbart: steinruten øverst, spilleren flytter masonen, raden synker; courieren tømmer fraktkassa.

## Fil- og eierskapskart
FRITT (disjunkt fra TAVERN-GATE): CourierWorkGoal, SettlerEntity (StopReason-sync, carry), SettlerModel + kjerre-render, Weight, WarehouseStorage/Index, TidyWarehouseGoal, StorageScreen, Production (kun LESE starvedForFuel), gametests. NYE: logistics/StopReason, logistics/SupplyNeeds, logistics/RouteLog, lampe-/lasteplass-renderer, fraktkasse-blokk, FreightBook-fane. **TAVERN-GATE eier:** HearthBlockEntity, SettlementManager, HearthMenu/Screen, HearthsteadCommand — logistikken bare LESER terskler/populasjon, og HearthsteadCommand trenger NULL endring (:684 leser strengen som består; StopReason er tillegg). Plakett-plasseringsestimatet (LOGISTICS.md steg 5) PARKERES til etter TAVERN-GATE.

## Testplan
- S1: `everyGiveUpPathRecordsAReason` (vokter: hver null-retur/restRoute setter reason ≠ NONE — stille stopp er byggefeil); `aFullHearthReadsHearthFull`; eksisterende suite grønn uendret.
- S2: `theStarvedSmithyBeatsTheDistantSurplus`; `twoEqualCraftersAlternate` (aging: ingen sulter >3 intervaller); `supplyRouteConservesItems` (eksakt sum varehus+bag+study, à la restockConservesItemsAcrossTheFullRoute); `supplyNeverStripsTheFloor` (gulv 8); ledger-assert mot dobbeltreservasjon; `restockConservesItemsAcrossTheFullRoute` GRØNN UENDRET.
- S3: render-proxy viser aldri mer enn tally (anti-dup-vokter); maks én lydevent per SORT_PERIOD per courier; kjerre: kapasitet 24/budsjett 48, perLoad ≥1; konserveringstester urørt.
- S4: `courierMinutesArithmeticIsPinned` (kjent rute ⇒ eksakt metrikk); `theFreightBookIsReadOnly` (ingen servermutasjon fra skjerm); fraktkassa: sum konservert, ingen sluk.

## Balansetall
Score 8/4/2/1 delt på (1+d/16): 16 blokker halverer; nær LOW slår fjern STARVED først forbi ~48 blokker; sult vinner alltid over kosmetikk. Aging +1/syklus cap 8: fjerne bygg når toppen på maks 7 skann. Uendret: BAG 8, BAG_BUDGET 16, keep-back 8, reserver 4 batcher, backoff 100→400 — bevist konvergente; overhaulens poeng er å gjøre dem synlige og valgene kloke, ikke flytte tallene. Kjerre 24/48 = 3× — én bakers dagsproduksjon i 1–2 turer i stedet for 5, rettferdiggjort av synlig redskap. StopReason: én byte per 20 ticks.

## Ikke-mål
Veifart, satellittdepoter/Accept-Obtain-Empty, courier-pinning, MILITARY-OUT (sømmen i SupplyNeeds står klar), ALE/servering, MILL/BREWERY-bemanning (eget Employment-fiks), plakett-estimat (TAVERN-GATE-låst). Ingen forespørselskø — en request overlever aldri turen.

## Invarianter
Chest truth: all synlighet (lamper, sekker, hyller, bok) er avledet render av tally/revision — en render-container er forbudt; konserveringstest per rute. Budsjettert skanning: scoringen gjenbruker dagens iterasjon og RESTOCK_LOOK_INTERVAL, null nye skann. Verden-først: Fraktboka er oppslag, feilsøking skjer ved å GÅ og SE. Aldri stille feil (vokter-test). Settlere bygger aldri selv.

## Dokumentrydding (del av S1-leveransen)
PLAN_A2b_SACK.md-header («not implemented» er usann), SPIDER_WEB_AUDIT («Weight foreldreløs» utdatert), FLOWS.md (+FORSYNING-rad), CourierWorkGoal-javadoc (forbudte rutenumre), LOGISTICS.md-etiketter (steg 1–2 utført; KF-027 lukket).

## DECISIONS.md
- **D-LOG-1** — hvert courier-give-up setter typet, synced, verdenslesbar StopReason; debug-strengen består; stille null er byggefeil.
- **D-LOG-2** — jobbvalg er score (alvorlighet/(1+avstand/16) + aging) INNENFOR den bestående prioritetsstigen; aldri listerekkefølge, aldri persistent kø.
- **D-LOG-3** — FORSYNING via deklarert behovstabell per bygningstype, varehusgulv 8; aldri forespørselsobjekter.
- **D-LOG-4** — all logistikk-visualisering er avledet proxy av kisteinnhold; egne containere for venting er forbudt.
- **D-LOG-5** — kapasitetsoppgraderinger er synlige redskaper (kjerre), aldri usynlige tall.

NESTE AMBISJON: veier som kjøper ned avstand (fart på DIRT_PATH), plakett-estimatet når TAVERN-GATE har landet, depotnettet — Settlers-årene, med Hearthsteads ærlighet.

---

# KRAVLISTE TIL KOORDINATOREN (rangert)

1. **S1 LESBAR STANS bemannes NÅ.** Galt: hvert courier-stopp er usynlig i spill — MineColonies-synden, klasse DISHONEST (spillet later som courieren er ledig). Bevis: lastRouteFailure leses kun av HearthsteadCommand:684; findFoodJob tier (:1776–1782). Anker: ANKER_ANALYSE.md:62–63, Factorio Bottleneck. Akseptkriterium: 30-sekunders-flyten PÅ FILM. Alvorlighet 1.
2. **StopReason skal være ADDITIV.** recordRouteFailure har sju kallsteder i seks andre goals (Hunter:258, Herder:271, Fisher:199, Lumberer:380, Summons:129/139, GoToPost:135/158) — enum-erstatning knekker dem. Akseptkriterium: null endring i HearthsteadCommand og null endring i de seks goalene i S1. Alvorlighet 1.
3. **S2 med Bs formel i As stige.** Stigen består, score innenfor trinn; vokter-tester d/e/f fra testplanen grønne. Alvorlighet 1 (eierens «logistikken gir mening»-klage).
4. **P9 lukkes i S2 via SupplyNeeds** — forskningsmateriell båret av courier, konserveringstest eksakt. Alvorlighet 2 (KNOWN_ISSUES pkt 3, årelang skam).
5. **Kjerra i S3 må RENDRES** — kapasitet uten synlig redskap avvises (Banished-fellen). Må filmes: kjerre-tur bakeri→varehus. Alvorlighet 2.
6. **Metronomen får lyd** — maks én event per SORT_PERIOD, vanilla-beat-kvalitet (minecraft-ui/vanilla-polish-ankeret). Alvorlighet 2.
7. **Fraktboka read-only med vokter-test** — meny-først er forbudt ved lov, testen (theFreightBookIsReadOnly) beviser det. Alvorlighet 2.
8. **Dokumentråten ryddes i S1** — PLAN_A2b_SACK/SPIDER_WEB_AUDIT/FLOWS/javadoc; en plan som lyver om koden er klasse DISHONEST internt. Alvorlighet 2.
9. **Rett linjereferansen :657→:684** i alt planverk før commit. Alvorlighet 3.
10. **Hver slice leverer FILM** før den meldes ferdig — grønn test er ingenting, jeg vil SE køen ved bakeriet krympe. Alvorlighet 1 på prosess.

# FØRSTE SLICE OG SEKVENSERING

**S1 — LESBAR STANS starter først.** Begrunnelse: (a) det er den eneste klasse-DISHONEST-defekten — spillet skjuler sannhet, og det fikses alltid først; (b) null ruteendring = null regresjonsrisiko mens S2-scoringen designes; (c) den er filmbar alene (30-sekunders-flyten) og hever alt annet — S2s kloke valg og S3s teater er verdiløse hvis stopp fortsatt er stumme.

**Kollisjonsnotat mot TAVERN-GATE (pågår NÅ):** TAVERN-GATE eier SettlementManager, HearthBlockEntity, HearthMenu/HearthScreen, HearthsteadCommand. S1–S4 rører INGEN av disse: StopReason er additiv (HearthsteadCommand:684 leser den bestående strengen uendret), FOOD-ruten bare LESER larder-terskler og populasjon, statuslampen bor på bygningsplakettene — ikke Hearth-UI. Skulle plakett-rendereren vise seg delt med TAVERN-GATE-filer, stoppes lampearbeidet og koordineres før én linje skrives. Plakett-plasseringsestimatet og alt som skriver til hearth/larder er PARKERT til TAVERN-GATE har landet. Parallell bemanning av S1 er trygg fra i kveld.
