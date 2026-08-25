#!/usr/bin/env python3
"""Reads a clip the way an animator does, without opening anything.

`.claude/skills/animation-quality` states what makes a swing read as heavy —
an accelerating wind-up, a torso that leads the arm, a real beat at contact, a
recovery that overshoots past rest. Until now that was prose, which means it
was checked when somebody remembered to check it.

This turns the decidable half into a report, and (with --strict) into an exit
code. It renders nothing and needs nothing installed: it reads the keyframes
straight out of SettlerAnimations.java. For the half that genuinely needs eyes,
tools/blockbench/ drives real Blockbench headless — see its README.

    python3 tools/anim_preview.py                 # every clip
    python3 tools/anim_preview.py CHOP HAMMER_ANVIL
    python3 tools/anim_preview.py --strict        # non-zero if a clip is limp

What it reports, per clip:

  seam      a looping clip whose last pose differs from its first, which shows
            in game as a jump once per loop
  pop       a large change with no hold on either side -- motion that arrives
            from nowhere and leaves the same way
  beat      the hold at contact. Zero dwell at impact is the single most common
            reason a swing reads as "there but weak"
  lead      whether the torso peaks BEFORE the arm reaches contact. Animating
            the arm alone reads as wrist-only, which is exactly what looks
            weightless
  overshoot whether the recovery passes the rest pose before settling, rather
            than sliding straight back to it
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anim_check import parse_definitions  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ANIMS = os.path.join(HERE, "..", "src", "main", "java", "com", "hearthstead",
                     "client", "model", "SettlerAnimations.java")

TICK = 0.05
# A segment moving faster than this is a strike rather than a transition.
STRIKE_DEG_PER_TICK = 18.0
# A pair of keyframes closer together than this counts as a hold.
HOLD_DEG = 6.0
# Clips whose whole point is an impact. These are held to the beat and lead
# rules; a walk cycle or an idle obviously is not.
IMPACT_CLIPS = {
    "CHOP", "LIMB_BRANCHES", "FARM_TILL", "MELEE", "CLEAVE", "HAMMER_ANVIL",
    "MASON_CHISEL",
}
ARMS = ("right_arm", "left_arm")

# Clips with a recorded, deliberate exception. Same ratchet as job_audit's
# CERTIFIED list: an entry here is a decision somebody made and wrote down, not
# a way to make the build quiet. MELEE is an A1 combat clip whose torso beat
# predates this checker; it is real, it is logged, and fixing it belongs to the
# combat slice rather than to whatever change happens to run next.
ACCEPTED = {"MELEE"}


def biggest_delta(vec_a, vec_b):
    return max(abs(a - b) for a, b in zip(vec_a, vec_b))


def analyse(name, clip):
    notes = []
    rotations = {bone: frames for bone, target, frames in clip["channels"]
                 if target == "ROTATION"}

    # -- seam ------------------------------------------------------------
    if clip["looping"]:
        for bone, frames in rotations.items():
            if len(frames) < 2:
                continue
            gap = biggest_delta(frames[0][2], frames[-1][2])
            if gap > 0.5:
                notes.append(("seam", f"{bone} ends {gap:.1f} deg from where it "
                                      f"starts -- the loop will jump"))

    # -- pop and strike --------------------------------------------------
    strikes = {}
    for bone, frames in rotations.items():
        for i in range(len(frames) - 1):
            t0, _, v0, _ = frames[i]
            t1, _, v1, _ = frames[i + 1]
            ticks = max(1e-6, (t1 - t0) / TICK)
            rate = biggest_delta(v0, v1) / ticks
            if rate < STRIKE_DEG_PER_TICK:
                continue
            strikes.setdefault(bone, []).append((t0, t1, rate))
            # What counts as a POP is narrower than "fast", and getting this
            # wrong in either direction makes the checker useless:
            #
            #   hold -> fast -> hold   is the IDEAL. That is anticipation, the
            #                          strike, and the beat at contact.
            #   ramp -> fast -> ...    is an accelerating wind-up, also ideal.
            #   ...  -> fast -> ...    with neither is motion that arrives from
            #                          nowhere and leaves the same way.
            #
            # So a fast segment is a pop only when it is NOT followed by a beat
            # and NOT preceded by motion building into it. Flagging the first
            # two shapes would train people to write floaty clips to keep the
            # checker quiet, which is worse than having no checker.
            beat_after = (i + 2 < len(frames)
                          and biggest_delta(v1, frames[i + 2][2]) <= HOLD_DEG)
            ramp_before = False
            if i > 0:
                prev_delta = biggest_delta(frames[i - 1][2], v0)
                prev_ticks = max(1e-6, (t0 - frames[i - 1][0]) / TICK)
                ramp_before = prev_delta / prev_ticks >= 2.0
            # The first segment of a ONE-SHOT has nothing before it by
            # definition -- the clip starts wherever the body already was and
            # the engine blends in. Judging it as a pop would mean every
            # one-shot is permanently guilty.
            opening_oneshot = (i == 0 and not clip["looping"])
            if not beat_after and not ramp_before and not opening_oneshot:
                notes.append(("pop", f"{bone} moves {rate:.0f} deg/tick at "
                                     f"{t0:.2f}s with nothing leading in and no "
                                     f"beat after"))

    # -- beat, lead, overshoot -- only meaningful for an impact clip ------
    if name in IMPACT_CLIPS:
        arm = next((b for b in ARMS if b in strikes), None)
        if arm is None:
            notes.append(("beat", "no strike found at all in either arm"))
        else:
            frames = rotations[arm]
            # Contact is NOT simply the first fast segment: on a clip with a
            # big wind-up the wind-up is faster than the strike. It is the fast
            # segment followed by the longest hold, because a hold after a fast
            # move is exactly what a beat at impact IS.
            contact, held, idx = None, -1.0, 0
            for _, seg_end, _ in strikes[arm]:
                where = next(i for i, f in enumerate(frames)
                             if abs(f[0] - seg_end) < 1e-6)
                dwell = 0.0
                for j in range(where, len(frames) - 1):
                    if biggest_delta(frames[j][2], frames[j + 1][2]) <= HOLD_DEG:
                        dwell = frames[j + 1][0] - seg_end
                    else:
                        break
                if dwell > held:
                    contact, held, idx = seg_end, dwell, where
            if contact is None:
                contact, held, idx = strikes[arm][0][1], 0.0, 0
            if held < 2 * TICK - 1e-6:
                notes.append(("beat", f"{arm} holds only {held / TICK:.0f} tick(s) "
                                      f"at contact -- needs 2 or more"))
            torso = rotations.get("torso")
            if torso:
                peak = max(torso, key=lambda f: abs(f[2][0]))[0]
                if peak >= contact:
                    notes.append(("lead", f"torso peaks at {peak:.2f}s, not before "
                                          f"the arm's contact at {contact:.2f}s"))
            rest = frames[0][2][0]
            after_contact = [f[2][0] for f in frames if f[0] > contact]
            if after_contact:
                extreme = max(after_contact, key=lambda v: abs(v - rest))
                if abs(extreme - rest) < 4.0:
                    notes.append(("overshoot",
                                  "recovery slides back to rest without "
                                  "passing it -- no inertia"))
    return notes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("clips", nargs="*")
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()

    defs = parse_definitions(ANIMS)
    wanted = args.clips or sorted(defs)
    missing = [c for c in wanted if c not in defs]
    if missing:
        print("no such clip: " + ", ".join(missing))
        return 2

    flawed = 0
    for name in wanted:
        notes = analyse(name, defs[name])
        clip = defs[name]
        head = (f"{name}  ({clip['length']:.2f}s"
                f"{', loop' if clip['looping'] else ''}, "
                f"{len(clip['channels'])} channels)")
        if not notes:
            print(f"ok    {head}")
            continue
        if name in ACCEPTED:
            print(f"known {head}  (recorded exception)")
            for kind, detail in notes:
                print(f"        {kind:<9} {detail}")
            continue
        flawed += 1
        print(f"WARN  {head}")
        for kind, detail in notes:
            print(f"        {kind:<9} {detail}")
    print()
    print(f"{len(wanted) - flawed} of {len(wanted)} clips read clean")
    return 1 if (flawed and args.strict) else 0


if __name__ == "__main__":
    sys.exit(main())
