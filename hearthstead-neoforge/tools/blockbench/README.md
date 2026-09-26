# Blockbench bridge — real Blockbench, headless, for any session

This directory makes the actual Blockbench engine (the industry-standard
Minecraft model/animation tool) usable from a headless session, so model and
animation work can be SEEN and iterated on — the visual half of the quality
loop — without a desktop.

## One-time setup

The renderer is portable across Windows, Linux and macOS. It needs Node 20+,
the local `playwright-core` dependency, a Chromium-family browser, and a built
Blockbench web app served over localhost.

```bash
# Linux/macOS: clone + build the Blockbench web app, then serve it
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 \
    https://github.com/JannisX11/blockbench /path/to/blockbench
cd /path/to/blockbench
ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm install --no-audit --no-fund
npm run build-web
python3 -m http.server 8901 --bind 127.0.0.1 &
```

```powershell
# Windows PowerShell: same build, then keep this hidden server process alive
git clone --depth 1 https://github.com/JannisX11/blockbench C:\tools\blockbench
Set-Location C:\tools\blockbench
$env:ELECTRON_SKIP_BINARY_DOWNLOAD = '1'
npm install --no-audit --no-fund
npm run build-web
Start-Process python -WindowStyle Hidden -WorkingDirectory C:\tools\blockbench `
    -ArgumentList '-m','http.server','8901','--bind','127.0.0.1'
```

In this directory, run `npm install --no-audit --no-fund` once. Browser
discovery covers normal Chrome/Edge installs and Playwright browser caches.
Set `BB_CHROMIUM` to an absolute executable path if discovery cannot find one.

## The workflow

```bash
# Export the settler model + ALL SettlerAnimations.java clips to .bbmodel:
python3 export_bbmodel.py            # -> settler.bbmodel
python3 export_bbmodel.py --check    # fail if the tracked export is stale

# Render the static model (front + back three-quarter views):
node bb_render.mjs /tmp/bb

# Render posed frames of one clip at chosen times (seconds):
node bb_render.mjs /tmp/bb walk 0 0.25 0.5 0.75
node bb_render.mjs /tmp/bb haul_log 0 1.2 2.4

# Add a one-shot over an exact phase of its continuously-running base:
BB_BASE_PHASE=2.6 node bb_render.mjs /tmp/bb-melee \
    --composite GUARD_STANCE MELEE 0 0.1 0.15 0.2 0.25 0.4 0.45 0.5
BB_BASE_PHASE=0.5 node bb_render.mjs /tmp/bb-walk-melee \
    --composite WALK MELEE 0 0.1 0.15 0.2 0.25 0.4 0.45 0.5
```

The same workflow from PowerShell (paths with spaces are supported):

```powershell
python .\export_bbmodel.py
python .\export_bbmodel.py --check
node .\bb_render.mjs "$env:TEMP\hearthstead-bb" chop 0 0.35 0.55
$env:BB_VIEW = 'right'
node .\bb_render.mjs "$env:TEMP\hearthstead-bb-right" melee 0 0.2 0.4 0.5
Remove-Item Env:BB_VIEW
$env:BB_CONTEXT = 'herder_cull'
node .\bb_render.mjs "$env:TEMP\hearthstead-bb-cleave" cleave 0.3 0.45
$env:BB_CONTEXT = 'archer'
node .\bb_render.mjs "$env:TEMP\hearthstead-bb-archer-stance" guard_stance 0 2.8
Remove-Item Env:BB_CONTEXT
```

`BB_BASE_PHASE` is accepted only with `--composite`. It offsets the base
animation's own sampling clock while the overlay remains at the requested
frame time. Use this for one-shots that can begin at arbitrary loop phases;
MELEE's required `GUARD_STANCE` review phases are 0.0, 1.8, 2.6, 3.1 and
3.6 seconds, plus `WALK` interaction. MELEE renders also include a fixed,
Minecraft-sized physical raider and wireframe hit volume. The runner fails if
the physical blade intersects early at 0.10/0.15 s or misses at tick 4 / 0.20
s. These remain offline Candidate images; native slow-motion is mandatory.

Item-bearing work and combat clips automatically receive a K1 held-item proxy
(`axe`, `hammer`, `pickaxe`, `hoe`, `sword`, `bow`, `shears`, or
`fishing_rod`) on the right-hand transform chain. Override inference when
reviewing a special case. If the override differs from the contract, the
runner prints `CONTRACT OVERRIDE`; that image is useful for diagnosis but is
not K1 approval evidence:

```bash
BB_PROP=sword node bb_render.mjs /tmp/bb melee 0 0.2 0.4 0.5
BB_PROP=none node bb_render.mjs /tmp/bb chop 0.4 0.55
BB_VIEW=left node bb_render.mjs /tmp/bb-left chop 0.35 0.55
BB_CONTEXT=butcher node bb_render.mjs /tmp/bb-cleave cleave 0.3 0.45
BB_CONTEXT=guard node bb_render.mjs /tmp/bb-guard-stance guard_stance 0 2.8
BB_CONTEXT=archer node bb_render.mjs /tmp/bb-archer-stance guard_stance 0 2.8
```

The renderer refuses every context-dependent clip unless `BB_CONTEXT` selects
one of the runtime roles declared in `prop_contract.json`. This prevents an
environment variable override from making one convenient prop look universal.

Frame times beyond the selected clip's declared length fail instead of being
silently clamped into duplicate screenshots. Check the length printed after
`selected:` when choosing review frames.

`prop_contract.json` pins both vanilla transform stages for Minecraft 1.21.1:

1. `ItemInHandLayer`: `translateToHand`, X -90°, Y 180°, then
   `[+1,+2,-10]` model pixels for the right hand.
2. The actual item's `thirdperson_righthand` profile, not one universal
   approximation: axe/pickaxe/hoe/sword use `item/handheld`; shears use
   `item/generated`; fishing rod uses `item/handheld_rod`; bow uses the custom
   transform in `item/bow`. The conceptual hammer proxy deliberately follows
   the handheld convention because Minecraft 1.21.1 has no vanilla hammer
   item. Each profile pins its own translation, rotationXYZ and scale before
   the 16×16 item model is centred.

The runner reconstructs both stages as nested Blockbench groups and refuses to
render unless the common layer origin resolves to `[7,12,-2]` and the selected
profile's origin, full orientation basis, item-space centre and local scale
match the pinned transform.
`BB_VIEW=back34`, `BB_VIEW=left` or `BB_VIEW=right` provides an alternate
contact check when the torso hides a tool from the default `front34` camera.
`left` is deliberately a shallow front-left side view (`[-68, 26, -18]`),
not an exact edge-on projection: the exact -X camera hid the right-hand sword
behind this block rig at MELEE's 0.10/0.15 s anticipation beats. The offset is
declared here so evidence never passes by an undisclosed camera trick.
The visible tool is a
voxel silhouette, not Mojang's item texture. Its placement/orientation is
faithful to the selected vanilla profile; pixel art, lighting, state-dependent
model overrides and runtime wiring still require live QA. The bow proxy verifies
the neutral `item/bow` transform but does not emulate the `pulling` predicate or
switch among `bow_pulling_0..2` textures (those child models inherit the same
transform). `HUNTER_LOOSE` now declares a physical candidate contract instead
of a known visual mismatch: a real `MAINHAND` bow follows `right_arm`, the left
arm draws the string from the synced vanilla item-use clock, and the body-only
1.20 s clip leaves both arms to that procedural pose. Tick 14 may broadcast the
release presentation only after a real Arrow enters the level; recovery ends at
tick 24. The animation gate checks those source seams and keeps the result
explicitly pending. It is not visual approval: capture the offline multiview
silhouette and the native pulling-model, release and recovery sequence before
citing the shot as qualified. Root's Hunter draw change to `bb_render.mjs` must
be merged with the separately owned controller-preview route before that
offline evidence is generated.

The same honest-boundary rule covers the remaining runtime/catalogue seams:

- Guard and Archer no longer share one equipment pose. `GUARD_STANCE`,
  `GUARD_PATROL`, `GUARD_HIT_REACT` and `MELEE` use the real MAINHAND sword;
  `ARCHER_STANCE`, `ARCHER_PATROL` and `IDLE_ARCHER` use the real MAINHAND
  bow. `IDLE_SENTRY` remains honestly contextual because a Guard carries a
  sword while a Hunter is empty-handed; render it with `BB_CONTEXT=guard` or
  `BB_CONTEXT=hunter`.
- `SHIELD_BLOCK` is listed in `conditionalRuntimeOffhandClips`. Runtime selects
  it and schedules `shield_thud` only when the synced OFFHAND is a real shield
  (and the MAINHAND is a real sword); otherwise `GUARD_HIT_REACT` plays with a
  free left hand. The offline runner deliberately refuses to render
  `SHIELD_BLOCK` rather than fabricate a shield. Its complete item silhouette
  requires real-client evidence with an actual shield stack.
- `CLEAVE` is context-dependent rather than a sword clip. Butchers are
  empty-handed at runtime; a herder cull carries the profession's shears.
  The renderer therefore refuses `CLEAVE` without `BB_CONTEXT=butcher` or
  `BB_CONTEXT=herder_cull`, and neither result proves the catalogue's missing
  cleaver.

`conceptualProxyClips` are deliberately labelled separately because those
trades currently have empty runtime hands: their proxies help pose review but
must never be cited as proof that an in-game item exists.

Then READ the produced PNGs (the Read tool renders images) and critique the
poses/arcs/silhouette against docs/ANIMATION_CATALOGUE.md before touching
keyframes. Adjust SettlerAnimations.java, re-export, re-render, compare.

## What this is and is not

- The geometry table in export_bbmodel.py is transcribed from
  SettlerModel.createBodyLayer() using the coordinate transform from
  Blockbench's own Java importer (js/formats/java/modded_entity.js) — if the
  Java model changes, update BONES there too.
- **SettlerAnimations.java stays the source of truth.** The .bbmodel is a
  preview/edit surface. If a clip is edited in Blockbench (web or desktop),
  the changed keyframes must be transcribed back into Java — there is no
  automatic import yet.
- The exporter derives stable UUIDs and writes the texture as a portable path
  relative to `tools/blockbench`; unchanged inputs no longer capture a
  machine path, and repeated exports are byte-identical.
- `tools/anim_check.py` gates the K1 contract, clip coverage and transform
  constants without booting a browser. A real Blockbench run additionally
  asserts the neutral matrices and proves that a proxy is visible. The
  in-game `tools/hearthstead-qa live` film remains completion evidence (an
  offline render proves clip data and placement, not in-game wiring).

## Troubleshooting

- Blank viewport: the headless shell needs `--use-angle=swiftshader
  --enable-webgl` (already in the scripts).
- `clip not found`: bb_render lists available names — they are
  `animation.settler.<lowercase_java_name>`.
- Port 8901 busy: any port works — set `BB_URL=http://127.0.0.1:<port>/index.html`.
- Browser not found: set `BB_CHROMIUM` to a Chrome, Edge, or Chromium executable.
  The renderer auto-detects common Windows, macOS, and Linux locations.
- `ItemInHandLayer matrix mismatch`: the exported rig or K1 contract changed;
  do not eyeball around it. Reconcile `SettlerModel.translateToHand`, the
  exporter and `prop_contract.json` before reviewing clips.
