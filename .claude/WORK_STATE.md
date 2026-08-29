# WORK_STATE — 2026-08-26 ~18:50 (ny sesjon, koordinator)

## EIERENS LEVERANSEMODUS (2026-08-26 ~19:15)
«jeg venter til hele overhaulen er good» — eieren venter på HELHETEN.
Ingen delleveranser, ingen mellom-jar, ingen «se på dette nå»-meldinger.
Neste kontakt med eieren er når overhaulen står: demokravene D1-D17
avkrysset med bevis, full x2 + gate grønn, jar + showcase-film. Fortsett
sammenhengende til da; rapportér kun hvis noe krever hans beslutning
(A3-pitchene: sult-konsekvens, matflyt-gate, charcoal, ALE-servering).

## Modus
TOTAL OVERHAUL (eierordre i kveld, ordrett i BYGGHERRENS_VILJE.md): UI,
animasjon, teksturer («100 bedre»), core gameplay → spillbar demo med satte
krav, balanse. «ikke start på nytt» — alt i eksisterende kodebase. Frysen
gjelder fortsatt for NYE systemer; eierens eksplisitte bestillinger går
foran. Eieren vil SE fremdrift (film/skjermbilder/jar).

## UI-ytelse landet 2026-08-29 (egen sesjon, Opus)
Eieren meldte «UI FPS drop» som P0. Målt, ikke gjettet — instrumentet er
`client/debug/UiProfiler` (QA-only, `-Dhearthstead.uiprofile=true`), som
tar tiden på `ScreenEvent.Render` Pre→Post på render-tråden og logger
ms/frame, KB/frame og fps per skjerm. Bevis: `qa/reports/uiperf/`.

Tre funn, i rekkefølge etter hvor mye de kostet:
1. **Bakgrunnen, ikke UI-et.** `Screen#renderBackground` kjører
   `processBlurEffect` — en fullskjerms post-process hver frame.
   Settler-arket: 117 ms/frame; med HELE panelet fjernet: 110 ms. Altså
   ~7 ms Hearthstead, ~110 ms bakgrunn. `AbstractContainerScreen`
   overstyrer den med `renderTransparentBackground` — derfor kostet
   Hearth 4 ms mens settler-arket kostet 117. Alle mod-skjermer gjør nå
   det samme. **117 ms → 3.98 ms.** Kontroll: vanilla `PauseScreen` og
   `ConfirmLinkScreen` måler fortsatt ~115 ms i samme scene.
2. **Nine-slice-flisene.** `blitTiledSprite` gir ÉN GL-draw per flis.
   `panel/window` var 18x18 med 7px kant → 4x4 midte; et 256x322-ark
   flisla den 4697 ganger. Midten er flat farge, så generatoren lager nå
   `2*border + INTERIOR` (50) — bit-identisk kunst, ~42x færre draws.
   `widget/divider` var 2px bred (120 draws per strek) → 50.
3. **Allokering per frame.** Hearth bygde Components og strenger hver
   frame for tall som endrer seg hvert minutt; render() allokerer nå
   ingenting (pakket long-stempel + cache). 942 → 526 KB/frame.

Skjermene selv: Hearth er kommandosentral på mørk eik (ikke pergament),
faner INNI rammen (de lå på topPos-14 og ble klippet bort ved guiScale 3),
statuslinjen brytes over to reserverte linjer (den lengste er 368px på
norsk og rant ut av vinduet). Settler-arket er to kolonner når bredden
finnes (322 → ~219 px høyt, ingen scroll ved normal oppløsning), én kolonne
under 400px; attributter er tall — «25 / 100» — med brass-bar og
hover-forklaring, slik eieren ba om.

IKKE gjort: `DevelopmentScreen` finnes ikke i dette repoet i det hele
tatt (ingen fil, ingen referanse). Den må bygges fra bunnen; ikke startet.

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

## LANDET OG GRØNT (pushet)
- Romskanner-fiksene (barriere=himmel; kandidat må oppfylle planen).
- Nybegynner-fiksene: DEMO_READMEs speilvendte plakett-oppskrift rettet,
  7 recipe-unlock-advancements, milepæler toaster+annonserer.
- TAVERN-GATE komplett: porten, bell.json, emerald-vokter, blocker foran
  progressbar. gametest 278/278 (artifacts 20260826T190321Z).
- OVERHAUL_PROGRAM.md (byggherre-dom 6, D1-D17 bindende) og
  PLAN_LOGISTICS_OVERHAUL.md (byggherre-dom 7) skrevet.

## Aktivt akkurat nå (4 arbeidere)
- A0: KF-001/004/005 → grønn full-baseline + frys yrkes-roster (D8).
  Trenger SEAM-vindu på lang for KF-004 — rapporterer nøkler.
- B1 (teksturmester): palett-revolusjonen i texlib.py + ramp-gates i
  validate_assets.py. Produserer D16 før/etter-par løpende.
- Animasjonsmester: CHOP-redesign, PICKUP, CANE-klipp, CELEBRATING,
  GUARD_STANCE. In-flight-commit finnes (quick RØD på 1 av 63 klipp —
  det er klippet under redigering, ikke en defekt).
- S1 logistikk: lesbar stans (StopReason additiv, courier-avlesning,
  plakett-lampe). Trenger SEAM-vindu på lang — rapporterer nøkler.

## Neste (koordinator, i rekkefølge)
1. Sekvenser lang-SEAM: A0s KF-004-nøkler, så S1s stopp-grunner.
2. Etter B1: K1 (item i offline-render) → ANIM-slicene; B2/B3; C1
   UI-systemgrep → C2-C5 skjermene.
3. S2 (jobbvalg med score), S3 (teater+kjerre), S4 (fraktbok+fraktkasse).
4. A2 ærlighetsfikser, A3 balansemålinger + eierpitcher, A5 KF-015.
5. Sluttsertifisering: full x2 + gate, D1-D17 avkrysset med bevislenker,
   showcase-film + jar til eieren. FØRST DA kontaktes eieren.

## Regler som gjelder
Suite kun via tools/hearthstead-qa; kun koordinatoren kjører suiter; aldri
suite under redigering; én om gangen; 15 GB RAM = aldri suite + klient
samtidig. Dommer svekkes aldri. Arbeidere har disjunkte filer; kun
koordinator committer/pusher. Pillow er installert i containeren (assets-
validatoren krever den — reinstaller ved ny container).
