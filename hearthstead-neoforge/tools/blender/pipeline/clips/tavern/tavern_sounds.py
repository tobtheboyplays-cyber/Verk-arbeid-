"""Sound cues of every tavern clip (tavern lane sound pass, 26 Sep). One table, used by the
authoring scripts (tavernkit.export) and by apply_tavern_sounds() to patch shipped JSON in place.

Cues are client-side, positional (ClipSound -> playLocalSound at the settler, NEUTRAL), each with
its own random pitch jitter, and ride the clip's OWN clock - so they land on the contact frames
and, because every patron plays on their own phase / lag / speed, never in unison.
Every id is a hearthstead SoundEvent (registered in ModSounds, listed in sounds.json); the JUnit
TavernSoundCuesTest enforces that.

(t seconds, id, volume, pitch jitter)
"""

import json
import os

H = "hearthstead:"
DRINK, MUG, CLINK, LAUGH, CHEER = H + "tavern.drink", H + "mug_set", H + "tavern.clink", H + "tavern_laugh", H + "cheer"
MURMUR, CHUCKLE, HEH, SLAP = H + "voice.npc.ambient", H + "tavern.chuckle", H + "tavern.heh", H + "tavern.table_slap"
CREAK, RUSTLE, VALVE, POUR = H + "tavern.seat_creak", H + "tavern.cloth_rustle", H + "tavern.tap_valve", H + "tavern.pour"
BARCREAK, WIPE, STEP = H + "tavern.bar_creak", H + "work.bar_wipe", H + "tavern.jig_step"
PUNCH, GRUNT, YES, NO = H + "brawl.punch_hit", H + "brawl.grunt", H + "voice.npc.yes", H + "voice.npc.no"
SCUFF, THUD, GROAN, HICCUP = H + "drunk.scuff", H + "drunk.thud", H + "drunk.groan", H + "tavern.hiccup"


def c(t, sid, vol, jit=0.08):
    return {"t": round(t, 3), "sound": sid, "volume": vol, "pitch_jitter": jit}


def _cheer(put_t, extra=()):
    # "hey!" on the raise, the pair's clink on the contact frame, the swig on the lips, the mug down
    return [c(0.45, CHEER, 0.16, 0.12), c(1.0, CLINK, 0.3, 0.1), c(1.62, DRINK, 0.14, 0.1), c(put_t, MUG, 0.2)] \
        + list(extra)


CUES = {
    # ---- seated
    "seated_idle": [c(4.4, CREAK, 0.05, 0.12)],
    "seated_sip": [c(0.82, DRINK, 0.15, 0.1), c(2.05, MUG, 0.18)],
    "seated_sip_left": [c(0.82, DRINK, 0.15, 0.1), c(2.05, MUG, 0.18)],
    "seated_drink": [c(1.33, DRINK, 0.15, 0.1), c(1.9, DRINK, 0.12, 0.12), c(2.68, MUG, 0.18)]
    + [c(t, SLAP, 0.04, 0.2) for t in (5.0, 5.15, 5.3, 5.45, 6.2, 6.35, 6.5, 6.65)],
    "seated_drink_left": [c(1.33, DRINK, 0.15, 0.1), c(1.9, DRINK, 0.12, 0.12), c(2.68, MUG, 0.18)]
    + [c(t, SLAP, 0.04, 0.2) for t in (5.0, 5.15, 5.3, 5.45, 6.2, 6.35, 6.5, 6.65)],
    "seated_story": [c(1.33, DRINK, 0.15, 0.1), c(2.68, MUG, 0.18), c(4.3, MURMUR, 0.12, 0.1),
                     c(4.95, MURMUR, 0.11, 0.12), c(5.85, MURMUR, 0.14, 0.1), c(6.3, CHUCKLE, 0.12, 0.1)],
    "seated_listen": [c(1.45, DRINK, 0.14, 0.1), c(2.5, MUG, 0.16), c(6.08, LAUGH, 0.3, 0.1),
                      c(6.38, SLAP, 0.3, 0.08), c(6.78, SLAP, 0.28, 0.08)],
    "seated_listen_smile": [c(2.88, DRINK, 0.13, 0.1), c(3.85, MUG, 0.16), c(6.2, HEH, 0.12, 0.12)],
    "seated_listen_chuckle": [c(4.7, DRINK, 0.14, 0.1), c(6.0, MUG, 0.16), c(6.12, CHUCKLE, 0.16, 0.1)],
    "seated_toast": [c(1.1, CHEER, 0.2, 0.1), c(2.5, DRINK, 0.15, 0.1), c(3.0, DRINK, 0.13, 0.12),
                     c(4.05, MUG, 0.26, 0.06), c(5.48, DRINK, 0.13, 0.1), c(6.55, MUG, 0.16)],
    "table_cheer": _cheer(2.55),
    "table_cheer_wipe": _cheer(2.05, [c(2.4, RUSTLE, 0.1, 0.12)]),
    "table_cheer_quick": _cheer(1.8, [c(2.3, CREAK, 0.07, 0.12)]),
    "sleepy_nod": [c(3.05, CREAK, 0.12, 0.1)],
    "seated_fidget_chin": [c(0.6, RUSTLE, 0.06, 0.15)],
    "seated_fidget_stretch": [c(1.0, RUSTLE, 0.1, 0.12), c(1.2, CREAK, 0.09, 0.12)],
    "seated_fidget_shift": [c(0.6, CREAK, 0.12, 0.12), c(1.3, MUG, 0.06, 0.12)],
    # ---- innkeeper
    "idle_innkeeper": [c(0.35, RUSTLE, 0.05, 0.15), c(1.25, RUSTLE, 0.05, 0.15)],
    "idle_innkeeper__v2": [c(t, STEP, 0.05, 0.12) for t in (1.5, 2.25, 3.0, 3.75)],
    "idle_innkeeper__v3": [c(3.8, YES, 0.1, 0.1)],
    "idle_innkeeper__v4": [c(3.1, RUSTLE, 0.08, 0.12)],
    "counter_wipe": [c(t, WIPE, 0.16, 0.12) for t in (0.2, 0.65, 1.1, 1.55, 2.5)],
    "counter_lean": [c(0.3, BARCREAK, 0.06, 0.15), c(4.2, BARCREAK, 0.05, 0.15)],
    "ale_pour": [c(1.12, VALVE, 0.16, 0.08), c(1.2, POUR, 0.2, 0.06), c(2.15, VALVE, 0.14, 0.08)],
    "inn_welcome": [c(0.4, YES, 0.14, 0.08)],
    # ---- revelry / events
    "dance_jig": [c(t, STEP, 0.14, 0.12) for t in (0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75)],
    "brawl_punch": [c(0.34, GRUNT, 0.2, 0.1), c(0.45, PUNCH, 0.3, 0.08)],
    "shoo_birds": [c(0.25, NO, 0.14, 0.1), c(1.47, STEP, 0.16, 0.1)],
    # ---- drunk (the walks stay silent: DrunkSounds schedules hiccups / burps per person)
    "stumble": [c(0.12, SCUFF, 0.22, 0.12), c(0.5, STEP, 0.12, 0.12)],
    "stumble__v2": [c(0.14, SCUFF, 0.2, 0.12), c(0.4, STEP, 0.12, 0.12), c(0.62, STEP, 0.1, 0.12)],
    "fall_forward": [c(0.3, SCUFF, 0.14, 0.12), c(0.95, THUD, 0.4, 0.08), c(1.95, GROAN, 0.22, 0.1),
                     c(3.2, RUSTLE, 0.1, 0.12), c(4.4, HICCUP, 0.12, 0.1)],
    "fall_side": [c(0.3, SCUFF, 0.14, 0.12), c(0.95, THUD, 0.4, 0.08), c(2.05, GROAN, 0.22, 0.1),
                  c(3.3, RUSTLE, 0.1, 0.12)],
    "drunk_lean": [c(2.2, HICCUP, 0.1, 0.12)],
    "drunk_sit": [c(0.9, THUD, 0.22, 0.12), c(2.5, GROAN, 0.16, 0.12), c(4.8, RUSTLE, 0.08, 0.12)],
}


def cues(slug):
    return CUES.get(slug)


def ids():
    return sorted({x["sound"] for v in CUES.values() for x in v})


def apply_tavern_sounds(anim_dir):
    """Replace hearthstead_sounds in the shipped JSON of every clip listed above."""
    done = []
    for slug, cue in CUES.items():
        path = os.path.join(anim_dir, slug + ".animation.json")
        if not os.path.exists(path):
            continue
        doc = json.load(open(path, encoding="utf-8"))
        anim = list(doc["animations"].values())[0]
        L = float(anim["animation_length"])
        assert all(0.0 <= x["t"] <= L for x in cue), (slug, L)
        anim["hearthstead_sounds"] = cue
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(doc, fh, indent=1)
            fh.write("\n")
        done.append(slug)
    return done


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.abspath(os.path.join(here, "..", "..", "..", "..", ".."))
    anim = os.path.join(repo, "src", "main", "resources", "assets", "hearthstead", "animations", "settler")
    print("PATCHED", apply_tavern_sounds(anim))
    print("IDS", ids())
