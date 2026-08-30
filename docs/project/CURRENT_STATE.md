# CURRENT STATE — HEARTHSTEAD

**Oppdatert:** 2026-08-27 etter playtestklippet 20:11
**Aktiv gate:** GATE 0 — Tobias' live checkpoint-test
**Neste gate:** GATE 1 — frys arbeidstreet og gjenopprett en ærlig baseline
**Aktiv gren:** `claude/hearthstead-settlement-mod-vbdb9n`
**Aktivt modprosjekt:** `hearthstead-neoforge/`

Dette dokumentet erstatter recovery-teksten fra 2026-08-23. Den eldre teksten
er fortsatt tilgjengelig i git-historikken, men skal ikke brukes som aktiv
sannhet.

## Kort status

Checkpointet er stort, skittent og verdifullt. Det inneholder betydelig arbeid
med Blessings, Journey-v1, raids, UI, motion, assets og QA-harness, men dagens
firestegs Journey, UI, lyd og flere arbeidsanimasjoner er underkjent i live
playtest. Ingen nåværende releasegate kan kalles grønn før baselinen kjøres på
den eksakte kildekoden som ligger her nå.

## Sikker checkpoint

En gjenopprettbar kopi ble laget før videre overhaul:

`C:\Users\tobia\OneDrive\Documents\ChatGPT\MINECRAFT MOD\_backups\Verk-arbeid-checkpoint-20260827T2025.zip`

SHA-256:

`8DD7851A5D9AF4EF15C4D9E6799D6432F12B2793628E3D3A54A2EB2364DB29FC`

Størrelse: 10 075 940 bytes. Backupen utelater `.git`, byggoutput, Gradle/run
og QA-rapporter, men beholder kode, scripts, docs og assets.

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
- Work Scepter velger to 3D-hjørner; Command Staff gir få, lesbare Guard-ordrer.
- Første komplette scope er House/Lodging, Lumberer, Farmer,
  Warehouse/Courier, Tavern/recruitment og basic Guard gjennom første raid.
- Første raid får readiness-ankret nedtelling slik at tutorialen kan rekke å
  lære rekruttering og forsvar uten å gjøre raidet gratis.

## Kvalitetsstatus

- **Gameplaygrunnlag:** PÅGÅR.
- **Save/nettverk:** betydelig arbeid finnes; ikke sertifisert på nåværende
  fingerprint.
- **UI:** UNDERKJENT.
- **Klientytelse i UI:** P1, IKKE MÅLT.
- **Animasjon:** enkelte klipp synlige; pickup/carry/Courier/Innkeeper og flere
  rolleklipp UNDERKJENT.
- **Lyd:** UNDERKJENT.
- **Teksturer:** ikke ferdig samlet premiumreview.
- **Full Journey til første raid:** IKKE IMPLEMENTERT.
- **Release:** IKKE KLAR.

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

Les og utfør `NEXT_ACTION.md`.
