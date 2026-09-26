# First-Raid Combat Reference Research

**Audience:** Hearthstead design and engineering  
**Date:** 2026-09-03  
**Decision:** How should Hearthstead's first raid allocate Guard/Archer targets, protect the player, preserve posts, communicate the captain/classes, and remain readable and replayable without turning into an RTS?

## Executive answer

Prefer **Architecture A: Anchored Roles + Bounded Threat Slots**. Keep Stand/Tower/Patrol as the player's durable intent, elect at most one eligible melee bodyguard near the issuing player, and allocate each raider a small server-authoritative engagement capacity. Guards prefer Brutes and threats inside their leash; Archers prefer exposed Skirmishers and the captain when line of sight is valid. The captain owns one tactical modifier, not inflated health. A single bossbar shows captain health plus exact remaining class counters from the sealed roster.

This combines the most legible public patterns—MineColonies' explicit Guard/Patrol/Follow tasks and raid progress, Ancient Warfare 2's bounded home/patrol orders and alert propagation, Tektopia's simple posts, and Guard Villagers' low-friction equipment/follow loop—without copying implementation, UI, text, assets, numbers, or encounter scripting.

## Method and clean-room boundary

- **FACT** means a behavior or status stated in an official page, first-party wiki, project repository, or clearly identified public project page.
- **ANECDOTE** means a user report; it is a discovery/risk signal, not proof of prevalence.
- **INFERENCE** is a Hearthstead-relevant conclusion from the cited facts.
- **PROPOSAL** is original Hearthstead design, not a claim about another mod.
- No jars, code archives, textures, models, sounds, or other assets were downloaded or decompiled. Open repositories were used only to verify public status/license or documented behavior. Millénaire explicitly forbids reuse of its elements without authorization; treat it as observation-only.

## Evidence matrix

| Mod / status | FACT: verified public behavior | Player problem solved | Weakness / complaint | Combat principle worth testing | Original Hearthstead proposal | MP/perf risk and required test | Source / license status |
|---|---|---|---|---|---|---|---|
| **MineColonies** — current, 1.21-era | Guard Tower exposes Patrol, Guard and Follow tasks; Follow acts as a personal bodyguard. Raids announce a direction, show a top-screen progress bar, may use multiple groups/types, attack colony targets, and scale with colony development and prior victories. | Gives strategic placement, personal protection, warning and encounter progress. | Official wiki says exact difficulty inputs are not public. **ANECDOTE:** players report guard wipes, path traps and resorting to raid-kill commands; frequency is unknown. | Player intent should persist independently of momentary aggro; warning and remaining threat must be visible. | Keep three orders but make their leash/election explicit; show exact sealed class counters rather than opaque strength. | Many guards independently scanning/pathing and multiple bars can be expensive. Test 5/15/30 defenders, two clients, unload/reload, wall/tower path failure, and bar cleanup. | [Raids](https://minecolonies.com/wiki/systems/raid/), [Guard Tower](https://minecolonies.com/wiki/buildings/guardtower/), [config](https://minecolonies.com/wiki/misc/configfile/). Active public project; do not assume its content license permits reuse. |
| **Guard Villagers** — active | Guards use sword or crossbow, accept armor/offhand shield or food, can stand/patrol and unlock follow behavior; clerics can heal and smiths repair. The author notes disabling patrol may reduce unintended lag. | Makes vanilla villages defend themselves with little management. | Official known performance advice implicates patrol load. **ANECDOTE:** tower users report ranged guards leaving towers or failing to shoot; prevalence unverified. | Equipment must remain physically understandable; ranged posts need a stronger commitment than generic pursuit. | Archer Tower Post keeps a vertical anchor and uses a line-of-sight envelope; never jumps down merely because target distance shrank. | Patrol scans, reputation/ownership and allied targeting require server tests. Test tower occlusion, ledges, retreat, 20 guards and mixed mods. | [CurseForge project page](https://www.curseforge.com/Minecraft/mc-mods/guard-villagers); page links public repository and states permissive porting, but exact version license should be checked before any reuse. Observation only here. |
| **Villager Recruits** — active 1.20.1 | Public page describes recruit armies, group commands, configurable behavior, PvP diplomacy/team compatibility. An addon documents line/square/circle/wedge formations and march/raid/stop/follow commands. | Provides explicit mass-unit control and coordinated movement. | **ANECDOTE:** friendly-target incidents are reported in modpacks; compatibility and configuration may be causal. Addon behavior is not proof of base-mod behavior. | Group orders improve clarity, but unlimited command scope increases ownership and friendly-fire risk. | Do not add formations for first raid. Borrow only the concept of one visible order per defender and a server-authoritative ally filter. | Highest MP risk: ownership, team changes, stale group commands, mass pathing. Test two settlements, two owners, PvP on/off, reconnect and friendly entities. | [Villager Recruits](https://www.curseforge.com/minecraft/mc-mods/recruits), [Recruits Addon](https://www.curseforge.com/minecraft/mc-mods/recruits-extras). Public pages; license not established in reviewed evidence. |
| **Human Companions / Modern Companions** — Human Companions stable through 1.20.1; Modern Companions current 1.21.1 successor/rebrand | Knight/archer classes; follow, patrol and guard states; alert/hunt controls; stationary ranged option; kill XP and health progression. Modern page adds radius control and recall. | Makes protection personal, portable and easy to command. | Human Companions officially lists archers shooting nothing after a kill and knights hesitating; it also warns companions die easily and inventory drops can duplicate/disappear. | Explicit target clearing, bounded radius and kill feedback matter; portable followers should not consume all defenders. | Elect one bodyguard only during active raid and inside Stand leash. Clear dead/terminal targets immediately; show XP pulse only after authoritative kill credit. | Target invalidation, inventory conservation, recalls and follow teleport are risks. Test terminal target handoff, projectile owner death, reconnect and full inventory. | [Human Companions](https://www.curseforge.com/minecraft/mc-mods/human-companions), [Modern Companions](https://www.curseforge.com/minecraft/mc-mods/modern-companions). Project-page behavior; license not verified. |
| **Ancient Warfare 2** — historical, Minecraft 1.12.x | Combat roles include Soldier, Archer, Commander, Medic and engineers. NPCs broadcast hostile alerts; civilians flee while combat NPCs respond. Home radius bounds pursuit. Combat Orders support multi-point patrol or one-point guard; a Command Baton can issue attack/move/guard commands to groups. | Offers deep tactical control, role identity and bounded defense. | Complexity and version age reduce direct applicability. Dense per-NPC orders are a UI and simulation burden. | Separate detection, alert propagation, role response and leash enforcement; one-point guard is a useful mental model. | Settlement publishes one bounded threat board per tick; defenders claim slots from it rather than each performing a full independent scan. | Shared threat state must be deterministic and dimension/settlement scoped. Test ordering under simultaneous joins/deaths and 2–4 clients. | [Player-Owned NPC wiki](https://github-wiki-see.page/m/P3pp3rF1y/AncientWarfare2/wiki/Player-Owned-NPCs), [official repository](https://github.com/P3pp3rF1y/AncientWarfare2). GPL-3.0; historical reference only. |
| **Tektopia** — historical, Minecraft 1.12.2 | Guard Posts define patrol points. Guards protect the village. A Necromancer encounter uses summoned minions and defensive “Soul Link” layers; rotating the Town Hall marker can suppress the encounter until the player is ready. | Makes defense readable through physical posts and a recognisable special threat. | Historical documentation is community/archival and the mod is no longer a current 1.21 reference. | One captain mechanic can create tactics without a huge class roster; readiness control reduces surprise frustration. | First captain gets one seeded doctrine: Rally (followers resist retarget), Mark (periodic priority target), or Screen (one follower intercept slot). Telegraph it in bossbar subtitle/particles. | Minion/defense layers can cause target churn and visual noise. Test doctrine determinism, restart, color-blind readability and low particles. | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/tektopia), [public wiki](https://sites.google.com/view/tektopia). License not established; observe concepts only. |
| **Millénaire** — rewritten 1.21.1 beta/dev in 2026 | Current public notes say fighters can be hired as guards or escorts; hostile mobs and villages raid; fixes include raid targeting, pathing and UI legibility. Historical official notes describe two-day warning, selected attackers, defenders regrouping, civilians hiding and a Town Hall military panel. | Makes settlements feel culturally alive and distinguishes civilian/defender behavior. | Current build is explicitly beta/development. Pathing, targeting, sleep and legibility appear repeatedly in fixes, indicating high-risk surfaces. | Civilians need a clear raid state; military information belongs at the settlement command surface. | On warning: workers choose shelter, Guards retain orders, Archer retains tower. Hearth shows “Captain + 1 Brute + 3 Skirmishers” and readiness gaps before attack. | Civilian shelter pathing plus combat can multiply navigation load. Test crowded doors, missing shelter, unloaded buildings and server restart. | [Current downloads/changelog](https://www.millenaire.org/downloads), [official license](https://www.millenaire.org/contribute). All rights reserved; reuse of elements is forbidden without authorization. Observation only. |
| **MCA Reborn** — active, 1.21.1/current multi-loader | Focuses on relationships and village management; villagers can follow and set homes. Public changelog records guards not panicking during raids, guards attacking MCA zombie villagers, night scheduling changes and village performance fixes. | Makes villagers legible as people and keeps village state socially meaningful. | Combat is secondary to social simulation. **ANECDOTE:** compatibility questions indicate separate guard/recruit mods may not share population identity. | Settlement identity/alliance must be one authority; noncombatants should not inherit guard behavior. | Captain callouts and aftermath should name affected settlers, but targeting must use settlement UUID/alliance rather than appearance or mod entity type. | Cross-mod population and team identity are risky. Test converted villagers, guests, allied players and two nearby settlements. | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/minecraft-comes-alive-reborn), [repository README](https://github.com/SakuraRK/MCA_Reborn/blob/1.20.1/README.md), [changelog](https://github.com/SakuraRK/MCA_Reborn/blob/1.20.1/changelog.md). GPL-3.0. |
| **MineFortress** — active RTS concept | Official site positions it as an RTS controlled by mouse clicks. Public development posts mention a combat HUD warrior count. | Makes many-unit command and battle state immediately visible. | Public technical detail was too thin to verify targeting or raid semantics; do not infer them. RTS camera/control is outside Hearthstead’s first-person settlement promise. | A count is valuable; RTS-level command density is not required for five defenders/enemies. | Use a small encounter HUD, never a tactical minimap for first raid. | Any HUD must remain one server-owned instance per player and clean up on range/dimension/logout. Test split-screen resolutions and two simultaneous settlements. | [Official site](https://www.minefortress.net/). License and detailed behavior not established in reviewed sources. |

## Cross-source findings for the unresolved first raid

### FACT

1. Explicit **Guard / Patrol / Follow** modes recur in MineColonies, Guard Villagers and companion mods; Ancient Warfare 2 adds home-bound pursuit and point/path orders.
2. Ranged defenders leaving towers or continuing to target nothing appear as public user/known-issue signals in Guard Villagers and Human Companions. These are not prevalence estimates, but they identify concrete regression tests.
3. MineColonies uses warning direction and a raid progress bar; Millénaire has used advance warning and military status; MineFortress exposes combat counts.
4. Large or distributed defense systems repeatedly surface pathing, patrol, target, performance and friendly-identity risks.
5. Tektopia’s special enemy illustrates that a single readable mechanic can differentiate a leader without relying only on health inflation.

### INFERENCE

1. Hearthstead should not let every defender independently select the globally nearest raider. That produces dogpiles, target churn and excess scanning, and it undermines posts/towers.
2. “Follow player” should be an elected temporary responsibility, not a mode that pulls the whole garrison away from authored positions.
3. Captain health and remaining class counts answer different questions. One bossbar can show both, but counts must come from the sealed server roster/terminal ledger, not loaded entities.
4. Role priority must be subordinate to leash, line of sight and urgent local threats. A perfect class matchup is wrong if it makes a Guard abandon a civilian beside them.
5. Replayability should come from small, seeded tactical variation and objectives—not opaque scaling or ever larger bands.

## Original combat architectures

### A — Anchored Roles + Bounded Threat Slots (**preferred**)

Server creates a settlement-scoped threat board from the exact active raid roster. Each living raider exposes limited engagement slots: ordinary Skirmisher 1 melee + 1 ranged; Brute 2 melee + 1 ranged; captain 1 melee + 2 ranged. Each defender claims at most one target using a stable score:

1. immediate attacker of player/settler;
2. threat inside authored Stand/Tower/Patrol leash;
3. class fit (Guard→Brute, Archer→exposed Skirmisher/captain);
4. distance;
5. stable UUID tie-break.

One eligible Stand Guard may be elected as bodyguard while the issuing player remains inside the post leash. Tower Archers never leave the tower envelope to improve aim; they reposition only among authored post cells. Claims expire immediately on terminal target, invalid line of sight beyond a short grace window, or leash violation.

**Why preferred:** smallest new mental model, deterministic anti-dogpile behavior, preserves existing Hearthstead orders, and is straightforward to test under restart and multiplayer.

### B — Sector Defense

Divide the settlement edge into four sectors around the Hearth. Warning direction activates one primary and two adjacent sectors. Patrol points and towers inherit a sector; defenders prefer threats entering theirs, with a capped reserve that follows the player.

**Strength:** exceptionally readable warning and formation.  
**Weakness:** irregular settlements and interior spawns can make cardinal sectors feel arbitrary; requires more map/UI language.

### C — Captain Doctrine Encounter

Keep basic target selection local, but seed one captain doctrine in the persisted raid plan: Rally, Mark or Screen. The doctrine changes follower coordination rather than raw damage/health. Killing the captain removes the doctrine but not the remaining attackers.

**Strength:** replayability and captain identity with only five raiders.  
**Weakness:** higher presentation burden and more state combinations. Best added after Architecture A is proven.

## Preferred first-raid specification

- **Composition:** exactly one named captain, one Brute follower and three Skirmishers; the captain’s physical build remains independently recorded.
- **Guard:** Stand Guard protects its post/player leash and prefers the Brute unless a closer raider is actively hurting a settler/player.
- **Archer:** remains at Tower Post, prefers a target not already at ranged capacity, and changes target when terminal/occluded after bounded grace.
- **Anti-dogpile:** threat slots plus stable target claims. Never assign a defender merely because another defender loaded first.
- **Player protection:** at most one elected bodyguard; deterministic distance then UUID tie-break; immediate release outside leash/dimension/raid.
- **Captain:** visible name and health only when exact authoritative entity is loaded; no substitute health. Remaining class counters survive unload/restart through the participant ledger.
- **HUD:** one bar: `Captain Name — 1 Brute · 3 Skirmishers`; decrement only on definitive terminal facts. Warning phase shows direction/objective without pretending the raid is active.
- **Replayability:** first raid fixed for teaching; later raids vary objective, approach and one doctrine under persisted seed. Do not hide the variables that affect difficulty.
- **Feedback:** authoritative kill credit produces one short XP pulse over the credited Guard/Archer and an inspectable persistent XP change; no fabricated ammunition or kill sharing.

## Test matrix required before implementation approval

1. Guard + Archer acquire different suitable targets when at least two exist.
2. Brute priority does not override an immediate attacker already striking a settler/player.
3. Two melee Guards can occupy two Brute slots; a third chooses another target.
4. Archer keeps Tower Post under clear, occluded and edge-of-range cases; never jumps down solely to pursue.
5. Bodyguard election yields exactly one Guard for two players/two eligible Guards, with stable tie-breaking.
6. Terminal target causes a bounded retarget; no firing at nothing and no stale melee lock.
7. Captain unload hides health but preserves name/counters; follower unload changes no count.
8. Save/restart preserves plan, participant roles, target claims only where safe, terminal counts and bossbar cleanup.
9. Two settlements raided in one dimension never share targets, bars, sleep authority or XP.
10. Friendly/guest/converted entities are never selected; alliance changes fail closed.
11. Performance matrix: 5/15/30 defenders × 5/20/80 hostiles, one and two clients; profile threat-board work, navigation and HUD packets separately.
12. Native playtest: warning readability, tower sightline, captain recognition, audio mix, bodyguard usefulness, combat feel and aftermath comprehension.

## Material limitations

- Tektopia and Ancient Warfare 2 are historical 1.12-era references; their patterns are conceptual evidence, not current compatibility evidence.
- MineFortress public material exposed the RTS proposition and HUD count but not enough detail for reliable target-allocation claims.
- Villager Recruits’ formation detail came from a separate addon and is explicitly not attributed to the base mod.
- Reddit reports are anecdotal and used only to define failure tests; no frequency or causality is claimed.
- License status was not consistently visible for every project. This report recommends original behavior only and grants no reuse permission.

## Claim-to-source ledger

| Source | Publisher / date status | Used for |
|---|---|---|
| [MineColonies Raids](https://minecolonies.com/wiki/systems/raid/) | MineColonies Wiki, accessed 2026-09-03 | Warning, bar, spawn/groups, objectives, scaling, aftermath |
| [MineColonies Guard Tower](https://minecolonies.com/wiki/buildings/guardtower/) | MineColonies Wiki, accessed 2026-09-03 | Patrol/Guard/Follow and settings |
| [MineColonies configuration](https://minecolonies.com/wiki/misc/configfile/) | MineColonies Wiki, accessed 2026-09-03 | Raid/guard configuration and scale |
| [Guard Villagers](https://www.curseforge.com/Minecraft/mc-mods/guard-villagers) | CurseForge project page, accessed 2026-09-03 | Equipment, patrol/follow, support roles, performance note |
| [Human Companions](https://www.curseforge.com/minecraft/mc-mods/human-companions) | CurseForge project page, latest listed 2024 | Roles, commands, XP, known issues |
| [Modern Companions](https://www.curseforge.com/minecraft/mc-mods/modern-companions) | CurseForge project page, current 2026 | 1.21.1 successor controls/radius/progression |
| [Villager Recruits](https://www.curseforge.com/minecraft/mc-mods/recruits) | CurseForge project page, current 2026 | Army/group-management scope |
| [Recruits Addon](https://www.curseforge.com/minecraft/mc-mods/recruits-extras) | CurseForge addon page | Formation/command contrast only |
| [Ancient Warfare 2 repository](https://github.com/P3pp3rF1y/AncientWarfare2) | Project repository | GPL-3.0 and module/status verification |
| [Ancient Warfare 2 Player-Owned NPCs](https://github-wiki-see.page/m/P3pp3rF1y/AncientWarfare2/wiki/Player-Owned-NPCs) | Archived public project wiki, 2020 | Roles, alerts, home leash, patrol and baton orders |
| [Tektopia](https://www.curseforge.com/minecraft/mc-mods/tektopia) | CurseForge project page, historical | Guard Posts and Necromancer encounter |
| [Tektopia Wiki](https://sites.google.com/view/tektopia) | Public project wiki | Historical version/status and special encounter routing |
| [Millénaire downloads](https://www.millenaire.org/downloads) | Official project site, 2026 | 1.21 rewrite status, guards/escorts, raids, fixes |
| [Millénaire license](https://www.millenaire.org/contribute) | Official project site, 2026 | All-rights-reserved clean-room boundary |
| [MCA Reborn CurseForge](https://www.curseforge.com/minecraft/mc-mods/minecraft-comes-alive-reborn) | CurseForge project page, current 2026 | Active versions, village/social scope, GPL-3.0 |
| [MCA Reborn changelog](https://github.com/SakuraRK/MCA_Reborn/blob/1.20.1/changelog.md) | Project repository | Guard raid behavior, schedule and performance fixes |
| [MineFortress](https://www.minefortress.net/) | Official project site | RTS scope only |

## Research stop rationale

Search stopped after two focused waves because every design-critical slot had primary/project-page support or an explicit limitation: commands, post/leash behavior, raid presentation, role identity, progression, known target/path/performance risks, current/historical status, and available license boundaries. Further results were increasingly duplicate, anecdotal, addon-specific, or too weak to change the preferred architecture.
