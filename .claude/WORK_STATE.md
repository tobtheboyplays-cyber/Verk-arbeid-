# WORK_STATE — 2026-08-26 ~18:50 (ny sesjon, koordinator)

## Modus
TOTAL OVERHAUL (eierordre i kveld, ordrett i BYGGHERRENS_VILJE.md): UI,
animasjon, teksturer («100 bedre»), core gameplay → spillbar demo med satte
krav, balanse. «ikke start på nytt» — alt i eksisterende kodebase. Frysen
gjelder fortsatt for NYE systemer; eierens eksplisitte bestillinger går
foran. Eieren vil SE fremdrift (film/skjermbilder/jar).

## Landet i denne sesjonen
- Romskanner-fiksene (a09cc27→rebased): barriere=himmel (aldri tak);
  plakett-kandidat vinner kun med rom som oppfyller planens krav.
  gametest 273/273 GRØNN (evidenskjøring 18:36, artifacts 20260826T183*).
- Teksturmesterens settler-ark-førsteutkast REDDET: forrige sesjon pushet
  793e05b («in flight, protected») før den døde. Rebased inn, quick grønn.
  Arket trenger fortsatt preview-verifisering + ferdigstilling (UI-strøm).
- Jar sendt eieren 18:42 (bat-flyt). PLAN_TAVERN_GATE.md skrevet og
  byggherre-dømt (dom 5, kravliste 1-9 bindende).
- Åpen kuriositet (ufarlig, alle asserts ærlige): ceiling-fixturens y1-lag
  fyller 3/19 celler (16 ukjente boundary-celler). Evidens:
  [hs-scan-debug] i artifacts 18:36-loggen. Tas i QA-herdingspasset.

## Aktivt akkurat nå (2 bakgrunnsstrømmer)
- TAVERN-GATE-arbeider (sonnet): implementerer porten etter
  PLAN_TAVERN_GATE.md kravliste 1-9. Eier: SettlementManager, HearthMenu,
  HearthBlockEntity, HearthScreen(stripen), HearthsteadCommand,
  RecruitGameTests, EconomyWallGameTests(ny), bell.json(ny), begge
  lang-filer, dok-synk.
- overhaul-masterplan-workflow: referansejakt (UI/anim/tekstur, internett)
  + demokrav + balansekart → byggherre-dom → OVERHAUL_PROGRAM.md.

## Neste (koordinator, i rekkefølge)
1. TAVERN-GATE lander → review → gametest → commit/push → jar til eieren.
2. Masterplan-dom lander → skriv OVERHAUL_PROGRAM.md + DEMO_KRAV →
   start de tre første slicene som parallell flåte (disjunkte filer;
   lang/HsUi er SEAM — sekvenser).
3. Settler-arket ferdigstilles (teksturmester-rolle) som del av UI-strøm.
4. Ved integrasjonsslutt: full x2 + gate (green_streak ≥ 2) i bakgrunnen.

## Regler som gjelder
Suite kun via tools/hearthstead-qa; kun koordinatoren kjører suiter; aldri
suite under redigering; én om gangen; 15 GB RAM = aldri suite + klient
samtidig. Dommer svekkes aldri. Arbeidere har disjunkte filer; kun
koordinator committer/pusher. Pillow er installert i containeren (assets-
validatoren krever den — reinstaller ved ny container).
