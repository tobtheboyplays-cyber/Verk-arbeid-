# Another Furniture 4.0.2: optional Bannerhold seating

## Evidence and scope

Inspected a private copy of `another_furniture-neoforge-4.0.2.jar` from the server mods folder (SHA-256 `C61A66C73BCC5147D9983DC72C485493A612D2DBC078FCB86E2024155E34B7DD`). Evidence is the jar's `neoforge.mods.toml`, blockstate/model JSON, and `javap` bytecode for its registry, seat blocks and seat entity. This is an implementation reference, not a native seating test. The jar declares mod id `another_furniture`, version `4.0.2`, NeoForge and Minecraft 1.21.1+.

## Exact IDs and states

All IDs below have the `another_furniture:` prefix. The eleven wood prefixes are **oak, spruce, birch, jungle, acacia, dark_oak, mangrove, cherry, bamboo, crimson, warped**. `AFBlocks` registers and the jar has blockstate assets for every wood prefix with these suffixes:

| Suffix | Relevant blockstate properties |
| --- | --- |
| `_chair` | `facing` north/east/south/west; `tucked` boolean; `variant` 1–11; `waterlogged` |
| `_bench` | `facing`; `horizontal_1` and `horizontal_2` connection shape; `back` boolean; `waterlogged` |
| `_table` | `facing`; `leg_1` through `leg_4` booleans; `update`; `waterlogged` |
| `_shelf` | `facing`; `horizontal` single/left/middle/right; waterlogging as defined by its block |
| `_drawer`, `_flower_box`, `_shutter` | Decoration only: drawer `facing/open`; flower box `facing/horizontal/attached`; shutter `facing/hinge/open/variant/vertical`. |

The sixteen color prefixes are **white, orange, magenta, light_blue, yellow, lime, pink, gray, light_gray, cyan, purple, blue, brown, green, red, black**. Every color has `_stool` (`low`, `waterlogged`), `_tall_stool` (`waterlogged`), `_sofa` (`facing`, `type`, `waterlogged`), `_curtain`, `_lamp`, and `_lamp_connector`. Sofa `type` assets include `single`, `left`, `middle`, `right`, `inner_left`, `inner_right`, `outer_left`, `outer_right`. The jar also has `another_furniture:service_bell`. Seating tags include chairs, benches, both stool heights and sofas; tables have their own tag. Shelves/decorations are listed here for recognition, not treated as seats/tables.

**Correction to watch:** chair `tucked=true` is explicitly *not sittable*. A generic block tag cannot filter blockstate properties, so the tavern lane must reject tucked chairs at runtime. Tables change leg props when neighbours connect; use the whole table block ID and let Another Furniture update the legs.

## Sitting and precise pose anchor

The jar registers `another_furniture:seat` (`com.starfish_studios.another_furniture.entity.SeatEntity`). A player right-click handled by `SeatBlock.useWithoutItem` checks interaction permission, sit state, and existing seat occupants. On the server, `SeatBlock.sitDown(level, pos, entity)` creates a `SeatEntity` at block center `(x+0.5, y+0.001, z+0.5)` and calls `entity.startRiding(seat)`. `SeatEntity` discards itself when its block stops being sittable or it has no passenger. It aligns passenger yaw using the seat block; chairs and benches use `facing.toYRot()`. Sofa corner types adjust yaw by 45 degrees. An occupied seat with a non-player passenger may be ejected by a player using that seat; an occupied player seat is kept.

The riding Y is `blockY + 0.001 + SeatBlock.seatHeight(state) + SeatEntity.getEntitySeatOffset(passenger)`. The special offsets apply to a few vanilla animal/monster classes; a Bannerhold settler gets zero from that method. Seat heights from the jar bytecode:

| Seat | `seatHeight` (blocks above seat block) | Notes |
| --- | ---: | --- |
| Chair | 0.35 | Reject `tucked=true`; `facing` points toward front/dismount side. |
| Bench | 0.35 | Connected bench pieces still each seat one passenger. |
| Sofa | 0.35 | Corner pieces rotate passenger about 45 degrees. |
| Stool | 0.40 normally; 0.35 when `low=true` | No facing property. |
| Tall stool | 0.95 | Needs its own raised leg/foot pose. |

The table top model spans Y=13..16 pixels of the table block, so its top is at blockY+1.0. A chair/bench/sofa seated settler's root should be around blockY+0.35, facing the table. Keep enough space for legs under the top; verify mug/arm reach and clip silhouette in game at all four facings. A tall stool needs a separate higher pose. For a vanilla stair fallback, measure the actual stair collision/seat height in game rather than applying Another Furniture's 0.35 universally.

The public `sitDown` method accepts an `Entity`, and `SeatEntity.addPassenger` is generic, so a settler **can** mount it in principle. The ordinary player interaction accepts leashed mobs only through the mod's `can_sit_in_seats` entity tag; it does not itself mount arbitrary settlers. A direct call to these classes would add a hard runtime/linkage dependency. For this optional integration, prefer Bannerhold's own server-authored sit state and animation at a validated seat position, without spawning Another Furniture's seat entity. Approach from the front, reserve the seat in Bannerhold, snap to the measured anchor only during the seated activity, and release the reservation on interruption/death/unload. This avoids mounts interfering with settler navigation and with players taking a seat. If actual mounts are later chosen, isolate the optional bridge behind a loaded-mod check and validate mount/dismount, occupancy, save/reload and co-op in game.

## Optional tags and fallback

Added `data/hearthstead/tags/block/settler_seats.json` (70 optional Another Furniture seat IDs + 11 vanilla wood stairs) and `settler_tables.json` (11 optional Another Furniture wood tables + 11 vanilla wood pressure plates). Every optional entry uses `{"id":"another_furniture:...","required":false}`; there is no dependency or Java reference. All 81 mod entries were checked against blockstate assets in this exact jar.

Vanilla fallback: use a wood stair as chair and a **wood fence with its matching pressure plate directly above** as a table. The table tag lists only the top pressure plates, not bare fences, because every fence in a wall should not become a tavern table. The tavern lane must require the fence beneath the tagged plate before accepting this fallback, and should still require a reachable adjacent seat. Match wood type where practical. Tags classify candidates; they do not prove furniture is safe to sit at or that a table assembly exists.

No GameTests or native visual check were run here. The tavern lane should test: optional mod present/absent, tucked chair rejected, all four facing directions, connected bench/sofa, tall stool pose, ordinary stairs, fence+plate assembly, seating interruption, and player/settler contention.
