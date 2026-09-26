# Tre kontrollrunder av Hearthstead Demo Master Plan

**Utført:** 29. august 2026  
**Kontrollert dokument:** `MASTER_PLAN.md`  
**Resultat:** Godkjent som planpakke; implementasjon er ikke startet

---

## Runde 1 — struktur, scope og avhengigheter

### Kontrollert

- at planen har én tydelig demoavgrensning;
- at alle gates finnes én gang og har et tydelig resultat;
- at Now/Next/Later hindrer scope creep;
- at sentrale systemavhengigheter ikke er sirkulære;
- at UI, animasjon og lyd bygges mot stabile gameplaykontrakter;
- at demoens sluttpunkt er entydig.

### Funn

Den første versjonen plasserte full UI, animasjon og lyd før den endelige
Guard/Archer/raid-gaten. Samtidig inneholdt raid-gaten fortsatt grunnleggende
authority-, threat- og snapshotarbeid. Dette kunne ha tvunget UI og
presentasjon til å bygges mot ustabile combatdata.

G4 og G5 krevde fysisk film før G8 eide den endelige animasjonskvaliteten. Uten
en presisering kunne samme gate bety både «state-rekkefølgen er riktig» og
«motion er endelig godkjent».

### Rettet

- G10 er delt i **G10A** og **G10B**.
- G10A fryser combat-/raidkontraktene før UI, animasjon og lyd.
- G10B integrerer og balanserer etter G7–G9.
- G4/G5 godkjenner fysisk og autoritativ sekvens; G8 godkjenner endelig motion.
- Endres props/contact timing i G8, blir G4/G5-regresjon og film obligatorisk
  på nytt.
- Etter G10B kjøres relevante G7-UI- og frame-time-rader på nytt.

### Runde 1-verdict

**PASS etter retting.** Avhengighetsrekkefølgen er eksplisitt og har ingen
uadressert presentasjon-mot-ustabil-state-konflikt.

---

## Runde 2 — teknisk, QA og release-konsistens

### Kontrollert mot

- `qa/QUICKSTART.md`;
- `qa/PROTOCOL.md`;
- `qa/RELEASE_CLIENT_GATE.md`;
- faktisk 600-testkontrakt;
- green-streak/fingerprint-reglene;
- Work Zone-authority;
- server- og UI-ytelsesportene;
- dagens miljøbegrensninger.

### Funn

- Planen hadde riktig 600-test- og 2× full-regel, men manglet de eksakte
  dedicated-server MSPT-budsjettene.
- Den nyere beslutningen om separat høydeklikk i Work Scepter var riktig i
  planen, men konflikten mot eldre QA-tekst var ikke eksplisitt definert som en
  Quality Ledger-korreksjon.
- Den eksisterende native input-/release-client-gaten er under fail-closed
  reparasjon og `live` er deaktivert. Uten dette i planen kunne et fremtidig
  resultat feilaktig kalles Approved på bare video/automatisering.

### Rettet

- Lagt inn average MSPT-gater: 25 ≤ 45 ms, 50 ≤ 45 ms, 100 ≤ 50 ms; én settler
  er baseline og median erstatter ikke average.
- Lagt inn formell spesifikasjonskorreksjon for Work Scepter:
  to hjørner + separat høydevalg.
- Lagt inn at native input-/release-client-infrastrukturen må repareres og
  uavhengig kontrolleres før status kan bli Approved.
- Hvis native gate fortsatt er blokkert, kan demoen bare være Candidate.

### Runde 2-verdict

**PASS etter retting.** Planen samsvarer med QA-protokollens fail-closed
godkjenningsmodell og konkrete ytelsesbudsjetter.

---

## Runde 3 — spillerreise, begreper og misforståelsesfare

### Kontrollert

- hele reisen fra Handbook til Aftermath;
- alle kritiske bygninger og seks demoroller;
- unlock → recipe → Build Plan → plaque → building → Emblem → request;
- Lumberer/Farmer/Courier-loopene;
- Guard + Archer readiness;
- hva spilleren ser når noe er blokkert;
- terminologi i planen mot player-facing tekst som finnes i prosjektet.

### Funn

- Releaseprotokollen krever fem forskjellige levende settlementmedlemmer og
  fem gyldige fysiske beds ved raid readiness. Planen nevnte «nok settlers»,
  men var ikke tilstrekkelig eksakt.
- Prosjektets språkfiler inneholder fortsatt eldre `Hire`-faner og tekster,
  selv om den låste designen sier fysisk Emblem → direkte ansettelse.
- Tobias har tidligere brukt ordet «receptor», mens det aktive systemet er
  Work Scepter/Building Plaque. Dette kan produsere feil item eller dobbel
  terminologi hvis det ikke auditeres.

### Rettet

- Readiness og Journey krever eksplisitt minst fem levende settlers og fem
  gyldige fysiske beds.
- G6 har fått en full audit av Hire-tabs, knapper, språk, Journey, nettverk og
  tester.
- Work Scepter og Building Plaque er canonical player-facing navn.
- «Receptor» må kartlegges dokumentert til riktig eksisterende konsept eller
  fjernes som foreldet; det opprettes ikke automatisk et nytt item.
- Handbook, recipes, inventory og tooltips må bruke samme navn.

### Runde 3-verdict

**PASS etter retting.** Den aktive demo-reisen er konkret nok til at en
implementeringsøkt ikke trenger å gjette på population, beds, jobbtildeling
eller itemnavn.

---

## Sluttkontroll

- Alle gateoverskrifter G0–G12 finnes uten duplikater.
- G10A/G10B-rekkefølgen er forklart både i roadmapen og gateindeksen.
- Demoens slutt er Aftermath.
- Senere yrker og post-raid-systemer er eksplisitt utenfor releaseomfang.
- `git diff --check` er ren for planpakken.
- Ingen gameplaykode, assets, tests eller JAR ble endret.
- Ingen QA-prosess ble startet eller avbrutt.

**Endelig planverdict: PASS — klar til å vente på eksplisitt oppstart av G0.**

