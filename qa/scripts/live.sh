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
#   live.sh hold <key> <seconds>  hold a key down (walking, mining)
#   live.sh type <text>           type text
#   live.sh cmd <command>         run a command as the player (no leading /)
#   live.sh scmd <command>        run a command on the server console (real TTY)
#   live.sh click [left|right]    click at the centre of the screen
#   live.sh look <dx> <dy>        turn the view
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
hold)   focus; xdotool keydown "$2"; sleep "${3:-1}"; xdotool keyup "$2"; echo "held $2 for ${3:-1}s";;
type)   focus; shift; xdotool type --delay 40 -- "$*"; echo "typed";;
cmd)    focus; shift
        xdotool key --clearmodifiers t; sleep 1
        xdotool type --delay 30 -- "/$*"; sleep 1
        xdotool key --clearmodifiers Return; sleep 1
        # Chat releases the mouse grab and closing it doesn't reliably
        # restore relative-look capture on its own — `safe_regrab` restores
        # it without whatever the crosshair currently holds paying for it.
        safe_regrab
        echo "ran as player: /$*";;
scmd)   shift; srv_send "$*"; echo "ran on server: $*";;
click)  focus; xdotool mousemove 640 360
        xdotool click "$([ "${2:-left}" = right ] && echo 3 || echo 1)"; echo "clicked ${2:-left}";;
look)   focus
        # See playtest.sh's `move` handler: a prior grab-click does not
        # reliably survive to a later `look` several commands on, and even
        # an immediately-preceding click doesn't always take. Regrab-then-
        # send twice: empirically far more reliable than either alone.
        safe_regrab
        xdotool mousemove_relative -- "${2:-0}" "${3:-0}"; sleep 1
        safe_regrab
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
