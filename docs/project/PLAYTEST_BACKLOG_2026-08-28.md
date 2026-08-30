# Hearthstead playtest backlog — 2026-08-28

This is the authoritative triage record for Tobias' 2026-08-28 playtest.
"Patched" means code and automated checks exist; only "live verified" means
the result has also been observed in a normal client playthrough.

## P0 — demo blockers

| Finding | Current state | Acceptance gate |
|---|---|---|
| Lumberer stalls after one tree | Patched/test corrected; six logs from two real three-log trees were delivered in the full GameTest run | Repeat in client without spin/idle stall |
| Courier cannot pass doors | Navigation already enables opening and passing doors; the sealed-room Courier door GameTest did not fail in the full run | Repeat through both inward/outward doors in client |
| Doors unstable for settlers | Shared ground navigation uses open/pass-door support | Live multi-profession route pass |
| Farmer reports no valuable target | Work Zone is now an exact three-click 3D volume so crops above ground level are included | Live harvest, replant and deposit loop |
| Work Scepter ignores height | Patched: corner 1, corner 2, dedicated height click, then confirmation | Live selection on sloped/elevated terrain |
| Level-1 Farmer area is overpowered | Patched server-authoritatively: 5x5, 9x9, 13x13, 17x17, 25x25 by Farmer tier | Verify rejection and upgrade expansion in client |

## P1 — animation and world props

| Finding | Current state | Acceptance gate |
|---|---|---|
| Lumberer bends backward during sack pickup | Forward-hinge signs corrected; deterministic and strict offline animation checks pass | Multi-angle in-game capture |
| Lumberer leans backward while carrying | Loaded-walk torso/head compensation corrected | Multi-angle in-game capture |
| Courier carries load in front | Courier lift/carry/set-down torso signs corrected for back load | In-game Courier delivery capture |
| Sack follows worker after placement | Server keeps an immutable world anchor; renderer counter-transforms it from entity motion | In-game stationary-sack capture |

## P1 — UI and presentation

| Finding | Current state | Acceptance gate |
|---|---|---|
| Development view cannot zoom far enough out | Patched with real 42%, 56%, 72%, 86%, 100% and 118% levels; compact cards actually shrink | Client readability/FPS capture |
| UI icon/alert flicker | Removed the Hearth alert's timed 400 ms flash; display is now stable | Client capture of the reported panel |
| UI FPS drop | Double-background rendering and hot-path screen caches are present; further live profiling remains mandatory | No material frame-time spike while opening/panning panels |
| UI visual hierarchy/placement | Existing Hearthstead UI kit retained; broader movable-panel redesign is not live-approved | Tobias review at target GUI scale |
| Mayor identity | Existing Mayor badge/mark needs live legibility approval | Mayor identified instantly in a mixed group |
| Crafting/onboarding for Work Scepter and Plaque | Three-click help and tutorial copy updated; handbook/journey must be live-read | New-player playthrough without external help |
| Staff role/emblem explanation | Existing role/emblem flow needs live wording review | Warehouse clearly asks for Courier emblem assignment |

## P1 — audio

The current sound mix and authored effects are not approved. A new direction
still requires live A/B listening against work, UI, guard XP and raid scenes;
passing file/registry checks alone is not an audio-quality approval.

## Work package — sound direction and UI frame time

Status on 2026-08-28: **inventory complete; runtime unchanged; not live
approved.** This package is deliberately split into measurable performance
work and authored sound work. A prettier screen that still drops frames, or a
technically valid OGG that still sounds cheap, does not pass.

### Non-negotiable quality rule

The Lumberer is the detail and quality standard for every job. That means more
than reusing its assets: each profession needs an authored state sequence, a
visible physical contact, one sound on the matching contact tick, a real
success condition, a spatial source, restrained variation, and silence on a
failed transfer/retry. The Lumberer already demonstrates this shape at
`hearthstead-neoforge/src/main/java/com/hearthstead/entity/ai/LumbererWorkGoal.java:891-949`
and for the sack sequence at `:1135-1160`, `:1428-1452`, `:1557-1573` and
`:1602-1617`. Farmer, Courier, Guard and every later trade must meet or exceed
that complete loop before approval.

Reference mods may be studied for pacing and information hierarchy only. No
TekTopia or MineColonies code, layout, texture, text or sound may be copied.

### Read-only sound inventory

| Surface | Current evidence | Audit result |
|---|---|---|
| Registry | `hearthstead-neoforge/src/main/java/com/hearthstead/registry/ModSounds.java:11-117` | 47 custom `SoundEvent` registrations |
| Event manifest | `hearthstead-neoforge/src/main/resources/assets/hearthstead/sounds.json` | 47 event keys; every referenced file exists |
| Shipped files | `hearthstead-neoforge/src/main/resources/assets/hearthstead/sounds/` | 70 OGG files, 539,314 bytes total; no unreferenced file and no duplicate SHA-256 |
| Technical format | the same 70 OGG files, inspected with `ffprobe` | All Vorbis, mono, 44.1 kHz; 0.10–3.00 s |
| Subtitles | `assets/hearthstead/lang/en_us.json` and `nb_no.json` | All 47 subtitle keys exist in both languages |
| Runtime reachability | all `ModSounds.*` references under `src/main/java`, excluding the registry declaration | All 47 registered constants have at least one runtime call site |
| CC0 provenance | `tools/audio_sources/manifest.json`, `README.md` and the two local Kenney license files | Source packs, creators, URLs, archive hashes and CC0-1.0 declarations are recorded |
| Layered candidate verifier | `tools/build_demo_audio.py:442-477`; current `build/reports/hearthstead/demo_audio_report.json` | 36/36 candidate renders passed the existing offline format/waveform gate; this is not a listening approval |

Representative call-site map:

| Family | Current call sites and cadence authority | Required review scene |
|---|---|---|
| UI grammar | `client/ui/HsUi.java:41-69`; open/close hooks exist across all 13 current screen classes, with authoritative confirm/error hooks in Development, Emblem, Blessing, Guard Order and Work Zone screens | Open, close, accepted action and refused action; never hover or an unconfirmed click |
| Lumberer | `LumbererWorkGoal.java:891-895`, `:979-983`, `:1146-1155`, `:1428-1452`, `:1557-1573`, `:1602-1611` | Complete tree → sack down → pickup → stow → sack up → carry loop |
| Farmer | `FarmerWorkGoal.java:769-787`, `:950-965`, `:1050-1063` | Harvest, stow, till, plant/water and workplace deposit |
| Courier | `CourierWorkGoal.java:1226-1229`, `:1445-1461`, `:1464-1470`, `:1554`, `:2043`, `:2118`, `:2289` | Pickup, loaded walk, strain/creak, warehouse deposit and request completion |
| Bench trades | `settlement/Employment.java:313-401` and `entity/ai/CrafterWorkGoal.java:206-221` | One full work cycle per profession, with contact-frame and repetition-fatigue approval |
| Guard/raid | `event/GuardExperienceEvents.java:140-159`, `entity/SettlerEntity.java:2076-2082`, `entity/ai/GuardLeapGoal.java:126-127,225-227`, `settlement/raid/RaidPresentation.java:47,133` | Warning → melee/ranged fight → valid kill XP → raid aftermath, with a clear loudness hierarchy |
| Settlement milestones | `settlement/SettlementManager.java:173,746,1044` and `entity/SettlerEntity.java:904,935` | Founding, recruitment and profession assignment without masking UI speech/subtitles |

#### P0 sound risks and gates

1. **Remove the two-writer asset hazard before re-authoring.**
   `tools/gen_sounds.py` declares 64 procedural outputs and rewrites
   `sounds.json` (`:1326-1397`, `:1754-1787`).
   `tools/build_demo_audio.py` owns 36 layered CC0 recipes (`:78-287`). Thirty
   output names overlap; six events (`ui_open`, `ui_close`, `ui_confirm`,
   `ui_error`, `bag_down`, `bag_up`) exist only in the newer recipe set. A
   default `gen_sounds.py` run can therefore overwrite 30 approved candidates
   and remove those six events from the manifest. Establish one authoritative
   output manifest/dispatcher, explicit ownership per file, and a CI check that
   refuses overlapping writers or a manifest contraction.
2. **Re-author and live A/B the demo-critical mix first:** UI grammar, the
   complete Lumberer loop, Farmer, Courier, guard alert/impact/XP and the first
   raid. Compare candidate A/B at the same Minecraft master/blocks/neutral
   volume, listening position and ambient scene. Approval requires correct
   material identity, contact sync, distance falloff, no harsh transient, no
   machine-gun fatigue over five repeated cycles, and no cue masking another
   higher-priority cue.
3. **Preserve truth at the call site.** Work sounds fire only on committed
   physical action; confirm/error sounds fire only after the authoritative
   result; guard XP fires once for one valid defender kill. Automated tests
   must reject sound on failure, retry, duplicate packet or non-contact frame.
4. **Do not call the existing candidate report current.**
   `tools/audio_sources/CANDIDATE_REPORT.md:75-104` still says the UI sounds
   have no call sites, while current screen integration exists. Regenerate the
   report from source inventory after the ownership fix so a stale narrative
   cannot serve as release evidence.

#### P1 sound work

1. Bring every non-demo profession through the same Lumberer state/contact/
   success/failure review. Existing documented borrows in
   `Employment.java:313-366` are placeholders, not premium approval: Miller
   and Baker, Brewer and Smelter, Scholar and Fletcher, Innkeeper and warehouse
   sorting must become distinguishable when their physical actions differ.
2. Add a bounded concurrency/cooldown policy per worker and per nearby sound
   family. Courier currently schedules step, strain and crate-creak on three
   independent periods at `CourierWorkGoal.java:1445-1461`; the live mix must
   prove that several workers cannot create an exhausting transient wall.
3. Commit a durable per-output provenance report outside ignored `build/`
   output: output SHA-256, recipe owner/version, exact raw-source hashes,
   license ID, modifications and live-approval status.

#### P2 sound polish

1. Add subtle settlement ambience only after action cues pass; ambience must
   duck below UI, warning and combat signals and obey normal sound settings.
2. Add accessibility QA for subtitles, hearing-important cues and quiet-volume
   intelligibility. A subtitle may explain a sound but cannot excuse a weak or
   misleading sound.
3. Expand variations only where repetition is audible. Variation count is not
   a quality metric; each variant still needs the same material identity and
   contact timing.

### Read-only UI and Development hot-path inventory

Already-good architecture to preserve:

- `client/ui/HsUi.java:72-127` renders widgets without calling
  `Screen.super.render`, avoiding the old second blur/background pass.
- `DevelopmentScreen.java:209-253` caches snapshot-derived Components,
  `ItemStack`s and wrapped inspector lines; `:323-357` rebinds and translates
  the existing node widgets instead of recreating them while panning.
- `DevelopmentScreen.java:701-799` creates node presentation data only on a
  snapshot/cache rebuild, not in a steady frame.
- `HearthScreen.java:2292-2547` has render-model caches for the large Mayor,
  readiness, aftermath and journey panels.
- `client/QaClientObserver.java:62-81,145-176,545-565` already has a bounded
  360-frame p50/p95/p99/max sampler, but the latest playtest log contains no
  `HSQA_FRAME_ACK`; current UI performance is therefore **unmeasured**, not
  verified.

Concrete hot paths to profile before changing visual design:

| Priority | Hot path | Current cost shape | Required decision |
|---|---|---|---|
| P0 | `DevelopmentScreen.java:411-425` | One vanilla `renderBackground` blur/menu-background pass plus the large 1824x490 nine-slice/inset surface every frame | Compare against HUD and vanilla inventory; if blur dominates, implement one shared cheap Hearthstead backdrop policy rather than per-screen exceptions |
| P0 | all non-container screens listed by `renderBackground` calls, e.g. Settler `:374`, Plaque `:381`, Blessing `:362`, Equipment Requests `:218`, Development `:412` | The same blur path affects nearly every reported UI, so a common GPU cost can look like each screen being individually slow | Instrument screen-open and steady-state separately at GUI scales 2/3/4 |
| P1 | `DevelopmentScreen.java:508-523` | All 17 topology edges are recomputed and emitted as three `fill` calls every frame, including off-viewport segments | Cache clipped edge geometry per pan/zoom revision if a profiler shows measurable CPU/draw overhead |
| P1 | `DevelopmentScreen.java:890-912` | Each visible node toggles its own scissor, draws a card, two outline fills, one item and two-to-four text layers | Test one viewport scissor/batched node pass and retain per-node clipping only where a card crosses the viewport boundary |
| P1 | `DevelopmentScreen.java:439-452` | Two linear scans of 18 node buttons every frame for hovered then focused inspection | Collapse to one bounded pass or event-driven hover/focus invalidation if allocation/CPU sampling identifies it |
| P1 | `DevelopmentScreen.java:487-498` | Empty-inspector path constructs a translated Component during render | Cache the empty inspector label with the other display lines |
| P2 | `HearthScreen.java:2776-2818,2821-2888` | Large modal/popout compositor plus hover-only tooltip list/Component allocation | Profile only after P0 common background and Development costs; cache tooltips if hover-frame allocation remains visible |

#### P0 UI performance gate

1. Capture the exact runtime JAR and native client identity, then collect at
   least two 360-frame windows for each of: HUD baseline, vanilla inventory,
   Hearth, Settler, Plaque, Equipment Requests, Development idle, Development
   pan and Development zoom. Use the same world position, GUI scale, render
   distance, resolution, FPS cap/VSync state and warmed resource set.
2. Record screen-open transient separately from steady state. After warm-up,
   no Hearthstead screen may introduce a repeatable p95 regression greater
   than **max(2 ms, 10%)** over the comparable vanilla screen, and no tested
   interaction may cause a Hearthstead-attributable frame above 100 ms. Any
   >33 ms frame must be itemized rather than averaged away.
3. Profile before and after each change. A lower allocation count alone does
   not pass; the native frame-time window and visual capture must both improve
   or remain neutral.
4. Run layout/interaction regression at GUI scales 2, 3 and 4 after the
   performance pass. Every card, tooltip, modal, click target, keyboard focus
   and scissor edge must remain readable and reachable.

#### P1/P2 UI work

- **P1:** act only on measured hotspots above; add a steady-frame allocation/
  draw-state regression contract for Development pan/zoom and preserve the
  current snapshot caches and single-background rule.
- **P1:** make the visual hierarchy cleaner after the frame gate: one primary
  action, one obvious reading order, no overlapping modal information, and
  building descriptions visible without opening a second screen.
- **P2:** add restrained motion only after stable frame time. Animations must
  be time-based, interruptible and optional/reduced-motion friendly; never use
  flashing as state communication.

### Asset and licence requirements

1. No audio, texture, UI layout, code or wording may be copied or extracted
   from TekTopia, MineColonies, Minecraft videos, streams or another mod. They
   are comparison references only.
2. Existing Kenney raw sources are CC0-1.0. Attribution is not legally
   required by that dedication, but Hearthstead must retain the two local
   licence files, creator/title/project URL, archive SHA-256 and source hashes;
   credit Kenney in release credits as a provenance best practice.
3. New external material is accepted only with recorded provenance and a
   distribution-compatible licence. Prefer original recordings/commissioned
   work with written rights transfer, or CC0. CC-BY requires creator, title,
   source URL, exact licence/version and modification notice in the shipped
   credits. Reject unknown, ripped, non-redistributable, NC or ND material for
   this mod release.
4. Procedurally synthesized outputs from `tools/gen_sounds.py` need an explicit
   Hearthstead asset licence/author record even though they contain no sampled
   audio. Generated does not mean undocumented.
5. Spotify Pedalboard, NumPy, SciPy, SoundFile and FFmpeg remain authoring/
   verification dependencies only and must not be packaged in the mod JAR.
   If the authoring toolchain itself is redistributed, retain each tool's own
   licence notices separately from the licence of the rendered OGG output.
6. No asset download is authorized by this work package. Selection,
   provenance approval and licence recording happen before acquisition.

## Mandatory reference research standard

This standard applies to every future comparison with MineColonies, TekTopia
or another mod. Reference research is a **read-only quality-analysis lane**, not
implementation authorization.

### Legal and clean-room boundary

1. Use only material that is lawfully public to view: official websites and
   wikis, official public repositories, official CurseForge/project pages,
   public issue trackers and public gameplay footage available through its
   normal viewing interface.
2. Do not decompile a distributed mod, bypass access controls, inspect private
   builds, extract/rip assets, isolate audio from footage, copy screenshots
   into shipped assets, or copy code, text, UI layout, texture, model,
   animation or sound. A public reference is not a reusable asset licence.
3. Public MineColonies source may be inspected to understand externally
   visible behaviour and architecture, subject to its repository licence, but
   no implementation expression is copied. TekTopia is limited to its lawful
   public documentation/pages and normal public gameplay observation unless
   the rights holder publishes a clearly licensed source. No decompilation is
   permitted to fill that gap.
4. Record behaviour and design principles in Hearthstead's own language. A
   resulting proposal must be independently designed around Hearthstead's
   systems, data model, visuals and quality bar.
5. When licence or permission is unclear, classify the material as
   **reference-only / no reuse** and stop there. Do not download it into the
   project or use it in a generator prompt/reference pack.

### Evidence classification

Every matrix row must use exactly one of these labels:

- **FACT** — directly supported by a named public source. Record a URL, page or
  repository path, version/commit when relevant, and access date. Paraphrase;
  do not paste substantial copyrighted text.
- **INFERENCE** — Hearthstead's interpretation of one or more facts. State the
  reasoning and uncertainty; never present it as something the reference mod
  claims.
- **PROPOSAL** — an original clean-room Hearthstead improvement. Link it to the
  local player problem/evidence and an acceptance gate, not merely to a desire
  to resemble the reference.

### Required reference matrix

Each research pass must add or link a matrix with these columns before anyone
asks to implement it:

| ID | Type | Player problem / Hearthstead evidence | Public reference observation | Source URL + version/commit + accessed date | Licence / reuse status | Hearthstead clean-room conclusion | Priority | Verification gate |
|---|---|---|---|---|---|---|---|---|
| Example only | FACT / INFERENCE / PROPOSAL | Exact playtest timestamp, log, screenshot or local file/line | Concise behavioural observation | Direct public source, never a search-result page | Reference-only, CC0, CC-BY, repository licence, or unknown | Original principle or bounded proposal | P0 / P1 / P2 | Measurable automated and/or live gate |

Rows may link to supporting notes, but none of the required fields may be
replaced by "like MineColonies" or "like TekTopia." The matrix must keep facts,
inferences and proposals on separate rows so an inference cannot inherit a
source citation that only proves the underlying fact.

Priority means:

- **P0:** blocks the current playable demo, truthful tutorial, conservation,
  control or stable frame/audio experience.
- **P1:** material quality/readability gap in a core job or settlement loop,
  after the demo remains functional.
- **P2:** optional depth, variety or polish that must not displace P0/P1.

### Required licence register

Each research pass must maintain a licence/provenance register even when every
source remains reference-only:

| Source/asset ID | Creator/rightsholder | Canonical URL | Licence + exact version | Permitted project use | Attribution/notice requirement | Local copy/hash | Decision |
|---|---|---|---|---|---|---|---|

- `Permitted project use` must distinguish **view/reference**, **modified
  redistribution**, **unmodified redistribution**, and **no reuse**.
- `Local copy/hash` must say `none` for ordinary reference research. A local
  copy is allowed only after its licence and project need are explicitly
  approved; then record a cryptographic hash and the original filename.
- An open-source code licence does not automatically licence that project's
  art, sound, trademarks, wiki text or screenshots. Register them separately.

### Output and implementation gate

1. End each reference pass with only **one or two recommended next
   improvements**, ranked against the current Hearthstead backlog. Each must
   identify the player problem, why the reference evidence matters, the
   smallest original Hearthstead change worth testing, risk, and acceptance
   gate.
2. Register and analyse first. Do **not** implement a newly discovered
   requirement, change runtime code, download an asset, regenerate an asset or
   expand scope until Tobias explicitly starts that named improvement. A
   general request to "keep researching" or "continue the audit" is not an
   implementation start.
3. Tobias' start authorizes only the selected proposal. Reconfirm or return to
   the backlog before taking a materially different recommendation.
4. Review results must say what was inspected, what was not accessible, what
   remains uncertain and which claims are facts versus inference. Never imply
   gameplay testing, source inspection or licence clearance that did not
   occur.

## Positive observations to preserve

- The Lumberer sack sits well on the worker in the successful carry state.
- The sack pickup concept and staging are strong.
- The visual direction of that interaction should be refined, not replaced.

## Verification note

The full GameTest sweep completed but contains broad pre-existing suite
interference and 69 failures across unrelated systems, so it is not a green
release gate. Targeted JUnit tests and all asset validators are green. The demo
must still pass a normal client playthrough before any remaining line is called
live verified.

## Linked UI deep research

- [`UI_REFERENCE_RESEARCH_2026-08-28.md`](UI_REFERENCE_RESEARCH_2026-08-28.md)
  records the authorized clean-room FACT/INFERENCE/PROPOSAL matrix for the P0
  UI frame-attribution gate and its conditional shared-backdrop patch. It does
  not authorize a runtime or asset change.

## P1 backlog — starter Handbook before the early progression item

**Status:** registered 2026-08-28; not authorized for implementation by this
entry. Run the mandatory Research -> Build -> Verify -> Loop process before
changing runtime code, data, UI or assets.

### Player outcome

Every eligible player receives exactly one physical Hearthstead Handbook on
their first entry into a singleplayer world or multiplayer server, before the
player is expected to craft, place or use the **Hearth**. The Handbook must
explain the first action, the simple early-game route, exact starter recipes,
core items, assignment flow, blocked-state help and the route to the Hearth
without requiring an external wiki.

### Current-project facts to inspect during research

- A registered Handbook item, screen, English/Norwegian localization and a
  recipe-unlock advancement already exist.
- The demo command currently grants a Handbook, but no ordinary first-join
  delivery path was identified during the registration pass.
- Tobias clarified on 2026-08-28 that **Harp** was a typo for **Hearth**. This
  requirement does not add or rename an item; research must verify the current
  Hearth recipe, placement and founding order and teach that exact flow.

### Required research and affected systems

- Audit onboarding, Handbook chapters, recipe JSON/advancements, the founding
  journey and the real early-item acquisition order.
- Use official Minecraft/NeoForge 1.21.1 documentation for player join/lifecycle,
  saved per-player state, item delivery, recipe display and localization.
- Produce separate FACT / INFERENCE / PROPOSAL rows plus a licence register.
- Expected affected systems: player lifecycle events, persistent exactly-once
  receipt state, safe item delivery/retry, Handbook item/screen/content,
  localization, recipe/progression data, and singleplayer/dedicated-server QA.

### Acceptance gate

1. Fresh singleplayer and first-time multiplayer players each receive exactly
   one Handbook before the relevant progression step.
2. Reconnect, death, respawn, dimension travel and server restart never create
   duplicates.
3. A full inventory cannot destroy or strand the Handbook: a documented,
   observable delivery fallback must eventually make it claimable.
4. The Handbook's recipes and ordering match the actual registered content,
   and English plus every supported locale remain readable.
5. Real-client plus server-authoritative tests cover fresh join, reconnect,
   full inventory, restart and progression ordering in singleplayer and
   multiplayer.

### Decision Tobias will need before implementation

- Choose the preferred full-inventory presentation after research: a safe
  physical drop with a clear message, or a durable pending-delivery/claim lane.

## Later progression — worker hauling equipment

**Status:** deliberately deferred until the enjoyable demo and first-raid loop
are proven. Do not add this complexity to the current demo gate.

Worker carrying should grow through a natural Development path rather than a
flat invisible stat increase. Candidate progression is a basic sack, a larger
reinforced pack/frame, then a handcart or wagon. Every step must visibly change
the worker's equipment and animation, state an exact weight/capacity benefit,
preserve item conservation, and introduce a credible trade-off such as speed,
terrain access or required infrastructure. The later research pass must define
which jobs can use each carrier, how doors/paths/slopes behave, and how the
upgrade is taught and crafted in the Development UI.
