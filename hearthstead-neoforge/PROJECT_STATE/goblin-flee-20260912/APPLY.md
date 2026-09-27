# Goblin flee speed — staged patch

## Diagnosis

`RaiderEntity` gives a skirmisher base movement speed of `0.38`, while `GoblinThiefDemo.TheftGoal.escape` passes `FLEE_CRUISE_SPEED` and `FLEE_TIGHT_TURN_SPEED` directly to `navigation.moveTo`. The live values (`0.31` and `0.21`) are navigation multipliers, so the second value makes each tight reroute a crawl. The policy has no branch on `lootCount`; one Coin is only the visible trigger for stage 2, not a weight penalty.

The synchronized stage-2 transition already clears `isShiftKeyDown`. `GoblinThiefModel` therefore is not applying its crouch transform during flight, but its `0.19F` torso lean still reads as a sneak-like getaway at the reported slow pace. The patch changes only that stage-2 lean to `0.10F`; the pouch, pickpocket windup, lockpick stage, approach crouch, witnesses, route selection, and real-Coin custody remain untouched.

## Proposed change

Apply `goblin-flee.patch` from the repository root after the source freeze lifts:

```powershell
git apply PROJECT_STATE/goblin-flee-20260912/goblin-flee.patch
```

Files changed by the patch:

- `src/main/java/com/hearthstead/event/GoblinThiefDemo.java` — `1.10` cruise and `0.90` tight-turn navigation multipliers; no new cargo-dependent logic.
- `src/main/java/com/hearthstead/client/model/GoblinThiefModel.java` — stage-2 torso is upright while keeping the genuine Coin pouch pose.
- `src/test/java/com/hearthstead/event/GoblinTheftSavedDataTest.java` — extends the existing pure flee-policy test with near-sprint and bounded-brake expectations.

## Proportional validation for root

Run the existing JUnit test `GoblinTheftSavedDataTest#fleeingGoblinBrakesForSharpTurnsThenRecoversSmoothly`, then the existing `naturalPlayerThiefWalksInWindsUpStealsAndFlees` GameTest. The latter continues to prove a real survival player is approached, the 60-tick windup happens, exactly 3 physical Coins transfer, and stage 2 actually begins fleeing. A fresh visual preview or native capture is still needed before claiming the posture looks approved in-game.