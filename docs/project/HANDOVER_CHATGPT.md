# Hearthstead — full, ærlig overlevering til Claude

**Oppdatert:** 30. august 2026 etter canonical recovery-/handover-gjennomgang.
Les hele filen før du endrer noe.

> **Sann status:** Den canonical integrasjonen ligger på
> `integration/hearthstead-demo-recovery-20260830` med recovery checkpoint
> `98926b99...`. Et stort nytt systemlag for UI, logistikk, rekruttering,
> animasjon, guard og første raid finnes, men modden er **ikke leveringsklar**.
> Quick er kun en billig PASS-sjekk. Siste historiske GameTest-evidence er
> **612 totalt / 48 påkrevde feil**; dette er **ikke current green proof**.
> Ikke installer eller lever ny JAR ennå.

## Canonical recovery truth

| Felt | Verdi |
| --- | --- |
| Integrasjonsgren | `integration/hearthstead-demo-recovery-20260830` |
| Recovery-ref | `recovery/hearthstead-pre-g0-head-20260830` |
| Recovery-tag | `recovery-pre-g0-head-20260830` |
| Recovery checkpoint | `98926b99...` |
| Build-identity foundation | `cd277d5eb3b11f1c83566fccf98d5d96a55457cc` |
| Endelig G0-tag/archive | **PENDING — opprettes etter docs closure** |

Recovery checkpointet og ref/tag-navnene over er den aktive G0-sannheten.
Endelig G0-tag, archive-navn og archive-hash skal opprettes etter at denne
dokumentasjonen er lukket; eksakte verdier skal ikke gjettes eller lånes fra
eldre artefakter.

## 1. Prosjektkart og sikkerhet

| Felt | Verdi |
| --- | --- |
| Repo-rot | `C:\\Users\\tobia\\OneDrive\\Documents\\ChatGPT\\MINECRAFT MOD\\Verk-arbeid-` |
| Aktiv modul | `hearthstead-neoforge/` |
| Plattform | NeoForge 1.21.1 |
| Gren | `integration/hearthstead-demo-recovery-20260830` |
| Frosset prototype | `hearthstead/` — ikke utvikle her |
| Spillerprofil | `C:\\Users\\tobia\\curseforge\\minecraft\\Instances\\SIVILASJON (1)` |
| Checkpoint ZIP | `C:\\Users\\tobia\\OneDrive\\Documents\\ChatGPT\\MINECRAFT MOD\\_backups\\Verk-arbeid-G0-canonical-98926b99efdc-20260830T200137.zip` |
| Checkpoint bundle | `C:\\Users\\tobia\\OneDrive\\Documents\\ChatGPT\\MINECRAFT MOD\\_backups\\Verk-arbeid-G0-canonical-98926b99efdc-20260830T200137.bundle` |

Arbeidstreet og recoveryhistorikken må behandles som en bevisst, ikke-
releasegodkjent arbeidskopi. Den endelige G0-taggen/archive er fortsatt
uopprettet.

Forgjenger-ZIP-en har SHA-256
`8DD7851A5D9AF4EF15C4D9E6799D6432F12B2793628E3D3A54A2EB2364DB29FC` og er
kun en predecessor recovery-artifact. Den utelater `.git`, byggoutput,
Gradle/run og QA-rapporter. Den er ikke recovery-taggen og ikke det kommende
endelige G0-arkivet.

Checkpoint-ZIP-en har SHA-256
`50BCA15FB5F6634D7E955E2487181A74FAA260DBE0EDD64587B6C80EE44004CC`,
inneholder 1 391 filer og bestod byte-identisk kontroll mot `git archive`.
Checkpoint-bundle-en har SHA-256
`B72037B9695F469F12086E30C62AD7E6C67B467FBE581151E2E7BC67217F957A`
og bestod `git bundle verify`. Begge peker på recovery-checkpointet
`98926b99efdc1cfa38a125cb91171cddacc7931e`, ikke på den kommende endelige
G0-lukkingen.

**Aldri:** `git reset --hard`, `git checkout --`, masse-sletting, opprydding av
ukjente filer, eller arbeid i `hearthstead/`. Ikke rediger mens QA kjører. Ikke
installer `hearthstead-neoforge/build/libs/hearthstead-0.2.0.jar`; den er bare
et build-output, ikke en godkjent release.

Les først: `AGENTS.md`, denne filen, `qa/QUICKSTART.md`, `qa/PROTOCOL.md` og
`qa/RELEASE_CLIENT_GATE.md`.

Den gamle `claude/hearthstead-ui-performance-ldc42l`-UI-en skal ikke merges
wholesale inn i current integration. Ta bare inn selektive, manuelt vurderte
ytelses-/QA-lærdommer som ikke bryter nåværende tabs, data contracts eller
spillerflyt.

## 2. Eiers mål — hva demoen faktisk må gi spilleren

### Aktiv prioritet i current integration

1. Synlig UI-overhaul med lesbar, responsiv og målbar spilleropplevelse.
2. Farmer, dører og storage som P0-flyt, med ekte autoritativ state og fysisk
   logistikk.
3. Én sammenhengende demo frem til og gjennom første raid.

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
- Eldre asset-/animasjonsresultater er historiske og skal ikke brukes som
  current green proof. Visuell godkjenning krever ny evidence fra
  `integration/hearthstead-demo-recovery-20260830`.

## 5. Autoritativ QA-status

### Current evidence interpretation

- Quick er kun en billig PASS-sjekk for tidlig feedback. En quick-PASS alene er
  ikke grønt releasebevis og beviser ikke hele GameTest- eller klientflyten.
- Siste historiske GameTest-evidence er **612 totalt / 48 påkrevde feil**.
  Dette er ikke current green proof, uansett hvilke senere patches eller
  fixture-endringer som finnes i arbeidstreet.
- Ingen aktuell GameTest-, build- eller JAR-status skal kalles grønn før den
  er kjørt på denne integrasjonsgrenen og knyttet til riktig source/build
  identity.

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

**Leveringsbevis etter G0:** quick (billig feedback) → fersk gametest med alle
historiske feil klassifisert → `full` to ganger på uendret source fingerprint →
`gate` → ekte Windows-klienttest etter `qa/RELEASE_CLIENT_GATE.md`. Først da er
en JAR testklar.

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

1. Les dokumentene og lukk canonical G0-statusen mot integrasjonsgren,
   recovery-ref/tag, recovery checkpoint og build-identity foundation.
2. Ikke opprett eller påstå endelig G0-tag/archive før docs closure er ferdig;
   registrer de faktiske navnene/hashene når de finnes.
3. Kontroller at ingen gammel QA-prosess skriver, og noter working-tree.
4. Kjør deretter `bash tools/hearthstead-qa quick` uendret og vent på sluttlinje.
5. Hvis ikke PASS: reparer kun første faktiske feil med fersk artifact som bevis.
6. Ved grønn quick: kjør én `bash tools/hearthstead-qa gametest`.
7. Sammenlign med alle 48 historiske feilnavn ovenfor. Ikke erklær noe løst
   fordi suite stoppet før den nådde en gammel feil.
8. Prioriter synlig UI-overhaul → Farmer/door/storage P0 → Lumberer-to-tree
   loop → Tavern/recruitment → Archer/Guard og first-raid-demo.
9. For hver endring: fakta → hypotese → liten løsning → test + in-game QA.
10. Etter grønn GameTest: `full` to ganger på samme fingerprint, `gate`, deretter
   Windows-klientspill: onboarding, plaque, emblem, inventory/request/Courier,
   Lumberer, Farmer, UI/FPS, Guards og første raid. Test save/reload,
   full inventory/chest, avbrutt rute og relevant multiplayer.

## 10. Klar melding til Claude

> Ta over Hearthstead fra `docs/project/HANDOVER_CHATGPT.md`. Les alt før du
> jobber. Current integration er `integration/hearthstead-demo-recovery-20260830`,
> med recovery checkpoint `98926b99...`, recovery-ref
> `recovery/hearthstead-pre-g0-head-20260830`, recovery-tag
> `recovery-pre-g0-head-20260830` og build-identity foundation
> `cd277d5eb3b11f1c83566fccf98d5d96a55457cc`. Lukk docs før endelig G0-
> tag/archive; eksakte sluttverdier er foreløpig pending. Quick er kun en
> billig PASS-sjekk. Siste historiske GameTest-evidence er 612 totalt / 48
> påkrevde feil, ikke current green proof. Prioriter synlig UI-overhaul,
> Farmer/door/storage P0 og en sammenhengende first-raid-demo. Den gamle
> `claude/hearthstead-ui-performance-ldc42l`-UI-en skal ikke wholesale-merges;
> behold fysisk itemflyt og fail-closed-regler. Ikke kall noe ferdig før full
> QA og faktisk Windows-klientplaythrough til første raid er bevist.

## 11. Ærlig status til Tobias

Mye er bygget. **Ingenting nytt er godkjent for installasjon.** Denne filen
inneholder canonical recovery truth, aktiv prioritet, låste valg, runtimefunn,
alle 48 historiske testfeil, QA-regler og eksakt fortsettelsesrekkefølge, slik
at neste agent kan arbeide videre uten å gjette eller late som modden er ferdig.
