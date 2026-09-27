# Farmer terminal-output recovery — staged only

## Proven live cause

Ansgar's four `Fine carrot` items are tagged to a real `FARM_HARVEST` action
`228afd38-8973-4b8e-85c7-56e5f39a2128`. The action is `WORK_COMMITTED`, has
four produced carrots and zero deposited carrots, and matches the current
settlement, Farmhouse, worker and Overworld dimension. It was committed under
Farm Zone revision 2. The same valid Farmhouse now holds revision 3.

`WorkerProvenanceService.depositOutput` currently requires exact full
`WorkZone` equality for farm output. It therefore returns the unchanged
one-item stack before `WorkerStorageAuthority.insertAt` is reached. The
animation receives `false` at its commit frame and correctly does not shrink
the bag, but its retry loop then remains at frame 47.

The shared Farmhouse/Warehouse boundary chest at `(122, 76, -111)` is within
the passed Farmhouse bounds. `WorkerStorageAuthority` does not consult the
Warehouse and contains no overlap rejection. It is not the cause.

## Proposed production change

`WorkerProvenanceService.depositOutput` gains the narrowly equivalent recovery
already used for terminal Lumber output, limited to terminal `FARM_HARVEST`:

- exact authentic transit action, worker, settlement, Farmhouse ID, source
  position and dimension must match;
- the worker must still be employed by the same currently valid Farmhouse;
- the insert still requires same-tick physical container contact and preserves
  the produced-minus-deposited limit; and
- no foreign, malformed, active or seed-input transit row is accepted.

This allows already-produced physical crops to finish delivery after a Farm
Zone is re-confirmed. It does not start field work, relax tool authority, or
insert before the existing physical storage transaction and receipt succeed.

## Regression added (+1 GameTest)

`terminalHarvestSurvivesSameFarmhouseZoneReconfirmation` creates a real
receipt-backed carrot harvest under Farm Zone v1, advances the same Farmhouse
to v2 with identical bounds, then deposits from the real worker bag at a
physical chest contact. It also attempts a well-formed foreign transit row
first and asserts that it remains untouched. The successful path asserts one
chest carrot, an empty worker bag, a receipt, and zero remaining action output.

## Apply

```powershell
git apply --check PROJECT_STATE/farmer-unload-recovery-20260912/farmer-zone-output-recovery.patch
git apply PROJECT_STATE/farmer-unload-recovery-20260912/farmer-zone-output-recovery.patch
```

No source, server data, build, or GameTest was changed or run while staging.