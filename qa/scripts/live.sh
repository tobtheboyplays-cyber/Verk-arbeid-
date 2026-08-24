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
    printf 'onboardAccessibility:false\nskipMultiplayerWarning:true\npauseOnLostFocus:false\nguiScale:3\nfullscreen:false\noverrideWidth:1280\noverrideHeight:720\ntutorialStep:none\nrawMouseInput:false\n' \
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
    EV_DIR=$(ev_dir_for_session); EV_SHOTS="$EV_DIR/shots"; mkdir -p "$EV_SHOTS"
    focus; grab "$EV_SHOTS/${2:-shot}.png"; echo "$EV_SHOTS/${2:-shot}.png"
    ;;

key)    focus; shift; xdotool key --clearmodifiers "$@"; echo "sent key: $*";;
hold)   focus; xdotool keydown "$2"; sleep "${3:-1}"; xdotool keyup "$2"; echo "held $2 for ${3:-1}s";;
type)   focus; shift; xdotool type --delay 40 -- "$*"; echo "typed";;
cmd)    focus; shift
        xdotool key --clearmodifiers t; sleep 1
        xdotool type --delay 30 -- "/$*"; sleep 1
        xdotool key --clearmodifiers Return; sleep 1
        # Chat releases the mouse grab and closing it doesn't reliably
        # restore relative-look capture on its own (see playtest.sh) — a
        # harmless click restores it for subsequent look/move calls.
        focus; xdotool mousemove 640 360; xdotool click 1; sleep 1
        echo "ran as player: /$*";;
scmd)   shift; srv_send "$*"; echo "ran on server: $*";;
click)  focus; xdotool mousemove 640 360
        xdotool click "$([ "${2:-left}" = right ] && echo 3 || echo 1)"; echo "clicked ${2:-left}";;
look)   focus
        # See playtest.sh's `move` handler: a prior grab-click does not
        # reliably survive to a later `look` several commands on, and even
        # an immediately-preceding click doesn't always take. Click-then-
        # send twice: harmless (a second identical relative send just adds
        # to an already-adequate rotation change) and empirically far more
        # reliable.
        xdotool mousemove 640 360; xdotool click 1; sleep 2
        xdotool mousemove_relative -- "${2:-0}" "${3:-0}"; sleep 1
        xdotool mousemove 640 360; xdotool click 1; sleep 1
        xdotool mousemove_relative -- "${2:-0}" "${3:-0}"
        echo "looked $2 $3";;

film)
    EV_DIR=$(ev_dir_for_session); EV_FILM="$EV_DIR/film"; mkdir -p "$EV_FILM"
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
    [ $RC -eq 0 ] && echo "film ok: ${SECS}s @ ${FPS}fps -> $EV_FILM/clip.mp4, $EV_FILM/contact-sheet.png"
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
        finish_result STOPPED
    fi
    rm -f "$STATE/ev_dir" "$STATE/player" "$STATE/inst"
    echo "session stopped"
    ;;

*) sed -n '2,26p' "$0";;
esac
