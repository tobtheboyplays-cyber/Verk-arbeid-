# WITHDRAWN — do not apply

The prior `RequestLedgerService.patch` proposed bypassing generic in-transit
validation for FOOD. It is incorrect and must not be applied.

`RequestLedgerService.containerAtLoaded(...)` explicitly adapts a
`HearthBlockEntity` to a live `SimpleContainer` view (current source
lines 2267–2292). FOOD therefore already passes the target-container check.
The interrupted Courier evidence does not prove a ledger endpoint failure.

The patch file is deleted. No active source patch is proposed here. Any further
diagnosis must trace `CourierWorkGoal` → `CourierFoodBagSession.begin/tick` →
`RequestLedgerService.deliver`, including persisted request and presentation
state at the failure.
