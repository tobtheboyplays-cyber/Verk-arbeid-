# JOURNEY TO FIRST RAID — BINDENDE IMPLEMENTERINGSSPEC 2026-08-27

**Status:** BINDENDE PRODUKT-, UX-, SAVE- OG IMPLEMENTERINGSSPEC  
**Eier:** Tobias Antonsen  
**Kilde:** Live playtest og eierdirektiver 2026-08-27  
**Omfang:** Frisk survival-verden fra grunnleggelse til rapporten etter første raid  
**Journey-versjon:** 2  
**Kjerneprogresjon:** 7 kapitler, 45 autoritative steg  
**Målplattform:** Hearthstead NeoForge  

Dette dokumentet er den bindende kontrakten for den første komplette
spillerreisen. Det supplerer:

- OWNER_DIRECTIVE_2026-08-27_TECH_TREE_EMBLEMS.md
- OWNER_DIRECTIVE_2026-08-27_SETTLER_CONTROL_LOGISTICS.md

Ved konflikt gjelder den nyeste eksplisitte eieravgjørelsen. Det betyr særlig:

1. Hele reisen fram til og med første raid går på én felles, naturlig
   tech-stamme uten strategisk låsevalg.
2. Rapporten etter første raid peker direkte videre til et råd med tre
   strategiske doktriner.
3. Doktrinene har reelle mulighetskostnader, men er senere reversible. Et
   feilvalg skal aldri ødelegge en save permanent.
4. De tre retningene åpner senere fem spesialiseringer.
5. Hver bygning har én felles, lokalisert kortbeskrivelse som brukes både på
   Build Plan og i Tech Tree. Tekstene får ikke dupliseres eller drive fra
   hverandre.

Ordene SKAL, SKAL IKKE, MÅ og KAN i dette dokumentet er normative.

---

## 1. Kort beslutning

Den nåværende Founding Journey er ikke tilstrekkelig som onboarding. Den
beviser bare:

**BUILD_LUMBER_CAMP → HIRE_LUMBERER → DELIVER_FIRST_LOG → COMPLETE**

Den lærer ikke spilleren hvordan man:

- åpner tech-treeet og låser opp bygg i naturlig rekkefølge;
- utnevner Mayor;
- kjøper og binder et fysisk jobb-emblem;
- åpner settlerens ekte inventory;
- leverer faktisk utstyr i stedet for å få gratis jernverktøy;
- avgrenser Lumbererens eller Farmerens arbeidsområde;
- bruker workplace storage;
- leser og løser requests;
- får Courieren til å bære en fysisk last til Warehouse;
- produserer mat og oppfyller rekrutteringskrav;
- rekrutterer en traveler eksplisitt;
- gir Guards stand-, patrol- eller tower-ordre;
- gjør bosetningen klar, mottar varsel og overlever første raid.

Journey v2 erstatter derfor fase-enumen som presentasjonsmodell med stabile
steg-ID-er og autoritativ event-evidence. Den bruker eksisterende
serverautoritative domener, men ingen klient får fullføre et steg ved å bare
vise en skjerm, sende en pakke eller ha et gammelt item.

---

## 2. Produktmål

### 2.1 Hovedmål

En ny spiller skal, uten wiki, admin-kommando eller gjetting, kunne fullføre
denne fysiske loopen:

~~~text
Hearth
  → Journey og Mayor
  → felles Tech Tree
  → Lumber Camp + Lumberer Emblem
  → ekte inventory + økse-request + arbeidssone
  → logs i Lumber Camp storage
  → Warehouse + Courier + request ledger
  → fysisk bagtransport til Warehouse
  → Farmhouse + Farmer + mat
  → House/Lodging + Tavern + traveler
  → eksplisitt rekruttering
  → Barracks + Guard + fysisk våpen + ordre
  → raid readiness
  → varsel
  → første raid
  → etterrapport
  → tre doktriner som neste steg
~~~

### 2.2 Kjennetegn på en god reise

- Den lærer ett nytt system om gangen og bruker det igjen senere.
- Hvert steg fullføres av samme serverevent som ekte gameplay bruker.
- Hvert stopp viser én konkret grunn, faktiske tall og nærmeste handling.
- Den tåler restart, reconnect, chunk unload, to samtidige spillere og stale
  klientdata uten dupe eller tap.
- Den setter aldri spilleren på et usynlig raid-tidspress før grunnloopen er
  klar.
- Den skjuler aldri kost, krav eller konsekvens bak farge alene.
- Den gir ingen gratis tech, bygg, emblems, verktøy, mat, våpen eller raidseier.

### 2.3 Ikke-mål for denne slicen

Følgende er ikke krav før første raid:

- Watchtower som obligatorisk bygg; Tower Post skal fungere i kontrollsystemet,
  men Stand Post eller Patrol Route er nok for readiness.
- senere produksjonskjeder som Mill, Bakery, Smithy, Mine og Sawmill;
- full balanse for alle tre doktriner og fem spesialiseringer;
- automatisering som erstatter de fysiske request- og transportleddene;
- admin-/testkommando som gameplaybevis;
- et permanent strategisk valg før spilleren har erfart første raid.

---

## 3. Bindende systeminvarianter

### 3.1 Serverautoritet

1. Klienten sender intensjon, aldri fasit.
2. Steg fullføres bare etter at domenetransaksjonen er committet.
3. Alle mutasjoner validerer settlement, player permission, dimension,
   avstand, mål-ID, session-ID og forventet revision.
4. En avvist eller stale handling flytter ingen item, endrer ingen tech og
   fullfører ingen Journey-evidence.
5. Samme transaction-ID kan anvendes maksimalt én gang.
6. Journey-klienten mottar snapshot eller delta med revision. Den scanner ikke
   verden og utleder ikke progresjon lokalt.

### 3.2 Ingen gratis eller renderer-only utstyr

Et Job Emblem gir yrkesidentitet og eventuell eksplisitt workplace-relasjon.
Det oppretter aldri verktøy, våpen, rustning, ammunisjon eller input.

Profession.tool(), initialHeldItem() og andre projection paths kan ikke være
itemkilde. Det settleren holder, viser i inventory, requester og lagrer skal
være samme fysiske ItemStack-sannhet.

### 3.3 Fysisk logistikk

1. Worker tar input fra eget inventory eller linked workplace storage.
2. Worker legger normal output i workplace storage.
3. OUTPUT_PICKUP-request peker på konkrete stacks i den lagringen.
4. Nøyaktig én Courier reserverer request og stacks.
5. Item flyttes fysisk til Courierens bag-inventory.
6. Courier går til Warehouse med lasten synlig i sekken på ryggen.
7. Item committes fysisk i Warehouse.
8. Hele kjeden bevarer item, count, components/NBT og eierskap.

Direkte levering til Hearth eller et globalt abstrakt lager teller ikke.

### 3.4 Fullført progresjon og levende krav

Steg-evidence er monotonic: et fullført steg blir ikke slettet fordi et bygg
senere går i stykker. Likevel må levende krav sjekkes igjen før avhengige
handlinger. Eksempel:

- Spilleren har tidligere koblet et Warehouse.
- Warehouse blir ugyldig før Courier skal hente.
- Link-steget står fortsatt fullført, men hentingen blokkeres med
  INVALID_BUILDING og Journey viser et reparasjonskort.

Dette hindrer både progress rollback og spoofing gjennom historiske objekter.

### 3.5 Skip er ikke en belønning

Skip Journey:

- setter presentationMode til SKIPPED;
- skjuler veiledningskort og feiring;
- starter første raid-countdown fra den aktuelle natten etter en tydelig
  bekreftelse;
- gir ingen tech, Build Plan, emblem, verktøy, mat, Guard, doctrine eller
  reward;
- endrer ikke vanlige gameplaykrav.

---

## 4. Felles tech-stamme gjennom første raid

Den tidlige progresjonen har ingen strategisk forgreining:

~~~text
hearthstead:first_fire
  → hearthstead:lumber_camp
  → hearthstead:warehouse
  → hearthstead:farmhouse
  → hearthstead:home
  → hearthstead:tavern
  → hearthstead:first_watch
  → hearthstead:first_raid
~~~

### 4.1 Nodekontrakt

| Node | Åpner | Journey-bruk |
|---|---|---|
| hearthstead:first_fire | Hearth, Journey, Mayor-utnevnelse | FJ-010–FJ-030 |
| hearthstead:lumber_camp | Lumber Camp Build Plan og Lumberer Emblem | FJ-100–FJ-180 |
| hearthstead:warehouse | Warehouse Build Plan, Courier Emblem og Request Ledger | FJ-200–FJ-260 |
| hearthstead:farmhouse | Farmhouse Build Plan og Farmer Emblem | FJ-300–FJ-380 |
| hearthstead:home | House og Lodging Build Plans | FJ-400–FJ-410 |
| hearthstead:tavern | Tavern Build Plan, traveler attraction og eventuell Innkeeper-katalog | FJ-420–FJ-460 |
| hearthstead:first_watch | Barracks Build Plan, Guard Emblem og Command Staff | FJ-500–FJ-560 |
| hearthstead:first_raid | Milestone; låses av readiness og raid lifecycle, ikke av betaling | FJ-600–FJ-620 |

Tech-unlock og faktisk bygging er separate fakta. Et gammelt Build Plan-item kan
ikke omgå en låst node i en frisk verden. En node åpner katalogtilgang, ikke et
gratis item.

House og Lodging er to reelle boligvalg under samme home-node. Begge må oppfylle
samme kapasitetskontrakt, men kan ha forskjellige komfort-, footprint- og
senere doktrineeffekter.

---

## 5. Journey v2 — datamodell og protokoll

### 5.1 Persisted settlement state

~~~text
FoundingJourneyState
  schemaVersion: int
  definitionVersion: int
  presentationMode: ACTIVE | SKIPPED | COMPLETE | QUARANTINED
  completedStepIds: ordered set<ResourceLocation>
  evidence: list<JourneyEvidence>
  currentChapterId: ResourceLocation
  revision: int
  outcome: NONE | HELD | HIT | SETTLEMENT_LOST
~~~

### 5.2 Evidence

~~~text
JourneyEvidence
  stepId: ResourceLocation
  eventId: ResourceLocation
  transactionId: UUID
  gameTime: long
  settlementId: UUID
  actorPlayerId: UUID optional
  subjectEntityId: UUID optional
  buildingId: UUID optional
  requestId: UUID optional
  stackFingerprint: string optional
  source: SURVIVAL | MIGRATION | TEST | ADMIN
~~~

Bare SURVIVAL og eksplisitt godkjent MIGRATION teller i normal Journey.
TEST/ADMIN kan brukes i fixtures og diagnostikk, men skal merkes og kan ikke
produsere releasebevis.

### 5.3 Stegdefinisjon

Hvert steg har:

- stabil ResourceLocation-ID;
- chapter-ID;
- definitionVersion;
- prerequisites;
- én eller flere autoritative completion-events;
- live guards;
- blocker-prioritet;
- lokalisert tittel, instruksjon, hjelp og fullført-tekst;
- valgfri world/highlight-action;
- valgfri recovery-action.

Steg-ID-er endres aldri etter release. Visningsrekkefølge, tekst og balanse kan
endres uten å endre ID. Enum-ordinal er aldri save- eller nettverksformat.

### 5.4 Autoritativ eventflyt

1. Et domene validerer og committer sin transaksjon.
2. Domenet publiserer et immutable event med unik transactionId.
3. JourneyProgressService finner bare det aktuelle settlementet og de få
   relevante stegene; ingen global scan.
4. Service validerer prerequisites og live guards mot committet state.
5. Hvis eventet ikke er brukt før, appendes evidence, step-ID markeres fullført,
   revision økes én gang og SavedData dirty-markeres.
6. Alle viewers mottar samme delta.
7. Dobbelt event, pakkeretry eller reconnect er idempotent.

### 5.5 Presentasjonskontrakt

Journey-panelet viser:

- nåværende kapittel og én primær handling;
- de nærmeste tre stegene;
- FERDIG, GJØR NÅ, MANGLER, VENTER eller REPARER;
- faktiske kostnader og tall fra samme serverassessment som domenet;
- knapp for Vis i verden der et gyldig mål finnes;
- knapp for Hvorfor? som viser canonical blocker-help;
- restart-sikker historikk uten falsk feiring.

---

## 6. De 45 bindende stegene

Alle eventnavn nedenfor betegner serverevents som sendes etter commit. Kolonnen
Anti-spoof/live guards er en minimumsliste; de generelle valideringene i
seksjon 3 gjelder i tillegg.

### Kapittel A — Foundation

**Chapter-ID:** hearthstead:journey/chapter/foundation

| # | Stabil step-ID | Spillerhandling og autoritativ completion-event | Anti-spoof/live guards | Canonical NB-hjelp |
|---:|---|---|---|---|
| 1 | hearthstead:journey/fj_010_found_hearth | Grunnlegg bosetningen. Fullføres av SettlementFoundedCommitted etter at Hearth, settlement-record og de tre founder-settlerne er committet atomisk. | Eksakt Hearth-posisjon og dimension; ny settlement-ID; alle tre founders er levende, bundet til samme settlement og lagret; ingen admin/test-kilde. | Plasser Hearth og fullfør grunnleggelsen. Hvis noe mangler, viser grunnleggelsesskjermen den konkrete blokken eller ressursen. |
| 2 | hearthstead:journey/fj_020_open_journey | Åpne Journey ved Hearth. Fullføres av JourneyViewOpened. | Serveren må ha en aktiv HearthMenu-session for eksakt Hearth, gyldig distance/permission og matching menu/session/revision. En vilkårlig klientpakke teller ikke. | Åpne Hearth og velg Journey. Her ser du hva bosetningen trenger akkurat nå. |
| 3 | hearthstead:journey/fj_030_appoint_mayor | Utnevn Mayor. Fullføres av MayorAppointedCommitted. | Kandidaten er en levende, bound member i samme settlement; spilleren har permission; Mayor-transaksjonen er APPLIED; gammel revision eller dobbel Mayor avvises uten kost. | Velg en av bosetterne som Mayor. Mayoren åpner tech-treeet og utsteder jobb-emblems. |

**Kapittelcheckpoint:** settlement, Hearth og Mayor finnes etter ren
serverrestart. Å åpne Journey igjen gir samme revision og neste steg.

### Kapittel B — First Labor

**Chapter-ID:** hearthstead:journey/chapter/first_labor

| # | Stabil step-ID | Spillerhandling og autoritativ completion-event | Anti-spoof/live guards | Canonical NB-hjelp |
|---:|---|---|---|---|
| 4 | hearthstead:journey/fj_100_unlock_lumber_camp | Lås opp Lumber Camp i Tech Tree. Fullføres av TechUnlockedCommitted med node hearthstead:lumber_camp. | first_fire og Mayor er gyldige; fysisk kost er trukket atomisk fra godkjent inventory/storage; gammel Build Plan eller klientvisning teller ikke. | Åpne Tech Tree ved Hearth eller Mayor, velg Tømmerleir og betal de viste fysiske varene. |
| 5 | hearthstead:journey/fj_110_link_lumber_camp | Bygg og koble Lumber Camp. Fullføres av BuildingLinkedValidCommitted. | Building-ID er registrert i samme settlement; type LUMBER_CAMP; node ulåst; room/footprint/plaque/storage-kontrakt er valid; ingen duplikatlink. | Bruk Build Plan for Tømmerleir, bygg rommet og koble plaque til bosetningen. Åpne plaque for å se hva som mangler. |
| 6 | hearthstead:journey/fj_120_staff_lumber_camp | Kjøp Lumberer Emblem hos Mayor, hold det og Shift-høyreklikk ønsket settler. Fullføres når EmblemTradeCommitted og den korrelerte JobEmblemBoundCommitted finnes. | Handel og binding har samme gyldige emblem-instance/transaction chain; ett fysisk emblem forbrukes først ved APPLIED; settler og camp er kompatible; nøyaktig én post valgt; ingen gratis Appoint-bypass. | Kjøp Tømmerhogger-emblemet hos Mayoren. Hold emblemet og Shift-høyreklikk bosetteren du vil gi jobben. |
| 7 | hearthstead:journey/fj_130_open_lumberer_inventory | Shift-høyreklikk Lumbereren med helt tom hovedhånd og offhand. Fullføres av SettlerInventoryViewOpened. | Serveren åpner en ekte SettlerInventoryMenu for eksakt entity; distance, permission, settlement, session og revision er gyldige; profilskjerm eller ghost bag teller ikke. | Tøm begge hendene og Shift-høyreklikk Tømmerhoggeren. Dette åpner det ekte inventoryet, utstyr og aktive requests. |
| 8 | hearthstead:journey/fj_140_set_lumber_zone | Bruk Work Scepter på worker/plaque og bekreft en 3D-sone. Fullføres av WorkZoneCommitted med type LUMBER. | Samme dimension/settlement; bounded volum og world border; ingen force-load; minst ett gyldig naturlig tre i sonen ved commit; expected revision matcher. | Velg Tømmerhoggeren med Work Scepter, marker to hjørner rundt trærne og bekreft området. |
| 9 | hearthstead:journey/fj_150_lumberer_requests_axe | Vent til Lumbereren vurderer neste jobb og mangler øks. Fullføres av EquipmentRequestOpened med axe-contract. | Persistent request har unik ID, riktig settler/profession/workplace, kompatibel axe-tag/tier/durability og state OPEN; worker eier ikke allerede gyldig øks; ingen free-tool projection. | Tømmerhoggeren mangler en øks. Åpne inventoryet eller request-listen for å se nøyaktig hva som godtas. |
| 10 | hearthstead:journey/fj_160_give_lumberer_axe | Lever en fysisk kompatibel øks direkte eller via workplace. Fullføres av RequestSatisfiedCommitted. | Eksakt request-ID; ItemStack er flyttet fysisk fra spiller/workplace til worker; count/components bevares; contract matcher; bare én satisfaction; ingen renderer-only stack. | Hold en godkjent øks og Shift-høyreklikk Tømmerhoggeren, eller legg den i Tømmerleirens input. |
| 11 | hearthstead:journey/fj_170_lumberer_fells_tree | Lumbereren fullfører ett ekte tre. Fullføres av LumbererTreeCompleted. | Treet var naturlig og gyldig ved claim; alle felte blokker ligger innenfor godkjent sone/settlement-policy; fysisk øks var eid og brukte durability; work action-ID er unik. | Tømmerhoggeren er klar. Hold området lastet og sørg for at det finnes et gyldig tre og ledig plass i leiren. |
| 12 | hearthstead:journey/fj_180_lumber_camp_stores_log | Worker legger minst én log fra work action i Lumber Camp storage. Fullføres av WorkplaceOutputCommitted. | Stack stammer fra FJ-170 workActionId; destinasjonen er campets linked storage; ingen direkte Hearth/global innsetting; item/count/components konserveres; fullt lager blokkerer synlig. | Åpne Tømmerleirens storage. Stokkene skal ligge her før en Courier kan hente dem. |

**Kapittelcheckpoint:** Journey skal kunne restartes både før og etter at
axe-requesten åpnes. Etter restart finnes nøyaktig én request, nøyaktig én øks
og samme work-zone.

### Kapittel C — Logistics

**Chapter-ID:** hearthstead:journey/chapter/logistics

| # | Stabil step-ID | Spillerhandling og autoritativ completion-event | Anti-spoof/live guards | Canonical NB-hjelp |
|---:|---|---|---|---|
| 13 | hearthstead:journey/fj_200_unlock_warehouse | Lås opp Warehouse. Fullføres av TechUnlockedCommitted med node hearthstead:warehouse. | Lumber Camp-node og FJ-180 er fullført; fysisk kost betales atomisk; settlement/revision gyldig. | Åpne Tech Tree og lås opp Lager. Det åpner Courier-emblemet og bosetningens request-ledger. |
| 14 | hearthstead:journey/fj_210_link_warehouse | Bygg og koble Warehouse. Fullføres av BuildingLinkedValidCommitted. | Type WAREHOUSE; tech ulåst; unik, gyldig building og linked storage; item index registrert uten global chest-scan. | Bygg Lageret med Build Plan, koble plaque og kontroller at lagerkistene vises som gyldige. |
| 15 | hearthstead:journey/fj_220_staff_warehouse | Kjøp og bind Courier Emblem og velg Warehouse-posten. Fullføres av korrelerte EmblemTradeCommitted og JobEmblemBoundCommitted. | Ett fysisk emblem; levende member; riktig settlement; kompatibel ledig post; ingen autoansettelse eller gratis gear; APPLIED én gang. | Kjøp Courier-emblemet hos Mayoren, hold det og Shift-høyreklikk bosetteren som skal frakte varer. |
| 16 | hearthstead:journey/fj_230_open_request_ledger | Åpne den globale Request Ledger via Hearth, Warehouse eller fysisk Tingbok. Fullføres av RequestLedgerViewOpened. | Serveren åpner samme autoritative ledger-snapshot for eksakt settlement og session; client-side liste eller feil settlement teller ikke. | Åpne Request Ledger. Her ser du item, mengde, requester, pickup, mål, Courier og konkret stoppårsak. |
| 17 | hearthstead:journey/fj_240_request_first_pickup | Velg Request pickup now på Lumber Camp-loggene. Fullføres av OutputPickupRequestOpened. | Minst én konkret log finnes i camp storage; request peker på building/storage/stack fingerprints; ingen duplikat OPEN-request for samme beholdning; automatic pickup kan gi samme event. | Be om henting fra Tømmerleiren. Hvis knappen er grå, må minst én log ligge i leirens storage. |
| 18 | hearthstead:journey/fj_250_courier_claims_pickup | Courier reserverer request og eksakte stacks. Fullføres av RequestReservationCommitted. | State går OPEN til RESERVED én gang; unik Courier og settlement; stack-reservasjon finnes; Courier har bagkapasitet og rute; to Couriers kan ikke vinne samme request. | Courieren har tatt oppdraget. Hvis det står VENTER, åpne requesten for å se om det mangler Courier, lagerplass eller rute. |
| 19 | hearthstead:journey/fj_260_warehouse_receives_log | Courier henter, bærer i ryggsekken og legger loggen i Warehouse. Fullføres av RequestSatisfiedCommitted for OUTPUT_PICKUP. | Full state-trace RESERVED → PICKUP → IN_TRANSIT → DELIVERED → SATISFIED; item går camp storage → Courier bag → Warehouse; samme fingerprint/count; ingen teleport eller dobbelt commit. | Følg Courieren. Lasten skal inn i sekken ved leiren og ut i Lageret. Ved stopp viser requesten nøyaktig hvor varen er. |

**Kapittelcheckpoint:** En restart i IN_TRANSIT skal gjenopprette én Courier,
én fysisk bag-stack og én request. Ingen kilde- eller målkopi er tillatt.

### Kapittel D — Food Security

**Chapter-ID:** hearthstead:journey/chapter/food_security

| # | Stabil step-ID | Spillerhandling og autoritativ completion-event | Anti-spoof/live guards | Canonical NB-hjelp |
|---:|---|---|---|---|
| 20 | hearthstead:journey/fj_300_unlock_farmhouse | Lås opp Farmhouse. Fullføres av TechUnlockedCommitted med node hearthstead:farmhouse. | Warehouse-node og FJ-260 er fullført; fysisk kost committet; riktig settlement/revision. | Lås opp Gårdshus i den felles tech-stammen. Dette åpner Farmer-emblemet og matloopen. |
| 21 | hearthstead:journey/fj_310_link_farmhouse | Bygg og koble Farmhouse. Fullføres av BuildingLinkedValidCommitted. | Type FARMHOUSE; tech ulåst; room/footprint/plaque/storage valid; samme settlement. | Bygg Gårdshuset, koble plaque og kontroller at input- og output-lagringen er gyldig. |
| 22 | hearthstead:journey/fj_320_staff_farmhouse | Kjøp og bind Farmer Emblem og velg Farmhouse. Fullføres av korrelerte EmblemTradeCommitted og JobEmblemBoundCommitted. | Ett fysisk emblem; kompatibel ledig post; ingen gratis hoe; APPLIED én gang; settlement og revision gyldig. | Kjøp Bonde-emblemet hos Mayoren og bind det til bosetteren som skal arbeide ved Gårdshuset. |
| 23 | hearthstead:journey/fj_330_set_farm_zone | Registrer et gyldig åkerfelt med Work Scepter. Fullføres av WorkZoneCommitted med type FARM. | Bounded 3D-zone; farmland/crop positions registrert; samme dimension/settlement; ingen vilkårlig avling utenfor feltet; expected revision. | Velg bonden eller Gårdshuset, marker åkeren og bekreft. Previewet må vise hele feltet, også høyden. |
| 24 | hearthstead:journey/fj_340_farmer_requests_hoe | Farmer mangler og requester hoe. Fullføres av EquipmentRequestOpened med hoe-contract. | Persistent unik request; riktig settler/profession/workplace; Farmer har ingen gyldig hoe; ingen initialHeldItem eller projection-spawn. | Bonden mangler en hakke. Se requesten for godkjent type og durability. |
| 25 | hearthstead:journey/fj_350_equip_farmer | Lever hoe fysisk, primært via Warehouse/Courier men direkte levering er lov. Fullføres av RequestSatisfiedCommitted. | Eksakt request; konkret stack flyttes én gang; contract/tier/policy matcher; Courier-reservasjon respekteres eller frigis atomisk ved direkte levering. | Legg en godkjent hakke i Lageret for automatisk levering, eller lever den direkte til bonden. |
| 26 | hearthstead:journey/fj_360_supply_first_seed | Lever minst én godkjent seed-stack til Farmhouse input. Fullføres av MaterialInputCommitted. | Fysisk stack; crop-contract matcher registrert felt; destinasjon er Farmhouse input; ingen syntetisk seed eller UI-only count. | Legg frø i Gårdshusets input eller opprett en material-request fra Lageret. |
| 27 | hearthstead:journey/fj_370_farmhouse_stores_crop | Farmer høster og legger en ekte crop-stack i Farmhouse storage. Fullføres av WorkplaceOutputCommitted. | Avlingen kommer fra registrert felt og unik workActionId; hoe/input er fysisk; destination er linked workplace storage; ingen direkte Hearth-deposit. | La bonden stelle feltet. Den første avlingen skal ende i Gårdshusets storage. |
| 28 | hearthstead:journey/fj_380_warehouse_receives_crop | Courier leverer crop fra Farmhouse til Warehouse. Fullføres av RequestSatisfiedCommitted for den korrelerte OUTPUT_PICKUP-requesten. | Full request state-trace og item conservation; exact Farmhouse source og Warehouse target; én reservasjon/levering. | Be om henting hvis det trengs. Følg requesten til avlingen ligger fysisk i Lageret. |

**Kapittelcheckpoint:** Farmer-zone, hoe-request, seed input, output og Courier-
levering bevares gjennom restart. Mat teller først når ReadyFood-policyen
faktisk klassifiserer den som spiseklar.

### Kapittel E — Growth

**Chapter-ID:** hearthstead:journey/chapter/growth

| # | Stabil step-ID | Spillerhandling og autoritativ completion-event | Anti-spoof/live guards | Canonical NB-hjelp |
|---:|---|---|---|---|
| 29 | hearthstead:journey/fj_400_unlock_home | Lås opp home-noden. Fullføres av TechUnlockedCommitted med node hearthstead:home. | Farmhouse-node og matloopen er fullført; fysisk kost betalt; både House og Lodging blir katalogtilgjengelige, ikke gratis. | Lås opp Hjem. Velg Hus for færre, bedre plasser eller Losji for flere enkle senger. |
| 30 | hearthstead:journey/fj_410_link_first_home | Bygg og koble House eller Lodging med minst én ledig plass. Fullføres av HousingCapacityCommitted. | Byggtype HOUSE eller LODGING; tech ulåst; valid room/beds; settlement.capacity er minst population + 1 etter commit; samme building-ID består recheck. | Bygg et Hus eller Losji. Kapasitetskortet må vise minst én ledig seng før rekruttering kan starte. |
| 31 | hearthstead:journey/fj_420_unlock_tavern | Lås opp Tavern. Fullføres av TechUnlockedCommitted med node hearthstead:tavern. | home-noden og FJ-410 er fullført; fysisk kost atomisk; korrekt settlement/revision. | Lås opp Vertshuset i Tech Tree. Vertshuset gjør bosetningen synlig for reisende. |
| 32 | hearthstead:journey/fj_430_link_tavern | Bygg og koble en gyldig Tavern. Fullføres av BuildingLinkedValidCommitted. | Type TAVERN; tech ulåst; room/footprint/plaque valid; samme settlement; gammel bare-visuell Tavern teller ikke. | Bygg Vertshuset og koble plaque. Rekrutteringskortet viser deretter resterende krav. |
| 33 | hearthstead:journey/fj_440_recruitment_window_starts | Oppfyll alle krav kontinuerlig. Fullføres av RecruitmentQualificationStartedCommitted når første kvalifiserte sekund faktisk er lagret. | Samme RecruitmentPolicy.assess brukes av UI, timer og admission; blocker NONE; gyldig Tavern, ledig seng, morale minst 60, betaling mulig og matreserve etter betaling; ingen client timer. | Hold alle krav grønne uten avbrudd. Ved tre nåværende settlers betyr dagens policy normalt 4 brød + 8 planker i pris og 32 spiseklare måltider igjen etter betalingen. |
| 34 | hearthstead:journey/fj_450_traveler_arrives | Traveler når Tavern-ankeret etter 2–4 sammenhengende kvalifiserte døgn. Fullføres av TravelerArrivedAtTavernCommitted. | Target duration er stabilt settlement/cycle-spesifikt; regress stopper eller resetter etter RecruitmentPolicy; entity må faktisk nå gyldig Tavern-anchor; edge spawn eller UUID alene teller ikke. | Kravene er oppfylt. Hold mat, seng, morale og Vertshus gyldig mens den reisende er på vei. |
| 35 | hearthstead:journey/fj_460_admit_traveler | Åpne kandidatkortet og trykk Rekrutter. Fullføres av TravelerAdmittedCommitted. | Eksakt traveler/session/revision; levende ventende traveler ved Tavern; alle krav vurderes på nytt; betaling skjer atomisk; kapasitet reserveres; én av to samtidige spillere vinner; ingen auto-convert. | Åpne den reisendes kandidatkort, les kostnaden og trykk Rekrutter. Hvis noe har endret seg, betales ingenting og kortet viser hvorfor. |

**Kapittelcheckpoint:** Restart mens kvalifiseringstimeren går, mens traveler er
på vei og mens traveler venter ved Tavern. Target, elapsed qualified time,
traveler-ID, pris og reservation må fortsette uten gratis fremdrift.

### Kapittel F — First Watch

**Chapter-ID:** hearthstead:journey/chapter/first_watch

| # | Stabil step-ID | Spillerhandling og autoritativ completion-event | Anti-spoof/live guards | Canonical NB-hjelp |
|---:|---|---|---|---|
| 36 | hearthstead:journey/fj_500_unlock_first_watch | Lås opp first_watch. Fullføres av TechUnlockedCommitted med node hearthstead:first_watch. | Tavern/recruit-steget fullført; fysisk kost betalt; åpner Barracks, Guard Emblem og Command Staff; åpner ikke Watchtower gratis. | Lås opp Første vakt. Du får tilgang til Brakke, Vakt-emblem og Command Staff. |
| 37 | hearthstead:journey/fj_510_link_barracks | Bygg og koble Barracks. Fullføres av BuildingLinkedValidCommitted. | Type BARRACKS; tech ulåst; valid room/footprint/plaque/storage; samme settlement. | Bygg Brakken og koble plaque. Kontroller at minst én vaktpost er ledig. |
| 38 | hearthstead:journey/fj_520_staff_barracks | Kjøp Guard Emblem, bind til valgt settler og velg Barracks. Fullføres av korrelerte EmblemTradeCommitted og JobEmblemBoundCommitted. | Ett fysisk emblem; levende member; riktig post; ingen gratis sword/bow/armor; APPLIED én gang. | Kjøp Vakt-emblemet hos Mayoren og bind det til bosetteren som skal forsvare landsbyen. |
| 39 | hearthstead:journey/fj_530_guard_requests_weapon | Guard oppretter weapon equipment-request. Fullføres av EquipmentRequestOpened med Guard weapon-contract. | Riktig Guard/workplace; ingen kompatibelt fysisk våpen allerede eid; request persistent og unik; ammo er separat når våpentypen krever det. | Vakten trenger et fysisk våpen. Åpne requesten for godkjente typer; rustning er nyttig, men ikke et skjult krav. |
| 40 | hearthstead:journey/fj_540_equip_guard | Lever et kompatibelt fysisk våpen. Fullføres av RequestSatisfiedCommitted. | Eksakt request og stack; item flyttes spiller/Warehouse → Guard én gang; tier/policy/durability matcher; renderer-only weapon teller ikke. | Lever våpenet direkte eller la Courieren hente det fra Lageret. Requesten skal ende som LØST. |
| 41 | hearthstead:journey/fj_550_set_guard_order | Bruk Command Staff og bekreft Stand Post eller Patrol Route. Fullføres av GuardAssignmentCommitted. | Samme settlement/dimension; Guard levende og bound; Stand-posisjon gyldig eller Patrol har 2–8 gyldige punkter; expected revision; Tower Post krever gyldig Watchtower-tech/bygningspost. | Velg vakten med Command Staff. Sett en ståpost eller lag en patrulje med minst to punkter, og bekreft ordren. |
| 42 | hearthstead:journey/fj_560_declare_raid_ready | Åpne readiness-kortet og trykk Bosetningen er klar. Fullføres av FirstRaidReadinessCommitted. | Hele checklisten i seksjon 10 revalideres atomisk; FirstRaidState er PREPARING; current revision; failure endrer ingenting og viser alle blockers; success beregner og lagrer eksakt varsel-/angrepsnatt. | Kontroller beredskapslisten. Når alle linjer er ferdige, bekreft at bosetningen er klar og nedtellingen starter. |

**Kapittelcheckpoint:** Guardens våpen og ordre er fysisk/persisted. Restart
etter readiness skal bevare nøyaktig warningNight, attackNight, warningLead og
RaidPlan-seed/identitet.

### Kapittel G — First Raid

**Chapter-ID:** hearthstead:journey/chapter/first_raid

| # | Stabil step-ID | Spillerhandling og autoritativ completion-event | Anti-spoof/live guards | Canonical NB-hjelp |
|---:|---|---|---|---|
| 43 | hearthstead:journey/fj_600_receive_first_warning | Motta det vedvarende første raid-varselet. Fullføres av FirstRaidWarningCommitted. | FirstRaidState SCHEDULED; nåværende natt er lagret warningNight; eksakt persisted RaidPlan opprettes/queues én gang; varselet kan åpnes igjen etter reconnect; klienttoast alene teller ikke. | Speidere melder fare. Åpne varselet for retning, forventet natt, angriperens mål og siste konkrete forberedelser. |
| 44 | hearthstead:journey/fj_610_first_raid_resolved | Forsvar bosetningen til raidet får et autoritativt terminalutfall. Fullføres av FirstRaidResolvedCommitted. | FirstRaidState gikk SCHEDULED → ACTIVE → COMPLETED; deltakerlisten ble sealed; bare tracked participants og objektivledger avgjør slutt; eventet har outcome HELD, HIT eller SETTLEMENT_LOST; ingen kill-count fra vilkårlige mobs. | Hold vaktene bevæpnet og følg varselets retning. Journey fullføres først når alle registrerte raiders er løst og utfallet er lagret. |
| 45 | hearthstead:journey/fj_620_review_aftermath | Åpne den lagrede etterrapporten ved Hearth. Fullføres av RaidAftermathViewOpened. | Eksakt Hearth session; persisted RaidLogEntry matcher første plan/outcome; settlement/revision/permission gyldig; en lokalt konstruert rapport teller ikke. | Åpne Hearth og les rapporten: hva raiderne ville ha, tap, skader, hvem som holdt linjen og hva som nå kan utvikles videre. |

FJ-620 setter presentationMode til COMPLETE etter at rapporten er åpnet.
Manual releasebevis skal ende i HELD eller HIT med bosetningen fortsatt
operativ. SETTLEMENT_LOST lagres og vises ærlig som nederlag/recovery, aldri
som en vunnet feiring og aldri som grunn til å slette save.

---

## 7. Interaction-kontrakt som Journey avhenger av

Serveren bruker denne prioriteten:

| Input | Resultat |
|---|---|
| Shift + høyreklikk med Blessing i aktiv hånd | Bind Blessing |
| Shift + høyreklikk med kompatibelt Job Emblem | Tilby eller bind yrke |
| Shift + høyreklikk med en aktivt etterspurt gjenstand | Direkte fysisk levering |
| Bruk med Work Scepter eller Surveyor's Staff | Velg worker/post og konfigurer work-zone |
| Bruk med Command Staff | Velg Guard/post og konfigurer stand/patrol/tower |
| Shift + høyreklikk med tom hovedhånd og tom offhand | Åpne ekte settler-inventory |
| Vanlig høyreklikk uten spesialitem | Åpne profil og inspection |

Offhand kan ikke kapre handlingen skjult. Hover/action hint skal vise hva neste
klikk gjør. Alle item-handlinger returnerer APPLIED, REJECTED eller STALE.

---

## 8. Worker-, storage- og request-kontrakt

### 8.1 Første request-typer

- EQUIPMENT
- MATERIAL_INPUT
- FOOD
- AMMUNITION
- OUTPUT_PICKUP
- REPAIR_MATERIAL

### 8.2 State machine

~~~text
OPEN
  → RESERVED
  → PICKUP
  → IN_TRANSIT
  → DELIVERED
  → SATISFIED

Sideutfall: BLOCKED | CANCELLED | EXPIRED
~~~

Alle overganger er persisted, serverautoritative og idempotente. En fysisk
stack kan eies av nøyaktig én kilde, reservasjon eller inventory om gangen.

### 8.3 Request Ledger viser alltid

- itemikon, type/tag og eksakt mengde;
- requester, profession og workplace;
- pickup, leveringsmål og avstand;
- status, prioritet og alder;
- fysisk stock-tilgjengelighet;
- hvilken Courier som har reservasjonen;
- konkret blocker;
- gyldige handlinger: prioriter, hent nå, lever det som finnes, vis i verden.

### 8.4 Courier-presentasjon

Under transport ligger item i bag-inventoryet og den synlige sekken på ryggen.
Armene skal ikke låses foran kroppen. Pickup bruker STOW_IN_BACKPACK. Levering
bruker TAKE_FROM_BACKPACK → PRESENT → HANDOFF eller STOW_IN_CHEST. Synlig stack
kan vises i hånden bare under den korte handoff-sekvensen.

---

## 9. Guard-kontroll

### 9.1 Ordremoduser

~~~text
STAND_POST
  position
  facing
  leashRadius

PATROL_ROUTE
  orderedPoints: 2..8
  traversal: LOOP | PING_PONG

TOWER_POST
  watchtowerBuildingId
  postCell
  facingArc
~~~

- STAND_POST og PATROL_ROUTE er godkjente readiness-ordrer i første Journey.
- TOWER_POST implementeres og testes i samme kontrollverktøy, men krever
  kompatibel Watchtower-node og gyldig post. Det er ikke et første-raidkrav.
- Combat og alarm kan midlertidig overstyre ordren innenfor settlementets
  forsvarsregler. Guard returnerer deretter til post/rute.
- Dagens automatiske faste åttepunktsring er ikke spillerstyring og teller
  ikke som FJ-550.
- GuardOrder lagres med stabil mode-ID, target data, revision og issuer.

---

## 10. Raid readiness og første raid-kalender

### 10.1 Hvorfor dagens grunnleggingskalender må endres

Dagens første raid rulles ved founding til natt +4–7, med varsel én eller to
netter før. Samtidig krever dagens rekrutteringspolicy 2–4 sammenhengende
Minecraft-døgn. Det kan gjøre en normal, lært spillerreise matematisk umulig:
raidet kan komme før spilleren rekker bolig, Tavern, kvalifisering, traveler,
Guard og utstyr.

Første raid for friske Journey v2-settlements skal derfor være
readiness-anchored uten å miste den opprinnelige 4–7-netters variasjonen.

### 10.2 Stabil lifecycle

Eksisterende wire-ID-er må aldri renummereres:

| State | Wire-ID | Betydning |
|---|---:|---|
| UNINITIALIZED | 0 | Legacy/ingen plan |
| SCHEDULED | 1 | Eksakt warningNight og attackNight er lagret |
| ACTIVE | 2 | Den persisted planen er startet og deltakerlisten bygges/seales |
| COMPLETED | 3 | Terminalt resultat og raidlogg er lagret |
| PREPARING | 4 | Ny frisk verden; founding-roll er lagret, men readiness er ikke erklært |

PREPARING får wire-ID 4 selv om kildekodens enumrekkefølge senere endres.

Ved vellykket founding lagres:

~~~text
foundedNight
rolledAttackOffset = random inclusive 4..7
rolledNotBeforeNight = foundedNight + rolledAttackOffset
warningLead = 1 eller 2 fra valgt RaidProfile
readinessNight = unset
warningNight = unset
attackNight = unset
firstState = PREPARING
~~~

Når FJ-560 committer:

~~~text
readinessNight = currentNight
attackNight = max(
    rolledNotBeforeNight,
    readinessNight + warningLead
)
warningNight = attackNight - warningLead
firstState = SCHEDULED
~~~

Konsekvensene er bindende:

- En rask spiller får aldri angrep før founding-rollens natt +4–7.
- En langsom spiller får alltid full warningLead etter readiness.
- Det trekkes ikke en ny random dato ved readiness, restart eller reconnect.
- warningNight, attackNight og den senere RaidPlan-identiteten lagres én gang.
- Eksisterende SCHEDULED, ACTIVE og COMPLETED raids beholder eksakt dato og
  plan under migrering.

Skip Journey er eneste normale unntak fra readiness-checklisten. Etter en
tydelig farebekreftelse brukes den aktuelle natten som readinessNight og samme
formel armer countdown. Skip gir fortsatt ingenting.

### 10.3 Eksakt readiness-checkliste

FirstRaidReadinessService vurderer hele listen fra ett konsistent
settlement-snapshot:

1. Hearth finnes, er lastet, har riktig settlement-ID og er fortsatt gyldig.
2. Ett gyldig linked Lumber Camp finnes.
3. Ett gyldig linked Warehouse med fungerende linked storage finnes.
4. Ett gyldig linked Farmhouse finnes.
5. Minst ett gyldig House eller Lodging finnes, og kapasiteten er ikke under
   nåværende population.
6. En gyldig linked Tavern finnes.
7. En gyldig linked Barracks finnes.
8. Minst én levende Lumberer har bound emblem og Lumber Camp-workplace.
9. Minst én levende Courier har bound emblem og Warehouse-workplace.
10. Minst én levende Farmer har bound emblem og Farmhouse-workplace.
11. Minst én levende, lastet Guard har bound emblem og Barracks-workplace.
12. Lumberer har en gyldig LUMBER-work-zone.
13. Farmer har et gyldig FARM-field.
14. Warehouse item index og physical storage er tilgjengelige og ikke
    quarantined.
15. Ingen critical request eller fysisk stack har konfliktende eier,
    reservasjon eller uavklart IN_TRANSIT-recovery.
16. Minst én Guard har et fysisk kompatibelt våpen.
17. Minst én bevæpnet Guard har gyldig STAND_POST eller PATROL_ROUTE.
18. Spiseklar mat etter eksisterende reservasjoner er minst
    RecruitmentPolicy.requiredReserve(currentPopulation). Med fire residents
    er dette etter dagens policy 32 ready meals.
19. FirstRaidState er PREPARING, ikke allerede SCHEDULED, ACTIVE eller
    COMPLETED.
20. Player/session/settlement revision matcher.

Readiness krever ikke at alle valgfrie requests er løst, at Watchtower finnes
eller at alle Guards har full rustning. Failure endrer ingen state og viser
alle blockers, ikke bare den første.

### 10.4 Raid start og resolution

- Warning-event oppstår på eller etter eksakt persisted warningNight, maksimalt
  én gang.
- Warning lager og lagrer den konkrete RaidPlan som angrepet senere bruker.
- Attack starter aldri en annen tilfeldig plan enn den som ble varslet.
- Ved ACTIVE opprettes en bounded participant ledger. Listen seals når planens
  spawnsekvens er ferdig.
- Bare en sealed deltaker kan påvirke terminal counter.
- Objective-truth, stjålne items, skadde settlers og captain-state hentes fra
  samme raidledger som rapporten.
- Resolution committer nøyaktig én RaidLogEntry og outcome.
- Reward kan bare utstedes etter terminal commit og bare én gang.
- Journey-evidence gir ikke reward; raid lifecycle gjør det.

---

## 11. Felles blocker-katalog

UI, Journey, Tech Tree, request-listen, recruitment og readiness skal bruke
samme stabile blocker-ID og samme lokalisering. Tall og objektnavn settes inn
som parametere; de hardkodes ikke i separate skjermer.

| Stabil blocker-ID | Når den brukes | Canonical NB-hjelp og handling |
|---|---|---|
| NO_MAYOR | Tech eller emblemhandling krever Mayor | Bosetningen mangler en Mayor. Åpne Journey ved Hearth og utnevn en levende bosetter. |
| TECH_LOCKED | Building, emblem eller control mode ligger bak en node | Denne delen er fortsatt låst i Tech Tree. Åpne den markerte forutsetningen først. |
| MISSING_TECH_COST | Fysisk nodekost mangler | Du mangler de viste varene. Legg dem i godkjent storage eller ha dem i inventory før du prøver igjen. |
| INVALID_BUILDING | Bygg/plaque/room/footprint/storage er ikke gyldig | Bygningen er ikke i drift. Åpne plaque for å se nøyaktig hvilke blokker, rom eller koblinger som mangler. |
| NO_OPEN_POST | Ingen kompatibel worker-post er ledig | Denne jobben har ingen ledig post. Bygg eller reparer en kompatibel arbeidsplass, eller frigjør en eksisterende post. |
| MISSING_EMBLEM | Spilleren prøver å ansette uten fysisk emblem | Du trenger riktig jobb-emblem fra Mayoren. Ingen jobb tildeles gratis. |
| NO_WORKPLACE | Settleren har yrke, men ingen gyldig workplace | Bosetteren beholder yrket, men mangler arbeidsplass. Velg en kompatibel ledig post. |
| NO_WORK_ZONE | Jobben krever område som ikke finnes | Arbeidsområdet er ikke satt. Bruk Work Scepter på worker eller plaque og marker sonen. |
| NO_VALID_TARGET | Sone finnes, men ingen gyldig blokk/crop/target | Ingen gyldige mål finnes i området. Utvid eller flytt sonen, eller fyll den med riktig måltype. |
| NEEDS_TOOL | Equipment-contract mangler fysisk item | Arbeidet stopper fordi verktøyet mangler. Åpne requesten og lever en kompatibel fysisk gjenstand. |
| NOT_IN_STOCK | Request har ingen matchende fysisk stack | Varen finnes ikke i Warehouse eller workplace. Skaff den, og legg den i vist lager. |
| NO_COURIER | Ingen Courier kan reservere oppgaven | Ingen Courier er tilgjengelig. Bind et Courier-emblem til en bosetter og gi dem en gyldig Warehouse-post. |
| NO_ROUTE | Pathing eller lastet rute er utilgjengelig | Courieren finner ingen trygg rute mellom pickup og mål. Fjern hindringen eller flytt koblingen; chunks force-loades ikke. |
| FULL_WORKPLACE | Worker kan ikke legge fra seg output | Arbeidsplassens storage er full. Tøm lageret eller be en Courier hente output. |
| FULL_WAREHOUSE | Leveringsmålet har ikke fysisk plass | Warehouse er fullt. Frigjør slots eller utvid gyldig linked storage. Ingen item droppes eller slettes. |
| NO_SEEDS | Farmer mangler kompatibel crop-input | Gårdshuset mangler frø for det registrerte feltet. Legg frø i input eller opprett en material-request. |
| NO_BED | Rekruttering mangler ledig capacity | Bosetningen har ingen ledig seng. Bygg eller reparer Hus/Losji til capacity er minst population + 1. |
| NO_TAVERN | Traveler attraction mangler gyldig Tavern | Et gyldig Vertshus må være låst opp, bygget og koblet før reisende kommer. |
| LOW_MORALE | RecruitmentPolicy er under 60 morale | Moralen er for lav. Kortet viser nåverdi og kravet 60; løs de konkrete morale-kildene før timeren fortsetter. |
| CANNOT_PAY | Eksakt recruit price kan ikke trekkes | Du kan ikke betale den viste prisen. Dagens grunnpris er 4 brød og 8 planker før eventuelle gyldige rabatter. |
| INSUFFICIENT_READY_FOOD | Mat etter betaling/reservasjon er under reserve | Det blir for lite spiseklar mat igjen. Kortet viser før, etter, krav og eksakt mangel. |
| TRAVELER_EN_ROUTE | Kvalifisering er fullført, entity går mot Tavern | En reisende er på vei. Hold Tavern, seng, mat og morale gyldig og følg forventet ankomststatus. |
| TRAVELER_WAITING_APPROVAL | Traveler står ved Tavern og krever spillerbekreftelse | Den reisende venter ved Vertshuset. Åpne kandidatkortet og velg Rekrutter eller Avvis. |
| GUARD_UNARMED | Readiness mangler fysisk kompatibelt Guard-våpen | Minst én vakt mangler et fysisk våpen. Løs weapon-requesten direkte eller via Warehouse. |
| NO_GUARD_ORDER | Guard har ingen gyldig stand/patrol | Gi minst én bevæpnet vakt en ståpost eller patruljerute med Command Staff. |
| READINESS_REGRESSED | Et tidligere Journey-faktum er fullført, men live state er nå ugyldig | En del av beredskapen har falt ut. Progresjonen er bevart; reparer de røde linjene før nedtellingen kan starte. |
| JOURNEY_QUARANTINED | Ukjent, fremtidig eller korrupt save/protokoll | Journey-data kunne ikke leses trygt og er satt i karantene. Ingen progresjon eller item er gjettet; åpne recovery-rapporten. |

NO_PERMISSION, TOO_FAR, WRONG_SETTLEMENT, WRONG_DIMENSION og STALE_REVISION er
felles interaction-feil under disse domeneblockerene. De skal ha konkrete
lokaliserte meldinger, men aldri forbruke item eller fullføre et steg.

Blocker-prioritet når flere gjelder:

1. korrupt/quarantined eller permission;
2. feil settlement/dimension/session/revision;
3. låst tech;
4. ugyldig eller manglende bygg/post;
5. manglende fysisk item/input/storage;
6. pathing/venter;
7. capacity/morale/balansekrav.

Readiness er unntaket: den viser alle feil samtidig i logisk rekkefølge.

---

## 12. Checkpoint-, restart- og reconnect-kontrakt

### 12.1 Generelle regler

- Hver commit dirty-marker SettlementSavedData før klientfeiring.
- Journey lagrer evidence og revision sammen med domenefakta eller etter
  domenets commit i en idempotent transaksjonskjede.
- På load kjøres bounded schema validation og targeted reconciliation. Det
  foretas ingen global chest/entity-scan.
- En restart spiller ikke completion-lyd eller betaling på nytt.
- En reconnect åpner siste server-snapshot; klientcache kan aldri vinne.
- En pågående fysisk transport gjenopptas fra den inventory-eieren som faktisk
  har stacken, ikke fra forrige animasjonsfase.
- Uklar fysisk eierskap feiler lukket til recovery, aldri ved å duplisere eller
  slette.

### 12.2 Obligatoriske checkpoints

| Checkpoint | Må lagres | Etter restart skal dette være sant |
|---|---|---|
| Founding committed | settlement-ID, Hearth, founders, Journey state/evidence | Én bosetning, tre founders og FJ-010 én gang |
| Tech unlocked | node-ID, cost transaction, tech revision | Node åpen; kost borte én gang; Build Plan bare tilgjengelig |
| Emblem kjøpt, ikke bundet | fysisk emblem ItemStack og trade transaction | Emblemet er fortsatt i samme sikre inventory; ingen profession |
| Emblem bundet | profession ID, bound emblem identity, workplace ID | Ingen løs emblem-kopi; settler beholder profession/post |
| Request OPEN | full typed contract og stack policy | Nøyaktig én åpen request med samme ID og alder |
| RESERVED/PICKUP | Courier-ID, source reservation, exact fingerprints | Ingen annen Courier kan claim; source stack er fortsatt entydig |
| IN_TRANSIT | stack i Courier bag, request state, route endpoints | Stack finnes bare i bag; Courier fortsetter eller returnerer sikkert |
| Work-zone committed | type, corners/field, dimension, revision | Samme bounded zone vises og worker holder seg innenfor |
| Workplace output | workActionId, destination, item delta | Output finnes én gang i workplace storage |
| Recruitment timer | cycle, target, qualified elapsed, blocker | Kontinuerlig tid fortsetter; offline realtid teller ikke |
| Traveler en route | traveler ID, route target, cycle | Samme traveler fortsetter; ingen ny kandidat spawner |
| Traveler waiting | traveler ID, Tavern anchor, offer/price revision | Kandidaten venter fortsatt og auto-konverteres ikke |
| Warning queued | warningNight, attackNight, exact RaidPlan | Samme varsel og plan vises; ingen ny random captain/objective |
| Raid ACTIVE | plan, sealed participants, active counters, physical losses | Samme raid fortsetter; døde deltakere respawner ikke |
| Raid resolved | terminal state, RaidLogEntry, reward eligibility/claim | Ingen ny resolution/reward; FJ-620 kan åpne rapporten |

### 12.3 Obligatoriske restart-bevis

Minst to rene dedikerte serveromstarter skal inngå i end-to-end-beviset.
Samlet testpakke skal i tillegg restartes ved:

1. åpen Lumberer axe-request;
2. Courier i IN_TRANSIT med minst én log;
3. traveler som venter på eksplisitt approval;
4. første raid-varsel som er queued;
5. ACTIVE raid med sealed participant ledger.

---

## 13. Save-migrering

### 13.1 Prinsipper

- Migrering gir aldri mer verdi enn den gamle saven beviselig eide.
- Gamle worlds relockes ikke stille.
- Friske Journey v2-worlds får ingen legacy-unntak.
- Ukjent/fremtidig state quarantines og endres ikke ved gjetting.
- Stable string/wire-ID brukes for tech, steps, requests, professions, Guard
  modes og raid states.

### 13.2 Founding Journey v1 til v2

| Gammel state | V2-adferd |
|---|---|
| BUILD_LUMBER_CAMP | Aktiver v2. Migrer FJ-010 bare når Hearth/settlement/founders kan bevises. Mayor og senere fakta må gjøres eller bevises separat. |
| HIRE_LUMBERER | Aktiver v2. Grandfather Lumber Camp-tech/building bare hvis eksakt registered valid building finnes. Ikke opprett emblem eller worker. |
| DELIVER_FIRST_LOG | Aktiver v2. Migrer camp og eksisterende worker/post som dokumentert under, men krev den nye fysiske storage/logistikkloopen fra første ubeviste steg. |
| COMPLETE | Ikke vekk et etablert world midt i onboarding. Behold presentationMode SKIPPED/legacy-complete og tilby eksplisitt Resume upgraded Journey. |
| SKIPPED | Behold SKIPPED. Ikke gi unlocks. Tilby opt-in til den nye presentasjonen uten å endre domenefakta. |
| QUARANTINED eller ukjent | Sett v2 QUARANTINED og skriv recovery-rapport; ingen overgang eller reward. |

Resume upgraded Journey går til første steg som kan bevises trygt av migrert
evidence og levende state. Det fullfører ikke steg bare fordi byggtypen finnes
et sted i verden.

### 13.3 Bygg og tech

- Hvert eksisterende, gyldig registrert bygg grandfather-unlocker sin egen node
  og dokumenterte prerequisites for det settlementet.
- Eksisterende Build Plan-items kan beholdes, men gir ikke nye unlocks eller
  kopier.
- Ugyldige/dupliserte building records quarantines målrettet.
- Parkerte bygg åpner ikke uimplementerte noder ved migrering.

### 13.4 Workers, emblems og utstyr

- En gammel gyldig profession/workplace-relasjon gir ett grandfathered
  bound-emblem-record.
- Det spawnes ikke et løst emblem.
- Profession og workplace lagres separat.
- En fysisk gammel tool-stack som faktisk finnes i settler-/building-inventory
  beholdes én gang.
- Et renderer-only eller automatisk regenerert projection-tool materialiseres
  ikke.
- Ved tvil quarantines equipment-slotten og rapporteres; det genereres aldri
  både gammelt og nytt verktøy.

### 13.5 Courier og storage

- Gamle building inventories registreres som workplace storage uten stille
  stackflytting.
- En gammel in-flight Courierjobb konverteres bare hvis source, carrier og
  target kan bevises entydig.
- Ellers returneres den ene fysiske stacken til den beviste sikre eieren før ny
  ledger aktiveres.
- Ingen gammel direkte-to-Hearth-rute beholdes som skjult bypass.

### 13.6 Første raid

- Eksisterende SCHEDULED raid beholder exact warningNight, attackNight og plan.
- ACTIVE beholder plan, participants og counters.
- COMPLETED beholder logg, outcome og reward claim-state.
- Bare nye, friske settlements går inn i PREPARING.
- UNINITIALIZED legacy-world får ikke automatisk et overraskelsesraid uten
  dokumentert migration/opt-in policy.

---

## 14. Post-raid capstone: tre doktriner og senere fem spesialiseringer

### 14.1 Deadlock-fri overgang

Doktrinevalg er med vilje ikke et av de 45 kravene for å fullføre første raid.
Å tvinge et strategisk savevalg før etterrapporten er lest kan softlocke
Journey, skape feilklikk i kampøyeblikket og gjøre nederlagshåndtering uklar.

Den bindende sekvensen er:

1. FJ-610 lagrer utfallet.
2. FJ-620 åpner rapporten og fullfører Journey to First Raid.
3. Rapportens eneste primære neste-knapp er Åpne doktrinerådet.
4. Neste Saga-kort har arbeids-ID
   hearthstead:journey/post_raid/pr_010_doctrine_council.
5. Rådet viser alle tre retninger, effekt, kost og reverseringsregel.
6. Spilleren kan velge én eller trykke Velg senere. Ingen stille standard.
7. Velg senere lar verden fortsette og lar kortet stå som tydelig neste
   strategiske steg; det fjerner ikke first-raid completion.

Dermed er doctrine council første post-raid capstone/next step, mens core
Journey forblir deadlock-fri.

### 14.2 Bindende doktrinemodell

Det finnes tre aktive hovedretninger. Arbeidsnavn og endelige lokaliserte navn
må eiergodkjennes før de fryses, men funksjonene er bindende:

| Retning | Spillidentitet | Fordel | Synlig mulighetskost |
|---|---|---|---|
| Forsvar | Sterkere beredskap, raskere Guard response og bedre kamputholdenhet | Første balansetarget er omtrent 15 prosent bedre Guard response/recovery og høyere prioritet på defense requests | Civilian work cadence omtrent 10 prosent langsommere og Guard-rations øker med én ready meal per Guard per døgn |
| Produksjon | Raskere, mer effektiv worker-økonomi og bedre workplace flyt | Første balansetarget er omtrent 12 prosent kortere worker action cooldown og 15 prosent høyere logistikk-throughput før køtrykk | Guards reagerer/trener omtrent 8 prosent langsommere og defense requests får ikke prioritetsovertaket |
| Fellesskap | Rekruttering, morale, Tavern og robust Courier-service | Første balansetarget er omtrent 15 prosent kortere qualification target og 10 prosent mer Courier-bagkapasitet | Peak worker- og Guard-cadence er omtrent 5 prosent lavere enn nøytral spesialisering |

Tallene er første mål for måling, ikke wireformat. Retning, synlig tradeoff og
at forskjellen er merkbar er bindende. Tuning krever dokumentert survival- og
ytelsesbevis.

### 14.3 Reversering uten saveødeleggelse

- Første doctrine choice etter første raid har ingen materialkost; det er
  raidets strategiske belønning.
- Bare én hoveddoktrine er aktiv samtidig i første versjon.
- Bytte kan gjøres ved Hearth/Mayor etter minst tre Minecraft-døgn.
- Første respec-kost er 8 brød + 16 planker, data-drevet og fullt synlig før
  bekreftelse.
- Betaling og doctrine switch er én atomisk transaction med expected revision.
- Rejected/stale switch forbruker ingenting.
- Switching sletter aldri tech, bygg, inventory, requests, emblems, levels,
  settlers eller spesialiseringsevidence.
- Gamle bonuser fjernes og nye aktiveres ved én servercommit; ingen tick med
  begge.
- Cooldown og fysisk kost hindrer gratis buff-swapping, men spilleren kan
  reparere et dårlig valg.
- En doktrine får aldri redusere en grunnloop under spillbar minimumsfunksjon.

I co-op krever doctrine-endring settlement permission, en full konsekvens-
preview og én atomic winner per revision. Andre viewers får umiddelbar delta.

### 14.4 Fem senere spesialiseringer

De tre retningene utvider senere til totalt fem spesialiseringskategorier:

1. fortification — byggvern, reparasjon og statiske forsvar;
2. warband — mobile Guards, patrol og aktiv respons;
3. industry — produksjonsdybde, foredling og worker-effektivitet;
4. stewardship — mat, lager, bærekraft og robust logistikk;
5. hospitality/trade — Tavern, rekruttering, gjester og handel.

Fortification og warband springer naturlig fra forsvarsretningen. Industry
fordyper produksjonsretningen. Stewardship og hospitality/trade springer fra
fellesskapsretningen, med mulige krysskrav som vises åpent.

Disse fem er bindende funksjonskategorier, men display names, ikoner,
ResourceLocation-ID-er og endelig balance fryses ikke før eiergodkjenning.
Ingen provisional ID skal skrives i save. Tech Tree kan vise silhuett/retning,
men skal ikke love en uimplementert effekt.

### 14.5 Doktrinetester

- Valg er umulig før FJ-620, men åpning av council kan forhåndsvises.
- Ingen silent default ved lukking, reconnect eller restart.
- Første valg committer én gang.
- To samtidige valg gir én vinner og én STALE.
- Cooldown og fysisk respec-kost håndheves.
- Switching bevarer alle items, bygg, workers, emblems, requests og tech.
- Hver retning gir både målt fordel og målt kost i 1/25/50/100-settlerprofil.
- Ingen doktrine er nødvendig for å resolve eller rapportere første raid.

---

## 15. Én felles kortbeskrivelse for alle bygninger

### 15.1 Single source of truth

Eksisterende lokale nøkkelfamilie
hearthstead.building.benefit.<building_id> blir canonical short description.
BuildingType skal eksponere nøyaktig én metode, for eksempel:

~~~text
BuildingType.shortDescriptionKey()
~~~

Både:

- BuildPlanItem tooltip/Build Plan-skjerm; og
- Tech Tree node/tooltip

skal lage sin Component fra denne samme metoden. Ingen av skjermene får eie en
kopiert tekststreng eller en separat description-key.

### 15.2 Tekstkrav

- EN_US og NB_NO finnes for hver aktiv BuildingType.
- Teksten er funksjonsførst og vanligvis én til to korte linjer.
- Den beskriver hva bygningen faktisk gjør nå.
- Den lover ikke worker-loop, bonus eller automasjon som ikke er implementert.
- Låste noder viser fortsatt kortbeskrivelsen, i tillegg til åpne
  prerequisites og kost.
- Parkerte bygg skjules eller merkes ærlig Ikke i drift ennå.
- Ingen viktig betydning uttrykkes bare med farge.
- Tile kan vise to linjer og full tooltip ved behov; teksten hardkuttes ikke.

### 15.3 Automatiske krav

En test itererer alle aktive BuildingType-verdier og krever:

1. ikke-tom canonical key;
2. ikke-tom EN_US-oversettelse;
3. ikke-tom NB_NO-oversettelse;
4. Build Plan og Tech Tree returnerer samme key;
5. ingen rå key vises ved manglende locale;
6. 1280×720 og guiScale 2/3/4 wrapper uten overlap;
7. unreleased building kan ikke fremstå som funksjonell.

---

## 16. UI-, animasjons-, tekstur- og lydkrav

### 16.1 Skjermer som skal designes og bevises

Før endelig Java-layout godkjennes, skal offline previews og deretter ekte
in-game captures finnes for:

- Hearth Journey;
- Tech Tree;
- Mayor Emblem Catalog;
- Build Plan tooltip/detail;
- Settler Profile;
- Settler Inventory;
- Equipment og Requests;
- Workplace Storage;
- Global Request Ledger;
- Courier Task Detail;
- Work-zone selection og confirmation;
- Recruitment checklist og traveler candidate;
- Guard Command Staff og route editor;
- Raid readiness;
- Raid warning;
- Aftermath report;
- post-raid Doctrine Council.

Hver relevant skjerm skal vises i:

- empty;
- normal;
- blocked;
- full;
- permission denied;
- stale revision;
- regression/recovery;
- complete.

### 16.2 Visuell kontrakt

- Samme Hearthstead materialrammer, spacing, ikonfamilie, typografi og motion
  brukes gjennom hele reisen.
- Aktiv handling er lett å finne uten at hele skjermen pulserer.
- Motion forklarer state change; den må ikke forsinke input eller servertruth.
- Alle animasjoner har reduced-motion/færre effekter-path der plattformen
  støtter det.
- Status har ikon, tekst og form i tillegg til farge.
- Lange EN/NB-strenger, lange settlement-/settlernavn og tall med fire sifre
  overlapper ikke.
- 1280×720 er hard minimum, ikke en nedskalert ettertanke.
- Textures og ikoner er skarpe på guiScale 2/3/4 og bruker konsistent
  pixel-density/filtering.
- Locked, available, purchased, active, blocked og stale er visuelt forskjellige
  uten å bryte det samme designspråket.

### 16.3 Animasjonsbevis

Video skal vise:

1. Shift-empty-hand åpner ekte settler-inventory.
2. Direkte axe-delivery flytter fysisk item og endrer request.
3. Lumbererens pickup, gange, trekontakt og stowing.
4. Worker legger logs i workplace storage.
5. Courier tar loggen, stuer i ryggsekk og går uten frontlåste armer.
6. Courier leverer fra ryggsekken til Warehouse.
7. Farmer bruker fysisk hoe, frø, felt og workplace storage.
8. traveler ankommer Tavern og venter på eksplisitt Recruit.
9. Guard går til Stand Post og følger Patrol Route.
10. Combat override og retur til ordre.
11. Raid warning, ankomst, resolution og aftermath.

Pickup-, carry- og walk-klipp vurderes ved normal hastighet, ikke bare
frame-by-frame. Foot sliding, armelås, teleportert item eller desynk mellom
lyd/kontakt er releaseblokker.

### 16.4 Lydkontrakt

Dagens generiske PLAYER_LEVELUP- og EXPERIENCE_ORB_PICKUP-cues skal ikke være
Journeyens endelige lydspråk.

- Journey har egne diskrete start/progress/chapter/complete-familier.
- Completion-lyd spilles etter servercommit og maksimalt én gang lokalt.
- Arbeidskontakt treffer synlig kontakt innen én renderframe eller én
  server-tick.
- Repeterende arbeid har 3–6 kontrollerte varianter.
- Courier har cloth/leather/strap, stow, rummage og handoff; ingen kontinuerlig
  høy sekkelyd.
- UI, arbeid, Blessing, stemme, kamp og ambience er separate mixfamilier.
- Crowd cooldown og voice budget hindrer lydvegg med mange settlers.
- Ingen stand-in-lyd godkjennes bare fordi filformat og mono-validator passerer.
- Hver kritisk kontakt kjøres minst 50 repetisjoner uten dobbel trigger,
  manglende trigger eller merkbar sync-drift.

### 16.5 Obligatorisk client playthrough-matrise

| Løp | Locale / oppløsning / GUI | Bolig / raidprofil / spillere | Påtvunget checkpoint |
|---|---|---|---|
| A | NB_NO, 1280×720, guiScale 3 | House, Peaceful, 1 spiller | Restart ved axe-request og warning queued |
| B | EN_US, 1920×1080, guiScale 2 | Lodging, Balanced, 1 spiller | Restart Courier IN_TRANSIT og raid ACTIVE |
| C | NB_NO, 1920×1080, guiScale 4 | House, valgt delt settlement, 2 spillere | Restart traveler waiting approval |
| D | EN_US, 1280×720, guiScale 4 | Live regression, 2–4 spillere | Stale actions, reconnect, invalidert bygg og recovery |

I tillegg tas screenshot-sweep på guiScale 2, 3 og 4 for begge språk og begge
referanseoppløsninger.

### 16.6 Realistisk tidsbevis

Med dagens policy tar recruitment qualification alene 40–80 minutter ekte
spilletid, fordi 2–4 Minecraft-døgn er 2 400–4 800 sekunder. Warning kommer
deretter én eller to netter etter readiness.

Derfor:

- En 10-minutters test er bare smoke test.
- GameTest kan bruke en eksplisitt testclock.
- Den manuelle release-runnen kan ikke bruke time commands, adminspawns,
  gratis items eller tvunget completion.
- Minst ett ekte HELD-løp dokumenteres fra fresh world til aftermath.

---

## 17. GameTest- og automatisert testmatrise

Foreslåtte testnavn er bindende i betydning, men kan tilpasses prosjektets
registreringskonvensjon. Testene skal feile med konkret invariant, ikke bare
timeout.

### 17.1 Journey-state og wireformat

**JourneyV2StateGameTests**

- freshJourneyStartsAtFj010WithEmptyEvidence
- currentSchemaRoundTripsEveryPresentationModeAndOutcome
- stableStepIdsRoundTripIndependentOfDefinitionOrder
- duplicateTransactionCompletesStepExactlyOnce
- outOfOrderEventCannotCompleteDependentStep
- clientSnapshotCannotCompleteServerStep
- adminAndTestEvidenceDoNotCountAsSurvival
- futureSchemaAndUnknownStepQuarantineFailClosed
- completedEvidenceRemainsWhileLiveRegressionBlocksDependency
- skipHidesPresentationArmsCountdownAndGrantsNothing

### 17.2 Founding, Tech Tree, descriptions og emblems

**FoundingTechJourneyGameTests**

- foundingCommitsHearthSettlementAndThreeFoundersAtomically
- commonTrunkRejectsOutOfOrderUnlock
- oldBuildPlanCannotBypassLockedTech
- techCostIsConsumedOnceOnlyAfterApplied
- lumbererEmblemTradeAndBindingAreCorrelated
- rejectedStaleOrCrossSettlementBindingConsumesNothing
- emblemBindingCreatesNoToolOrOtherItem
- everyActiveBuildingHasSharedEnAndNbShortDescription
- buildPlanAndTechTreeUseIdenticalDescriptionKey
- unreleasedBuildingCannotPromiseActiveGameplay

### 17.3 Interaction og ekte settler-inventory

**SettlerInteractionJourneyGameTests**

- interactionPriorityMatchesBindingTableForBothHands
- blessingWinsOverEmblemAndRequestedDelivery
- requestedDeliveryWinsOverEmptyHandInventory
- shiftEmptyBothHandsOpensRealInventoryMenu
- nonEmptyOffhandCannotSilentlyOpenInventory
- normalClickOpensProfileNotInventory
- wrongDistancePermissionDimensionSettlementAndRevisionFailClosed
- twoViewersConvergeAndStaleMutationMovesNoStack
- physicalHeldRenderedRequestedAndPersistedStackStayIdentical

### 17.4 Lumberer, equipment og work-zone

**LumberJourneyGameTests**

- professionProjectionNeverCreatesFreeAxe
- bindingCreatesOnePersistentAxeRequest
- compatibleDirectAxeDeliveryConsumesExactlyOnePhysicalStack
- invalidTierDurabilityOrPolicyDoesNotSatisfyRequest
- boundedLumberZoneRejectsOversizeCrossDimensionAndWorldBorder
- lumbererNeverClaimsTreeOutsideCommittedZone
- deletedTargetProducesNoValidTargetWithoutBusyLoop
- treeWorkConsumesDurabilityOnce
- logsCommitToCampStorageNotHearth
- fullCampStopsWithFullWorkplaceAndPreservesOutput
- axeRequestAndZoneSurviveTwoRestarts

### 17.5 Request ledger, Courier og item conservation

**RequestCourierJourneyGameTests**

- outputPickupRequiresConcreteSourceStack
- duplicateOutputDoesNotCreateDuplicateOpenRequest
- twoCouriersYieldOneReservationWinner
- reservationBindsExactStackFingerprint
- requestTransitionsFollowLegalStateMachineOnly
- campToBagToWarehouseConservesItemCountAndComponents
- inTransitStackExistsInBagOnly
- restartAtEveryRequestStateConverges
- courierDeathChunkUnloadFullBagAndFullTargetRecoverSafely
- noStockRemainsBlockedWithoutBusyLoop
- agingProvidesBoundedFairness
- crossSettlementRouteFailsClosed
- courierTransportAnimationStateNeverClaimsFrontHeldCargo

### 17.6 Farmer og mat

**FarmerJourneyGameTests**

- farmerBindingCreatesNoFreeHoe
- registeredFarmFieldRejectsCropsOutsideZone
- hoeRequestCanBeSatisfiedDirectlyOrByCourierExactlyOnce
- seedInputIsPhysicalAndCropCompatible
- harvestCommitsToFarmhouseStorage
- cropPickupConservesStackToWarehouse
- rawNonReadyCropDoesNotSpoofReadyFood
- farmhouseFullStopsWorkerWithoutDropOrDelete

### 17.7 Housing, Tavern og recruitment

**RecruitmentJourneyGameTests**

- houseAndLodgingBothSatisfyCapacityWhenValid
- capacityAtPopulationDoesNotSatisfySpareBed
- attractionUsesSameRecruitmentAssessmentAsUiAndAdmission
- morale59BlocksAndMorale60Qualifies
- oneMissingPriceItemBlocksWithoutPartialPayment
- pricePaymentStillLeavesExactRequiredReadyFood
- populationThreeRequiresThirtyTwoReadyMealsAfterPrice
- qualificationAtTargetMinusOneSecondDoesNotComplete
- qualificationAtExactStableTargetCompletes
- regressionInterruptsContinuousQualificationDeterministically
- travelerEdgeSpawnDoesNotCountAsArrival
- travelerAtExactTavernAnchorCountsOnce
- travelerWaitsForExplicitRecruit
- twoPlayersRecruitSameTravelerYieldOneAtomicWinner
- restartDuringTimerEnRouteAndWaitingPreservesCycle

### 17.8 Guard og readiness

**GuardJourneyGameTests**

- guardBindingCreatesNoFreeWeaponOrArmor
- physicalWeaponRequestAndDeliveryAreRequired
- standPostPersistsPositionFacingAndLeash
- patrolRequiresTwoToEightOrderedValidPoints
- patrolLoopAndPingPongAreDeterministic
- towerPostRejectsMissingTechOrInvalidWatchtower
- combatOverrideReturnsGuardToAssignment
- automaticEightPointRingDoesNotSatisfyJourneyOrder
- readinessReportsEveryMissingRequirement
- readinessFailureChangesNoRaidStateOrDate
- readinessExactSuccessCommitsOnce
- requestOwnershipConflictBlocksReadiness
- readyFoodSubtractsPhysicalReservations

### 17.9 Første raid

**FirstRaidJourneyGameTests**

- firstRaidWireIdsZeroToThreeRemainUnchangedAndPreparingIsFour
- foundingRollPersistsInclusiveOffsetFourToSeven
- readinessBeforeRolledMinimumKeepsRolledMinimum
- lateReadinessGuaranteesFullWarningLead
- warningNightEqualsAttackNightMinusLead
- restartNeverRerollsFirstRaidDatesOrPlan
- warningCommitsExactlyOnce
- attackUsesExactWarnedPlan
- participantLedgerSealsAndIgnoresUntrackedMobs
- resolutionCommitsOneOutcomeLogAndRewardEligibility
- activeAndResolvedRaidSurviveRestart
- legacyScheduledActiveCompletedKeepExactDatesAndState
- settlementLostProducesRecoveryNotVictoryPresentation

### 17.10 Full vertikal loop

**JourneyToFirstRaidGameTests**

- freshSurvivalJourneyReachesAndResolvesFirstRaidWithItemConservation
- freshSurvivalJourneyRejectsEveryKnownBypass
- twoPlayerJourneyConvergesAcrossStaleActionsAndReconnect
- fullJourneyRoundTripsAcrossAllMandatoryRestartCheckpoints

Den første testen skal føre konkrete stacks gjennom hele loopen og kontrollere
sluttregnskapet. Testclock er tillatt bare fordi den markeres TEST og ikke kan
produsere SURVIVAL-evidence.

### 17.11 Ytelse

**JourneyLogisticsPerformanceTests**

- 1, 25, 50 og 100 aktive settlers;
- event-indexed workplace storage uten global chest-scan;
- bounded request retries og queue growth;
- ingen dobbeltpathing før reservation;
- incremental work-zone search uten chunk force-load;
- Journey snapshot/delta bare ved revision, ikke hvert tick;
- lyd- og partikkelbudget ved chapter completion og raid warning.

---

## 18. Konkret filkart

Dette er implementeringskartet, ikke tillatelse til å blande alle endringer i
én commit. Eksisterende filer er merket EKSISTERER; foreslåtte nye filer er
merket NY.

### 18.1 Journey og persisted state

- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/state/FoundingJourney.java
  — erstattes/migreres til v2 state uten å bryte v1 decoder.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/FoundingJourneyProgress.java
  — blir tynt event-adapterlag eller erstattes av service.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/Settlement.java
  — eier Journey state, outcome og references.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/SettlementSavedData.java
  — schema/load/save/migration.
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/journey/JourneyDefinition.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/journey/JourneyStep.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/journey/JourneyEvidence.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/journey/JourneyProgressService.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/journey/JourneyMigration.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/journey/JourneyBlocker.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/journey/JourneySnapshot.java

### 18.2 Hearth UI og nettverk

- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/block/HearthBlockEntity.java
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/menu/HearthMenu.java
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/HearthScreen.java
- NY package:
  hearthstead-neoforge/src/main/java/com/hearthstead/network/journey/
  — open action, snapshot/delta, step action, world highlight og stale result.
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/JourneyScreen.java
  eller en klart avgrenset Journey-tab-komponent.

### 18.3 Tech Tree, Mayor og byggbeskrivelse

- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/building/BuildingType.java
  — canonical shortDescriptionKey.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/item/BuildPlanItem.java
  — bruker BuildingType.shortDescriptionKey.
- NY package:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/tech/
  — CivicTechState, CivicTechNode, CivicTechService, unlock transaction og
  migration.
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/TechTreeScreen.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/MayorEmblemScreen.java
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/Employment.java
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/entity/Profession.java
  — free-tool paths fjernes som itemkilde.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/entity/SettlerEntity.java
  — bound emblem, physical gear og interaction router.

### 18.4 Ekte inventory og requests

- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/menu/SettlerInventoryMenu.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/SettlerInventoryScreen.java
- NY package:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/request/
  — RequestLedger, RequestRecord, RequestState, EquipmentContract,
  MaterialContract, reservation og recovery.
- NY package:
  hearthstead-neoforge/src/main/java/com/hearthstead/network/request/
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/RequestLedgerScreen.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/RequestDetailScreen.java

### 18.5 Workplace storage, Lumberer, Farmer og Courier

- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/block/PlaqueBlockEntity.java
  — explicit storage contract.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/network/StorageNetwork.java
  — må ikke forveksles med gameplay request-nettverk.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/warehouse/WarehouseIndex.java
  — event-indexed physical stock truth.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/entity/ai/LumbererWorkGoal.java
  — zone, equipment contract og workplace output.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/entity/ai/FarmerWorkGoal.java
  — field, hoe/seed requests og workplace output.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/entity/ai/CourierWorkGoal.java
  — dagens fire ruter uten request queue erstattes med ledger claim/transport.
- NY package:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/workzone/
  — WorkZone, WorkZoneService, validation, cache og tools.
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/item/WorkScepterItem.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/overlay/WorkZonePreview.java

### 18.6 Recruitment

- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/RecruitmentPolicy.java
  — fortsatt eneste assessment-kilde.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/SettlementManager.java
  — attraction/admission må splitte arrival fra eksplisitt recruit.
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/RecruitmentChecklistScreen.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/TravelerCandidateScreen.java
- NY package:
  hearthstead-neoforge/src/main/java/com/hearthstead/network/recruitment/
  — candidate snapshot og atomic Recruit action.

### 18.7 Guard og raid

- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/state/GuardOrder.java
  — utvides med player-issued modes og data.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/entity/ai/GuardPatrolGoal.java
  — konsumerer ordre i stedet for bare automatisk ring.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/entity/ai/GuardRespondGoal.java
  — combat override og retur.
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/item/CommandStaffItem.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/guard/GuardAssignmentService.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/overlay/GuardRoutePreview.java
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/state/FirstRaidState.java
  — PREPARING wire-ID 4.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/state/RaidLifecycle.java
  — readiness-anchored dato og migration.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/raid/RaidDirector.java
  — eksakt warning/plan/start/resolution.
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/raid/RaidTelegraph.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/raid/FirstRaidReadinessService.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/RaidReadinessScreen.java
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/RaidAftermathScreen.java

### 18.8 Doktriner

- NY package etter owner naming approval:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/doctrine/
  — DoctrineState, DoctrineDefinition, DoctrineService og respec transaction.
- NY:
  hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/DoctrineCouncilScreen.java

### 18.9 Resources

- EKSISTERER:
  hearthstead-neoforge/src/main/resources/assets/hearthstead/lang/en_us.json
- EKSISTERER:
  hearthstead-neoforge/src/main/resources/assets/hearthstead/lang/nb_no.json
- Journey-, blocker-, tech-, request-, recruitment-, guard-, raid- og doctrine-
  keys legges i begge.
- Eksisterende hearthstead.building.benefit.* er canonical building short
  description.
- UI-atlas, ikoner, textures, sound events og OGG-filer følger eksisterende
  assets/hearthstead-struktur og kvalitetssjekkes visuelt/semantisk.

### 18.10 Tester

- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/settlement/FoundingJourneyGameTests.java
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/gametest/EmploymentGameTests.java
- EKSISTERER:
  hearthstead-neoforge/src/main/java/com/hearthstead/gametest/RecruitmentPolicyGameTests.java
- NY/utvidet testsuite følger gruppene i seksjon 17 under prosjektets
  eksisterende GameTest-registrering.
- Client playthrough captures og rapporter lagres under artifacts/qa eller
  prosjektets vedtatte bevismappe, ikke som påstander i kodekommentarer.

---

## 19. Implementeringsrekkefølge og release gates

Rekkefølgen er valgt for å unngå å bygge premium UI over falske domeneevents.
Hver gate må være grønn før neste systemslice erklæres ferdig.

### Gate 1 — Journey v2 state

1. State/evidence/revision/idempotency.
2. Strict decoder, quarantine og v1 migration.
3. Snapshot/delta og Hearth-panelets sannferdige basis.
4. Ingen gameplaybelønning ennå.

**Gate:** state/wire/migration-tests grønne; ingen eksisterende save ødelagt.

### Gate 2 — Felles tech-stamme og emblems

1. first_fire til first_watch som én lineær trunk.
2. Atomic physical tech costs.
3. Mayor catalog og fysisk emblemhandel.
4. Bound emblem og workplace separat.
5. Canonical building short description i Build Plan og Tech Tree.

**Gate:** ingen Build Plan-/Appoint-bypass; beskrivelsestest EN/NB grønn.

### Gate 3 — Ekte settler-inventory

1. Interaction-router.
2. Fysiske slots, gear, reserved-view og requests.
3. Shift-empty-both-hands.
4. To viewers, stale og recovery.

**Gate:** fysisk inventory er eneste truth; renderer-only gear kan ikke spoofe.

### Gate 4 — Lumberer equipment-loop

1. Fjern gratis øks.
2. Equipment contract og persistent request.
3. Direkte fysisk axe-delivery.
4. Tool use/durability/replacement.

**Gate:** FJ-150 og FJ-160 består restart og itemregnskap.

### Gate 5 — Work-zone og Lumber Camp storage

1. Work Scepter og bounded preview.
2. Lumberer target cache og zone enforcement.
3. Worker output til workplace storage.
4. Full storage blocker.

**Gate:** ingen tre utenfor zone og ingen direkte-to-Hearth output.

### Gate 6 — Request Ledger, Courier og Warehouse

1. Typed persistent ledger.
2. Unique reservation og item fingerprints.
3. Courier bag inventory og state machine.
4. Physical camp → bag → Warehouse.
5. Ny carry/stow/handoff-animasjon og lyd.

**Gate:** restart i hver state og to-Courier item conservation grønt.

### Gate 7 — Farmhouse og mat

1. Farmer emblem uten hoe.
2. Field-zone, hoe- og seed-request.
3. Farmhouse output.
4. Courier crop delivery og ReadyFood truth.

**Gate:** matloopen er fysisk, zonestyrt og restart-sikker.

### Gate 8 — Housing, Tavern og explicit recruitment

1. House/Lodging capacity.
2. Permanent Neste settler-checklist.
3. RecruitmentPolicy som eneste assessment.
4. 2–4-dagers continuous clock.
5. Traveler fysisk til Tavern.
6. Candidate card og atomic Recruit.

**Gate:** ingen auto-convert; alle boundary-, restart- og co-op-tester grønne.

### Gate 9 — First Watch og Guard control

1. Barracks/Guard Emblem uten gear.
2. Weapon request/delivery.
3. Command Staff.
4. Stand, patrol og tower validation.
5. Combat override/return.

**Gate:** minst én spillerstyrt Guard kan bevises visuelt og automatisk.

### Gate 10 — Readiness-anchored første raid

1. PREPARING wire-ID 4.
2. Persisted founding roll.
3. Atomic readiness checklist.
4. Eksakt warning/attack formula.
5. Warning, sealed participants, outcome og report.

**Gate:** dato/plan endres aldri etter restart; full loop GameTest grønn.

### Gate 11 — Premium presentasjon

1. Offline previews.
2. Implementert UI og responsive states.
3. Ikon-/texture-pass.
4. Motion-pass.
5. Custom sound families og mix.
6. EN/NB og accessibility.

**Gate:** hele screenshot-, video- og 50-repetition audio/motion-matrisen består.

### Gate 12 — Releasebevis

1. Fire client-løp fra seksjon 16.
2. To rene serveromstarter i hver relevant beviskjede.
3. 2–4-spiller stale/concurrency.
4. 1/25/50/100-settlerprofil.
5. Minst ett fresh, uten-admin, normal-time HELD-løp.
6. Aftermath peker til Doctrine Council.

**Gate:** ingen blocker, dupe, tap, silent failure, gratis item eller falsk
completion. Først da kan Journey to First Raid kalles known-working.

---

## 20. Release-definisjon

Journey to First Raid er ferdig bare når en frisk survival-spiller kan vise,
uten forklaring utenfra:

1. hvordan Hearth, Journey og Mayor henger sammen;
2. hvordan bygg åpnes i én naturlig tech-stamme;
3. hvordan første Lumberer Emblem kjøpes og bindes fysisk;
4. hvordan Shift + tom hånd åpner ekte settler-inventory;
5. at worker ikke får gratis gear, men requester det;
6. at fysisk øks kan leveres direkte uten dupe;
7. hvordan arbeidsområder styres;
8. at worker output går til eget workplace storage;
9. hvordan Request Ledger forklarer flyten;
10. at Courieren henter, bærer i ryggsekk og leverer fysisk til Warehouse;
11. hvordan Farmer, felt, frø, crop og matreserve virker;
12. nøyaktig hva som mangler for neste settler;
13. at traveler når Tavern og krever eksplisitt Recruit;
14. hvordan Guard bevæpnes og får stand/patrol;
15. hvilke readiness-krav som mangler;
16. at countdown først armer etter readiness;
17. at varselet og angrepet bruker samme lagrede plan;
18. at første raid resolve-er uten falske deltakere eller dobbel reward;
19. at aftermath viser sant resultat;
20. at neste synlige steg er tre reversible doktriner som senere blir fem
    spesialiseringer.

---

## 21. Eksplisitte antakelser som er låst for denne spesifikasjonen

1. Dagens RecruitmentPolicy beholdes foreløpig: morale minst 60, grunnpris
   4 brød + 8 planker, to dagers ready-food-reserve og stabil 2–4-dagers
   attraction. Senere balansetuning skal ikke splitte assessment-kilden.
2. Home-noden åpner både House og Lodging som reelle alternative bygg.
3. First Watch åpner Barracks, Guard Emblem og Command Staff. Watchtower er
   implementert i kontrollsystemet, men ikke et første-raidkrav.
4. Peaceful/Balanced-profil bruker to netters warningLead og den hardeste
   profilen kan bruke én, i tråd med eksisterende raidpolicy.
5. Doctrine-funksjonene og reversible tradeoffs er bindende. Endelige navn,
   ikoner, stable IDs og de fem specialization-navnene krever eiergodkjenning
   før de skrives i save.
6. SETTLEMENT_LOST gir ærlig recovery-presentasjon og bevarer save; det
   forfalskes ikke til HELD og trigger ikke automatisk rollback.
7. Functional request/logistics behavior er clean-room-inspirert av
   MineColonies. Ingen kode, tekst, texture, lyd eller UI-layout kopieres.

---

**Bindende sluttpunkt:** Common tech trunk slutter ved første raid. FJ-620
fullfører den kjente, testede reisen og peker direkte til
post_raid/pr_010_doctrine_council. Strategi begynner først etter at spilleren
har lært økonomi, vekst, logistikk og grunnforsvar i praksis.
