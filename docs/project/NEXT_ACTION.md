# NEXT ACTION — HEARTHSTEAD

**Oppdatert:** 2026-08-27 etter playtestklippet 20:11
**Hovedregel:** Bevis først, deretter minste komplette vertikale endring.

Dette er gjenopptakspunktet etter restart eller compaction.

## Gjør dette nå

1. Fullfør de read-only motion-, UI-FPS- og Journey-auditene. Bevar konkrete
   filer/linjer og oppdater hovedplanen; ikke gjør samtidige edits i seam-filer.
2. Bekreft at `test_perf_probe_safety.py`-migreringen er hel og kjør de korte
   harness-kontrakttestene den tilhører.
3. Kjør den billige baselinen på eksakt arbeidstre:

   - `tools/hearthstead-qa quick`
   - `tools/hearthstead-qa doctor`
   - `tools/hearthstead-qa fast`

4. Ikke slett `.stale`/`BLOCKED` manuelt. Registrer første reelle røde årsak og
   skill gammel WIP fra ny feil.
5. Når baselinen er ærlig, land første additive kode-slice:

   - `BuildingDescription`/Building-katalog med én kort NB/EN-kilde;
   - `JobContract`-katalog for alle aktive professions;
   - statiske kontrakttester som feiler ved manglende type, beskrivelse,
     equipmentregel eller outputregel.

6. Bygg deretter den første spillbare Lumberer-kjeden i denne rekkefølgen:

   - Development-unlock;
   - fysisk Lumberer Emblem fra Mayor;
   - ekte Settler Inventory;
   - persistent axe-request;
   - direkte fysisk levering;
   - Work Scepter og bounded 3D-zone;
   - ekte axe/durability i jobben;
   - output til Lumber Camp-storage.

7. Først når hele erstatningskjeden er grønn fjernes `Profession.tool()` som
   gameplaykilde. Legacy tools bevares fysisk og markeres; de slettes aldri ved
   migrering.
8. Verifiser slicen med bygg, målrettede GameTests, save/restart,
   item-konservering, tospiller-replay, UI capture og frame-time-måling.
9. Koble deretter på Warehouse/Courier, Farmer/mat, Tavern/recruitment, Guard og
   readiness-ankret første raid — én bevist vertikal del om gangen.

## Harde stoppregler

- Stopp kodefan-out dersom baselinen viser P0 eller ukjent P1.
- Ikke la en grønn compile erstatte gameplay-, save-, performance- eller
  visuell test.
- Ikke rediger `Settlement`, felles network classes, `HsUi`, språkfiler eller
  `SettlerAnimations` fra to arbeidere samtidig.
- Ikke installer eller regenerer assets over eksisterende filer uten backup og
  deterministisk validator.

## Ferdig med dette punktet når

- baseline-resultatene er datert og knyttet til én source fingerprint;
- BuildingDescription og JobContract har full roster-dekning og tester;
- den første Lumbereren får jobb uten gratis gear, ber om en øks, mottar samme
  fysiske stack, arbeider bare i valgt zone og legger en ekte logg i campen;
- save/restart og tospillerforsøk bevarer item, request, zone, job og workplace;
- UI-et for slicen holder det relative frame-time-budsjettet og viser ingen
  avkuttet NB/EN-tekst;
- før/etter-video viser motion og lyd fra front, side og bak.
