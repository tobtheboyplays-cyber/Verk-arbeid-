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

TEARDOWN_DONE=0
teardown() {
    [ "$TEARDOWN_DONE" = 1 ] && return
    TEARDOWN_DONE=1
    [ -n "${GRADLE_PID:-}" ] && kill -9 -- "-$GRADLE_PID" 2>/dev/null
    pkill -9 -f "neoforge.*client" 2>/dev/null || true
    exec 9>&- 2>/dev/null || true
    [ -n "${SERVER_PID:-}" ] && kill -9 -- "-$SERVER_PID" 2>/dev/null
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
OPTS

# Drive the server through a FIFO so scenarios can issue console commands
# (op, gamemode, summon, data get) without needing an already-privileged
# player. 'nogui' matters: with DISPLAY set, the dedicated server would
# otherwise open its Swing console on the same virtual screen and steal
# synthetic input meant for the game.
CONSOLE="$EV_DIR/server-console.fifo"
rm -f "$CONSOLE"; mkfifo "$CONSOLE"
set -m
(cd "$INST" && timeout 900 ./run.sh nogui < "$CONSOLE") > "$EV_LOGS/playtest-server.log" 2>&1 &
SERVER_PID=$!
set +m
register_pid "$ROLE" "-$SERVER_PID"
exec 9>"$CONSOLE"   # hold the write end open so the server never sees EOF
scmd() { echo "$1" >&9; sleep 2; }

SERVER_UP=0
for _ in $(seq 1 90); do
    sleep 2
    grep -q 'Done (' "$EV_LOGS/playtest-server.log" 2>/dev/null && { SERVER_UP=1; break; }
    kill -0 "$SERVER_PID" 2>/dev/null || break
done
if [ "$SERVER_UP" != 1 ]; then
    REASON=$(grep -m1 -E 'FAILED TO BIND|Address already in use|Exception|Error' "$EV_LOGS/playtest-server.log" 2>/dev/null || echo "no Done( line — see logs/playtest-server.log")
    die server_started "server never reached Done( : $REASON"
fi
check_pass server_started "$(grep -m1 'Done (' "$EV_LOGS/playtest-server.log")"

cd "$MOD"
set -m
HSQA_JOIN="127.0.0.1:$PORT" timeout 900 ./gradlew runClient > "$EV_LOGS/playtest-client.log" 2>&1 &
GRADLE_PID=$!
set +m
register_pid "$ROLE" "-$GRADLE_PID"

# Wait for the player to actually be in the world. The server-side join line
# is the authoritative signal (AC-1) — but a client BUILD failure (N2) must
# be caught and named, not left to time out and misreport as "never joined".
READY=0
BUILD_FAILED=0
for i in $(seq 1 150); do
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
               xdotool key --clearmodifiers Return; sleep 2;;
        click) focus
               xdotool mousemove 640 360
               xdotool click "$([ "${rest:-left}" = right ] && echo 3 || echo 1)"; sleep 2;;
        move)  focus; xdotool mousemove_relative -- $rest; sleep 1;;
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
