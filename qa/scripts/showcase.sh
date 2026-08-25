#!/usr/bin/env bash
# Turnkey showcase scenes against a RUNNING live session (qa/scripts/live.sh).
#
# Why this exists: filming an animation used to mean knowing the pose keys,
# the yaw conventions, the camera math and the one-shot pulse cadence by
# heart. That knowledge lived in one operator's head, which made every other
# operator slow. Each scene here is one command with the knowledge baked in,
# so driving the camera needs no expertise at all:
#
#   showcase.sh anim <page>     lineup page 0..3, camera placed, filmed 10s
#   showcase.sh anim-all        all four pages back to back
#   showcase.sh village         found a settlement + trees + 5 settlers
#   showcase.sh camp            build & register the lumber camp, hire
#   showcase.sh clean           kill all settlers, clear the stage
#
# Every take lands in the live session's evidence dir (film/take-NN-<label>).
# The row spawns FACING NORTH (the 0.2.0 jar's lineup yaw); the camera is
# therefore placed on the north side looking south. If the lineup yaw is ever
# flipped to face south, flip CAM_SIDE below with it.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIVE="$HERE/live.sh"

# The stage: a fixed spot far from anything the operator may have built at
# spawn, so scenes never contaminate each other or a real village.
SX=200; SY=-60; SZ=200

sc() { "$LIVE" scmd "$1" >/dev/null; }
say() { echo "showcase: $*"; }

# Waits for the client camera to settle after a teleport (KF-006: the client
# lags the server's rotation by a beat; filming too early films the swing).
settle() { sleep 2; }

pulse_loop() { # <seconds> — re-fires one-shot clips every 2s while filming
    local end=$((SECONDS + $1))
    while [ $SECONDS -lt $end ]; do
        sc "execute positioned $SX $SY $SZ run hearthstead pulse"
        sleep 2
    done
}

case "${1:-}" in
anim)
    PAGE="${2:-0}"
    say "lineup page $PAGE"
    sc "kill @e[type=hearthstead:settler]"
    sc "time set 1000"
    # Force-load the stage BEFORE building or spawning on it. Proven live
    # (20260825T183505Z): with the stage chunks unloaded, getHeightmapPos
    # answers the world floor (-64), the whole lineup spawns inside bedrock
    # and quietly suffocates -- "7 settlers posed" and an empty field.
    sc "forceload add $((SX-16)) $((SZ-20)) $((SX+16)) $((SZ+10))"
    sleep 2
    # A flat, even stage: a lumpy horizon reads as noise on film.
    sc "fill $((SX-14)) $((SY-1)) $((SZ-16)) $((SX+14)) $((SY+4)) $((SZ+8)) air"
    sc "fill $((SX-14)) $((SY-1)) $((SZ-16)) $((SX+14)) $((SY-1)) $((SZ+8)) grass_block"
    # Stage the row at the fixed spot; row spawns 4 north of the origin.
    sc "execute positioned $SX.5 $SY $SZ.5 run hearthstead lineup $PAGE"
    sleep 1
    # Camera: south of the row (row z = SZ-4, facing south since the yaw
    # fix), looking north at their fronts.
    sc "tp Dev $SX.5 $((SY+1)).5 $((SZ+5)).5 180 8"
    settle
    # F3+D clears the chat log; F1 would clear MORE (whole HUD) but takes
    # the nameplates with it -- Minecraft.renderNames() is false while the
    # GUI is hidden, and the labels are the entire point of a lineup.
    "$LIVE" key F3+d
    sleep 1
    pulse_loop 12 &
    PL=$!
    HSQA_FILM_LABEL="anim-page$PAGE" "$LIVE" film 10 24
    RC=$?
    kill $PL 2>/dev/null; wait $PL 2>/dev/null
    exit $RC
    ;;
anim-all)
    for p in 0 1 2 3 4; do "$0" anim "$p" || exit 1; done
    ;;
village)
    # The proven core-demo sequence (evidence: live 20260825T183505Z —
    # Heatherbrook: founding, two Registered plaques, two hires, a felled
    # tree and 16 logs chest-true in the hearth). One command instead of
    # twenty-five, so a fresh session can stand the whole scene up in
    # about a minute.
    VX=300; VZ=300
    say "village at $VX,$VZ"
    sc "forceload add $((VX-20)) $((VZ-20)) $((VX+30)) $((VZ+20))"
    sleep 2
    sc "gamerule doDaylightCycle false"
    sc "time set 2000"
    sc "fill $((VX-15)) $((SY-1)) $((VZ-15)) $((VX+25)) $((SY+10)) $((VZ+25)) air"
    sc "fill $((VX-15)) $((SY-1)) $((VZ-15)) $((VX+25)) $((SY-1)) $((VZ+25)) grass_block"
    sleep 1
    # The hearth founds on its own tick (HearthBlockEntity.tryFound).
    sc "setblock $VX $SY $VZ hearthstead:hearth"
    # Real trees, real leaves -- the lumberjack's validateTree wants nature.
    sc "place feature minecraft:oak $((VX-8)) $SY $((VZ-6))"
    sc "place feature minecraft:oak $((VX-11)) $SY $((VZ+2))"
    sc "place feature minecraft:oak $((VX-6)) $SY $((VZ+8))"
    sc "place feature minecraft:oak $((VX-14)) $SY $((VZ-3))"
    # Lumber camp: 7x7 shell, 5x5 interior (floorSpace 16 + furniture).
    sc "fill $((VX+6)) $((SY-1)) $((VZ-3)) $((VX+12)) $((SY+4)) $((VZ+3)) oak_planks hollow"
    sc "setblock $((VX+6)) $SY $VZ oak_door[half=lower,facing=east]"
    sc "setblock $((VX+6)) $((SY+1)) $VZ oak_door[half=upper,facing=east]"
    sc "setblock $((VX+11)) $SY $((VZ+2)) torch"
    sc "setblock $((VX+7)) $SY $((VZ-2)) crafting_table"
    sc "setblock $((VX+7)) $SY $((VZ+2)) chest"
    sc "setblock $((VX+11)) $((SY+1)) $VZ hearthstead:plaque[facing=west]"
    sleep 1
    sc "data merge block $((VX+11)) $((SY+1)) $VZ {Type:\"lumber_camp\",State:\"plan_inserted_unlinked\"}"
    # Warehouse: 8x8 shell, 6x6 interior (floorSpace 25 + 4 chests).
    sc "fill $((VX+6)) $((SY-1)) $((VZ+6)) $((VX+13)) $((SY+4)) $((VZ+13)) oak_planks hollow"
    sc "setblock $((VX+6)) $SY $((VZ+9)) oak_door[half=lower,facing=east]"
    sc "setblock $((VX+6)) $((SY+1)) $((VZ+9)) oak_door[half=upper,facing=east]"
    for dz in 1 2 4 5; do sc "setblock $((VX+12)) $SY $((VZ+6+dz)) chest"; done
    sc "setblock $((VX+7)) $SY $((VZ+12)) torch"
    sc "setblock $((VX+11)) $SY $((VZ+12)) torch"
    sc "setblock $((VX+12)) $((SY+1)) $((VZ+9)) hearthstead:plaque[facing=west]"
    sleep 1
    sc "data merge block $((VX+12)) $((SY+1)) $((VZ+9)) {Type:\"warehouse\",State:\"plan_inserted_unlinked\"}"
    # Founding takes a few seconds; survey both rooms twice, then hire.
    sleep 12
    sc "hearthstead scan $((VX+11)) $((SY+1)) $VZ"; sleep 2
    sc "hearthstead scan $((VX+11)) $((SY+1)) $VZ"; sleep 1
    sc "hearthstead scan $((VX+12)) $((SY+1)) $((VZ+9))"; sleep 2
    sc "hearthstead scan $((VX+12)) $((SY+1)) $((VZ+9))"; sleep 1
    sc "execute positioned $VX $SY $VZ run hearthstead hire $((VX+11)) $((SY+1)) $VZ"
    sleep 1
    sc "execute positioned $VX $SY $VZ run hearthstead hire $((VX+12)) $((SY+1)) $((VZ+9))"
    say "village standing -- watch with: tp Dev $((VX-3)).5 $((SY+6)).0 $((VZ+13)).5 -128 20"
    ;;
clean)
    sc "kill @e[type=hearthstead:settler]"
    sc "hearthstead pose clear" 2>/dev/null || true
    say "stage cleared"
    ;;
*)
    grep '^#   showcase.sh' "$0" | sed 's/^#   //'
    exit 1
    ;;
esac
