# ATTRIBUTES — audit and rework (attributes lane, 26 Sep)

Owner's order: "All attributes must be useful. Make sure the jobs cover all of them."

This file has two parts:
- **Part 1** is the audit of the code as found on 26 Sep, before this lane changed anything.
- **Part 2** is the design and what was implemented.

---------------------------------------------------------------------------

## Part 1 — Audit (state before the rework)

Sources:
- `entity/Attribute.java`
- `entity/SettlerAttributes.java`
- `entity/JobAttributeProfile.java`
- `entity/SkillLevels.java`
- `entity/JobEffects.java`
- `entity/Trait.java`
- `entity/GuardRank.java`
- `entity/ArcherRank.java`
- `settlement/Employment.java` (`trainedBy`, `keyAttributeOf`)
- `settlement/Mayor.java`
- `building/Production.java` together with `settlement/work/CraftedQuality.java`
- `settlement/work/GoodsQuality.java`
- `settlement/work/FisherProgression.java`
- `settlement/workzone/WorkZoneService.java`
- `settlement/development/WorkerPacks.java`
- `client/screen/SettlerScreen.java` (Skills tab)
- a grep of every `Attribute.X` and `SkillLevels.*` call site.

Scale:
- There are 8 attributes, each 0..99.
- A newcomer is capped at 15 (median 4). The knack attribute has median 7.
- Growth is asymptotic: reaching 50 takes about 1,800 work units.

### 1.1 Attribute × real effect (what the code actually does)

"Generic primary/secondary" means an effect that reaches an attribute only because it is a job's first or second CORE slot in `JobAttributeProfile`:
- `SkillLevels.speedBonus`: the primary attribute weights the trade-level speed bonus from ×0.5 to ×1.0. The bonus is zero at trade level 1.
- `SkillLevels.sideChance`: the secondary attribute adds up to +5% side chance, but only from trade level 5.
- `CraftedQuality`: the primary attribute adds 0.005 quality-mean per point.

| Attribute | Real, attribute-specific effects | Size | Text-only / dead |
|---|---|---|---|
| **Strength** | Guard rank ladder: 20/40/60/80 unlock bash, cleave, leap and rally, plus +0.5 melee damage per rank. The blades (`RoleCombat`, `RoleMeleeGoal`) read the same rank. | big and stepped | — |
| | Lumberer axe contacts per log: 4 below 25, 3 from 25, 2 from 70 (`JobEffects.lumberContacts`) | up to −50% chop time | — |
| | Lumberer carry budget: 8 × trait + Str/10 (`JobEffects.carryItems`) | +0..9 items | — |
| | Generic primary (speed weight, quality) for lumberer, courier, butcher, smelter, smith, mason, miner, miller and armourer | small | — |
| | Courier "Strength +carry" in `SkillLevels` | — | **Not Strength.** It is trade level only; the tooltip itself says "Strength does not currently raise this limit". |
| **Stamina** | Daily Effort pool: 20 + Sta/5 (`Effort.capacity`) | +0..19 units | — |
| | Fatigue pace floor: 0.65 + 0.0015 × Sta (`JobEffects.workPace`) | 65% → 80% at zero energy | — |
| | Carry slowdown relief: up to 25% | small | — |
| | Generic secondary for farmer (+1 seed) and courier (pace, from level 5) | tiny | — |
| | `JobEffects.workingDrain`, `sleepRecovery`, `restRecovery` | — | **Dead**: pure formulas with no caller |
| **Wits** | All attribute growth: ×(1 + Wits/200) | +0..50% growth | — |
| | Trade XP: +1% per point above 15, capped at 25% | +0..25% | — |
| | Scholar research: +1% per point above 15, capped at 25%, from trade level 2 | +0..25% | — |
| | Generic primary for trader (no work goal), scholar and brewer | small | — |
| **Dexterity** | Archer rank: 20 Marksman (spread 6→2, +25% damage), 35 Sharpshooter (Power Shot), 55 Master (triple) | big and stepped | — |
| | Fisher: cast 300→160 ticks and catch-quality tiers at 25/50/75/90 | big | — |
| | Farmer field tier: 1 + Dex/20 (plot size) | stepped | — |
| | Gathered-goods quality gate: `trainedBy` ≥ 20, for farmer | stepped | — |
| | Generic primary (speed weight, craft quality) for 11 crafters, plus builder and archer | small | — |
| **Spirit** | *Only* generic: the herder's primary weights the flock-round pause (level 2+), and the innkeeper's secondary adds a +1 guest-morale side chance (level 5+) | tiny | Healer trains Spirit but heals the same regardless. Morale ignores Spirit. |
| **Perception** | *Only* generic: the hunter's primary weights the hunt pause (level 2+), and the fisher's secondary is the "rod spared" chance (level 5+, max 8%) | tiny | Miner, archer, herder and spearman list it but read nothing. No detection range uses it. |
| **Focus** | Nothing at all. Focus is never a first core, and no side effect reads a second core that is Focus. | **0** | **Dead attribute.** The rune mage trains it; nothing reads it. |
| **Presence** | *Only* generic: the innkeeper's primary weights the meal-preparation wait (level 2+). The trader has no work goal. | tiny | **Effectively dead.** Persuasion, prices and morale ignore it. |

Mayor boons (the knack picks the boon):
- Hard Hands, Careful Work and Open Hearth have formulas with **no caller** (dead).
- Clear Sight, Steady Purpose and Common Voice have **no formula at all**. The lang text itself says "no settlement-wide effect is active yet".
- Only Long Days (energy) and Good Counsel (growth) are live.

### 1.2 Planned / not-live effect statuses

- Every `JobAttributeProfile.Slot` status is **FOUNDATION_ONLY or CALCULATOR_READY**. Not one is LIVE_VERIFIED.
- The unit test even asserts `assertFalse(slot.status().mayDescribeAsLive())` for every slot.

| EffectId | Status | Truth |
|---|---|---|
| PHYSICAL_OUTPUT (Str) | FOUNDATION_ONLY | Partly live for the lumberer and guard only |
| FATIGUE_PACE (Sta) | CALCULATOR_READY | Live (pace floor, effort) |
| LEARNING_RATE (Wits) | FOUNDATION_ONLY | Live |
| PRECISION_EXECUTION (Dex) | FOUNDATION_ONLY | Live for the archer, fisher and farmer only |
| MORALE_RESILIENCE (Spi) | FOUNDATION_ONLY | Dead |
| TARGET_DISCOVERY (Per) | FOUNDATION_ONLY | Dead |
| TASK_CONTINUITY (Foc) | FOUNDATION_ONLY | Dead |
| SOCIAL_INFLUENCE (Pre) | FOUNDATION_ONLY | Dead |
| CARRY_CAPACITY (Str, courier) | CALCULATOR_READY | Dead for the courier (level only) |
| LUMBER_CONTACTS (Str) | CALCULATOR_READY | Live |

### 1.3 Profession × attribute matrix (before)

There are 31 employed professions:
- 26 trades (15 of them are the extended trades behind `[features] extendedTrades`, marked ★);
- the 4 battle roles;
- the Builder.

MAYOR and NONE are not employed.

Legend: P = first core (primary), S = second core (secondary), s = support.

| Profession | Str | Sta | Wit | Dex | Spi | Per | Foc | Pre |
|---|---|---|---|---|---|---|---|---|
| Farmer | | S | | P | | | s | |
| Lumberer | P | S | s | | | | | |
| Guard | P | S | | | | | | s |
| Courier | P | S | | | | | | |
| Baker ★ | | s | | P | | | S | |
| Cook ★ | | s | S | P | | | | |
| Butcher ★ | P | s | | S | | | | |
| Smelter ★ | P | s | | | | | S | |
| Smith ★ | P | | | S | | | s | |
| Sawyer | S | s | | P | | | | |
| Carpenter ★ | | | S | P | | | s | |
| Mason ★ | P | s | | S | | | | |
| Fletcher ★ | | | S | P | | | s | |
| Weaver ★ | | | s | P | | | S | |
| Tanner ★ | S | s | | P | | | | |
| Miner ★ | P | s | | | | S | | |
| Innkeeper | | | s | | S | | | P |
| Scholar | | | P | | s | | S | |
| Miller ★ | P | s | | | | | S | |
| Brewer ★ | | | P | S | | | s | |
| Archer | | | | P | | S | | s |
| Armourer ★ | P | | | S | | | s | |
| Herder ★ | | s | | | P | S | | |
| Fisher | | s | | P | | S | | |
| Hunter | | s | | S | | P | | |
| Trader | | s | P | | | | | S |
| Spearman | P | S | | | | s | | |
| Longswordsman | P | s | | S | | | | |
| Healer | | | s | S | P | | | |
| Rune mage | | | S | | s | | P | |
| Builder | | s | S | P | | | | |

Core counts:

| Attribute | Core (P+S) | Primary |
|---|---|---|
| Dexterity | 19 | 13 |
| Strength | 14 | 10 |
| Wits | 8 | 3 |
| Focus | 6 | 1 |
| Stamina | 5 | 0 |
| Perception | 5 | 1 |
| Spirit | 3 | 2 |
| Presence | **2** | 1 |

### 1.4 Dead attributes and dead effects (before)

**Dead attributes.** No real effect is attached to the attribute itself:
- **Focus**: zero readers.
- **Presence**: only a level-gated weight on the innkeeper's meal wait.
- **Spirit**: the same kind of weight on the herder, plus a level-5 side chance on the innkeeper.
- **Perception**: the same kind of weight on the hunter, plus the level-5 rod-spared chance on the fisher.

A settler with 60 Focus and one with 2 Focus play identically in every job.

**Dead effects:**
- `JobEffects.workingDrain`, `sleepRecovery` and `restRecovery`;
- the Mayor boons Hard Hands, Careful Work, Open Hearth, Clear Sight, Steady Purpose and Common Voice;
- the four FOUNDATION_ONLY effect ids for Spirit, Perception, Focus and Presence;
- the courier CARRY_CAPACITY slot, which is really driven by trade level, not Strength.

**Attributes no job relies on (no live job effect):**
- Focus in every job;
- Presence in every job except the innkeeper (tiny);
- Spirit for the healer (the role it defines);
- Perception for the miner, archer, spearman and herder.

**Display (Skills tab):**
- The sheet already marks PRIMARY (green), SECONDARY (gold) and support.
- It shows attribute tooltips.
- For every Focus, Presence, Perception or Spirit row it prints "No direct bonus for this job yet".

---------------------------------------------------------------------------

## Part 2 — Design (attributes lane, 26 Sep)

Decisions:
- **We keep the eight attributes and their ids.** The set is sound. The problem was wiring, not vocabulary.
- **No save migration is needed.** `SettlerAttributes` DATA_VERSION stays 2, and ordinals and NBT are unchanged.
- The matrix changes in 5 rows only (listed below).

### 2.1 One curve, one helper, one knob

- Every new effect goes through `entity/AttributeEffects`, which is pure and tested with JUnit.
- The effect size is `max × sqrt(v/99) × effectStrength`.
- The square root is deliberate:
  - newcomers sit at 1–15, so a linear curve would make them identical;
  - with the root, 4 already gives 20% of the max, 15 gives 39%, 50 gives 71% and 99 gives 100%.
- Every effect has a hard cap at any strength.
- Work-time cuts from trade level and job fit **add** together and are capped at 30% in total. They never multiply.
- The server config has an `[attributes] effectStrength` setting, from 0.0 to 2.0 (default 1.0).
  - On the GameTest server it is 0 unless a batch opts in, the same rule as `[quality]`, so the other lanes' pinned timings don't move.
  - Rank ladders, effort pool, lumber contacts and learning speed are older structural effects. The knob does not scale them.

### 2.2 Attribute → effects → main jobs

| Attribute | Effects (at 99 / at a typical newcomer of 10) | Main jobs (P = primary, S = secondary) |
|---|---|---|
| **Strength** | Melee damage +20% (+6%). Haul +4 items per trip for couriers and gatherers (+1). Guard/blade rank ladder (existing). Lumber axe contacts (existing). Job fit on heavy trades. | P: lumberer, guard, courier, butcher, smelter, smith, mason, miner, miller, armourer, spearman, longsword. S: sawyer, tanner |
| **Stamina** | Energy drain while working −20% (−6%). Max health +4 HP (+1). Daily effort pool +1 per 5 (existing). Tired pace floor 65→80% (existing). Carry slowdown relief (existing). | S: farmer, lumberer, courier, spearman. Support in 14 more |
| **Wits** | Attribute growth up to +50% (existing). Trade XP up to +25% (existing). Scholar research up to +25% (existing). Job fit. | P: scholar, brewer. S: trader, cook, carpenter, fletcher, rune mage, builder |
| **Dexterity** | Arrow spread −30% (−10%). Melee swing recovery −15% (−5%). Craft quality (primary). Archer rank, fisher cast and catch, field size (existing). Job fit on fine trades. | P: farmer, baker, cook, sawyer, carpenter, fletcher, weaver, tanner, archer, fisher, builder. S: butcher, smith, mason, brewer, armourer, hunter, longsword, healer |
| **Spirit** | Own morale loss −20% (−6%). Healer heal amount +25% (+8%). Innkeeper guest +1 morale side roll (existing). Job fit (herder flock rounds, healer bandage). | P: herder, healer. S: innkeeper. Support: scholar, rune mage |
| **Perception** | Hunter prey search radius and archer shot range +25% (+8%). Extra find +10% chance of one more item per harvest, block, shear or catch (+3%). Job fit (hunter). | P: hunter. S: miner, archer, herder, fisher. Support: farmer |
| **Focus** | Craft batch time −12% at any bench or the butcher's table (−4%). Archer draw and rune mage cast −20% (−6%). Scholar research +10% of a session (+3%). | P: rune mage. S: baker, smelter, weaver, scholar, miller. Support: smith, carpenter, fletcher, brewer, armourer, archer |
| **Presence** | Persuasion +10 points, from the settlement's best speaker (mayor, trader, innkeeper or guard) (+3). Trader payout +10% (+3%). Innkeeper meal and ale morale +25% (+8%). Job fit (innkeeper meal preparation). | P: trader, innkeeper. S: guard |

**Job fit.** In every job with timed work (farmer, lumberer, miner, herder, hunter, innkeeper, crafters, builder, healer bandage), the job's primary attribute cuts work time by up to 10% and the secondary by up to 5%. This sits on top of the trade-level bonus, and the total cut is capped at 30%. It applies from level 1. This is what makes "A is the better smith" true on the first day.

### 2.3 Matrix changes

| Job | Before | After | Why |
|---|---|---|---|
| Farmer | Dex, Sta, s Focus | Dex, Sta, s **Perception** | Focus has no field effect; Perception = extra crop find |
| Guard | Str, Sta, s Presence | Str, **Presence**, s Stamina | The guard speaks in raid parley. Presence needs 3 core jobs |
| Trader | Wits, Presence | **Presence**, Wits | Presence drives the payout |
| Archer | Dex, Per, s Presence | Dex, Per, s **Focus** | Steady draw (Focus) |
| Spearman | Str, Sta, s Perception | Str, Sta, s **Dexterity** | Swing tempo; no Perception effect in melee |

Core-slot count after the change:

| Attribute | Core slots |
|---|---|
| Strength | 14 |
| Stamina | 4 |
| Wits | 8 |
| Dexterity | 19 |
| Spirit | 3 |
| Perception | 5 |
| Focus | 6 |
| Presence | 3 |

Every attribute is core in at least 3 jobs, and every job uses 2 or 3 attributes (only the courier uses 2).

---------------------------------------------------------------------------

## Part 3 — Implementation (landed 26 Sep ~11:30)

### 3.1 New files

| File | Role |
|---|---|
| `entity/AttributeEffects.java` | The pure core, with no world access: the curve, the 16 scaled effects with their max and hard cap, job fit, the 30% work cap, and display formatting |
| `entity/AttributeRuntime.java` | Server glue. Each hook in the game code is one line calling this class, and every method is identity at strength 0 |
| `entity/AttributeConfig.java` | The `[attributes] effectStrength` setting (0–2, default 1). It is 0 on the GameTest server unless `testOverride` is set |
| `entity/AttributeFit.java` | Pure "suits which jobs" lookup for the hire card. It never compares two people |
| `gametest/AttributeGameTests.java` | Batches `attributes_effects` (6 tests) and `attributes_neutral` (1) |
| `test/.../AttributeEffectsTest.java` | JUnit: every attribute has ≥2 live effects, the four formerly dead attributes have ≥2 scaled effects each, bounds at any strength, no save migration, formatting |
| `test/.../AttributeFitTest.java` | JUnit for `AttributeFit` |

### 3.2 Hooks (one line each, in shared files)

| Where | Effect |
|---|---|
| `SkillLevels.shortenWait` / `shortenLooped` (settler overloads) | Job fit plus Focus craft time, added to the level bonus, capped at 30%. This covers every crafter, lumberer, farmer, miner, herder, hunter and innkeeper |
| `BuilderWorkGoal.placeTicks` | Job fit (builder placement) |
| `HealerMedicGoal` | Job fit on bandage channel time; Spirit heal amount |
| `SettlerEntity.addMorale` | Spirit reduces morale loss |
| `SettlerEntity.tickNeeds` | Stamina reduces working drain; `applyMaxHealth` |
| `GuardMeleeGoal` | Strength melee modifier; Dexterity cadence |
| `RoleCombat.blowDamage`, `RoleMeleeGoal` | Strength damage and Dexterity cadence for the blades |
| `ArcherAttackGoal` | Perception shot range, Focus draw time, Dexterity spread |
| `RuneMageGoal` | Focus cast time |
| `HunterWorkGoal.huntBounds(anchor, hunter)` | Perception. Used for both finding prey and revalidating the harvest |
| `Farmer` / `Miner` / `Herder` / `FisherWorkGoal` | Perception extra find |
| `CourierSatchel.apply`, `WorkerPacks.naturalCapacity` | Strength haul bonus |
| `TavernServingEntity` | Presence ale and meal morale |
| `ScholarWorkGoal` | Focus study bonus |
| `ConversationService.chanceFor` | Presence of the best speaker (mayor, trader, innkeeper or guard). Clamped to 5..95 |
| `TraderSaleService` plus new `GoldCoinTrades.takeFromOwnedPurse` | Presence payout, drawn FROM the purse (economy lane's request). Exact-once inside the receipt |
| `GuardPatrolGoal`, `TraderSaleService` | These now train Presence: the guard's rounds and a struck bargain. Before this, Presence had no training path for these jobs |
| `HearthsteadServerConfig` | Registers `[attributes]` |

`JobAttributeProfile` changes:
- a new `EffectId` set (each id names a live effect);
- 5 rows changed deliberately;
- `scaledEffect()` added;
- every slot is `LIVE_VERIFIED`;
- `JobProfileUi.effectKey` is now name-based.

UI:
- **SettlerScreen, Skills tab only.** Each attribute tooltip starts with "Affects: …" plus the live value of each scaled effect. The "No direct bonus / no effect in this job" texts are gone. The job-focus band shows each core slot's real effect and value. Primary (green) and secondary (gold) framing is unchanged.
- **HearthScreen hire card.** The aptitude line appends "· suits Smith, Mason".

Lang:
- `hearthstead.attribute.affects` and `hearthstead.attribute.effect.*` (28 keys);
- `hearthstead.job_profile.effect.*` (18 new);
- `hearthstead.recruit.card.suits`;
- the `trained_by` text for Focus, Perception and Presence, which used to say "not active yet".

### 3.3 Evidence

| Level | What |
|---|---|
| E1 JUnit | `AttributeEffectsTest`, `AttributeFitTest`, `JobAttributeProfileTest` (+2 new tests), `SkillLevels*`, `SettlerAttributesTest`, `SettlerScreen*`, conversation: all green. The last targeted run was 58 tests and the client/lang run was 74 |
| E2 GameTest, runtime hook on live entities (same methods the goals call) | Smith 90/90 vs 2/2: 400-tick batch → ≤320 vs 400−, pause shorter; builder placement. Spearman: damage +>5% at Str 19, recovery shorter; spread −>25%, draw 20→16. Hunter box +25%; extra find ~10% vs 0; haul +4 vs 0; heal, hospitality, study; best-speaker +10 persuasion; trader payout ~+10% vs exact |
| E3 GameTest end-to-end through the entity tick | Max health +4.0 through the once-a-second needs tick; morale loss 16 vs 20 through the real `addMorale` |
| E0 neutral | `attributes_neutral`: on the GameTest server, strength defaults to 0, so no job fit, no melee or haul bonus, and equal health. This is why other lanes' pinned timings do not move |

Runs:
- `attributes_` batch: 7/7.
- `attributes_` + `economy_tuned_merchant` + `trade_`: 38/38.
- Full-suite run at 11:18, which excluded `scenario_hall_rooms` because it crashes the server with an NPE in `ScenarioHallRoomGameTests`: 16 failures, all in other lanes' in-flight, untracked batches (patrol squads, scenario, alarm/archer, goods_quality smith, dog, handbook, builder switch). None of them run through code that is live at strength 0.

Not yet done:
- A two-workshop end-to-end race, where the same sawmill is timed with a high and a low sawyer.
- An in-game visual check of the Skills-tab tooltip. It compiles and the render-cache test passes, but nobody has seen it in game.

### 3.4 Before → after (dead attributes)

| Attribute | Before: real effects | After: real effects |
|---|---|---|
| Strength | 2 (rank, lumber) | 4 + job fit (melee dmg, haul, rank, lumber) |
| Stamina | 3 | 5 (+ energy drain, max health) |
| Wits | 3 | 3 + job fit (unchanged, it was already good) |
| Dexterity | 4 | 6 + job fit (+ spread, swing tempo) |
| Spirit | ~0 (level-gated weight) | 2 + job fit + innkeeper side (morale loss, heal) |
| Perception | ~0 | 2 + job fit (range, extra find) |
| **Focus** | **0** | 3 (craft time, draw/cast, study) |
| **Presence** | ~0 | 3 + job fit (persuasion, trade payout, hospitality) |

Other results:
- Dead `JobAttributeProfile` statuses went from 31 of 31 not live to 0.
- Jobs where an attribute slot had "no effect in this job" went from most jobs to none.

---------------------------------------------------------------------------

## Part 4 — Mayor boons and unread traits made real (26 Sep, afternoon)

### 4.1 Mayor boons

The knack picks the boon, exactly as before. The six dead boons now run through `AttributeEffects.Boon` and `AttributeRuntime.boon`:
- **Size:** `max × (0.5 + 0.5·sqrt(v/99)) × effectStrength`, where v is the mayor's own value in the boon's attribute. Any mayor gives half the maximum; a mayor strong in that attribute gives all of it.
- **When it applies:** only while `Mayor.activeBoon` returns it, so only after the settling-in period and never during mourning.
- **Unchanged:** Long Days and Good Counsel keep their old fixed values.

| Boon (attribute) | Effect | Combined cap |
|---|---|---|
| Hard Hands (Str) | every trade's timed work −5..10% (all `SkillLevels` work waits and loops, builder, healer) | inside the 30% work cap |
| Careful Work (Dex) | +5..10% chance of one extra item per harvest, mined block, shear or catch | 20% with Perception |
| Open Hearth (Spi) | everyone's morale loss −10..20% | 35% with Spirit |
| Clear Sight (Per) | hunter search box and archer shot range +7.5..15% | +40% with Perception and WATCHFUL |
| Steady Purpose (Foc) | batch time at every bench −4..8% | inside the 30% work cap |
| Common Voice (Pre) | +4..8 persuasion points in conversations | 15 points with the best speaker |

Other changes:
- `Mayor.workSpeed`, `extraYieldChance` and `moraleDecay` used to hold fixed numbers that nothing read. They now report the real values.
- The lang `.desc` text states the real ranges. "no settlement-wide effect is active yet" is gone.

### 4.2 Traits

Traits are effects of their own, not attributes. They are on when `effectStrength > 0`, and off on the GameTest server by default.

| Field | Wired as | Size |
|---|---|---|
| `Trait.speed` | a transient `hearthstead:trait_speed` movement modifier, set in the needs tick | STRONG_BACK −8%, FEARFUL +15%, clamped 0.85..1.15 |
| `Trait.sight` | adds to `AttributeRuntime.range` (hunter box, archer range) and to the civilian threat scan | WATCHFUL +30%, capped +40% combined, scan ~15.6 blocks |
| `FEARFUL` | `SettlerPanicGoal.danger()` scan radius 12 → 16 blocks: flees earlier | — |
| `WATCHFUL` | in `SettlerPanicGoal.start()`, a real sighting raises the settlement alarm at once (`SettlementManager.raiseAlert`), instead of only after reaching a Guard. Only when no alert is running, so retries do not re-announce it | — |

The bug hunter approved the `SettlerPanicGoal` change: two spots only; CRLF line endings, the BH-31 changes and `shelterSpeed` are untouched.

Still unread and **not** in this scope:
- `Trait.work` (WELCOMING, BIG_EATER);
- the flags EARLY_RISER, NIGHT_OWL, GREEN_FINGERS and WELCOMING.

Their description lines still promise behaviour that does not exist. The trait chips on the sheet show only the wired multipliers. See the report for the decision this needs.

### 4.3 Evidence for Part 4 (landed 26 Sep ~12:12)

**Build.** I built in a private project copy (`build-agent-attributes/proj`), synced from the shared tree before every build, with my staged files merged in 3-way (`rebase.sh`), then deployed. The shared tree compiled green after the deploy.

**JUnit, 116 green:**
- `mayorBoonsAreHalfToFullAndCapped`: every boon gives half its max at mayor value 0 and its full max at 99, stays inside its cap at any strength, and its name and attribute match `Mayor.Boon`.
- `traitSpeedSightAndPanicAreBoundedAndOffAtZero`: the trait table stays inside the wired bounds.

**GameTest batch `attributes_boons`, 9/9.** Each boon was measured on an ordinary worker (all attributes 0, neutral trait) in three states:
- no mayor → 0;
- mayor appointed but still settling in → 0 (`activeBoon` is null);
- settled mayor → real.

| Boon | Measured on |
|---|---|
| Hard Hands | `SkillLevels.shortenWait` |
| Careful Work | `extraFind`, 2000 rolls |
| Open Hearth | `moraleDelta` |
| Clear Sight | `range` |
| Steady Purpose | `shortenLooped` |
| Common Voice | `persuasionPoints`; the mayor's own speaker points count at once, the boon adds on top only after settling |

Trait tests in the same batch:
- **Speed:** the STRONG_BACK −0.08 and FEARFUL +0.15 modifiers appear on the real `MOVEMENT_SPEED` through the needs tick; a settler with no speed trait gets no modifier.
- **Sight and fear:** WATCHFUL range 20 → 26; panic scan radius is 12 normally, 16 for FEARFUL, ~15.6 for WATCHFUL.
- **Live alarm:** a WATCHFUL civilian who sees a zombie raises the settlement alarm through `SettlerPanicGoal`, with no Guard in the world.

**Neutral runs.**
- At strength 0 (the GameTest default), the trait hooks never call `traits()`. An early lazy trait roll shifted the entity random and broke `alarm_bell` `alarmSendsCivilianToShelterAndStopsWork`; the guard fixes that.
- Related batches (`attributes_`, `alarm_bell`, `bughunt_panic`, `mayor*`, `trade_*`, `guard_salute_alarm`, `patrol_alarm`, `archer*`, `hunter*`, `economy_tuned_merchant`) show the same results with and without my changes. Only `alarmwakesrealsleeperwhofetchesbowandlooses` fails, and it fails without my changes too.
- One combined run did fail `alarm_bell` 2/4. The rerun passed, and `alarm_bell` together with `attributes_` passed 20/20. This looks like an order- and RNG-sensitive test, flagged to the captain.

---------------------------------------------------------------------------

## Part 5 — Every trait wired (26 Sep, afternoon)

Owner rule: every attribute and trait must be useful.

All of these are on when `[attributes] effectStrength > 0` and faded in by it. On the GameTest server they are off unless a batch opts in. When off, they never read `traits()`, so the entity random stays as it was.

| Trait | What it does now | Hook |
|---|---|---|
| BIG_EATER | works 15% faster while fed (hunger ≥ 50); hunger ×1.40 as before | `AttributeRuntime.traitWorkCut` → `SkillLevels.workCut` / `shortenWork` |
| WELCOMING | works 8% slower at a bench ("the work waits while they talk") but 5% faster as innkeeper or trader | same |
| WELCOMING, flag | if any loaded member is WELCOMING: a recruitment guest waits ×1.5 as long, tavern visits last ×1.5 (900 instead of 600 ticks), and +3 persuasion with visitors (not raiders, brutes or rival lords). The check is cached for 100 ticks | `SettlementManager` patience (both sites), `TavernSeatEntity`, `ConversationService.chanceFor` |
| EARLY_RISER | lives an hour ahead of the village clock: rises, works, eats and sleeps an hour earlier; works 5% faster in the morning ([0, 6000)) | `SettlerEntity.dayPhase()` → `AttributeRuntime.dayPhaseOf` |
| NIGHT_OWL | an hour behind: sleeps late, works late; 5% faster in the late hours ([9000, 23000)); working energy drain −25% at night | `dayPhase()`, `workingDrain` → `nightDrain` |
| GREEN_FINGERS | a Farmer working a field gives one random column's crop an extra vanilla random tick every 60 ticks (light and farmland rules still apply), which is roughly a quarter faster growth while they work; also +5% extra-harvest chance (inside the 20% cap) | `FarmerWorkGoal.tick` → `tendField`; `extraFind` |

Caps:
- A trait may slow timed work by at most 10% and speed it by at most 20%.
- Combined with level, job fit and boons, the total is bounded to [−10%, +30%].

Descriptions and sheet:
- The description lines were rewritten to state exactly this, with numbers.
- The trait chips on the Skills tab now also show the live speed, sight, work and carry multipliers.

### 5.1 Review fixes (bug hunter, 26 Sep)

- `GoToPostGoal:73` now uses `settler.dayPhase()`. Before, a shifted settler was walked to their post on the village clock and then idled.
- Martial roles and the healer (`Profession.battlefield()`) ignore the clock shift. The watch is a duty rota: a lark on day watch and an owl on night watch would otherwise leave up to 2000 ticks unguarded at the handover. A NIGHT_OWL guard still tires 25% less at night.
- WELCOMING gives no persuasion bonus with the speaker kinds raider, brute, rival_lord or captain (the conversation lane confirmed these are all the hostile kinds).

### 5.2 Evidence (landed ~12:45)

- **JUnit `traitWorkAndClockAreBoundedAndOffAtZero`:** signed work cut −10%..+30%; loops lengthen by at most 10%; clock shift is 0 at strength 0; every `Trait.work` value is inside the bounds.
- **JUnit, broad run** (entity, screen, conversation, settlement): 762 tests. The only failure is `TavernTableMathTest`, which fails without my changes too (anim/tavern lane).
- **GameTest batch `attributes_traits`, 5/5.** Each test compares a settler with the trait against an identical settler without it:
  - BIG_EATER fed: 100 → 85 ticks; hungry: 100; plain: 100.
  - WELCOMING smith: 108; WELCOMING innkeeper: 95; the builder/healer path gives the same result.
  - EARLY_RISER works at 06:30 while the village rises. NIGHT_OWL still rises at 07:30 and works at 17:30. A NIGHT_OWL guard keeps the rota clock but tires less at night. The morning and late-hours bonuses apply only in their windows.
  - GREEN_FINGERS: 400 extra random ticks on a 3×3 wheat field made it grow (the field went mature), and the farmer finds ~5% extra; a plain farmer finds 0.
  - WELCOMING member present: guest patience 60,000 → 90,000, tavern visit 600 → 900, +3 persuasion with a traveller, 0 with a raider. After the trait is removed, everything is back to plain.
- **GameTest `attributes_neutral` `traitsAreOffByDefault`:** passes.
- **All `attributes_` batches: 22/22.**

**Neutrality.** On a frozen shared snapshot, the batch groups gave identical results with and without my changes:

| Batch group | Result |
|---|---|
| alarm/panic/guard_salute/patrol_alarm | 7/7 |
| trade_/farmer | 64/64 |
| mayor | 7/7 |
| hunter/merchant/recruit/talk/conversation/tavern | same result both ways; one failure, `mealfinisheswhileseatedandvisiteventuallyends`, fails without my changes too |

**GameTest server crash.** It crashes with a ConcurrentModificationException in `GameTestIsolation.resetBetweenBatches:169`, with or without my changes. I sent the cause and the fix (iterate a copy) to the captain, and patched it only in my private copy to get the results above.
