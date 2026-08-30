# UI deep research: performance and settlement navigation

**Status:** authorized read-only research completed 2026-08-28. This note does
not authorize or contain a runtime/UI/asset change. It follows the clean-room
standard in `PLAYTEST_BACKLOG_2026-08-28.md`: public primary sources only, no
decompilation, asset extraction, copied layout, copied code or copied wording.

**Target:** Hearthstead on Minecraft 1.21.1 / NeoForge. The local citations are
to the live worktree inspected on 2026-08-28; several cited files are modified
or untracked, so the line references—not repository HEAD—identify that snapshot.

## Decision in one paragraph

There is one justified P0 sequence, not a licence to redesign the UI: first use
the existing bounded native-frame observer to isolate the common background
path from each screen's content; only if that A/B proves the shared background
is the repeatable regression should the active patch replace it with one
Hearthstead-wide, single-pass low-cost backdrop. Static inspection proves that
the background and content are drawn every frame, but it cannot prove which is
the GPU/CPU bottleneck. Development node/edge batching remains P1 until the P0
measurement attributes cost to it.

## Source-backed reference matrix

| ID | Type | Player problem / Hearthstead evidence | Public reference observation | Source URL + version/commit + accessed date | Licence / reuse status | Hearthstead clean-room conclusion | Priority | Performance risk | Verification gate |
|---|---|---|---|---|---|---|---|---|---|
| UI-F1 | **FACT** | Players report FPS loss when a Hearthstead UI opens. `DevelopmentScreen.java:411-431` calls `renderBackground`, draws its window/content, then renders widgets. | NeoForge documents that a screen's `render` path draws its background, widgets and tooltips every frame. It also documents that GUI coordinates vary with resolution and GUI scale and exposes scissor clipping through `GuiGraphics`. | [NeoForge Screens](https://docs.neoforged.net/docs/1.21.1/gui/screens/), docs version **1.21-1.21.1**; public docs repository HEAD observed at `816c03d31ff7948179c7bd4a58d23bcfda09c18a`; accessed 2026-08-28. | Official public documentation, repository MIT. Reference/paraphrase only in this pass; no source or assets copied. | Treat every repeated layer as frame work and test at GUI scales 2/3/4. The source does **not** establish that blur or scissors are slow on Tobias' GPU. | P0 | Choosing a patch from static call counts can optimize the wrong layer. GUI-scale-specific clipping/layout regressions can be missed at one scale. | Separate screen-open transient from warmed steady state; record identical 360-frame windows at GUI scales 2/3/4. |
| UI-F2 | **FACT** | `HsUi.widgets` directly iterates renderables without drawing a background (`HsUi.java:108-113`), while Development makes one explicit `renderBackground` call at `DevelopmentScreen.java:412` before calling that helper at `:431`. `QaClientObserver.java:55-83,367-387` has a bounded 360-frame sampler and reports p50/p95/p99/max and >33/>100 ms counts. | NeoForge's documented render order permits a background before screen content and widgets; it does not require Hearthstead's visual background to be recomputed in a particular style. | [NeoForge Screens, render section](https://docs.neoforged.net/docs/1.21.1/gui/screens/#rendering-the-screen), version 1.21-1.21.1; accessed 2026-08-28. Local worktree paths above inspected 2026-08-28. | NeoForge docs MIT; local Hearthstead source is internal project material. No external implementation copied. | Preserve the worktree's explicit one-background Development path. Use the existing observer rather than introducing an unrelated profiler or visual redesign during P0. | P0 | A capture that mixes open animation/resource warm-up with steady state will overstate or hide the common cost. The observer itself reports correlation, not attribution. | At least two warmed windows for HUD, vanilla inventory, and each Hearthstead screen; identical resolution, FPS cap/VSync, render distance, world position and runtime JAR identity. |
| UI-I1 | **INFERENCE** | The FPS complaint spans multiple UIs, while the large Development tree has its own extra content path. | The common every-frame background is a high-value shared suspect; Development-specific work is a separate suspect. Neither public documentation nor static source inspection identifies the dominant cost. | Derived from UI-F1/UI-F2 and local `DevelopmentScreen.java:508-523,850-912`; accessed 2026-08-28. | Original Hearthstead analysis; no reference expression reused. | Run a controlled layer A/B before assigning blame. Do not call the background, text, item rendering, edges or scissors “the cause” without native frame evidence. | P0 | A false attribution could reduce visual quality while leaving the reported FPS drop intact. | The A/B must change one layer at a time and include a matched control screen plus screenshot/layout parity evidence. |
| UI-P1 | **PROPOSAL** | There is no trustworthy patch choice until the cross-screen regression is isolated. | None; this is an original measurement proposal using existing Hearthstead instrumentation. | Grounded in UI-F1/UI-F2/UI-I1; proposed 2026-08-28. | Original Hearthstead proposal. | **P0-A — attribution gate:** collect two warmed 360-frame windows for HUD, vanilla inventory, Hearth, Settler, Plaque, Equipment Requests, Development idle, pan and zoom. For a diagnostic build, compare the existing single background with a visually obvious diagnostic no-blur/flat-dim toggle, without shipping that toggle as design. | P0 | Diagnostic variants can invalidate results if they also change widget/content work or if different world/camera conditions are used. | Record runtime JAR hash and environment. Itemize every >33 ms frame; no Hearthstead-attributable >100 ms frame. A release patch must meet the existing `max(2 ms, 10%)` p95 regression ceiling versus the comparable vanilla screen. |
| UI-P2 | **PROPOSAL** | If UI-P1 attributes the repeatable regression to the common background, all affected screens need one consistent fix rather than per-screen exceptions. | NeoForge provides the screen/render primitives; it does not prescribe Hearthstead's art direction. | Grounded in UI-F1/UI-F2/UI-I1; proposed 2026-08-28. | Original Hearthstead proposal; no MineColonies/Microsoft layout, code or assets. | **P0-B — conditional shared backdrop:** replace only the proven costly common background layer with one single-pass, low-cost Hearthstead backdrop policy. Preserve current window/cards, one-background invariant, widget order and screen semantics. Do not combine this with node batching, layout changes or animation. | P0, conditional on UI-P1 | Too-light dimming can hurt contrast; too-opaque treatment can hide world context; inconsistent migration can produce double backgrounds; container screens may have different semantic needs. | Before/after native p95/max at GUI scales 2/3/4, pixel/layout capture for every affected screen, all click targets/focus/tooltips reachable, no duplicate background, and no regression beyond `max(2 ms, 10%)` on any screen. If the A/B does not show repeatable benefit, reject P0-B. |
| UI-F3 | **FACT** | Development is a zoomed/pannable spatial tree. A performance patch must not make navigation dependent on mouse hover or visual scanning alone. | XAG 112 recommends predictable logical focus, navigation that tracks changed scale/layout, and an alternate text route for a zoomed map rather than forcing scrolling alone. XAGs are game-accessibility best practices, not a legal compliance certificate. | [Microsoft XAG 112: UI navigation](https://learn.microsoft.com/en-us/xbox/accessibility/xbox-accessibility-guidelines/112), XAG **v3.2**, page updated 2026-03-04; [XAG overview](https://learn.microsoft.com/en-us/xbox/accessibility/guidelines), accessed 2026-08-28. | Microsoft Learn terms; view/reference only. Do not copy text, screenshots or example presentation. | P0 performance work must preserve `AbstractButton` focus/narration, logical traversal after scaling, mouse/keyboard reachability and a clear exit. A supplementary node index/jump route is a later P1 design candidate, not authorized by this research. | P1 guardrail on P0 | Manual rendering/batching can accidentally bypass widget focus, narration or hitboxes even if the screen looks identical. | Keyboard-only traversal at GUI scales 2/3/4; focus order follows visible tree meaning; narration remains populated; focus can always leave a node and Close/Escape remains reachable. |
| UI-F4 | **FACT** | A dense screen becomes harder to read and more expensive if every settlement function is placed on one spatial surface. | MineColonies' official Town Hall documentation exposes a town map from Actions, while events/work orders, citizens and aggregate statistics are described as separate information surfaces. The map shows buildings/citizens but is refreshed from player-supplied maps rather than updating automatically. | [MineColonies Town Hall documentation](https://minecolonies.com/wiki/buildings/townhall/); official wiki repository HEAD observed at `8b35d440c94ccf5ef022f62c3adf723fc4d1dda9`; accessed 2026-08-28. | Official wiki repository declares GPL-3.0. Screenshots, art and trademarks are not assumed separately reusable. **Reference-only / no reuse** for this pass. | Preserve the behavioural principle of task-specific surfaces: Development remains progression, while operations/lists remain elsewhere. Do not copy tabs, dimensions, labels, visuals or map mechanics. The documentation does not state that manual map refresh or separated pages were chosen for performance. | P1 guardrail | Copying or expanding the reference structure would add scope and potentially more render work; inferring a performance motive would be unsupported. | Information-architecture review after the P0 frame gate: one primary purpose per screen, no duplicate live data, and no new runtime work authorized from this row. |

## The two P0 recommendations

### 1. P0-A — isolate the shared cost before patching

- Keep the same runtime JAR, world/camera position, resolution, GUI scale,
  render distance, FPS cap/VSync and warmed resources.
- Record at least two 360-frame steady windows per required screen/state. Keep
  screen-open transients in a separate record.
- Use one-layer diagnostic A/Bs: common background versus content, then—only if
  Development remains an outlier—Development background versus node/edge
  content. Do not change art/layout during attribution.
- Pass/fail remains the backlog's existing frame gate: no repeatable p95 loss
  above `max(2 ms, 10%)` versus the comparable vanilla screen, no attributable
  frame above 100 ms, and every frame above 33 ms itemized.

### 2. P0-B — conditional shared single-pass backdrop

Proceed only if P0-A repeatedly attributes the regression to the background.
Implement one original Hearthstead backdrop policy and migrate the affected
non-container screens consistently. Preserve the existing one-background
invariant, widget order, focus/narration, window/card art and GUI-scale layout.
Reject the change if it does not improve the native frame window or if a visual
or interaction capture regresses. Do not bundle Development scissor/edge
optimizations into this patch; those remain separately measurable P1 work.

## Licence and provenance register

| Source/asset ID | Creator/rightsholder | Canonical URL | Licence + exact version | Permitted project use | Attribution/notice requirement | Local copy/hash | Decision |
|---|---|---|---|---|---|---|---|
| NEO-DOC-SCREENS | NeoForged | [NeoForge 1.21.1 Screens](https://docs.neoforged.net/docs/1.21.1/gui/screens/) | Documentation repository MIT; target docs version 1.21-1.21.1; observed repo HEAD `816c03d31ff7948179c7bd4a58d23bcfda09c18a` | Public view and paraphrased technical reference. MIT redistribution was not needed. | If copied or substantially redistributed later, preserve MIT copyright/licence notice. No copy was made here. | none | Approved as primary reference; no code/assets copied. |
| MC-WIKI-TOWNHALL | LDTTeam / MineColonies contributors | [Official Town Hall page](https://minecolonies.com/wiki/buildings/townhall/) | Wiki repository declares GPL-3.0; observed repo HEAD `8b35d440c94ccf5ef022f62c3adf723fc4d1dda9`. Separate screenshot/art/trademark rights not assumed. | Public view and high-level behavioural reference only; no modified or unmodified redistribution. | No copied material. Any later reuse would require a separate file-level rights and notice audit. | none | Reference-only / no reuse. |
| MS-XAG-112 | Microsoft | [XAG 112](https://learn.microsoft.com/en-us/xbox/accessibility/xbox-accessibility-guidelines/112) | Microsoft Learn Terms of Use; XAG v3.2 | Public view and paraphrased accessibility principle only; no text/image/example redistribution. | Link and name the guideline when documenting the rationale; do not copy protected presentation. | none | Reference-only; best-practice guardrail, not a compliance claim. |

## Scope and uncertainty record

- Inspected: official NeoForge 1.21-1.21.1 screen documentation, official
  MineColonies Town Hall documentation and public wiki licence/status,
  Microsoft XAG v3.2 UI-navigation guidance, and the named live Hearthstead
  source paths.
- Not inspected: distributed MineColonies or TekTopia binaries, decompiled
  source, extracted assets, private material, or third-party reuploads. TekTopia
  was not needed for either P0 decision and was deliberately excluded.
- Not established: which draw layer causes Tobias' FPS loss, the performance
  motive behind any MineColonies UI choice, or a measured improvement from the
  proposed backdrop. Those remain test questions, not facts.
- No assets were downloaded; no runtime code, UI layout, texture, sound or
  animation was changed.

## Development visual loop after owner review

**FACT — rejected:** The first offline Development proposal presented the
founding path as a horizontal sequence of rectangular cards with three large
doctrine cards below it. Tobias rejected the whole direction on 2026-08-28;
the failure was composition, not merely colour or spacing. It did not read as
a living, branching tree before the labels were read.

**FACT — public reference principles:** MineColonies' official research schema
separates named branches, parent requirements, physical costs and effects; its
University documentation says a selected option exposes requirements. Against
the Storm's official design notes describe limited per-level choices with
different effects and resource costs. These are information principles only:
[MineColonies Research](https://minecolonies.com/wiki/tutorials/datapacks/research/),
[MineColonies University](https://minecolonies.com/wiki/buildings/university/),
[Against the Storm Upgraded Living](https://eremitegames.com/upgraded-living-update/).
No layout, code, wording, texture, icon or other asset was copied.

**INFERENCE:** Tree topology has to be readable spatially before prose appears.
Large costs, quest copy and consequences belong in hover/pinned inspection,
while the map itself needs distinct branches, state, route and landmarks.

**PROPOSAL — accepted direction:** Use the original `Living Settlement Tree`:
one numbered founding road grows to a large First Raid gate, then physically
splits into three coloured doctrines and five later endpoints. Hover shows a
bounded summary and highlights the route from the Hearth; click pins a full
inspector. Learned Build Plan nodes expose their authoritative crafting recipe
and real Emblem artwork. This remains tool-preview scope until native runtime,
input, frame-time and multiplayer verification is performed.
