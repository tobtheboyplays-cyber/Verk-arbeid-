# WORK_STATE — 2026-08-26 natt (Opus-økt, wave R ferdig)

## Mode
Koordinator + parallelle Sonnet-arbeidere, streng fileierskap. Eieren sover.
BYGGHERREN (.claude/agents/byggherren.md) taler med eierens stemme: dom #1
og #2 i docs/project/BYGGHERRE_DOM_*.md, dom #3 er bestilt på nattens arbeid.

## VIKTIG ved oppstart
Containeren er blitt tilbakestilt TO ganger i natt. Remote er sannheten:
  git fetch origin claude/hearthstead-settlement-mod-vbdb9n
  git reset --hard FETCH_HEAD
Sjekk også `git branch --show-current` — hovedrepoet havnet én gang i
detached HEAD med en commit utenfor grenen (reddet). Unngå `cd` inn i
worktrees i bakgrunnskommandoer; bruk `git -C <sti>`.

## Landet og pushet i natt (alt mot Byggherre-dom #1)
Brenselsøkonomi (Fuel.java, kull-kaldstart, bloom x1.67 med ratio-test) ·
kurér-rute 5 (mat til peis + brensel, karusell lukket) · synlig rangrustning
(SettlerArmorLayer + gen_armor.py) · rustning KJØPT fra våpenhuset, ikke
trylt (ARMOURY-1) · bueskytteren med DEX-stige, Power/Triple Shot og
chest-true piler · Profession.martial() · reparasjonsdugnaden · raidere som
bryter dører og stjeler (arr FØR ødeleggelse) · forskningsbonusene koblet ·
33 survival-oppskrifter med ratchet · Costs.java med navngitte rabatter ·
håndbok: 6 kapitler + 2 advancements + 466 nøkler i paritet · polermester:
CHOP og PICKUP_STOW ombygget til dom #2s måltall, lavgarde-sverdholdning,
nye ansikter/hår, fire antrekk.

## Testtilstanden — les KF-019, KF-020, KF-021
- KF-019 LØST: tester delte én verdensklokke (og verden). Hver testklasse
  eier nå sin batch. Modden var aldri ødelagt; suiten løy.
- KF-020 LØST: CrafterWorkGoal manglet requiresUpdateEveryTick(), så ALLE
  oppskrifter tok dobbelt så lang tid i ekte spill. Ekte spillfeil.
  FarmerWorkGoal krevde en arbeidsgiver for å se en avling — fallback lagt inn.
- KF-021 ÅPEN og VIKTIGST: suiten er USTABIL. Seks kjøringer uten
  kodeendring ga 31/25/21/20/22/24 feil, og medlemskapet byttet. Ingen gate
  kan hvile på dette. FLAKE-1 jakter rotårsaken (hypotese: tilfeldige
  attributtkast — Dagsverk-kapasitet er 20 + STAMINA/5, og bondens
  tendede rute er DEX-skalert).
- Fortsatt rødt uten kjent årsak: de tre lumberer-testene (instrumentert nå
  med routeFailureNote), cleave-bystander (grisen FJERNES, tar ingen skade),
  summon-payload, homeinvalidated, plakett-advancement.

## Neste
1. FLAKE-1 lander → stabiliser suiten → DA er tallene til å stole på.
2. Byggherre-dom #3 → nye fikse-arbeidere per krav.
3. Film: live-økt kjører på nattens jar. Levert til eier: landsbyfilm +
   alle fem animasjonssider (side 0 fra gammel jar, 1-4 fra nattens).
   Gjenstår SHOWCASE_PLAN-scenene 15-18 + «følg brødskiva» (kjeden lukker
   seg: åker→peis→lager→mølle→bakeri→lager→peis→munn).
4. Så GATE-1 (full x2, green_streak >= 2) — men ikke før KF-021 er lukket.
