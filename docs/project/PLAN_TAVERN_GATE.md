# PLAN — SLICE TAVERN-GATE: tavernaen som port for nye settlers

Kilde: eierens ordre 2026-08-26 — «tavern er kritisk for å få nye settlers»,
MineColonies-anker, null emeralds. STRAMMING av RECRUIT-1, ikke nytt system:
samme gauge, samme gjest, samme pris. Eierens ordre opphever frysen for
akkurat denne slicen; alt annet forblir frosset. Designet av to uavhengige
utkast, dømt og syntetisert av byggherren (dom 5) — kravlisten hans står
nederst og er bindende.

## Porten

1. **Én betingelse, ett sted.** `SettlementManager.tickRecruitment` sitt
   attractive-uttrykk (`SettlementManager.java:215-217`) får fjerde ledd:
   `&& tavern != null`. Tavernaen er alt resolvet (:207, `firstValidTavern`
   :310-317). Porten krever GYLDIG bygning — aldri bemannet: en
   bemanningsport dødlåser settlementet når founders dør før ansettelse.
   Bygningsnivå er dødsspiral-sikkert.
2. **Uten taverna: null gain, aldri spawn.** Ikke-attraktiv ruter inn i
   eksisterende decay-gren (:241-243), `recruitProgress--` per sekund.
   Spawn-grenen nås aldri. Ingen ny traveler uten taverna —
   MineColonies-troskap der det teller.
3. **Joiningen portes IKKE.** `tickWaitingTraveler` (:278-283),
   hearth-fallbacken og `TravelerJoinGoal.waitingSpot` (:49-60) er uendret.
   En gjest som alt står i verden er invitert og fullføres på gamle regler.
   Bevarer semantikken i tre GameTests og strander aldri en gjest ved
   verdensoppgradering.
4. **Prisen består.** 4 brød + 8 `#planks`, chest-true, rabatter som før
   (`Costs.java:175-179`, :222-232). Null emeralds — gjøres til LOV med
   vokter-test.

## Stripen leser porten — foran progressbaren

Ny synced slot `DATA_TAVERN = 8`, `DATA_COUNT = 9` (`HearthMenu.java:21-29`),
fylt i `HearthBlockEntity` ContainerData (:185-196) med
`firstValidTavern(s) != null ? 1 : 0`.

**KRITISK rekkefølge i `HearthScreen` (:439-447):** tavern-blockeren rendres
FØR `recruit > 0`-grenen. Ellers viser et tavern-løst settlement med
decayende progress «En vandrer nærmer seg...» — en løgn. Rekkefølge:
alert → **tavern-blocker** → progressbar → beds/food/morale/ready.

Lang (en_us kilde, nb_no paritet, husets nøkkelfamilie):

```
"hearthstead.gui.recruit_blocked.tavern":
  en: "Next settler needs a tavern — travelers have nowhere to stay"
  nb: "Neste settler trenger en taverna — vandrere har ingen steder å ta inn"
```

Omskrives: `hearthstead.guide.recruiting.body` (:175 begge filer — «eller
arnen, om du ennå ikke har noe» strykes for attraksjonen; hearth beskrives
kun som gjestens reserve-ventepunkt), `benefit.tavern`, PLAN_RECRUIT.md
punkt 1-2, SURVIVAL_AUDIT.md (bell-veggen var uflagget; F8-notat).

`/hearthstead recruit` (`HearthsteadCommand.java:243-252`): feedback utvides
— teller hoppede settlements («skipped %s (no tavern)»). Aldri stille
virkningsløs.

## Bootstrap: null settlers trengs for første taverna

| Steg | Krav | Settlers |
|---|---|---|
| Hearth | 3 logs + 5 stein + campfire | 0 — `tryFound` (:101-103) gir 3 GRATIS founders, portfrie |
| Taverna-plan | paper + feather + 2 brød + barrel | 0 |
| Plakett | 5 kobber + 1 jern + 3 planker | 0 |
| Rommet | 2 storage, 1 dør, 3 lys, 36 gulvceller | 0 |
| **Bell (1)** | **INGEN vanilla-oppskrift** | landsby-loot eller emeralds — begge uakseptable |

**Eneste unntaksregel:** data-only oppskrift
`data/hearthstead/recipe/bell.json` (shaped: 3 gull-ingoter + 2 pinner +
1 jern → `minecraft:bell`). Vanilla-item, én JSON, innenfor frysen. Uten den
soft-locker porten enhver verden uten generert landsby på 3 settlers for
alltid. Bell-kravet byttes IKKE — bell er tavernaens emblem og identitet.

## Kantsaker

- **Taverna mister gyldighet mens gjest venter:** gjesten er grandfathered —
  waitingSpot recomputes per tick, faller til hearth, kan betales inn.
  Gaugen for NESTE stopper (decay).
- **Innkeeper:** uendret i tall, ny mening — akselerator over porten
  (effektiv gain 2-4/s med taverna, 0 uten); patience ×2 og −25 % pris
  består.
- **Eksisterende verdener:** påbegynt progress decayer synlig med lesbar
  blocker-linje.

## Testplan

- **Nye (RecruitGameTests):** (d) `noTavernMeansTheGaugeNeverFills` —
  attraktivt-ellers, ingen taverna, `recruitProgress=50` (MÅ seedes >0 så
  decay er målbar); ett tick ⇒ progress==49 og `travelerId==null`.
  (e) `aValidTavernReopensTheGate` — taverna til ⇒ progress stiger.
  (f) `aWaitingGuestSurvivesTavernInvalidation` — gjest venter, tavernaen
  invalideres via ekte mekanisme (`valid=false` på Building-fixturen);
  assert join fullføres ved hearth, pris eksakt.
- **Nye voktere:** (g) `noBuildPlanOrPriceUsesEmeralds` — itererer ALLE
  build_plan-ingredienser + ALLE Costs-linjer, forbyr `Items.EMERALD`.
  (h) `theBellIsCraftable` — bell.json gjennom ekte `RecipeManager`
  (SurvivalAuditWall-stil).
- **Endres:** (c) javadoc + evt. seedet decay-assert; (b) sin javadoc
  omformuleres til gjeste-reserve.
- **Uendret:** RecruitGameTests (a); alle 5 CostsGameTests (fixturene setter
  `travelerId` direkte — join er ikke portet); persistens (:471-495).

## Ikke-mål

Arkitekt-/plankjøp PARKERT (D-006 binder formen). Ingen port på joining.
Ingen pris-/patience-/founder-endring. Ingen starter-taverna. Ingen nye
blokker/items/skjermer.

## Invarianter

Chest truth uendret. Plaketten er landmåler: `Building.valid` er portens
eneste sannhet, null nye skann (oppslaget kjører alt 1/s). Ingen meny-først:
porten åpnes ved å BYGGE. Aldri stille feil: blocker foran progressbar,
command-feedback, synlig decay.

## DECISIONS.md

- **D-TAVERN-1** — porten er bygning, ikke bemanning; gjelder tiltrekning,
  ikke gjester (grandfathered).
- **D-TAVERN-2** — bell får data-only mod-oppskrift; emeralds forbys
  samtidig ved ratchet-test (g).

## Byggherrens kravliste (rangert, bindende)

1. **[Alvorlighet 1 — dishonest]** Tavern-blockeren SKAL rendres foran
   `recruit > 0`-grenen i `HearthScreen.java:439-447`. Akseptkriterium:
   tavern-løst settlement med progress>0 viser tavern-blocker, aldri «En
   vandrer nærmer seg». Må filmes/screenshotes.
2. **[1 — soft-lock]** `bell.json` lander i SAMME commit som porten, med
   vokter-test (h) grønn. Porten uten bell-oppskrift er AVVIST på forhånd.
3. **[1]** Porten kun i attractive (:215-217), `tavern != null`,
   bygningsnivå. Joining/fallback urørt; RecruitGameTests (a), CostsGameTests
   alle 5, persistens-testen forblir grønne UENDRET.
4. **[1 — stille feil]** `/hearthstead recruit`-feedback teller hoppede
   settlements med grunn.
5. **[2]** `DATA_TAVERN=8`/`DATA_COUNT=9` + ContainerData-case + begge
   lang-filer i takt (nøkkelfamilie-stil, nb-paritet samme posisjon).
6. **[2]** Tester (d)(e)(f) med seedet progress og ekte
   invalideringsmekanisme — ingen 0==0-assertions.
7. **[2]** Emerald-ratchet (g) over planer OG Costs-linjer — eierens «ikke
   emeralds» blir vokter, ikke notat.
8. **[3]** Dok i takt: PLAN_RECRUIT.md, guide.recruiting.body (begge språk),
   benefit.tavern, SURVIVAL_AUDIT.md (bell-vegg + F8), DECISIONS.md
   D-TAVERN-1/2.
9. **[3]** RecruitGameTests (b)/(c) javadoc omskrives til
   grandfather-/port-semantikk — testdok skal aldri beskrive utgått design.

NESTE AMBISJON (etter porten): gjestens ankomst ved tavernaen skal LEVES,
ikke bare ventes — gjesten setter seg, innkeeperen serverer (ALE har fortsatt
ingen forbruker, `FLOWS.md:120-148`), og betalingsøyeblikket sees i verden.
Det er TekTopia-halvdelen av porten, og der slår vi begge ankerne.
