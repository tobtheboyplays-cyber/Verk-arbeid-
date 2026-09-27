# Daily Tavern visitors — staged only

This is an unintegrated proposal. It intentionally did not change live source,
assets, QA scripts, a running server, or the urgent deployment candidate.

## Behaviour

Natural (`survivalAuthored`) recruitment that reaches `READY_TO_SPAWN` waits
for the 11000–15000 evening-to-night window. A real `spawnSettler` publication records
the game day and its already-validated outside feet cell in the settlement save,
then sends the existing one-per-success broadcast using the exact copy `New
travelers in town!`. A failed origin or cancelled entity publication records
nothing, so it may retry only during that evening window.

The exact persisted `WAITING_ADMISSION` traveler may reserve and sit at its
locked valid Tavern without an Innkeeper. That does not authorize serving:
`TavernHostService`, paid Coin transfer, stock ownership, and Hearth admission
remain their existing authorities. Residents still require an Innkeeper.

At 15000 the traveler leaves a seat through the existing collision-safe release
and changes to persisted `DEPARTING`. The registered `TravelerJoinGoal` reuses
its existing bounded Vanilla navigation legs to the saved exterior origin. The
terminal discards only after the actual traveler reaches that origin outside the
settlement radius; unloaded traveler/origin chunks pause. There is no table
despawn, target teleport, new movement framework, reroll, free bed, or free
Coin path.

`LastTavernVisitorDay` and `TavernVisitorDepartureOrigin` are optional
Settlement NBT fields, so existing saves default safely. `DEPARTING` and
`VISIT_COMPLETE` are explicit preserved wire values. The recruitment quote and
locked Tavern transaction are untouched. The explicit admin prime command
remains immediate rather than silently changing an existing operator tool.

## Included test and acceptance

`RecruitGameTests.ordinaryTavernVisitorPublishesOncePerEveningAndPersistsTheDayLock`
creates a natural candidate, proves it does not publish before 12000, proves the
evening publication is a real outside traveler, then round-trips Settlement NBT
and proves the persisted day/origin prevent a duplicate within that evening.
The existing wall-plaque traveler test updates its assertion to permit this exact
unstaffed visitor to seek a seat.

Unrun (source/QA freeze and user-requested deployment without automated
gameplay):

```text
bash tools/hearthstead-qa gametest --selector recruitgametests.ordinarytavernvisitorpublishesoncepereveningandpersiststhedaylock
```

Before integration, run a controlled server scene through arrival, seat reserve,
unloaded-origin pause/reload, 15000 seat release, physical outside return, and
next-day publication. A green compile or isolated GameTest does not prove that
player-visible route.

## Apply

From `hearthstead-neoforge`:

```text
git apply --check PROJECT_STATE/daily-tavern-visitors/daily-tavern-visitors.patch
git apply PROJECT_STATE/daily-tavern-visitors/daily-tavern-visitors.patch
```

The staged baseline-directory apply check passed; no build, QA, GameTest, server,
or client was run.
