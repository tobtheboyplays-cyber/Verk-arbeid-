# Town palette — Elmfield (owner's server, copy 2026-09-25)

Source: `world-copies/server-20260925` (read-only; region `r.0.-1.mca` copied to the blueprint lane's scratchpad and parsed with nbtlib).
- Settlement "Elmfield", centre (110, 72, -116), radius 48.
- 7 registered buildings: house ×2 (the two-storey house), barracks, lumber camp, warehouse, farmhouse, tavern.
- Scan box: centre ±96 blocks, y 55..120.
- **Excluded as natural:** stone and its variants, dirt, grass, sand, sandstone (it is all beach sub-soil at y ≤ 72), gravel, water, ores, plants, and non-persistent leaves. Logs with axis=y within 2 blocks of natural leaves were also excluded as trees (1,354 of them).

## Placed blocks by use (whole town)

| Rank | Block | Count | Role |
|---|---|---|---|
| 1 | oak_planks | 1188 | **walls (infill), floors, ceilings, roof decks** |
| 2 | oak_log | 521 (207 upright, 188 horizontal) | **frame**: corner posts + a beam ring at every floor line |
| 3 | oak_stairs | 354 (283 bottom, 25 top) | **roof** (stepped/hipped eaves), interior stairs |
| 4 | cobblestone | 336 | **base / plinth course** under every house; tavern walls |
| 5 | dirt_path | 314 | streets and door aprons |
| 6 | oak_slab | 260 (168 bottom, 41 top) | **roof caps**, flat roof decks, shelf/counter tops |
| 7 | cobblestone_slab | 155 | path steps, tavern roof |
| 8 | oak_fence | 136 | railings, fences, table legs, a balcony |
| 9 | wall_torch + torch | 56 + 19 | **lighting** (lanterns only 2) |
| 10 | chest | 49 | storage |
| 11 | farmland / wheat / carrots | 46 / 20 / 20 | fields |
| 12 | birch_planks | 36 | **accent floor** (warehouse) |
| 13 | glass_pane | 35 | **windows**: small, 1–3 panes per wall |
| 14 | cobblestone_stairs | 31 | tavern roof/eaves |
| 15 | oak_door | 26 | every door is oak |
| 16 | ladder | 22 | outside ladder to the upper door, warehouse loft |
| – | white_bed 18, crafting_table 10, mossy_cobblestone 10, barrel 7, hay_block 11, furnace 4, lantern 2, bell 1 | | furniture |
| – | another_furniture: oak_table 9, oak_chair 6, oak_bench 6, oak_shelf 5 | | furniture from a **non-vanilla mod**; blueprints use vanilla stand-ins (fence + pressure plate table, stair chairs) |

### Per building (block counts inside the registered bounds)
- **Two-storey house + barracks block**
  - oak planks 283/515, oak slab 49/96, oak stairs 30/91, cobblestone 30/58;
  - oak log: upright 25/45, horizontal 28/55;
  - beds, wall torches, ladder, a few panes.
- **Warehouse**
  - planks 167, stairs 85, cobble 52, slabs 65;
  - chests 37, birch planks 36 (floor), fence 18, wall torches 10.
- **Tavern**
  - cobblestone 60, cobble stairs 27, cobble slabs 42;
  - logs 29, glass panes 15, wall torches 10;
  - bell and ale tap. It is half dug into the hillside.
- **Lumber camp / farmhouse**: the same kit at 6×5×5. Planks around 110–140, a stair roof, a cobble base and a log frame.

## The style anchor: the owner's two-storey house
Region x101..120 y70..81 z-135..-115, exported by the pathing lane as `data/hearthstead/structure/path_server_house.nbt`.

| Element | What the owner built | Blueprint rule |
|---|---|---|
| Plinth | 1 course of **cobblestone** at ground level under the whole footprint | Every blueprint: cobblestone floor/plinth row at `ground_level` |
| Frame | **oak_log** corner posts and posts every 5–7 blocks, full height | Log corner posts + posts every 3–4 blocks (tighter = more "timber-frame") |
| Floor line | a full **horizontal oak_log ring** at the upper-floor level (y76) that sticks out 1 block: a small jetty | Log beam ring at every floor line; 2-storey variants jetty 1 block on the long sides |
| Walls | **oak_planks** infill | oak_planks infill (large variants: cobblestone ground storey, like the tavern) |
| Windows | single **glass_pane**, 1–3 per wall, at eye height | glass panes at eye height between posts, with oak trapdoor shutters |
| Roof | **stepped oak_stairs eave, oak_planks deck, oak_slab cap** (low hip) | gable roofs of oak_stairs with a plank gable and slab ridge; 1-block overhang (medieval upgrade of the owner's hip) |
| Stairs inside | a 1-wide spiral stair round a log pillar + an outside ladder to a raised upper door | straight interior oak-stair runs, 2-high doors, ≥2 headroom (walkable by settlers and the Builder) |
| Light | **wall torches** | wall torches inside; lanterns under the eaves and by doors for the medieval look |
| Doors | oak_door | oak_door, always 2 high, never carpet in the doorway |
| Grounds | dirt_path apron, oak_fence railings | dirt_path apron at the door (optional), fence railings, window boxes of trapdoors + potted flowers |

**Resulting blueprint palette** (the whole catalogue uses these; anything else is a small accent):
- **Frame:** oak_log
- **Walls:** oak_planks, cobblestone
- **Plinth:** cobblestone, plus mossy_cobblestone as a 1-in-10 accent
- **Roof:** oak_stairs, oak_slab, oak_planks
- **Stone buildings:** cobblestone_stairs/slab/wall, and stone_bricks only where a checklist asks for dressed stone (mason)
- **Windows:** glass_pane
- **Doors:** oak_door
- **Light:** wall_torch, lantern
- **Trim:** oak_fence, oak_trapdoor
- **Floors:** oak/birch planks
- **Streets:** dirt_path
