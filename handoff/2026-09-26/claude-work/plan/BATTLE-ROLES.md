# Bannerhold — Battle roles: Spearman, Longswordsman, Healer, Rune Mage

*26 Sep 2026, battle-roles lane. The design comes first. The implementation status is at the bottom.*

**Owner brief:** four new battlefield roles, each with its own command key. The mage is a "rune mage, but not too broken". A mage should feel strong but never replace guards.

**Rules that shape everything below.**
- **The building decides the trade** (D-011, `Employment`). Every role therefore gets a building whose trade it is.
- **Every item is physical** (INV-3). Weapons come through the equipment-request board, and consumables come from chests.
- **Damage is server-authoritative and ticketed.** Each blow gets one contact ticket and lands exactly once, on its hit tick.

---

## 0. Roster at a glance

| Role | Key | Building (hires) | Code unlock today | Tree node (new design) | Weapon / kit | One-line identity |
|---|---|---|---|---|---|---|
| Knight (Guard) | R | Barracks | first_watch | (existing) | sword + shield | all-rounder, shield bash |
| Archer | G | Watchtower | arm_the_watch | (existing) | bow + arrows | ranged, towers |
| **Spearman** | **J** | **Pike Yard** (new) | shield_doctrine | `spearmen` (Watch, Village) | spear (wood/iron/diamond) | reach, brace vs chargers, pike line |
| **Longswordsman** | **K** | **Sword Hall** (new) | shield_doctrine + anvil-priced hall | `longswords` (Watch, Town) | longsword (iron/diamond), two-handed | cleave 2–3, guard-break vs shields |
| **Healer** | **H** | **Infirmary** (now 2 slots) | first_raid_aftermath | `battle_healer` (Commons, Village) | bandages / herbs | heal over time, revive players, evacuate |
| **Rune Mage** | **N** | **Rune Hall** (new) | hearth_doctrine + Rune Hall | `rune_mage` (Watch, Town); `high_runes` (Castle) | rune charges + rune stones | Firebolt, Ward, Frost Rune; capped 1–2 |

Keys were agreed with the command lane (all rebindable, category Bannerhold):
- Vanilla 1.21.1 does not use J, K, N or H.
- I read the defaults out of the actual jars in `mods-staging` with javap:
  - Xaero's Minimap 26.5.0: Y, U, B, Z and numpad-plus.
  - Xaero's World Map 1.46.0: M, `]` and Right Shift.
  - Jade 15.10.6: numpad 0 to 5.
- Known clash: the optional **B** order strip clashes with Xaero's "new waypoint". The command lane has been told.

**Wire and save ids:**
- Profession ids are appended after TRADER(27): `SPEARMAN(28,"spearman")`, `LONGSWORDSMAN(29,"longswordsman")`, `HEALER(30,"healer")`, `RUNE_MAGE(31,"rune_mage")`.
- BuildingType is persisted by string id: `pike_yard`, `sword_hall`, `rune_hall`.
- Command group wire ids 3 to 6 are owned by the command lane.

`Profession.martial()` covers GUARD, ARCHER, SPEARMAN, LONGSWORDSMAN and RUNE_MAGE: they stand watch, don't panic, and pick targets. HEALER is **not** martial. It flees and never picks fights. The new `Profession.battlefield()` (martial or healer) keeps healers awake and at their post during raids.

---

## 1. Spearman — "the wall with a point"

**Fantasy:** a steady pikeman who holds a line and makes charging a mistake.

| Stat | Value | vs Guard |
|---|---|---|
| Reach | **1.5× vanilla melee reach**: centre distance ≤ 1.5 × (w/2 + 0.828 + w/2) ≈ 2.1 blocks for two 0.6-wide bodies | Guard 1.0× |
| Weapon bonus | wooden +2, iron +4, diamond +5 attack | a little below the matching sword; reach pays for it |
| Health / speed | as Guard (24 HP, 0.30) | same |
| Shield | none (both hands on the shaft) | Guard has shield bash |

**Moveset** (server ticks from wind-up; `RoleMove`):

| Move | Len | Hit | Dmg × | KB | Stagger | Arc ½ | Cooldown | Use |
|---|---|---|---|---|---|---|---|---|
| THRUST | 12 | 5 | 1.0 | 0.3 | 0 | 30° | 8 | default poke, narrow cone |
| DOUBLE_THRUST | 16 | 5 & 11 | 0.7 + 0.7 | 0.2 | 0 | 30° | 12 | ranked opener (Veteran+); two tickets, each lands at most once |
| BRACE_STRIKE | 10 | 3 | **1.75** | 0.8 | **24** | 40° | 30 | a charger entering reach while braced |

**Brace (stance).**
- *When*: in a LINE order (`FieldOrders.bracing`), or on its own when an enemy within 8 blocks is closing fast toward it.
- *What it does*: the spearman plants and faces the threat. While braced, the first **charger** to enter reach eats a BRACE_STRIKE: 1.75× damage, 24-tick stagger, knockback 0.8.
- *Charger rule* (`ChargeRead`): closing speed ≥ **0.15 blocks/tick**, or ≥ 0.08 for known chargers (Brute, wolves, later cavalry).
- *Cost*: BRACE_STRIKE has a 30-tick cooldown, so a brace stops the first of a wave, not all of it.
- *Movement*: braced spearmen don't chase and hold their spot.

**Weak when flanked.**
- A spearman takes **+35% damage** from any hit whose attacker stands more than **100°** off its facing (sides and back).
- A flank hit also **breaks the brace** for 40 ticks.
- A raider skirmisher's flanking path (it circles behind martial targets) is exactly the counter.

**Pike line** (command lane, done):
- A SPEARMEN ground order defaults to **two ranks**, width = ceil(n/2), with the back rank 1 block behind.
- The back rank's 1.5× reach lets it strike past the front rank.
- Standing at the slot = braced.

**Counters.** Raider archers, from range. Skirmisher flanks. Shieldbearers, whom the thrust's narrow arc barely touches.
**Good against.** Brutes, wolves and later cavalry.

**Gear tiers** (tags `#hearthstead:gear_tier/tN`, gear lane):
- Wooden spear T0.
- Iron spear T0. Iron hand weapons are Common by the gear lane's rule.
- Diamond spear T3: Sergeant + Castle knowledge.

**Spear recipes:**
- Wooden: 2 sticks + 1 plank.
- Iron: 2 sticks + 1 iron ingot + 1 plank.
- Diamond: smithing-free, 2 sticks + 1 diamond + 1 iron ingot.

---

## 2. Longswordsman — "the shield-breaker"

**Fantasy:** a two-handed swordsman who opens holes in a shield wall. He's slow, and he's exposed without a shield.

| Stat | Value |
|---|---|
| Weapon | longsword, two-handed, **no offhand**. Iron +6, diamond +7.5 attack |
| Speed | **−8%** movement (`role_speed` modifier) |
| Archer weakness | **+25% damage from projectiles** |
| Health | 24 HP (as Guard) |

**Moveset:**

| Move | Len | Hit | Dmg × | KB | Stagger | Arc ½ | Targets | Cooldown |
|---|---|---|---|---|---|---|---|---|
| CLEAVE | 16 | 8 | 1.1 primary, **0.75 others** | 0.3 | 0 | **70°** | **up to 3** in reach 1.25× | 10 |
| HEAVY_OVERHEAD | 26 | 14 | **2.2** | 0.9 | 12 | 40° | 1 | 16 |
| HALF_SWORD (pommel strike) | 14 | 6 | 0.8 | 0.5 | **16** | 40° | 1 | 40 |

**Cleave:**
- Hits each enemy in the arc **at most once per swing**. One ticket per swing; the hit set is fixed at contact.
- Hostile only: never a bystander, a pet or a player.
- The primary target is always the first.

**Half-sword / pommel strike (guard break):**
- Chosen when the target is **blocking** or is a **shieldbearer**.
- A blocking **player** has the shield disabled for 100 ticks, like an axe.
- A blocking or shieldbearer **raider** is staggered 16 ticks and `guardBrokenUntil` is set (a 60-tick window in which frontal blocks don't apply).
  The shieldbearer variant doesn't exist yet (raid lane). The hook is `RoleCombatHooks.breakGuard`.
- A staggered target is then punished with HEAVY_OVERHEAD, as for Guards.

**Counters:** raider archers (bonus damage), skirmishers (slow to turn), being swarmed while he's in a heavy wind-up.
**Good against:** shieldbearers, clumps of skirmishers.

**Gear tiers:**
- Iron longsword **T1 Mail**: Man-at-Arms rank + Village Charter. A specialist blade, deliberately one step above the iron sword.
- Diamond longsword **T3**.

**Recipes:**
- Iron: 2 iron ingots + 1 iron block + 1 stick + 1 leather (grip).
- Diamond: 2 diamonds + 1 iron ingot + 1 stick + 1 leather.

**Rendering:**
- The player item uses a custom two-handed arm pose (`LongswordClientExtensions`): both arms forward, blade up and to the right.
- On settlers, the item's third-person transform is authored so the grip sits between both hands. The two-handed arm pose on `SettlerModel` is an animation-lane spec (section 6).

---

## 3. Healer — "the early answer to permanent death"

**Fantasy:** the village medic who works behind the line. Bandages, a steady hand, and running when an enemy turns toward them.

| Stat | Value |
|---|---|
| Health | 24; +5% speed |
| Weapon | none. Self-defence is fleeing |
| Supplies | **Bandage** (new item): heals **8 HP over 8 s**. **Healing herbs** (`#hearthstead:healing_herbs`: sweet berries, glow berries): **4 HP over 8 s** |
| Bag | carries up to 8 supplies. Restocks from Infirmary chests, then the warehouse |

**Behaviour, in priority order (`HealerMedicGoal`):**
1. **Flee if targeted.**
   - Trigger: any hostile whose target is the healer, or a hostile within 4 blocks.
   - Response: run 10 blocks away from it, toward the nearest Guard or the Infirmary, for 60 ticks.
2. **Revive downed players** (revive lane's `DownedRescuer` hook).
   - A healer within 32 blocks takes the job, walks to the player, and pings `ReviveService.pingRevive` every tick in reach.
   - Same timer as a player, and damage interrupts it the same way.
   - It won't enter a spot with a hostile within 5 blocks of the downed player. It waits at 8 blocks instead.
3. **Evacuate the badly wounded.**
   - Trigger: a battlefield settler at ≤ 30% health (the `GuardRecoveryPolicy` threshold) while hostiles are within 12 blocks.
   - The healer tags the patient with an **evacuation order to the Infirmary** (`EvacuateToInfirmaryGoal`, priority 1), then walks beside them.
   - On arrival the patient gets a bandage.
4. **Bandage the wounded.**
   - Trigger: any settlement settler (or a player of the settlement) below 70% health within 24 blocks.
   - Pick the lowest health fraction first, and prefer one with no hostile within 4 blocks.
   - Walk to them, **channel for 30 ticks**, consume one supply, and start the heal-over-time.
   - A patient never stacks two heal-over-time effects; a new one refreshes to the larger remaining amount.
5. **Otherwise** stay 6–10 blocks behind the nearest melee soldier, or stay at the order point (H-key ground order).

**Heal-over-time** (`HealLedger`, pure and unit-tested):
- A total amount, paid out in 1-second pulses.
- Pulses stop if the patient dies or unloads.
- Pulses never heal a dead entity, and never go above max health.

**Supply chain.**
- The Infirmary keeps a standing stock target of **16 bandages** (8 per healer slot).
- Couriers fill it through the existing **equipment/request ledger path**, as for any other building stock, so there are no virtual items.
- Until that courier route lands (see status), the healer walks to the warehouse itself and takes up to 8. That is a real chest transfer.

**Death rule tie-in.** Death stays permanent. A healer keeps people from reaching 0: heal-over-time, evacuation, and reviving downed players. The later hospital phase (resurrection / "death is permanent unless you have a hospital") builds on the Infirmary this role staffs.

**Counters:** skirmishers and wolves (they chase exposed targets: the healer gets +0.5 skirmisher exposure), and running out of bandages.

---

## 4. Rune Mage — "strong, but never a replacement for guards"

**Fantasy:** a rune-carver who draws a circle of glowing glyphs in the air and turns a fight at the right moment. They're then helpless for a few seconds.

**The limiters (why it can't be broken):**
1. **Rune charges.** 3 max. +1 every **60 s** (1200 ticks). Each spell costs charges.
2. **Rune Stones** (new item) refill a charge instantly (+1 per stone, consumed from the Rune Hall chest or the mage's bag).
   - Recipe: 1 amethyst shard + 2 lapis + 1 smooth stone → 2 stones.
   - Couriers stock them, the same way as bandages.
   - That makes spell volume an economic choice.
3. **Cooldowns per spell** (below), plus a **shared 1-second global cooldown**.
4. **Visible cast time.**
   - Every spell has a 16–24 tick channel with a rune circle (enchant glyph and end-rod particles) and a cast sound.
   - Damage interrupts the channel: the charge is kept, but the cooldown is spent.
5. **Fragile:**
   - Max health **−6** (18 HP), no armour above leather, **+0.75 skirmisher exposure** (a priority target).
   - Keeps 8–14 blocks from enemies and retreats if one gets within 5.
6. **Cap:**
   - **1 mage per settlement**. **2** once the settlement has 20+ settlers, until the `high_runes` node exists; then high_runes sets it.
   - Refused at hire with an honest reason.

| Spell | Charges | Cooldown | Cast | Range | Effect |
|---|---|---|---|---|---|
| **Firebolt** | 1 | 100 t (5 s) | 20 t | 16 | Bolt to the target point; **AoE r = 2.0**, **6 dmg** at the centre falling to **3.6** at the edge, 2 s burning (40 t). **Enemies only**: settlers, players, pets and bystanders are never hit. |
| **Ward** | 2 | 400 t (20 s) | 16 t | self r = 6 | Up to **4** allies (settlement settlers + players) get a rune shield that **absorbs 6 HP** for **100 t (5 s)**. Implemented as tracked absorption: expiry removes only what's left of the ward, never other absorption. Rune-ring particles on each warded ally. |
| **Frost Rune** | 1 | 240 t (12 s) | 24 t | 16 | Rune on the ground, **r = 3**, lasts **80 t**. Enemies inside get **Slowness II** (refreshed each second for 30 t). No damage. |

**Why this is "not too broken":**
- At full charges and no stones, a mage lands about one Firebolt or Frost every ~60 s after the opening burst of 3.
- The opening burst is 3 firebolts = ~18 centre damage spread over a few targets. That's less than one Guard's combo on one target.
- Ward is the strongest team effect. It costs 2 of 3 charges and absorbs 24 HP across 4 allies once per 20 s.
- Frost Rune makes brace, archers and cleave better. The mage multiplies soldiers; it doesn't replace them.

**Spell choice** (`RuneMageBrain`, pure and unit-tested; the first that fits wins):
1. **Ward**: ≥ 2 allies within 6 blocks, in combat, with the lowest ≤ 60% health, or any ally about to take a brute heavy.
2. **Frost Rune**: ≥ 2 enemies within 3 of a point, or a charger closing on our line.
3. **Firebolt**: ≥ 2 enemies within 2 of a point, or any single enemy while charges = max (use it or lose recharge time).
4. Otherwise hold position.

**Building: Rune Hall** (`rune_hall`):
- Requirements: 1 enchanting table, 4 amethyst blocks, 4 bookshelves, 1 door, 3 lights, 25 floor.
- Worker slots: 2 (the cap applies on top).
- Plan emblem: enchanting table.
- Unlock: Town (`rune_mage`). The Castle node `high_runes` raises the cap to 2, makes stones craft 3, and makes Ward last 6 s.

---

## 5. Shared rules for all four roles

- **Profession entries**: appended ids 28–31, lang `hearthstead.profession.*`, colours:
  - spear: russet 0x8C5A2B
  - longsword: steel-blue 0x4F6D8A
  - healer: linen-white/red cross 0xC8BFA8
  - mage: lapis 0x3A4FA0
- **Job emblems** (`JobEmblemCatalog`):

  | Role | Code unlock | Price |
  |---|---|---|
  | Spearman | shield_doctrine | 4 Coins + 1 iron ingot + 1 leather |
  | Longswordsman | shield_doctrine | 5 Coins + 3 iron ingots + 2 leather |
  | Healer | first_raid_aftermath | 3 Coins + 2 paper + 1 leather |
  | Rune Mage | hearth_doctrine | 6 Coins + 8 lapis + 4 amethyst shards + 1 book |

- **Hiring**: hired into their building (plaque Hire, or emblem auto-hire), like every other trade.
- **Default smart AI with no orders:**
  - Spearman: holds the nearest post and braces toward the threat.
  - Longswordsman: engages the nearest shieldbearer or the densest group.
  - Healer: triage.
  - Mage: stays behind the melee and uses the spell-choice ladder.
  - All of them honour `BannerTeams.active / allowsTarget / anchor` (the command contract).
- **Training**:
  - Spear and longsword train Strength per landed blow (as Guards), so **GuardRank** applies to them and the gear ladder uses Role GUARD.
  - The healer trains Spirit per completed bandage.
  - The mage trains Focus per completed cast.
- **Settler sheet**: each role has a one-line "what they do" (`hearthstead.role.<key>.desc`) and live state: Braced, Charges 2/3, Supplies 5.
- **Sounds** (`sounds.json`, reusing licensed files in `sound-sources/LICENSES.md`):
  - `role.spear_thrust`, `role.longsword_cleave`, `role.rune_cast`, `role.firebolt_impact`, `role.ward_up`, `role.frost_rune`, `role.bandage`.
  - Each event points at an already-licensed sample, pitched differently, until bespoke files arrive.

## 6. Animation clip specs (for the combat animator, Blender)

Ticks are 20 Hz and contact frames must land on the hit tick. Until each clip lands, the fallback is an existing guard clip broadcast via its existing entity event.

| Clip id | Len | Contact | Fallback today | Notes |
|---|---|---|---|---|
| SPEAR_THRUST | 12 | 5 | EV_MELEE (MELEE) | two-hand grip, rear hand at the butt; lunge step on 3–5; recover 6–12 |
| SPEAR_DOUBLE_THRUST | 16 | 5, 11 | EV_GUARD_LIGHT_B | short retract between |
| SPEAR_BRACE_IDLE (loop) | 20 | – | stance (shield idle) | low stance, butt planted by the back foot, tip at chest height of the enemy |
| SPEAR_BRACE_STRIKE | 10 | 3 | EV_GUARD_SHIELD_BASH | shove forward from brace; heavy weight shift |
| LONGSWORD_CLEAVE | 16 | 8 | EV_GUARD_FINISHER | wide horizontal arc right→left, both hands, hips lead |
| LONGSWORD_HEAVY | 26 | 14 | EV_GUARD_HEAVY | high guard (vom Tag) to overhead; long windup telegraph |
| LONGSWORD_HALF_SWORD | 14 | 6 | EV_GUARD_SHIELD_BASH | left hand on the blade, pommel punch forward (mordhau feel optional) |
| LONGSWORD_IDLE (loop) | 40 | – | idle sentry | blade resting on the shoulder |
| RUNE_CAST (Firebolt/Frost) | 20/24 | release 20/24 | none, particles only | left hand traces a rune, right palm pushes on release |
| RUNE_WARD | 16 | 16 | none, particles only | both palms out, circle drawn around the body |
| HEALER_BANDAGE | 30 | 10, 20 (wraps) | WORK_WEAVE (fine work) | kneel, wrap twice |
| HEALER_FLEE | loop | – | panic run | hunched run, arm up |
| HEALER_REVIVE | loop 40 | – | WORK_WEAVE | kneel, press on chest |

## 7. What the other lanes own

| Lane | What it needs from me / gives me |
|---|---|
| Command (a77dcc6059299345d) | Keys J/K/N/H, group wire ids 3–6, membership by `Profession.key()`, spearmen 2-rank line, `bracing` for all melee roles: **done on their side** |
| Combat (a18c40b89e2b1229b) | Owns GuardMeleeGoal/GuardMove. My goals are separate files and don't edit theirs. Ask: raider shieldbearer variant + `breakGuard(ticks)`; skirmisher exposure hook (one-line call into `BattleRoles.skirmisherExposureBonus`) |
| Gear (ac36696d6b537eb95) | `GearGate.roleOf`: SPEARMAN/LONGSWORDSMAN → GUARD. Tier tags for spears/longswords (list in §1/§2) |
| Revive (abf5d8d7a2c8f91e7) | `DownedRescuer` implementation registered by `HealerRescuer` |
| Tech-tree design (a23780ae41c60d284) | Node ids `spearmen`, `longswords`, `battle_healer`, `rune_mage`, `high_runes`; pike_square becomes a spearmen doctrine |
| Animator (a4119f6678f417864) | Clip specs in §6 |

**Naming issue (raised with the lead and the tree designer):** GuardRank's first stripe is called "Spearman". I propose changing its display text to "Man-at-Arms". The key `hearthstead.rank.spearman` stays.

## 8. Implementation status (26 Sep, 02:30)

Evidence levels: **I** Implemented · **C** Compiled · **J** JUnit green · **G** GameTest written but not run (queued in the lead's serialized window) · **S** Seen in game: nothing yet.

| Piece | Where | Evidence |
|---|---|---|
| Professions 28–31, `battlefield()`, `frontline()`, martial set | entity/Profession.java | I C J |
| Attribute profiles (reviewed matrix) | entity/JobAttributeProfile.java + test | I C J |
| Pure rules: moves, reach, brace, flank, cleave, spells, charges, heal ledger, triage, mage cap | entity/combat/role/{RoleMove,RoleCombatRules,RuneSpell,RuneCharges,RuneMageBrain,HealLedger}.java | I C J (4 test classes) |
| Spear / longsword goals (ticketed two-phase, fallback EV_* clips) | RoleMeleeGoal, SpearmanCombatGoal, LongswordCombatGoal | I C G |
| Rune Mage goal (channel, interrupt, Ward/Frost/Firebolt, stones) | RuneMageGoal, RoleWorld | I C G |
| Healer (flee, revive hook, evacuate, bandage, restock, timeouts) | HealerMedicGoal, EvacuateToInfirmaryGoal, HealerRescuer | I C G (bandage only; revive path untested) |
| World state: heal-over-time, wards, frost zones, flank and arrow damage rules, role attributes, drop supplies on death | RoleWorld | I C G |
| Kill-switch `[features] battleRoles` | RoleCombat.enabled() everywhere, RoleHiring | I C G |
| Halls: Pike Yard, Sword Hall, Rune Hall; the Infirmary gets 2 slots; trades, motions, training | BuildingType, Employment, RoleUnlocks, Development | I C |
| Emblems + prices; mage cap at hire | JobEmblemCatalog, JobEmblemItem, RoleHiring | I C |
| Items: 3 spears, 2 longswords, bandage, rune stone, 4 emblems; models (3D in hand, 2D in GUI); textures; recipes; tags; player two-hand pose | RoleItems, item/role, client/role, assets, data | I C (not seen) |
| Outfits (4 recoloured layers), lang, 7 sound events | assets | I |
| Skirmisher priority on mage and healer | RaiderSkirmishGoal (one line, agreed with the combat lane) | I C |

**Stubs / not yet done:**
- Role-specific Blender clips. Specs are with the animator, waiting for coordinator approval. Until then the guard clips stand in: the motion lane widened the SettlerModel gate.
- Two-handed settler grip: an authored clip is planned.
- Courier stocking of bandages and rune stones through the request ledger. It needs a generic "building supply" RequestType from the logistics/courier lane. Until then the healer walks to the Infirmary or Warehouse chests itself, and mages use stones placed in their offhand.
- Translucent 3D ward shell. Today the ward is shown with rune-ring particles, and absorption hearts on players.
- Settler-sheet live role state (charges, supplies). The one-line description keys already exist.
- The new tech-tree nodes. Code gates on today's nodes; see section 5.
