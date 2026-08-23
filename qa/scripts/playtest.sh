#!/usr/bin/env bash
# In-game playtest harness: boots the real client under Xvfb, gets past the
# menus into a live creative world, drives it with synthetic input, and
# screenshots what the game actually renders.
#
# This is what makes "go in and try the mod" real rather than described: the
# screenshots it produces are the evidence the premium-UI gate is scored on.
#
# Args: <mod-dir> <artifact-dir> [scenario]
#   scenario: a file of directives, one per line (default qa/scenarios/default.txt)
#     wait <seconds>              — let the game run
#     key <xdotool keyspec>       — send a key (e.g. Escape, t, Return)
#     type <text>                 — type text (chat/commands)
#     cmd <command without slash> — open chat, type /command, submit
#     click [left|right]          — click at screen centre
#     move <dx> <dy>              — move the mouse (look around)
#     shot <name>                 — capture <name>.png
set -eu
MOD="$1"; OUT="$2"; SCENARIO="${3:-}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[ -n "$SCENARIO" ] || SCENARIO="$REPO/qa/scenarios/default.txt"
DISPLAY_NUM=":98"
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export DISPLAY="$DISPLAY_NUM"

command -v xdotool >/dev/null || { echo "FAIL: xdotool missing"; exit 1; }
[ -f "$SCENARIO" ] || { echo "FAIL: scenario not found: $SCENARIO"; exit 1; }

Xvfb "$DISPLAY_NUM" -screen 0 1280x720x24 > "$OUT/playtest-xvfb.log" 2>&1 &
XVFB_PID=$!
cleanup() {
    kill "$GRADLE_PID" 2>/dev/null || true
    pkill -f "neoforge.*client" 2>/dev/null || true
    kill "$XVFB_PID" 2>/dev/null || true
}
trap cleanup EXIT
sleep 2

# The client's game directory is run/ (that is where its logs and saves land),
# NOT run/client — options written anywhere else are silently ignored, which
# leaves the accessibility onboarding screen up and blocks quickPlay entirely.
RUN_DIR="$MOD/run"
mkdir -p "$RUN_DIR"
# Skip the accessibility onboarding and the multiplayer warning, and keep the
# window at a known size so screenshot coordinates are stable.
if [ ! -f "$RUN_DIR/options.txt" ] || ! grep -q onboardAccessibility "$RUN_DIR/options.txt" 2>/dev/null; then
    cat >> "$RUN_DIR/options.txt" <<'OPTS'
onboardAccessibility:false
skipMultiplayerWarning:true
pauseOnLostFocus:false
guiScale:3
fullscreen:false
overrideWidth:1280
overrideHeight:720
tutorialStep:none
OPTS
fi

# The client JOINS our own dedicated server rather than opening a save.
# Two reasons: joining a server is far more reliable to automate than driving
# the world-creation menus, and it exercises the real client/server split —
# payload networking, synced entity data, server-authoritative state — which
# a singleplayer session partly short-circuits.
SERVER_DIR="${HSQA_SERVER_DIR:-/tmp/claude-0/hsqa-server}"
[ -d "$SERVER_DIR" ] || { echo "FAIL: no server install; run 'tools/hearthstead-qa dedicated' first"; exit 1; }

# Refresh the server's copy of the mod so the playtest runs current code.
mkdir -p "$SERVER_DIR/mods"
JAR=$(ls -t "$MOD"/build/libs/hearthstead-*.jar 2>/dev/null | grep -v sources | head -1)
[ -n "$JAR" ] || { echo "FAIL: no mod jar built"; exit 1; }
rm -f "$SERVER_DIR"/mods/hearthstead-*.jar
cp "$JAR" "$SERVER_DIR/mods/"

# Drive the server through a FIFO so scenarios can issue console commands
# (op, gamemode, summon) without needing an already-privileged player.
CONSOLE="$OUT/server-console.fifo"
rm -f "$CONSOLE"; mkfifo "$CONSOLE"
# 'nogui' matters more than it looks: with DISPLAY set, the dedicated server
# opens its Swing console window on the same virtual screen, which then eats
# the synthetic input meant for the game and is what a root-window screenshot
# captures instead of Minecraft.
(cd "$SERVER_DIR" && timeout 900 ./run.sh nogui < "$CONSOLE" > "$OUT/playtest-server.log" 2>&1) &
SERVER_PID=$!
exec 9>"$CONSOLE"          # hold the write end open so the server never sees EOF
scmd() { echo "$1" >&9; sleep 2; }
SERVER_UP=0
for _ in $(seq 1 90); do
    sleep 2
    grep -q 'Done (' "$OUT/playtest-server.log" 2>/dev/null && { SERVER_UP=1; break; }
    kill -0 "$SERVER_PID" 2>/dev/null || break
done
[ "$SERVER_UP" = 1 ] || { echo "FAIL: dedicated server never finished starting"; exit 1; }
echo "server up; connecting client"

cd "$MOD"
HSQA_JOIN="127.0.0.1:25565" timeout 900 ./gradlew runClient \
    > "$OUT/playtest-client.log" 2>&1 &
GRADLE_PID=$!

# Wait for the player to actually be in the world. The server-side join line
# is the authoritative signal — the client log is quiet once it is in.
READY=0
NUDGED=0
for i in $(seq 1 150); do
    sleep 3
    if grep -qE "joined the game|logged in with entity id" "$OUT/playtest-server.log" 2>/dev/null; then
        READY=1; sleep 10; break
    fi
    kill -0 "$GRADLE_PID" 2>/dev/null || break
    # Belt and braces: if a first-run screen still manages to appear, click
    # its bottom button ("Continue") once so quickPlay can proceed.
    if [ "$i" -gt 20 ] && [ "$NUDGED" = 0 ] \
        && grep -q "Loaded 0 entity animations" "$OUT/playtest-client.log" 2>/dev/null; then
        NUDGED=1
        W=$(xdotool search --name "Minecraft" 2>/dev/null | tail -1 || true)
        [ -n "$W" ] && xdotool windowactivate --sync "$W" 2>/dev/null || true
        xdotool mousemove 640 567 click 1 2>/dev/null || true
        echo "nudged a first-run screen"
    fi
done
if [ "$READY" != 1 ]; then
    # Capture what the client was showing so the failure is diagnosable.
    import -window root "$OUT/playtest-FAILED-state.png" 2>/dev/null || true
    echo "FAIL: player never joined the world (see playtest-FAILED-state.png)"
    exit 1
fi

# Whoever just joined becomes an operator, so scenario commands work.
PLAYER=$(grep -oP '^\S+ \S+ \[Server thread/INFO\].*?: \K\w+(?= joined the game)' \
    "$OUT/playtest-server.log" 2>/dev/null | tail -1)
[ -n "$PLAYER" ] || PLAYER=$(grep -oP '\K\w+(?= joined the game)' \
    "$OUT/playtest-server.log" 2>/dev/null | tail -1)
if [ -n "$PLAYER" ]; then
    echo "player joined: $PLAYER"
    scmd "op $PLAYER"
else
    echo "WARN: could not identify the joined player; scenario commands may be denied"
fi

# Capture the game window itself, never the root window: anything else that
# opens on this display would otherwise end up in the "evidence".
WIN=$(xdotool search --name "Minecraft" 2>/dev/null | tail -1 || true)
focus() { [ -n "$WIN" ] && xdotool windowactivate --sync "$WIN" 2>/dev/null || true; }
shot() {
    if [ -n "$WIN" ]; then
        import -window "$WIN" "$OUT/$1.png" 2>/dev/null || import -window root "$OUT/$1.png" 2>/dev/null || true
    else
        import -window root "$OUT/$1.png" 2>/dev/null || true
    fi
}

focus
shot playtest-00-title

while read -r verb rest; do
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
        *)     echo "unknown directive: $verb $rest";;
    esac
done < "$SCENARIO"

sleep 2
if grep -qE "Exception in thread \"Render thread\"|crash-report|Failed to start" \
    "$OUT/playtest-client.log"; then
    echo "FAIL: client crashed during the playtest"; exit 1
fi
COUNT=$(find "$OUT" -maxdepth 1 -name 'playtest-*.png' | wc -l)
[ "$COUNT" -gt 0 ] || { echo "FAIL: no screenshots captured"; exit 1; }
echo "playtest ok: $COUNT screenshots in $OUT"
