# Lumber: bounded final leaf approach

## Evidence and confidence

**Fact:** Dunstan has an authenticated active `LUMBER_TREE` action in Work Zone revision 5 for tree base `(98, 74, -94)`. His UUID-derived stand is north at `(98, 74, -95)`. From the reported last position `(99.82, 73, -97.50)`, the horizontal final approach is about 2.83 blocks. Production clearance only scans 0.85 blocks.

**Inference:** A natural collision leaf in that fixed final lane can stop route progress before clearance is close enough to run. The actor was no longer loaded during the read-only RCON check, so this does not claim a particular live leaf coordinate.

## Scope

`LumbererWorkGoal` receives a three-block maximum only while `TO_TREE` is moving toward the active claimed tree's UUID-derived stand. Ground-output collection retains its 0.85-block clearance. Existing gates remain: named work action and claimant, authoritative live work zone, collision with the exact forward corridor, non-persistent `LeavesBlock`, no registered building, one leaf per ten ticks, physical axe swing, and route recomputation.

## Regression coverage

Adds one real `lumberer_leaf_access` GameTest. It hires a genuine Lumberer, makes a UUID-fixed north approach, and holds the first production tick at an initial body position exactly 2.5 blocks before the natural blocking leaf. The test requires the claimed production goal itself to break that leaf; then ordinary AI resumes the same authenticated action and must visibly reach `WORK_CHOP`, fell and physically return all four logs. A natural leaf outside the three-block lane remains. Existing cardinal leaf-route tests retain their persistent player-leaf and stone-wall assertions.

## Apply

```powershell
git apply --check PROJECT_STATE/lumber-leaf-approach-20260912/lumber-leaf-final-approach.patch
git apply PROJECT_STATE/lumber-leaf-approach-20260912/lumber-leaf-final-approach.patch
```

No QA was run by this staging task.
