# COORD — Claude ⇄ Codex coordination (Bannerhold / Hearthstead copy)

Owner (Tobias), 25 Sep 2026: Claude and Codex work in the SAME folder
`C:\Users\tobia\Hearthstead-Claude\Verk-arbeid-` on the same mod. **Claude is the lead**
(integration, design calls within the owner's decisions, file ownership, the one QA queue).
Codex is a peer builder/reviewer. This folder is where we talk and agree.

## Files here
| File | Who writes | Purpose |
|---|---|---|
| `README.md` | Claude | These rules |
| `DECISIONS.md` | Claude | Owner decisions that override older docs (AGENTS.md, PROJECT_STATE, REQUIREMENTS) |
| `OWNERSHIP.md` | Claude | Who may write which files/areas right now |
| `TASKS-CODEX.md` | Claude (Codex ticks status) | Codex's assigned tasks, acceptance, allowed files |
| `to-claude.md` | Codex (append only) | Messages/questions/results from Codex |
| `to-codex.md` | Claude (append only) | Messages/answers from Claude |

Message format (append, newest at bottom):
```
## 2026-09-25 23:59 — codex — <short subject>
<body: what, files, evidence level, question if any>
```

## Hard rules for everyone in this folder
1. **Claim before you write.** Only edit files in areas `OWNERSHIP.md` gives you. Need something
   else? Ask in `to-claude.md` and wait for the answer in `to-codex.md`.
2. **Never run git commands that change the working tree or history**: no checkout/switch,
   reset, stash, restore, clean, rebase, merge, commit, push, branch deletion. There are 1000+
   uncommitted changes from ~20 parallel agents. Read-only git (status, diff, log, show) is fine.
3. **Shared files** (`en_us.json`, `HearthsteadServerConfig.java`, `SettlerEntity.java`,
   `build.gradle`, `sounds.json`): targeted small edits only, re-read immediately before editing,
   never rewrite the whole file from a stale copy.
4. **Line endings**: preserve each file's CRLF/LF exactly (many Java files are CRLF, JSON is LF).
5. **Build** only with a private build dir:
   `./gradlew compileJava test --offline -PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-codex`
   A compile error in a file you don't own is probably another agent mid-edit — wait/retry,
   don't "fix" it.
6. **GameTests**: never run `runGameTestServer` in this folder (shared `run/world`). Ask Claude;
   Claude runs the full suite in one serialized queue.
7. **Never** touch the owner's server (`C:\Users\tobia\Hearthstead-Server`), real saves, or
   `world-copies/` masters. No publishing, pushing, purchases.
8. **Evidence levels** in every report: Implemented / Compiled / JUnit / GameTest / Seen in game.
   Never claim done without proof.
9. Code, identifiers and docs in English. The owner gets short Norwegian summaries (Claude relays).
