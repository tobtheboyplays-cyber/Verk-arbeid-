# OWNER DIRECTIVE 2026-08-27 — SETTLER CONTROL, INVENTORY OG LOGISTIKK

**Status:** BINDENDE PRODUKTENDRING  
**Kilde:** Tobias under live checkpoint-test 2026-08-27  
**Omfang:** Rekruttering, jobb-emblems, settler-inventory, utstyr,
arbeidsområder, workplace storage, requests, courier-presentasjon og lyd

Eierens avgjørelser er:

- Settlers skal ikke få gratis verktøy eller utstyr når de får en jobb.
- De skal be om manglende utstyr; spilleren eller en courier leverer den
  faktiske gjenstanden.
- Shift + høyreklikk med tom hånd skal åpne settlerens ekte inventory.
- Courieren skal bære lasten i ryggsekken, ikke holde en usynlig eller abstrakt
  last foran kroppen mens han går.
- Alle arbeidere skal legge output i storage ved egen arbeidsplass. Courieren
  henter derfra og leverer til Warehouse.
- Courieren og settlementet skal ha en tydelig request-liste.
- Spilleren trenger et fysisk styringsverktøy for å angi hvor relevante
  arbeidere får jobbe, for eksempel hvor Lumbereren får hugge.
- Veien til flere settlers skal være synlig og forståelig i spillet.
- Dagens UI og lyd er ikke releasegodkjent og skal få en full kvalitetspass.

Dette direktivet supplerer
`OWNER_DIRECTIVE_2026-08-27_TECH_TREE_EMBLEMS.md` og overstyrer eldre design
som gir et yrkesverktøy via `Profession.tool()`/`initialHeldItem()`, viser
gratis Appoint som normal survival-flyt, eller lar en worker levere produksjon
direkte utenom den fysiske arbeidsplass- og courier-kjeden.

## 1. Clean-room referansekontrakt

Hearthstead skal matche den forståelige delen av MineColonies-flyten tett:

1. Worker undersøker eget inventory og storage ved egen arbeidsplass.
2. Hvis påkrevd input eller utstyr mangler, opprettes en automatisk request.
3. Hvis varen finnes i Warehouse, reserverer en courier den og leverer den
   fysisk til riktig arbeidsplass eller settler.
4. Hvis varen ikke kan leveres automatisk, får spilleren en tydelig unresolved
   request og kan levere direkte eller legge varen i Warehouse/workplace.
5. Worker legger fysisk output i workplace storage.
6. Courier henter output fra workplace storage og legger den fysisk i
   Warehouse.
7. Request-listen viser hva, hvem, hvor, mengde, prioritet, status og konkret
   stoppårsak.
8. Relevant worker kan begrenses til en eksplisitt tredimensjonal arbeidssone.

Dette er en funksjonell clean-room-implementasjon. MineColonies-kode,
teksturer, lyd, tekst og UI-layout kopieres ikke. Hearthstead bruker egne
klasser, eget visuelt språk, færre skjulte regler og prosjektets chest-truth-
og ytelseskrav.

Offisielle referanser:

- https://minecolonies.com/wiki/systems/request/
- https://minecolonies.com/wiki/buildings/deliveryman/
- https://minecolonies.com/wiki/buildings/lumberjack/
- https://minecolonies.com/wiki/items/clipboard/
- https://minecolonies.com/wiki/buildings/tavern/

## 2. Én entydig interaction-router

Settler-interaksjon avgjøres på serveren i denne prioriteten:

| Input | Resultat |
|---|---|
| Shift + høyreklikk med Blessing i aktiv hånd | Bind Blessing |
| Shift + høyreklikk med kompatibelt Job Emblem | Tilby/bind yrke |
| Shift + høyreklikk med en aktivt etterspurt gjenstand | Direkte fysisk levering |
| Bruk med Work Scepter/Surveyor's Staff | Velg settler/post og konfigurer arbeidssone |
| Shift + høyreklikk med tom hovedhånd og tom offhand | Åpne ekte settler-inventory |
| Vanlig høyreklikk uten spesialitem | Åpne settlerens profil/inspection |

Regler:

- Offhand må aldri kapre klikket skjult. UI viser hvilken handling som vil skje.
- Feil item, feil settlement, manglende permission, gammel revision eller feil
  mål bruker aldri itemet.
- Item-handlinger har eksplisitte resultater: `APPLIED`, `REJECTED` eller
  `STALE`; ingen klient antar suksess.
- Creative/admin-bypass er en merket testvei og teller ikke som gameplaybevis.

## 3. Settlerens ekte inventory

Inventoryet er serverautoritativt og består av faktiske `ItemStack`-slots:

- personlig bag/cargo;
- aktivt hovedverktøy og eventuelt sekundærverktøy;
- offhand;
- rustningsslots der yrket bruker rustning;
- read-only visning av items som er reservert for eller av settleren;
- aktive equipment-/material-/food-requests;
- durability, tillatt tier og konkret arbeidsstopp.

Skjermen skal aldri vise et renderer-only verktøy som om det var fysisk eid.
Det settleren holder i verden, det som står i inventoryet, det request-systemet
ser og det som lagres i NBT skal være samme sannhet.

Skjermen må støtte:

- flytting mellom spiller og settler med vanilla-forventet klikk/shift-klikk;
- servervalidering av avstand, permission, settlement og revision;
- full inventory uten tap eller ukontrollert drop;
- EN/NB, guiScale 2/3/4, lange navn og 1280×720;
- statusene `NEEDS_TOOL`, `REQUESTED`, `COURIER_RESERVED`, `IN_TRANSIT`,
  `READY`, `BROKEN_TOOL`, `NO_WORKPLACE` og `NO_WORK_ZONE`.

## 4. Jobb gir aldri gratis utstyr

Et Job Emblem gir bare yrkesidentitet og, etter eksplisitt valg, en kompatibel
arbeidspost. Det skaper ingen øks, hakke, bue, rustning, ammunisjon, fiskestang,
saks eller annet item.

Normal utstyrsflyt:

1. Yrke/post blir bundet.
2. Worker beregner minste gyldige equipment-contract for neste handling.
3. Worker sjekker eget inventory.
4. Worker sjekker linked workplace input/storage.
5. Settlementets lagerindeks sjekkes uten global chest-scan.
6. Manglende item lager én persistent, typed `EquipmentRequest`.
7. Før Warehouse/Courier finnes, lærer Sagaoppdraget spilleren å levere direkte
   eller legge itemet i workplace storage.
8. Etter at Warehouse/Courier finnes, kan requesten reserveres og leveres
   automatisk.
9. Først når den faktiske stacken er mottatt, kan worker starte handlingen.

En equipment-contract beskriver minst:

- stable request-id og settlement-id;
- settler-id, profession-id og workplace-id;
- akseptert item-tag/type;
- minimums- og maksimumstier;
- antall og minimum durability;
- priority, created tick, revision og current state;
- eventuell courier-reservasjon og fysisk stack-fingerprint.

Verdifulle tiers forbrukes ikke automatisk uten settlementpolicy. Spilleren kan
blokkere automatisk bruk av for eksempel diamond/netherite eller spesifikke
named/enchantede items.

Slitte verktøy requestes før de går i stykker etter en målbar terskel. Det
gamle verktøyet returneres til workplace/Warehouse når erstatningen faktisk er
levert. Dismiss, retrain, death, building dissolve og settlement dissolve har
en fysisk recovery-stige; ingen gear dupliseres eller slettes stille.

## 5. Persistent request-ledger

Første implementerte request-typer:

- `EQUIPMENT`
- `MATERIAL_INPUT`
- `FOOD`
- `AMMUNITION`
- `OUTPUT_PICKUP`
- `REPAIR_MATERIAL`

State machine:

`OPEN -> RESERVED -> PICKUP -> IN_TRANSIT -> DELIVERED -> SATISFIED`

Tillatte sideutfall er `BLOCKED`, `CANCELLED` og `EXPIRED`. Alle overganger er
serverautoritative og idempotente. Restart/reconnect gjenoppretter requesten,
men en in-transit stack kan bare eies av én fysisk inventory/reservasjon.

Prioritet:

1. livreddende og aktivt forsvar;
2. manglende primærverktøy eller input som stopper en kjernejobb;
3. mat og kritisk produksjon;
4. planlagt erstatning, sekundærutstyr og output pickup;
5. valgfri upgrade.

Køen bruker aging og bounded fairness slik at høy prioritet ikke kan sulte alle
andre ruter for alltid.

Global request-visning viser:

- itemikon og eksakt mengde;
- requester, profession, workplace og avstand;
- status og alder;
- om varen finnes i Warehouse;
- hvilken courier som har reservert oppdraget;
- pickup- og leveringsmål;
- blokkering som `NOT_IN_STOCK`, `NO_COURIER`, `NO_ROUTE`, `FULL_TARGET`,
  `WRONG_TIER`, `NO_PERMISSION` eller `STALE`;
- handlingene «prioriter», «hent nå», «lever det som finnes» og «vis i verden»
  når de er gyldige.

En fysisk Tingbok/Request Ledger åpner samme autoritative liste. Den er et
vindu inn i ledgeren, ikke en separat kopi av sannheten.

## 6. Workplace storage er navet

Hver aktive arbeidsbygning har én eksplisitt linked storage-kontrakt. Den kan
peke på plaque-/hut-inventory og godkjente fysiske kister/racks i byggets
registrerte footprint.

Arbeidsflyt:

1. Worker tar verktøy og input fra eget inventory eller workplace storage.
2. Worker utfører ekte arbeid.
3. Worker legger all normal output i workplace storage.
4. Når output passerer pickupgrense eller lageret nærmer seg fullt, opprettes
   eller oppdateres én `OUTPUT_PICKUP`-request.
5. Courier reserverer nøyaktige stacks, går til arbeidsplassen, tar dem ut,
   bærer dem fysisk og committer dem i Warehouse.
6. Input-restock går motsatt vei og kan bruke minimum-stock-regler.

Per building skal spilleren kunne sette:

- pickup priority 1–10;
- automatic pickup on/off;
- «request pickup now»;
- minimum stock for godkjente items;
- input/output-filter der jobben trenger det;
- hvilken Warehouse/Courier-gruppe bygget tilhører når flere finnes.

Arbeideren leverer ikke direkte til Hearth eller globalt lager som en skjult
snarvei. Ved fullt workplace storage stopper jobben med synlig grunn og request,
fremfor å slette, droppe eller trylle bort output.

## 7. Courierens ryggsekk og levering

Videoen fra 19:56 viser at `CARRYING`-posen holder begge armene stivt foran
kroppen selv om den fysiske sack-modellen ligger på ryggen. Dette er avvist.

Bindende visuell kontrakt:

- Lasten ligger i ryggsekken/sacken gjennom hele transportetappen.
- Ingen usynlig kasse eller vare holdes foran kroppen under gange.
- Lett last bruker naturlig gange; tyngre last gir målbar, men ikke komisk,
  rygglean og kortere steg.
- En hånd kan stabilisere en skulderstropp ved høy last; den andre får naturlig
  motbevegelse. Begge armene skal aldri låses rett frem.
- Sackens størrelse/fyllgrad følger reell carried stack-count/capacity.
- Ved pickup brukes en kort `STOW_IN_BACKPACK`-sekvens.
- Ved levering brukes `TAKE_FROM_BACKPACK -> PRESENT -> HANDOFF/STOW_IN_CHEST`.
- Den synlige stacken vises bare under den korte overleveringen når det er
  lesbart og billig; ellers er den i bag-inventoryet.
- Avbrutt pathing, chunk unload, death eller full mål-inventory gir rollback/
  recovery uten å skille animasjon fra fysisk itemeierskap.

## 8. Work Scepter og arbeidsområder

Et eget `Work Scepter`/`Surveyor's Staff` er settlementets styringsverktøy.
Det gir sonestyring, ikke blokk-for-blokk-mikrostyring.

Første flyt:

1. Bruk verktøyet på settler eller workplace plaque for å velge post.
2. Høyreklikk første hjørne.
3. Venstreklikk motsatt hjørne; venstreklikket bryter ikke blokken mens
   verktøyet er aktivt.
4. En transparent 3D-preview viser hele boksen, inkludert Y-ledd.
5. Bekreft eller avbryt; serveren validerer settlement, permission, størrelse,
   world border, chunk/dimension og revision.
6. Worker søker eventdrevet/begrenset innenfor sonen og viser `NO_VALID_TARGET`
   når den er tom.

Første støttede soner:

- Lumberer: trær og eventuell gjenplanting;
- Miner: eksplisitt volum/nivå og tillatte blokktyper;
- Farmer: registrerte åkerfelt fremfor vilkårlig terreng;
- Hunter/Herder: valgfri radius/område etter at dyrevelferd og pathing er
  bevist;
- Guard/Archer: separate post-/patrolpunkter, ikke samme harvest-zone.

Zonen er valgfri for jobber der en sikker standardradius finnes, men UI skal
alltid vise hva som faktisk styrer søket. Maksvolum og søkebudsjett skal hindre
store soner fra å bli en tick- eller chunk-load-felle.

## 9. Flere settlers må være umulig å misforstå

Hearth/Journey viser en permanent «Neste settler»-kortkjede:

1. ledig bolig/Lodging;
2. Tavern åpnet i tech-tree og bygget;
3. omtrent to døgn spiseklar mat i settlement storage;
4. eventuell annen fysisk recruit cost;
5. traveler-status og forventet ankomstvindu;
6. kandidatkort med kostnad og eksplisitt recruit-knapp;
7. boligvalg, Job Emblem og workplace som påfølgende separate steg.

Hvert ledd viser `FERDIG`, `MANGLER` eller `VENTER`, eksakt antall og en
handling som peker spilleren til riktig skjerm/blokk. Ingen spiller skal måtte
vite fra wiki at Tavern eller housing øker kapasitet.

Founding Journey utvides med den første vertikale loopen:

`Hearth -> unlock Lumber Camp -> kjøp Lumberer-emblem -> bind valgt settler ->
angi eller godta arbeidssone -> worker requests axe -> spilleren leverer axe ->
worker legger logs i Lumber Camp storage -> unlock Warehouse/Courier -> courier
henter logs -> Tavern/Lodging/food -> recruit neste settler`.

## 10. UI-krav etter eierens avvisning

Dagens UI regnes som funksjonelt prototypebevis, ikke premiumdesign.

Før Java-skjermene omskrives lages offline previews for:

- Settler Profile;
- Settler Inventory;
- Equipment/Requests;
- Workplace Storage;
- Courier Task List;
- Global Request Ledger;
- Work-zone selection/confirmation;
- Recruitment checklist;
- Tech tree og Mayor Emblem Catalog.

Alle får samme Hearthstead-tokens, ikonfamilie, spacing, materialrammer,
statusspråk og motion. Hver skjerm må bevises i tom, normal, full, blocked,
permission-denied og stale state på EN/NB, guiScale 2/3/4 og 1280×720 samt
1920×1080. Ingen spillkritisk status kan uttrykkes bare med farge.

## 11. Lyd er en releaseblokker

Ingen aktiv lyd antas godkjent bare fordi filen finnes eller er mono OGG.

- Hver lyd må semantisk matche handlingen.
- Repeterende arbeid får 3–6 kontrollerte varianter.
- Kontakttransient ligger innen én renderframe/server-tick fra synlig kontakt.
- Courier får egne, diskrete cloth/leather/strap-, stow-, rummage- og
  handoff-lyder; ingen kontinuerlig høy sekkelyd under gange.
- Arbeidsplass, UI, Blessing, kamp, stemmer og ambience mikses som separate
  familier med konsistent loudness og distance rolloff.
- Crowd cooldown/voice budget hindrer mange settlers i å bli en lydvegg.
- Shear/fish/hunt og andre dokumenterte stand-ins erstattes.
- Film med ekte gameplaylyd, ikke bare waveform/validator, er sluttbeviset.

## 12. Migrering

- Gamle workers beholder profession og workplace.
- Projection-only gratisverktøy fjernes ikke blindt. Migrering auditerer om en
  stack er fysisk lagret; én ekte stack beholdes, renderer-only/verktøy som
  alltid ble regenerert blir ikke duplisert inn i nytt inventory.
- Gamle building inventories registreres som workplace storage uten å flytte
  stacks stille.
- In-flight gamle courier jobs konverteres eller returnerer last fysisk til
  sikker kilde før ny ledger aktiveres.
- Nye ID-er er stabile strings/wireverdier; enum-rekkefølge er aldri saveformat.
- Fremtidig eller korrupt request-/inventorydata settes i karantene med
  recoveryrapport.

## 13. Ytelsesinvarianter

- Ingen global per-tick scan av alle chests, settlers, requests eller soner.
- Workplace storage oppdaterer en settlement-wide item index ved inventory-
  event/change, chunk load/unload og explicit reconciliation.
- Request ledger bruker bounded queues og faste retry-budsjetter.
- Pathing skjer bare etter vunnet reservasjon; flere couriers løper ikke etter
  samme stack.
- Work-zone search er inkrementell, cachet og invalidert av relevante
  blockevents; den force-loader aldri chunks.
- UI mottar snapshots/deltas med revision, ikke hele settlementet hvert tick.
- 1/25/50/100-settler-matrisen må måle requests, storage indexing og work zones
  eksplisitt.

## 14. Minimum testmatrise

1. Hvert av 25 aktive yrker får riktig equipment-contract eller eksplisitt
   `NO_EQUIPMENT_REQUIRED`.
2. Emblem-binding skaper null items.
3. Direkte spillerlevering bruker nøyaktig én fysisk stack/count.
4. Workplace-levering tilfredsstiller samme request.
5. Courier Warehouse -> workplace/settler leverer og committer én gang.
6. Worker output -> workplace -> courier -> Warehouse konserverer item+NBT.
7. To couriers kan ikke reservere samme stack/request.
8. Full kilde, full courier og fullt mål har trygg, synlig recovery.
9. Manglende stock forblir unresolved uten busy-loop.
10. Tier, durability, enchantment, named item og do-not-use policy respekteres.
11. Verktøybrudd og replacement-request dupliserer ikke gammelt gear.
12. Dismiss, retrain, death og building dissolve konserverer gear.
13. Restart og chunk unload i hver request-state konvergerer korrekt.
14. Cross-settlement og manglende permission feiler lukket.
15. Shift-interaksjon følger prioritetstabellen med main/offhand-varianter.
16. Settler-inventory tåler to samtidige viewers og stale revision.
17. Work zone tåler negative koordinater, høydegrenser, stor avvist boks,
    unloadede chunks og slettet mål.
18. Lumberer velger aldri et tre utenfor godkjent sone.
19. Request priority/pickup-now/disable og aging er deterministisk.
20. Rekrutteringskortet viser riktig blocker og oppdateres etter faktisk state.
21. Courier bærer kun bag på transportfilm; armene er aldri låst foran.
22. Lydkontakt og animasjonskontakt består 50 repetisjoner uten dobbel trigger.
23. EN/NB og guiScale 2/3/4 består screenshotmatrisen.
24. 50 og 100 aktive settlers holder ytelsesbudsjettet uten kø-/cachevekst.
25. To rene serveromstarter og 2–4 spillere består hele første vertikale loop.

## 15. Gate for ferdig vertikal slice

Slicen er ikke ferdig før en frisk survival-verden kan vise, uten admin:

1. hvordan man får neste settler;
2. hvordan man kjøper og binder et Lumberer-emblem;
3. hvordan man åpner settler-inventory med tom hånd;
4. at Lumbereren mangler og requester en øks;
5. at spilleren kan levere øksen fysisk;
6. at Lumbereren bare hugger i valgt/godkjent område;
7. at logs ender i Lumber Camp storage;
8. at request-listen viser status sannferdig;
9. at courieren henter logs i ryggsekken og legger dem i Warehouse;
10. at lyd, UI, save/restart og item conservation består beviskravene.
