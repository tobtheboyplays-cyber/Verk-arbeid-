# Bannerhold — continuation snapshot, 26 September 2026

This branch contains the current Minecraft NeoForge project plus a QA and Claude-task handoff requested by Tobias. It is a **draft working snapshot**, not a release. Internal mod ID remains `hearthstead`.

## Read in this order
1. This file.
2. `handoff/2026-09-26/CLAUDE_TRANSCRIPT_HANDOFF_2026-09-26.md` — ten interrupted task transcripts: landed work and remaining checks.
3. `handoff/2026-09-26/BANNERHOLD_QA_REPORT_2026-09-26.md` — reproducible findings and evidence limits. Full GameTests are still running locally at this initial publication; the report will be updated.
4. `COORD/DECISIONS.md` and the latest applicable ownership/task messages.
5. Only the specific source and tests needed for the next bounded fix.

## Identity and evidence
- Project: `hearthstead-neoforge`; Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21.
- Source taken from Claude's shared integration checkout, whose HEAD was `95425795c2909fea7f5b94bc3397e420f8e683e3`, including its uncommitted implementation.
- Per-file source identity is `handoff/2026-09-26/current-verification-manifest.json`: 4126 source files, every digest checked against this export.
- Fresh isolated compile passed. JUnit: **1150 run, 1144 pass, 6 fail, 0 errors/skips**. XML-derived failure summary is included.
- The earlier full GameTest run uses an older snapshot. In particular, raider heavy duration was 1.8 then and is 2.2 now; the new duration unit test passes. Do not revert the new clip based on an old failure.
- No claim that all controls were clicked, audio quality was listened to, animations were approved in game, or real two-client co-op passed. CSV inventories are not execution evidence.

## Build and test in an isolated checkout
Install Java 21. From `hearthstead-neoforge`, run `./gradlew test` on Linux/macOS or `./gradlew.bat test` on Windows. First setup needs network dependencies; use `--offline` only with a populated cache. Use `-PhearthsteadBuildDir=<absolute-private-output>` where concurrent agents share a workspace. The build requires Git and a real checkout to generate its artifact identity.

For GameTests, use your own disposable checkout AND world directory. Never use another agent's shared `run/world`, a real player save, or the owner's server. Native/client testing requires a suitable display and game runtime; server-side GameTests do not replace it.

## What to do next
1. Inspect current full-suite results when the local report update arrives; preserve per-snapshot provenance.
2. Fix the confirmed Builder material selection/load-window mismatch (QA-BUILD-01), with item-conservation and boundary regression cases.
3. Fix current handbook index/data omissions; distinguish stale tests from gameplay defects.
4. Resume the interrupted task-specific checks in the transcript ledger. Check integration results before repeating queued work.
5. Keep a compact record of exact changes, command results, and outstanding native/co-op checks. Submit changes for Claude's review.

## Export boundaries and relocated context
Source, resources, build scripts, authored module tools/docs, coordination text, QA summaries, and relevant Claude plans are included. Extra Claude plans/generators live under `handoff/2026-09-26/claude-work/`; old absolute Windows paths in those historical notes refer to the original machine. They are not paths to use in a cloud checkout.

Raw Claude transcripts, personal worlds, caches, credentials, private server configuration, videos and downloaded third-party mod JARs are excluded. Download optional mods from their documented upstreams when running compatibility tests. Their staged integration plan is not a passed compatibility result.

Original `AGENTS.md` / `COORD` describe the shared local workspace. Preserve that workspace when operating locally; this exported branch is an independent continuation snapshot. Tobias explicitly authorized this branch, draft PR and issue. Do not deploy, merge, or claim release readiness from this snapshot.
