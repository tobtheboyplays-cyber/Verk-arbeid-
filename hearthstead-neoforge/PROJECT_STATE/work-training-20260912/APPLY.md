# Work training and honest job effects — staged patch

## What is actually happening

Training is not absent for Courier or Lumberer, and it is not awarded for idling:

| Job | Existing committed endpoint | Current credit | What the player sees today |
| --- | --- | ---: | --- |
| Courier | A real bag stack is inserted into a warehouse, request target, or Hearth | Stamina 1.0 | Whole integer only |
| Lumberer | A log falls after its authenticated felling action commits | Strength 1.0 | Whole integer only |
| Farmer | A physical seed is consumed after the plant transaction commits | Dexterity 1.0 | Whole integer only |

`SettlerAttributes.train` persists fractions in `Attributes.ProgressBits`. The unboosted gain from a stat of 5 is `0.05 × 0.95² = 0.0451` per work unit: about 23 one-unit actions per visible point. It intentionally slows as the stat rises; the class contract estimates 5→25 at roughly 550 units and 5→50 at roughly 1,800.

The settler sheet does **not** receive `trainingProgress`: `SettlerNetwork.snapshot` only sends whole `attributeValues`. This is an omission of the partial progress from the client view, not arithmetic rounding that destroys it. The stored fraction survives save/reload already.

## Verified mismatch

The job cards call Stamina a core attribute for both Lumberer and Farmer. Neither corresponding production goal currently awards Stamina. This is a real progression gap:

- Lumberer has Strength credit on a committed log but no Stamina credit.
- Farmer has Dexterity credit on a committed plant but no Stamina credit.

## Patch contents

`work-training.patch` changes five files and nothing else.

1. **Committed work only**
   - Lumberer: `+0.5 Stamina` only when the log has fallen at the existing physical commit endpoint.
   - Farmer: `+0.5 Stamina` only after crop removal, provenance receipt, and physical output have all succeeded.
   - Existing Courier, Lumberer Strength, and Farmer Dexterity endpoints/rates remain unchanged.
   - At the same low-stat baseline, each new 0.5 credit is about 45 completed logs or crops per visible +1 Stamina. There is no timer, movement-distance, waiting, or failed-route credit.

2. **Honest primary / secondary cards**
   - First core card reads `PRIMARY`; second reads `SECONDARY`.
   - Stamina's green line is calculated from the live engine formula and current Energy: `Movement now NN% · floor NN%`.
   - Lumberer Strength retains the live calculated `hits · carry` line.
   - A profile slot without a runtime consumer now says `No live bonus` in muted text instead of presenting a planned role preference as a buff. This specifically avoids claiming that Courier Strength adds slots: the current item/weight trip budget is not updated from Courier Strength.
   - No percentage movement bonus is fabricated. The displayed percentage is the actual `JobEffects.workPace` multiplier that `SettlerEntity.applyFatigueSlow` applies to movement speed.

3. **Focused unit coverage**
   - Adds one JUnit case proving a `0.5` committed-work credit remains fractional and exact after save/load.

## Deliberately not in this patch

A later UI transport change is required to show `ProgressBits` as “NN% to next point” beside each attribute. It needs a versioned `SettlerSnapshotPayload` field and bounds/round-trip tests; this patch does not silently read a client-side rolled attribute or fake server progress. The staged unit test proves the server persistence which that future payload must expose.

## Apply and validate

From the mod root after the source freeze:

```powershell
git apply PROJECT_STATE/work-training-20260912/work-training.patch
```

Patch applicability was checked with `git apply --check`; no build or QA was run by this task.

Suggested root checks:

1. `SettlerAttributesTest#partialCommittedWorkPersistsExactlyAcrossSaveLoad`.
2. Existing Courier/Lumberer/Farmer physical-work GameTests, with narrow assertions added only if the integration branch needs endpoint-specific regressions.
3. A client UI capture at low Energy and full Energy before describing the green Stamina text as visually approved.