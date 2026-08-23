#!/usr/bin/env bash
# A PERSISTENT playable session, so the game can be looked at while it runs
# rather than only through a finished screenshot run.
#
# A scripted playtest answers "did the flow work". This answers "does it feel
# right": it keeps a client and server up between commands, so an action can be
# taken, the result looked at, and the next action decided from what was
# actually seen — which is what playing is. `film` additionally records motion,
# because a still frame cannot show whether an animation reads correctly.
#
#   live.sh start                 boot server + client, join, hold the session
#   live.sh shot <name>           capture the game window right now
#   live.sh key <keys>            send keys (Escape, t, 1, w, shift+w)
#   live.sh hold <key> <seconds>  hold a key down (walking, mining)
#   live.sh type <text>           type text
#   live.sh cmd <command>         run a command as the player (no leading /)
#   live.sh scmd <command>        run a command on the server console
#   live.sh click [left|right]    click at the centre of the screen
#   live.sh look <dx> <dy>        turn the view
#   live.sh film <secs> [fps]     record motion, then build a contact sheet
#   live.sh status                is it up? what is on screen?
#   live.sh stop                  shut everything down
set -u
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MOD="$REPO/hearthstead-neoforge"
SESSION="${HSQA_LIVE_DIR:-/tmp/claude-0/hsqa-live}"
SERVER_DIR="${HSQA_SERVER_DIR:-/tmp/claude-0/hsqa-server}"
DISPLAY_NUM=":99"
export DISPLAY="$DISPLAY_NUM"
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
mkdir -p "$SESSION"
CONSOLE="$SESSION/console.fifo"

win() { xdotool search --name "Minecraft" 2>/dev/null | tail -1; }
focus() { W=$(win); [ -n "$W" ] && xdotool windowactivate --sync "$W" 2>/dev/null; }
grab() { # grab <outfile>
    W=$(win)
    if [ -n "$W" ]; then import -window "$W" "$1" 2>/dev/null; else import -window root "$1" 2>/dev/null; fi
}

case "${1:-help}" in

start)
    pkill -9 -f 'gradlew run[C]lient' 2>/dev/null
    pkill -9 -f "Xvfb $DISPLAY_NUM" 2>/dev/null
    sleep 1
    Xvfb "$DISPLAY_NUM" -screen 0 1280x720x24 > "$SESSION/xvfb.log" 2>&1 &
    sleep 2

    [ -d "$SERVER_DIR" ] || { echo "no server install; run 'tools/hearthstead-qa dedicated' first"; exit 1; }
    JAR=$(ls -t "$MOD"/build/libs/hearthstead-*.jar 2>/dev/null | grep -v sources | head -1)
    [ -n "$JAR" ] || { echo "no mod jar; build first"; exit 1; }
    mkdir -p "$SERVER_DIR/mods"; rm -f "$SERVER_DIR"/mods/hearthstead-*.jar
    cp "$JAR" "$SERVER_DIR/mods/"

    rm -f "$CONSOLE"; mkfifo "$CONSOLE"
    (cd "$SERVER_DIR" && ./run.sh nogui < "$CONSOLE" > "$SESSION/server.log" 2>&1) &
    # A sleeping writer keeps the FIFO open so the server never reads EOF and
    # shuts itself down between commands.
    (sleep 86400 > "$CONSOLE") &
    for _ in $(seq 1 90); do
        sleep 2; grep -q 'Done (' "$SESSION/server.log" 2>/dev/null && break
    done
    grep -q 'Done (' "$SESSION/server.log" || { echo "server failed to start"; exit 1; }

    printf 'onboardAccessibility:false\nskipMultiplayerWarning:true\npauseOnLostFocus:false\nguiScale:3\ntutorialStep:none\n' \
        > "$MOD/run/options.txt"
    (cd "$MOD" && HSQA_JOIN="127.0.0.1:25565" ./gradlew runClient > "$SESSION/client.log" 2>&1) &

    for _ in $(seq 1 150); do
        sleep 3
        grep -qE "joined the game" "$SESSION/server.log" 2>/dev/null && break
    done
    if grep -qE "joined the game" "$SESSION/server.log"; then
        PLAYER=$(grep -oP '\K\w+(?= joined the game)' "$SESSION/server.log" | tail -1)
        echo "$PLAYER" > "$SESSION/player"
        echo "op $PLAYER" > "$CONSOLE"
        sleep 2
        echo "LIVE: $PLAYER is in the world. Session held open."
    else
        grab "$SESSION/start-failed.png"
        echo "LIVE: client never joined (see $SESSION/start-failed.png)"; exit 1
    fi
    ;;

shot)   grab "$SESSION/${2:-shot}.png"; echo "$SESSION/${2:-shot}.png";;
key)    focus; shift; xdotool key --clearmodifiers "$@"; echo "sent key: $*";;
hold)   focus; xdotool keydown "$2"; sleep "${3:-1}"; xdotool keyup "$2"; echo "held $2 for ${3:-1}s";;
type)   focus; shift; xdotool type --delay 40 -- "$*"; echo "typed";;
cmd)    focus; shift
        xdotool key --clearmodifiers t; sleep 1
        xdotool type --delay 30 -- "/$*"; sleep 1
        xdotool key --clearmodifiers Return; sleep 1
        echo "ran as player: /$*";;
scmd)   shift; echo "$*" > "$CONSOLE"; sleep 1; echo "ran on server: $*";;
click)  focus; xdotool mousemove 640 360
        xdotool click "$([ "${2:-left}" = right ] && echo 3 || echo 1)"; echo "clicked ${2:-left}";;
look)   focus; xdotool mousemove_relative -- "${2:-0}" "${3:-0}"; echo "looked $2 $3";;

film)
    SECS="${2:-6}"; FPS="${3:-4}"
    OUTDIR="$SESSION/film"; rm -rf "$OUTDIR"; mkdir -p "$OUTDIR"
    W=$(win)
    GEOM=$(xdotool getwindowgeometry --shell "$W" 2>/dev/null | grep -E '^(WIDTH|HEIGHT|X|Y)=' | tr '\n' ' ')
    eval "$GEOM"
    ffmpeg -loglevel error -f x11grab -framerate "$FPS" \
        -video_size "${WIDTH:-1280}x${HEIGHT:-720}" -i "$DISPLAY_NUM+${X:-0},${Y:-0}" \
        -t "$SECS" "$OUTDIR/frame_%03d.png" 2>"$SESSION/ffmpeg.log"
    COUNT=$(find "$OUTDIR" -name 'frame_*.png' | wc -l)
    if [ "$COUNT" -lt 2 ]; then echo "film failed (see $SESSION/ffmpeg.log)"; exit 1; fi
    # A grid of frames in time order: motion becomes visible in one image.
    montage "$OUTDIR"/frame_*.png -tile 4x -geometry 320x180+2+2 \
        -background '#101010' "$SESSION/film-sheet.png" 2>/dev/null \
        || convert "$OUTDIR"/frame_*.png -resize 320x180 -append "$SESSION/film-sheet.png"
    echo "$COUNT frames over ${SECS}s -> $SESSION/film-sheet.png"
    ;;

status)
    pgrep -f 'gradlew run[C]lient' >/dev/null && echo "client: UP" || echo "client: down"
    grep -q 'Done (' "$SESSION/server.log" 2>/dev/null && echo "server: UP" || echo "server: down"
    [ -f "$SESSION/player" ] && echo "player: $(cat "$SESSION/player")"
    grab "$SESSION/status.png" && echo "screen: $SESSION/status.png"
    ;;

stop)
    pkill -9 -f 'gradlew run[C]lient' 2>/dev/null
    pkill -9 -f 'forge[c]lientdev' 2>/dev/null
    echo "stop" > "$CONSOLE" 2>/dev/null
    sleep 3
    pkill -9 -f "Xvfb $DISPLAY_NUM" 2>/dev/null
    echo "session stopped"
    ;;

*) sed -n '2,25p' "$0";;
esac
