# Hearthstead Raid Showcase

This operator-only QA fixture creates a physical Hearthstead settlement around
the executing player without starting its first raid.

1. Enter a disposable, backed-up world in a broad, flat, unloaded area and run
   `/hearthstead raidqa prepare` as an operator (permission level 2).
2. If the command reports a natural crop or traveler wait, let the world tick
   and repeat `prepare`. Do not move the fixture by running it elsewhere: its
   saved marker deliberately keeps the first actor, origin, and settlement.
3. Save the world only after `/hearthstead raidqa status` reports
   `READY_BEFORE_FIRST_RAID` and `fixtureReady=true`. That saved world is the
   reusable showcase entry point for Tobias and other testers.
4. Walk the settlement and inspect the physical jobs, storage, loadouts, Guard
   Stand order, Archer Tower Post, and the closed west gate. Run `status` again
   at any time for the marker-bound readiness report.
5. When everyone is ready to watch the real encounter, run
   `/hearthstead raidqa start` once. It opens the controlled west gate and calls
   the normal `RaidDirector`; repeating `start` does not create another band.

The GameTest uses physical bone meal through vanilla item interaction because
its vertically isolated test section is not selected for natural random ticks.
This acceleration exists only in the test: the command and survival runtime
still wait for ordinary crop growth and use normal production authority.
