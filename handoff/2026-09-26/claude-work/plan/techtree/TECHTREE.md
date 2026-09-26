# Bannerhold tech tree: expanded design

Status: design only, 26 Sep 2026. No code was changed.
**v2 (same day):** the owner found the Commons & Household branch "a bit tame". It has been reworked around "a living village people love to live in", and every other branch got a pass to add a visible or behavioural hook to its plain "+X%" nodes (see section 5, v2 changes).
**v3 (same day):**
- Captain's Voice (the owner's per-role command keys) replaces the Commander's Horn.
- Building levels and home tiers are recognised from a checklist (plaque scan, "next level: add X"), never from a blueprint.
- New nodes for four lanes being built now: Builder, battle roles, gear tiers and warehouse levels. Their ids and costs match those agents exactly (see section 5.0a).
Data file: `plan/techtree/techtree.json`, the source for the interactive preview page.
Audited copy: `Verk-arbeid-/hearthstead-neoforge`. That covers `settlement/development/*`, `settlement/research/*`, `JobEmblemCatalog`, `BuildingType`, `SkillLevels`, `DevelopmentScreen` and the Journey ids. It builds on `ui-proposal/tech-tree-rework.md`, `ui-proposal/survival-progression-audit.md`, `plan/COMBAT-DEFENSE-DESIGN.md`, `plan/ROADMAP.md` and the owner vision (25–26 Sep).

**Counts: 84 nodes.**

| Branch | Direction | Nodes | existing | existing_changed | new |
|---|---|---|---|---|---|
| Crown (rank spine) | centre | 5 | 0 | 2 | 3 |
| Watch & Defense | N | 23 | 2 | 6 | 15 |
| Logistics | E | 18 | 0 | 5 | 13 |
| Craft & Production | W | 17 | 1 | 10 | 6 |
| Commons & Household | S | 21 | 1 | 8 | 12 |
| **Total** | | **84** | **4** | **31** | **49** |

By type: 44 unlocks, 20 bonuses, 10 pick-one choices and 10 capstones (4 rank seals and 6 branch capstones, including the Knights/Pikes and Cathedral/Revels pick-one pairs).

The "existing" count dropped from 12 to 4 because most plain-stat nodes kept their number and gained a visible hook. They are now `existing_changed`, and the hook can ship after the number.

---

## 1. Audit: what the tree is today

The tree has two catalogues. `DevelopmentNode` holds 19 nodes and draws the tree layout. `PostRaidUpgrade` holds 12 paid bonuses drawn beside the jobs they improve. Save data and packets use the string id and the wire id, never the ordinal. Every price is paid all-or-nothing from the Banner treasury view (buyer inventory, Banner, Warehouse chests): **Coins first, then the declared goods.** `DevelopmentNode.costs()` now prepends a Coin line and charges the materials; the old override that charged Coins only is gone.

### 1.1 Development nodes (`DevelopmentNode`)

| id (wire) | UI name | Screen position | Cost charged today | Prerequisite and quest gate | Grants | Works in game? |
|---|---|---|---|---|---|---|
| `settlement_charter` (0) | Settlement Charter | centre | free, auto | — | nothing (root seal) | yes, but empty |
| `shelter` (1) | Banner Raised | centre | free, auto | charter | nothing | yes, but empty |
| `timber_rights` (2) | Lumber Camp | W ring 1 | 1 C, 8 logs, 8 cobble | shelter; foundation ready | Lumber Camp, Lumberer emblem, Work Scepter recipe | yes |
| `stores_and_roads` (4) | Stores & Roads | E ring 1 | 2 C, 8 logs, 2 leather | timber; 1 Lumberer log stored | Warehouse, Courier emblem (no roads exist) | yes |
| `cultivated_ground` (3) | Cultivated Ground | W ring 1 | 2 C, 8 seeds, 4 logs | stores; 1 Courier delivery | Farmhouse, Farmer | yes |
| `shore_provisions` (10) | Shore Provisions | W ring 1 | 2 C, 4 logs, 2 string | stores; 1 delivery | Fishery, Fisher | yes |
| `home` (9) | Home | S ring 1 | 1 C, 12 logs, 8 cobble | shelter; foundation ready | House, Lodging | yes |
| `hospitality` (5) | Hospitality & Trade | S ring 1 | 4 C, 4 bread, 2 leather | home; 3 housed | Tavern, Trading Post, Innkeeper, Trader | yes |
| `first_watch` (6) | First Watch | N ring 1 | 4 C, 4 iron, 8 logs | stores; 1 delivery | Barracks, Guard | yes |
| `arm_the_watch` (8) | Arm the Watch | N ring 1 | free (quest only) | first_watch; 1 weapon delivered to a Guard | Watchtower, Archer, Tower Post orders | yes |
| `first_raid_aftermath` (7) | First Raid Aftermath | raid ring | free, but you must press Learn | arm_the_watch; first raid complete | nothing itself (opens the outer ring) | yes, but empty |
| `shield_doctrine` (20) | Shield Doctrine | N ring 2 | 6 C, 8 iron, 4 leather | aftermath; 40 Guard XP | +25% guard combat XP | yes |
| `guild_doctrine` (21) | Guild Doctrine | W ring 2 | 6 C, 24 logs, 8 iron | aftermath; 64 goods moved + 3 deliveries | Sawmill, Sawyer | yes |
| `hearth_doctrine` (22) | Commons Doctrine | S ring 2 | 6 C, 4 books, 8 bread | aftermath; 24 000 ticks all housed + 5 equipment requests | Architect's Study, Scholar (research) | yes |
| `fortification` (40) | Fortification | N ring 3 | 6 C, 4 iron blocks | shield | Armoury, Armourer | **no**: `implemented=false` (FUTURE) |
| `border_wardens` (41) | Border Wardens | N ring 3 | 6 C, 8 leather, 16 arrows | shield | Hunters' Lodge, Hunter | yes |
| `land_and_harvest` (42) | Land and Harvest | W ring 3 | 6 C, 8 hay blocks | guild | Mill, Pasture, Bakery, Butcher + 4 jobs | **no** (FUTURE) |
| `craft_and_industry` (43) | Craft and Industry | W ring 3 | 6 C, 3 iron blocks, 16 bricks | guild | Mine, Carpenter, Mason, Smelter, Smithy, Tannery, Weaver + 7 jobs | **no** (FUTURE) |
| `hall_and_learning` (44) | Hall and Learning | S ring 3 | 6 C, 6 bookshelves, 16 bread | hearth | Kitchen, Library, Dining Hall, Brewery, Well, School, Infirmary, Market + Cook, Brewer | **no** (FUTURE) |

Coin prices come from `coinCost()`: Timber and Home cost 1, Stores, Cultivated and Shore cost 2, Hospitality and First Watch cost 4, and everything else costs 6. The doctrine ACTIVE/DORMANT "primary tradition" is saved but no gameplay code reads it.

### 1.2 Bonus upgrades (`PostRaidUpgrade`)

| id (wire) | Direction | Hangs off | Cost today | Gate | Effect (class) | Works? |
|---|---|---|---|---|---|---|
| `courier_satchel` (0) | Logistics | aftermath | 4 C only | — | Courier carry 8 → 12 (`CourierSatchel`) | yes |
| `hand_cart` (1) | Logistics | aftermath + satchel | 8 C only | — | Courier +8 more (12 → 20), no visual cart | yes (invisible) |
| `worker_packs` (2) | Logistics | aftermath | 6 C only | — | Lumberer and Farmer carry ×1.5 (`WorkerPacks`) | yes |
| `guard_arms_iron` (3) | Watch | aftermath | 8 C only | — | Veteran and above dressed one tier earlier (`GuardArms`) | yes |
| `archer_longbow_drill` (4) | Watch | aftermath | 6 C only | — | Range +2, draw −4 ticks (`ArcherDrill`) | yes |
| `warm_hearth` (5) | Hearth | home | 3 C, 16 cobble, 8 logs | 2 housed | Night hunger −10% | yes |
| `sturdy_beds` (6) | Hearth | hospitality + warm_hearth | 4 C, 3 wool, 12 planks | 3 housed | +2 morale with a bed | yes |
| `feather_quilts` (7) | Hearth | aftermath + sturdy_beds | 6 C, 8 feathers, 4 wool | — | Sleep energy +20% | yes |
| `sharpened_axes` (8) | Craft | timber | 2 C, 2 iron, 8 cobble | 16 logs stored | Felling −10% | yes |
| `fishers_nets` (9) | Craft | shore | 3 C, 8 string, 4 logs | 4 deliveries | Cast −10% | yes |
| `stout_straps` (10) | Logistics | stores | 2 C, 4 leather, 4 string | 6 deliveries | Courier +2 | yes |
| `guard_drill` (11) | Watch | first_watch | 3 C, 8 logs, 2 leather | 1 guard weapon delivery | Guard combat XP +15% | yes |

**In progress now (logistics build agent, wire 12–15):** `leather_pack`, `frame_pack`, `paved_roads` and `swift_couriers`. It is also turning `hand_cart` into a real visible cart and changing sack and cart carry to a percentage formula: 8 × (100 + sack% + cart%) / 100, plus 2 for Straps, plus the Trade skill (0–3). This design uses those ids and costs unchanged.

### 1.3 Related systems

- **Job emblems** (`JobEmblemCatalog`, Mayor shop). There are 11 released emblems: Lumberer (1 C, 2 flint), Farmer, Fisher, Courier, Trader and Innkeeper (2 C each plus goods), Guard and Archer (3 C plus goods), and Hunter, Sawyer and Scholar (4 C or 2 C plus goods). Each emblem needs its unlocking node. Other professions such as Miller, Baker, Smith, Tanner, Armourer and Cook fail closed because they have no catalogue entry.
- **Research** (`ResearchProject`, Scholar at the Architect's Study). There are 6 projects, each costing 4 paper plus one domain sample and taking 2–4 sessions. Three can be started: Seasoned Timber (sawmill −15%), Crop Rotation (crops +15%) and Guard Drill (Strength training +10%). Three are blocked until their trade ships: Better Yeast, Blast Bellows and Tanning Acid. A Library gives a research discount through `Costs`.
- **Buildings** (`BuildingType`). There are 33 types, and every one has a Build Plan recipe. A House holds up to 4 beds and a Lodging 8. Population equals beds, with a minimum of 4 founders (`Settlement.capacity`). A Warehouse can hold up to 64 containers.
- **Trade skills** (`SkillLevels`). Levels 1–10 take about 44 in-game days to reach 10. The primary attribute gives up to +18% work speed and the secondary gives up to 8% side chances. A Courier gets +1 carry per 3 levels (max +3). There is no Coin gate.
- **Already-built combat** that the tree can build on: the full guard moveset (light, heavy, 3-hit combo, shield bash), the `AlarmBell` (16-block radius), tower archers with ammunition logistics, `RaiderBreachGoal` (raiders chop through blocking blocks), `TavernBard` (+5 evening morale), `TavernGuestPayment`, `GoodsQuality` (fish) and three Blessings (Warden's Oath, Hearthward, Thorned Roads).
- **Journey** chapters: foundation → first labor → logistics → food security → growth → first watch → first raid. They follow the Hamlet ring exactly, so the redesign keeps the Hamlet ring intact.

---

## 2. Design rationale

1. **Keep the hub, and make the rings mean rank.** The Banner stays at the centre, with Watch to the north, Logistics east, Commons south and Craft west, which matches the current `DevelopmentScreen`. Each ring is now a settlement rank: Hamlet, Village, Town, Castle. The rank seals (the **Crown spine**) spiral outward clockwise and sit on the ring boundaries, so the tree always shows "what rank am I, and what does the next one ask".
2. **The first raid is the Village Charter.** The existing `first_raid_aftermath` becomes the auto-stamped Village seal. It is free, it cannot be missed, and it opens ring 2 of every branch whether the raid was won or lost. That removes today's "press Learn on a seal that gives nothing".
3. **Every node does something you can see.** The four FUTURE placeholders become real nodes by splitting them into loops that already exist in code: the buildings, professions and Production recipes are there, and only emblem entries and release tests are missing. No card promises anything the game can't do without saying so. Each node lists its `depends_on` honestly.
4. **Bonuses people want, and each one changes what you see or do.** Every node carries a behaviour or something visible, not only a number:
   - bigger sacks you can see on the Courier (Satchel → Leather Pack → Frame Pack);
   - real carts (Hand Cart → Coster's Cart → Mule Cart) and faster roads;
   - Captain's Voice, with one command key per battle role, and archers who loose volleys on command;
   - new battle roles: Spearmen, Longswordsmen, Rune Mage and Healers;
   - guards sparring in the morning, lumberers whetting axes and replanting, a fisher's net buoy;
   - walls, crossbows or longbows, knights or pikes.
   Where a node has a number, it is concrete and modest: +10–15% for small bonuses, +25–50% for big ones, and ×2 only on capstones.
5. **Commons is the heart that powers the rest** (v2). Its fantasy is "a living village people love to live in", told through four lines:
   - **Homes:** Hut → Cottage → Townhouse → Manor. Better homes draw more skilled travellers and allow bigger families.
   - **The Tavern:** Bard's Stage → Rooms to Let (fame and visitor events) → Hall of Revels.
   - **Hearth & feasts:** the dusk gathering, the long table with cravings, the War Feast before a raid (a real combat buff), and Harvest and Midsummer festivals.
   - **Care & memory:** the Infirmary (moved here), the Hall of Heroes with fallen guards and nemesis trophies, and Families & School, where children inherit a trade.
   Its pick-one, **Wayside Chapel vs Alehouse**, decides the capstone path: Cathedral or Hall of Revels.
6. **Real choices.** There are 7 pick-one pairs, each with a different play style rather than a "right" answer:
   - Longbows vs Crossbows: range and speed, or armour-piercing punch.
   - Palisade vs Ditch & Stakes: a wall with a weak gate, or a slowing ditch with no gate.
   - Porters vs Runners: heavy loads or fast feet.
   - Kilns vs Deep Mine: faster iron or more ore.
   - Knights vs Pike Square: offence or an immovable defence.
   - Wayside Chapel vs Alehouse (Village): Blessings and remembrance, or ale and more frequent feasts.
   - Cathedral vs Hall of Revels (Castle): the capstone of whichever Commons path you took.
7. **No taxes.** Coins come from trade, the tavern, guests, festivals, raid victories and merchants. Merchant purses grow with rank (12 → 16 → 20). Coins also come from:
   - bard tips from travellers;
   - rent from guest rooms;
   - ale sold at the Alehouse;
   - visitors spending at festivals.
8. **Costs match survival.** Hamlet nodes cost 1–4 C plus day-one goods (logs, cobble, leather, string, a little iron). Village nodes cost 4–10 C plus iron, leather, wool and planks. Town nodes cost 10–16 C plus processed goods (stone bricks, Timber Beams, Cured Hide, ale) and a day of study. Castle nodes cost 24–40 C plus iron and gold blocks and 2 days of study. The goods pull players into the production chains: tanner → Cured Hide → packs, and sawyer → Timber Beams → carts.
9. **Levels come from what you build, not from blueprints** (owner rule, v3). Home tiers (Cottage → Townhouse → Manor), Warehouse levels (Storeroom → Royal Storehouse) and the Tavern's Inn level are recognised by the plaque checklist, which shows "next level: add X". A node unlocks the recognition of a level and its perks. The player builds by hand, or orders a Builder to add the missing pieces (Upgrade Orders). Blueprints are optional starters only.
10. **Save compatibility.** Every existing id and wire id is kept. Placeholder ids are reused for what they now unlock (`fortification` = Armoury, `land_and_harvest` = Mill/Bakery/Pasture, `craft_and_industry` = Mine/Smelter/Smithy, `hall_and_learning` = Families & School). `shelter` stays as a hidden id.

---

## 3. Progression pacing (efficient co-op player, ×2 day length: 1 in-game day = 40 real min)

Coin income, measured today: the merchant gives 8 C, then 12 C every 20 min (about 36 C/h). The first victory gives 8 C and later victories 4 C. The Trader adds more once the Trading Post runs. Another agent is loosening early Coins. After the Village rank, Tavern and Market nodes add 5–15 C/h. The budget below assumes about 45 C/h in the Village and about 60 C/h in the Town.

| Tier | Coins to buy the whole ring | Typical buys |
|---|---|---|
| Hamlet | 48 C (19 nodes + the Banner seal) | nearly all of them, over hours 1–2 |
| Village | 218 C (33 nodes + the free Village seal) | about 18–22 of them by hour 5; the rest by hour 8–9 |
| Town | 234 C (17 nodes) + charter 20 | hours 7–12 |
| Castle | 308 C (10 nodes) + charter 40 | hours 12–18 |
| Kingdom | 80 C | the long game (phase 6 systems) |

**Hour 1 (Hamlet, before the raid).** Banner Raised, Lumber Camp, Houses & Lodging, Warehouse & Courier, Fields & Farmer, Sharpened Axes, Hearth Fires, Barracks & Guard, Stout Straps, Paved Roads, Builder's Hut, Defense Plans, and Captain's Voice once built. The player has a working log → warehouse → farm loop, a Builder filling checklist gaps, and a guard they can already command with the Knights key. At dusk they see the settlers gather round the Banner fire.

**Hour 2 (end of Hamlet, first raid at about 100–120 min).** Watchtower & Archers, Tavern & Trade, Cottages (the first family signs go up), Fishery + Fisher's Nets (the net buoy), Guard Drill (morning sparring), and barricades raised by the whole village after the warning (Raise the Barricades). The first raid stamps the **Village Charter**.

**Hour 5 (Village).** The big "wants" ring:
- Sacks: Courier Satchel → Leather Pack.
- A real Hand Cart and Swift Couriers.
- Shield Wall (the Knights' shield line), Spearmen, plus Longbows or Crossbows.
- Masonry for the Builder, the Storehouse (Warehouse level 3), the Infirmary and Battle Healers.
- Watchfires & Great Bell for earlier warnings.
- Armoury & Armourer and Iron Arms Drill.
- Sawmill and Mill, Bakery & Pasture.
- Commons: the **War Feast** before the second raid, the Bard's Stage (dancing and tips), Townhouses (more settlers), the Kitchen & Long Table, and the first big fantasy choice: **Wayside Chapel or Alehouse**.

Raids so far: 2 (the first at about 2 h, the next 3–4 in-game days = 2–2.7 real hours later).

**Hour 10 (Town).** The Town Charter (15 settlers, 3 wins) is bought at about hour 7–8. Then come:
- Frame Packs, and a Coster's Cart on the roads.
- The Porters or Runners guild.
- Stone Walls & Arrow Slits, Veteran Techniques (4-hit combo, shield-breaking heavies, tower shields), Longswordsmen and the Rune Mage.
- Great Storehouse (Warehouse level 4).
- Carpenter, Mason & Weaver, Masters & Apprentices, and Kilns or Deep Mine.
- Rooms to Let (a famous inn with visitor events), Harvest & Midsummer Festivals, Manors, the Hall of Heroes, and Families & School (the first children).

The player works toward the Castle Charter (25 settlers, 6 wins, a stone ring; about hour 14–16) and eyes the capstones: Master Armoury (diamond gear), High Runes, Knights or Pikes, Mule Carts, Outposts & Caravans, Guild Halls, and Cathedral or Hall of Revels.

Note on raid cadence: at ×2 days, one in-game day is 40 real min, so "every 3–4 in-game days" means a raid every 2–2.7 real hours. The rank gates are sized to that pace:

| Rank | Raids won | Reached at about |
|---|---|---|
| Town | 3 | hour 7–8 |
| Castle | 6 | hour 14–16 |
| Kingdom | 12 | hour 30 or later |

If the cadence or the day length changes, change these gates with it. They are the main pacing knob.

---

## 4. Every node

### Crown

| Tier | id | Name | Type | Status | Size | Cost | Gate / time | Requires | Offers |
|---|---|---|---|---|---|---|---|---|---|
| 1 Hamlet | `settlement_charter` | Banner Raised | unlock | existing_changed | S | 0 C | Raise the Banner | - | Found your hamlet: the Mayor, 4 founder places and ring 1 of every branch. |
| 2 Village | `first_raid_aftermath` | Village Charter | capstone | existing_changed | S | 0 C | First raid resolved (auto) | arm_the_watch | Survive your first raid and the hamlet becomes a Village: ring 2 opens in every branch. |
| 3 Town | `town_charter` | Town Charter | capstone | new | M | 20 C, 32 stone bricks, 8 iron ingot, 4 book | 15 settlers, 3 raids won, and 2 Village nodes in each of 3 branches; 1 day | first_raid_aftermath | Become a Town: ring 3 opens, merchants bring fuller purses and raids grow tougher. |
| 4 Castle | `castle_charter` | Castle Charter | capstone | new | L | 40 C, 64 stone bricks, 4 iron block, 8 gold ingot | 25 settlers, 6 raids won, stone walls around the Banner; 2 days | town_charter, stone_walls | Become a Castle: ring 4 opens and you raise a Keep, the last stand of your people. |
| 5 Kingdom | `kingdom_crown` | Crown of the Realm | capstone | new | L | 80 C, 4 gold block, 4 diamond, 16 wool bolt | 40 settlers, 12 raids won, 1 outpost, an alliance or a vassal; 3 days | castle_charter | Crown yourself: Kingdom rank, two Blessings per victory, and a seat among the rival kingdoms. |

### Watch & Defense

| Tier | id | Name | Type | Status | Size | Cost | Gate / time | Requires | Offers |
|---|---|---|---|---|---|---|---|---|---|
| 1 Hamlet | `first_watch` | Barracks & Guard | unlock | existing | - | 4 C, 4 iron ingot, 8 any logs | 1 Courier delivery | stores_and_roads | Build a Barracks and hire Guards who patrol, answer the alarm and fight beside you. |
| 1 Hamlet | `guard_drill` | Guard Drill | bonus | existing_changed | S | 3 C, 8 any logs, 2 leather | 1 weapon delivered to a Guard | first_watch | Guards spar every morning at the Barracks and earn +15% combat XP. |
| 1 Hamlet | `arm_the_watch` | Watchtower & Archers | unlock | existing | - | 0 C | 1 Courier-delivered weapon to a Guard (a proof milestone, no Coins) | first_watch | Build a Watchtower, hire Archers, and post Guards on the tower. |
| 1 Hamlet | `commanders_horn` | Captain's Voice | unlock | new | M | 3 C, 4 copper ingot, 2 leather | - | first_watch | Command your soldiers in the fight: one key per role (R knights, G archers, and keys for spearmen, longswords, mages, healers), ordered by what you look at. |
| 1 Hamlet | `barricades` | Raise the Barricades | unlock | new | M | 2 C, 16 any logs | - | first_watch, builders_hut | When a raid is spotted, sound the order and the whole village turns out to throw up barricades on the spots you marked. |
| 1 Hamlet | `defense_plans` | Defense Plans | unlock | new | M | 3 C, 16 any logs, 16 stick | - | first_watch | Draw a defense line on the ground and your Builder raises palisade walls, gates and a timber watchtower along it. |
| 2 Village | `shield_doctrine` | Shield Wall | bonus | existing_changed | M | 6 C, 8 iron ingot, 4 leather | 40 Guard combat XP earned (existing gate) | first_raid_aftermath, guard_drill | Guards learn the shield wall: the Hold the Line order and +25% combat XP. |
| 2 Village | `spearmen` | Spearmen | unlock | new | M | 12 C, 8 iron ingot, 16 stick | - | first_raid_aftermath, first_watch | A new battle role: Spearmen fight from the second rank with long reach and brace against charges. |
| 2 Village | `guard_arms_iron` | Iron Arms Drill | bonus | existing_changed | S | 8 C, 6 iron ingot | - | first_raid_aftermath | Veterans wear iron chest and legs, Sergeants full iron: one armour tier earlier. |
| 2 Village | `archer_longbow_drill` | Longbows | choice | existing_changed | M | 6 C, 8 string, 12 any logs | - | first_raid_aftermath, arm_the_watch (excl. crossbows) | Archers shoot farther and faster, and loose volleys on your command. |
| 2 Village | `crossbows` | Crossbows | choice | new | M | 6 C, 6 iron ingot, 8 string, 2 tripwire hook | - | first_raid_aftermath, arm_the_watch (excl. archer_longbow_drill) | Archers switch to crossbows: slower, but every bolt hits hard and punches through shields. |
| 2 Village | `fortification` | Armoury & Armourer | unlock | existing_changed | M | 8 C, 12 iron ingot, 8 leather | - | first_raid_aftermath, first_watch | An Armourer forges armour and shields for your guards from village iron and leather. |
| 2 Village | `watchfires` | Watchfires & Great Bell | bonus | new | S | 5 C, 16 any logs, 8 charcoal, 4 copper ingot | - | first_raid_aftermath, arm_the_watch | Earlier raid warnings, and an alarm bell heard across the whole village. |
| 2 Village | `palisade` | Manned Palisade | choice | new | L | 8 C, 64 any logs, 4 iron ingot | - | first_raid_aftermath, defense_plans (excl. earthworks) | Your palisade becomes a fighting wall: guards shut the gates on the alarm, archers walk the wall, and the timber holds twice as long. |
| 2 Village | `earthworks` | Ditch & Stakes | choice | new | M | 6 C, 24 any logs, 16 cobblestone | - | first_raid_aftermath, defense_plans (excl. palisade) | Settlers dig a staked ditch. Raiders crossing it slow to a crawl, and there is no gate to lose. |
| 3 Town | `stone_walls` | Stone Walls & Arrow Slits | unlock | new | L | 16 C, 96 stone bricks, 12 iron ingot | Own Manned Palisade or Ditch & Stakes; 1 day | town_charter, masonry | Stone walls three times as strong, and arrow slits that make your towers deadly. |
| 3 Town | `veteran_techniques` | Veteran Techniques | bonus | existing_changed | M | 12 C, 8 iron ingot, 4 book | 1 day | town_charter, shield_doctrine | Guards master their moveset: harder bashes, a four-strike combo and shield-breaking heavies. |
| 3 Town | `longswords` | Longswordsmen | unlock | new | L | 18 C, 2 iron block, 6 leather | 1 day | town_charter, spearmen | A new battle role: two-handed longswordsmen who break shields and cut through crowds. |
| 3 Town | `rune_mage` | Rune Mage | unlock | new | L | 20 C, 16 lapis lazuli, 8 amethyst shard, 4 book | 1 day | town_charter, hearth_doctrine | A new battle role: a Rune Mage who spends rune charges on spells that turn a fight. |
| 4 Castle | `master_armoury` | Master Armoury | unlock | new | M | 30 C, 4 diamond, 2 iron block, 8 leather | 2 days | castle_charter, fortification | Your Armourer and Smith learn to work diamond and netherite: Gear Tier 3 for Sergeants, Master Archers and level-8 workers, and with the Crown, Tier 4 for Captains. |
| 4 Castle | `high_runes` | High Runes | bonus | new | M | 30 C, 4 amethyst block, 1 diamond, 8 book | 2 days | castle_charter, rune_mage | Your mages master the high runes: a second Rune Mage, more stones from every carving, and longer wards. |
| 4 Castle | `knights` | Order of Knights | capstone | new | L | 30 C, 4 iron block, 4 saddle, 16 hay block | 2 days | castle_charter, veteran_techniques (excl. pike_square) | Mounted knights: your best guards (the R-key Knights) take to horseback, charge through raider lines and run down archers. |
| 4 Castle | `pike_square` | Pike Square | capstone | new | L | 30 C, 4 iron block, 32 any logs, 16 iron ingot | 2 days | castle_charter, spearmen (excl. knights) | Spearmen doctrine: your Spearmen form a braced square, and nothing gets through, not brutes, not wolves. |

### Logistics

| Tier | id | Name | Type | Status | Size | Cost | Gate / time | Requires | Offers |
|---|---|---|---|---|---|---|---|---|---|
| 1 Hamlet | `stores_and_roads` | Warehouse & Courier | unlock | existing_changed | S | 2 C, 8 any logs, 2 leather | 1 log stored by a Lumberer | timber_rights | Build a Warehouse and hire Couriers who carry goods to one shared store. |
| 1 Hamlet | `stout_straps` | Stout Straps | bonus | existing_changed | M | 2 C, 4 leather, 4 string | 6 Courier deliveries | stores_and_roads | Couriers carry 2 more items and pick up two different jobs on one trip. |
| 1 Hamlet | `paved_roads` | Paved Roads | bonus | new | S | 4 C, 32 gravel, 16 cobblestone | 8 Courier deliveries | stores_and_roads | Every settler walks 15% faster on roads: dirt path, gravel, cobble, stone and bricks. |
| 2 Village | `courier_satchel` | Courier Satchel | bonus | existing_changed | S | 4 C | - | stout_straps, first_raid_aftermath | Sack tier 1: Couriers carry 50% more (8 -> 12 per trip), and you can see the satchel on their hip. |
| 2 Village | `leather_pack` | Leather Pack | bonus | new | S | 6 C, 8 leather, 4 any wool, 6 string | - | courier_satchel, stores_and_roads | Sack tier 2: Couriers carry double (8 -> 16 per trip). |
| 2 Village | `frame_pack` | Frame Pack | bonus | new | S | 10 C, 12 leather, 8 string, 8 stick, 2 iron ingot | - | leather_pack | Sack tier 3: Couriers carry 2.5x (8 -> 20 per trip). |
| 2 Village | `hand_cart` | Hand Cart | bonus | existing_changed | M | 8 C, 12 any planks, 4 any logs, 2 iron ingot | - | courier_satchel | Couriers push a real, visible hand cart: +200% carry. |
| 2 Village | `worker_packs` | Worker Packs | bonus | existing_changed | S | 6 C | - | stores_and_roads, first_raid_aftermath | Lumberers and Farmers carry at the settlement's sack tier (at least +50%). |
| 2 Village | `swift_couriers` | Swift Couriers | bonus | new | S | 4 C, 6 leather, 4 string | 12 Courier deliveries | stout_straps, first_raid_aftermath | Couriers walk 10% faster everywhere and jog when a delivery is urgent. |
| 2 Village | `warehouse_racks` | Storehouse (Warehouse L3) | unlock | new | S | 5 C, 24 any planks, 4 iron ingot | - | stores_and_roads, first_raid_aftermath | The plaque now recognises Warehouse level 3, the Storehouse: 64 managed containers, with labelled shelves your Couriers can read. |
| 2 Village | `courier_ledger` | Courier's Ledger | unlock | new | M | 5 C, 8 paper, 1 book | - | stores_and_roads, first_raid_aftermath | Set each building's delivery priority and minimum stock; urgent needs are served first. |
| 3 Town | `costers_cart` | Coster's Cart | bonus | new | M | 14 C, 16 timber beam, 8 iron ingot | 1 day | hand_cart, paved_roads, town_charter | Two-wheeled cart, cart tier 2: +300% carry. |
| 3 Town | `porters_guild` | Porters' Guild | choice | new | S | 12 C, 8 cured hide, 16 bread | 1 day | town_charter, courier_ledger (excl. runners_guild) | Heavy loads: +50 points in the carry formula, but Couriers walk 10% slower. |
| 3 Town | `runners_guild` | Runners' Guild | choice | new | S | 12 C, 8 leather, 16 sugar | 1 day | town_charter, courier_ledger (excl. porters_guild) | Swift feet: Couriers walk 15% faster and take their next job the instant one is free. |
| 3 Town | `great_storehouse` | Great Storehouse (Warehouse L4) | unlock | new | S | 12 C, 32 stone bricks, 8 iron ingot | 24 Courier deliveries | warehouse_racks, town_charter | The plaque now recognises the Great Storehouse: 128 managed containers for a town's worth of goods. |
| 4 Castle | `mule_cart` | Mule Carts | bonus | new | L | 24 C, 16 hay block, 4 lead, 8 iron ingot | 2 days | costers_cart, castle_charter | Mule-drawn carts, cart tier 3: +400% carry, and 20% faster on roads. |
| 4 Castle | `royal_storehouse` | Royal Storehouse (Warehouse L5) | unlock | new | S | 24 C, 64 stone bricks, 16 iron ingot, 4 gold ingot | 64 Courier deliveries | great_storehouse, castle_charter | The plaque now recognises the Royal Storehouse: 256 managed containers, the granary of a castle. |
| 4 Castle | `caravan_routes` | Outposts & Caravans | capstone | new | L | 30 C, 64 stone bricks, 8 gold ingot, 1 white banner | 2 days | castle_charter, costers_cart | Found mine, farm and watch outposts, linked to home by scheduled caravans on the King's Road. |

### Craft & Production

| Tier | id | Name | Type | Status | Size | Cost | Gate / time | Requires | Offers |
|---|---|---|---|---|---|---|---|---|---|
| 1 Hamlet | `timber_rights` | Lumber Camp | unlock | existing | - | 1 C, 8 any logs, 8 cobblestone | Foundation ready | settlement_charter | Build a Lumber Camp, hire a Lumberer, and get the Work Scepter. |
| 1 Hamlet | `builders_hut` | Builder's Hut | unlock | new | M | 3 C, 16 any logs, 8 cobblestone | - | timber_rights | Hire a Builder: they fill the gaps on any building's checklist for you, build starter blueprints, and make barricades. |
| 1 Hamlet | `sharpened_axes` | Sharpened Axes | bonus | existing_changed | S | 2 C, 2 iron ingot, 8 cobblestone | 16 logs stored by Lumberers | timber_rights | Lumberers whet their axes on a grindstone and fell each tree 10% faster, and they replant as they go. |
| 1 Hamlet | `cultivated_ground` | Fields & Farmer | unlock | existing_changed | S | 2 C, 8 wheat seeds, 4 any logs | 1 Courier delivery | stores_and_roads | Build a Farmhouse and hire a Farmer who sows, reaps and stores wheat. |
| 1 Hamlet | `shore_provisions` | Fishery | unlock | existing_changed | S | 2 C, 4 any logs, 2 string | 1 Courier delivery | stores_and_roads | Build a Fishery; a Fisher lands food and rare catches for trade. |
| 1 Hamlet | `fishers_nets` | Fisher's Nets | bonus | existing_changed | S | 3 C, 8 string, 4 any logs | 4 Courier deliveries | shore_provisions | Casts finish 10% sooner, and the Fisher sets a net with a floating buoy that fills while he rests. |
| 2 Village | `guild_doctrine` | Sawmill & Sawyer | unlock | existing_changed | S | 6 C, 24 any logs, 8 iron ingot | 64 goods moved + 3 Courier deliveries (existing gate) | first_raid_aftermath, timber_rights | A Sawyer turns logs into planks and Timber Beams. |
| 2 Village | `land_and_harvest` | Mill, Bakery & Pasture | unlock | existing_changed | M | 6 C, 8 hay block, 8 oak fence | - | first_raid_aftermath, cultivated_ground | Millers, Bakers, Herders and Butchers: bread in bulk, wool, leather and meat. |
| 2 Village | `border_wardens` | Hunters' Lodge | unlock | existing_changed | S | 6 C, 8 leather, 16 arrow | - | first_raid_aftermath | A Hunter ranges beyond the walls and brings back meat, feathers and hides. |
| 2 Village | `tannery` | Tannery & Tanner | unlock | existing_changed | M | 8 C, 16 any logs, 8 leather, 1 cauldron | Own the Hunters' Lodge or Mill, Bakery & Pasture (a hide source) | first_raid_aftermath | A Tanner cures hides into leather and Cured Hide, and you can sew yourself a Pedlar's Sack. |
| 2 Village | `craft_and_industry` | Mine, Smelter & Smithy | unlock | existing_changed | M | 8 C, 8 iron ingot, 24 cobblestone, 16 any logs | - | first_raid_aftermath, timber_rights | Mine ore, smelt iron and forge tools, and workers holding iron tools work faster. |
| 2 Village | `masonry` | Masonry | unlock | new | M | 6 C, 32 cobblestone, 16 stone bricks | - | first_raid_aftermath, defense_plans | Your Builder learns stone: stone wall segments, a stone gatehouse, a stone watchtower, and stone floors in Upgrade Orders. |
| 3 Town | `carpenter_mason` | Carpenter, Mason & Weaver | unlock | existing_changed | M | 12 C, 32 any planks, 32 cobblestone, 1 loom | 1 day | town_charter, guild_doctrine | Workshops for furniture, stone bricks and Wool Bolts, the stuff towns are built from. |
| 3 Town | `charcoal_kilns` | Charcoal Kilns | choice | new | M | 12 C, 48 cobblestone, 16 any logs | 1 day | town_charter, craft_and_industry (excl. deep_mine) | The Smelter burns its own charcoal from logs: smelting 25% faster, with no coal needed. |
| 3 Town | `deep_mine` | Deep Mine | choice | new | M | 12 C, 16 timber beam, 8 iron ingot | 1 day | town_charter, craft_and_industry (excl. charcoal_kilns) | The Miner digs two levels deeper: +1 ore on 15% of ore blocks, and a chance of gold. |
| 3 Town | `masters_apprentices` | Masters & Apprentices | unlock | new | M | 12 C, 4 book, 4 iron ingot | 1 day | town_charter, carpenter_mason | Skilled workers become Masters and take an Apprentice who follows them and learns twice as fast. |
| 4 Castle | `guild_halls` | Guild Halls | capstone | new | L | 30 C, 16 timber beam, 16 wool bolt, 8 book | 2 days | castle_charter, masters_apprentices | Each trade raises its own guild hall with a banner: more hands in every workshop, Fine goods, and a forge that mends the guard's gear. |

### Commons & Household

| Tier | id | Name | Type | Status | Size | Cost | Gate / time | Requires | Offers |
|---|---|---|---|---|---|---|---|---|---|
| 1 Hamlet | `home` | Houses & Lodging | unlock | existing_changed | S | 1 C, 12 any logs, 8 cobblestone | Foundation ready | settlement_charter | Build Houses and a Lodging; every bed beyond the 4 founders lets one more settler live here. |
| 1 Hamlet | `warm_hearth` | Hearth Fires | bonus | existing_changed | M | 3 C, 16 cobblestone, 8 any logs | 2 settlers housed | home | At dusk the village gathers round the fire to eat and talk, and warm homes keep hunger down at night. |
| 1 Hamlet | `hospitality` | Tavern & Trade | unlock | existing | - | 4 C, 4 bread, 2 leather | 3 settlers housed | home | Build a Tavern and a Trading Post; hire an Innkeeper and a Trader. |
| 1 Hamlet | `sturdy_beds` | Cottages | unlock | existing_changed | M | 4 C, 3 any wool, 12 any planks | 3 settlers housed | hospitality, warm_hearth | Home tier 1: the plaque now recognises Cottages. Give a House a bed for everyone, a chest and a flower pot or carpet, and its family makes it theirs. |
| 2 Village | `feather_quilts` | Feather Quilts | bonus | existing_changed | S | 6 C, 8 feather, 4 any wool | - | sturdy_beds, first_raid_aftermath | Settlers who sleep a full night under quilts wake up Well Rested and work faster all morning. |
| 2 Village | `hearth_doctrine` | Scholar's Study & Library | unlock | existing_changed | M | 6 C, 4 book, 8 bread | Everyone housed for 1 day + 5 equipment requests served (existing gate) | first_raid_aftermath | A Scholar researches upgrades you choose, and a Library makes every project cheaper. |
| 2 Village | `bards_songbook` | Bard's Stage | unlock | new | M | 5 C, 2 note block, 16 any planks, 8 bread | - | hospitality, first_raid_aftermath | Raise a stage in the Tavern: the bard plays to a crowd, settlers dance, and travellers throw Coins. |
| 2 Village | `kitchen_and_hall` | Kitchen & Long Table | unlock | existing_changed | M | 8 C, 1 smoker, 16 any planks | - | hospitality, first_raid_aftermath | A Cook feeds the village at one long table, and settlers start to crave favourite foods. |
| 2 Village | `war_feast` | War Feast | unlock | new | M | 6 C, 16 any planks, 8 bread, 4 ale | - | hospitality, first_raid_aftermath | When a raid is spotted, call a feast at the Banner: the village eats together and faces the night unafraid. |
| 2 Village | `two_storey_houses` | Townhouses | unlock | new | M | 6 C, 32 any planks, 8 glass | - | sturdy_beds, first_raid_aftermath | Home tier 2: the plaque now recognises Townhouses. Build a second floor and furnish it: 6 beds, couples move in together, and skilled travellers take notice. |
| 2 Village | `wayside_shrine` | Wayside Chapel | choice | new | M | 6 C, 16 cobblestone, 2 gold ingot, 4 candle | - | first_raid_aftermath (excl. alehouse) | A small chapel: 4 Blessing cards after every victory, a reroll, and a candle for every soul lost. |
| 2 Village | `alehouse` | Alehouse | choice | new | M | 6 C, 4 barrel, 24 wheat | - | hospitality, first_raid_aftermath (excl. wayside_shrine) | A proper alehouse: settlers stop for a pint after work, and feasts and festivals come round far more often. |
| 2 Village | `infirmary` | Infirmary | unlock | existing_changed | L | 8 C, 8 any wool, 1 cauldron, 8 bread | - | first_raid_aftermath, home | Downed settlers are carried to the Infirmary instead of dying, and rest there until they are whole. |
| 2 Village | `battle_healer` | Battle Healers | unlock | new | M | 8 C, 6 paper, 8 string | - | infirmary | Hire Healers at the Infirmary: they bandage the wounded, follow your soldiers into the fight, and are the first answer to permanent death. |
| 3 Town | `manors` | Manors | unlock | new | M | 14 C, 32 stone bricks, 8 glass, 4 bookshelf | 1 day | town_charter, two_storey_houses | Home tier 3: the plaque now recognises Manors. Furnish a grand house and it draws master newcomers, raises bigger families, and gives the village an Elder. |
| 3 Town | `great_tavern` | Rooms to Let | unlock | new | M | 14 C, 32 any planks, 6 any wool, 16 ale | 1 day | town_charter, bards_songbook | Guest rooms turn the Tavern into a famous inn: travellers stay, pay, and bring stories and surprises. |
| 3 Town | `harvest_feast` | Harvest & Midsummer Festivals | unlock | new | M | 14 C, 48 bread, 16 ale, 8 any wool | 1 day | town_charter, kitchen_and_hall | Hold village festivals: bunting and lanterns go up, the bard plays, crowds come to spend, and the whole village works faster the next day. |
| 3 Town | `hall_of_heroes` | Hall of Heroes | unlock | new | M | 14 C, 32 stone bricks, 4 gold ingot, 4 white banner | 1 day | town_charter, infirmary | Honour the fallen: memorial plaques, nemesis trophies on the wall, and recruits who arrive braver for it. |
| 3 Town | `hall_and_learning` | Families & School | unlock | existing_changed | L | 14 C, 4 bookshelf, 8 any wool, 16 bread | 1 day | town_charter, two_storey_houses | Couples marry, children are born and grow up, and at School they learn a parent's trade with a head start. |
| 4 Castle | `cathedral` | Cathedral | capstone | new | L | 40 C, 96 stone bricks, 16 gold ingot, 8 book | 2 days | castle_charter, wayside_shrine (excl. hall_of_revels) | A cathedral rises over the town: two Blessings after every victory, and curses bite half as hard. |
| 4 Castle | `hall_of_revels` | Hall of Revels | capstone | new | L | 40 C, 64 any planks, 16 wool bolt, 32 ale | 2 days | castle_charter, alehouse (excl. cathedral) | The greatest hall in the land: Tavern income doubled, travellers flock in, and a Grand Tourney every 4 days. |

Each node's full effect list, flavour line, `depends_on` and (for changed nodes) the exact `change` text are in `techtree.json`.

---

## 5. Changes from the current tree

### 5.0 v2 changes (owner feedback: Commons was "a bit tame")

**Commons & Household rebuilt**

| id | v1 | v2 | Status |
|---|---|---|---|
| `home` | Houses & Lodging | + plaque shows the home tier | existing_changed S |
| `warm_hearth` | Warm Homes (−10% night hunger) | **Hearth Fires**: dusk gathering round the fire, chimney smoke, −10% kept | existing_changed M |
| `sturdy_beds` | Sturdy Beds (+2 morale) | **Cottages**: home tier 1, family sign, morning routine, +1 recruit candidate; +2 kept | existing_changed M |
| `feather_quilts` | +20% sleep energy | + **Well Rested** morning buff (+10% work until noon; lost if the alarm wakes them) | existing_changed S |
| `hearth_doctrine` | Scholar's Study | **Scholar's Study & Library** (the Library moves here) | existing_changed M |
| `bards_songbook` | Bard's Songbook (numbers) | **Bard's Stage**: a stage block, a dancing crowd, traveller tips | new M |
| `kitchen_and_hall` | Kitchen & Dining Hall | **Kitchen & Long Table**: communal noon meal plus cravings (absorbs Varied Table) | existing_changed M |
| `varied_table` | Varied Table (stat) | **removed**, merged into Kitchen & Long Table | — |
| `war_feast` | — | **War Feast**: a feast at the Banner after a raid warning; guards get +10% damage, +20 morale and courage; civilians stay calm | new M |
| `two_storey_houses` | Two-Storey Houses (bed cap) | **Townhouses**: home tier 2, couples, skilled travellers | new M |
| `wayside_shrine` | Wayside Shrine | **Wayside Chapel**, pick-one: 4 cards + a reroll, a service after victories, candles for the dead | new M |
| `alehouse` | — | **Alehouse**, pick-one: an after-work pint, feasts and festivals half price and more often, ale Coins | new M |
| `infirmary` | in Watch | **moved to Commons** (Watch cross-links); carry animation | existing_changed L |
| `manors` | — | **Manors**: home tier 3, master travellers, bigger families, a Village Elder | new M |
| `great_tavern` | Great Tavern | **Rooms to Let**: rent, tavern fame stars, weekly visitor events (knight, rich trader, storyteller) | new M |
| `harvest_feast` | Harvest Feast & Market Day | **Harvest & Midsummer Festivals**: decorations, maypole, dancing, visiting crowds, +10% work the next day | new M |
| `hall_of_heroes` | — | **Hall of Heroes**: memorial plaques, nemesis trophies, braver recruits, a "For the fallen!" buff | new M |
| `hall_and_learning` | Library & School | **Families & School**: weddings, children, inherited trades with a 2-level head start | existing_changed L |
| `cathedral` / `hall_of_revels` | capstone pick-one | same, but each now follows its Village path (Chapel → Cathedral, Alehouse → Revels) | new L |

Ideas considered but not picked:
- Bathhouse & Barber: its hair and beard art cost is high for a small payoff. It is a good later cosmetic node.
- Town Crier: Rooms to Let's visitor events and the Watch's Watchfires already cover early news.
- A Commons Guild Hall: masters and apprentices went to Craft instead, as `masters_apprentices`.

**Tame pass on the other branches.** Each keeps its number and gains a hook:

| Node | Hook added |
|---|---|
| `guard_drill` | Guards spar every morning at the Barracks |
| `archer_longbow_drill` | Volley: archers loose together on the Archers key (G) |
| `stout_straps` | Pickup batching: a Courier takes two different jobs on one trip |
| `courier_satchel` | Sack tiers are drawn on the Courier's model; Worker Packs show the same sack on gatherers |
| `swift_couriers` | Couriers jog on Urgent requests and arrow restocks |
| `warehouse_racks` | New Storage Rack block that displays what it holds; couriers sort by kind |
| `sharpened_axes` | The Lumberer whets his axe at a grindstone and replants every stump |
| `fishers_nets` | A net buoy that holds up to 4 fish while the Fisher rests |
| `porters_guild` | Towering loads; two Porters can move a whole chest |
| `runners_guild` | Runners' relay sprints the raid warning to outlying workers |
| `guild_halls` | Guild banners and a guild feast day |
| `masters_apprentices` (**new** Craft T3, M) | Masters take an Apprentice who follows them and earns double XP; Wits matters; the Apprentice inherits the workshop |

**Counts:** v1 had 70 nodes, v2 has 74. Commons went from 16 to 20, Craft from 14 to 15, and Watch from 19 to 18 (the Infirmary moved).

### 5.0a v3 changes (Captain's Voice, checklist levels, four new lanes)

**Captain's Voice** (`commanders_horn`, owner's choice; coordinator text kept verbatim): one key per role. R = Knights (the plain Guard), G = Archers, J = Spearmen, K = Longswordsmen, N = Mages, H = Healers. The order depends on what you look at: ground forms a line, an enemy is charged or focused, a wall or tower sends archers up, your own feet means "follow me". Shift + key sends them back to posts. Nodes that said "the Horn" were reworded: `shield_doctrine` becomes the Knights' shield line, and `archer_longbow_drill`'s Volley is on the G key.

**Checklist-recognised tiers (owner rule):**
- `sturdy_beds` (Cottage), `two_storey_houses` (Townhouse) and `manors` (Manor) each unlock the plaque's recognition of that home tier and its perks. The plaque lists "next level: add X", and the player or a Builder Upgrade Order meets it.
- The same rule applies to Warehouse levels and to the Tavern's Inn level (`great_tavern`).
- No level depends on a blueprint.

**Builder lane** (agent a1f86e34f18da84e3; ids, costs and wire ids match):

| id | Branch / tier | Cost | What it does | Code today |
|---|---|---|---|---|
| `builders_hut` (new) | Craft, Hamlet | 3 C, 16 logs, 8 cobble | Builder's Hut + Builder, Upgrade Orders, starter blueprints, barricade block | rides on `timber_rights` until the tree lands |
| `defense_plans` (new) | Watch, Hamlet | 3 C, 16 logs, 16 sticks | Defense Plan line tool: palisade walls, gates, timber watchtower | PostRaidUpgrade wire 19 |
| `masonry` (new) | Craft, Village | 6 C, 32 cobble, 16 stone bricks | Stone segments, stone gatehouse and tower, stone floors in Upgrade Orders; unlocks no levels | wire 20 |
| `barricades` (reworked) | — | — | Now only the warning-time "Raise the Barricades" village order; the block comes with the Builder's Hut | — |
| `palisade` (reworked) | — | — | Renamed **Manned Palisade**: builds on Defense Plans with double HP, guards shut the gates, archers on the walkway | — |
| `earthworks` (reworked) | — | — | Now builds on Defense Plans | — |
| `stone_walls` (reworked) | — | — | Now requires Masonry and is the combat side (HP, arrow slits) | — |

**Battle-roles lane** (agent ab2beb0f1e2d547df; see `plan/BATTLE-ROLES.md`):

| id | Branch / tier | Node cost | Building + emblem |
|---|---|---|---|
| `spearmen` | Watch, Village | 12 C, 8 iron, 16 sticks | Pike Yard + Spearman emblem (4 C) |
| `longswords` | Watch, Town | 18 C, 2 iron blocks, 6 leather | Sword Hall + emblem (5 C) |
| `rune_mage` | Watch, Town | 20 C, 16 lapis, 8 amethyst, 4 books | Rune Hall + emblem (6 C); rune charges and Rune Stones |
| `high_runes` | Watch, Castle | 30 C | 2 mages, 3 stones per carving, 6 s Ward. Replaces my `arcane_tower` idea: there is no separate tower |
| `battle_healer` | Commons, Village, next to `infirmary` | 8 C, 6 paper, 8 string | Healer emblem, hired at the Infirmary (2 slots); Bandage recipe |

Related changes:
- `pike_square` is now a Spearmen doctrine: all-round brace, brace strike +50%, chargers stopped dead.
- `knights` stays "Order of Knights" and is explicitly mounted. The R-key Knights are the plain Guard.
- `shieldbearers` is **removed**. It is not one of the owner's roles, and its tower-shield perk moved into `veteran_techniques`.
- The GuardRank "Spearman" is now shown as **"Man-at-Arms"** in tree text, to avoid a clash with the new job. The lang key is unchanged.
- In code today these roles gate on existing nodes (`shield_doctrine`, `first_raid_aftermath`, `hearth_doctrine`) until new DevelopmentNode wire ids exist.

**Gear-tier lane** (agent ac36696d6b537eb95; `GearTier` / `GearGate`). A settler may use a tier only when their own rank or level AND the village's knowledge both allow it.

| Tier | Opened by | Where it shows in the tree |
|---|---|---|
| 1 Mail | `first_raid_aftermath` | gear line on the Village Charter |
| 2 Plate (iron armour, crossbows) | any of `guard_arms_iron`, `fortification`, `craft_and_industry`, an Armoury or a Smithy | gear line on each of those nodes; crossbows need Tier 2 |
| 3 Diamond | `castle_charter` + `master_armoury` | half-tier lines on both nodes |
| 4 Netherite | `kingdom_crown` + `master_armoury` | half-tier lines on both nodes |

- New node `master_armoury`: Watch, Castle tier, 30 C, 4 diamond, 2 iron blocks, 8 leather; requires the Castle Charter and `fortification`.
- `guard_arms_iron` keeps its meaning.

**Warehouse-levels lane** (agent a053cd97fe5e2bfab; `WarehouseLevels`). Each level is recognised from the plaque checklist; the node only unlocks that recognition.

| Level | Name | Managed containers | Unlocked by | Cost | Gate |
|---|---|---|---|---|---|
| L1 | Storeroom | 16 | `stores_and_roads` | — | — |
| L2 | Stockroom | 32 | `stores_and_roads` | — | — |
| L3 | Storehouse | 64 | **`warehouse_racks`** (renamed; wire 16) | 5 C, 24 planks, 4 iron | — |
| L4 | Great Storehouse | 128 | **`great_storehouse`** (new, Town; wire 17) | 12 C, 32 stone bricks, 8 iron | 24 deliveries |
| L5 | Royal Storehouse | 256 | **`royal_storehouse`** (new, Castle; wire 18) | 24 C, 64 stone bricks, 16 iron, 4 gold | 64 deliveries |

- Containers beyond the cap are "not managed (warehouse full)"; nothing breaks.
- The earlier 64 → 96 cap idea is dropped. The Storage Rack display block and sort-by-kind filing are a future idea.

**PostRaidUpgrade wire ids in use:**
- 0–11: existing
- 12–15: logistics agent
- 16–18: warehouse agent
- 19–20: Builder agent
- 21+: next free

The five battle-role nodes and `master_armoury` are DevelopmentNode unlocks. They need new DevelopmentNode wire ids, which are free from 45 upward.

**Counts:** v2 had 74 nodes, v3 has 84.
- Added: builders_hut, defense_plans, masonry, spearmen, longswords, rune_mage, high_runes, battle_healer, master_armoury, great_storehouse, royal_storehouse.
- Removed: shieldbearers.

### 5.1 Changes against today's code

**Structure**
- The rings become ranks: Hamlet, Village, Town, Castle, plus a Kingdom crown. A new Crown spine has 3 new rank seals (`town_charter`, `castle_charter`, `kingdom_crown`).
- `settlement_charter` and `shelter` merge into one "Banner Raised" seal. `shelter` stays as a hidden id.
- `first_raid_aftermath` is renamed **Village Charter** and auto-stamps when the first raid resolves. The Learn click goes away.
- Doctrines lose the ACTIVE/DORMANT "primary tradition". Nothing reads it, so it is dropped: keep reading the NBT, stop writing it.

**Renames** (lang only, same id and wire id)

| id | Old name | New name |
|---|---|---|
| `stores_and_roads` | Stores & Roads | Warehouse & Courier |
| `cultivated_ground` | Cultivated Ground | Fields & Farmer |
| `shore_provisions` | Shore Provisions | Fishery |
| `home` | Home | Houses & Lodging |
| `shield_doctrine` | Shield Doctrine | Shield Wall |
| `guild_doctrine` | Guild Doctrine | Sawmill & Sawyer |
| `hearth_doctrine` | Commons Doctrine | Scholar's Study |
| `arm_the_watch` | Arm the Watch | Watchtower & Archers |
| `first_watch` | First Watch | Barracks & Guard |
| `hospitality` | Hospitality & Trade | Tavern & Trade |

**Placeholders made real** (buildings, professions and recipes already exist; emblems and release tests are missing)

| id | Now called | What it unlocks |
|---|---|---|
| `fortification` | Armoury & Armourer | Armoury, Armourer. Price drops from 4 iron blocks to 12 iron + 8 leather, and it moves to the Village ring |
| `land_and_harvest` | Mill, Bakery & Pasture | Miller, Baker, Herder, Butcher |
| `craft_and_industry` | Mine, Smelter & Smithy | Miner, Smelter, Smith, plus the tool-tier speed bonus |
| `hall_and_learning` | Families & School | School and families (the Library moves to Scholar's Study) |

Split out of the placeholders as new ids for content that already exists:
- `tannery` (Tanner, plus the Pedlar's Sack player item)
- `carpenter_mason` (Carpenter, Mason, Weaver)
- `kitchen_and_hall` (Cook, Kitchen, Dining Hall)
- `infirmary` (Healer; needs the downed-state system)

**Moves**
- `border_wardens` (Hunters' Lodge) moves from Watch to Craft and now needs only the Village Charter.
- The Infirmary moves to Commons (v2). It sits with the other life-and-care nodes and feeds the Hall of Heroes, and Watch cross-links to it.

**Choices**
- `archer_longbow_drill` becomes a pick-one against the new `crossbows`. Saves that already own it keep it, and Crossbows stay locked for them.

**Re-priced** (Coin-only today, now Coins plus goods)
- `guard_arms_iron`: +6 iron.
- `archer_longbow_drill`: +8 string, +12 logs.
- `hand_cart`: +12 planks, +4 logs, +2 iron (logistics agent).
- `courier_satchel` and `worker_packs` keep their Coin-only price, because the logistics agent kept them unchanged. A later balance pass can add leather.

**Reworked effects**
- `hand_cart` becomes a visible cart with a percentage bonus and road/off-road speed.
- `worker_packs` follows the settlement's sack tier.
- `shield_doctrine` gains the Hold the Line order.
- `veteran_techniques` upgrades the existing moveset: it only adds, nothing is taken away.
- `bards_songbook` builds on `TavernBard`.

**New content:** 35 nodes. See the "new" rows in the tables. Four of the new Logistics nodes are being built right now by the logistics agent: `leather_pack`, `frame_pack`, `paved_roads` and `swift_couriers`.

**Screen:** `DevelopmentScreen` needs `worldX/worldY` and `upgradeX/upgradeY` for about 40 more cards, ring labels per rank, the spiral Crown seals, and a pick-one badge. The json `x/y` values are ready to copy: same axes, y grows down.

**Open design conflicts to confirm with the owner**
1. **Bard tips.** An earlier masterplan said the bard must never create Coins. The owner's vision lists tavern and services as a Coin source. The design pays tips only from visiting travellers, never from your own settlers.
2. **Moveset techniques.** The owner asked for heavy, combo and bash as "trainable techniques". All guards have them today, so the design adds upgrades on top rather than locking existing moves, to avoid a visible nerf right after the Sunday test. If the owner prefers real gating, grant the basics to every save that owns `first_watch`.
3. **Infirmary placement.** In v2 it moved to Commons. If the owner wants it back under Watch, only the `branch` field and the x/y change.
4. **The Commons pick-one locks the capstone.** Chapel leads to the Cathedral, and Alehouse leads to the Hall of Revels. This makes the Village-ring choice a real path decision.

---

## 6. Implementation plan

### Sunday (27 Sep): only what the freeze allows

Code freeze is Saturday 21:00, and the Sunday build is gated on stability. Nothing below is required for Sunday. It is listed in order of safety, to be used only if the go/no-go allows it.

1. **Logistics agent's slice**, if it passes its GameTests before the freeze: `leather_pack`, `frame_pack`, `paved_roads`, `swift_couriers`, the visible `hand_cart`, and `worker_packs` on the sack tier. This is the owner's headline "carts and bigger sacks" ask.
2. **Lang-only renames** (the table above). There is no logic change and the risk is zero, but the existing lang keys are pinned by tests: check `DevelopmentScreenCostAndTooltipTest` first.
3. **Nothing else.** The Village Charter auto-stamp, the new nodes and the placeholder releases wait until after Sunday.

### After Sunday: slices in roadmap order (each with GameTests and a video before owner review)

| Week | Slice | Nodes delivered | Size |
|---|---|---|---|
| 40 | **Tree plumbing.** Auto-stamped Village Charter. Drop DORMANT. New `PostRaidUpgrade` entries for the S-size bonuses. Screen positions from the json. | `first_raid_aftermath`, `settlement_charter` merge, `watchfires` (bell radius + earlier warning), `feather_quilts` Well Rested, `guard_drill` sparring, `sharpened_axes` grindstone and replanting, re-prices | M |
| 40–41 | **Living-village slice 1** (roadmap phase 1 thought bubbles): dusk gathering, home-tier room check, Bard's Stage, pickup batching | `warm_hearth`, `sturdy_beds` Cottages, `two_storey_houses` Townhouses, `bards_songbook`, `stout_straps` | M–L |
| 40–41 | **Placeholder releases.** Emblem entries + release GameTests for Armourer, Miller, Baker, Herder, Butcher, Miner, Smelter, Smith, Tanner, Cook. Tool-tier speed hook. | `fortification`, `land_and_harvest`, `craft_and_industry`, `tannery`, `kitchen_and_hall`, `border_wardens` move | M–L |
| now | **Lanes already being built** (logistics, Builder, battle roles, gear tiers, warehouse levels): code gates on existing nodes until the tree lands | `leather_pack`, `frame_pack`, `paved_roads`, `swift_couriers`, `hand_cart`, `builders_hut`, `defense_plans`, `masonry`, `spearmen`, `longswords`, `rune_mage`, `battle_healer`, gear lines, `warehouse_racks`, `great_storehouse`, `royal_storehouse` | — |
| 41 | **Captain's Voice** (per-role keys, being built now). **War Feast** rides on the raid-warning hook | `commanders_horn`, `shield_doctrine` rework, `war_feast`, `archer_longbow_drill` Volley | M |
| 42 | **Crossbows + Longbows choice.** Pick-one support in `Development` (an `excludes` check). | `crossbows`, `archer_longbow_drill` | M |
| 42–43 | **Enemy roster** (shieldbearers, raider archers), then Veteran Techniques (tower shields) and Longswordsmen | `veteran_techniques`, `longswords` | L |
| 44–45 | **Walls & gates** (combat steps 4–6): barricades, palisade, ditch, stone walls, arrow slits | `barricades`, `palisade`, `earthworks`, `stone_walls` | L |
| 44–45 | **Downed state + Healer + named heroes** (roadmap phase 3) | `infirmary`, `hall_of_heroes` | L |
| 46–47 | **Rank ladder 1** plus a study timer on nodes. Priority and minimum stock. Food variety. | `town_charter`, `courier_ledger`, `kitchen_and_hall` cravings, `manors`, `carpenter_mason`, `masters_apprentices`, `charcoal_kilns` / `deep_mine`, `costers_cart`, `porters_guild` / `runners_guild`, `warehouse_racks` | L |
| 46–47 | **Blessings expansion** (roguelike: rarities, curses) + the Alehouse after-work visit | `wayside_shrine` / `alehouse` | M |
| 48–49 | **Event engine festivals**, outposts and carters, rank ladder 2, horses and mules | `harvest_feast`, `great_tavern` visitor events, `master_armoury`, `high_runes`, `hall_and_learning` families and children, `caravan_routes`, `castle_charter`, `mule_cart`, `knights` / `pike_square`, `guild_halls`, `cathedral` / `hall_of_revels` | L |
| 50–51 | **Diplomacy and rival kingdoms** | `kingdom_crown` | L |

**Engineering notes**
- Keep one catalogue for effects: `PostRaidUpgrade` for bonuses, `DevelopmentNode` for unlocks.
- Add `tier` / `rank` and `excludes` to both.
- `Development.canUnlock` checks three things: the rank seal, the prerequisites, and that no excluded node is already owned.
- A study timer is a new `DevelopmentState` field: node id plus the finish day, advanced only while players are online (pause-when-empty). The Scholar gives −25% and the Library −20%.
- New wire ids continue from 16 upward, because the logistics agent uses 12–15. Never renumber.
