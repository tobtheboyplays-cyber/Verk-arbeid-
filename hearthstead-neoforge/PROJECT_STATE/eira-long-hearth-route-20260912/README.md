# Eira long Hearth return — staged only

## Concrete live cause

At game time 820478, Eira was hungry (11, later 7), standing near `(158,73,-116)` while the bound Hearth at `(110,72,-116)` held food. Her ordinary `FOLLOW_RANGE` was 32. `EatFromHearthGoal` used `HearthApproach.findProgressingContactPath`, which accepts either a full contact route or a partial route whose endpoint is nearer to the Hearth. It therefore rejects a legal route whose first step must detour away around the elevated house, doors, and stairs.

## Staged implementation

The patch changes only these files:

- `HearthApproach.java`: adds `findCivilianMealContactPath`, leaving the existing general/Guard method unchanged. It first keeps ordinary Road and vanilla exact paths, then performs one isolated multi-target full-route search before accepting a nearer partial prefix.
- `EatFromHearthGoal.java`: selects that civilian-meal-only route.
- `HearthsteadCommand.java`: adds a read-only `hearthstead why` navigation snapshot for live path diagnosis.
- `ResidentMealGameTests.java`: adds the focused long-route probe below.

Bytecode inspection of NeoForge Minecraft 1.21.1 proves the protected overload is `createPath(Set, regionOffset, above, accuracy, searchRange)`. The staged `RoadNavigation` subclass calls it once with `(targets, 0, false, 0, 96.0F)` and first sets its visited-node multiplier to `3.0F`. Thus the 96-block value is the actual final search range, not the accuracy parameter. The query ranks the existing legal contact set once; it does not loop one search per contact cell.

No FOLLOW_RANGE, combat navigation, food custody, or Guard routing changes. Food still leaves the Hearth only at the existing close-range ray-contact gate in `EatFromHearthGoal`.

## Focused regression

`civilianMealProbeFindsCompleteRouteBeyondFollowRange` uses `empty64`, floor `(0..63)`, a Hearth at `(4,1,4)`, and a resident at `(55,1,55)`. It asserts the standard 30–40 follow range, starts beyond it, receives a complete valid Hearth-contact path, and verifies planning has not withdrawn the physical bread or made the settler own a meal.

## Validation boundary

`git apply --check PROJECT_STATE/eira-long-hearth-route-20260912/eira-long-hearth-route.patch` passed against the current checkout. No source file was changed and no compile or GameTest ran. The added test is staged for the parent QA run.
