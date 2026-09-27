# Courier route diagnostics — staged only

Status: source freeze respected. This directory contains no active source edit and no QA was run.

## Evidence read

The supplied archived artifact directory is not currently present in this checkout. The matching current \`run/logs/latest.log\` contains the three terminal failures:

- \`restockOutranksAHungryHearth\`: smithy restock completed, Hearth remains 0; Courier \`TRAVELING\`, no route failure.
- \`gatheredCodReachesAWarehouseAndFeedsAHungrySettler\`: fishery 2 / Courier bag 8 / warehouse 0 / Hearth 0; Courier \`TRAVELING\`, phase \`MEAL\`, lifecycle \`INTERRUPTED\`, navigation done, with an active task reference.
- \`mayorPhysicallyHaulsAndRetainsOfficeAcrossSettlementReload\`: Hearth retains all 4 logs; Mayor is idle after a \`TO_HEARTH\` rest with no cargo.

These are distinct observable states. They do not prove one shared Courier behavior bug.

## Staged diagnostic

\`courier-route-diagnostics.patch\` adds only read-only timeout witnesses:

1. The actual running vanilla goal classes from \`goalSelector\`.
2. Every installed \`CourierWorkGoal\` instance and its internal \`mode\`, job, source/target and request identities.
3. Navigation done/target, worker lifecycle/task.
4. Raw persisted NBT for source, food and Hearth sessions.

The exact three failure assertions receive this witness. It is intentionally a diagnostic-only patch; it does not repair sessions, change priorities, or change timeouts.

## Additional code finding — not applied

A source session at \`Clock=24\`, no cargo, and a ledger row changed to \`BLOCKED\` is currently retained: \`CourierWorkGoal.canUse()\` sees an active source session, the new Wilmot reconciler deliberately rejects non-zero clocks, then \`sourceBag.request()\` rejects the blocked row and returns false before generic \`routeForCourier()\` runs. This is a real analogous control-flow hole, but it is not yet tied to any of the three failures. Do not broaden the Clock=0 Wilmot reconciliation until the staged witness confirms that exact state in a failing fixture.

