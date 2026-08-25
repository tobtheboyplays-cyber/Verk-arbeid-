# SLICE A2b — the courier's sack: carrying you can *see*

Status: designed, not implemented. Written while `full` was executing, so
nothing here has touched source yet.

## The ask

Verbatim from the user: *"courier må ha en sekk så kan vi se hvor mye han
bærer og hvor mye han klarer å bære som blir en mechanic. Kanskje mulig å
oppgradere til en slags vogn senere."*

Three requirements, in order of weight:

1. A **visible sack** on the courier.
2. It shows **how much he is carrying right now**.
3. **How much he can carry is a mechanic** — a number the player can change,
   with a cart as a later upgrade.

## What exists today (verified, not assumed)

- `SettlerModel` already has a `backpack` part: `torso` child, `texOffs(96, 0)`,
  a 6×7×3 box at `(-3, -9, 2.5)`. It is **purely decorative** — always
  rendered, no visibility rule, no relationship to anything the settler
  carries. `gen_settler.py` paints it as part of the default garment.
- `CourierWorkGoal.LOAD_TRIGGER = 8` is a `private static final int`. That is
  requirement 3 as a magic constant: invisible, unchangeable, not a mechanic.
- `SettlerEntity.bag` is a real persisted container that drops on death. The
  item truth is already right; none of it reaches the client.

So requirement 1 is half-built, and 2 and 3 do not exist.

## How the references do it, and how to beat them

| | TekTopia | MineColonies | Hearthstead A2b |
|---|---|---|---|
| Is carrying visible? | Villager holds the item stack in hand | Courier shows nothing; the load is a number in a GUI | A sack that **swells with the load** |
| Can you read the amount at a glance? | Only the item type, not the amount | No — you open a screen | Yes: size, sag and the carrier's lean all scale with the load |
| Is capacity a mechanic? | No | Yes, but as a hidden stat (Adaptability) behind a level-up | Yes, and **legible**: a full sack looks full |

The thing to beat is legibility. Both references make you *open something* to
learn what a worker is doing. A settlement should be readable by looking at
it — a courier trudging home with a bulging sack tells the story without a UI.

## Design

**Synced state.** Two entity data accessors on `SettlerEntity`:
`DATA_CARRY_LOAD` (int) and `DATA_CARRY_CAPACITY` (int). The server recomputes
load whenever `bag` changes — the bag is tiny, so a recompute is cheaper than
a change-listener. Capacity is a field, base 8, persisted, and the single
source for `CourierWorkGoal`'s stop condition (`LOAD_TRIGGER` is deleted, not
kept alongside it — two sources of one truth is how the plaque/settlement
split went wrong before, see D-006).

**The sack part.** A new `sack` cube on `torso`, distinct from the general
`backpack` so a non-courier keeps the outfit piece and only a laden courier
grows a sack. Visible only when `load > 0`. `ModelPart` exposes `xScale`,
`yScale`, `zScale` in 1.21, so one cube covers every fill state:

- scale `0.55 → 1.15` across `load / capacity`,
- a small downward offset that grows with the load (it *hangs*),
- a bounce term driven by `limbSwing` so it moves with the gait rather than
  riding rigidly on the back.

Discrete tiers were considered and rejected: three swap-in cubes cost three UV
regions and read as popping. Continuous scale on one cube is both cheaper and
smoother, and the blocky silhouette survives it.

**Weight.** `WALK_LADEN` and `COURIER_CARRY` already exist. Their forward lean
becomes a function of the load fraction, so a full sack visibly bends the
carrier and an almost-empty one barely does. This is the same "vekt bak seg"
note the user gave on the chop animation, applied to carrying.

**Texture.** A new UV region in the 128×64 atlas (the exact offset gets
computed against `gen_settler.py`'s `UV` map at implementation time — the
region must not collide with `backpack` at (96,0), `belt` at (96,20) or
`hat_brim` at (64,44)). Cloth bulge, a tie cord at the neck, and the same
palette derivation the rest of the outfit uses, so it stays deterministic.

**The cart, later.** Capacity being a real synced number is the whole seam: a
handcart raises `DATA_CARRY_CAPACITY` and swaps the sack part for a cart
model. Nothing else has to change. Not this slice.

## Acceptance

- A courier carrying 0 shows no sack; carrying capacity shows a full one, and
  the intermediate states are visibly different from both.
- Capacity is one number, used by both the AI stop condition and the renderer.
- GameTest: the synced load tracks the real bag through a full round trip,
  including the RETURNING path from KF-013.
- Asset validator covers the new UV region; `gen_settler.py` stays
  byte-reproducible across two runs (KF-007's rule).
- Live video of a laden round trip — the KF-013 fix means no such footage
  exists yet, and this slice is exactly what it should show.
