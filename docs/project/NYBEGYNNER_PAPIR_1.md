# Nybegynner-papirøkt 1 (2026-08-26) — funn og disposisjon

Full rapport i agent-loggen; dette er koordinatorens disposisjon. Metode:
papir-gjennomspilling hearth → første grønne plakett → første settler via
taverna, kun mot det spillet selv viser (lang, oppskrifter, advancements,
skjermklasser for hva-vises-når). Stoppeklokke-gjetning: 60-90 min for ekte
nybegynner, hvorav ~30-45 min var én dokfeil (funn 1).

## Fikset umiddelbart (denne commiten)

1. **[Risiko 5] DEMO_README hadde plakett-oppskriften speilvendt** — sa
   «5 iron + 1 copper + oak planks»; koden krever 5 kobber + 1 jern +
   3 planker (vilkårlig tre). Rettet, med grid-plassering beskrevet.
2. **[Risiko 4] Ingen oppskrifter unlocket i oppskriftsboken** — ingen
   recipe-advancements fantes. Lagt til 7 unlock-advancements
   (advancement/recipes/): hearth (av logs), plaque (av kobber), alle 33
   build plans (av papir), handbook (av bok), flour (av hvete), wool_bolt
   (av ull), bell (av gull).
3. **[Risiko 4] Milepælene varslet aldri** — show_toast/announce_to_chat
   var false på begge advancements. Slått på.

## Dekkes av TAVERN-GATE-slicen (pågår)

4. **[Risiko 5-dagens] «The village is attractive — someone will come»
   lyver uten taverna** — byggherrens krav 1 (blocker foran progressbar).

## Køet til UI-/polish-strømmen

5. **[Risiko 3] Gjesten som venter er umerket** — samme navn/utseende som
   egne settlere; kun chat-linjer skiller. Kandidat: navneskilt-variant
   («Traveler»), partikkel eller glød mens den venter. Hører naturlig til
   TAVERN-GATE-oppfølgeren «gjestens ankomst skal LEVES».
6. **[Risiko 2] Prisen (4 brød + 8 planker) står kun i Handbook** som
   lappen kaller «bonus» — kandidat: vis prisen i rekrutteringsstripen
   eller tavernaens gevinstlinje.
7. **[Risiko 2] scan.leak_unknown («a gap somewhere»)** — vag fallback når
   skanneren ikke kan peke; sjeldnere nå etter barriere-fiksen, men
   fallbacken bør helst aldri inntreffe.

## Verifisering utestående

- Unlock-advancements må sees laste rent i neste gametest-kjøring (grep
  loggen for advancement-feil) og helst bekreftes live (oppskriftsbok
  viser hearth/plaque/planer).
- Toast/announce bekreftes visuelt i neste live-økt/film.
