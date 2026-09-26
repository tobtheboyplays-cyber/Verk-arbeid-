# Equipment request / workplace storage vertical

## Implemented server truth

- Hiring never creates a tool. FARMER, LUMBERER, GUARD and ARCHER publish one
  deduplicated, persistent request owned by their workplace. Their concrete
  defaults are an iron hoe, iron axe, iron sword and bow, while the accepted
  item tags allow compatible real alternatives.
- A request names priority, status, preferred item plus accepted tool tag,
  count, requester, destination, profession and reason (`MISSING`,
  `WRONG_TOOL`, `WORN`).
- A player fulfils it by putting a compatible, serviceable tool in the job's
  chest. The worker moves that exact physical item chest -> main hand before
  work begins. Wrong/worn held items are never overwritten.
- Courier equipment is the highest request tier and reuses the established
  chest -> bag -> workplace route. It withdraws one tool, leases the row,
  renews while active, returns cargo on failure, and marks `DELIVERED` only
  after the destination chest accepted the real item.
- Farm and lumber output now lands in the job chest. Courier collection only
  takes farm produce / logs, protects farm planting stock, and never mistakes
  requested tools for output.
- Request rows persist in `Building` NBT; claims expire/reopen; reassignment
  and dismissal remove stale rows.
- Farmer/Lumberer work and Guard/Archer combat are gated by the serviceable
  item that is physically in main hand. Reassignment immediately invalidates
  the old combat gate even if the old weapon is still held.
- Missing-tool workplace scans have a bounded 20-tick retry cache (maximum 512
  worker rows). No-op reconciliation does not dirty SavedData. Courier claim
  renewal persists only in the latter half of a lease instead of dirtying the
  world save on every AI tick.

## Courier Request Queue contract

`EquipmentRequestListRequestPayload`, `EquipmentRequestListPayload` and
`EquipmentRequestListNetwork` implement an exact-session, server-authoritative
S2C snapshot. `EquipmentRequestMovePayload` is the only write path: one moved
request id, one server-known before-id, and the exact persisted queue revision.
The snapshot is bounded at 64 rows and carries:

- `priorityWireId`, `statusWireId`, requested `item`, `count`
- `requestId`, `requesterId`, `requesterName`
- `destinationBuildingId`, `destinationType`
- truthful `queuePosition`, `reasonWireId`, settlement/Courier/session identity
- persisted `queueRevision`, controller/read-only authority, server timestamp,
  and the first hidden request id as a safe insertion anchor when capped

`EquipmentRequestQueue` persists the manual order and monotonic revision in the
settlement. New automatic rows enter by urgency/default policy; existing rows
retain their manual relative order. `CourierWorkGoal.findEquipmentJob` and the
UI consume the same ordered list, so `#1` is gameplay truth rather than a visual
sort. Reorder commits validate the exact open inspection session, Courier UUID
and entity id, settlement id, distance, controller authority, revision, active
membership and both referenced request ids. Foreign, duplicate, stale, no-op
and replayed moves mutate nothing.

The payloads are registered in `ModBusEvents` network generation 7. Request
completion advances Development quests only after a real CLAIMED -> DELIVERED
transition. General Courier quest delivery is emitted once per completed route
with the exact number the real destination accepted, never on partial failure,
return or simulated transfer.

### Player-visible Courier integration

- Shift-right-click the Courier, then use the centred `Request Queue` button on
  that Courier's settler sheet. The button does not exist for another job.
- Opening the child screen requests one fresh snapshot. `Refresh` is the only
  repeat request; render never sends packets and draws from an immutable cache.
- Every row displays its real `#1`, `#2`, ... Courier position, physical
  item/count, requester, destination, priority, status and reason. Letter/item
  placeholder tiles are gone; full unabridged values remain in the tooltip.
- An authorized non-spectator can drag the handle or whole row. Other viewers
  receive a read-only snapshot. Rows slide locally during the drag; one packet
  is sent on a valid release. Wheel scrolling still works, edge dragging
  auto-scrolls, and Escape/outside release cancels without a packet.
- Waiting, empty-queue and five-second no-reply states are explicit in English
  and Norwegian. Escape/Back returns to the same settler sheet without closing
  its server inspection session.
- A delayed or unrelated S2C reply cannot open a screen or land on a different
  Courier; `ClientHooks` only updates an already-open matching child view.

## Honest demo boundary

Automated Courier sourcing currently searches valid WAREHOUSE storage. A player
may also fulfil directly through the destination workplace chest. Searching
every settlement/workplace inventory as a Courier source is **not implemented**
yet; broadening that must reserve each source and protect its own inputs/tools,
otherwise the Courier will steal one worker's kit to fulfil another request.

The server test suite includes physical chest-to-hand transfers, wrong/worn
negative paths, reassignment, Guard/Archer combat gates, persistent manual
order, default urgency insertion, exact positions, cap anchors, foreign/no-op
refusal and stale/replay rejection.
The tests were authored in this change set but the final Gradle run is owned by
the root orchestrator so concurrent builds do not corrupt the shared OneDrive
build directory.
