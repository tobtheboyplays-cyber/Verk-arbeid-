# Plan etter demoen — skrevet 17:55 mens eieren spiller

Eierens ordre: vent med alt, planlegg videre, store tilbakemeldinger kommer.
Ingen kjøringer og ingen endringer før tilbakemeldingene er inne.

## Runde 0 — når tilbakemeldingene kommer (i kveld)

Alt eieren sier sorteres i fire kurver før noe som helst fikses:

1. **Bugs** — ting som ikke virker. Hver enkelt REPRODUSERES først
   (målrettet GameTest eller `tools/hearthstead-qa reproduce`) før den
   fikses. Dagens lærdom står: diagnoser fra logger var feil tre ganger,
   diagnoser fra bevis var riktige hver gang.
2. **Balanse** — tall som føles feil. Batches og verifiseres samlet mot
   BALANCE_AUDIT/BALANCE_PITCH, én sertifisering til slutt, ikke én per tall.
3. **Designbeslutninger** — ting bare eieren kan avgjøre (listen under).
   Formuleres som konkrete valg med kostnad, ikke åpne spørsmål.
4. **Polish** — ting som virker men ser/kjennes feil ut. Køes til
   polermester-runden.

## Runde 1 — kjent gjeld som fikses uansett (uavhengig av feedback)

Prioritert:

1. **Sukkerrør-klipp** (KF-036): egne CANE_CUT/CANE_PLANT-keyframes.
   Invariant: hver oppgave sitt eget klipp — hvete-lånet er et brudd.
2. **CELEBRATING settes aldri**: feiringen summeres med yrkes-idle (arm
   gjennom brystet). Beslutning: sett aktiviteten fra celebrate() eller
   resetPose før klippet — samme mønster som SHIELD_BLOCK allerede bruker.
3. **Research/Handbook på guiScale 3**: porter SettlerScreen-scrollmønsteret,
   med eksplisitt regel for hvem som eier musehjulet (indre liste først,
   panel når listen er i endene).
4. **Den fjerde playtest-intermittensen**: instrumenter `open`-direktivet
   med et skjermbilde rett etter safe_regrab (mistenkt: regrab-klikket
   treffer luft med planen i hånden og forskyver timingen). Deretter
   `full` x2 → green_streak ≥ 2 → porten PASS. BLOCKED-filen har detaljene.
5. **Raider-SPRINT og cane-shot på film**: EYES-1s etter-film av ladningen
   (take-09 er før-filmen) — bevis for at fikset faktisk leses på skjerm.

## Runde 2 — designbeslutninger til eieren (fra byggherre-dommen)

Presenteres som valg, én melding, når feedbacken er fordøyd:

- **Sult har ingen konsekvens** (den største): økonomien kan ikke tapes.
  Alternativer: (a) produktivitetsmalus under X sult, (b) settlere kan
  forlate landsbyen, (c) kun HUD-varsel. Ankerne: MineColonies nekter å
  jobbe, TekTopia kan sulte i hjel.
- **Rekruttering på matFLYT** (P3): krev lager ≥ 2× befolkning i stedet
  for flatt 8. Én linje, men en vanskelighetsendring.
- **Kull er brenselnøytralt** (P6): 2 fuel-verdi eller 2 stokker inn.
- **ALE mangler forbruker**: ekte tavern-servering (innkeeper serverer,
  morale-effekt) — aldri en falsk sink.
- **Brønnen**: kutt eller gi den mening (f.eks. vannbehov/brannslukking).
- **Forskningsmateriell på courier-ruten** (P9): den største gjenværende
  logistikk-hullet.

## Runde 3 — roadmap-fortsettelse (fra hovedplanen, etter 1-2)

1. **Blessings v1** (A3): finnes ikke ennå — roguelike-loopen etter
   overlevde raid-netter. Kort-UI ved hearthen, sjeldenhet fra
   raid-vanskelighet.
2. **Captain-epiteter for BRANN/BLOD** (KNOWN_ISSUES #6): saga-løftet
   spillet allerede gir i tekst.
3. **VISUAL-1**: modulær settler-appearance (skin×hår×ansikt×klær).
4. **Ekte yrker for School/Market/Infirmary** — så workerCapacity kan
   settes tilbake.
5. **Fraktboka** (LOGISTICS.md): courier-minutter/dag synlig for spilleren
   — nå som vekt faktisk biter, er tallet meningsfullt.

## Regler som står

- All testkjøring via tools/hearthstead-qa; full x2 for hvert slice-slutt.
- Aldri svekke dommeren; spesifikasjonskorreksjoner føres i kvalitetsboka.
- Skjermbilde før teori ved playtest-rødt.
- Sonnet-arbeidere med disjunkt fileierskap; koordinator committer.
