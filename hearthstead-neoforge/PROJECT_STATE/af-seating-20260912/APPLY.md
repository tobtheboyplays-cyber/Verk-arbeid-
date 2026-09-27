# Another Furniture seating route — staged only

## Scope

- `src/main/java/com/hearthstead/entity/TavernSeatMotion.java`
- `src/main/java/com/hearthstead/gametest/TavernSeatingGameTests.java`

No active source, asset, test, Lumber, or Tavern-service files were changed.

## Physical contract

For the installed `another_furniture-neoforge-4.0.2.jar`, an untucked north-facing chair has a back at local `z=12..14/16`, and the matching table has a top at `y=13..16/16` plus its corner legs. The table-facing / chair-back gap is therefore the only usable seated-torso lane.

The production torso is `5/16` blocks deep. In the loaded test arrangement (chair `(6,1,7)`, north-facing table, east aisle), while the torso overlaps the table height its origin must stay strictly between:

```
7.0 + 2.5/16 = 7.15625
7.75 - 2.5/16 = 7.59375
```

The existing final articulated origin was `z=7.625`, outside that lane; the saved seat anchor is `z=7.561338998...`, inside it. This accounts for the reported tick `19.5` torso/chair-back collision and the final visual snap.

The patch translates the **whole already-solved frame** toward `dinerFacing` by
`0.25 - sqrt(5)/12 = 0.063661001875...` during the existing first eight-tick 7/16 seat-lift phase. It does not change the rigid lift, foot/hip link geometry, endpoint anchor, timer, collision predicate, or any Vanilla/Lumber branch. Moving feet with the hips preserves the existing IK relative coordinates.

## Staged verification

`route-analysis.py` is a direct numerical port of the production frame/mesh transforms. It tested all 89 half-tick samples against the exact chair solid components and the table top plus all four possible table legs. Its conservative mesh AABB check reported zero overlaps for the staged route. AABB separation is stronger than the production oriented-box test for proving non-overlap.

The staged GameTest adds the missing assertion that `sample(site, TICKS).origin()` equals `seatedAnchor(site)`. The existing installed-furniture GameTest remains unchanged in its strict half-tick `TavernSeatMotion.clear(...)` loop and still calls the real entry path; the checker has not been weakened.

`git apply --check PROJECT_STATE/af-seating-20260912/af-seating.patch` passed. No build or QA was run during the freeze.

## Apply

```powershell
git apply PROJECT_STATE/af-seating-20260912/af-seating.patch
```

Then use the existing canonical Tavern compatibility GameTest batch with Another Furniture installed; it covers actual loaded collision shapes, entry, settled anchor, and the unchanged collision predicate.
