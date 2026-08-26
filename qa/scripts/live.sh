#!/usr/bin/env bash
# A PERSISTENT playable session, so the game can be looked at while it runs
# rather than only through a finished screenshot run (AC-6).
#
# D-H1: tmux, not a FIFO writer. One named session `hsqa-live` with three
# windows — xvfb / server / client — each running its process in the
# foreground with a REAL pty. That removes the old `sleep 86400 > fifo`
# writer (which is exactly how PID 1273 leaked in KF-002) and lets the
# server read console commands from a real TTY stdin instead of a pipe.
# tmux's job is process persistence across separate shell invocations only —
# game input still goes through xdotool/X11 against the Xvfb display, same
# as playtest.sh.
#
#   live.sh start                 boot server + client, join, hold the session
#   live.sh status                is it up? what is on screen?
#   live.sh shot <name>           capture the game window right now
#   live.sh key <keys>            send keys (Escape, t, 1, w, shift+w)
#   live.sh hold <key> <seconds>  hold a key down (walking, sneaking) —
#                                  self-verifying, see KF-035 below
#   live.sh mine <seconds>        hold left click (mining/attacking) —
#                                  self-verifying, see KF-035 below. Use this
#                                  instead of raw mousedown/mouseup: it will
#                                  not silently do nothing.
#   live.sh type <text>           type text
#   live.sh cmd <command>         run a command as the player (no leading /)
#   live.sh scmd <command>        run a command on the server console (real TTY)
#   live.sh click [left|right]    click at the centre of the screen (GUI
#                                  slots too — NOT self-verifying, see
#                                  ensure_grab's own comment for why)
#   live.sh look <dx> <dy>        turn the view — self-verifying, see KF-035
#   live.sh film <secs> [fps] [pan]  record motion (AC-5): clip.mp4 + labelled
#                                  contact sheet + motion_ok verdict. `pan` is
#                                  OPT-IN (default: static camera, so a truly
#                                  frozen subject genuinely FAILS motion_ok —
#                                  see the `film)` case for why a forced pan
#                                  was rejected as a default).
#   live.sh stop                  shut everything down (AC-7, always run this)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/lib_harness.sh"
MOD="$HSQA_REPO/hearthstead-neoforge"

TMUX_SESSION="hsqa-live"
ROLE="live"
PORT="${HSQA_LIVE_PORT:-25574}"
DISPLAY_NUM=":99"
export DISPLAY="$DISPLAY_NUM"
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe

STATE="${HSQA_LIVE_STATE:-/tmp/claude-0/hsqa-live-state}"
mkdir -p "$STATE"

# An empty EV_DIR would make every `mkdir -p "$EV_DIR/..."` below operate on
# an absolute path rooted at /. Fail loudly instead: an empty EV_DIR means the
# session state is gone, so there is nothing meaningful to record anyway.
require_ev_dir() {
    [ -n "${EV_DIR:-}" ] || { echo "no live session (state gone) — run: live start" >&2; exit 1; }
}

win() { xdotool search --name "Minecraft" 2>/dev/null | tail -1; }
focus() { local w; w=$(win); [ -n "$w" ] && xdotool windowfocus --sync "$w" 2>/dev/null; }
# D-H4: no window manager here (windowactivate needs EWMH, which is absent),
# and `key --window` is silently discarded by GLFW — focus with windowfocus,
# then always send input through XTEST (xdotool's default target).
grab() { # <outfile>
    local w; w=$(win)
    if [ -n "$w" ]; then import -window "$w" "$1" 2>/dev/null; else import -window root "$1" 2>/dev/null; fi
}
tmux_up() { tmux has-session -t "$TMUX_SESSION" 2>/dev/null; }
srv_send() { tmux send-keys -l -t "$TMUX_SESSION:server" "$1"; tmux send-keys -t "$TMUX_SESSION:server" Enter; }

# Same defect, same fix, as playtest.sh's safe_regrab (see its own comment
# there for the full story: a grab-restoring click used to fire at whatever
# the crosshair currently held, which in creative mode is an instant block
# break -- this destroyed a plaque under manual live-session testing too,
# silently, since nothing here ever checked for it). Loads player/instance
# from $STATE since each `live.sh` invocation is a fresh process.
#
# Second fix, same as playtest.sh's: looking straight up is not enough when
# the player is underground (a roof or natural terrain is still in reach) --
# a block broken there still silently re-surveys any plaque within 32
# blocks (BuildingManager.nudgeNear) and can invalidate it with no chat
# trace at all. Teleport to a fixed height (300) that is guaranteed clear
# above anything this harness builds, click there, then restore the exact
# original position AND rotation via absolute coordinates -- never `~`.
#
# Third fix, same as playtest.sh's: a 1s gap after the up-teleport is not
# always enough for the CLIENT to actually catch up to the new position
# (same class of gotcha KF-006 already named for rotation) -- proven live
# that the click can still fire while the client renders the old,
# underground scene, breaking a block there despite the server already
# considering the player to be at Y=300.
safe_regrab() {
    local inst player pos x y z rot yaw pitch
    inst=$(cat "$STATE/inst" 2>/dev/null)
    player=$(cat "$STATE/player" 2>/dev/null)
    if [ -z "$inst" ] || [ -z "$player" ]; then
        focus; xdotool mousemove 640 360; xdotool click 1; sleep 1
        return
    fi
    srv_send "data get entity $player Pos"
    sleep 1
    pos=$(grep -oP "$player has the following entity data: \[\K[-0-9.]+d, [-0-9.]+d, [-0-9.]+d" \
          "$inst/logs/latest.log" 2>/dev/null | tail -1)
    x=$(echo "$pos" | cut -d',' -f1 | tr -d 'd ')
    y=$(echo "$pos" | cut -d',' -f2 | tr -d 'd ')
    z=$(echo "$pos" | cut -d',' -f3 | tr -d 'd ')

    srv_send "data get entity $player Rotation"
    sleep 1
    rot=$(grep -oP "$player has the following entity data: \[\K[-0-9.]+f, [-0-9.]+f" \
          "$inst/logs/latest.log" 2>/dev/null | tail -1)
    yaw=$(echo "$rot" | cut -d',' -f1 | tr -d 'f ')
    pitch=$(echo "$rot" | cut -d',' -f2 | tr -d 'f ')

    if [ -z "$x" ] || [ -z "$y" ] || [ -z "$z" ] || [ -z "$yaw" ] || [ -z "$pitch" ]; then
        focus; xdotool mousemove 640 360; xdotool click 1; sleep 1
        return
    fi
    # Fourth fix (2026-08-26, found by the first SURVIVAL playthrough):
    # the Y=300 teleport is LETHAL outside creative mode. Every previous fix
    # here was proven against a creative session, where flight is exempt and
    # falling is harmless. In survival the same teleport leaves the player
    # airborne with nothing under them: the vanilla anti-cheat kicked with
    # "was kicked for floating too long" about four seconds after the
    # teleport, the immediate rejoin was kicked again while still airborne,
    # and the third rejoin caught the player mid-fall and killed them
    # ("Dev fell from a high place"). That session's inventory happened to be
    # empty; a later-game player would have dropped everything they carried
    # at the death site. A camera helper must not be able to kill the player.
    #
    # So: only creative sessions get the teleport. In survival, the same
    # goal -- put empty sky under the crosshair before the grab-restoring
    # click -- is met by looking straight up in place. That is weaker (a roof
    # or an overhang can still be in reach underground, which is exactly what
    # the second fix above was written for), so when the player is NOT under
    # open sky the click is skipped entirely rather than risking a broken
    # block: a lost grab costs a retry, a broken plaque costs a silent
    # re-survey nobody sees.
    local mode sky
    srv_send "data get entity $player playerGameType"
    sleep 1
    mode=$(grep -oP "$player has the following entity data: \K[0-9]+" \
           "$inst/logs/latest.log" 2>/dev/null | tail -1)
    if [ "$mode" = "1" ]; then
        srv_send "execute at $player run tp $player ~ 300 ~ $yaw -90"
        sleep 3
        focus; xdotool mousemove 640 360; xdotool click 1; sleep 1
        srv_send "tp $player $x $y $z $yaw $pitch"
        sleep 1
        return
    fi
    # Survival (or unknown, which is treated as survival -- the safe
    # assumption, since guessing creative is the one that kills).
    srv_send "execute at $player run tp $player ~ ~ ~ $yaw -90"
    sleep 1
    srv_send "execute at $player positioned ~ ~2 ~ if block ~ ~ ~ air run say HSQA_SKY_CLEAR"
    sleep 1
    sky=$(grep -c "HSQA_SKY_CLEAR" "$inst/logs/latest.log" 2>/dev/null | tail -1)
    if [ "${sky:-0}" -gt "$(cat "$STATE/sky_seen" 2>/dev/null || echo 0)" ]; then
        echo "$sky" > "$STATE/sky_seen"
        focus; xdotool mousemove 640 360; xdotool click 1; sleep 1
    else
        echo "live.sh: safe_regrab skipped the grab click (survival, no clear sky overhead)" >&2
    fi
    srv_send "tp $player $x $y $z $yaw $pitch"
    sleep 1
}

# Reads a value back from the SERVER, never the screen -- ensure_grab below
# exists specifically because trusting the client's own apparent state is
# what let KF-035 go unnoticed for an entire round.
read_yaw() { # <player> <inst> -> yaw, or empty
    srv_send "data get entity $1 Rotation"
    sleep 1
    local rot
    rot=$(grep -oP "$1 has the following entity data: \[\K[-0-9.]+f, [-0-9.]+f" \
          "$2/logs/latest.log" 2>/dev/null | tail -1)
    echo "$rot" | cut -d',' -f1 | tr -d 'f '
}
read_pos() { # <player> <inst> -> "x, y, z" (with trailing d's), or empty
    srv_send "data get entity $1 Pos"
    sleep 1
    grep -oP "$1 has the following entity data: \[\K[-0-9.]+d, [-0-9.]+d, [-0-9.]+d" \
        "$2/logs/latest.log" 2>/dev/null | tail -1
}

# KF-035 (2026-08-26, round 2): verify-then-regrab, not regrab-and-hope.
# Round 1 found that late in a long session even the pre-existing blind
# regrab click stopped helping -- not because the click itself had stopped
# working, but because nothing ever checked whether it had, so a caller
# kept sending WASD/look/mine into a window that had gone quietly deaf.
# This makes that check load-bearing and automatic instead of something a
# human has to notice after the fact.
#
# Mechanism, confirmed live against this exact round-1 session, not
# assumed: GLFW's X11 disabled-cursor input-capture grab silently desyncs
# from the client's own capture state during a session. When it does,
# world-control input (WASD, jump, mouse-look, mouse buttons) goes
# completely dead while screen-gated keyboard input (chat via `t`, F3)
# keeps working normally -- proof the fault is specifically in the
# grabbed-camera-control gate, not a general input pipe. X11-level delivery
# was independently proven intact THE WHOLE TIME: a bare `xev` window,
# focused at the exact moment gameplay input was dead, received the
# identical xdotool-synthesized key as a real KeyPress (synthetic NO) --
# this rules out the X server and xdotool as the fault. A single real
# click into the window instantly and completely restored BOTH mouse-look
# and WASD together in every case this was tried. No single deterministic
# trigger was pinned down in isolated testing (a held mousedown with
# concurrent screenshot capture reproduced it once; the same sequence
# repeated several times after did not) -- it correlates with sustained
# input/render load on this environment's resource-constrained software-GL
# client (11fps / 92% CPU observed even near-idle, on a 4-core box shared
# with the dedicated server and Xvfb), so the fix is to verify and retry
# rather than chase one exact race.
#
# Verification uses the SAME channel the bug hits (camera rotation) and
# reads the answer back from the server, then undoes its own probe nudge
# so calling this leaves no net aim drift for whoever calls it next.
#
# SAFE TO CALL ONLY WHEN NO GUI SCREEN IS OPEN: a regrab click while
# chat/inventory/a crafting screen is open lands ON that screen, not on
# safe empty space (the whole reason KF-009 cause 1 exists). That is why
# this is wired into `look`/`hold`/`mine`/`cmd` (each only ever called
# in-world, or -- for `cmd` -- immediately after the chat screen it opened
# has already been closed by its own Return) and deliberately NOT wired
# into `click`/`key`, which are also used for GUI slots and menu keys.
ensure_grab() {
    local inst player yaw0 yaw1 attempt max moved
    inst=$(cat "$STATE/inst" 2>/dev/null)
    player=$(cat "$STATE/player" 2>/dev/null)
    if [ -z "$inst" ] || [ -z "$player" ]; then focus; return 0; fi
    max="${HSQA_ENSURE_GRAB_ATTEMPTS:-5}"
    attempt=1
    # A while-loop, not `for attempt in $(seq 1 max)`: the budget counts
    # REGRABS, and every regrab must be followed by one more check before
    # giving up, or the very last regrab's own result is never verified.
    # (Caught live, 2026-08-26: a `for` version reported "still dead after
    # 5 attempts" while a manual check moments later showed the grab was in
    # fact alive — the 5th regrab had worked; nothing re-checked it. That
    # false negative would have told a caller to treat a live session as a
    # WALL. This shape fixes it: check first, only regrab if the check
    # failed AND budget remains, then always loop back to check again.)
    while :; do
        focus
        yaw0=$(read_yaw "$player" "$inst")
        xdotool mousemove_relative -- 40 0
        sleep 0.3
        yaw1=$(read_yaw "$player" "$inst")
        xdotool mousemove_relative -- -40 0   # undo the probe: drift-neutral
        moved=0
        if [ -n "$yaw0" ] && [ -n "$yaw1" ]; then
            moved=$(awk -v a="$yaw0" -v b="$yaw1" 'BEGIN{d=a-b; if(d<0)d=-d; print (d>0.5)?1:0}')
        fi
        if [ "$moved" = "1" ]; then
            if [ "$attempt" -gt 1 ]; then
                echo "ensure_grab: input alive again after $attempt check(s)" >&2
                [ -n "${EV_DIR:-}" ] && check_pass "input_regrab" "recovered after $attempt checks (yaw $yaw0 -> $yaw1)"
            fi
            return 0
        fi
        if [ "$attempt" -ge "$((max + 1))" ]; then
            echo "ensure_grab: input STILL dead after $max regrab attempts -- giving up, caller must surface this, not proceed as if fine" >&2
            [ -n "${EV_DIR:-}" ] && check_fail "input_dead" "grab did not recover after $max regrab attempts"
            return 1
        fi
        echo "ensure_grab: check $attempt -- grab looks dead (yaw $yaw0 -> $yaw1 unchanged), regrabbing (attempt $attempt/$max)" >&2
        safe_regrab
        sleep 0.5
        attempt=$((attempt + 1))
    done
}

# NOT pgrep -f on the -Dhsqa.instanceDir marker: proven live that a java
# process launched via `@argfile` NEVER shows the argfile's contents in
# /proc/PID/cmdline (java expands @-files internally, not the kernel/shell —
# `tr '\0' '\n' < /proc/PID/cmdline` for a real running server showed just
# the literal, unexpanded "@user_jvm_args.txt" token). /proc/PID/cwd is
# reliable instead: the server always runs with the instance dir as its cwd.
server_pid() {
    local inst; inst=$(cat "$STATE/inst" 2>/dev/null) || return
    [ -n "$inst" ] || return
    for p in /proc/[0-9]*; do
        [ "$(readlink -f "$p/cwd" 2>/dev/null)" = "$inst" ] || continue
        basename "$p"; return
    done
}

ev_dir_for_session() {
    [ -f "$STATE/ev_dir" ] && cat "$STATE/ev_dir"
}
# Available to every subcommand below (ensure_grab records checks against
# it when set) without each one having to fetch it; `start` overwrites this
# with its own freshly-`ev_init`'d value a few lines into its own case arm.
EV_DIR=$(ev_dir_for_session)

case "${1:-help}" in

start)
    # Idempotent: tear down any previous session first so a stale one from an
    # earlier aborted start (the exact PID-1273 shape) can never linger and
    # collide with this one. Xvfb specifically IGNORES the SIGHUP that
    # `tmux kill-session` sends its pane (X servers do this deliberately, to
    # survive a terminal disconnect) — proven: it survived `live stop`
    # unkilled and collided with the next `start`'s own Xvfb on the same
    # display. So it needs an explicit SIGKILL, not just the session kill.
    tmux_up && tmux kill-session -t "$TMUX_SESSION" 2>/dev/null
    pkill -9 -f "hsqa\.instanceDir=.*/$ROLE" 2>/dev/null || true
    pkill -9 -f "neoforge.*client" 2>/dev/null || true
    pkill -9 -f "Xvfb $DISPLAY_NUM" 2>/dev/null || true
    sleep 1

    ev_init "$ROLE"
    echo "$EV_DIR" > "$STATE/ev_dir"
    rm -f "$STATE/player"
    # KF-035 (found alongside the input-decay fix, same day): $STATE/sky_seen
    # is a monotonic "have I already seen this many HSQA_SKY_CLEAR lines"
    # counter that safe_regrab (survival branch) writes but never resets. It
    # compares against the FRESH per-instance log's own count on every call,
    # so a value left over from a long previous session can outrun what a
    # new short session's own log will ever reach -- silently disabling the
    # survival-mode regrab click for the entire new session with no error.
    # A brand new instance/log deserves a brand new baseline.
    rm -f "$STATE/sky_seen"

    if ! MSG=$(preflight_port "$PORT" "$ROLE"); then die port_preflight "$MSG"; fi
    check_pass port_preflight "port $PORT free before launch"

    JAR=$(ls -t "$MOD"/build/libs/hearthstead-*.jar 2>/dev/null | grep -v sources | head -1)
    [ -n "$JAR" ] || die build_jar "no mod jar; build first"
    check_pass build_jar "$(basename "$JAR")"

    bash "$HERE/server_install.sh" "$MOD" > "$EV_LOGS/install.log" 2>&1 \
        || die install "shared NeoForge install failed — see logs/install.log"
    check_pass install "shared install present"

    INST=$(bash "$HERE/server_instance.sh" "$ROLE" "$PORT" "$MOD" 2>"$EV_LOGS/instance.log" | tail -1)
    [ -d "$INST" ] || die instance "server_instance.sh failed — see logs/instance.log"
    echo "$INST" > "$STATE/inst"
    check_pass instance "$INST"

    RUN_DIR="$MOD/run"; mkdir -p "$RUN_DIR"
    # rawMouseInput:false: with it true (default), GLFW reads camera look
    # from XInput2 raw motion, which xdotool's XTest-synthesized motion
    # never generates (see playtest.sh for the diagnostic that found this).
    printf 'onboardAccessibility:false\nskipMultiplayerWarning:true\npauseOnLostFocus:false\nguiScale:3\nfullscreen:false\noverrideWidth:1280\noverrideHeight:720\ntutorialStep:none\nrawMouseInput:false\nrenderDistance:6\nsimulationDistance:6\n' \
        > "$RUN_DIR/options.txt"

    # -x/-y: a DETACHED tmux session with no client attached otherwise
    # defaults to a narrow (~80-column) terminal. Proven live: the dedicated
    # server's console is a real readline-style editor, and a Minecraft
    # command longer than the pane width gets corrupted by its line-wrap
    # redraw (a `fill ...` command literally arrived at the server with its
    # middle sliced out and an ANSI cursor-move escape spliced in). A wide
    # pane avoids wrapping for any command this harness sends.
    tmux new-session -d -s "$TMUX_SESSION" -n xvfb -x 500 -y 50 \
        "Xvfb $DISPLAY_NUM -screen 0 1280x720x24 > '$EV_LOGS/xvfb.log' 2>&1"
    tmux new-window -d -t "$TMUX_SESSION" -n server \
        "cd '$INST' && ./run.sh nogui 2>&1 | tee '$EV_LOGS/live-server.log'"
    tmux new-window -d -t "$TMUX_SESSION" -n client \
        "cd '$MOD' && HSQA_JOIN='127.0.0.1:$PORT' ./gradlew runClient 2>&1 | tee '$EV_LOGS/live-client.log'"

    sleep 2
    if ! tmux_up; then die tmux_session "tmux session '$TMUX_SESSION' failed to start"; fi
    check_pass tmux_session "session $TMUX_SESSION up (xvfb/server/client windows)"

    for _ in $(seq 1 90); do
        sleep 2; grep -q 'Done (' "$EV_LOGS/live-server.log" 2>/dev/null && break
    done
    if ! grep -q 'Done (' "$EV_LOGS/live-server.log" 2>/dev/null; then
        REASON=$(grep -m1 -E 'FAILED TO BIND|Address already in use|Exception|Error' "$EV_LOGS/live-server.log" 2>/dev/null || echo "no Done( line")
        die server_started "server never reached Done( : $REASON"
    fi
    check_pass server_started "$(grep -m1 'Done (' "$EV_LOGS/live-server.log")"

    # Same network-stall budget as playtest.sh: minutes, not a hang.
    for _ in $(seq 1 250); do
        sleep 3
        grep -qE "joined the game" "$EV_LOGS/live-server.log" 2>/dev/null && break
    done
    if grep -qE "joined the game" "$EV_LOGS/live-server.log" 2>/dev/null; then
        PLAYER=$(grep -oP '\K\w+(?= joined the game)' "$EV_LOGS/live-server.log" | tail -1)
        echo "$PLAYER" > "$STATE/player"
        srv_send "op $PLAYER"
        sleep 2
        check_pass player_joined "$(grep -m1 'joined the game' "$EV_LOGS/live-server.log")"
        # GLFW does not grab the mouse for relative look until the FIRST
        # real click into the window (proven live: look/move silently do
        # nothing before any click, every time). One harmless click here
        # means every later `look`/`move` invocation across this whole
        # session actually works, in any order.
        focus; xdotool mousemove 640 360; xdotool click 1; sleep 1
        # A joined player is not the same thing as a RUNNING world. Proven
        # live: `start` reported "Dev is in the world", the session was then
        # left unattended, a Slime killed the player, and the client sat on
        # the death screen. A dead player stops holding the surrounding
        # chunks at full ticking, so block entities near them stop ticking
        # too — a hearth placed afterwards never founded its settlement, and
        # nothing moved for anyone watching, while `live status` still
        # cheerfully reported the session up. Everything observed in that
        # state is a frozen world, which silently invalidates any judgement
        # about motion or behaviour made from it.
        #
        # So bringing the session up now also puts the world into a state
        # where it will still be running when someone looks at it, and the
        # last check asserts the player is actually ALIVE rather than merely
        # connected.
        for cmd in "difficulty peaceful" "gamemode creative $PLAYER" \
                   "time set day" "weather clear" "gamerule doMobSpawning false"; do
            srv_send "$cmd"; sleep 1
        done
        srv_send "data get entity $PLAYER Health"; sleep 2
        HEALTH=$(grep -oP "$PLAYER has the following entity data: \\K[0-9.]+(?=f)" \
                 "$INST/logs/latest.log" 2>/dev/null | tail -1)
        case "$HEALTH" in
            ""|0|0.0) die player_alive "player is not alive after join (Health=${HEALTH:-unknown})";;
        esac
        check_pass player_alive "observation state set, Health=$HEALTH"
        finish_result PASS
        write_reproduction "# Reproduce: live start
tools/hearthstead-qa live start
Instance: $INST (port $PORT), player: $PLAYER
Session: tmux $TMUX_SESSION (windows: xvfb/server/client)
Drive with: tools/hearthstead-qa live <status|shot|key|hold|type|cmd|scmd|click|look|film|stop>
"
        echo "LIVE: $PLAYER is in the world. Session held open in tmux ($TMUX_SESSION)."
    else
        grab "$EV_SHOTS/start-failed.png"
        die player_joined "client never joined (see shots/start-failed.png)"
    fi
    ;;

shot)
    EV_DIR=$(ev_dir_for_session); require_ev_dir; EV_SHOTS="$EV_DIR/shots"; mkdir -p "$EV_SHOTS"
    SHOT_NAME="${2:-shot}"
    focus; grab "$EV_SHOTS/$SHOT_NAME.png"
    # Same reasoning as film: a capture that is blank, black or the wrong size
    # must become a FAILED check, otherwise `stop` can only ever derive PASS.
    SHOT_RES=$(python3 "$HERE/check_screenshot.py" "$EV_SHOTS/$SHOT_NAME.png" 2>&1)
    if [ $? -eq 0 ]; then
        check_pass "shot:$SHOT_NAME" "$SHOT_RES"
    else
        check_fail "shot:$SHOT_NAME" "$SHOT_RES"
    fi
    echo "$EV_SHOTS/$SHOT_NAME.png"
    ;;

key)    focus; shift; xdotool key --clearmodifiers "$@"; echo "sent key: $*";;
hold)   # KF-035: verify the grab is alive BEFORE trusting a hold to do
        # anything (see ensure_grab's own comment for the full mechanism).
        # Safe here specifically because `hold` is only ever an in-world
        # action (no GUI is ever driven by holding a key down).
        ensure_grab
        HOLD_INST=$(cat "$STATE/inst" 2>/dev/null); HOLD_PLAYER=$(cat "$STATE/player" 2>/dev/null)
        HOLD_POS0=""
        case "$2" in
            w|a|s|d) [ -n "$HOLD_INST" ] && [ -n "$HOLD_PLAYER" ] && HOLD_POS0=$(read_pos "$HOLD_PLAYER" "$HOLD_INST");;
        esac
        focus; xdotool keydown "$2"; sleep "${3:-1}"; xdotool keyup "$2"
        if [ -n "$HOLD_POS0" ]; then
            HOLD_POS1=$(read_pos "$HOLD_PLAYER" "$HOLD_INST")
            if [ "$HOLD_POS0" = "$HOLD_POS1" ]; then
                echo "hold: position UNCHANGED after holding '$2' for ${3:-1}s ($HOLD_POS0) -- either blocked by geometry, or the grab died mid-hold despite the pre-check. Re-run 'hold' or 'look' to confirm before assuming it worked." >&2
            else
                echo "hold: confirmed movement ($HOLD_POS0 -> $HOLD_POS1)" >&2
            fi
        fi
        echo "held $2 for ${3:-1}s";;
mine)   # KF-035: a first-class mining/attack primitive. PLAYTHROUGH_PROTOCOL
        # previously told drivers to bypass this script entirely and send
        # raw `xdotool mousedown`/`mouseup` for exactly this action — the
        # single highest-risk gap this round closed. Verifies before AND
        # after: before, because a dead grab makes the hold a no-op from
        # the first frame; after, because the one live reproduction this
        # round found happened DURING a held click (concurrent screenshot
        # capture racing the render thread) — so the hold itself can be
        # what kills the grab, not just something already true beforehand.
        ensure_grab
        MINE_SECS="${2:-1.5}"
        focus; xdotool mousemove 640 360
        xdotool mousedown 1
        sleep "$MINE_SECS"
        xdotool mouseup 1
        echo "mined (held left click) for ${MINE_SECS}s"
        ensure_grab >/dev/null 2>&1 \
            || echo "mine: input pipe is STILL dead after this mine, even after retries -- this is a WALL, not a misaim; do not keep retrying blind" >&2
        ;;
type)   focus; shift; xdotool type --delay 40 -- "$*"; echo "typed";;
cmd)    focus; shift
        xdotool key --clearmodifiers t; sleep 1
        xdotool type --delay 30 -- "/$*"; sleep 1
        xdotool key --clearmodifiers Return; sleep 1
        # Chat releases the mouse grab and closing it doesn't reliably
        # restore relative-look capture on its own. KF-035: verify it came
        # back rather than assume a bare regrab click was enough.
        ensure_grab
        echo "ran as player: /$*";;
scmd)   shift; srv_send "$*"; echo "ran on server: $*";;
click)  focus; xdotool mousemove 640 360
        xdotool click "$([ "${2:-left}" = right ] && echo 3 || echo 1)"; echo "clicked ${2:-left}";;
look)   # KF-035: verify-then-move, not move-and-hope. ensure_grab already
        # proves the grab is alive (with real regrab retries + evidence) by
        # the time it returns, so a single real move afterward is both
        # simpler and more trustworthy than the old code's blind "try
        # twice" pattern.
        ensure_grab
        focus
        xdotool mousemove_relative -- "${2:-0}" "${3:-0}"
        echo "looked $2 $3";;

film)
    EV_DIR=$(ev_dir_for_session); require_ev_dir
    # Every take gets its OWN directory. A single `film/` was overwritten by
    # the next film in the same session, so proving a claim that needs two
    # takes — a moving subject that must PASS and a frozen one that must
    # FAIL — destroyed the first take's clip, contact sheet and verdict as
    # soon as the second ran. Optional label via HSQA_FILM_LABEL so a take
    # can say what it was for without disturbing the positional arguments.
    TAKE_N=$(( $(find "$EV_DIR/film" -maxdepth 1 -type d -name 'take-*' 2>/dev/null | wc -l) + 1 ))
    TAKE_LABEL="${HSQA_FILM_LABEL:-}"
    EV_FILM="$EV_DIR/film/$(printf 'take-%02d' "$TAKE_N")${TAKE_LABEL:+-$TAKE_LABEL}"
    mkdir -p "$EV_FILM"
    SECS="${2:-6}"; FPS="${3:-24}"; PAN="${4:-}"
    W=$(win)
    GEOM=$(xdotool getwindowgeometry --shell "$W" 2>/dev/null | grep -E '^(WIDTH|HEIGHT|X|Y)=' | tr '\n' ' ')
    eval "$GEOM"
    # Finding 3: pan is OPT-IN (pass `pan` as the 4th arg), default OFF. A
    # continuous camera pan alone guarantees inter-frame difference — that
    # makes motion_ok pass unconditionally, even for a completely frozen
    # subject, which defeats the actual point of this capability (judging
    # whether an animation moves). Judging animation means pointing a STATIC
    # camera at a settler that is actually idling/walking near the hearth
    # and letting motion_ok reflect the settler's own motion, not the
    # camera's. Kept available (not deleted) because a previous pass DID
    # find a real case — momentarily idle settlers giving median_mad 0.4 —
    # where an operator may deliberately want to prove the CAPTURE pipeline
    # itself works independent of subject motion; that is now an explicit,
    # visible choice instead of a silent default.
    PAN_PID=""
    if [ "$PAN" = "pan" ]; then
        ( STEPS=$((SECS * 4)); for _ in $(seq 1 "$STEPS"); do
            xdotool mousemove_relative -- 40 0 2>/dev/null
            sleep 0.25
          done ) &
        PAN_PID=$!
    fi
    ffmpeg -y -loglevel error -f x11grab -framerate "$FPS" \
        -video_size "${WIDTH:-1280}x${HEIGHT:-720}" -i "$DISPLAY_NUM+${X:-0},${Y:-0}" \
        -t "$SECS" -c:v libx264 -pix_fmt yuv420p "$EV_FILM/clip.mp4" 2>"$EV_FILM/ffmpeg.log"
    [ -n "$PAN_PID" ] && { kill "$PAN_PID" 2>/dev/null; wait "$PAN_PID" 2>/dev/null; }
    if [ ! -s "$EV_FILM/clip.mp4" ]; then echo "film failed (see $EV_FILM/ffmpeg.log)"; exit 1; fi
    ffprobe -v error "$EV_FILM/clip.mp4" >/dev/null 2>"$EV_FILM/ffprobe.log" \
        || { echo "clip.mp4 not decodable (see $EV_FILM/ffprobe.log)"; exit 1; }
    RESULT=$(python3 "$HERE/build_contact_sheet.py" "$EV_FILM/clip.mp4" "$EV_FILM/contact-sheet.png" --frames 12)
    RC=$?
    echo "$RESULT" > "$EV_FILM/motion.json"
    echo "$RESULT"
    # Record the take as a CHECK, not only as a motion.json nobody aggregates.
    # `stop` derives the session verdict from .checks.jsonl, so a take that
    # fails AC-5 has to land there or the derived verdict is unreachable — the
    # session would compute PASS while carrying a failed take on disk.
    TAKE_NAME="film:$(basename "$EV_FILM")"
    if [ $RC -eq 0 ]; then
        check_pass "$TAKE_NAME" "$RESULT"
        echo "film ok: ${SECS}s @ ${FPS}fps -> $EV_FILM/clip.mp4, $EV_FILM/contact-sheet.png"
    else
        check_fail "$TAKE_NAME" "$RESULT"
    fi
    exit $RC
    ;;

status)
    UP="down"; tmux_up && UP="up (tmux session $TMUX_SESSION)"
    echo "session: $UP"
    SPID=$(server_pid)
    if [ -n "$SPID" ]; then echo "server: UP (pid $SPID)"; else echo "server: down"; fi
    pgrep -f "neoforge.*client" >/dev/null && echo "client: UP" || echo "client: down"
    [ -f "$STATE/player" ] && echo "player: $(cat "$STATE/player")"
    EV_DIR=$(ev_dir_for_session)
    if [ -n "$EV_DIR" ]; then
        mkdir -p "$EV_DIR/shots"
        grab "$EV_DIR/shots/status.png" && echo "screen: $EV_DIR/shots/status.png"
    fi
    ;;

stop)
    EV_DIR=$(ev_dir_for_session)
    # $INST is ephemeral (server_instance.sh rm -rf's it on this role's next
    # start) — preserve the authoritative server log before tearing anything
    # down, same reasoning as playtest.sh's teardown().
    INST=$(cat "$STATE/inst" 2>/dev/null)
    if [ -n "$EV_DIR" ] && [ -n "$INST" ] && [ -f "$INST/logs/latest.log" ]; then
        mkdir -p "$EV_DIR/logs"
        cp "$INST/logs/latest.log" "$EV_DIR/logs/live-server-latest.log" 2>/dev/null
    fi
    pkill -9 -f "neoforge.*client" 2>/dev/null || true
    pkill -9 -f "hsqa\.instanceDir=.*/$ROLE" 2>/dev/null || true
    tmux_up && tmux kill-session -t "$TMUX_SESSION" 2>/dev/null
    pkill -9 -f "Xvfb $DISPLAY_NUM" 2>/dev/null || true   # Xvfb ignores SIGHUP
    sleep 1
    # Finding 4: `stop` must RECORD the stop, not DISCARD `start`'s checks.
    # finish_result aggregates from $EV_DIR/.checks.jsonl, which start's
    # check_pass calls already populated and which nothing has deleted — so
    # appending one more check and re-aggregating keeps every earlier one.
    if [ -n "$EV_DIR" ]; then
        check_pass session_stopped "live session stopped and torn down cleanly"
        # AUTO, not a literal "STOPPED": that a session was torn down says
        # nothing about whether it went well, and a hard-coded terminal
        # status made a session carrying a FAILED check read exactly like a
        # clean one. The stop itself is recorded as its own check above.
        finish_result AUTO
    fi
    rm -f "$STATE/ev_dir" "$STATE/player" "$STATE/inst"
    echo "session stopped"
    ;;

*) sed -n '2,26p' "$0";;
esac
