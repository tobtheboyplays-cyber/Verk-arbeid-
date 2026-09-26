# Lumber Craft — physical state contract

Status: active Blender authoring contract. This replaces visual guesswork; it
does not replace the server-authoritative transaction.

## Actor and trigger

- Actor: Lumberer performing emergency wooden-axe self-maintenance.
- Runtime activity: `WORK_CRAFT`; animation state: `craftState`.
- Duration: 2.40 seconds / 48 game ticks, non-looping.
- Locomotion owner: none. Both feet stay planted throughout the clip.
- World target: the confirmed crafting-table surface directly in front of the
  worker.

## Ownership and contacts

- The crafting table is world-owned and never follows the worker.
- Recipe ingredients are renderer-owned projections of the server-authored
  vanilla 3x3 recipe (`PP_ / PS_ / _S_`). They become visible in recipe-slot
  order at ticks 4, 8, 12, 16 and 18.
- The craft commit is authoritative at tick 30 / 1.50 seconds. The striking
  hand must visibly contact the table on that exact frame. Ingredients vanish
  and the real wooden-axe result appears on the table on that frame.
- The output remains world-presented on the table through the left-hand contact
  at tick 43 / 2.15 seconds. The real item has already existed in durable
  worker escrow since the tick-30 commit; tick 44 removes the table projection
  and presents no hand item because runtime carries that escrow in the bag.

## Pose beats

1. Rest and target read: 0.00–0.10 s.
2. Alternating ingredient layout: contacts at 0.20, 0.40, 0.60, 0.80, 0.90 s.
3. Brief read and planted wind-up: 1.05–1.40 s.
4. Decisive table strike: 1.50 s, followed by a short weight-bearing hold.
5. Result read: 1.80–1.95 s.
6. Left-hand pickup: exact contact at 2.15 s.
7. Recovery to exact neutral: 2.40 s.

## Rejection rules

- Reject any torso key that visually bends away from the table.
- Reject feet that slide, lift or inherit walking motion.
- Reject a hand passing above the crown or clipping through the head.
- Reject floating, following or duplicated ingredient/output props.
- Reject a visual craft/pickup contact that disagrees with ticks 30 or 43.
- Reject any owned channel that does not end at exact neutral.
