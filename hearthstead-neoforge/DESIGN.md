# Hearthstead — Full Mod Design & Roadmap (post-interview synthesis)

## Context

The verified Hearthstead vertical slice (MC 1.20.1/Forge, PR #1: founding, 3 professions, needs sim, animated original settler, premium UI, sound, i18n, 9/9 GameTests, dedicated-server E2E) proved the concept. The user then answered a **100-question design interview** to define the full mod — a spiritual successor to TekTopia (self-built world, organic village life) and MineColonies (management, logistics, raids) with original signature systems. This plan synthesizes every answer into the design + build roadmap. Name stays **Hearthstead**.

## Vision

You are the **mayor-founder** of a village of 20–30 settlers in a world you build yourself, TekTopia-style — the mod detects your architecture, settlers move in and live visibly complex lives, and everything the village owns physically exists in real chests. It is **rough survival with cozy veins**: raids can raze the village to the ground, settlers can be kidnapped or die — but surviving raid nights feeds a **roguelike Blessing loop**, and everyone (friend and foe) accumulates a persistent **Saga** — the Nemesis-inspired memory system. High replay value is a design requirement throughout.

## The ten core systems (consolidated from the interview)

1. **Buildings = your architecture.** Automatic room validation (enclosed + bed + door + light for homes) declared via a craftable **deed plaque**: pick building type, plaque glows green/amber with what's missing, wax-seal stamp + chime on approval. Quality from furnishing (each furniture type scores once) + fulfilled personal wishes. 3 building tiers via contents. Settlers NEVER build — they visibly **repair raid damage** and upgrade your builds. Buildings gate professions.

2. **Warehouse logistics — the flagship.** MineColonies-principle, item-accurate: chests are the truth, Tingboka is a live index; couriers visibly carry everything (farmer→warehouse→smithy→dining hall); reservations prevent double-fetching; plaque-marked zones/filters, requests and priority routes; physical inventory means raiders can genuinely steal. Upkeep flows: food + firewood/warmth + tool wear.

3. **Settlers — «helt sykt detaljert».** Modular visuals (skin×hair×face×clothing + profession outfits). Needs: hunger, energy, morale + social, safety, food variety, warmth, sleep quality, job satisfaction, fun, health. Traits with real AI effects + 1–5★ talents capped growth (learning by doing). Full life-wheel: automatic couples (double bed), children grow 5–8 days/stage, school at the scribe's; no old-age death. Downed-not-dead: dying settlers can be rescued by healer/player; failed rescue = grave in the graveyard, memorial stone, real mourning.

4. **Saga system (Nemesis both ways).** Settlers accumulate memories (combat & rescue, player actions/gifts→loyalty, achievements→titles with small bonuses). Enemy factions field **3–5 named captains each** in a Tingbok «enemy gallery»: generated names/scars/unique gear, learnable strengths/weaknesses, they grow on victories, taunt you, steal trophies (reclaimable at camps), and lieutenants inherit hatred when captains fall. Player death mid-raid: the raid marches on without you.

5. **Raids & defense.** 2–3 original factions with distinct tactics + environmental threats (fire w/ bucket chains, wolf packs, sickness). Escalating, telegraphed 1–2 days ahead (scouts, bard's unease). Raiders hunt settlers, breach gates/barricades (HP, smith-reinforceable), steal from real chests, commit arson. Lighting matters (dark = infiltration). Guards: weapon classes, visible equipment progression, training/veteran ranks, protect-civilians-first AI. Player fights in front line + 6-segment horn/banner command wheel (rally, hold, fall back, focus target, civilians to shelter, militia to arms). Tool-militia: desperate, frightened, costly. **Total destruction is possible.** Kidnapping → visible cages in camps → rescue expeditions. Aftermath: repair dugnad + defense report. Difficulty: three profiles (Fredelig/Balansert/Jernvinter).

6. **Blessings (roguelike loop).** Only after surviving RAID nights: satisfying card-reveal UI at the hearth (rarity frames, flip animation); **raid difficulty determines rarity**. Categories: person-blessings (max 1 per settler, swappable), hearth auras, place-blessings, cursed high-risk cards. Each blessing targets ONE specific thing — scarce strategic choices.

7. **Progression: Hearth tiers + Traditions + research.** Expensive hearth tiers gate capacity/radius/professions. Large tradition tree (20+, Craft/War/Community): new MECHANICS with built-in trade-offs, visible in the world (banners, garlands); **sequencing model** — all attainable in one world, order is the strategic choice. Research: one active resource-consuming project + passive scribe trickle.

8. **Recruitment & employment.** Travelers visit the **tavern**; recruit by paying a price in village-grown goods → craft the **role card** → assign a work **post**. Newcomers live in the **lodging house** (hearth-side rough sleeping as bad fallback); homes near work matter; idle folk gather at social anchors (tavern, dining hall, plaza). Free reassignment; expulsion possible but expensive. Growth also via births, rescues (freed prisoners/caravans), Blessing heroes.

9. **The world outside.** Reactive spawning of faction camps + friendly NPC villages + road caravans around your settlement. Neighbors are alive: they can be raided (help → reputation/trade/recruits; ignore → they fall) and some are rivals. Free-form war expeditions with 2–4 guards (village vulnerable meanwhile). Currency («Sølvmark») is external-trade spice only — can never buy hearth tiers, traditions or blessings. Lightweight own season system (winter pressure; my defaults for details: 7-day seasons, frozen crops, warmth need, desperate winter raids, few clear diseases + healer's herb garden — adjustable).

10. **Presentation.** UI north star = user's inspiration image: dark iron-and-oak panels, portrait citizen cards with skill pips/traits, Town-Hall overview bars, radial orders wheel, alert cards, rarity frames, live settlement map (later). **Tingboka** (tabbed book: People/Work/Storage/Defense/Traditions/Saga chronicle) + HUD alerts. Emotional mumble voices + subtitles only in direct talk; bard-driven diegetic music; rich soundscape, small animals, festivals, weather/light atmosphere. Saga chronicle writes itself; the bard performs it. English first, Norwegian each release. Onboarding = saga quest chain. Multiplayer: co-op village (roles), 2–4 players/village, full pause when unloaded.

## Technical foundation

- **Target: NeoForge 1.21.1** (user delegated; newest healthy ecosystem). First step is porting the prototype's proven core (settlement SavedData, settler entity+animations, room-independent systems) — it becomes the reference implementation.
- Architecture carries over: goal-based AI w/ budgeted WorkScanner, SavedData settlement registry, keyframe animation library, deterministic asset pipeline (tools/), GameTest-driven verification (arena-building pattern), validator scripts. New pillars: room-detection engine (flood-fill validator + plaque BE), logistics engine (chest-index + reservation ledger + courier goals), raid director (faction state machine + captain persistence), saga/memory store (capped per-settler event log), blessing registry.
- Performance: one village, 2–4 co-op players; scans/logistics budgeted per tick as in prototype.
- Compat: JEI/EMI recipes, Xaero map markers, performance-mod friendliness. Nothing else active.

## Build order (answering «hva lages først»)

**Workers first:** 1-3) Farmer/Lumberer/Guard (upgrade from prototype) → 4) **Courier** → 5) **Innkeeper** (recruiting loop) → 6) Cook → 7) Miner → 8) Smith → 9) Healer.
**Buildings first:** Homes (room detection+plaque) → **Warehouse** → Lodging house → Tavern w/ dining hall → Kitchen → Smithy → Mine entrance → Infirmary → Barracks+watchtower → Graveyard.

### Phases toward 1.0 (private until beta, then CurseForge)

- **A1 — Foundation port:** NeoForge 1.21.1 port of prototype core; room detection + deed plaque; homes; modular settler visuals.
- **A2 — Logistics & livelihood:** warehouse system + courier; tavern recruiting chain (goods price → role card → post); lodging; cook + meals + dining hall; needs expansion; wishes.
- **A3 — Raid vertical:** first faction + captains (saga basics), telegraphed raids, gates/HP, horn commands, militia, downed/rescue + healer, repair dugnad, Blessings v1, difficulty profiles. *(= «kjerneloop + raid» alpha the user chose.)*
- **B1 — Depth:** miner+smith+equipment progression, traditions tree + research, hearth tiers, seasons/winter, fire/wolves/sickness, kidnapping+camp rescues, expeditions.
- **B2 — Living world:** NPC villages/rivals, caravans, families/children/school, festivals, bard music, saga chronicle UI, second/third faction, full nemesis growth.
- **1.0:** polish, Norwegian localization, balancing, trailer-worthy moments.

Each phase ends like the prototype did: green build, GameTest suite, server E2E, honest report.

## Verification approach

Continue the proven pipeline: GameTests per system (room validation, courier delivery correctness incl. reservation ledger vs chest truth, raid director, blessing application, saga persistence), dedicated-server E2E scripts, asset validator, anim QA — all headless. Playtesting by the user at every phase end (private until beta).

---

## Appendix — full interview log (100 answers)

R1: Hybrid TekTopia-build + mayor role; 20–30 settlers; rough survival + cozy + roguelike night rewards.
R2: Auto room detection; houses=capacity+quality→morale; building types gate professions; boons after raid nights only.
R3: Settlers only repair/upgrade (never build); «sykt detaljert» personalities + Nemesis reference; friendship & family; modular visuals.
R4: Saga both sides; memories=combat/player/titles; needs all + more; downed-state rescue.
R5: +sleep/job-satisfaction/fun/health; professions=my call (phased full palette); learning+talent; storage+couriers, complex, high replay.
R6: Own factions (2–3) + environmental threats; escalating telegraphed; hunt/breach/steal/arson; ALL reward types named «Blessings».
R7: All military layers; all captain features; fight+command; blessings=one specific target each, bigger later.
R8: Own light seasons; progression=capacity-gated+expensive+research+trade-offs (my 3 proposals); fire/wolves/sickness; camps+NPC villages+caravans.
R9: Mix hearth tiers+traditions+active research; fully self-running civilians + military control + emergency militia; Tingboka+HUD; sagabook+bard.
R10: Full life-wheel; all recruitment paths; currency must not buy everything (external spice only); living neighbors + rivals.
R11: Mumble voices+subtitles in talk; bard-driven music; all atmosphere layers; English first.
R12: Co-op village; 2–4 players/one village; full pause unloaded; version=my call → NeoForge 1.21.1.
R13: Home=enclosed+bed+door+light; quality=furnishing+wishes; TekTopia-style glowing plaque (made satisfying); settlers choose, player overrides.
R14: Critical buildings first; plaque-choice types; 3 tiers via contents; tavern+dining hall & graveyard+memorial.
R15: Large tree 20+; new mechanics+trade-offs+visible; sequencing not lockout; active+passive research.
R16: Blessing UI choice made satisfying; difficulty→rarity; all categories incl. cursed; max 1/person, swappable.
R17: 3–5 captains/faction + gallery; inheritance&promotion; grow on wins+taunts+trophy theft; player death → raid continues.
R18: Total destruction possible; kidnapping+rescue; repair dugnad+defense report; three difficulty profiles.
R19: Command wheel=my synthesis (6 segments); tool-militia+fear; gates/horn/lighting (no traps); protect-civilians-first.
R20: Tidy mineshaft; FULL flow logistics; upkeep=food+firewood+tool wear; cook+meals.
R21: 5–8 days/stage; no old-age death; school at scribe; simple automatic marriage.
R22: Card+quip lines; gifts only; war expeditions 2–4 guards; wishes diegetic+list.
R23: Tavern recruiting; hire=goods price→role card→post; free reassign/expensive expulsion; lodging+hearth fallback+social anchors+job proximity.
R24: Reactive spawning; free hearth placement; saga-quest onboarding; free-form expeditions.
R25: Alpha=core loop+raid; private until beta; keep Hearthstead; JEI/map/perf compat only.
(Dismissed round on sickness/winter details → my defaults in system 9, adjustable.)
== all animation requirement ==

## Addendum (user directives during A1)
- **Every settler task must have its own animation** — no shared/generic work loops: courier carry, mining, smithing, cooking, serving, healing, repairing, sowing vs harvesting, resting, socializing each get distinct keyframe profiles. Build/extend tooling as needed (animation preview renderer, pose sampler, texture pipeline) whenever it makes the mod better.
- Medieval theme confirmed as all-encompassing (art, UI, naming, sound).
