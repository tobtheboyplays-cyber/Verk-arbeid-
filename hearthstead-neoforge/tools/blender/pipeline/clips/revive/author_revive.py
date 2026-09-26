"""Co-op downed / revive PLAYER clips (revive agent, 26 Sep). One Blender process, four clips:

    blender -b --factory-startup --python ../../run_batch.py -- --fast clips/revive/author_revive.py
    blender -b --factory-startup --python author_revive.py -- [--fast|--full] [--no-export]

Contract (agreed with the motion-engine agent):
  * Files: assets/hearthstead/animations/player/<slug>.animation.json, animation name
    "animation.player.<slug>" (library key "player/<slug>").
  * Bones are the vanilla PlayerModel parts (head, body, right_arm, left_arm, right_leg,
    left_leg). They are SIBLINGS under the model root (no torso->arm hierarchy, no bend
    bones), so every track is authored per part.
  * Values are ADDITIVE over the pose the game already forces:
      DOWNED_IDLE / DOWNED_CRAWL  over Pose.SWIMMING (vanilla crawl lays the model prone)
      REVIVE_KNEEL                over Pose.CROUCHING
      GET_UP                      over the standing pose, one-shot, starts at the revive
    Degrees and posVec pixels exactly like SettlerAnimations (pos y up).
  * Client query: DownedClient.playerClip(player) -> (constant, startGameTime, loop).

Preview: the pipeline rig is the settler, so --fast renders the additive tracks on
the settler rig over an approximated base pose (prone root / crouch) -- a motion check,
not a pixel-exact player render.
"""

import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PIPE = os.path.abspath(os.path.join(HERE, "..", ".."))
for p in (PIPE, os.path.join(PIPE, "clips", "life")):
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ.setdefault("HS_LIFE_VIDEOS", r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\revive")

import motionkit as mk  # noqa: E402
import export_mc_clip as ex  # noqa: E402

try:
    import bpy  # noqa: F401,E402
    import hsrig  # noqa: E402
    import lifekit as lk  # noqa: E402
    IN_BLENDER = True
except ImportError:  # plain python: export only (CI / quick checks)
    IN_BLENDER = False

REPO = os.path.abspath(os.path.join(PIPE, "..", "..", ".."))
PLAYER_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead",
                          "animations", "player")
FPS = 60
BONES = ("head", "body", "right_arm", "left_arm", "right_leg", "left_leg")


def tr(keys, t, length=None, default="smooth"):
    """Scalar piecewise track over motionkit.track: keys = [(t, v[, ease])]."""
    vec = [(k[0], (k[1],)) + tuple(k[2:]) for k in keys]
    return mk.track(vec, t, length, default)[0]


def breath(t, period):
    return 0.5 - 0.5 * math.cos(2.0 * math.pi * (t % period) / period)


# --------------------------------------------------------------------------- clips
def downed_idle(t):
    """3.0 s loop: shallow laboured breathing, the head lifting to look for help and
    sagging back, the reaching arm's fingers dragging a little, one leg twitching."""
    L = 3.0
    b = breath(t, 1.5)   # two breaths per loop (fast, laboured)
    head_x = tr([(0.0, -6.0), (0.9, -16.0), (1.6, -18.0), (2.2, -8.0, "out"), (3.0, -6.0)], t, L, "sine")
    head_y = tr([(0.0, 0.0), (1.0, -9.0), (1.8, 7.0), (2.6, 2.0), (3.0, 0.0)], t, L, "sine")
    return {
        "head": {"rot": (head_x - 2.0 * b, head_y, 0.0)},
        "body": {"rot": (-1.5 * b, 0.0, 0.0), "pos": (0.0, 0.35 * b, 0.0)},
        "right_arm": {"rot": (tr([(0.0, 0.0), (1.2, -6.0), (2.4, -2.0), (3.0, 0.0)], t, L, "sine"),
                              0.0, 2.0 * b)},
        "left_arm": {"rot": (1.5 * b, 0.0, -tr([(0.0, 0.0), (1.4, 5.0), (3.0, 0.0)], t, L, "sine"))},
        "right_leg": {"rot": (tr([(0.0, 0.0), (2.3, 0.0), (2.45, 7.0, "out"), (2.8, 1.0), (3.0, 0.0)],
                                 t, L, "sine"), 0.0, 0.0)},
        "left_leg": {"rot": (0.0, 0.0, -1.0 * b)},
    }


def downed_crawl(t):
    """1.2 s loop: a wounded belly-crawl -- one arm reaches and pulls while the
    opposite knee drags up (frog kick), hips wag, the head stays up, bobbing on
    each pull. Additive over vanilla's own crawl arm stroke, so kept moderate."""
    L = 1.2
    ph = 2.0 * math.pi * (t % L) / L
    pull = math.sin(ph)                    # +: right arm pulling, left knee drawing
    pull2 = math.sin(2.0 * ph)             # twice per loop: head bob / body heave
    return {
        "head": {"rot": (-12.0 - 4.0 * abs(pull), 5.0 * pull, 0.0)},
        "body": {"rot": (0.0, 6.0 * pull, 2.0 * pull), "pos": (0.0, 0.4 * max(0.0, pull2), 0.0)},
        "right_arm": {"rot": (14.0 * pull, 0.0, 6.0 * max(0.0, -pull))},
        "left_arm": {"rot": (-14.0 * pull, 0.0, -6.0 * max(0.0, pull))},
        "right_leg": {"rot": (8.0 * max(0.0, -pull), 0.0, 12.0 * max(0.0, -pull))},
        "left_leg": {"rot": (8.0 * max(0.0, pull), 0.0, -12.0 * max(0.0, pull))},
    }


def revive_kneel(t):
    """1.0 s loop over the crouch: lean further over the fallen friend, both hands
    reaching down onto their back and pressing twice (chest-press rhythm), head
    down looking at them, a small weight drop into each press."""
    L = 1.0
    press = tr([(0.0, 0.0), (0.12, 1.0, "in"), (0.24, 0.1, "out"), (0.5, 0.0),
                (0.62, 1.0, "in"), (0.74, 0.1, "out"), (1.0, 0.0)], t, L, "smooth")
    return {
        "head": {"rot": (22.0 + 4.0 * press, 0.0, 0.0)},
        "body": {"rot": (14.0 + 5.0 * press, 0.0, 0.0), "pos": (0.0, -1.0 - 0.8 * press, -0.5)},
        "right_arm": {"rot": (-38.0 - 8.0 * press, 8.0, 6.0), "pos": (0.0, -1.0 - 0.8 * press, -1.0)},
        "left_arm": {"rot": (-38.0 - 8.0 * press, -8.0, -6.0), "pos": (0.0, -1.0 - 0.8 * press, -1.0)},
        "right_leg": {"rot": (-24.0, 0.0, 4.0), "pos": (0.0, -1.0, 0.0)},     # front knee up
        "left_leg": {"rot": (34.0, 0.0, -3.0), "pos": (0.0, -1.0, 1.5)},      # back knee toward the ground
    }


def get_up(t):
    """1.2 s one-shot from the revive: starts folded low (body pitched forward, a
    knee up, hands pushing off the ground), a push-up heave, rises through a lunge
    and settles upright with a shake of the head. Ends exactly at rest."""
    L = 1.2
    u = min(max(t, 0.0), L)
    k = [(0.0, 1.0), (0.25, 0.92, "sine"), (0.6, 0.45, "out"), (1.0, 0.05, "smooth"), (L, 0.0, "smooth")]
    fold = tr(k, u)
    shake = math.sin(2.0 * math.pi * 3.0 * u) * max(0.0, 1.0 - abs(u - 0.95) / 0.18) * 6.0
    return {
        "head": {"rot": (30.0 * fold, shake, 0.0)},
        "body": {"rot": (48.0 * fold, 0.0, 0.0), "pos": (0.0, -7.0 * fold, -2.0 * fold)},
        "right_arm": {"rot": (-62.0 * fold, 0.0, 10.0 * fold), "pos": (0.0, -7.0 * fold, -2.0 * fold)},
        "left_arm": {"rot": (-56.0 * fold, 0.0, -10.0 * fold), "pos": (0.0, -7.0 * fold, -2.0 * fold)},
        "right_leg": {"rot": (-70.0 * fold, 0.0, 0.0), "pos": (0.0, -4.0 * fold, 0.0)},
        "left_leg": {"rot": (20.0 * fold, 0.0, 0.0), "pos": (0.0, -4.0 * fold, 2.0 * fold)},
    }


CLIPS = [
    # const, fn, length, loop, preview base
    ("DOWNED_IDLE", downed_idle, 3.0, True, "prone"),
    ("DOWNED_CRAWL", downed_crawl, 1.2, True, "prone"),
    ("REVIVE_KNEEL", revive_kneel, 1.0, True, "crouch"),
    ("GET_UP", get_up, 1.2, False, "stand"),
]


def sample(fn, length):
    n = int(round(length * FPS))
    times = [i / FPS for i in range(n + 1)]
    return times, [fn(t) for t in times]


def export(const, fn, length, loop, write=True):
    times, samples = sample(fn, length)
    chan = {}
    for bone in BONES:
        chan[bone] = {"rotation": [list(s.get(bone, {}).get("rot", (0, 0, 0))) for s in samples],
                      "position": [list(s.get(bone, {}).get("pos", (0, 0, 0))) for s in samples]}
    name = "animation.player." + const.lower()
    meta = {"source": "tools/blender/pipeline/clips/revive/author_revive.py",
            "rig": "vanilla PlayerModel parts, additive",
            "base_pose": dict((c[0], c[4]) for c in CLIPS)[const],
            "query": "DownedClient.playerClip"}
    doc, report = ex.build_bedrock(name, length, loop, times, chan, rot_tol=0.2, pos_tol=0.015,
                                   keep_times=(0.0, length), meta=meta)
    worst = 0.0
    for bone, kinds in chan.items():
        anim_bone = doc["animations"][name]["bones"].get(bone, {})
        for kind, vecs in kinds.items():
            if kind not in anim_bone:
                continue
            for t, v in zip(times, vecs):
                got = ex.sample(doc, name, bone, kind, t)
                worst = max(worst, max(abs(a - q) for a, q in zip(got, v)))
    checks = {"length": length, "loop": loop, "roundtrip_max_err": round(worst, 4)}
    if loop:
        a, b = samples[0], samples[-1]
        checks["loop_seam"] = round(max(abs(x - y) for bone in BONES for k in ("rot", "pos")
                                        for x, y in zip(a.get(bone, {}).get(k, (0, 0, 0)),
                                                        b.get(bone, {}).get(k, (0, 0, 0)))), 4)
    else:
        checks["ends_at_rest"] = round(max(abs(x) for bone in BONES for k in ("rot", "pos")
                                           for x in samples[-1].get(bone, {}).get(k, (0, 0, 0))), 4)
    path = os.path.join(PLAYER_DIR, const.lower() + ".animation.json")
    if write:
        os.makedirs(PLAYER_DIR, exist_ok=True)
        ex.write(doc, path)
    print("EXPORTED", const, path if write else "(dry run)",
          sum(r["keys"] for r in report.values()), "keys", "CHECKS", json.dumps(checks))
    return times, samples, checks


# --------------------------------------------------------------------------- preview (settler rig)
BASE = {
    "prone": {"root": {"rot": (90.0, 0.0, 0.0), "pos": (0.0, 2.5, 16.0)},
              "head": {"rot": (-40.0, 0.0, 0.0)},
              "right_arm": {"rot": (-165.0, 0.0, 8.0)}, "left_arm": {"rot": (-20.0, 0.0, -10.0)}},
    "crouch": {"root": {"pos": (0.0, -3.0, 0.0)}, "torso": {"rot": (28.0, 0.0, 0.0)},
               "right_leg": {"rot": (-10.0, 0.0, 0.0)}, "left_leg": {"rot": (-10.0, 0.0, 0.0)},
               "right_shin": {"rot": (30.0, 0.0, 0.0)}, "left_shin": {"rot": (30.0, 0.0, 0.0)}},
    "stand": {},
}
TO_SETTLER = {"body": "torso", "head": "head", "right_arm": "right_arm", "left_arm": "left_arm",
              "right_leg": "right_leg", "left_leg": "left_leg"}


def preview_solve(fn, base):
    def solve(t):
        ch = json.loads(json.dumps(BASE[base]))
        ch = {b: {k: tuple(v) for k, v in kinds.items()} for b, kinds in ch.items()}
        add = {TO_SETTLER[b]: kinds for b, kinds in fn(t).items()}
        return mk.add(ch, add)
    return solve


def main():
    a = hsrig.parse_args() if IN_BLENDER else {"fast": False, "full": False,
                                               "export": "--no-export" not in sys.argv}
    summary = {}
    for const, fn, length, loop, base in CLIPS:
        _, _, checks = export(const, fn, length, loop, write=a["export"])
        summary[const] = checks
        if IN_BLENDER and (a["fast"] or a["full"]):
            objs = lk.scene("settler_none.png")
            hsrig.bake(preview_solve(fn, base), length, objs)
            lk.preview("revive_" + const.lower(), length, a, loops=2 if loop else 1)
    print("REVIVE_SUMMARY", json.dumps(summary))


main()
