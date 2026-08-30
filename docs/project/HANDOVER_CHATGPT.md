# Hearthstead — full, ærlig overlevering til Claude

**Oppdatert:** 28. august 2026. Les hele filen før du endrer noe.

> **Sann status:** Et stort nytt systemlag for UI, logistikk, rekruttering,
> animasjon, guard og første raid er implementert i et svært dirty arbeidstre.
> Modden er **ikke leveringsklar**. Siste fullførte autoritative GameTest:
> **552 / 600 bestått, 48 feil**. Feilene ble deretter analysert og patched
> eller fikk moderniserte fixtures, men dette er **ikke validert** av en ny
> GameTest. Siste quick bygget kildekoden og passerte asset-/animasjonssjekker,
> men ble avbrutt før `QUICK: PASS`. Ikke installer eller lever ny JAR ennå.

## 1. Prosjektkart og sikkerhet

| Felt | Verdi |
| --- | --- |
| Repo-rot | `C:\\Users\\tobia\\OneDrive\\Documents\\ChatGPT\\MINECRAFT MOD\\Verk-arbeid-` |
| Aktiv modul | `hearthstead-neoforge/` |
| Plattform | NeoForge 1.21.1 |
| Gren | `claude/hearthstead-settlement-mod-vbdb9n` |
| Frosset prototype | `hearthstead/` — ikke utvikle her |
| Spillerprofil | `C:\\Users\\tobia\\curseforge\\minecraft\\Instances\\SIVILASJON (1)` |
| Sikker backup | `C:\\Users\\tobia\\OneDrive\\Documents\\ChatGPT\\MINECRAFT MOD\\_backups\\Verk-arbeid-checkpoint-20260827T2025.zip` |

Arbeidstreet har omtrent **281 endrede/tilføyde filer** og ca. **38 025
innsettinger / 4 671 slettinger**. Det er bevisst dirty.

**Aldri:** `git reset --hard`, `git checkout --`, masse-sletting, opprydding av
ukjente filer, eller arbeid i `hearthstead/`. Ikke rediger mens QA kjører. Ikke
installer `hearthstead-neoforge/build/libs/hearthstead-0.2.0.jar`; den er bare
et build-output, ikke en godkjent release.

Les først: `AGENTS.md`, denne filen, `qa/QUICKSTART.md`, `qa/PROTOCOL.md` og
`qa/RELEASE_CLIENT_GATE.md`.

## 2. Eiers mål — hva demoen faktisk må gi spilleren

Tobias vil ha én komplett, tilfredsstillende og forståelig spilløkt til første
raid, ikke en bred mod med halvferdige systemer:

1. Ny spiller får Hearthstead Handbook og forstår Hearth.
2. Hearth → Development åpner Build Plans i naturlig rekkefølge.
3. Spilleren researcher, ser oppskrift, lærer en plan og bygger en gyldig fysisk
   bygning via plaque/rom.
4. Spilleren gir riktig fysisk Emblem til valgfri settler. Emblem ansetter direkte
   når riktig bygning er aktiv; ingen ekstra uklar Hire-flyt.
5. Worker starter uten gratis tools, melder synlig request, mottar fysisk vare.
6. Lumberer/Farmer jobber til workplace storage; Courier flytter fysiske varer
   fra source → bag → Warehouse via request/ledger.
7. Spilleren får Archer + Guard, rustning/piler og forstår readiness.
8. Første raid er spennende, rettferdig og teknisk komplett.

**Invarianter:** Ingen gyldig plan/rom/plaque = ingen bygning. Ingen item-
teleportering, duplisering eller usynlig tap. Profession skaper aldri magisk
tool/armor/ammo. Requests er persistent settlement-data. Feiltilstander forklarer
hva, hvorfor og neste handling. Engelsk er spilltekst. UI hjelper spilleren,
men verden/figurene er hovedflaten.

## 3. Låste designvalg

### Development, emblem og attributes

- Tech tree ligger i **Hearth**, ikke Mayor; én tidlig stamme, senere grener med
  reelle tradeoffs. Ikke gjør det til en flat liste.
- Hover på node viser krav, belønning og Build Plan-oppskrift; research gir en
  faktisk learned plan/flyt, ikke bare tekst.
- Attributes er tall (`25 / 100`), ikke prikker. Alle ligger på første
  settlerside, med hover for konkrete bonusser.
- Hver jobb viser 2–3 relevante attributes med enkelt språk. F.eks. Strength:
  færre øksehugg + mer bærevekt; Stamina: mindre tempo-tap ved tung last. Ikke
  fortell hvem spiller skal velge — forklar krav/effekt.
- Blessing er fysisk item: Shift + høyreklikk på settler eller plaque gir
  permanent blessing.

### Bygg, scepter og logistikk

- Byggblokker må ha mening. Crafting table i Lumber Camp er bare rett dersom
  Lumberer faktisk kan bruke den, eksempelvis til wooden axe av logs.
- Work Scepter velger to 3D-hjørner og separat høydeklikk. Farmer-zone trenger
  level-/kapasitetscap slik at level 1 ikke blir overdrevent sterk.
- Shift + høyreklikk med tom hånd på settler åpner ekte serverautoritativt
  inventory.
- Courier må ha synlig request-kø og fysisk last, ikke usynlig container.

### UI, animasjon og lyd

- UI: varmt, ryddig, fargerikt, responsivt; ingen ikonflimmer/glow, overlapper
  eller 20-FPS-kollaps. Development må zoome langt ut. Mayor er gjenkjennelig.
  Staff forklarer rollebehov og Emblem-flyt. Receptor/plaque crafting forklares.
- Lumberer er **golden standard** for alle jobber. Sekk settes ned og blir der;
  ingen flyting/følging. Alle bøy går framover, ikke bakover.
- Bag→chest er synlig: sett ned bag, reach, faktisk item synlig, åpne chest med
  fri arm, legg inn, oppdater inventory deterministisk, gjenta. Ingen skjult
  stack-flytting eller client/server-dobling.
- Crafting må vise komponenter på bord, arbeid og synlig resultat.
- Lyd må være original eller klart lisensiert; aldri hent ut/kopier fra andre
  mods/spill/videoer. Handlingsnær timing, variasjon og anti-spam kreves.

MineColonies/Tektopia er bare clean-room referanser for **prinsipper**. Ikke
kopier/dekompiler kode, layout, ordlyd, assets, modeller, textures, animasjoner,
lyd eller data.

## 4. Endringer som allerede finnes — men fortsatt må bevises

### Guard, Archer og første raid

- `RaiderEntity.java`: SKIRMISHER = 18 HP, speed `.38`, KB resist `0.0`;
  BRUTE = 30 HP, speed `.26`, KB resist `.40`.
- Archer gjør `+25 %` mot ordinær SKIRMISHER; Guard/Knight `+25 %` mot ordinær
  BRUTE. Ingen feilrolle-straff. Captain er counter-nøytral.
- `RaidDirector.java`: første raid = én navngitt captain + én BRUTE + tre
  SKIRMISHERS. Mål er KORN/Warehouse når det finnes, med BLOD legacy fallback.
- `FirstRaidReadinessService`: minst 8 arrows. 7 feiler; 8 passerer. 5 i riktig
  Watchtower rack + 3 i samme Archer sin persisterte quiver kan passere; feil
  tower teller ikke.
- Guard patrol, player-defense, stand-guard og networking har fått omfattende
  arbeid, men mangler full runtime/multiplayer-godkjenning.

### Lumberer, Farmer og Courier

- `LumbererWorkGoal`: aksepterer nå korrekt ettblokk-høydeforskjell ved siste
  logg etter stump, men krever fortsatt sti, reach og fri ray. Camp-deposit
  krever faktisk chest contact/LOS, ikke gjennom vegg.
- Lumberer-fixtures er modernisert til riktig Lumber Camp/LUMBER-zone,
  `Employment.hire`, fysisk chest og axe. To eldre tests i
  `HearthsteadGameTests` er også migrert.
- Farmer-fixtures: ekte Farmhouse/FARM-zone, ansettelse, tool, seeds og storage.
- Courier-fixtures: registrert/støttet plaque, korrekt radius/kapasitet,
  fysisk lastbudsjett og ledger-observasjon.
- **Dette beviser ikke produksjon:** video 28. august ca. `00:02.9–00:07.3`
  viser moden wheat i bekreftet Farmhouse-zone, mens Farmer sier
  `NO VALID TARGET — nothing workable in confirmed zone target Farmhouse`.
  Dette er en bekreftet P0. Reproduser med reell Farmhouse/zone og spor
  autoritativ target scan, bounds og høyde.
- Carrier-animasjon fikk positiv tilbakemelding, men dørpassering og fysisk
  delivery-loop er ikke godkjent.

### Progression/recruitment/UI/assets

- Progression-fixtures følger Stores/Roads → Cultivated og Home → House;
  Timber Rights krever live Hearth, Mayor og tre founders.
- Plaque-fixtures bruker learned plans og gyldige rom. Recruitment bruker live
  Tavern/plaque, persisted attraction/spawn/arrival og faktisk pris/patience.
- Seals er ikke-stackbare og kan bli sikre fysiske drops ved full inventory;
  ingen item-tap. Første `beginQualification()`-tick teller `1/1`.
- Store aktive WIP-endringer finnes i `HearthScreen`, `PlaqueScreen`,
  `SettlerScreen`, `StorageScreen`, `ResearchScreen`, `HandbookScreen`, `HsUi`
  og nettverkssnapshots. Ikke kall dette ferdig UI overhaul.
- Siste assetsjekk: **995/995 PASS**.
- Siste animasjonskontrakt: **PASS med 3 warnings**:
  1. 39 katalogførte clips er fortsatt bare planlagt/fasevis.
  2. `HUNTER_LOOSE`: runtime MAINHAND følger høyre arm, clip authorer venstre
     som buearm; ikke godkjenn prop-renderen.
  3. `CLEAVE`/`IDLE_SENTRY` er kontekstuelle og mangler universelt sannferdig
     prop.

## 5. Autoritativ QA-status

### Siste fullførte GameTest

- **552/600 pass, 48 feil**.
- Artefakt: `qa/reports/artifacts/20260828T175857.838204119Z-1747.83CyTW/`.
- Detaljer: `gametest-failures.txt` i samme mappe.
- Alle fikk senere reparasjon/fixture-migrering, men **ingen ny GameTest har
  validert dette**.

### Siste quick

- Artefakt: `qa/reports/artifacts/20260828T182940.885484422Z-415.O1yQtj/`.
- Build produserte `hearthstead-neoforge/build/libs/hearthstead-0.2.0.jar`.
- Assets PASS 995/995. Animasjonskontrakt PASS med warningene over.
- Quick ble avbrutt før sluttlinjen `QUICK: PASS`; resultatet er **ikke grønt**.

### Eldre compile-funn som trolig er rettet, men ikke bevises ennå

`qa/reports/artifacts/20260828T182353.349015308Z-396.ZG14zu/` har tidligere
compile-feil i `RecruitGameTests.java` fordi `LevelData.setGameTime(long)` ikke
finnes på interface. Fixture ble endret til `ServerLevelData` etterpå. Første
rene quick må bevise at dette faktisk kompilerer.

### Absolutt QA-regel

Kjør bare fra repo-roten, én suite om gangen:

```bash
bash tools/hearthstead-qa quick
bash tools/hearthstead-qa gametest
bash tools/hearthstead-qa full
bash tools/hearthstead-qa gate
```

Ingen rå Gradle, `runGameTestServer` eller `runClient`. Ikke slett
`BLOCKED`/`.stale` manuelt. Diagnostiser ut fra nyeste artifact. Når en gammel
fixture ikke følger ekte spillerflyt, migrer fixture — ikke svekk autoritativ
produksjonslogikk.

**Leveringsbevis:** quick grønn → gametest grønn → `full` to ganger på uendret
source fingerprint → `gate` → ekte Windows-klienttest etter
`qa/RELEASE_CLIENT_GATE.md`. Først da er en JAR testklar.

## 6. Alle 48 historiske GameTest-feil — gruppevis

**A. Lumberer / fysisk skog:**

`lumbererfellstreecleanly`; `lumbererlimbsthenhaulsafterfelling`;
`onelumberercompletestwotreeswithoutspinningidle`;
`lumbererreplantswherethetreestoodwithoutahardquota`;
`reachablesideofstumpcollectsowneddropwhennearestsideissealed`;
`sealedcamprouteyieldsthenrecoverswithoutcargoloss`;
`treeclaimisworldscopedandexpireswithoutheartbeat`;
`movingphysicaldroprebasesrouteandtransferssameuuid`.

Tidligere symptom: spinner/idle etter første tre, siste logg ikke banket og
rute-recovery mister/glemmer cargo. Ved ny feil: logg target, claim/reservation,
route state, reachability, chest contact og activity.

**B. Farmer / crop-konservering:**

`farmeractivityprogressesthroughharvestandplant`; `farmerharvestsanddeposits`;
`depositholdsbacktheseedreserve`; `harvestcontactsurvivesinterruptionandcannotreplay`.

Overlapper med bekreftet live Farmer P0. Første hypotese må bevises/avkreftes:
Farmhouse/zone-høyde eller target-query ekskluderer moden wheat.

**C. Courier / warehouse / restock:**

`camptobagtowarehouseconservescountandcomponents`;
`realcouriercollectionadvancescultivatedobjective`;
`restockconservesitemsacrossthefullroute`;
`restockloadreturnstowarehousewhenthecrafterdissolvesmidtrip`;
`restockdeliverswhentheonlystandablecellisoutsidethecraftersbounds`;
`restockoutranksahungryhearth`; `mineyieldiscollectedcompletely`;
`gatheredcodreachesawarehouseandfeedsahungrysettler`.

Dører er P0 for alle settlers, ikke bare Carrier. Få felles navigation/collision
rotårsak før du maskerer det i én Courier-rute.

**D. Guard / authority / patrol:**

`spectatorsessionisreadonlyandcannotcreateanorder`;
`nullbuildingcannotbecomeguardauthority`;
`patrolroutevisitseverypointinthenumberedorder`;
`unreachablepatrolpointreportsfailurewithoutfakearrival`;
`prefersaraiderattackingtheplayeroveraneareridleone`;
`borrowedquiversurvivesreloadwithoutbreakingconservation`;
`watchschemamigrationpreservesoldproofandcurrentdamagefailsclosed`.

Spectator må aldri opprette authority. Patrol må fysisk besøke punkter i rekkefølge;
umulig punkt skal gi `no_path`, beholde ordre og ikke late som arrival.

**E. Progression / plaque / Journey:**

`legacyhearthlogcannotforgestoredproduction`;
`rawadminhireandrealstoragecannotforgereadiness`;
`realauthoritativejourneyunlocksoneexactfirstraid`;
`planuseisdeniedbeforeandallowedaftersettlementknowledge`;
`persistedscheduledcalendarrecoversmissingjourneyreceipt`;
`fittingaplanconsumesitfromtheplayershand`;
`rightclickingablankplaquewithaplanfitsit`;
`emptyplaqueopensnoscreenuntilplaninserted`;
`mayoractionsrequireexactopenhearthmenu`;
`spawnedbandsealsandheldrewardisexactlyonce`.

Flere her var legitime `QUEST_REQUIRED`/learned-plan/room-fixturefeil, men det
må først bekreftes i ny run.

**F. Tavern/recruitment:**

`notavernmeansthegaugeneverfills`; `awaitingguestsurvivestaverninvalidation`;
`aninnkeeperdiscountdoesnotcompresstherecruitclock`; `apayableguestjoinsandthepriceisexact`;
`avalidtavernreopensthegate`; `anunpayableguestwalksawayinsteadofjoining`;
`recruitpricestillacceptsanyplanks`; `nodiscountbuildingsmeansfullpricecharged`;
`employedinnkeeperappliesthenameddiscount`; `onehundredacceptedoffersmintexactlyonehundredseals`.

**G. Energy:** `settlerwakesatdawnwithrecoveredenergy`.
Tobias vil at workers heller arbeider tregere enn at et irriterende "daily work
left"-system styrer dem.

## 7. Viktige runtime-observasjoner

**Positivt:** Lumberer-sekk/pickup-idé og store deler av animasjonen ble godt
mottatt. Carrier-bæring ble senere vurdert som god. Den nye Tech Tree-retningen
er eiergodkjent i prinsipp; forbedre sammenheng med andre skjermer, ikke bytt
den ut tilfeldig.

**Fortsatt reelle feil:** Farmer P0 over. Carrier/dører P0. UI opplevdes rundt
20 FPS, med overlapper, for tett Development zoom og visuell inkonsistens.
Lumber bend/carry må være framoverlent; bag blir igjen når satt ned. Lyddesign
er underkjent. Playtest-evidence ligger i `qa/reports/playtest/`; Farmer-rapport:
`qa/reports/playtest/2026-08-28_145855_video_inbox/REPORT.md`. Ikke slett eller
flytt originale NVIDIA-videoer.

## 8. Viktigste kodeområder

**Runtime:** `SettlerEntity.java`, `SettlerActivity.java`, `SettlerAttributes.java`,
`RaiderEntity.java`, `ai/LumbererWorkGoal.java`, `ai/FarmerWorkGoal.java`,
`ai/CourierWorkGoal.java`, `ai/WorkScanner.java`, `ai/GuardPatrolGoal.java`,
`ai/SettlerDefenseTargetGoal.java`, `settlement/Employment.java`,
`settlement/Settlement.java`, `settlement/SettlementManager.java`,
`settlement/raid/RaidDirector.java`, `settlement/state/GuardOrder.java`.

**UI/nettverk:** `client/screen/HearthScreen.java`, `PlaqueScreen.java`,
`SettlerScreen.java`, `StorageScreen.java`, `ResearchScreen.java`,
`HandbookScreen.java`, `client/ui/HsUi.java`, `network/HearthNetwork.java`,
`SettlerNetwork.java`, `PlaqueNetwork.java`.

**Visual/audio:** `client/model/SettlerAnimations.java`, `SettlerModel.java`,
`client/render/SettlerRenderer.java`, `registry/ModSounds.java`,
`resources/assets/hearthstead/sounds.json`, `docs/ANIMATION_CATALOGUE.md`,
`tools/anim_check.py`, `tools/anim_preview.py`, `tools/blockbench/`.

**Tests:** `LumbererGameTests`, `FarmerBootstrapGameTests`, `LogisticsGameTests`,
`CourierFoodRouteGameTests`, `CourierWorkshopRouteGameTests`,
`GuardControlGameTests`, `GuardDefenseGameTests`, `FirstRaidRuntimeGameTests`,
`FirstRaidReadinessGameTests`, `RecruitGameTests`, `RecruitmentPolicyGameTests`,
`network/GuardOrderNetworkGameTests`.

## 9. Presis oppstartsrekkefølge for Claude

1. Les dokumentene, sjekk ingen gammel QA-prosess skriver, noter working-tree.
2. Kjør `bash tools/hearthstead-qa quick` uendret og vent på sluttlinje.
3. Hvis ikke PASS: reparer kun første faktiske feil med fersk artifact som bevis.
4. Ved grønn quick: kjør én `bash tools/hearthstead-qa gametest`.
5. Sammenlign med alle 48 navn ovenfor. Ikke erklær noe løst fordi suite stoppet
   før den nådde den gamle feilen.
6. Prioriter: Farmer target/3D-zone → dører + Courier → Lumberer-to-tree loop →
   Tavern/recruitment → Archer/Guard/raid → UI-yting/målt UX → animasjon/lyd.
7. For hver endring: fakta → hypotese → liten løsning → test + in-game QA.
8. Etter grønn GameTest: `full` to ganger på samme fingerprint, `gate`, deretter
   Windows-klientspill: onboarding, plaque, emblem, inventory/request/Courier,
   Lumberer, Farmer, UI/FPS, Guards og første raid. Test save/reload,
   full inventory/chest, avbrutt rute og relevant multiplayer.

## 10. Klar melding til Claude

> Ta over Hearthstead fra `docs/project/HANDOVER_CHATGPT.md`. Les alt før du
> jobber. Ikke redesign eller installer JAR nå: etabler ærlig baseline med kun
> `bash tools/hearthstead-qa quick`, deretter `gametest`. Siste autoritative
> resultat er 552/600; reparasjonene etterpå er uverifiserte. Prioriter ekte
> Farmer target-detection, stabile dører/Courier, Lumberer-kontinuitet og legitim
> rekruttering til Archer/Guard før første raid. Behold fysisk itemflyt og
> fail-closed-regler. Ikke kall noe ferdig før full QA og faktisk Windows-
> klientplaythrough til første raid er bevist.

## 11. Ærlig status til Tobias

Mye er bygget. **Ingenting nytt er godkjent for installasjon.** Denne filen
inneholder nå mål, låste valg, faktisk endret arbeid, runtimefunn, alle 48
historiske testfeil, QA-bevis, filseams og eksakt fortsettelsesrekkefølge, slik
at neste agent kan arbeide videre uten å gjette eller late som modden er ferdig.
