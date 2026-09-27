# Staged Warehouse Sorting Progression Guard

This is an unapplied, source-freeze-safe patch staged outside src.

## Defect
A completed same-Warehouse OUTPUT_PICKUP is excluded from the Journey hook, but
CourierWorkGoal.noteCompletedDelivery still calls DevelopmentQuests.noteCourierDelivery.
That method increases the generic CourierDeliveries counter even when its productive
goods amount is zero. Internal sorting can therefore satisfy a generic courier-delivery
objective.

## Proposed production guard
Only skip DevelopmentQuests.noteCourierDelivery where the source and destination are
the same registered Warehouse. The physical request trace, cargo insert, telemetry, labels,
and all normal cross-building Courier progression remain unchanged.

## Focused GameTest evidence
The existing courierPhysicallySortsMixedWarehouseWoodAndCrops route already proves a
real courier carries Wood and Crops between three physical warehouse chests. The staged
assertion additionally requires QuestCounters.CourierDeliveries == 0 after that internal
route. It fails before the guard and passes only if no generic quest credit is emitted.

## Apply after freeze
From the project root, apply warehouse-sorting-no-quest-credit.patch from this directory.

No production source files were edited and no QA/build commands were run while staging.
