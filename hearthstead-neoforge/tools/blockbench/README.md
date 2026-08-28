# Blockbench bridge — real Blockbench, headless, for any session

This directory makes the actual Blockbench engine (the industry-standard
Minecraft model/animation tool) usable from a headless session, so model and
animation work can be SEEN and iterated on — the visual half of the quality
loop — without a desktop.

## One-time setup (per container)

```bash
# 1. Clone + build the Blockbench web app (public repo, ~1 min total):
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 \
    https://github.com/JannisX11/blockbench /home/user/jannisx11/blockbench
cd /home/user/jannisx11/blockbench
ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm install --no-audit --no-fund
npm run build-web

# 2. Serve it locally (leave running):
python3 -m http.server 8901 --bind 127.0.0.1 &

# 3. Install the driver dep (in THIS directory):
npm install --no-audit --no-fund   # playwright-core only; Chromium is preinstalled
```

## The workflow

```bash
# Export the settler model + ALL SettlerAnimations.java clips to .bbmodel:
python3 export_bbmodel.py            # -> settler.bbmodel

# Render the static model (front + back three-quarter views):
node bb_render.mjs /tmp/bb

# Render posed frames of one clip at chosen times (seconds):
node bb_render.mjs /tmp/bb walk 0 0.25 0.5 0.75
node bb_render.mjs /tmp/bb haul_log 0 1.2 2.4
```

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
- The QA gate does not depend on any of this; it is an authoring aid. The
  in-game `tools/hearthstead-qa live` film remains the completion evidence
  (a Blockbench render proves the clip data, not the in-game wiring).

## Troubleshooting

- Blank viewport: the headless shell needs `--use-angle=swiftshader
  --enable-webgl` (already in the scripts).
- `clip not found`: bb_render lists available names — they are
  `animation.settler.<lowercase_java_name>`.
- Port 8901 busy: any port works — set `BB_URL=http://127.0.0.1:<port>/index.html`.
