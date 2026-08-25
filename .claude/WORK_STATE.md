# WORK_STATE — 2026-08-26 natt (Opus-økt, wave R)

## Mode
Ultracode-arbeidsform: koordinator + parallelle Sonnet-arbeidere, streng
fileierskap; koordinator kompilerer/committer/kjører QA. Eieren sover —
BYGGHERREN (mini-eieren) taler med eierens stemme til han er tilbake:
.claude/agents/byggherren.md + docs/project/BYGGHERRENS_VILJE.md (alle
eierens direktiver ordrett) + BYGGHERRE_DOM_1.md / _2_ANIM.md.

## Landet og pushet i natt (alle mot Byggherre-dom #1)
- Brenselsøkonomi (Fuel.java): smelter/bakeri/smie/bryggeri brenner; kull
  fra tømmer er kaldstart-unntaket. Bloom-kjeden retunet til ekte x1.67.
- Kurér-rute 5: mat lager→peis (under restock, over opprydding) + brensel-
  etterfylling. Karusellen lukket (keep-back gulvet på brenselreserven).
- Synlig rangrustning: SettlerArmorLayer + gen_armor.py (4 tiers).
- Bueskytteren: WATCHTOWER-yrke, DEX-stige (Steady Hand/Power Shot/Triple
  Shot), chest-true piler fra tårnets kister.
- Profession.martial(): bueskytteren slutter å panikke/sove/ignorere alarm.
- Reparasjonsdugnaden: arr registreres ved brann, mureren + ledige fikser
  dem med ekte stein.
- Forskningsbonusene KOBLET (Production-ticks, vaktdrill, Åkerskifte).
- 33 survival-oppskrifter for byggeplaner + ratchet-test.
- Costs.java: én pristabell + navngitte rabatter (additivt, cap -50%).
- Rekruttpris godtar alle plankeslag.
- Fire tester seeder brensel; FLOWS/PLAN_CHAINS/COSTS true-et opp.

## I lufta
- polermester-R: CHOP + PICKUP_STOW OMBYGGING (Byggherre-dom #2 gir
  eksakte måltall), skins, 4 manglende outfits (scholar/miller/brewer/
  archer), 5 signaturlyder, ALLE språknøkler (research + archer + rabatt),
  3 goal-registreringer i SettlerEntity (Scholar 6, Archer 2, Repair 5).
- ARMOURY-1: GuardRank chest-true (rustning kjøpes, trylles ikke).
- HANDBOOK-2: håndbokkapitler + advancement-kjede.
- RAIDER-BREACH: raidere som faktisk bryter dører og stjeler fra kister
  (kaller RaidDirector.recordScar før hver ødeleggelse).

## Bevis
Gametest-suiten kjører i et RENT worktree på committed HEAD
(scratchpad/verify-tree) — hovedtreet er låst av arbeiderne. Det er krav 1.
Live-økt (hsqa-live) står med den GAMLE jaren; ny jar må bygges før film.
Levert til eier i kveld: landsbyfilm + alle 5 animasjonssider.

## Neste
1. Suiteresultat → fikseloop med eiende arbeidere.
2. Land de fire siste → kompiler → commit → hovedtre-suite → slett BLOCKED.
3. Ny jar → film scener 15-18 (SHOWCASE_PLAN) + «følg brødskiva»-klippet
   (kjeden lukker seg faktisk: åker→peis→lager→mølle→bakeri→lager→peis).
4. Ny Byggherre-dom på alt som landet. Så GATE-1 (full x2).
Gjenstår ufordelt: krav 7 (verktøy chest-true + slitasje) — venter på at
polermester slipper SettlerEntity.
