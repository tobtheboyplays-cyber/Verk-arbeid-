# The tools we still need, and the standard they have to meet

*"Med den UI toolen så er det standarden. Sjekk om du kan finne flere gode
tools til alle problemene vi har slik vi bare får premium resultat."*
— owner, 2026-08-25.

## What made the UI tool good, stated as a rubric

The UI kit is now the bar. Four properties made it worth building, and any tool
proposed below has to have all four or it is not worth the lines:

1. **It runs offline in about a second.** Iteration you can afford is
   iteration you actually do. Booting the client to look at a panel cost
   minutes, so panels went unlooked-at.
2. **It uses the real thing, not a model of it.** Real sprites, real
   nine-slice rules, the real vanilla font out of the client jar. A tool that
   approximates will flatter you, and a flattering tool is worse than none
   because you trust it.
3. **It fails loudly on a decidable error class.** Not "looks fine" — an exit
   code. Text wider than its box is decidable; *pretty* is not. Pick the
   decidable half and enforce it ruthlessly.
4. **It is wired into the gate.** `validate_assets.py` runs the generator
   twice under different hash seeds and fails on drift. A tool nobody is forced
   to run rots within a month.

Everything below is scored against that rubric.

---

## 1. Animation preview — `tools/anim_preview.py` **(highest value)**

**The problem.** A permanent invariant says every settler task has its own
keyframe clip; there are 23, and 28 building types are coming, each with work
to animate. Today the only check is `anim_check.py` — which proves a clip
*exists* and does not silently fall back to walking — plus booting the client
and watching a settler. Weight, timing and pops cannot be judged from a table
of numbers, so they are judged rarely.

**The tool.** Read the keyframe tables out of `SettlerAnimations.java`, apply
the same interpolation the game applies, pose the settler model, and render an
animated GIF plus a contact sheet of key poses. One command, every clip.

**What it fails on** — all decidable, all the defects that read as *cheap*:
- keyframes out of order, or a clip whose declared length disagrees with its
  last keyframe;
- a **pop**: any joint moving more than a threshold in a single tick;
- a **loop seam**: a looping clip whose last pose is far from its first;
- a clip with no motion at all on the joint its task is named for.

**Rubric.** Offline ✔ · real keyframe data ✔ · decidable failures ✔ · wire into
`animation` stage ✔.

---

## 2. Economy simulator — `tools/sim_economy.py` **(catches the most expensive mistakes)**

**The problem.** Chains, recipes, needs and raid pressure are currently numbers
argued in prose. Nothing anywhere answers "how many farms does a twenty-settler
village need to not starve", so the first time we learn a chain is boring,
impossible, or trivially exploitable is in a play test — after it is built.
Design errors caught after implementation are the most expensive kind there is.

**The tool.** A headless day-by-day simulation over the same tables the game
uses: `Production`'s recipes and tick costs, need decay, worker counts,
courier throughput. Output a table and a verdict.

**What it fails on:**
- a chain that cannot pay for itself — a building whose throughput is worth
  less than the food its own workers eat;
- a recipe loop that produces its own input (free items);
- a settlement configuration that starves under its stated worker counts;
- a bottleneck the design claims is interesting but that never binds.

**The honesty problem, and its answer.** A simulator reading numbers that have
drifted from the game is exactly the "flattering tool" the rubric forbids. So
it parses the real table out of `Production.java` and **fails if it cannot
parse it** — a loud failure, never a silent fallback to stale constants.

**Rubric.** Offline ✔ · real tables ✔ · decidable failures ✔ · new `economy`
stage ✔.

---

## 3. Screenshot regression — `tools/shot_diff.py` **(closes a hole the protocol names)**

**The problem.** The `visual` stage's own message is *"screenshots present;
inspect them before scoring UI"*. That is a human step, every run, and it does
not happen every run. So a visual regression can pass the whole gate.

**The tool.** Perceptual diff of each QA screenshot against a committed
baseline, with a per-shot tolerance, plus a contact sheet of everything that
moved. Baselines are updated deliberately, in a commit, with the diff visible
in review — which is the point: a changed screenshot becomes a decision instead
of an unread artefact.

**What it fails on:** any shot differing from its baseline beyond tolerance
without the baseline being updated in the same change.

**Rubric.** Offline ✔ · real screenshots ✔ · decidable ✔ · replaces the
hand-wave in the `visual` stage ✔.

---

## 4. Decision-trace timeline — `tools/trace_view.py`

**The problem.** The `behavior` stage samples settler decisions and the QA
protocol says to debug from captured evidence. Today that evidence is log
lines. KF-013 — a courier who loaded and never delivered — took a play session
to spot because "idle" is invisible in a wall of text.

**The tool.** Render a trace into a timeline: one row per settler, activity as
coloured bands over time, route failures marked. A settler stuck in one band
for an hour is a shape you see instantly and a line you never notice.

**What it fails on:** a settler in one activity beyond a threshold, a route
failure repeating with the same reason, a goal that never once ran.

**Rubric.** Offline ✔ · real traces ✔ · decidable ✔ · extends `behavior` ✔.

---

## 5. Sound contact sheet — `tools/sound_sheet.py`

**The problem.** Sounds are generated by `gen_sounds.py` and judged by
listening, which means judged rarely and never comparatively. The usual result
is one clip 12 dB hotter than the rest and clipping nobody noticed.

**The tool.** Waveform + spectrogram contact sheet for every sound, with
integrated loudness per clip.

**What it fails on:** samples at full scale (clipping), a clip more than a set
number of LU from the set's median, silence at the head or tail beyond a
threshold.

**Rubric.** Offline ✔ · real audio ✔ · decidable ✔ · extends `assets` ✔.

---

## Order, and why

| # | tool | why now |
|---|---|---|
| 1 | `anim_preview.py` | the biggest upcoming workload is animation, and it is the least verifiable thing we own |
| 2 | `sim_economy.py` | the chains are entirely on paper; wrong numbers cost the most when found late |
| 3 | `shot_diff.py` | the gate currently has a named hole in it |
| 4 | `trace_view.py` | pays off once professions multiply and settlers start idling for subtle reasons |
| 5 | `sound_sheet.py` | real, but the smallest fire |

## What is deliberately not on this list

- **A GUI editor for layouts.** The preview plus a spec written as code is
  faster than a drag-and-drop tool for one person, and code diffs in review.
- **A general "AI reviews the screenshots" step.** Not decidable, so by the
  rubric it is not a gate — it is a habit, and it already exists.
- **A model/rig editor.** `tools/blockbench/` already bridges that.
