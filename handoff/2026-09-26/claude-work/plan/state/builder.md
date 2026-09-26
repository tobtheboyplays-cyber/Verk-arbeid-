# Builder lane - state (26 Sep 2026)

Design: plan/BUILDER.md (sections 1-12). Code: settlement/builder/**, client/builder/**,
entity/ai/BuilderWorkGoal, network/Builder*, event/BuilderEvents, gametest/Builder*.

## Evidence
- Compiled: whole shared tree, EXIT 0 (latest after scaffold round 3).
- JUnit: 42/42 green via gradle (builder suites, BuildingLevelChecklist, AuthoredClipAssets,
  JobAttributeProfile, PostRaidUpgradeContract).
- GameTest W3a (06:35): builder_switch passed. builder_core, builder_robust and builder_regress(11) passed EXCEPT palisadeLineWithGate (4 skipped) and twoStoreysWithStairs (1 skipped). Fixed: PLAN_REACH 3.6 stand cells, sorted by distance to the target, and skipReport diagnostics in the failure lines. Waiting on the re-run.
- GameTest (original plan): 31 written. They're queued with the integration captain (ace73d42bf01cec8d)
  in this order: builder_core (7) -> builder_switch (1, run alone, kill switch) -> builder_robust (9)
  -> builder_regress (14, Codex T3b rounds 1-4).
- Seen in game: nothing yet.

## Pending (W1)
- GameTest results from the captain. Fix from the failure lines (they carry the site status and bill).
- Film for the owner: after builder_core passes, ask the captain for the WSL lock slot. Plan:
  a hidden client, a flat world, the Builder's Hut + a Builder + a stocked hut, then place the blueprint
  house_two_storey (blueprint lane, modelled on the server house) and a palisade line with a gate.
  Record the full build to videos/builder/. One JVM, short session, delete the lock afterwards.

## Done since the last note
- W6: builder_core, builder_switch and builder_regress PASS. twoStoreys fixed (the test blueprint staircase was unwalkable). Film slot: after finisher and conversation (captain gives the go).
- W4a: builder_switch and builder_regress PASS. The 3 remaining fails are fixed: the defense line takes ground near the clicked height (no heightmap); stand cells pass up to 64 to the pathfinder; the cottage test checks via its plaque; the test settlement radius is 12. Waiting on W5.
- W3b (full suite, order-dependent): exactlyEnoughLadders stuck UNSCAFFOLDING and aPlayerReplacedRung. Fixed: forgetRung scans all jobs (settlements overlap in the suite); take-down walks to ground off the ladders, with a bounded stuck guard that hands over to a cleanup job. Day-time pin in BuilderTestKit may fight neighbours in a full suite (flagged to the captain).
- BH-20: all BuilderNetwork sends go through PayloadSend (the bug hunter's edit; confirmed).
- Codex round 4: the scaffold cleanup takes every rung (loaded or not). It is admitted past the job cap as system work (addSystem, bound MAX_JOBS+32). The source list is cleared only after it is accepted; otherwise it is retried every upkeep. A job still owning ladders is never pruned, and the load cap was raised to the system bound.

- Film attempt 1 (26 Sep 08:11–08:25, lock released and conversation lane + captain pinged):
  - The setup is OK (hut, Ada hired, 528 + 66 steps), but Ada was wedged for 6 min against the hut front.
  - Root cause: blueprint window-box shelves are closed half=top trapdoors at y+1. Vanilla/RoadNodeEvaluator treats them as passable, but settlers are 1.95 tall.
  - Proof: swapped the shelves for top slabs in-world, and she then loaded and laid the house foundation.
  - 68 of 77 blueprints are affected. Fix proposed to the captain: pathing lane (RoadNodeEvaluator head-height block) and/or blueprint lane (kit.py shelf).
  - Also run/options.txt renderDistance:2 left the house in fog. film_builder.sh now raises it to 6 for the run and restores it on exit.
  - The raw diagnostic capture is in videos/builder/builder-raw.mp4. Re-film after the fix lands and the captain gives a new slot.
- W9 twoStoreys: the Builder never reached the upper floor. Vanilla tryJumpOn needs 3 clear blocks over the column the settler steps from.
  - The test stairwell was widened over the approach cell (2,3,3). Waiting for the captain's builder_robust re-run.
  - The same rule breaks house_two_storey: (3,4,2) blocks stair 1→2. Handed to the captain/headroom lane (the lead's headroom lane also owns the trapdoor fix).
  - Re-film: the captain requeues me after the headroom fix lands.

- Film take 2 (11:40–11:51):
  - The headroom fix works: Ada built the foundation, floor and ground walls. See videos/builder/builder-take2-house-walls.mp4.
  - Then she wedged on a chair (stair) used as a work spot.
  - W10 fix (compiles green; waiting for the captain's builder_ run):
    - stand cells need a whole floor (fullFloor);
    - unwedge guard: a route stuck 100 ticks → step off to the nearest whole-floor cell.
  - Film script: TERM trap, case-insensitive palisade grep. Take 3 is waiting for a new slot.

- 26 Sep afternoon (owner: "she takes a lot of breaks"):
  - BuilderUtilisation measures place / move_site / carry / scaffold / wait_material / stall / other_goal; /builderstats prints it.
  - Fixes:
    - nearest-first within a layer;
    - run pace 6 ticks (first block 10);
    - load 64;
    - courier pre-request while building;
    - 60-tick stall guard.
  - GameTest builder_util: cottage, >=70% utilisation, pause <=80 ticks.
- Presets UI: one row per building, preset chips, 3D preview (BlueprintThumbnail), materials from the preview, Town style toggle.
  - BlueprintStyles.install hook for the artist's TownStyle.
- Water: F_POUR (2 buckets per body, INTERIOR; the rest free in the next phase); fishery never drained. GameTest builder_water.
- Private project copy: C:/Users/tobia/Hearthstead-Claude/builder-private (own git repo for the build's identity check).
  - Compile there, then copy into shared: new files first, lang via targeted insert. Never sed -i on CRLF files.
- Waiting: captain runs builder_util / builder_water / builder_*; then the take-3 film.

- WSL rules (Main, 13:05):
  - never `gradlew --stop`;
  - take the lock with `( set -o noclobber; echo "$ME" > $L )`;
  - release only if `[ "$(cat $L)" = "$ME" ]`;
  - get slots from the captain.
  - film_builder.sh only kills its own PIDs (Xvfb :78, hsc-bld-build client, its gradle wrapper).

## Open items
- Clip polish (BUILD_HAMMER knee dip, CARRY_PLANKS weight) is handed to the animation lane
  (aa945c9f20dced2d9). Author with the Blender 4.5 copy; Blender 5.2 breaks hsrig fcurves.
- The map lane (acd0a5a18e727f5b9) renders the Banner-screen construction rows and work orders from
  BuildSitesClient.
- The blueprint lane (abe4f8c266eb4b115, paused) has 77 blueprints on disk. Decorations should use
  kind:"decoration". Its BlueprintCatalogGameTests are its own.
- Codex continues the T3b review of the remaining paths. Route findings here.
- Known limits:
  - Scaffold = ladder column on built wall (the pathing lane doesn't climb vanilla scaffolding).
  - REORIENT on door/bed pairs goes through clear/overwrite.
  - A pair with a player block in the companion cell blocks.
- Builder plan drafting is a flat paper + feather + oak plank for every type, and Survival QA logged it as balance-only (not an exploit). The Builder's Hut hand recipe is now paper + feather + crafting table (QA lane).
- Design delete is owner/op only (Q-007, coordinator OK).
