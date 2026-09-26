# Weapons: stats per type and tier

Weapons lane, 26 Sep 2026. Source of truth: `item/weapon/WeaponType.java` (captain weapons) and `item/role/RoleWeaponItem.java` (longsword). Art and models come from `tools/weapons/` (gen_weapons.py; `sh regen.sh <resources>` rebuilds everything). Trait strength is configurable in the server config under `[weapons]`. `[features] captainWeapons=false` turns every trait off; the items keep their base stats.

Player damage = 1 (base) + type modifier + tier bonus. Attacks per second = 4 + speed modifier. Durability, repair material and enchantability come from the vanilla tier.

| Type | Speed (/s) | Identity |
|---|---|---|
| Short Sword | 2.0 | one-handed, carried as a pair; fast |
| Double Axe | 0.8 | two-handed; armour shred +0.3/armour pt (max +4), +2 vs Brutes; breaks shields; sweeps |
| Halberd | 0.9 | two-handed; reach +1.5; x1.35 vs a foe running at you; sweeps |
| Warhammer | 0.8 | two-handed; knockback +1; breaks shields; 25% stun (Slowness IV 1 s, raiders stagger); no sweep |
| Longsword | 1.0 | two-handed (roles lane RoleWeaponItem); cleave hits several; sweeps |

## Damage per hit (and DPS) by tier

| Type | Wooden | Stone | Iron | Golden | Diamond | Netherite |
|---|---|---|---|---|---|---|
| Short Sword | 3 (6.0) | 4 (8.0) | 5 (10.0) | 3 (6.0) | 6 (12.0) | 7 (14.0) |
| Double Axe | 8 (6.4) | 9 (7.2) | 10 (8.0) | 8 (6.4) | 11 (8.8) | 12 (9.6) |
| Halberd | 6 (5.4) | 7 (6.3) | 8 (7.2) | 6 (5.4) | 9 (8.1) | 10 (9.0) |
| Warhammer | 7 (5.6) | 8 (6.4) | 9 (7.2) | 7 (5.6) | 10 (8.0) | 11 (8.8) |
| Longsword | 5 (5.0) | 6 (6.0) | 7 (7.0) | 5 (5.0) | 8 (8.0) | 9 (9.0) |
| *vanilla sword* | 4 (6.4) | 5 (8.0) | 6 (9.6) | 4 (6.4) | 7 (11.2) | 8 (12.8) |
| *vanilla axe* | 7 (5.6) | 9 (7.2) | 9 (8.1) | 7 (7.0) | 9 (9.0) | 10 (10.0) |

Durability: wooden 59, stone 131, iron 250, golden 32, diamond 1561, netherite 2031. Netherite is fire resistant and made at the smithing table (diamond version + netherite upgrade template + netherite ingot).

## Against raiders (iron tier, a player hitting at full cooldown, no armour or enchantments)

| Weapon | Goblin (20 HP) | Skirmisher (28 HP) | Brute (70 HP) |
|---|---|---|---|
| Iron sword | 4 hits / 2.5 s | 5 hits / 3.1 s | 12 hits / 7.5 s |
| Iron short sword | 4 hits / 2.0 s | 6 hits / 3.0 s | 14 hits / 7.0 s |
| Iron longsword | 3 hits / 3.0 s | 4 hits / 4.0 s | 10 hits / 10.0 s |
| Iron double axe | 2 hits / 2.5 s | 3 hits / 3.8 s | 6 hits / 7.5 s |
| Iron halberd (vs charge x1.35) | 3 hits / 3.3 s | 4 hits / 4.4 s | 9 hits / 10.0 s |
| Iron warhammer | 3 hits / 3.8 s | 4 hits / 5.0 s | 8 hits / 10.0 s |

Brute armour, when it has any, adds the double axe's shred bonus on top. The halberd's charge bonus makes it 11 per hit against a charging Brute (7 hits). Each weapon's special pays off where its DPS is lower: sweeps for crowds, shield breaks for shield walls, reach and anti-charge for holding a line, stun and knockback for peeling Brutes off a gate.

## Recipes (M = planks / cobblestone (stone tool materials) / iron / gold / diamond, S = stick, L = leather)

- Short sword: `M / L / S` (one per craft; craft two for the pair)
- Double axe: `MMM / MSM / _L_`
- Halberd: `_MM / LSM / S__`
- Warhammer: `MMM / _S_ / _L_`
- Longsword (new tiers): wooden `__P / SP_ / LS_`, stone `__C / CC_ / LS_`, golden `__G / IG_ / LS_`. Iron and diamond keep the roles lane's recipes.
- Netherite: smithing table upgrade of the diamond version.

Tech gates (`data/hearthstead/techtree/recipe_gates.json`, framework lane): wooden and stone need Barracks & Guard (`first_watch`); iron and golden need Armoury (`fortification`); diamond and netherite need Master Armoury.

## Captain loadouts and tags

`#hearthstead:captain/dual_swords` (short swords), `captain/great_axes` (double axes), `captain/halberds`, `captain/warhammers`, `captain/bows` (vanilla bow). Short swords are also in `#minecraft:swords`. `#hearthstead:heavy_weapons` feeds the vanilla sharp-weapon, weapon, fire-aspect and durability enchantment tags. The new longsword tiers are in `#hearthstead:longswords`.

## Holding and animation

- Player third person: two-handers use a custom `ArmPose` (the two-handed guard, a NeoForge enum extension). The haft runs diagonally across the body with the off hand low and the main hand high, and the head sits out beside the main-hand shoulder at chest height, so the face stays clear. Arm angles and display transforms were solved together in `tools/weapons/tune_twohand.py`: fist-on-haft error about 0.75 px, body penetration 0 px. On attack, the off arm follows the vanilla main-arm swing.
- First person: two-handers and longswords sit diagonally at the lower right. The longsword is about 1.5x a vanilla sword. A near-plane check through the whole vanilla swing shows no clipping (`held_math.fp_near_clip`).
- Settlers: the clips come from the battle-roles and anim lanes. Grip points and blade lengths are in `tools/weapons/weapon_geometry.json`.
- Bows: vanilla bow. Settlers use a settler-side hold (`client/weapon/SettlerBowHold`) at 1.22x size, in three states: idle at the side, low ready across the hips with an arrow nocked, and drawing upright.

