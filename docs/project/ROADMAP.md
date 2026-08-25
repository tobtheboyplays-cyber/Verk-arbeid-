# ROADMAP — the updated long-term plan (2026-08-25)

*Full review of where the mod actually stands against DESIGN.md's ten
systems and the original A1→B2 phasing, plus a deliberate gap analysis
against the two references (MineColonies, TekTopia). This supersedes the
phase ordering in the original plan where they differ; DESIGN.md's vision
and the permanent invariants stand unchanged.*

## Where we actually are (honest state)

The original plan expected us to be mid-A2. Reality: the fleet sessions
pulled several B-phase systems forward. LANDED and code-verified (gate run
pending): plaque room engine with grace period; hire/fire employment with
candidates and costs; five attributes + traits + training; Dagsverk effort
limits + skill-scaled farm plots; day rhythm/postings; road preference +
desire paths; warehouse/courier with reservation ledger; tavern recruiting
with goods price; INNKEEPER; mayor + boons; guard ranks + leap; raid
pressure/telegraph/theft/defense report; NAMED captains with succession
(saga v1); research v1 (Prøvebenken, in flight); six chain items (in
flight); 33 keyframe clips punched up + side-swing felling; five real trade
sounds; premium UI kit + five screens measured in two languages; QA: ~50
GameTests, live harness, fast client boot.

## Gap analysis vs the references

### From MineColonies — adopt
- **Requests board** (their postbox/request system, their best legibility
  idea): a Tingbok tab listing what every building currently wants
  (restock shortfalls, research materials, repair goods) with courier
  status. Ours stays chest-true — the board READS the ledger, never
  creates items. → C2.
- **Herder & Fisher**: PASTURE and FISHERY buildings exist with no trade
  behind them — the only Ring-1 sources still dead. Herder breeds/tends,
  fisher works water. Closes the meat/hide inflow the butcher/tannery
  chains assume. → C1 (next content slice).
- **Happiness breakdown**: morale exists; SHOW its components on the
  settler sheet (food variety, sleep, job satisfaction…) the way `why`
  does for AI. Legibility is our brand. → C2.
- **Colony border visualization**: a toggle on the hearth screen that
  shows the radius as a particle ring for ~30s. Cheap, answers the most
  common "why won't it register" confusion. → C2.
- **Guard classes (archer/knight)**: user-deferred; keep at D1.
- **University tree** → covered by Prøvebenken + traditions (D1).
- REJECT: builder/schematics (violates "settlers never construct"),
  taxes/abstract currency (violates chest truth), the 30-trade sprawl
  (quality over count; certify what exists first).

### From TekTopia — adopt
- **Building tiers via furnishing** (their structure-quality feel, already
  promised in DESIGN system 1): tier 2/3 via contents, quality feeds
  morale + capacity. → C1.
- **Patrol waypoints**: player-placed watch posts guards actually walk
  (banner/post item). Pairs with GUARD-2's captain. → C2.
- **Children + school** (their strongest life-sim beat): B2 as planned,
  after the core loop is gate-green. → D1.
- **Bard + festivals**: diegetic music/saga performance → D2.
- **Nomad/merchant caravans** → D2 with the outside world.
- REJECT: emblem/token hiring (user replaced with hire/fire), fight-night
  arena (fun, not core), villager nitwit (a Trait covers the flavor).

## The re-sequenced plan

**C0 — GATE (now, blocking everything):** integrate the fleet, gametest
suite green with fix-loops, full ×2 green_streak ≥ 2, RELEASE_GATE, the
showcase film per SHOWCASE_PLAN.md. The mod must be PLAYABLE and PROVEN.
**C1 — Complete the living village (1-2 sessions):** Herder + Fisher;
building tiers via furnishing; COSTS-1 central pricing with village
discounts; GUARD-2 armor ranks + Vaktkaptein; scholar/summons/research
polish from the fleet's follow-up notes; wire Research bonuses into
Production; PICKUP_STOW triggers at every pickup site.
**C2 — Legibility & command (1-2 sessions):** Tingbok requests board;
morale breakdown; border toggle; patrol waypoints; horn/banner command
wheel v1 (rally/hold/shelter); healer + downed-not-dead rescue (infirmary
exists) — the last A3 promise.
**D1 — Depth:** archers + knight class; traditions tree + hearth tiers;
seasons/winter; fire/wolves/sickness; kidnapping + camp rescue; Blessings
v1 (post-raid card reveal — the roguelike loop the vision leads with).
**D2 — Living world:** children/school/marriage; bard + festivals;
caravans + NPC villages + rivals; second/third faction; full nemesis
growth; saga chronicle UI.
**1.0:** balance from live play, Norwegian parity audit, trailer, private
beta → CurseForge.

## Working rules that got us here (keep)
Fleet parallelism under strict file ownership with the coordinator
playing/testing live and feeding findings back; constitutions
(FLOWS/COSTS/JOB_STANDARD) before content; every claim gated by the QA
system; the user sees films, not promises.
