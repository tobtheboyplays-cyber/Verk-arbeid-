# Hearthstead real-client release gate

This gate is stricter than the ordinary `full` QA suite. A green build,
GameTest run, static render, scripted screenshot run, or server performance
matrix makes a change a **Candidate**. It does not make it **Approved**.

`Approved` means that the exact JAR was exercised in a real Minecraft client,
with player-equivalent input, and that every required observation below has a
fresh evidence file whose source fingerprint and JAR hash match the release.

## Non-negotiable verdict rules

- Do not call server-console commands player input. Console commands may
  stabilise weather, create an arena, query authority, or accelerate the
  clock. They may not stand in for opening a screen, clicking a control,
  handing over an emblem, opening a settler inventory, equipping a worker,
  directing a job, attacking a raider, or accepting a reward.
- Do not call a posed settler a working settler. `hearthstead pose`, `pulse`,
  and `lineup` are framing tools only. Worker approval requires the real AI
  activity and real inventory transaction that normally trigger the clip.
- Do not infer success from a screenshot. Every state-changing player action
  needs a server-authoritative observation after the physical input.
- Do not infer smoothness from an encoded recording. UI performance needs
  client frame-time samples. Video remains the evidence for visual motion,
  transitions, clipping, and sound/contact review.
- Do not approve audio from the WSL/Xvfb playtest. That suite deliberately
  exports `ALSOFT_DRIVERS=null`, so it cannot prove the live mix.
- A failed interaction is not repaired by blindly clicking again. Re-aim,
  prove the client pick ray or screen state, then retry once with both attempts
  recorded.
- Any visual, sync, audio, authority, crash, or performance defect returns the
  feature to Candidate and invalidates its previous in-game approval.

## Required evidence identity

Create one directory under
`qa/reports/artifacts/release-client/<UTC timestamp>/` containing:

- `manifest.json`: source fingerprint, Git commit, dirty hash, exact JAR path,
  JAR SHA-256, Minecraft version, NeoForge version, Java version, renderer,
  the exact ordered `displayProfiles` and `languages` rosters, primary display
  profile/GUI scale/language, native observer session/activation source, world
  seed, strict UTC start/finish timestamps, and matching wall-clock duration.
  It also names the exact native game directory and save directory. For this
  release they must resolve to
  `C:\Users\tobia\curseforge\minecraft\Instances\SIVILASJON (1)` and one
  direct child of its `saves/` directory; the installed JAR must be a direct
  child of that exact profile's `mods/` directory. `nativeLogSegments` seals
  the helper-owned `logs/native-log-segments.json` index and its SHA-256.
  Schema v3 also requires `nativeInputTranscript`, which seals the canonical
  `logs/native-input.jsonl` SHA-256, record count, tail hash and exact
  `windows_sendinput_v3` driver identity, plus `nativeLaunchRegistry`, which
  seals `logs/native-launch-registry.jsonl`, its count and tail hash.
- `result.json`: schema-v2 record for every machine-owned matrix row. Every row
  carries the same native session/world, strict observation/review timestamps,
  exact observed profiles/languages, explicit authority/audio/frame/restart
  verdicts where required, and row-specific evidence locators.
- `reproduction.md`: exact commands plus the ordered physical input script. It
  records `HSQA_CLIENT_SESSION`, `Display profiles`, `Physical input`, and the
  literal `progressionCommandsUsed: []`; its SHA-256 is sealed in the manifest.
- `logs/`: immutable helper-sealed copies of the exact CurseForge profile's
  integrated-client `logs/latest.log`, `native-log-segments.json`, frame-time
  reports, the complete hash-chained `native-input.jsonl`, and any crash
  report. In singleplayer, genuine Render-thread client
  ACKs and genuine Server-thread authority events come from the same Log4j
  launch segment; do not invent or relabel a separate dedicated-server log.
- `shots/`: full-frame PNG before/contact/after screenshots for every UI or
  physical transfer. The PNG dimensions must match its declared native profile;
  one generic image may not satisfy two rows.
- `film/`: a dedicated, decodable 29-fps-or-better MP4 excerpt plus PNG
  contact sheet for every required row. A continuous source recording may be
  retained for audit, but each matrix row references its own row-labelled
  excerpt with a unique content hash; renamed/copied generic clips fail.
  Uniqueness is calculated from decoded RGB frames at fixed 30-fps timestamps
  and fixed geometry, not container metadata or compressed packets, so a
  remuxed or losslessly re-encoded copy still fails.
- `audio/`: decodable 44.1-kHz-or-better stereo WAV native loopback capture and a
  short note identifying the heard event, contact timestamp, clipping verdict,
  and relative level against footsteps and vanilla UI sounds. Each required
  row references a dedicated, uniquely hashed excerpt and embeds the unedited
  PASS JSON printed by `capture_windows_loopback.py`; its output path, device,
  channel/rate/frame counts, requested duration and wall time must agree with
  the WAV and manifest. Each located PCM range must be non-silent and below the
  digital-clipping fraction gate.
- `world/`: a structured before/after restart manifest plus a hashed, readable
  ZIP of the exact save containing `level.dat`. Settlement, building, settler,
  development, raid, Blessing, guard-XP and inventory identity must match.

The gate fails closed if any required file is missing, the JAR hash differs
from the installed copy, the source changes during the run, or a required
screen/action has no server corroboration.
The finish timestamp may be at most five minutes ahead of the validating host
and must be no more than 24 hours old; every row, evidence timestamp, and
runtime ACK must lie inside that same interval.
Every sealed launch segment, consumed-marker creation time, ACK observation,
source-log modification time, and sealing time must also lie inside it.

The required row roster is machine-owned by
`qa/release_client_matrix.json`. After completing the native run, validate the
evidence directory against the **current frozen** source fingerprint through
the canonical controller:

```bash
./tools/hearthstead-qa release-client-gate <run>
```

The validator hashes and opens both the candidate and separately installed
Hearthstead JAR, hashes every referenced evidence file, decodes native media,
binds every frame-report acknowledgement back to an identical acknowledgement
in its hashed source client log, and requires every matrix row to say
`APPROVED`. A render-thread `HSQA_FRAME_ACK` must report the hash of the JAR
actually executing, its strict installed-JAR resolution source, native Windows,
the current QA session, approved observer activation source, language,
framebuffer and GUI scale. It also reports only SHA-256 tokens for the canonical
game directory, exact runtime-JAR path, and exact integrated-server save path,
plus the runtime observation epoch. The validator derives the same tokens from
the manifest paths; raw user paths are not written to the client log. Hashing a
JAR on disk after playing is not runtime proof.
`CANDIDATE`, `FAIL`, missing, duplicated, extra, stale-fingerprint, synthetic
progression, malformed, empty, symlinked, or hash-mismatched evidence all fail
closed. This validator is the final approval authority; the ordinary `gate`
command remains the headless/full-suite authority only.

The native input trace is not a handwritten attestation. Every state-changing
test action is sent as ordinary foreground Windows `SendInput`. The helper
first binds the exact HWND/PID to a separately registered launch identity:
kernel process-creation time, full Java executable path, exact game directory,
runtime JAR and SHA-256, world, observer-log segment, display profile and fresh
launch nonce. Window title and class are only a secondary allowlist. Each
individual `SendInput` event revalidates the same foreground HWND/PID and
launch registry immediately before and after insertion into the Windows input
stream. `SendInput` itself has no target-HWND parameter, so this is a strict
foreground bracket rather than a claim of atomic OS routing; the supervised
native run must be abandoned if any focus theft is observed. Held keys are bounded
pulses. If focus is stolen or delivery is partial, cleanup sends a key-up only
after the exact target has been proven foreground again; otherwise it sends no
input and leaves an unmatched `INTENT`, which invalidates the run. There is no
generic hotkey, clipboard, paste, Ctrl, Alt or Tab route. Text exists only for
one 1-32 byte ASCII settlement name, and the exact cleartext is sealed.

`windows_native_input.py` records the launch identity, verified
HWND/PID/client bounds, action, monotonic sequence, wall-clock time and exact
postcondition in a full-file SHA-256 chain. Every action is a consecutive
`INTENT`/`COMPLETED` pair; no `COMPLETED` is written without exact foreground
and delivery postconditions. The gate recomputes every link, rejects field
drift or mutation, requires at least 100 completed actions (200 records), both
client PIDs around the mandatory relaunch, held movement, mouse look, UI
scroll, settlement-name typing, left/right click and an atomic
Shift-right-click. Minecraft memory, background-window messages and
gameplay/server commands are not available through this helper.
The transcript and launch registry must be the two canonical files in the same
`<release-root>/<direct-run>/logs` directory. An input timestamp must follow
that launch's durable registration and precede its immutable log-segment seal.

### Native input precommit, launch registry and external seal

Before the first native input, generate a random 32-byte-or-longer operator
key outside the run artifact and keep it separate from the evidence directory.
Also generate one fresh 64-lowercase-hex session nonce. With the installed JAR
and fresh world already present, print the canonical precommit line:

```powershell
py -3 qa\scripts\native_release_session.py input-precommit `
  --session <same-native-session> `
  --session-nonce <fresh-64-lowercase-hex> `
  --run-directory <absolute-release-client-run-directory> `
  --operator-key-file <absolute-external-operator-key> `
  --runtime-jar <absolute-installed-hearthstead-jar> `
  --world-directory <absolute-fresh-world-directory>
```

Publish the resulting single `HSQA_INPUT_PRECOMMIT_V1` line in the external
review chat **before any input is sent**. It pins the operator-key SHA-256,
native session, session nonce, canonical run-directory token, exact
installed-JAR SHA-256 and world id outside the mutable run. Never publish the
key itself. The validator checks the posted values, while the external chat's
own earlier timestamp remains the human-reviewed proof of precommit chronology;
the run artifact cannot manufacture that chronology by itself.

After each Minecraft launch, identify the live client PID using the helper's
read-only `list` command, then register that launch before any other driver
action:

```powershell
py -3 qa\scripts\windows_native_input.py list
py -3 qa\scripts\native_release_session.py register-launch `
  --session <same-native-session> `
  --run-directory <absolute-release-client-run-directory> `
  --pid <exact-live-minecraft-pid> `
  --launch-nonce <fresh-16-to-96-safe-ascii-launch-nonce> `
  --display-profile 1280x720-gui3-en_us `
  --runtime-jar <absolute-installed-hearthstead-jar> `
  --world-directory <absolute-fresh-world-directory>
```

Every state-changing `windows_native_input.py` action then requires the exact
`--hwnd`, `--session`, `--launch-registry`, `--launch-nonce` and canonical
`--transcript <run>\logs\native-input.jsonl`. `list` is the only command that
may be used without a registered launch; `inspect` is read-only but is not
evidence and must not replace registration. Seal `latest.log` before stopping
each launch as described below.

After the final input and final log seal, append the independent operator seal
using the **same** externally published session nonce:

```powershell
py -3 qa\scripts\native_release_session.py seal-input `
  --session <same-native-session> `
  --session-nonce <same-published-64-lowercase-hex> `
  --run-directory <absolute-release-client-run-directory> `
  --input-seal <absolute-external-append-only-seal-ledger> `
  --operator-key-file <absolute-external-operator-key> `
  --operator-id <reviewer-id>
```

This HMAC ledger is tamper-evident only while the external key remains
separate; it is **not** an honest claim of OS-level immutability. The external
precommit line supplies the independent time anchor. Missing, in-run,
symlink/reparse-backed, rewritten or mismatched key/seal/precommit data fails
closed. Run the canonical gate with all externally posted values repeated
exactly:

```powershell
./tools/hearthstead-qa release-client-gate <run> `
  --input-seal <absolute-external-append-only-seal-ledger> `
  --operator-key-file <absolute-external-operator-key> `
  --expected-operator-key-sha256 <published-key-sha256> `
  --expected-precommit-session-nonce <published-session-nonce> `
  --expected-precommit-run-directory-token <published-run-directory-token> `
  --expected-precommit-jar-sha256 <published-installed-jar-sha256> `
  --expected-precommit-world-id <published-world-id>
```

No separate key, external seal and exact precommit match means the run cannot
be Approved.

### Native observer activation

Prefer launching the native client with both `HSQA_CLIENT_OBSERVER=1` and one
fresh 8-80-character `HSQA_CLIENT_SESSION`. Because CurseForge can interpose a
launcher that drops inherited environment variables, the exact installed game
directory also supports a one-shot fallback named
`hsqa-native-observer-once.txt` with exactly these four lines:

```text
HEARTHSTEAD_NATIVE_QA_V2
session=<the same unique session id>
createdEpochSeconds=<current UTC epoch, no more than 300 seconds old>
expiresEpochSeconds=<UTC epoch after now and no more than 3600 seconds after created>
```

The marker must be a plain non-symlink file no larger than 256 bytes. Do not
create it with an ad-hoc shell redirect. From the repository root, use the
path-pinned helper immediately before each CurseForge launch:

```powershell
py -3 qa\scripts\native_release_session.py create-marker `
  --session <same-8-to-80-character-session> --ttl-seconds 900
```

The production helper has no game-directory override: it writes only to
`C:\Users\tobia\curseforge\minecraft\Instances\SIVILASJON (1)`, refuses a
symlink/reparse substitute, refuses to overwrite even a malformed or stale
marker, and refuses marker activation while either observer environment
variable is present. The mod
deletes it before enabling the observer and logs
`HSQA_OBSERVER_ENABLED source=one_shot_marker ... markerConsumed=true` plus
the exact consumed marker creation/expiry epochs.
Malformed, expired, non-deletable, or stale markers fail closed; rejected
markers are deleted when possible. Supplying both environment activation and a
marker also fails closed and removes the ambiguous marker. Create a new marker
before each separate Minecraft launch when using this fallback. After a run,
verify the marker is absent; delete any rejected leftover marker manually
before retrying. Never ship or archive an activation marker in the modpack.

Before every client stop/relaunch, seal the current `latest.log`; Minecraft
rotates it on the next launch, so sealing afterward loses the pre-restart
source:

```powershell
py -3 qa\scripts\native_release_session.py seal-log `
  --session <same-session> `
  --run-directory <absolute-release-client-run-directory> `
  --display-profile 1280x720-gui3-en_us
```

`seal-log` reads only that exact profile's physical non-symlink
`logs/latest.log`, requires one matching observer activation plus native
integrated-server ACKs, writes a new immutable numbered segment without
overwriting an older one, and atomically extends the segment index. Re-arm the
same session with a new one-shot marker for the next launch. The restart row
must reference distinct pre/post segments without renaming either source, and
all sealed segments must be consumed by client-log evidence. A server-log
locator is a row-specific range in one of those same sealed segments. It must
identify one exact `HEARTHSTEAD_AUTHORITY_V1` transaction by its fixed-vocabulary
`authorityEvent`, `authorityResult`, and exact `authorityTarget` on a genuine
`[Server thread/INFO] [hearthstead/...]` line. The validator reparses all 15
ordered fields, rejects unsafe/duplicate/missing/extra data, verifies canonical
UUID and integer forms, proves the declared item delta, and requires every
rejected transaction to leave revision, count, and item state unchanged. A
client echo, legacy `HSQA_AUTH` substring, malformed record, or free-form event
name is not authority evidence.

Every matrix row that requests `server_log` evidence also declares one or more
`requiredAuthorityTransactions`. Each locator must name exactly one of those
stable requirement IDs and must match its event, result, target prefix and
exact reason/reason prefix. The validator requires the declared occurrence
count exactly; an unrelated valid event, a forged locator label, a duplicated
line, or one missing pre/post-restart observation fails the whole run. Rows
without `server_log` evidence may not declare an authority transaction.
`STATE_LOAD_SUMMARY` is accepted only for the restart and stability rows; it
can never substitute for a gameplay commit.

For runtime identity, the observer accepts a CodeSource only when it resolves
to a physical, non-symlink JAR directly inside the selected game directory's
exact `mods` directory.
NeoForge production class loading can expose a union-filesystem CodeSource, so
the observer falls back to the active Hearthstead mod file reported by
`ModList`; that path must satisfy the same strict installed-JAR rules. If both
paths are usable but name different files, or only a development directory,
transform cache, union root or non-JAR is available, the ACK reports no usable
runtime hash and the release gate fails.

Each evidence item records `capturedAt`, `nativeSessionId`, `worldId`, SHA-256,
and (for native visual/audio/client evidence) one exact display profile and
language. Its `locator` is kind-specific: `lineStart`/`lineEnd` for logs,
`startSeconds`/`endSeconds` for MP4/WAV, `frameLabel` for PNG,
`transitions` for frame reports, and `state: before-after-restart` for the world
manifest. Shared raw client/server logs may support several rows only through
distinct line ranges with an exact row state and row-specific ACK nonce or
server authority event. Client ACKs additionally bind the integrated save and
their own wall-clock observation to this run. Recordings do not share matrix evidence hashes: export
one labelled MP4/WAV excerpt per row. Copying a file or changing its name does
not create new evidence. `setupCommandsUsed` may contain only bounded weather
or clock commands, whitelisted raw-material `/give`, a narrow vanilla-hostile
`/summon` without NBT, the separately reviewed real-shield offhand setup, and
bounded `/fill`/`/setblock` shell commands. Shell commands must use `keep`, span
at most 128 blocks per axis and 65,536 blocks total, and may place only inert
vanilla stone/dirt/sand/glass/plank shell blocks. They cannot place air,
furnishings, doors, lights, storage, beds, workstations, plaques, Hearthstead
blocks or any block entity. Teleport, arbitrary gamerule, hostile NBT, item
summoning, Hearthstead commands, tools/weapons/job items, broad item/data/loot
injection, recipes/advancements, or Hearthstead-entity summoning fail the
natural journey.

### Aggregate-row assertions

Broad matrix rows are not permission to approve only the flattering part of a
loop. `result.json.verifiedAssertions` must exactly repeat each machine-owned
`acceptanceAssertions` list. In particular:

| Matrix row | Assertions that must all be visibly and authoritatively proven |
|---|---|
| `journey.mayor_appointment_and_emblem_shop` | Inspect Mayor opens the ordinary settler sheet for the authenticated Mayor; wrong identity, range and stale revision are denied without mutation. |
| `journey.settler_inventory_and_need_icon` | newly assigned worker has no fabricated tool, need icon/request is visible, Shift-RMB with both hands empty opens that settler, and a real transfer persists server-side. |
| `journey.lumber_work_zone` | physical Work Scepter target and two-corner selection; transparent full-height preview; explicit confirm/cancel; exact persisted bounds/revision after restart; no-zone fallback is truthful and idle. |
| `journey.cultivated_ground_farmer_unlock` | no starting hoe; visible request and real delivery; harvest reaches the farm job chest. |
| `journey.farm_work_zone` | physical Work Scepter target and two-corner selection; transparent full-height preview; explicit confirm/cancel; exact persisted bounds/revision after restart; no-zone fallback is truthful and idle. |
| `journey.stores_roads_courier_unlock` | visible request list; collection from the real job chest; physical carry and server-authoritative delivery. |
| `journey.arm_the_watch_guard_weapon_delivery` | no starting weapon; visible need; equip happens only after real Courier delivery. |
| `journey.watchtower_archer_emblem_and_bow` | no starting bow/arrows; visible request; physical inventory/Courier delivery only; no fabricated ammunition. |
| `role.lumberer_complete_physical_loop` | no starting axe; job-chest deposit/Courier availability; bag attached only while worn and fixed on the ground through bend, stow, pickup and locomotion recovery; no search/claim/break/collect/deposit outside the committed Lumber Work Zone. |
| `role.farmer_complete_physical_loop` | no starting hoe; farm-chest deposit/Courier availability; no till/plant/search/harvest/collect/deposit outside the committed Farm Work Zone. |
| `role.courier_request_collection_delivery` | requester/item visible; exact job-chest removal; no duplication; matching delivery. |
| `role.archer_bow_stance_patrol_and_combat` | no starting bow/arrows; visible request; real equipment transfer; no shot may fabricate ammunition. |
| `ui.plaque_requirements_and_plan` | plaque and learned build-plan preview stay continuously visible with no blink, z-fight, stale overlay or frame disappearance. |
| `ui.work_scepter_zone_preview_confirm_cancel` | job site and both physical corners are readable; transparent full-height 3D preview is truthful; Confirm commits once and Cancel commits nothing. |
| `authority.work_zone_invalid_stale_unloaded_no_mutation` | invalid/range/stale/cross-settlement/oversized/height/unloaded requests do not mutate or force-load; all eight box corners must remain inside the spherical settlement; only one bounded confirmed request advances revision once. |

## Stage A: repeatable automated client proof

Run only after the integration build and all headless suites are green and no
other agent is editing fingerprinted files:

```bash
cd "/mnt/c/Users/tobia/OneDrive/Documents/ChatGPT/MINECRAFT MOD/Verk-arbeid-"
./tools/hearthstead-qa reap check
./tools/hearthstead-qa doctor
./tools/hearthstead-qa playtest
```

This stage must prove all four input classes with the existing physical-input
driver: player command, keyboard key, mouse click, and mouse look. The client
observer must report the exact screen class, mouse-grab state, effective GUI
scale, framebuffer, language, player position, camera angles, actual pick
result where applicable, and a post-input frame acknowledgement. The physical
input row needs at least two row-nonced ACKs with a grabbed mouse, at least a
5-degree measured look delta and 0.5-block measured movement delta. Every
acknowledgement must also contain the current `qaSession`, `observerSource`,
`nativeWindows=true`, exact runtime JAR SHA-256, and
`runtimeJarSource=code_source|mod_list`, the exact game/JAR/save identity
tokens, `integratedServer=true`, and an in-run epoch. The input row must prove
look and movement in the same profile with both before/after ACKs grabbed.
Screenshots must pass
`check_screenshot.py`.

Run the playtest at these display profiles:

| Profile | Resolution | Requested GUI scale | Required languages |
|---|---:|---:|---|
| compact | 1280x720 | 2 | `en_us` |
| normal | 1280x720 | 3 | `en_us`, `nb_no` |
| large | 1920x1080 | 4 | `en_us` |

The current automated playtest proves client boot, joining, input delivery,
Blessing interaction, handbook rendering, a real plaque/plan interaction, and
basic screenshots. It does **not** currently prove the complete Founding
Journey, all four first-demo jobs, the first raid, live audio, or native-GPU
frame time. Those remain mandatory in the next stages.

## Stage B: one continuous fresh-world journey

Use a fresh normal world (`HSQA_LEVEL_TYPE=normal`) and one continuous save.
Do not import a prepared settlement and do not edit Hearthstead SavedData.
World-building commands may shorten repetitive arena construction, but every
Hearthstead progression commit must come from the same physical action a
normal player uses.

Record the following **exact 56-step JourneyDefinition.V3 chain**. The frozen
FJ ID, order and one-step prerequisite chain are canonical; a broader clip or
Development objective may support a row, but it may not merge, reorder or
rename Journey steps.

| # | Frozen FJ ID | Physical player action | Server-authoritative proof | Visual proof |
|---:|---|---|---|---|
| 1 | `fj_010_found_hearth` | Place the Hearth from the hotbar and complete founding with all three settlers alive. | Settlement exists; exactly three founder records and three live settlers are bound to its UUID. | Founding message, cue/particles and the physical Hearth. |
| 2 | `fj_020_open_journey` | Right-click that Hearth and choose Journey. | Exact live `HearthMenu` records `JOURNEY_VIEW_OPENED` for this settlement. | `HearthScreen` shows the current and next Journey needs without overlap. |
| 3 | `fj_030_appoint_mayor` | Choose one living founder as Mayor through the intended UI. | One persisted Mayor UUID is a live member; appointment revision advances once. | Named Mayor, authority explanation and access to Development/emblems. |
| 4 | `fj_100_unlock_lumber_camp` | Open Hearth → Development and learn Timber Rights after supplying 8 any logs + 8 cobblestone. | `FOUNDATION_READY` is true; exact physical cost leaves the Hearth once; the node revision commits once. | Cost/objective, Lumber Camp description, learned plan and Lumberer knowledge. |
| 5 | `fj_110_link_lumber_camp` | Craft the learned plan, build the room, place a blank plaque, and link a valid Lumber Camp with accessible workplace storage. | Plan is consumed once; the plaque/building becomes `LINKED_VALID` with one registered physical container. | Stable plan/plaque, room requirements and storage route. |
| 6 | `fj_120_staff_lumber_camp` | Buy one Lumberer Emblem from the Mayor, hold it in the main hand, then right-click the chosen compatible settler without sneaking. | One trade and one emblem bind commit in the required transaction; the exact settler is auto-employed at the camp. | Shop cost, emblem consumption, profession/employer change and axe need. |
| 7 | `fj_130_open_lumberer_inventory` | Empty both hands and Shift-right-click that Lumberer. | Exact `SettlerInventoryScreen` is authorised for the targeted live settlement member. | Real slots, equipment/request state and no hidden or fabricated inventory. |
| 8 | `fj_140_set_lumber_zone` | Use the Work Scepter, hit two real corner blocks around natural trees, inspect the full-height volume, and Confirm. | One exact bounded 3D Lumber Work Zone revision commits; Cancel/default/stale/unloaded inputs commit nothing. | Target, both corners, transparent preview, Confirm/Cancel and readable bounds. |
| 9 | `fj_150_lumberer_requests_axe` | Let the unequipped Lumberer assess the confirmed job. | One persistent compatible-axe equipment request opens; no starter tool is fabricated. | Need icon and exact accepted axe requirement in settler/Requests UI. |
| 10 | `fj_160_give_lumberer_axe` | Move one serviceable compatible physical axe into the Lumberer's inventory or linked Lumber Camp storage. | Real item ownership moves and the exact request is satisfied once. | Slot/storage delta and need icon clearing at the transaction. |
| 11 | `fj_170_lumberer_fells_tree` | Let that equipped Lumberer claim and fell a real natural tree inside the confirmed zone. | Same worker, employer, zone and tree provenance commit one completed work action; items conserve. | Chop/contact, no out-of-zone work and the complete sack collection sequence. |
| 12 | `fj_180_lumber_camp_stores_log` | Let the Lumberer deposit at least one conserved log from that work action into camp storage. | `WORKPLACE_OUTPUT_COMMITTED` names the exact worker/building/stack; camp count rises once. | Sack recovery, job-chest insertion and next-work continuation. |
| 13 | `fj_200_unlock_warehouse` | Return to Development and learn Stores and Roads after supplying 8 any logs + 2 leather. | The post-baseline first Lumber Camp log objective is true; exact cost/revision commits once. | Warehouse/Courier description, cost, objective and learned plan. |
| 14 | `fj_210_link_warehouse` | Craft/build the plan and link a valid Warehouse with registered accessible storage. | Plan is consumed once; exact plaque/building/storage identities commit `LINKED_VALID`. | Requirements, non-blinking plaque and readable source/destination role. |
| 15 | `fj_220_staff_warehouse` | Buy a Courier Emblem, hold it in the main hand, and right-click a settler compatible with the Warehouse without sneaking. | Trade/bind transaction consumes once; exact settler is auto-employed at the Warehouse. | Courier profession/employer and no separate Hire control. |
| 16 | `fj_230_open_request_ledger` | Open the Hearth, choose Requests, press Refresh, and inspect item/count, route, assigned Courier, physical owner and Stop reason. | Exact settlement/container-bound bounded snapshot is sent, then `REQUEST_LEDGER_VIEW_OPENED` records; constructing or spoofing a list cannot advance. | `HearthScreen` → Requests loading/empty/rows, readable hover text and scroll control. |
| 17 | `fj_240_request_first_pickup` | Leave the concrete logs in Lumber Camp storage, open or Refresh Hearth → Requests, and wait for the automatic Courier system to publish their pickup row. | One typed `OUTPUT_PICKUP_REQUEST_OPENED` row binds exact settlement, source, target, item fingerprint and counts. | New Requests row with the true camp-to-Warehouse route and initial owner; there is no redundant manual pickup button. |
| 18 | `fj_250_courier_claims_pickup` | Wait for the employed Courier to reserve the request and its exact physical stacks. | One Courier wins the lease; `REQUEST_RESERVATION_COMMITTED` records once and competitors cannot claim it. | Assigned Courier, Reserved state, owner/source and truthful blocker/Ready text. |
| 19 | `fj_260_warehouse_receives_log` | Let that Courier pick up, carry and deposit the reserved logs into Warehouse storage. | Source → Courier bag → target counts conserve; exact request reaches satisfied once. | Physical collection/carry/insertion, owner transitions and queue completion. |
| 20 | `fj_300_unlock_farmhouse` | Learn Cultivated Ground after the first real Courier delivery and supply 8 wheat seeds + 4 any logs. | Courier objective is post-baseline and server-measured; exact cost/revision commits once. | Farmhouse/Farmer description, cost, objective and learned plan. |
| 21 | `fj_310_link_farmhouse` | Craft/build the plan and link a valid Farmhouse with accessible workplace storage. | Exact plan/plaque/building/container identities commit `LINKED_VALID` once. | Farmhouse requirements, stable plaque and storage. |
| 22 | `fj_320_staff_farmhouse` | Buy a Farmer Emblem, hold it in the main hand, and right-click a compatible settler without sneaking. | One trade/bind transaction auto-employs that exact settler at the Farmhouse. | Profession/employer change and no starter hoe. |
| 23 | `fj_330_set_farm_zone` | Use the Work Scepter to confirm a bounded Farm Work Zone containing farmland or planted crops. | One exact 3D zone revision commits; invalid/out-of-range/stale/unloaded alternatives do not mutate. | Both corners, readable volume, terrain visibility and Confirm/Cancel. |
| 24 | `fj_340_farmer_requests_hoe` | Let the unequipped Farmer assess the confirmed field. | One persistent compatible-hoe request opens; no tool is fabricated. | Need icon and exact physical hoe requirement. |
| 25 | `fj_350_equip_farmer` | Move a serviceable compatible hoe into the Farmer's inventory or linked Farmhouse storage. | Real item ownership changes and the exact request is satisfied once. | Slot/storage delta and request/need clear. |
| 26 | `fj_360_supply_first_seed` | Put an accepted physical seed in Farmhouse storage and let the Farmer withdraw and plant that exact seed inside the zone. | `MATERIAL_INPUT_COMMITTED` proves source, worker, field and conserved seed decrement. | Withdraw/carry/plant contact inside the confirmed field. |
| 27 | `fj_370_farmhouse_stores_crop` | Let the Farmer harvest one mature accepted crop and deposit the output in Farmhouse storage. | Real Farmer work provenance and `WORKPLACE_OUTPUT_COMMITTED` increase the exact job-chest stack once. | Harvest/collect/carry/deposit and continued valid work. |
| 28 | `fj_380_warehouse_receives_crop` | Let a Courier reserve, collect and deliver those physical crops from Farmhouse to Warehouse. | Crop source/bag/target counts conserve and the request reaches satisfied once. | Queue owner/route transitions and Warehouse insertion. |
| 29 | `fj_400_unlock_home` | Learn Home after the first Farmhouse crop output and supply 12 any logs + 8 cobblestone. | Post-baseline crop objective is true; exact cost/revision commits once. | House/Lodging descriptions, objective, cost and learned plans. |
| 30 | `fj_410_link_first_home` | Craft/build and link a valid House or Lodging with physical beds. | Plan/plaque commits once and server-measured resident capacity rises from real valid beds. | Readable housing requirements, beds and capacity change. |
| 31 | `fj_420_unlock_tavern` | House all three founders, then learn Hospitality with 8 bread + 2 leather. | `HOUSED_SETTLERS` is 3; exact Hearth cost and node revision commit once. | Tavern description, cost/objective and learned plan. |
| 32 | `fj_430_link_tavern` | Craft/build the plan and link a valid Tavern before inviting travelers. | Exact plan/plaque/building state commits `LINKED_VALID` once. | Tavern requirements, stable plaque and recruitment entry point. |
| 33 | `fj_440_recruitment_window_starts` | Keep Tavern, housing, ready food/reserve, safety and morale gates valid and open the Hearth recruitment surface. | One persisted survival-authored qualification cycle starts; client inspection cannot manufacture it. | Every live blocker, traveler slot, admission price and status is understandable. |
| 34 | `fj_450_traveler_arrives` | Maintain all gates for the authored 2,400–4,800 qualified seconds until the chosen traveler physically arrives. | Same persisted cycle/target traveler reaches waiting admission; no recruit command or spawn egg. | Travel/arrival sequence, named candidate, patience and sound. |
| 35 | `fj_460_admit_traveler` | Review and explicitly Admit that waiting traveler, paying the exact 3→4 price while retaining the required food reserve. | Correct traveler UUID becomes the fourth member; price is consumed once; stale/wrong/Dismiss paths do not admit. | Candidate card, price, Admit result and live population four. |
| 36 | `fj_500_unlock_first_watch` | House four settlers, then learn First Watch with 8 iron ingots + 8 any logs. | `HOUSED_SETTLERS` is 4; exact cost/revision commits once. | Barracks/Guard description, objective, cost and learned plan/emblem. |
| 37 | `fj_510_link_barracks` | Craft/build the plan and link a valid Barracks with accessible storage. | Exact plan/plaque/building/container identities commit once. | Barracks requirements, stable plaque and storage. |
| 38 | `fj_520_staff_barracks` | Buy a Guard Emblem, hold it in the main hand, and right-click a compatible settler without sneaking. | Required trade/bind transaction consumes once and auto-employs the exact Guard at Barracks. | Guard profession/employer and no starter weapon. |
| 39 | `fj_530_guard_requests_weapon` | Let the unequipped Guard assess duty. | Canonical compatible-weapon request opens once with exact requester/building identity. | Guard need icon plus the same truthful row in Hearth → Requests. |
| 40 | `fj_540_equip_guard` | Put a serviceable weapon in Warehouse storage, let the Courier deliver it to Barracks, then let the Guard acquire it. | Warehouse → Courier bag → Barracks/Guard ownership conserves; request satisfies once and the unique Guard-delivery objective reaches 1/1. | Full transfer, queue clear, need clear and real weapon in hand/inventory. |
| 41 | `fj_550_set_guard_order` | Commit one valid Stand Post or Patrol Route assignment for the melee Guard through Guard Orders. | Server validates exact Guard identity, Barracks employment, settlement, revision, range and route; `GUARD_ASSIGNMENT_COMMITTED` advances once. Tower Post is rejected for the melee Guard. | Order feedback and the Guard visibly executes, stops and returns as ordered without using the Archer-only Tower Post. |
| 42 | `fj_551_unlock_arm_the_watch` | Return to Development and explicitly claim cost-free Arm the Watch after the real Guard weapon delivery objective reaches 1/1. | The normal Development transaction commits exactly once, charges no invented material cost, and teaches the Watchtower plan, Archer Emblem and Tower Post command. A v2 migration or save tear remains explicitly claimable or heals from the persisted owned-node receipt without replay. | Arm the Watch objective, zero cost, confirmation and newly learned baseline Watch knowledge; Shield Doctrine remains post-raid. |
| 43 | `fj_552_add_fifth_bed` | Add enough valid physical beds to linked Houses or Lodgings to reach at least five unique loaded bed heads. | The healthy housing authority re-observes the bed change and records `HOUSING_CAPACITY_COMMITTED`; duplicate heads, invalid buildings and stale capacity do not count. | Five real beds and a truthful 5+ housing-capacity objective. |
| 44 | `fj_553_second_recruitment_window` | Keep the valid Tavern and fifth bed available while the Watch recruitment slot begins or adopt the same already-running natural transaction. | One survival-authored transaction is durably bound to the Watch slot; the first post-bed attempt and every retry use the deterministic four-to-eight-minute Call to Arms window without changing its Tavern or transaction identity. | Visible Call to Arms qualification, current blockers and bounded progress; no hidden sixth-bed workaround. |
| 45 | `fj_554_second_traveler_arrives` | Maintain the live gates until that exact Watch-slot traveler physically arrives at the linked Tavern. | The transaction bound at FJ-553 reaches persisted waiting admission with the same traveler and Tavern identities; dismissal, timeout, death and invalid Tavern retries cannot deadlock the slot. | Named traveler arrival, patience and exact Tavern destination. |
| 46 | `fj_555_admit_fifth_settler` | Review and explicitly Admit the waiting Watch recruit, paying the real price while retaining the required food reserve. | The exact transaction/payment receipt commits once and adds one distinct live settlement member; stale, wrong-traveler, Dismiss and admin-primed attempts do not advance. Mature cycle numbers and death replacements remain reachable, while readiness separately enforces five live members. | Candidate card, price, Admit result and live population/housing state. |
| 47 | `fj_556_link_watchtower` | Craft the learned plan, build the room, place a blank plaque and link one valid Watchtower with accessible physical arrow storage. | Plan consumes once; exact plaque, building and loaded storage identities commit `LINKED_VALID`. | Watchtower description, stable plaque, standable post and visible arrow rack/container. |
| 48 | `fj_557_staff_watchtower` | Buy one Archer Emblem, hold it in the main hand and right-click a current compatible settler beside that linked Watchtower without sneaking. The Archer must be a different settler from the Guard. | One exact sale-and-bind transaction consumes the emblem once and auto-employs that distinct settler as Archer at the same Watchtower; wrong profession, worker, settlement, building or transaction does not advance. | Archer profession/employer, no separate Hire action, no starter bow and a distinct Guard still employed at Barracks. |
| 49 | `fj_558_archer_requests_bow` | Let the unequipped Archer assess the linked Watchtower. | One persistent compatible-bow request opens for the exact Archer/employer identity; no starter bow or arrows are fabricated. | Archer need icon and the same truthful bow row in Hearth → Requests. |
| 50 | `fj_559_equip_archer` | Put a serviceable physical bow into the logistics route and let a Courier deliver it to the Archer. | Real source → Courier → Archer ownership conserves, the exact request satisfies once, and the physical bow reaches the Archer's main hand/inventory. | Full transfer, queue clear, need clear and real bow visible. |
| 51 | `fj_559a_supply_archer_ammunition` | Put at least one physical Arrow into storage of this exact employer Watchtower, then proceed to confirm Tower Post. | The later Tower Post commit re-observes the exact loaded rack and records the bounded physical-ammunition fact; arrows in another tower or only an unproven internal quiver do not satisfy this Journey step. | Arrow stack in the correct Watchtower storage and truthful instruction that Tower Post confirms it. |
| 52 | `fj_559b_set_archer_tower_post` | Assign Tower Post to that exact Archer at the linked employer Watchtower. | Server validates Archer identity, employment, revision, exact tower, physical ammo and a reachable standable tower cell; the same click commits the ammo observation and Archer-only `GUARD_ASSIGNMENT_COMMITTED` without changing the melee Guard. | Archer reaches/holds the tower post with bow stance while the Guard continues the separate Stand/Patrol order. |
| 53 | `fj_560_declare_raid_ready` | Open Journey → **Check Readiness**, physically scroll the entire blocker list, resolve every blocker, Check again and press **Declare Ready**. | Check/scroll are read-only. Readiness requires five distinct live members, five physical beds, one serviceable melee Guard with Stand/Patrol and one different Archer with bow, Tower Post and physical arrows in the exact employer rack or that Archer's bounded persisted quiver after real rack withdrawal. One exact generation commits and schedules one persisted raid calendar; stale/replayed Declare cannot mutate. | Explicit Guard 1/1 and Archer 1/1 rows, every blocker/metric, scroll reachability, Ready state, confirmation and stable calendar receipt. |
| 54 | `fj_600_receive_first_warning` | After readiness is committed, wait naturally or advance only world time without changing the persisted plan. | Exact saved raid date/direction/objective reaches warning once; the plan is not rerolled and re-observes the same five-member/two-defender field. | Authored omen/warning clearly names when, where and why before attackers arrive. |
| 55 | `fj_610_first_raid_resolved` | Fight the raid with normal controls while the melee Guard and Archer act autonomously until the real outcome is terminal. | Sealed participant UUIDs become terminal; actual outcome/losses/reward persist; Guard/Archer combat credit and XP accept each valid contact/kill at most once. | Coordinated melee and ranged defense, contact-synchronised combat, XP cue, objective/captain pressure, resolution and return to each defender's order. |
| 56 | `fj_620_review_aftermath` | Reopen Hearth → Journey after the raid and review the persisted Aftermath report and road ahead. | Exact `RAID_AFTERMATH_VIEW_OPENED` records for the terminal raid; only now does Journey enter COMPLETE. | Persisted result/losses/reward summary, next-road explanation and unambiguous completion state. |

**Journey completion boundary:** the Founding Journey is ACTIVE through
`fj_610_first_raid_resolved`. It becomes COMPLETE only after the player performs
`fj_620_review_aftermath`. Lumber, Farmer, Courier, readiness declaration,
warning, or raid resolution alone must never be described as Journey
completion.

After the canonical FJ-620 completion, continue the same release save for the
non-Journey demo qualifications that were previously mixed into the Journey
table:

| Post-Journey action | Server-authoritative proof | Visual proof |
|---|---|---|
| Learn the cost-free First Raid Aftermath node, then make the permanent Shield Doctrine choice through Development when its real XP objective and 16 iron + 4 leather cost are satisfied. | First raid is terminal; one doctrine becomes active; mutually exclusive doctrines cannot also commit. | Clear one-of-three trade-off, confirmation, sound and persistent branch. |
| Exercise Hold Here/Stand Post, Defend Hearth, a 2–8 point Patrol and Clear through melee Guard Orders, then re-check the Archer's separate Tower Post. | Valid current revisions apply to the intended defender; stale, wrong-settlement, range, profession and locked alternatives do not mutate. | Guard motion/stop/return and truthful, separate Archer Tower Post state. |
| Select an earned Blessing, obtain its physical seal, and Shift-right-click a settler. | Offer and seal consume once; permanent settler rank commits once. | Select/confirm, contact frame, recovery and inspected rank. |
| Apply a separately earned/available physical seal to a valid building plaque. | Exact building identity/rank commits once; invalid/cross-settlement target refuses without mutation. | Plaque contact, feedback and inspected building rank. |
| Save, stop, restart and reconnect to this exact world. | Identical settlement/building/entity UUIDs; Development, completed Journey, raid, doctrine, jobs, XP, inventories, orders and both Blessings persist. | Post-restart inspection across all affected screens/entities. |

For the time-accelerated qualification, changing `dayTime` is allowed only
after readiness is already `READY`; it accelerates waiting but must not alter
the authored warning or attack night. A separate natural-pacing playthrough is
still required before claiming the tutorial pacing itself is approved.

The material-assisted native gate may list whitelisted vanilla `/give` plus
the bounded inert-block `/fill ... keep` and `/setblock ... keep` forms above
in `setupCommandsUsed` to stage raw survival-crafting inputs, room shells, and
the combat arena. The `/give` allowlist is limited to logs, cobblestone, dirt,
leather, seeds, bread, coal, copper/gold/iron ingots, sugar cane, feathers,
white wool, string, flint, and sticks. It deliberately requires the player to
craft paper, campfires, containers, beds, banners, arrows, bows, tools,
weapons, armour and bells. Those commands may never provide a finished Build
Plan, plaque, Job Emblem, settler, profession, Development state, quest
counter, request result, raid result, Blessing, Hearthstead item, or furnished
building block. `progressionCommandsUsed` must remain exactly empty. In
particular, `/hearthstead demo`, `recruit`, `hire`, `mayor`, and `scan`, spawn
eggs, and direct data edits invalidate the continuous journey.

### Mandatory Work Scepter and Work Zone path

Before either first-demo worker is allowed to perform productive work, the
player must use the Work Scepter on the intended Lumber Camp or Farmhouse,
physically select two block-hit corners, inspect a transparent full-height 3D
volume, then choose Confirm or Cancel. Preview and Cancel are client-only and
must not mutate saved state. Confirm sends the target building identity,
settlement identity, expected revision and two corners to the server; exactly
one bounded, loaded, same-settlement validation may advance the persisted zone
revision once.

The Lumberer and Farmer must remain idle while no explicit zone is committed.
The UI must label that no-zone/default fallback honestly; it may not quietly
scan the whole settlement. After commit, every work-search, claim, break/till,
plant, harvest, ground collection and deposit attribution must originate
inside the matching zone. The zone and revision must survive the Stage-B
restart unchanged. Range, stale revision, wrong settlement/building, malformed
or inverted bounds, excessive volume/height, any of the eight box corners
outside the spherical settlement, and unloaded corners all fail without
mutation and without loading or force-loading a chunk.

These are release-blocking rows, not future polish:
`journey.lumber_work_zone`, `journey.farm_work_zone`,
`ui.work_scepter_zone_preview_confirm_cancel`, and
`authority.work_zone_invalid_stale_unloaded_no_mutation`.

## Stage C: targeted first-demo role qualification

Each role gets its own real-AI take. A server-built arena is allowed; invoking
the activity directly, posing the settler, or injecting the expected result is
not.

| Role | Required real interaction and state coverage | Hard rejection examples |
|---|---|---|
| Lumberer | committed Lumber Work Zone; no starting axe; visible request; real delivery/equip; in-zone tree search; approach; limb/chop; shoulder-to-left-hand-to-world frame handoff; frame placed and stationary; every in-zone ground item picked and stowed; frame lifted; laden walk **and turn**; workplace deposit; next job resumes | any search/claim/break/collection sourced outside the zone, unlabelled whole-settlement fallback, carried frame lag, ground frame following/bobbing, lost hand contact, backward bend, hand over head, crawling gait, lost fourth log, long unexplained idle |
| Farmer | committed Farm Work Zone; no starting hoe; request/delivery; till/plant/grow/harvest only inside the zone; workplace deposit; next field task within bounded latency | any till/plant/search/harvest/collection sourced outside the zone, unlabelled whole-settlement fallback, wrong crop/field, tool fabricated, harvest disappears, unexplained stop |
| Courier | visible request list opened from the intended UI; picks a real request; collects from exact workplace storage; carries the physical stack; delivers to exact destination; request clears only after insertion | magical inventory transfer, wrong chest, stale request, duplicate item, courier idle with reachable work |
| Guard | no starting weapon/armour; equipment request and real Courier delivery; player applies Hold Here, Defend Hearth, and a 2-8 point Patrol; civilian-first response; authored blade contact equals damage/audio tick; credited kill grants exact XP once with audible cue; returns to order | attacks wrong target, early/duplicate damage, ignores threatened civilian, no player control, free equipment, XP on uncredited kill, Tower Post presented as functional while still locked |
| Archer | no starting bow/arrows; real request/delivery and physical bow; truthful bow idle/patrol/aim/loose; target acquisition, ammo starvation signal, restock, combat and return | bow behind head, fake held prop, shoots with no bow/ammo, silent starvation, double shot/damage, wrong target, stuck after restock |

For every role capture idle, request, equipment handoff, outbound walk, work
contact, carry, deposit, and return-to-work. Film the full transition edges;
do not approve a collection of disconnected flattering clips.

## Stage D: UI frame-time and layout gate

The client observer records rolling frame-time percentiles, maximum frame,
slow-frame count, screen class, UI state, and transition label. Capture at
least 180 post-warm-up samples for each state below on the same client launch:

- world baseline;
- Hearth settlement tab;
- Mayor popout open/scroll/close;
- Journey popout and confirmation open/cancel;
- Development open, inspector open/close, pan, zoom, and scroll;
- Emblem shop open and scroll;
- Settler sheet open and scroll;
- Settler inventory open and one real slot click;
- Guard Orders open, add/undo route point, start/stop order, and close;
- Courier request list open and scroll;
- Plaque screen and building requirements;
- Blessing offer open, select, confirm, Later, and close;
- Handbook index scroll, body scroll, page change, and close;
- every narrow Hearth modal must consume clicks and suppress slots/tooltips
  underneath it.

Evaluate both absolute native-client latency and relative regression:

- native hardware run: p95 at or below 33.3 ms and no repeated UI-caused
  frame above 100 ms after the first shader/resource warm-up;
- Xvfb/llvmpipe run: UI p95 may not exceed the same-run world-baseline p95 by
  more than 35%, and slow-frame fraction may not rise by more than 10
  percentage points;
- screen opening is measured separately from steady-state rendering; one
  labelled transition spike cannot be hidden inside a long idle window;
- every supported resolution/GUI-scale/language profile must be visually
  checked for overlap, clipped labels, tooltip collisions, translucent modal
  bleed-through, dead controls, and scroll reachability.

If the observer reports fewer than 180 samples, `uiState=unavailable`, the
wrong screen class, or an unlabelled transition, the row is not measured and
cannot pass.

Extract and fail-closed-check the relative windows with the repository parser:

```bash
python3 qa/scripts/check_client_frame_stats.py \
  qa/reports/artifacts/release-client/<run>/logs/playtest-client.log \
  --baseline screen_none \
  --require screen_HearthScreen \
  --require screen_DevelopmentScreen \
  --min-samples 180 --max-p95-ratio 1.35 --max-slow-rise 0.10 \
  --max-p95-ms 33.3 --max-frame-ms 100 --max-frames-over100 0 \
  --expected-jar-sha256 <candidate-sha256> \
  --expected-session <HSQA_CLIENT_SESSION> \
  --expected-profile 1280x720-gui3-en_us \
  --expected-game-directory-token <profile-path-token> \
  --expected-runtime-jar-path-token <installed-jar-path-token> \
  --expected-world-path-token <save-path-token> \
  --require-native-windows --require-integrated-server \
  --output qa/reports/artifacts/release-client/<run>/logs/frame-time-report.json
```

Run the parser separately for each native profile and add every transition
owned by that profile's matrix rows as a separate `--require`. The
parser rejects missing/malformed acknowledgement fields, non-monotonic
percentiles, insufficient samples, an unavailable/wrong screen state, stale
session, wrong runtime JAR/source/native OS/profile, an older good window that
tries to hide a newer bad window, excessive p95 ratio, and excessive slow-frame
fraction rise. Its report seals the exact source client-log SHA-256; the final
gate reparses that log, requires the report ACK roster to equal the entire
hashed log, and independently requires every selected baseline/state to be the
newest matching ACK. A hand-edited PASS report cannot select an older window.

## Stage E: animation, audio, and sync gate

For each changed animation record the real trigger in motion at 30 fps or
better. Preserve a labelled contact sheet with start, anticipation,
pre-contact, exact contact/transfer, post-contact, recovery, and handoff to the
next state. Back-carried or ground containers require front three-quarter,
both side views, and a back view across the evidence set.

Reject immediately for wrong-way spine motion, hand/head clipping, foot slide,
fixed props following the actor, invisible inventory jumps, wrong held item,
transition pops, locomotion cycling while blocked, or multiplayer/client sync
drift.

Audio is reviewed in the native Windows client with an actual output device
and loopback capture. For every changed sound verify:

- it occurs once, only on the authoritative contact/success event;
- its audible transient aligns with the visible contact within two video
  frames;
- failure/retry paths stay silent unless they have an intentional error cue;
- no clipping, repeated identical-machine-gun cadence, missing subtitle, or
  large loudness jump against footsteps and vanilla UI sounds;
- the same event is audible at normal player distance in the real game mix.

## Final approval sequence

1. Freeze source changes and build the exact candidate JAR.
2. Record its SHA-256 before installing it.
3. Run the repository `full` suite twice at the same fingerprint and require
   `green_streak >= 2`.
4. Complete Stages A-E against that exact JAR and one clearly identified
   fresh-world save.
5. Review every film/contact sheet and audio take; numerical motion detection
   only proves that something moved, not that the intended actor moved well.
6. Scan client/server logs for exceptions, missing resources, OpenAL errors,
   rejected packets, stale revisions, navigation failures, and rendering
   warnings.
7. Restart and re-inspect persistence.
8. Hash the installed JAR and compare it to the candidate hash.
9. Mark only individually proven rows `Approved`. Any unrun or ambiguous row
   remains `Candidate`; the demo as a whole remains not approved while one
   required row is not approved.

## Current environmental blockers (2026-08-28)

- The Work Scepter and persisted, bounded Lumber/Farm Work Zones now exist as
  a source candidate. They remain unapproved until the current adversarial
  authority repairs, all focused GameTests, restart checks and every related
  Journey/role/UI/authority native row pass with the exact candidate JAR.
- The Windows player-input recorder is under a fail-closed security repair.
  No native approval run may start until independent review proves foreground
  HWND binding, launch/JAR/world identity, forbidden-command exclusion and an
  externally sealed transcript.
- `tools/hearthstead-qa live` is intentionally disabled until its persistent
  tmux/client launch path satisfies the exact tracked-process contract. Use
  the bounded `playtest` for automation; use a deliberately supervised native
  client for the final hardware/audio pass.
- The Xvfb playtest uses software rendering and a null OpenAL backend. It can
  prove renderer/UI correctness and relative regressions, but not the native
  GPU frame-time target or audible live mix.
- No prepared world currently contains an honest, complete fresh-world path
  through the Founding Journey and first raid. The Blessing restart suite's
  world is a focused persistence fixture, not a substitute.
- Concurrent edits change the source fingerprint between the controller's two
  fail-closed observations. Run the client gate only after integration is
  frozen; do not weaken the fingerprint check.
