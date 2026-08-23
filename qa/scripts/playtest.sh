#!/usr/bin/env bash
# In-game playtest harness: boots a real dedicated server + real client under
# Xvfb, drives the client with synthetic input, and proves every observable
# server-side rather than trusting the client's own idea of what happened.
#
# Ordered fact ladder (AC-13): preflight port -> jar built -> install ->
# instance -> Xvfb -> server "Done (" -> client build not broken (N2) ->
# player joined, server-side (AC-1, N4) -> scenario directives, each of
# which can itself assert (AC-14) -> teardown, always (AC-7).
#
# Args: <mod-dir> <artifact-dir> [scenario]
#   scenario directives, one per line:
#     wait <seconds>                      let the game run
#     key <xdotool keyspec>               send a key
#     type <text>                         type text (chat/commands)
#     cmd <command without slash>         open chat, type /command, submit (as player)
#     scmd <command>                      run a command on the server console
#     click [left|right]                  click at screen centre
#     move <dx> <dy>                      move the mouse (look around)
#     shot <name>                         capture shots/<name>.png
#     expect_server <regex>               FAIL unless regex is in the server log (AC-14)
#     expect_shot <name>                  FAIL unless shots/<name>.png passes AC-3
#     expect_pixel_change <before> <after> <min-pct> [region]
#                                          FAIL unless the two named shots differ by
#                                          more than min-pct in `region` (lower-third|full)
#     expect_rotation_change <min-degrees>
#                                          FAIL unless the two most recent server-side
#                                          `data get entity $PLAYER Rotation` results
#                                          (bracket a `look` with two such scmd calls)
#                                          differ in yaw by more than min-degrees
#     expect_block_near_player <block-id> <radius>
#                                          issues a BARE `fill` (not `execute at
#                                          ... run fill` — proven to swallow all
#                                          feedback, silently) over a box of the
#                                          given radius around the most recent
#                                          `data get entity $PLAYER Pos`, replacing
#                                          <block-id> with itself; FAILs unless it
#                                          reports replacing >=1 block
#   $PLAYER in any directive's arguments is substituted with the joined player's name.
set -u
MOD="$1"; OUT="$2"; SCENARIO="${3:-}"
HERE="$(dirname "${BASH_SOURCE[0]}")"
. "$HERE/lib_harness.sh"
REPO="$HSQA_REPO"
[ -n "$SCENARIO" ] || SCENARIO="$REPO/qa/scenarios/default.txt"

ROLE="playtest"
PORT="${HSQA_PLAYTEST_PORT:-25573}"
DISPLAY_NUM=":98"
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export DISPLAY="$DISPLAY_NUM"

ev_init "$ROLE"

command -v xdotool >/dev/null || { echo "FAIL: xdotool missing"; exit 1; }
[ -f "$SCENARIO" ] || { echo "FAIL: scenario not found: $SCENARIO"; exit 1; }

TMUX_PT="hsqa-playtest"
TEARDOWN_DONE=0
teardown() {
    [ "$TEARDOWN_DONE" = 1 ] && return
    TEARDOWN_DONE=1
    [ -n "${GRADLE_PID:-}" ] && kill -9 -- "-$GRADLE_PID" 2>/dev/null
    pkill -9 -f "neoforge.*client" 2>/dev/null || true
    tmux has-session -t "$TMUX_PT" 2>/dev/null && tmux kill-session -t "$TMUX_PT" 2>/dev/null
    pkill -9 -f "hsqa.instanceDir=.*/$ROLE" 2>/dev/null || true
    [ -n "${XVFB_PID:-}" ] && kill -9 "$XVFB_PID" 2>/dev/null
    clear_pidfile "$ROLE"
    clear_pidfile "${ROLE}-xvfb"
}
trap teardown EXIT INT TERM

# FACT 1: port free.
if ! MSG=$(preflight_port "$PORT" "$ROLE"); then die port_preflight "$MSG"; fi
check_pass port_preflight "port $PORT free before launch"

JAR=$(ls -t "$MOD"/build/libs/hearthstead-*.jar 2>/dev/null | grep -v sources | head -1)
[ -n "$JAR" ] || die build_jar "no mod jar built — run build first"
check_pass build_jar "$(basename "$JAR")"

bash "$HERE/server_install.sh" "$MOD" > "$EV_LOGS/install.log" 2>&1 \
    || die install "shared NeoForge install failed — see logs/install.log"
check_pass install "shared install present"

INST=$(bash "$HERE/server_instance.sh" "$ROLE" "$PORT" "$MOD" 2>"$EV_LOGS/instance.log" | tail -1)
[ -d "$INST" ] || die instance "server_instance.sh did not produce a usable instance — see logs/instance.log"
check_pass instance "$INST"

Xvfb "$DISPLAY_NUM" -screen 0 1280x720x24 > "$EV_LOGS/xvfb.log" 2>&1 &
XVFB_PID=$!
register_pid "${ROLE}-xvfb" "$XVFB_PID"
sleep 2
kill -0 "$XVFB_PID" 2>/dev/null || die xvfb "Xvfb failed to start — see logs/xvfb.log"
check_pass xvfb "Xvfb :98 up (pid $XVFB_PID)"

# The client's game directory is run/ (that is where its logs and saves
# land), NOT run/client — options written anywhere else are silently
# ignored, which leaves the accessibility onboarding screen up and blocks
# quickPlay entirely (KF-006). Written deterministically every run so the
# window is always exactly 1280x720 (regression risk: the client rewrites
# this file on its own exit).
# rawMouseInput:false matters more than it looks: with it true (the
# default), GLFW reads camera look from XInput2 raw motion events, which
# xdotool's XTest-synthesized motion never generates — `look`/`move`
# directives silently produce zero rotation change with it on (proven: a
# live diagnostic session showed the mouse's X11 pointer position moving
# correctly while server-side Rotation stayed exactly [0.0f, 0.0f]). With it
# false, GLFW falls back to ordinary pointer-motion deltas, which XTest does
# drive.
RUN_DIR="$MOD/run"
mkdir -p "$RUN_DIR"
cat > "$RUN_DIR/options.txt" <<'OPTS'
onboardAccessibility:false
skipMultiplayerWarning:true
pauseOnLostFocus:false
guiScale:3
fullscreen:false
overrideWidth:1280
overrideHeight:720
tutorialStep:none
rawMouseInput:false
OPTS

# Drive the server through a tmux window (same mechanism as live.sh's D-H1
# design) so scenarios can issue console commands (op, gamemode, summon,
# data get, fill) without needing an already-privileged player, with a real
# TTY. A plain FIFO was tried first and proved unreliable for this — a
# console command sent minutes into an otherwise-healthy session sometimes
# never reached the server's command processor at all, no error, nothing in
# the log, reproduced directly against an isolated instance. tmux is already
# a hard dependency (live.sh) and has been proven reliable here once given a
# wide pane (see the -x/-y note below). 'nogui' matters: with DISPLAY set,
# the dedicated server would otherwise open its Swing console on the same
# virtual screen and steal synthetic input meant for the game.
# Idempotent: in case a previous invocation's session leaked.
tmux has-session -t "$TMUX_PT" 2>/dev/null && tmux kill-session -t "$TMUX_PT" 2>/dev/null
# -x/-y: a detached tmux session defaults to a narrow (~80-column) terminal;
# a Minecraft command longer than the pane width gets corrupted by the
# console's line-wrap redraw (proven live: a `fill` command arrived with an
# ANSI cursor-move escape spliced into its middle). A wide pane avoids it.
tmux new-session -d -s "$TMUX_PT" -n server -x 500 -y 50 \
    "cd '$INST' && timeout --foreground 900 ./run.sh nogui 2>&1 | tee '$EV_LOGS/playtest-server.log'"
scmd() { tmux send-keys -l -t "$TMUX_PT:server" "$1"; tmux send-keys -t "$TMUX_PT:server" Enter; sleep 2; }

SERVER_UP=0
for _ in $(seq 1 90); do
    sleep 2
    grep -q 'Done (' "$EV_LOGS/playtest-server.log" 2>/dev/null && { SERVER_UP=1; break; }
    tmux has-session -t "$TMUX_PT" 2>/dev/null || break
done
if [ "$SERVER_UP" != 1 ]; then
    REASON=$(grep -m1 -E 'FAILED TO BIND|Address already in use|Exception|Error' "$EV_LOGS/playtest-server.log" 2>/dev/null || echo "no Done( line — see logs/playtest-server.log")
    die server_started "server never reached Done( : $REASON"
fi
check_pass server_started "$(grep -m1 'Done (' "$EV_LOGS/playtest-server.log")"

cd "$MOD"
# HSQA_TEST_BAD_JOIN_PORT is a test-only hook (AC-8/N4): points the client at
# a port nothing is listening on, so the server comes up fine but the client
# can never join — proving the harness reports THAT, with a diagnostic
# screenshot, rather than misreporting it as a server or build problem.
JOIN_PORT="${HSQA_TEST_BAD_JOIN_PORT:-$PORT}"
set -m
HSQA_JOIN="127.0.0.1:$JOIN_PORT" timeout --foreground 900 ./gradlew runClient > "$EV_LOGS/playtest-client.log" 2>&1 &
GRADLE_PID=$!
set +m
register_pid "$ROLE" "-$GRADLE_PID"

# Wait for the player to actually be in the world. The server-side join line
# is the authoritative signal (AC-1) — but a client BUILD failure (N2) must
# be caught and named, not left to time out and misreport as "never joined".
READY=0
BUILD_FAILED=0
# Budget: minutes, not a hang. An outbound HTTPS call in authlib (session
# server) can stall for a long time behind this environment's proxy before
# the client falls through and proceeds — observed up to ~9 minutes to reach
# a usable window even though the client is genuinely progressing throughout
# (high CPU, not deadlocked). The server-side join line remains the only
# thing that actually decides READY (AC-1).
for i in $(seq 1 250); do
    sleep 3
    if grep -qE "joined the game|logged in with entity id" "$EV_LOGS/playtest-server.log" 2>/dev/null; then
        READY=1; sleep 10; break
    fi
    if grep -qE "BUILD FAILED|FAILURE: Build failed" "$EV_LOGS/playtest-client.log" 2>/dev/null; then
        BUILD_FAILED=1; break
    fi
    kill -0 "$GRADLE_PID" 2>/dev/null || break
done

if [ "$BUILD_FAILED" = 1 ]; then
    FIRST_ERROR=$(grep -m1 -E 'error:' "$EV_LOGS/playtest-client.log" 2>/dev/null || echo "(no 'error:' line found — see logs/playtest-client.log)")
    die client_build "client build failed: $FIRST_ERROR"
fi
if [ "$READY" != 1 ]; then
    import -window root "$EV_SHOTS/FAILED-state.png" 2>/dev/null || true
    die player_joined "player never joined the world (see shots/FAILED-state.png)"
fi

PLAYER=$(grep -oP '^\S+ \S+ \[Server thread/INFO\].*?: \K\w+(?= joined the game)' \
    "$EV_LOGS/playtest-server.log" 2>/dev/null | tail -1)
[ -n "$PLAYER" ] || PLAYER=$(grep -oP '\K\w+(?= joined the game)' \
    "$EV_LOGS/playtest-server.log" 2>/dev/null | tail -1)
[ -n "$PLAYER" ] || die player_joined "joined the game seen but player name could not be parsed"
check_pass player_joined "$(grep -m1 "joined the game" "$EV_LOGS/playtest-server.log")"

scmd "op $PLAYER"
# AC-1: server-side corroboration beyond the join line itself.
scmd "data get entity $PLAYER Pos"
sleep 1
POS_LINE=$(grep -m1 -A0 "has the following entity data" "$EV_LOGS/playtest-server.log" | tail -1)
check_pass player_pos_query "${POS_LINE:-data get entity $PLAYER Pos issued}"

# Capture the game window itself, never the root window: anything else that
# opens on this display would otherwise end up in the "evidence".
WIN=$(xdotool search --name "Minecraft" 2>/dev/null | tail -1 || true)
focus() { [ -n "$WIN" ] && xdotool windowfocus --sync "$WIN" 2>/dev/null || true; }
# D-H4: windowactivate needs a window manager (none here, EWMH absent) and
# `key --window` is silently discarded by GLFW — so focus via windowfocus and
# always send input through XTEST (xdotool's default), never targeted.
shot() { # <name>
    focus
    if [ -n "$WIN" ]; then
        import -window "$WIN" "$EV_SHOTS/$1.png" 2>/dev/null || import -window root "$EV_SHOTS/$1.png" 2>/dev/null || true
    else
        import -window root "$EV_SHOTS/$1.png" 2>/dev/null || true
    fi
}

shot playtest-00-title

# GLFW does not grab the mouse for relative look until the FIRST real click
# into the window — proven live: `look`/`move` silently produce zero
# rotation change before any click has happened, on every attempt, and
# start working immediately after one. quickPlay drops the player straight
# into the world with no menu to click through, so nothing else establishes
# this grab. One harmless click here (empty air, before any scenario
# directive) means every scenario's look/move directives actually work
# regardless of what order it does things in.
focus; xdotool mousemove 640 360; xdotool click 1; sleep 1

while read -r verb rest; do
    rest="${rest//\$PLAYER/$PLAYER}"
    case "${verb:-}" in
        ''|'#') continue;;
        wait)  sleep "$rest";;
        key)   focus; xdotool key --clearmodifiers $rest; sleep 1;;
        type)  focus; xdotool type --delay 40 -- "$rest"; sleep 1;;
        cmd)   focus
               xdotool key --clearmodifiers t; sleep 1
               xdotool type --delay 35 -- "/$rest"; sleep 1
               xdotool key --clearmodifiers Return; sleep 2
               # Opening chat releases the mouse grab (needed so chat text
               # can be clicked/selected); closing it does not reliably
               # re-establish relative-look capture on its own — proven
               # live: a `move`/`look` right after a `cmd` silently produced
               # zero rotation change until an explicit click. A harmless
               # left-click on empty space restores it so every later
               # move/look/click directive keeps working regardless of how
               # many chat commands ran before it.
               focus; xdotool mousemove 640 360; xdotool click 1; sleep 1;;
        click) focus
               xdotool mousemove 640 360
               xdotool click "$([ "${rest:-left}" = right ] && echo 3 || echo 1)"; sleep 2;;
        move)  focus
               # A prior grab-establishing click does not reliably survive
               # to a LATER `move` several directives on — proven live,
               # exact cause not fully isolated (not simply "any key press"
               # or "any chat", since some sequences of those did survive
               # while others with an apparently identical shape did not).
               # Belt and braces: click-then-send TWICE. Harmless in
               # creative (at worst breaks a grass block, and a second
               # identical relative send just doubles an already-adequate
               # rotation change), and empirically far more reliable than
               # either a single click or a single send alone.
               xdotool mousemove 640 360; xdotool click 1; sleep 2
               xdotool mousemove_relative -- $rest; sleep 1
               xdotool mousemove 640 360; xdotool click 1; sleep 1
               xdotool mousemove_relative -- $rest; sleep 1;;
        scmd)  scmd "$rest";;
        shot)  sleep 1; shot "$rest"; echo "captured $rest.png";;

        expect_server)
            FOUND=0
            for _ in $(seq 1 10); do
                grep -qE "$rest" "$EV_LOGS/playtest-server.log" 2>/dev/null && { FOUND=1; break; }
                sleep 1
            done
            if [ "$FOUND" = 1 ]; then
                check_pass "expect_server:$rest" "$(grep -m1 -E "$rest" "$EV_LOGS/playtest-server.log")"
            else
                die "expect_server:$rest" "server log never matched /$rest/"
            fi
            ;;

        expect_shot)
            RES=$(python3 "$HERE/check_screenshot.py" "$EV_SHOTS/$rest.png" 2>&1)
            if echo "$RES" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("pass") else 1)' 2>/dev/null; then
                check_pass "expect_shot:$rest" "$RES"
            else
                die "expect_shot:$rest" "shots/$rest.png failed AC-3: $RES"
            fi
            ;;

        expect_pixel_change)
            set -- $rest
            BEFORE="$1"; AFTER="$2"; MINPCT="${3:-2.0}"; REGION="${4:-lower-third}"
            RES=$(python3 "$HERE/pixel_diff.py" "$EV_SHOTS/$BEFORE.png" "$EV_SHOTS/$AFTER.png" --min-percent "$MINPCT" --region "$REGION" 2>&1)
            if echo "$RES" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("pass") else 1)' 2>/dev/null; then
                check_pass "expect_pixel_change:$BEFORE->$AFTER" "$RES"
            else
                die "expect_pixel_change:$BEFORE->$AFTER" "key input produced no visible change: $RES"
            fi
            ;;

        expect_block_near_player)
            set -- $rest
            BLOCKID="$1"; RADIUS="${2:-6}"
            # Absolute coords, bare `fill` — deliberate: `execute at <player>
            # run fill ...` was proven live to execute (the block landed) but
            # emit NO feedback at all, silently, so a wrapped fill can never
            # satisfy expect_server. Bare fill with absolute coordinates does
            # report normally.
            POSLINE=$(grep -oP 'has the following entity data: \[\K[-0-9.]+d, [-0-9.]+d, [-0-9.]+d' "$EV_LOGS/playtest-server.log" | tail -1)
            if [ -z "$POSLINE" ]; then
                die "expect_block_near_player:$BLOCKID" "no prior 'data get entity \$PLAYER Pos' result to compute a box from"
            fi
            read -r PX PY PZ <<< "$(echo "$POSLINE" | tr -d 'd,')"
            BOX=$(python3 -c "
px,py,pz,r = $PX,$PY,$PZ,$RADIUS
print(int(px-r), int(py-r), int(pz-r), int(px+r), int(py+r), int(pz+r))
")
            scmd "fill $BOX $BLOCKID replace $BLOCKID"
            FOUND=0
            for _ in $(seq 1 10); do
                grep -qE "Successfully (filled|replaced) [1-9][0-9]* block" "$EV_LOGS/playtest-server.log" 2>/dev/null && { FOUND=1; break; }
                sleep 1
            done
            if [ "$FOUND" = 1 ]; then
                check_pass "expect_block_near_player:$BLOCKID" "$(grep -m1 -E 'Successfully (filled|replaced) [1-9]' "$EV_LOGS/playtest-server.log")"
            else
                die "expect_block_near_player:$BLOCKID" "no $BLOCKID found within $RADIUS blocks of the player"
            fi
            ;;

        expect_rotation_change)
            MINDEG="${rest:-30}"
            RESULT=$(python3 - "$EV_LOGS/playtest-server.log" "$MINDEG" <<'PYEOF'
import re, sys
log, min_deg = sys.argv[1], float(sys.argv[2])
rots = re.findall(r"has the following entity data: \[(-?[0-9.]+)f, (-?[0-9.]+)f\]", open(log).read())
if len(rots) < 2:
    print("FAIL: fewer than 2 Rotation queries captured")
    sys.exit(1)
(y0, _p0), (y1, _p1) = rots[-2], rots[-1]
y0, y1 = float(y0), float(y1)
delta = abs(y0 - y1) % 360
delta = min(delta, 360 - delta)
if delta > min_deg:
    print(f"PASS: yaw {y0} -> {y1} (delta {delta:.1f} > {min_deg})")
    sys.exit(0)
print(f"FAIL: yaw {y0} -> {y1} (delta {delta:.1f} <= {min_deg})")
sys.exit(1)
PYEOF
)
            if [[ "$RESULT" == PASS:* ]]; then
                check_pass "expect_rotation_change" "$RESULT"
            else
                die "expect_rotation_change" "$RESULT"
            fi
            ;;

        *)     echo "unknown directive: $verb $rest";;
    esac
done < "$SCENARIO"

sleep 2
if grep -qE "Exception in thread \"Render thread\"|crash-report|Failed to start" \
    "$EV_LOGS/playtest-client.log"; then
    die client_crash "client crashed during the playtest"
fi
COUNT=$(find "$EV_SHOTS" -maxdepth 1 -name '*.png' | wc -l)
[ "$COUNT" -gt 0 ] || die screenshots_captured "no screenshots captured"
check_pass screenshots_captured "$COUNT screenshots in shots/"

finish_result PASS
write_reproduction "# Reproduce: playtest
tools/hearthstead-qa playtest
Scenario: $SCENARIO
Instance: $INST (port $PORT), player: $PLAYER
"
echo "playtest ok: $COUNT screenshots in $EV_SHOTS"
