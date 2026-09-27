"""Hand-authored core clips (imported by author_clips.py)."""
from clipkit import (merge, pos, rot, stance_keys, legacy_bones, legacy_meta, arm_elbow,
                     leg_ik, channel)

CLIPS = {}


def clip(fn):
    CLIPS[fn.__name__] = fn
    return fn


# -------------------------------------------------------------- idle ---

@clip
def idle():
    L = 4.0
    b = legacy_bones("IDLE", skip=("right_leg", "left_leg"))
    # Relaxed arms are never locked straight: the elbows soften with the sway.
    b["right_forearm"] = rot([(0, [-9, 0, 0], "cat"), (1.3, [-13, 0, 0], "cat"), (1.9, [-12, 0, 0], "cat"),
                              (3.1, [-8, 0, 0], "cat"), (L, [-9, 0, 0], "cat")])
    b["left_forearm"] = rot([(0, [-9, 0, 0], "cat"), (1.3, [-8, 0, 0], "cat"), (1.9, [-8.5, 0, 0], "cat"),
                             (3.1, [-13, 0, 0], "cat"), (L, [-9, 0, 0], "cat")])
    # Contrapposto: while the hips roll onto one foot the free leg softens its knee;
    # the thigh leans back by half so the sole stays under the hip.
    knees_r = [(0, 1.0), (1.3, 7.0), (2.1, 6.0), (3.1, 1.0), (3.6, 1.0), (L, 1.0)]
    knees_l = [(0, 1.0), (1.3, 1.0), (2.1, 1.0), (3.1, 6.5), (3.6, 5.5), (L, 1.0)]
    roll = {0: 0.0, 1.3: -1.6, 2.1: -1.4, 3.1: 1.3, 3.6: 1.1, L: 0.0}
    b["right_leg"] = rot([(t, [-k / 2, 0, roll[t]], "cat") for t, k in knees_r])
    b["left_leg"] = rot([(t, [-k / 2, 0, roll[t]], "cat") for t, k in knees_l])
    b["right_shin"] = rot([(t, [k, 0, 0], "cat") for t, k in knees_r])
    b["left_shin"] = rot([(t, [k, 0, 0], "cat") for t, k in knees_l])
    return L, True, b


# ------------------------------------------------------------ guard ---

GUARD_R_FOOT, GUARD_L_FOOT, GUARD_DROP = -2.0, 2.0, 0.6


def _shift_x(bones, bone, shift):
    for v in bones[bone]["rotation"].values():
        vec = v if isinstance(v, list) else v["post"]
        vec[0] = round(vec[0] + shift, 3)


@clip
def guard_stance():
    L = 4.0
    b = legacy_bones("GUARD_STANCE", skip=("right_leg", "left_leg", "right_arm", "left_arm"))
    # Sword ready: upper arm lower, forearm raised -- the blade rides higher.
    b.update(arm_elbow("GUARD_STANCE", "right_arm", [(0, -38), (0.6, -37), (2.8, -40), (L, -38)],
                       keep_hand=False))
    b.update(arm_elbow("GUARD_STANCE", "left_arm", [(0, -22), (2.8, -26), (L, -22)], keep_hand=False))
    _shift_x(b, "right_arm", 16.0)
    _shift_x(b, "left_arm", 8.0)
    legs = stance_keys([(0, GUARD_DROP, "cat"), (1.4, GUARD_DROP + 0.25, "cat"), (2.8, GUARD_DROP - 0.1, "cat"),
                        (L, GUARD_DROP, "cat")], GUARD_R_FOOT, GUARD_L_FOOT, 3, -3)
    b = merge(b, legs)
    b["torso"]["scale"] = channel([(0, [1, 1, 1], "cat"), (1.8, [1.012, 1.02, 1.012], "cat"),
                                   (L, [1, 1, 1], "cat")])
    return L, True, b


@clip
def guard_patrol():
    L = 4.0
    b = legacy_bones("GUARD_PATROL", skip=("right_arm", "left_arm"))
    b.update(arm_elbow("GUARD_PATROL", "right_arm", [(0, -38), (L, -38)], keep_hand=False))
    b.update(arm_elbow("GUARD_PATROL", "left_arm", [(0, -18), (L, -18)], keep_hand=False))
    _shift_x(b, "right_arm", 16.0)
    _shift_x(b, "left_arm", 6.0)
    return L, True, b


@clip
def melee():
    """Additive over GUARD_STANCE / GUARD_PATROL. Blade contact 0.20 s (tick 4)."""
    L = 0.5
    base_r = leg_ik(GUARD_DROP, GUARD_R_FOOT, 3)
    base_l = leg_ik(GUARD_DROP, GUARD_L_FOOT, -3)
    stance = [(0, 0.0, "io"), (0.1, -0.15, "io"), (0.2, 0.75, "inq"), (0.26, 0.7, "outq"),
              (0.36, 0.2, "io"), (L, 0.0, "io")]
    rl, ll, rs, ls, rp = [], [], [], [], []
    for t, d, e in stance:
        rt, rk = leg_ik(GUARD_DROP + d, GUARD_R_FOOT, 3)
        lt, lk = leg_ik(GUARD_DROP + d, GUARD_L_FOOT, -3)
        rl.append((t, [rt - base_r[0], 0, 0], e))
        ll.append((t, [lt - base_l[0], 0, 0], e))
        rs.append((t, [rk - base_r[1], 0, 0], e))
        ls.append((t, [lk - base_l[1], 0, 0], e))
        rp.append((t, [0, -d, 0], e))
    b = {
        "right_arm": rot([(0, [0, 0, 0], "io"), (0.05, [-10, 3, 9], "outq"), (0.1, [-38, 12, 26], "io"),
                          (0.13, [-40, 12, 26], "io"), (0.2, [-13, -24, 2], "inq"),
                          (0.26, [-7, -34, -4], "outq"), (0.34, [-3, -18, 5], "io"),
                          (0.42, [1, -3, 2], "io"), (L, [0, 0, 0], "io")]),
        "right_forearm": rot([(0, [0, 0, 0], "io"), (0.05, [-14, 0, 0], "outq"), (0.1, [-46, 0, 0], "io"),
                              (0.13, [-48, 0, 0], "io"), (0.2, [24, 0, 0], "inq"), (0.26, [27, 0, 0], "outq"),
                              (0.34, [10, 0, 0], "io"), (0.42, [-2, 0, 0], "io"), (L, [0, 0, 0], "io")]),
        "left_arm": rot([(0, [0, 0, 0], "io"), (0.1, [-8, 2, 2], "io"), (0.2, [15, -2, 4], "inq"),
                         (0.26, [16, -2, 4], "outq"), (0.36, [7, -1, 1.5], "io"), (L, [0, 0, 0], "io")]),
        "left_forearm": rot([(0, [0, 0, 0], "io"), (0.1, [-10, 0, 0], "io"), (0.2, [-18, 0, 0], "inq"),
                             (0.3, [-12, 0, 0], "io"), (L, [0, 0, 0], "io")]),
        "torso": merge(rot([(0, [0, 0, 0], "io"), (0.05, [-2, 4, 0], "outq"), (0.1, [-5, 14, -1], "io"),
                            (0.13, [-6, 15, -1], "io"), (0.2, [-11, -14.5, -3], "inq"),
                            (0.26, [-10, -20, -3], "outq"), (0.34, [-6, -6, -2], "io"),
                            (0.42, [0, 1, 0], "io"), (L, [0, 0, 0], "io")]),
                       pos([(0, [0, 0, 0], "io"), (0.1, [0, 0.1, 0.2], "io"),
                            (0.2, [0, -0.1, -0.9], "inq"), (0.26, [0, -0.1, -1.0], "outq"),
                            (0.36, [0, 0, -0.4], "io"), (L, [0, 0, 0], "io")])),
        "head": rot([(0, [0, 0, 0], "io"), (0.1, [3, -14, 0], "io"), (0.2, [6, 14.5, 0], "inq"),
                     (0.26, [6, 18, 0], "outq"), (0.36, [3, 2, 0], "io"), (L, [0, 0, 0], "io")]),
        "cloak": rot([(0, [0, 0, 0], "io"), (0.1, [-10, 0, -8], "io"), (0.17, [-12, 0, -9], "io"),
                      (0.24, [8, 0, 8], "outq"), (0.3, [10, 0, 8], "io"), (0.4, [-1.5, 0, -1], "io"),
                      (L, [0, 0, 0], "io")]),
        "right_leg": rot(rl), "left_leg": rot(ll), "right_shin": rot(rs), "left_shin": rot(ls),
        "root": pos(rp),
    }
    return L, False, b


# ------------------------------------------------------------ smith ---

@clip
def hammer_anvil():
    """Strike at 0.45 s (tick 9 of 20)."""
    L = 1.0
    legs = stance_keys([(0, 0.4, "io"), (0.15, 0.75, "io"), (0.3, 0.0, "outq"), (0.37, 0.1, "io"),
                        (0.45, 1.5, "inc"), (0.52, 1.3, "outq"), (0.65, 1.2, "io"), (0.85, 0.4, "io"),
                        (L, 0.4, "io")], right_foot_z=-2.2, left_foot_z=2.0, right_splay=3, left_splay=-3)
    b = merge(legs, {
        "right_arm": rot([(0, [-42, -10, -5], "io"), (0.15, [-70, -10, -6], "io"), (0.3, [-168, -8, -8], "outq"),
                          (0.37, [-162, -8, -8], "io"), (0.45, [-34, -13, -4], "inc"),
                          (0.51, [-46, -13, -4], "outq"), (0.62, [-34, -13, -4], "io"),
                          (0.8, [-24, -11, -5], "io"), (L, [-42, -10, -5], "io")]),
        "right_forearm": rot([(0, [-40, 0, 0], "io"), (0.15, [-60, 0, 0], "io"), (0.3, [-55, 0, 0], "outq"),
                              (0.37, [-50, 0, 0], "io"), (0.45, [-12, 0, 0], "inc"), (0.51, [-30, 0, 0], "outq"),
                              (0.62, [-22, 0, 0], "io"), (0.8, [-30, 0, 0], "io"), (L, [-40, 0, 0], "io")]),
        "left_arm": rot([(0, [-46, 20, -3], "io"), (0.3, [-48, 20, -3], "io"), (0.45, [-44, 20, -3], "inc"),
                         (0.5, [-47, 21, -3], "outq"), (0.65, [-46, 21, -3], "io"), (L, [-46, 20, -3], "io")]),
        "left_forearm": rot([(0, [-55, 0, 0], "io"), (0.45, [-52, 0, 0], "inc"), (0.5, [-57, 0, 0], "outq"),
                             (L, [-55, 0, 0], "io")]),
        "torso": rot([(0, [10, 4, 0], "io"), (0.2, [-10, 16, 0], "io"), (0.3, [-18, 19, 0], "outq"),
                      (0.37, [-16, 18, 0], "io"), (0.45, [36, -9, 0], "inc"), (0.52, [32, -7, 0], "outq"),
                      (0.65, [30, -6, 0], "io"), (0.85, [-1, 10, 0], "io"), (L, [10, 4, 0], "io")]),
        "head": rot([(0, [20, 0, 0], "io"), (0.3, [12, 3, 0], "outq"), (0.45, [30, 0, 0], "inc"),
                     (0.6, [27, 0, 0], "io"), (L, [20, 0, 0], "io")]),
        "cloak": rot([(0, [3, 0, 0], "io"), (0.3, [-24, 0, 0], "io"), (0.47, [6, 0, 0], "inq"),
                      (0.57, [21, 0, 0], "outq"), (0.75, [8, 0, 0], "io"), (L, [3, 0, 0], "io")]),
    })
    return L, True, b


# ----------------------------------------------------------- farmer ---

@clip
def farm_till():
    """Hoe bite at 0.60 s (tick 12 of 30)."""
    L = 1.5
    legs = stance_keys([(0, 0.5, "io"), (0.25, 0.4, "io"), (0.45, 0.2, "outq"), (0.5, 0.25, "io"),
                        (0.6, 1.6, "inc"), (0.66, 1.5, "outq"), (0.78, 1.3, "io"), (1.05, 0.8, "io"),
                        (L, 0.5, "io")], right_foot_z=-2.5, left_foot_z=2.5, right_splay=2, left_splay=-2)
    b = merge(legs, {
        "right_arm": rot([(0, [-30, 0, 0], "io"), (0.25, [-58, 0, 0], "io"), (0.45, [-88, 0, 0], "outq"),
                          (0.5, [-86, 0, 0], "io"), (0.6, [-18, 0, 0], "inc"), (0.66, [-22, 0, 0], "outq"),
                          (0.78, [-26, 0, 0], "io"), (1.05, [-24, 0, 0], "io"), (1.3, [-30, 0, 0], "io"),
                          (L, [-30, 0, 0], "io")]),
        "right_forearm": rot([(0, [-34, 0, 0], "io"), (0.25, [-58, 0, 0], "io"), (0.45, [-72, 0, 0], "outq"),
                              (0.5, [-70, 0, 0], "io"), (0.6, [-12, 0, 0], "inc"), (0.66, [-18, 0, 0], "outq"),
                              (0.78, [-34, 0, 0], "io"), (1.05, [-34, 0, 0], "io"), (L, [-34, 0, 0], "io")]),
        "left_arm": rot([(0, [-14, 0, -3], "io"), (0.45, [-40, 0, -3], "outq"), (0.5, [-40, 0, -3], "io"),
                         (0.6, [-34, 0, -3], "inc"), (0.78, [-30, 0, -3], "io"), (L, [-14, 0, -3], "io")]),
        "left_forearm": rot([(0, [-30, 0, 0], "io"), (0.45, [-54, 0, 0], "outq"), (0.6, [-12, 0, 0], "inc"),
                             (0.78, [-28, 0, 0], "io"), (L, [-30, 0, 0], "io")]),
        "torso": rot([(0, [18, 0, 0], "io"), (0.25, [12, 0, 0], "io"), (0.45, [6, 0, 0], "outq"),
                      (0.5, [7, 0, 0], "io"), (0.6, [42, 0, 0], "inc"), (0.66, [40, 0, 0], "outq"),
                      (0.78, [36, 0, 0], "io"), (1.05, [26, 0, 0], "io"), (1.3, [20, 0, 0], "io"),
                      (L, [18, 0, 0], "io")]),
        "head": rot([(0, [4, 0, 0], "io"), (0.45, [10, 0, 0], "outq"), (0.6, [-8, 0, 0], "inc"),
                     (0.78, [-6, 0, 0], "io"), (1.05, [0, 0, 0], "io"), (L, [4, 0, 0], "io")]),
        "cloak": rot([(0, [2, 0, 0], "io"), (0.45, [-4, 0, 0], "io"), (0.62, [8, 0, 0], "inq"),
                      (0.72, [10, 0, 0], "outq"), (1.0, [4, 0, 0], "io"), (L, [2, 0, 0], "io")]),
    })
    return L, True, b


@clip
def farm_harvest():
    """Grab at 0.45 s (tick 9), stow into the back sack at 0.90 s (tick 18)."""
    L = 1.8
    legs = stance_keys([(0, 1.0, "io"), (0.35, 2.6, "io"), (0.45, 2.7, "outq"), (0.6, 1.8, "io"),
                        (0.85, 0.3, "io"), (1.2, 0.4, "io"), (L, 1.0, "io")],
                       right_foot_z=-2.0, left_foot_z=2.5, right_splay=4, left_splay=-4)
    b = merge(legs, {
        "torso": rot([(0, [24, 14, 0], "io"), (0.35, [32, 20, 0], "io"), (0.45, [33, 21, 0], "outq"),
                      (0.7, [4, -18, 0], "io"), (0.85, [1, -21, 0], "outq"), (1.2, [6, -18, 0], "io"),
                      (L, [24, 14, 0], "io")]),
        "right_arm": rot([(0, [-18, 30, 0], "io"), (0.35, [-6, 38, 0], "io"), (0.45, [-10, 41, 0], "io"),
                          (0.85, [-18, 30, 0], "io"), (L, [-18, 30, 0], "io")]),
        "right_forearm": rot([(0, [-30, 0, 0], "io"), (L, [-30, 0, 0], "io")]),
        "left_arm": rot([(0, [-40, -16, 0], "io"), (0.35, [-48, -19, 0], "io"), (0.45, [-44, -19, 0], "outq"),
                         (0.7, [-122, -26, 12], "io"), (0.85, [-158, -29, 16], "outq"),
                         (0.95, [-156, -29, 16], "io"), (1.2, [-100, -26, 14], "io"),
                         (L, [-40, -16, 0], "io")]),
        "left_forearm": rot([(0, [-20, 0, 0], "io"), (0.35, [-12, 0, 0], "io"), (0.45, [-32, 0, 0], "outq"),
                             (0.7, [-72, 0, 0], "io"), (0.85, [-104, 0, 0], "outq"), (0.95, [-100, 0, 0], "io"),
                             (1.2, [-60, 0, 0], "io"), (L, [-20, 0, 0], "io")]),
        "head": rot([(0, [16, 12, 0], "io"), (0.35, [22, 18, 0], "io"), (0.85, [-1, -23, 0], "io"),
                     (1.2, [3, -21, 0], "io"), (L, [16, 12, 0], "io")]),
        "cloak": rot([(0, [3, 0, -4], "io"), (0.35, [8, 0, -9], "io"), (0.9, [7, 0, 14], "io"),
                      (L, [3, 0, -4], "io")]),
    })
    return L, True, b


@clip
def farm_plant():
    """Seed pressed at 0.70 s (tick 14 of 40): a real squat instead of the torso sinking into the belt."""
    L = 2.0
    legs = stance_keys([(0, 0.3, "io"), (0.2, 0.9, "io"), (0.55, 3.0, "io"), (0.7, 3.3, "outq"),
                        (0.85, 3.0, "io"), (1.2, 1.6, "io"), (1.55, 0.5, "io"), (L, 0.3, "io")],
                       right_foot_z=-1.5, left_foot_z=2.0, right_splay=5, left_splay=-5)
    b = merge(legs, {
        "torso": rot([(0, [3, 0, 0], "io"), (0.2, [11, -2, 0], "io"), (0.55, [32, -8, 0], "io"),
                      (0.7, [35, -10, 0], "outq"), (0.85, [32, -8, 0], "io"), (1.2, [16, -4, 0], "io"),
                      (1.55, [5, -1, 0], "io"), (L, [3, 0, 0], "io")]),
        "left_arm": rot([(0, [-6, 8, 4], "io"), (0.2, [-14, 11, 6], "io"), (0.55, [-40, 18, 10], "io"),
                         (0.7, [-50, 22, 12], "inq"), (0.85, [-38, 18, 10], "outq"), (1.2, [-14, 12, 7], "io"),
                         (1.55, [-2, 8, 4], "io"), (L, [-6, 8, 4], "io")]),
        "left_forearm": rot([(0, [-12, 0, 0], "io"), (0.2, [-22, 0, 0], "io"), (0.55, [-22, 0, 0], "io"),
                             (0.7, [-6, 0, 0], "inq"), (0.85, [-24, 0, 0], "outq"), (1.2, [-20, 0, 0], "io"),
                             (L, [-12, 0, 0], "io")]),
        "right_arm": rot([(0, [-6, -8, -3], "io"), (0.55, [-16, -12, -6], "io"), (0.7, [-18, -13, -6], "io"),
                          (1.2, [-10, -10, -4], "io"), (L, [-6, -8, -3], "io")]),
        "right_forearm": rot([(0, [-18, 0, 0], "io"), (0.55, [-32, 0, 0], "io"), (0.7, [-34, 0, 0], "io"),
                              (1.2, [-24, 0, 0], "io"), (L, [-18, 0, 0], "io")]),
        "head": rot([(0, [-2, 0, 0], "io"), (0.55, [14, -3, 0], "io"), (0.7, [18, -4, 0], "outq"),
                     (0.85, [14, -3, 0], "io"), (1.2, [6, -1, 0], "io"), (L, [-2, 0, 0], "io")]),
        "cloak": rot([(0, [2, 0, 0], "io"), (0.55, [16, 0, 3], "io"), (0.7, [19, 0, 4], "io"),
                      (0.95, [15, 0, 2], "io"), (1.4, [5, 0, 0], "io"), (L, [2, 0, 0], "io")]),
    })
    return L, True, b


# ---------------------------------------------------- baker / cook ---

@clip
def knead():
    """Palms bottom out at 0.45 s (tick 9 of 24)."""
    L, _ = legacy_meta("KNEAD")
    b = legacy_bones("KNEAD", skip=("right_arm", "left_arm", "right_leg", "left_leg", "root"))
    b.update(arm_elbow("KNEAD", "right_arm", [(0, -40), (0.25, -72), (0.45, -16), (0.6, -18), (0.8, -66), (1.2, -40)],
                       eases={0.25: "outq", 0.45: "inq", 0.6: "lin", 0.8: "io", 1.2: "io"}))
    b.update(arm_elbow("KNEAD", "left_arm", [(0, -40), (0.3, -16), (0.5, -70), (0.65, -64), (0.9, -20), (1.2, -40)],
                       eases={0.3: "inq", 0.5: "outq", 0.65: "lin", 0.9: "inq", 1.2: "io"}))
    b = merge(b, stance_keys([(0, 0.4, "io"), (0.45, 1.0, "inq"), (0.55, 1.1, "outq"), (0.7, 1.0, "io"),
                              (0.9, 0.9, "inq"), (1.2, 0.4, "io")], -1.5, 1.5, 3, -3))
    return L, True, b


@clip
def cook_stir():
    """Pot accent at 1.20 s (tick 24 of 30); elbows carry the circle."""
    L, _ = legacy_meta("COOK_STIR")
    b = legacy_bones("COOK_STIR", skip=("right_arm", "left_arm", "right_leg", "left_leg", "root"))
    b.update(arm_elbow("COOK_STIR", "right_arm", [(0, -48), (0.3, -58), (0.55, -62), (0.75, -52), (1.0, -40),
                                                  (1.25, -42), (1.5, -48)]))
    b.update(arm_elbow("COOK_STIR", "left_arm", [(0, -48), (1.5, -48)]))
    b = merge(b, stance_keys([(0, 0.35, "cat"), (0.55, 0.5, "cat"), (1.0, 0.3, "cat"), (1.5, 0.35, "cat")],
                             -1.0, 1.2, 2, -2))
    return L, True, b


# ----------------------------------------------------------- herder ---

@clip
def herder_shear():
    """Snip at 0.45 s (tick 9 of 20)."""
    L, _ = legacy_meta("HERDER_SHEAR")
    b = legacy_bones("HERDER_SHEAR", skip=("right_arm", "left_arm", "right_leg", "left_leg", "root"))
    b.update(arm_elbow("HERDER_SHEAR", "right_arm",
                       [(0, -40), (0.15, -44), (0.35, -66), (0.4, -70), (0.45, -20), (0.55, -24), (0.75, -36),
                        (0.9, -40), (1.0, -40)],
                       eases={0.35: "io", 0.4: "outq", 0.45: "inq", 0.55: "outq", 0.75: "io", 0.9: "io"}))
    b.update(arm_elbow("HERDER_SHEAR", "left_arm", [(0, -30), (1.0, -30)]))
    b = merge(b, stance_keys([(0, 1.2, "io"), (0.35, 1.0, "io"), (0.45, 1.6, "inq"), (0.55, 1.5, "outq"),
                              (0.75, 1.1, "io"), (1.0, 1.2, "io")], -2.0, 2.0, 4, -4))
    return L, True, b


# ------------------------------------------------------------ archer ---

@clip
def archer_stance():
    """Ready stance with the bow low in front. The legacy clip keyed both arms at
    +68..+73 deg X, which on this rig swings them BEHIND the back (negative X is
    forward); the bow now rests low and forward with soft elbows."""
    L = 3.2
    b = legacy_bones("ARCHER_STANCE", skip=("right_arm", "left_arm"))
    b["right_arm"] = rot([(0, [-12, -18, -8], "cat"), (1.8, [-14, -19, -8.5], "cat"), (L, [-12, -18, -8], "cat")])
    b["right_forearm"] = rot([(0, [-26, 0, 0], "cat"), (1.8, [-29, 0, 0], "cat"), (L, [-26, 0, 0], "cat")])
    b["left_arm"] = rot([(0, [-16, 14, 8], "cat"), (1.8, [-18, 15, 9], "cat"), (L, [-16, 14, 8], "cat")])
    b["left_forearm"] = rot([(0, [-34, 0, 0], "cat"), (1.8, [-37, 0, 0], "cat"), (L, [-34, 0, 0], "cat")])
    return L, True, b
