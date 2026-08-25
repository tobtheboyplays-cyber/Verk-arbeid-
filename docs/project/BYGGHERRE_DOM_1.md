# Byggherre-dom #1 — 2026-08-25, HEAD a5eecbe-æraen. DOM: AVVIST

Full dom i koordinator-loggen; dette er kravlista med ansvar/status.
Ingen GATE-1 før krav 1-5 er lukket.

| # | krav | alv | ansvar | status |
|---|---|---|---|---|
| 1 | Null suite-bevis for hele flåte-æraen; siste full RØD | 1 | koordinator | **BEVIS KJØRT** 2026-08-25 22:15Z på rent worktree: 186 tester, 31 røde. Baseline på forrige commit: 160/25 — natta ga NULL regresjoner. Rotårsak isolert som KF-019 (settlere ankommer aldri jobben, pre-eksisterende) og eid av ARRIVAL-1. Grønn kjøring gjenstår. |
| 2 | Forskning død 3×: scholar-goal uregistrert, lang-nøkler mangler, bonuser ukoblet | 1 | polermester-R (goal-linje + nøkler); koordinator (bonuser) | **bonuser LANDET** 6b9fc25; goal-linje + nøkler hos polermester |
| 3 | Ingen spiser kokkens mat (mat ekskludert fra frakt) | 1 | FOOD-1R rute 5 | **LANDET** b844016 |
| 4 | Vaktrustning trylles + usynlig | 1 | ARMOR-1R (synlig); NY: chest-true armoury-kjede | **synlig LANDET** 4caef43; chest-true kjede gjenstår |
| 5 | 27/33 bygg uten survival-oppskrift | 1 | RECIPES-1 | **LANDET** 1b885b4 (alle 33, ratchet-test) |
| 6 | Håndbok/advancements dekker ikke halve modden | 2 | NY: HANDBOOK-2 (nøkler via handoff — polermester eier lang) | kø |
| 7 | Verktøy trylles, ingen slitasje (F2) | 2 | kø: TOOLS-1 (SettlerEntity — etter polermester) | kø |
| 8 | Rekruttpris krevde eksakt EIK | 2 | koordinator | FIKSET (tag #minecraft:planks) |
| 9 | COSTS-rabatter er fiksjon | 2 | kø: COSTS-1 | kø |
| 10 | Smelter fed-path under FLOWS-båndet + testen unnviker bloom | 2 | FUEL-1R-addendum | **LANDET** b649596 (x1.67, ratio-test over terskelen) |
| 11 | Stein/brensel-sinker mangler | 2 | REPAIR-1R + FUEL-1R | **BEGGE LANDET** (brensel b649596, dugnad committet) |
| 12 | Visuelle påstander ufilmet | 2 | koordinator: FILM per SHOWCASE_PLAN (village + anim-sider levert i kveld) | i arbeid |

NESTE AMBISJON (byggherrens): «Følg brødskiva» — ett kamera, én gjenstand,
chest-true hele kjeden åker→mølle→ovn→kurér→hearth→munn. Det klippet er
traileren, og bare mulig fordi vi aldri jukser med items.


## Dom #2 (samme natt): CHOP + PICKUP_STOW keyframe-analyse — AVVIST
Egen fil: BYGGHERRE_DOM_2_ANIM.md. Sendt til polermester-R som prioritet 0.
