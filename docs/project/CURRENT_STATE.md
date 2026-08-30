# CURRENT STATE — HEARTHSTEAD

**Oppdatert:** 2026-08-30 etter recovery-/handover-gjennomgang
**Aktiv gate:** G0 — dokumentasjonslukking før endelig tag/archive
**Neste gate:** G1 — fersk baseline etter at G0-dokumentasjonen er lukket
**Aktiv gren:** `integration/hearthstead-demo-recovery-20260830`
**Aktivt modprosjekt:** `hearthstead-neoforge/`

## Canonical recovery truth

| Felt | Verdi |
| --- | --- |
| Integrasjonsgren | `integration/hearthstead-demo-recovery-20260830` |
| Recovery-ref | `recovery/hearthstead-pre-g0-head-20260830` |
| Recovery-tag | `recovery-pre-g0-head-20260830` |
| Recovery checkpoint | `98926b99...` |
| Build-identity foundation | `cd277d5eb3b11f1c83566fccf98d5d96a55457cc` |
| Endelig G0-tag/archive | **PENDING — opprettes etter docs closure** |

`98926b99...` er recovery-checkpointet som skal være sporbar sikkerhetsline
for G0. Den endelige G0-taggen og det endelige archive-navnet/-hashen er ikke
opprettet ennå og skal ikke gjettes eller fylles inn fra eldre artefakter.

Dette dokumentet erstatter recovery-teksten fra 2026-08-23. Den eldre teksten
er fortsatt tilgjengelig i git-historikken, men skal ikke brukes som aktiv
sannhet.

## Kort status

Integrasjonen er stor, verdifull og fortsatt ikke releasegodkjent. Den inneholder
betydelig arbeid med Blessings, Journey-v1, raids, UI, motion, assets og
QA-harness. Nåværende aktive prioritet er synlig UI-overhaul, Farmer/door/storage
som P0 og en sammenhengende first-raid-demo. Ingen nåværende releasegate kan
kalles grønn på historiske resultater alene.

Den gamle `claude/hearthstead-ui-performance-ldc42l`-UI-en skal ikke
wholesale-merges inn i denne integrasjonen. Den kan bare brukes som kilde til
selektiv ytelses-/QA-læring etter vurdering mot nåværende tabs, data contracts
og spillerflyt.

## Verifiserte recoveryartefakter

Recovery-checkpoint `98926b99efdc1cfa38a125cb91171cddacc7931e`
er også bevart i to verifiserte artefakter:

- ZIP:
  `C:\Users\tobia\OneDrive\Documents\ChatGPT\MINECRAFT MOD\_backups\Verk-arbeid-G0-canonical-98926b99efdc-20260830T200137.zip`
  - SHA-256: `50BCA15FB5F6634D7E955E2487181A74FAA260DBE0EDD64587B6C80EE44004CC`
  - 1 391 filer; byte-identisk kontroll mot `git archive` bestod.
- Git bundle:
  `C:\Users\tobia\OneDrive\Documents\ChatGPT\MINECRAFT MOD\_backups\Verk-arbeid-G0-canonical-98926b99efdc-20260830T200137.bundle`
  - SHA-256: `B72037B9695F469F12086E30C62AD7E6C67B467FBE581151E2E7BC67217F957A`
  - `git bundle verify` bestod og bevarer komplett historikk.

Disse er recoveryartefakter for checkpointet, ikke det endelige G0-arkivet
for den senere docs-/integrasjonslukningen.

En enda eldre predecessor recovery-artifact finnes også:

En predecessor recovery-artifact ble laget før nåværende G0-integrasjon:

`C:\Users\tobia\OneDrive\Documents\ChatGPT\MINECRAFT MOD\_backups\Verk-arbeid-checkpoint-20260827T2025.zip`

SHA-256:

`8DD7851A5D9AF4EF15C4D9E6799D6432F12B2793628E3D3A54A2EB2364DB29FC`

Størrelse: 10 075 940 bytes. Artefakten utelater `.git`, byggoutput,
Gradle/run og QA-rapporter, men beholder kode, scripts, docs og assets. Den er
ikke det endelige G0-arkivet og skal ikke forveksles med recovery-refen,
recovery-taggen eller den kommende endelige G0-archive.

## Beviselig synlig i checkpointet

- Hearth, settlers, plaques og arbeidere finnes i faktisk klient.
- Lumberer finner et tre og CHOP-klippet er runtime-synlig.
- Tre fysiske Blessing-segl og permanente Blessing-tilstander er implementert.
- Shift + høyreklikk kan gi Blessing til settler eller plaque.
- Founding Journey-v1 viser fire serverautoritative Lumberer-steg.
- Bygningskrav, Tavern, Mayor og Journey kan åpnes i UI.
- Nytt QA-/save-/nettverksarbeid og mange GameTests finnes i arbeidstreet.

Dette er synlig/grunnlag, ikke premium- eller releasegodkjenning.

## Åpne P1-funn fra eieren

1. Det er fortsatt uklart hvordan man får flere settlers.
2. Journey stopper etter første logg og lærer ikke den virkelige settlement-
   loopen til første raid.
3. Fresh workers får gratis utstyr; ønsket system er ekte requests og fysisk
   spiller-/Courier-levering.
4. Shift + tom hånd åpner ikke en ekte serverautoritativ settler-inventory.
5. Lumberer/Farmer leverer ikke gjennom ønsket workplace-storage/request-loop.
6. Courier bærer som om en usynlig kasse holdes foran kroppen.
7. Lumberer pickup/stow og laden walk er visuelt underkjent.
8. Guard mangler fungerende Stand, Patrol, Tower og Recall-kontroll.
9. Tavern/Hearth/Journey UI er visuelt underkjent; kritisk tekst kuttes eller
   konkurrerer med andre lag.
10. UI gir et merkbart FPS-fall ved åpning; rotårsak og recovery er ikke målt.
11. Innkeeper bruker en generisk, lite troverdig arbeidsbevegelse.
12. Name/activity-overlay er for stor og skjuler arbeid.
13. Dagens lydpakke er underkjent.

## Bindende produktavgjørelser

- Blessing er et fysisk item i hånden; Shift + høyreklikk gir det permanent til
  settler eller plaque.
- Bygninger åpnes i ett Development Tech Tree.
- Tech starter med én felles stamme til første raid, splitter så i tre
  doktriner og senere fem spesialiseringer med reelle tradeoffs.
- Permanente unlocks slettes aldri ved doctrinebytte; aktive bonuser/ulemper
  kan byttes hos Mayor mot fysisk pris og cooldown.
- Hver bygning har én kort NB/EN-beskrivelse som gjenbrukes på Build Plan og i
  Development Tech Tree.
- Job Emblems handles fysisk hos Mayor og er jobbauthorization.
- Jobb og workplace er separate relasjoner. Jobb skaper aldri tool, armor eller
  ammo.
- Worker requests er persistent settlement-data. Items flyttes fysisk:
  source/workplace → Courier bag → target/Warehouse.
- Lumberer/Farmer legger output i egen workplace storage.
- Work Scepter velger to horisontale hjørner og deretter ett eksplisitt
  høydeklikk; Command Staff gir få, lesbare Guard-ordrer.
- Første komplette scope er House/Lodging, Lumberer, Farmer,
  Warehouse/Courier, Tavern/recruitment og basic Guard gjennom første raid.
- Første raid får readiness-ankret nedtelling slik at tutorialen kan rekke å
  lære rekruttering og forsvar uten å gjøre raidet gratis.

## Kvalitetsstatus

- **Gameplaygrunnlag:** PÅGÅR.
- **Save/nettverk:** betydelig arbeid finnes; ikke sertifisert på nåværende
  fingerprint.
- **UI:** UNDERKJENT.
- **Klientytelse i UI:** P0; rapportert kollaps er bekreftet som releaseblokker,
  mens ny native før/etter-måling på current fingerprint fortsatt mangler.
- **Animasjon:** enkelte klipp synlige; pickup/carry/Courier/Innkeeper og flere
  rolleklipp UNDERKJENT.
- **Lyd:** UNDERKJENT.
- **Teksturer:** ikke ferdig samlet premiumreview.
- **Full Journey til første raid:** IKKE IMPLEMENTERT.
- **Release:** IKKE KLAR.
- **Quick:** kun en billig PASS-sjekk; quick alene er ikke grønt releasebevis.
- **Siste historiske GameTest-evidence:** 612 totalt / 48 påkrevde feil; dette
  er ikke current green proof.

## Aktiv dokumentasjon

1. `MASTERPLAN_PREMIUM_RELEASE_2026-08-27.md` — eneste operative hovedplan.
2. `OWNER_DIRECTIVE_2026-08-27_TECH_TREE_EMBLEMS.md` — Development/Emblems.
3. `OWNER_DIRECTIVE_2026-08-27_SETTLER_CONTROL_LOGISTICS.md` — inventory,
   requests, workplace, Courier, work/guard control.
4. `JOURNEY_TO_FIRST_RAID_SPEC_2026-08-27.md` — full onboarding/raidkontrakt.
5. `PLAYTEST_REVIEW_2026-08-27_1945.md`, `..._1956.md` og `..._2011.md` —
   tidskodet eierbevis.
6. `NEXT_ACTION.md` — eksakt gjenopptakspunkt.

## Verktøy på maskinen

- Blockbench 5.1.6 — modell/motion-authoring.
- Audacity 3.7.8 — lydredigering og A/B-lytting.
- FFmpeg 8.1 — video/audio extraction og måling.
- Innebygd ChatGPT Voice i Codex er den valgte taleløsningen.
- OpenWhispr er avinstallert etter eierens beslutning.
- Pixelorama er ønsket for pixel-art, men installasjonen må verifiseres før det
  kan behandles som tilgjengelig.

## Ikke lov å gjøre

- Ikke reset eller slett den skitne arbeidskopien.
- Ikke kall historiske testresultater current.
- Ikke slett `BLOCKED` eller `.stale` manuelt for å skape grønn status.
- Ikke fjern gratis gear før fysisk request/inventory-erstatning finnes i samme
  vertikale slice.
- Ikke kopier MineColonies-kode, UI, teksturer, tekst eller lyd. Gjenskap den
  forståelige fysiske logistikkflyten clean-room.
- Ikke kall UI, motion eller lyd ferdig uten runtimebevis og eiergodkjenning.

## Neste eksakte handling

Lukk canonical recovery-/handover-dokumentasjonen først. Opprett deretter
endelig G0-tag/archive med faktiske navn og hashverdier. Først etter docs closure
skal `NEXT_ACTION.md` brukes som arbeidsinngang til G1-baseline.
