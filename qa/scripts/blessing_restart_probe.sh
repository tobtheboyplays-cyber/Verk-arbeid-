#!/usr/bin/env bash
# Runtime persistence gate for physical Blessing targets.  One explicit world
# is created once, chunk-unloaded/reloaded, then cleanly stopped and restarted
# twice.  A real client performs every seal binding; server state and the raw
# settlement SavedData are checked independently after every pass.
#
# Args: <mod-dir> <artifact-dir>
set -u
set -o pipefail
MOD="$1"; OUT="$2"
HERE="$(dirname "${BASH_SOURCE[0]}")"
unset HSQA_TMUX_SELFTEST_MODE HSQA_TMUX_SOCKET
export HSQA_PIDDIR=/tmp/claude-0/hsqa-pids-v2
. "$HERE/lib_harness.sh"
. "$HERE/lib_tmux_harness.sh"

ROLE="blessing-restart"
PORT="${HSQA_BLESSING_RESTART_PORT:-25576}"
DISPLAY_NUM="${HSQA_BLESSING_RESTART_DISPLAY:-:96}"
TMUX_SESSION="hsqa-blessing-restart"
TMUX_OWNER="restart-tmux"
TMUX_SESSION_ID=""
LEVEL_NAME="blessing_restart_world"
PLAYER="BlessingViewer"
PLAQUE_X=522; PLAQUE_Y=-58; PLAQUE_Z=519
MAX_WORLD_BYTES=$((64 * 1024 * 1024))
START_EPOCH=$(date +%s)

DISPLAY_INDEX="${DISPLAY_NUM#:}"
case "$DISPLAY_INDEX" in
    ''|*[!0-9]*)
        echo "FAIL: blessing-restart display must be a local numeric X display, got $DISPLAY_NUM"
        exit 1
        ;;
esac
XVFB_LOCK="/tmp/.X${DISPLAY_INDEX}-lock"
XVFB_SOCKET="/tmp/.X11-unix/X${DISPLAY_INDEX}"

export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export ALSOFT_DRIVERS=null
export DISPLAY="$DISPLAY_NUM"

# The role, instance directory and tmux name are intentionally stable so all
# three boots reuse one exact world.  That also means two invocations must
# never overlap: without this lock, both can pass the port check before either
# listens, then server_instance.sh from one removes the other's world and a
# losing trap kills the winner's tmux session.  Acquire before ev_init, traps,
# or any shared-role path so a rejected contender owns nothing it could clean.
command -v flock >/dev/null \
    || { echo "FAIL: flock is required for blessing-restart exclusivity"; exit 1; }
LOCK_DIR="${HSQA_PIDDIR:-/tmp/claude-0/hsqa-pids}"
LOCK_PATH="$LOCK_DIR/$ROLE.lock"
mkdir -p "$LOCK_DIR"
exec 9> "$LOCK_PATH"
if ! flock -n 9; then
    echo "FAIL: blessing-restart is already active (lock held: $LOCK_PATH)"
    exit 1
fi

ev_init "$ROLE"

TEARDOWN_DONE=0
TEARDOWN_RUNNING=0
TEARDOWN_PENDING_SIGNAL=0
TEARDOWN_STATUS=0
WORLD_ARCHIVED=0
CLIENT_PID=""
CLIENT_PGID=""
CLIENT_RECORD=""
CLIENT_EFFECTIVE_ROLE="${ROLE}-client"
CLIENT_WINDOW=""
CLIENT_LOG=""
XVFB_PID=""
XVFB_PGID=""
XVFB_RECORD=""
XVFB_EFFECTIVE_ROLE="${ROLE}-xvfb"
INST=""
WORLD=""
SERVER_WINDOW=""
SERVER_WINDOW_ID=""
SERVER_PANE_ID=""
SERVER_PANE_PID=""
SERVER_PGID=""
SERVER_RECORD=""
SERVER_EFFECTIVE_ROLE="${ROLE}-server"
FRAME_COUNTER=0
FRAME_ACK_LINE=""
FRAME_REQUEST=""
FRAME_REQUEST_TMP=""
SNEAK_COUNTER=0

preserve_world() {
    [ "$WORLD_ARCHIVED" = 1 ] && return
    WORLD_ARCHIVED=1
    [ -n "$WORLD" ] && [ -d "$WORLD" ] || return
    local bytes
    bytes=$(du -sb "$WORLD" 2>/dev/null | awk '{print $1}')
    printf 'world=%s\nbytes=%s\nlimit=%s\n' \
        "$WORLD" "${bytes:-unknown}" "$MAX_WORLD_BYTES" \
        > "$EV_DIR/world-size.txt"
    if [ -n "$bytes" ] && [ "$bytes" -le "$MAX_WORLD_BYTES" ]; then
        tar -C "$INST" -czf "$EV_DIR/$LEVEL_NAME.tar.gz" "$LEVEL_NAME" \
            2> "$EV_LOGS/world-archive.log" || true
        [ -s "$EV_DIR/$LEVEL_NAME.tar.gz" ] \
            && sha256sum "$EV_DIR/$LEVEL_NAME.tar.gz" \
                > "$EV_DIR/$LEVEL_NAME.tar.gz.sha256"
    fi
}

stop_client_process() {
    [ -n "$FRAME_REQUEST" ] && rm -f -- "$FRAME_REQUEST"
    [ -n "$FRAME_REQUEST_TMP" ] && rm -f -- "$FRAME_REQUEST_TMP"
    FRAME_REQUEST_TMP=""
    [ -n "$CLIENT_RECORD" ] || return 0
    hsqa_stop_tracked "$CLIENT_EFFECTIVE_ROLE" "$CLIENT_RECORD" || return 1
    CLIENT_PID=""
    CLIENT_PGID=""
    CLIENT_RECORD=""
}

teardown_defer_signal() {
    [ "$TEARDOWN_PENDING_SIGNAL" -ne 0 ] \
        || TEARDOWN_PENDING_SIGNAL="$1"
}

teardown() {
    local cleanup_status=0
    [ "$TEARDOWN_DONE" = 1 ] && return "$TEARDOWN_STATUS"
    [ "$TEARDOWN_RUNNING" = 0 ] || return 125
    TEARDOWN_RUNNING=1
    trap 'teardown_defer_signal 130' INT
    trap 'teardown_defer_signal 143' TERM
    stop_client_process || cleanup_status=1
    # A failing assertion should still leave a coherent world artifact. Ask
    # only the immutable pane backed by the exact durable server record to
    # stop, then stop/prove the whole tracked group before touching control.
    if [ -n "$SERVER_RECORD" ] \
            && hsqa_tmux_pane_record_owned "$SERVER_PANE_ID" \
                "$SERVER_PANE_PID" "$SERVER_RECORD" restart-server; then
        hsqa_tmux send-keys -l -t "$SERVER_PANE_ID" stop 2>/dev/null \
            && hsqa_tmux send-keys -t "$SERVER_PANE_ID" Enter 2>/dev/null \
            || true
    fi
    if hsqa_stop_tracked "$SERVER_EFFECTIVE_ROLE" "$SERVER_RECORD"; then
        SERVER_RECORD=""
    else
        cleanup_status=1
    fi
    preserve_world
    [ -z "$TMUX_SESSION_ID" ] \
        || hsqa_stop_owned_tmux_session "$TMUX_SESSION_ID" "$TMUX_OWNER" \
        || cleanup_status=1
    hsqa_wait_port_free "$PORT" || cleanup_status=1
    hsqa_stop_tracked "$XVFB_EFFECTIVE_ROLE" "$XVFB_RECORD" || cleanup_status=1
    for _ in $(seq 1 20); do
        DISPLAY="$DISPLAY_NUM" xdotool getmouselocation >/dev/null 2>&1 || break
        sleep 0.1
    done
    DISPLAY="$DISPLAY_NUM" xdotool getmouselocation >/dev/null 2>&1 \
        && cleanup_status=1
    TEARDOWN_STATUS="$cleanup_status"
    TEARDOWN_DONE=1
    TEARDOWN_RUNNING=0
    if [ "$TEARDOWN_PENDING_SIGNAL" -eq 0 ]; then
        trap 'on_signal 130' INT
        trap 'on_signal 143' TERM
    fi
    return "$cleanup_status"
}
on_signal() {
    local status="$1" cleanup_status=0 final_status
    trap - EXIT
    [ "$TEARDOWN_PENDING_SIGNAL" -ne 0 ] \
        || TEARDOWN_PENDING_SIGNAL="$status"
    teardown || cleanup_status=1
    status="$TEARDOWN_PENDING_SIGNAL"
    hsqa_finish_interrupted "$status" blessing-restart \
        "tools/hearthstead-qa blessing-restart" "$cleanup_status"
    final_status=$?
    exit "$final_status"
}
on_exit() {
    hsqa_exit_after_cleanup "$?" blessing-restart \
        "tools/hearthstead-qa blessing-restart" teardown TEARDOWN_PENDING_SIGNAL
}
trap on_exit EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

command -v tmux >/dev/null || die tmux "tmux is required"
command -v Xvfb >/dev/null || die xvfb "Xvfb is required"
command -v xdotool >/dev/null || die xdotool "xdotool is required"
command -v import >/dev/null || die screenshot "ImageMagick import is required"
command -v lsof >/dev/null || die lsof "lsof is required for exact process ownership"

if ! MSG=$(preflight_port "$PORT" "$ROLE"); then die port_preflight "$MSG"; fi
check_pass port_preflight "port $PORT free before launch"

JAR=$(ls -t "$MOD"/build/libs/hearthstead-*.jar 2>/dev/null \
    | grep -v sources | head -1)
[ -n "$JAR" ] || die build_jar "no mod jar built - run build first"
NEWEST_SRC=$(find "$MOD/src" "$MOD/build.gradle" "$MOD/gradle.properties" \
    -type f -newer "$JAR" 2>/dev/null | head -1)
[ -z "$NEWEST_SRC" ] \
    || die build_jar "stale jar: $(basename "$JAR") predates $NEWEST_SRC"
check_pass build_jar "$(basename "$JAR")"

bash "$HERE/server_install.sh" "$MOD" > "$EV_LOGS/server-install.log" 2>&1 \
    || die server_install "shared server install failed - see logs/server-install.log"
check_pass server_install "shared NeoForge server install present"

INST=$(bash "$HERE/server_instance.sh" "$ROLE" "$PORT" "$MOD" \
    2> "$EV_LOGS/instance.log" | tail -1)
[ -d "$INST" ] || die instance "server_instance.sh did not produce an instance"
WORLD="$INST/$LEVEL_NAME"
printf '\nlevel-name=%s\nlevel-seed=748193625\nview-distance=4\nsimulation-distance=4\n' \
    "$LEVEL_NAME" >> "$INST/server.properties"
check_pass world_path "single instance=$INST world=$WORLD"

bash "$HERE/client_install.sh" "$MOD" > "$EV_LOGS/client-install.log" 2>&1 \
    || die client_install "shared client install failed - see logs/client-install.log"
CLIENT_INSTALL_DIR="${HSQA_CLIENT_INSTALL_DIR:-/tmp/claude-0/hsqa-client-install-v2}"
NEOFORGE_ID=$(basename "$(ls -d "$CLIENT_INSTALL_DIR"/versions/neoforge-*/ \
    2>/dev/null | head -1)")
CLIENT_MC_VERSION=$(basename "$(ls -d "$CLIENT_INSTALL_DIR"/versions/*/ \
    2>/dev/null | grep -v '/neoforge-' | head -1)")
[ -n "$NEOFORGE_ID" ] && [ -n "$CLIENT_MC_VERSION" ] \
    || die client_install "could not resolve installed client versions"
check_pass client_install "shared production-style client install present"

CLIENT_GAME="$INST/qa-client"
mkdir -p "$CLIENT_GAME/mods"
cp "$JAR" "$CLIENT_GAME/mods/"
FRAME_REQUEST="$CLIENT_GAME/hsqa-frame-request"
rm -f -- "$FRAME_REQUEST"
cat > "$CLIENT_GAME/options.txt" <<'OPTS'
onboardAccessibility:false
skipMultiplayerWarning:true
pauseOnLostFocus:false
guiScale:3
lang:en_us
fullscreen:false
overrideWidth:1280
overrideHeight:720
tutorialStep:none
rawMouseInput:false
renderDistance:4
simulationDistance:4
OPTS

# Refuse to share a live display.  An earlier exact process may have been
# externally SIGKILLed, leaving X's lock/socket behind; only when this display
# is unreachable and its recorded PID is gone may those two exact paths be
# treated as stale and removed.
if xdotool getmouselocation >/dev/null 2>&1; then
    die display_preflight "display $DISPLAY_NUM is already live"
fi
if [ -e "$XVFB_LOCK" ]; then
    STALE_X_PID=$(tr -dc '0-9' < "$XVFB_LOCK" 2>/dev/null || true)
    if [ -n "$STALE_X_PID" ] && kill -0 "$STALE_X_PID" 2>/dev/null; then
        STALE_X_CMD=$(tr '\0' ' ' < "/proc/$STALE_X_PID/cmdline" 2>/dev/null || true)
        die display_preflight \
            "display $DISPLAY_NUM lock belongs to live pid $STALE_X_PID ($STALE_X_CMD)"
    fi
fi
rm -f -- "$XVFB_LOCK" "$XVFB_SOCKET"
check_pass display_preflight "display $DISPLAY_NUM is isolated and free"

hsqa_launch_tracked_group "${ROLE}-xvfb" restart-xvfb on_signal \
    XVFB_PID XVFB_PGID XVFB_RECORD XVFB_EFFECTIVE_ROLE \
    Xvfb "$DISPLAY_NUM" -screen 0 1280x720x24 \
    > "$EV_LOGS/xvfb.log" 2>&1 \
    || die xvfb "could not safely launch/register Xvfb"
sleep 2
kill -0 "$XVFB_PID" 2>/dev/null || die xvfb "Xvfb failed to start"
check_pass xvfb "Xvfb $DISPLAY_NUM up (pid $XVFB_PID)"

hsqa_create_owned_tmux_session "$TMUX_SESSION" "$TMUX_OWNER" on_signal \
    TMUX_SESSION_ID || die tmux "failed to create exact owned tmux control session"

wait_log() { # regex seconds
    local regex="$1" seconds="$2" i
    for i in $(seq 1 "$seconds"); do
        grep -qE "$regex" "$INST/logs/latest.log" 2>/dev/null && return 0
        sleep 1
    done
    return 1
}

scmd() {
    hsqa_tmux_pane_record_owned "$SERVER_PANE_ID" "$SERVER_PANE_PID" \
        "$SERVER_RECORD" restart-server \
        || die server_console_identity "restart server pane/record identity changed"
    hsqa_tmux send-keys -l -t "$SERVER_PANE_ID" "$1" \
        && hsqa_tmux send-keys -t "$SERVER_PANE_ID" Enter \
        || die server_console_send "could not send to exact restart server pane"
    sleep 0.25
}

assert_server() { # check marker command evidence
    local check="$1" marker="$2" command="$3" evidence="$4"
    scmd "$command"
    if wait_log "$marker" 15; then
        check_pass "$check" "$evidence"
    else
        die "$check" "$evidence; server marker $marker never appeared"
    fi
}

boot_server() { # pass
    local pass="$1" boot_epoch ready=0 log_mtime=0
    SERVER_WINDOW="server-$pass"
    boot_epoch=$(date +%s)
    SERVER_WINDOW_ID=""; SERVER_PANE_ID=""; SERVER_PANE_PID=""
    SERVER_PGID=""; SERVER_RECORD=""; SERVER_EFFECTIVE_ROLE="${ROLE}-server"
    hsqa_launch_tracked_tmux_window "$TMUX_SESSION_ID" "$TMUX_OWNER" \
        "$SERVER_WINDOW" "${ROLE}-server" restart-server on_signal \
        SERVER_WINDOW_ID SERVER_PANE_ID SERVER_PANE_PID SERVER_PGID \
        SERVER_RECORD SERVER_EFFECTIVE_ROLE \
        /bin/bash --noprofile --norc -p -c \
        'set -o pipefail; cd "$1" || exit 2; timeout --kill-after=10 --foreground 240 ./run.sh nogui 2>&1 | tee "$2"' \
        -- "$INST" "$EV_LOGS/server-$pass-console.log" \
        || die "server_${pass}_launch" \
            "could not durably launch exact restart server pane/group"
    for _ in $(seq 1 120); do
        log_mtime=$(stat -c %Y "$INST/logs/latest.log" 2>/dev/null || echo 0)
        if [ "$log_mtime" -ge "$boot_epoch" ] \
            && grep -qE 'Done \(' "$INST/logs/latest.log" 2>/dev/null; then
            ready=1
            break
        fi
        sleep 1
    done
    if [ "$ready" != 1 ]; then
        local reason
        reason=$(grep -m1 -E 'FAILED TO BIND|Address already in use|Exception|Error' \
            "$INST/logs/latest.log" 2>/dev/null || echo "no Done line")
        die "server_${pass}_started" "boot $pass never reached Done: $reason"
    fi
    [ "$log_mtime" -ge "$boot_epoch" ] \
        || die "server_${pass}_fresh_log" "latest.log predates boot $pass"
    grep -q "Preparing level \"$LEVEL_NAME\"" "$INST/logs/latest.log" \
        || die "server_${pass}_world" "boot $pass did not prepare $LEVEL_NAME"
    check_pass "server_${pass}_started" \
        "$(grep -m1 'Done (' "$INST/logs/latest.log")"
    check_pass "server_${pass}_world" "prepared exact world $WORLD"
}

start_client() { # pass
    local pass="$1" log
    log="$EV_LOGS/client-$pass.log"
    CLIENT_LOG="$log"
    rm -f -- "$FRAME_REQUEST"
    hsqa_launch_tracked_group "${ROLE}-client" restart-client on_signal \
        CLIENT_PID CLIENT_PGID CLIENT_RECORD CLIENT_EFFECTIVE_ROLE \
        bash -c 'cd "$1" || exit 1; exec env HSQA_CLIENT_OBSERVER=1 timeout --kill-after=10 --foreground 210 python3 "$2/launch.py" --install-dir "$2" --mc-version "$3" --neoforge-id "$4" --game-dir "$1" --username "$5" --width 1280 --height 720 --join "127.0.0.1:$6"' \
        -- "$CLIENT_GAME" "$CLIENT_INSTALL_DIR" "$CLIENT_MC_VERSION" \
        "$NEOFORGE_ID" "$PLAYER" "$PORT" > "$log" 2>&1 \
        || die "viewer_${pass}_launch" "could not safely launch/register real client"
    if ! wait_log "$PLAYER joined the game" 150; then
        die "viewer_${pass}_joined" "real viewer did not join on boot $pass"
    fi
    check_pass "viewer_${pass}_joined" \
        "$(grep -m1 "$PLAYER joined the game" "$INST/logs/latest.log")"
    scmd "gamemode creative $PLAYER"
    scmd "effect give $PLAYER minecraft:resistance infinite 255 true"
    scmd "effect give $PLAYER minecraft:saturation infinite 1 true"
}

focus_client() {
    local win="" focused="" geom width height
    for _ in $(seq 1 30); do
        win=$(xdotool search --name Minecraft 2>/dev/null | tail -1)
        [ -n "$win" ] && break
        sleep 1
    done
    [ -n "$win" ] || die client_window "Minecraft window not found"
    geom=$(xdotool getwindowgeometry --shell "$win" 2>/dev/null) || true
    width=$(printf '%s\n' "$geom" | sed -n 's/^WIDTH=//p')
    height=$(printf '%s\n' "$geom" | sed -n 's/^HEIGHT=//p')
    [ "$width" = 1280 ] && [ "$height" = 720 ] \
        || die client_window "Minecraft window is ${width:-?}x${height:-?}, expected 1280x720"
    # Xvfb intentionally has no window manager, so EWMH activation is neither
    # available nor evidence that input will reach Minecraft.  Focus the exact
    # X window directly, then fail closed unless X reports that same window.
    xdotool windowfocus --sync "$win" 2>/dev/null \
        || die client_window "could not focus Minecraft window $win"
    focused=$(xdotool getwindowfocus 2>/dev/null || true)
    [ "$focused" = "$win" ] \
        || die client_window "focused window is ${focused:-none}, expected Minecraft $win"
    CLIENT_WINDOW="$win"
}

capture_client() { # label
    local label="$1"
    focus_client
    import -window "$CLIENT_WINDOW" "$EV_SHOTS/$label.png" 2>/dev/null \
        || die screenshot "could not capture client frame $label"
}

wait_client_frame() { # expected-screen expected-grabbed context
    local expected_screen="$1" expected_grabbed="$2" context="$3"
    local anchor nonce line="" deadline
    FRAME_ACK_LINE=""
    anchor=$(wc -l < "$CLIENT_LOG" 2>/dev/null || echo 0)
    FRAME_COUNTER=$((FRAME_COUNTER + 1))
    nonce="${ROLE}_${FRAME_COUNTER}_${RANDOM}"
    FRAME_REQUEST_TMP="$CLIENT_GAME/.hsqa-frame-request.${nonce}.$$"
    printf '%s\n' "$nonce" > "$FRAME_REQUEST_TMP" \
        || { FRAME_REQUEST_TMP=""; return 1; }
    mv -f -- "$FRAME_REQUEST_TMP" "$FRAME_REQUEST" \
        || { FRAME_REQUEST_TMP=""; return 1; }
    FRAME_REQUEST_TMP=""
    deadline=$((SECONDS + 30))
    while [ "$SECONDS" -lt "$deadline" ]; do
        line=$(tail -n +"$((anchor + 1))" "$CLIENT_LOG" 2>/dev/null \
            | grep -F "HSQA_FRAME_ACK nonce=$nonce " | tail -1)
        if [ -n "$line" ]; then
            rm -f -- "$FRAME_REQUEST"
            FRAME_ACK_LINE="$line"
            printf '%s | %s\n' "$context" "$line" \
                >> "$EV_LOGS/client-observer.log"
            [[ "$line" == *" screen=$expected_screen "* ]] || return 1
            [[ "$line" == *" grabbed=$expected_grabbed "* ]] || return 1
            return 0
        fi
        if tail -n +"$((anchor + 1))" "$CLIENT_LOG" 2>/dev/null \
            | grep -Fq 'HSQA_FRAME_ERROR'; then
            rm -f -- "$FRAME_REQUEST"
            return 1
        fi
        kill -0 "$CLIENT_PID" 2>/dev/null || {
            rm -f -- "$FRAME_REQUEST"
            return 1
        }
        sleep 0.10
    done
    rm -f -- "$FRAME_REQUEST"
    printf '%s: no matching client frame ack; last=%s\n' \
        "$context" "${line:-none}" >> "$EV_LOGS/client-observer.log"
    return 1
}

assert_world_input() { # check expected-hit expected-block-or-none
    local check="$1" expected_hit="$2" expected_block="$3" focused
    focus_client
    focused=$(xdotool getwindowfocus 2>/dev/null || true)
    [ "$focused" = "$CLIENT_WINDOW" ] \
        || die "$check" "Minecraft lost X focus before physical input"
    wait_client_frame null true "$check" \
        || die "$check" \
            "client did not prove screen=null and grabbed=true: ${FRAME_ACK_LINE:-no ack}"
    [[ "$FRAME_ACK_LINE" == *" hitType=$expected_hit "* ]] \
        || die "$check" "client pick result is not $expected_hit: $FRAME_ACK_LINE"
    if [ "$expected_block" != none ]; then
        [[ "$FRAME_ACK_LINE" == *" hitBlock=$expected_block "* ]] \
            || die "$check" \
                "client pick ray missed block $expected_block: $FRAME_ACK_LINE"
    fi
    check_pass "$check" "screen=null grabbed=true focused=$focused hit=$expected_hit ${expected_block#none}"
}

right_click() {
    focus_client
    xdotool click 3
    sleep 1
}

sneak_right_click() { # evidence-label [held-hit-type held-block-or-none]
    local label="$1" expected_hit="${2:-none}" expected_block="${3:-none}"
    local down=0 click=0 up=0 line="" marker anchor focused
    local deadline

    SNEAK_COUNTER=$((SNEAK_COUNTER + 1))
    marker="HSQA_BR_SNEAK_LATCHED_${SNEAK_COUNTER}"
    scmd "scoreboard players set $PLAYER hsqa_sneak 0"
    assert_server "${label}_sneak_reset" "HSQA_BR_SNEAK_RESET_${SNEAK_COUNTER}" \
        "execute if score $PLAYER hsqa_sneak matches 0 run say HSQA_BR_SNEAK_RESET_${SNEAK_COUNTER}" \
        "server sneak statistic reset before physical Shift hold"

    focus_client
    xdotool keydown Shift_L || down=$?
    if [ "$down" -ne 0 ]; then
        xdotool keyup Shift_L >/dev/null 2>&1 || true
        die "${label}_sneak_latch" "xdotool Shift keydown failed: $down"
    fi
    # Give GLFW one poll boundary to latch sneak before the use packet.  The
    # block-use packet does not carry secondary-action, so the physical click
    # must additionally wait until the dedicated server has observed at least
    # one crouch tick. Keep Shift held throughout this bounded handshake.
    sleep 0.10
    anchor=$(wc -l < "$INST/logs/latest.log")
    deadline=$((SECONDS + 8))
    while [ "$SECONDS" -lt "$deadline" ]; do
        scmd "execute if score $PLAYER hsqa_sneak matches 1.. run say $marker"
        sleep 0.20
        line=$(tail -n +$((anchor + 1)) "$INST/logs/latest.log" 2>/dev/null \
            | grep -F "$marker" | tail -1)
        [ -n "$line" ] && break
        if ! kill -0 "$CLIENT_PID" 2>/dev/null; then
            xdotool keyup Shift_L >/dev/null 2>&1 || true
            die "${label}_sneak_latch" \
                "client exited before server acknowledged physical Shift"
        fi
    done
    if [ -z "$line" ]; then
        xdotool keyup Shift_L >/dev/null 2>&1 || true
        die "${label}_sneak_latch" \
            "server did not observe a sneak tick within 8s while Shift remained held"
    fi
    check_pass "${label}_sneak_latch" \
        "server sneak_time advanced while physical Shift remained held ($marker)"

    # Crouching changes the camera eye height after the standing precheck.
    # For thin blocks, only a fresh client pick while the physical key is
    # still held proves the packet about to be sent names the intended block.
    if [ "$expected_hit" != none ]; then
        focused=$(xdotool getwindowfocus 2>/dev/null || true)
        if [ "$focused" != "$CLIENT_WINDOW" ]; then
            xdotool keyup Shift_L >/dev/null 2>&1 || true
            die "${label}_held_hit" \
                "Minecraft lost X focus while physical Shift remained held"
        fi
        if ! wait_client_frame null true "${label}_held_hit"; then
            xdotool keyup Shift_L >/dev/null 2>&1 || true
            die "${label}_held_hit" \
                "client did not prove screen=null and grabbed=true while Shift was held: ${FRAME_ACK_LINE:-no ack}"
        fi
        if [[ "$FRAME_ACK_LINE" != *" hitType=$expected_hit "* ]]; then
            xdotool keyup Shift_L >/dev/null 2>&1 || true
            die "${label}_held_hit" \
                "Shift-held client pick is not $expected_hit: $FRAME_ACK_LINE"
        fi
        if [ "$expected_block" != none ] \
            && [[ "$FRAME_ACK_LINE" != *" hitBlock=$expected_block "* ]]; then
            xdotool keyup Shift_L >/dev/null 2>&1 || true
            die "${label}_held_hit" \
                "Shift-held client pick missed block $expected_block: $FRAME_ACK_LINE"
        fi
        check_pass "${label}_held_hit" \
            "Shift held after server latch; screen=null grabbed=true focused=$focused hit=$expected_hit $expected_block"
    fi

    xdotool click 3 || click=$?
    sleep 0.25
    # Always release the modifier, including after a rejected click.
    xdotool keyup Shift_L || up=$?
    if [ "$down" -ne 0 ] || [ "$click" -ne 0 ] || [ "$up" -ne 0 ]; then
        die physical_input "xdotool failed: down=$down click=$click up=$up"
    fi
    sleep 1
}

query_uuid() { # selector output-file
    local selector="$1" output="$2" before line
    before=$(wc -l < "$INST/logs/latest.log")
    scmd "data get entity $selector UUID"
    for _ in $(seq 1 15); do
        line=$(tail -n +$((before + 1)) "$INST/logs/latest.log" 2>/dev/null \
            | grep -E 'following entity data: \[I;' | tail -1)
        if [ -n "$line" ]; then
            printf '%s\n' "$line" | grep -oE '\[I;[^]]+\]' > "$output"
            [ -s "$output" ] && return 0
        fi
        sleep 1
    done
    return 1
}

WORLD_SCORE=$(python3 - <<'PYEOF'
import secrets
print(secrets.randbelow(2_000_000_000) + 1)
PYEOF
)
printf 'instance=%s\nworld=%s\nlevel_name=%s\nworld_score=%s\n' \
    "$INST" "$WORLD" "$LEVEL_NAME" "$WORLD_SCORE" \
    > "$EV_DIR/world-identity.txt"

boot_server 0
start_client 0

# Establish a harmless grabbed input state before aiming at any target.
scmd "tp $PLAYER 512.5 -58 506.5 0 -90"
sleep 2
focus_client
xdotool mousemove --window "$CLIENT_WINDOW" 640 360
xdotool click 1
sleep 1

scmd "difficulty peaceful"
scmd "gamerule doMobSpawning false"
scmd "gamerule doDaylightCycle false"
scmd "time set day"
scmd "weather clear"
scmd "scoreboard objectives add hsqa_world dummy"
scmd "scoreboard objectives add hsqa_tmp dummy"
scmd "scoreboard objectives add hsqa_sneak minecraft.custom:minecraft.sneak_time"
scmd "scoreboard players set #world hsqa_world $WORLD_SCORE"
scmd "forceload add 512 512"
scmd "fill 508 -60 508 526 -60 526 minecraft:stone"
scmd "setblock 512 -59 512 hearthstead:hearth"
# Founding and both initial settler spawns are deliberately asynchronous.
# Assign the two distinct target tags as they appear, then reissue the
# authoritative two-entity predicate during one bounded polling window.
SETTLEMENT_FOUNDED=0
for _ in $(seq 1 30); do
    scmd "execute unless entity @e[type=hearthstead:settler,tag=hsqa_blessed,limit=1] run tag @e[type=hearthstead:settler,tag=!hsqa_blessed,sort=nearest,limit=1,x=512,y=-64,z=512,distance=..48] add hsqa_blessed"
    scmd "execute unless entity @e[type=hearthstead:settler,tag=hsqa_quarantine,limit=1] run tag @e[type=hearthstead:settler,tag=!hsqa_blessed,tag=!hsqa_quarantine,sort=nearest,limit=1,x=512,y=-64,z=512,distance=..48] add hsqa_quarantine"
    scmd "execute if entity @e[type=hearthstead:settler,tag=hsqa_blessed,limit=1] if entity @e[type=hearthstead:settler,tag=hsqa_quarantine,limit=1] run say HSQA_BR_SETTLEMENT_FOUNDED"
    if grep -q 'HSQA_BR_SETTLEMENT_FOUNDED' "$INST/logs/latest.log" 2>/dev/null; then
        SETTLEMENT_FOUNDED=1
        break
    fi
    sleep 1
done
[ "$SETTLEMENT_FOUNDED" = 1 ] \
    || die settlement_founded "real hearth did not found a settlement with two settlers within 30 bounded polls"
check_pass settlement_founded "real hearth founded a settlement with two distinct tagged settlers"

scmd "data merge entity @e[tag=hsqa_blessed,limit=1] {NoAI:1b,PersistenceRequired:1b}"
scmd "data merge entity @e[tag=hsqa_quarantine,limit=1] {NoAI:1b,PersistenceRequired:1b}"
scmd "tp @e[tag=hsqa_blessed,limit=1] 516.5 -59 512.5 180 0"
scmd "tp @e[tag=hsqa_quarantine,limit=1] 518.5 -59 512.5 180 0"

query_uuid "@e[tag=hsqa_blessed,limit=1]" "$EV_DIR/blessed-uuid.txt" \
    || die blessed_uuid "could not read blessed settler UUID"
query_uuid "@e[tag=hsqa_quarantine,limit=1]" "$EV_DIR/quarantine-uuid.txt" \
    || die quarantine_uuid "could not read quarantine settler UUID"
query_uuid "$PLAYER" "$EV_DIR/viewer-uuid.txt" \
    || die viewer_uuid "could not read viewer UUID"
check_pass target_identity "captured both settler UUIDs and real viewer UUID"

# A complete 3x3-interior house, with the plaque outside the north wall.
scmd "fill 519 -60 518 525 -55 525 minecraft:air"
scmd "fill 520 -59 520 524 -56 524 minecraft:stone_bricks hollow"
scmd "setblock 522 -58 520 minecraft:oak_door[half=lower,facing=south]"
scmd "setblock 522 -57 520 minecraft:oak_door[half=upper,facing=south]"
scmd "setblock 522 -58 522 minecraft:red_bed[facing=south,part=foot]"
scmd "setblock 522 -58 523 minecraft:red_bed[facing=south,part=head]"
scmd "setblock 521 -58 522 minecraft:torch"
scmd "setblock 522 -58 519 hearthstead:plaque[facing=north]"
scmd "item replace entity $PLAYER hotbar.0 with hearthstead:build_plan[hearthstead:building_type=\"house\"] 1"
scmd "tp $PLAYER 522.5 -59 516.5 0 2"
sleep 3
focus_client
xdotool key 1
right_click
if ! grep -q 'HSQA_BR_PLAN_INSERTED' "$INST/logs/latest.log" 2>/dev/null; then
    assert_server plan_inserted "HSQA_BR_PLAN_INSERTED" \
        "execute if data block $PLAQUE_X $PLAQUE_Y $PLAQUE_Z Plan run say HSQA_BR_PLAN_INSERTED" \
        "physical Build Plan insertion reached the plaque"
fi
scmd "hearthstead scan $PLAQUE_X $PLAQUE_Y $PLAQUE_Z"
assert_server plaque_registered "HSQA_BR_PLAQUE_REGISTERED" \
    "execute if data block $PLAQUE_X $PLAQUE_Y $PLAQUE_Z {State:\"linked_valid\"} if data block $PLAQUE_X $PLAQUE_Y $PLAQUE_Z Building run say HSQA_BR_PLAQUE_REGISTERED" \
    "plaque owns a registered building after real plan insertion"

# Build Plan insertion opens the registered plaque's House screen.  Close it
# exactly once, then use the dormant opt-in client observer as a post-input
# frame fence.  No Blessing click is permitted unless Minecraft itself reports
# screen=null and a grabbed in-world mouse, while X reports this exact window
# focused.
focus_client
xdotool key --clearmodifiers Escape \
    || die binding_world_ready "could not close the House screen"
wait_client_frame null true binding_world_ready \
    || die binding_world_ready \
        "Escape did not produce screen=null/grabbed=true: ${FRAME_ACK_LINE:-no ack}"
BINDING_FOCUS=$(xdotool getwindowfocus 2>/dev/null || true)
[ "$BINDING_FOCUS" = "$CLIENT_WINDOW" ] \
    || die binding_world_ready "Minecraft lost X focus after closing the House screen"
check_pass binding_world_ready \
    "one Escape -> screen=null grabbed=true focused=$BINDING_FOCUS"
capture_client binding-world-ready

# Physical settler binding: exact state before/after every Shift-right-click.
scmd "gamemode survival $PLAYER"
assert_server settler_rank_0 "HSQA_BR_SETTLER_RANK_0" \
    "execute unless data entity @e[tag=hsqa_blessed,limit=1] TargetBlessings.Ranks[0] run say HSQA_BR_SETTLER_RANK_0" \
    "blessed target starts with no ranks"
scmd "item replace entity $PLAYER hotbar.0 with hearthstead:hearthward_seal 2"
scmd "execute store result score #seal hsqa_tmp run clear $PLAYER hearthstead:hearthward_seal 0"
assert_server settler_stack_2_exact "HSQA_BR_SETTLER_STACK_2_EXACT" \
    "execute if score #seal hsqa_tmp matches 2 run say HSQA_BR_SETTLER_STACK_2_EXACT" \
    "settler seal stack starts at exactly 2"
assert_server settler_held "HSQA_BR_SETTLER_HELD" \
    "execute if items entity $PLAYER weapon.mainhand hearthstead:hearthward_seal run say HSQA_BR_SETTLER_HELD" \
    "Hearthward Seal is the actual selected main-hand item"
scmd "tp $PLAYER 516.5 -59 509.5"
# Teleport's inline `facing` computes from the destination feet, which pitches
# 28.9 degrees above a same-height target.  Re-anchor the command source at the
# real camera eyes, derive its facing rotation, then apply that rotation in
# place so the physical crosshair—not the player's feet vector—hits the eyes.
scmd "execute as $PLAYER at @s anchored eyes facing entity @e[tag=hsqa_blessed,limit=1] eyes run tp @s 516.5 -59 509.5 ~ ~"
sleep 3
scmd "data get entity $PLAYER Pos"
scmd "data get entity $PLAYER Rotation"
assert_server settler_hit_precheck "HSQA_BR_SETTLER_HIT" \
    "execute if entity @a[name=$PLAYER,x=516.5,y=-59,z=509.5,distance=..0.2,y_rotation=-1..1,x_rotation=-5..5] if block 516 -58 510 minecraft:air if block 516 -58 511 minecraft:air positioned 516.5 -59 509.5 if entity @e[tag=hsqa_blessed,distance=2.9..3.1,limit=1] run say HSQA_BR_SETTLER_HIT" \
    "server pose faces the sole tagged settler exactly 3 blocks away across two air samples"
assert_world_input settler_world_rank_1 entity none
capture_client settler-before-rank-1
sneak_right_click settler_rank_1
assert_server settler_rank_1 "HSQA_BR_SETTLER_RANK_1" \
    "execute if data entity @e[tag=hsqa_blessed,limit=1] TargetBlessings.Ranks[{WireId:1,Id:\"hearthward\",Rank:1}] run say HSQA_BR_SETTLER_RANK_1" \
    "first physical seal produced exact Hearthward I"
scmd "execute store result score #seal hsqa_tmp run clear $PLAYER hearthstead:hearthward_seal 0"
assert_server settler_stack_1 "HSQA_BR_SETTLER_STACK_1" \
    "execute if score #seal hsqa_tmp matches 1 run say HSQA_BR_SETTLER_STACK_1" \
    "first survival seal consumed stack 2->1"
assert_server settler_held_rank_2 "HSQA_BR_SETTLER_HELD_RANK_2" \
    "execute if items entity $PLAYER weapon.mainhand hearthstead:hearthward_seal run say HSQA_BR_SETTLER_HELD_RANK_2" \
    "remaining Hearthward Seal is still selected"
assert_world_input settler_world_rank_2 entity none
capture_client settler-before-rank-2
sneak_right_click settler_rank_2
assert_server settler_rank_2 "HSQA_BR_SETTLER_RANK_2" \
    "execute if data entity @e[tag=hsqa_blessed,limit=1] TargetBlessings.Ranks[{WireId:1,Id:\"hearthward\",Rank:2}] run say HSQA_BR_SETTLER_RANK_2" \
    "second physical seal produced exact Hearthward II"
scmd "execute store result score #seal hsqa_tmp run clear $PLAYER hearthstead:hearthward_seal 0"
assert_server settler_seals_exact "HSQA_BR_SETTLER_SEALS_EXACT" \
    "execute if score #seal hsqa_tmp matches 0 run say HSQA_BR_SETTLER_SEALS_EXACT" \
    "second survival seal consumed stack 1->0"

# Physical plaque binding.  Consumption is authoritative for each APPLIED
# rank; the independent SavedData reader below proves the exact building rank.
scmd "item replace entity $PLAYER hotbar.0 with hearthstead:thorned_roads_seal 3"
scmd "execute store result score #seal hsqa_tmp run clear $PLAYER hearthstead:thorned_roads_seal 0"
assert_server plaque_stack_3 "HSQA_BR_PLAQUE_STACK_3" \
    "execute if score #seal hsqa_tmp matches 3 run say HSQA_BR_PLAQUE_STACK_3" \
    "plaque seal stack starts at exactly 3"
assert_server plaque_held "HSQA_BR_PLAQUE_HELD" \
    "execute if items entity $PLAYER weapon.mainhand hearthstead:thorned_roads_seal run say HSQA_BR_PLAQUE_HELD" \
    "Thorned Roads Seal is the actual selected main-hand item"
scmd "tp $PLAYER 522.5 -59 516.5"
scmd "execute as $PLAYER at @s anchored eyes facing 522.5 -57.34 519.0 run tp @s 522.5 -59 516.5 ~ ~"
sleep 3
scmd "data get entity $PLAYER Pos"
scmd "data get entity $PLAYER Rotation"
assert_server plaque_hit_precheck "HSQA_BR_PLAQUE_HIT" \
    "execute if entity @a[name=$PLAYER,x=522.5,y=-59,z=516.5,distance=..0.2,y_rotation=-1..1,x_rotation=-2..1] if block 522 -58 517 minecraft:air if block 522 -58 518 minecraft:air if block $PLAQUE_X $PLAQUE_Y $PLAQUE_Z hearthstead:plaque[facing=north] run say HSQA_BR_PLAQUE_HIT" \
    "server pose aims through the standing/crouching overlap of the exact north plaque face"
for rank in 1 2 3; do
    assert_world_input "plaque_world_rank_$rank" block \
        "$PLAQUE_X,$PLAQUE_Y,$PLAQUE_Z"
    capture_client "plaque-before-rank-$rank"
    sneak_right_click "plaque_rank_$rank" block \
        "$PLAQUE_X,$PLAQUE_Y,$PLAQUE_Z"
    remaining=$((3 - rank))
    scmd "execute store result score #seal hsqa_tmp run clear $PLAYER hearthstead:thorned_roads_seal 0"
    assert_server "plaque_seal_count_$rank" "HSQA_BR_PLAQUE_SEAL_${rank}" \
        "execute if score #seal hsqa_tmp matches $remaining run say HSQA_BR_PLAQUE_SEAL_${rank}" \
        "physical plaque click $rank consumed exactly one APPLIED seal; remaining=$remaining"
done

# Deliberately malformed second target.  Vanilla /data makes the entity load
# the bad ledger through its real decoder; the in-memory rewrite must already
# expose sticky, inert quarantine before any chunk or process reload.
scmd "data modify entity @e[tag=hsqa_quarantine,limit=1] TargetBlessings set value {DataVersion:1,Quarantined:0b,Ranks:[{WireId:0,Id:\"warden_oath\",Rank:99}]}"
assert_server quarantine_created "HSQA_BR_QUARANTINE_CREATED" \
    "execute if data entity @e[tag=hsqa_quarantine,limit=1] TargetBlessings{DataVersion:1,Quarantined:1b} unless data entity @e[tag=hsqa_quarantine,limit=1] TargetBlessings.Ranks[0] run say HSQA_BR_QUARANTINE_CREATED" \
    "malformed rank decoded to sticky empty quarantine"

EXPECTED_SETTLEMENT=""
EXPECTED_BUILDING=""
PREVIOUS_SAVED_MTIME=0

assert_runtime_state() { # pass
    local pass="$1" blessed_now quarantine_now viewer_now
    assert_server "world_token_$pass" "HSQA_BR_WORLD_TOKEN_$pass" \
        "execute if score #world hsqa_world matches $WORLD_SCORE run say HSQA_BR_WORLD_TOKEN_$pass" \
        "same persistent world token on pass $pass"
    assert_server "chunk_loaded_$pass" "HSQA_BR_CHUNK_LOADED_$pass" \
        "execute in minecraft:overworld if loaded $PLAQUE_X $PLAQUE_Y $PLAQUE_Z run say HSQA_BR_CHUNK_LOADED_$pass" \
        "target chunk loaded on pass $pass"
    assert_server "settler_state_$pass" "HSQA_BR_SETTLER_STATE_$pass" \
        "execute if data entity @e[tag=hsqa_blessed,limit=1] TargetBlessings{DataVersion:1,Quarantined:0b} if data entity @e[tag=hsqa_blessed,limit=1] TargetBlessings.Ranks[{WireId:1,Id:\"hearthward\",Rank:2}] unless data entity @e[tag=hsqa_blessed,limit=1] TargetBlessings.Ranks[{Id:\"warden_oath\"}] unless data entity @e[tag=hsqa_blessed,limit=1] TargetBlessings.Ranks[{Id:\"thorned_roads\"}] run say HSQA_BR_SETTLER_STATE_$pass" \
        "server entity state remains exact Hearthward II on pass $pass"
    assert_server "quarantine_state_$pass" "HSQA_BR_QUARANTINE_STATE_$pass" \
        "execute if data entity @e[tag=hsqa_quarantine,limit=1] TargetBlessings{DataVersion:1,Quarantined:1b} unless data entity @e[tag=hsqa_quarantine,limit=1] TargetBlessings.Ranks[0] run say HSQA_BR_QUARANTINE_STATE_$pass" \
        "quarantine remains sticky and empty on pass $pass"
    assert_server "plaque_state_$pass" "HSQA_BR_PLAQUE_STATE_$pass" \
        "execute if data block $PLAQUE_X $PLAQUE_Y $PLAQUE_Z {State:\"linked_valid\"} if data block $PLAQUE_X $PLAQUE_Y $PLAQUE_Z Building run say HSQA_BR_PLAQUE_STATE_$pass" \
        "registered plaque identity remains live on pass $pass"

    query_uuid "@e[tag=hsqa_blessed,limit=1]" "$EV_DIR/blessed-uuid-pass-$pass.txt" \
        || die "blessed_uuid_$pass" "could not read blessed UUID on pass $pass"
    query_uuid "@e[tag=hsqa_quarantine,limit=1]" "$EV_DIR/quarantine-uuid-pass-$pass.txt" \
        || die "quarantine_uuid_$pass" "could not read quarantine UUID on pass $pass"
    query_uuid "$PLAYER" "$EV_DIR/viewer-uuid-pass-$pass.txt" \
        || die "viewer_uuid_$pass" "could not read viewer UUID on pass $pass"
    cmp -s "$EV_DIR/blessed-uuid.txt" "$EV_DIR/blessed-uuid-pass-$pass.txt" \
        || die "blessed_uuid_$pass" "blessed settler UUID changed on pass $pass"
    cmp -s "$EV_DIR/quarantine-uuid.txt" "$EV_DIR/quarantine-uuid-pass-$pass.txt" \
        || die "quarantine_uuid_$pass" "quarantine settler UUID changed on pass $pass"
    cmp -s "$EV_DIR/viewer-uuid.txt" "$EV_DIR/viewer-uuid-pass-$pass.txt" \
        || die "viewer_uuid_$pass" "viewer UUID changed on reconnect $pass"
    check_pass "identities_$pass" "settler and viewer UUIDs unchanged on pass $pass"
}

snapshot_saved_data() { # pass
    local pass="$1" data_file="$WORLD/data/hearthstead_settlements.dat"
    local before after snapshot json inspect_log hash
    before=$(stat -c %Y "$data_file" 2>/dev/null || echo 0)
    sleep 2
    scmd "hearthstead scan $PLAQUE_X $PLAQUE_Y $PLAQUE_Z"
    scmd "save-all flush"
    scmd "say HSQA_BR_SAVE_FLUSHED_$pass"
    wait_log "HSQA_BR_SAVE_FLUSHED_$pass" 30 \
        || die "save_flush_$pass" "save-all flush did not complete on pass $pass"
    [ -s "$data_file" ] \
        || die "saved_data_$pass" "SavedData missing after pass $pass flush"
    after=$(stat -c %Y "$data_file" 2>/dev/null || echo 0)
    [ "$after" -gt "$before" ] \
        || die "saved_data_fresh_$pass" \
            "SavedData mtime did not advance on pass $pass ($before -> $after)"
    [ "$after" -gt "$PREVIOUS_SAVED_MTIME" ] \
        || die "saved_data_monotonic_$pass" \
            "SavedData mtime is not newer than the previous pass"
    PREVIOUS_SAVED_MTIME="$after"

    snapshot="$EV_DIR/hearthstead_settlements-pass-$pass.dat"
    json="$EV_DIR/saved-state-pass-$pass.json"
    inspect_log="$EV_LOGS/saved-state-pass-$pass.log"
    cp "$data_file" "$snapshot"
    hash=$(sha256sum "$snapshot" | cut -d' ' -f1)
    printf 'pass=%s\nsource=%s\nmtime=%s\nsha256=%s\n' \
        "$pass" "$data_file" "$after" "$hash" \
        > "$EV_DIR/saved-state-pass-$pass.meta"

    args=("$snapshot" --repo "$HSQA_REPO" --plaque \
        "$PLAQUE_X" "$PLAQUE_Y" "$PLAQUE_Z" --out "$json")
    [ -n "$EXPECTED_SETTLEMENT" ] \
        && args+=(--expected-settlement "$EXPECTED_SETTLEMENT")
    [ -n "$EXPECTED_BUILDING" ] \
        && args+=(--expected-building "$EXPECTED_BUILDING")
    python3 "$HERE/blessing_restart_inspect.py" "${args[@]}" \
        > "$inspect_log" 2>&1 \
        || die "saved_state_$pass" \
            "raw SavedData inspection failed on pass $pass: $(tail -1 "$inspect_log")"
    if [ -z "$EXPECTED_SETTLEMENT" ]; then
        EXPECTED_SETTLEMENT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["settlement_id"])' "$json")
        EXPECTED_BUILDING=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["building_id"])' "$json")
        printf 'settlement_id=%s\nbuilding_id=%s\n' \
            "$EXPECTED_SETTLEMENT" "$EXPECTED_BUILDING" \
            >> "$EV_DIR/world-identity.txt"
    fi
    check_pass "saved_data_fresh_$pass" \
        "flush mtime $before->$after sha256=$hash"
    check_pass "saved_state_$pass" "$(tail -1 "$inspect_log")"
}

clean_stop_server() { # pass
    local pass="$1" i holder pane_status
    scmd "stop"
    for i in $(seq 1 60); do
        hsqa_tmux_exact_pane_tuple "$SERVER_PANE_ID" >/dev/null 2>&1
        pane_status=$?
        [ "$pane_status" -eq 1 ] && break
        [ "$pane_status" -eq 0 ] \
            || die "clean_stop_$pass" "tmux pane query failed during clean stop"
        sleep 1
    done
    hsqa_tmux_exact_pane_tuple "$SERVER_PANE_ID" >/dev/null 2>&1
    pane_status=$?
    if [ "$pane_status" -eq 0 ]; then
        die "clean_stop_$pass" "server process did not exit within 60s"
    fi
    [ "$pane_status" -eq 1 ] \
        || die "clean_stop_$pass" "could not prove exact server pane absence"
    cp "$INST/logs/latest.log" "$EV_LOGS/server-$pass-latest.log" \
        2>/dev/null || true
    grep -q 'Stopping server' "$EV_LOGS/server-$pass-latest.log" \
        || die "clean_stop_$pass" "boot $pass lacks Stopping server marker"
    grep -q 'Saving worlds' "$EV_LOGS/server-$pass-latest.log" \
        || die "clean_stop_$pass" "boot $pass lacks Saving worlds marker"
    hsqa_stop_tracked "$SERVER_EFFECTIVE_ROLE" "$SERVER_RECORD" \
        || die "clean_stop_$pass" "exact server group survived; recovery record retained"
    SERVER_RECORD=""
    SERVER_WINDOW_ID=""; SERVER_PANE_ID=""; SERVER_PANE_PID=""; SERVER_PGID=""
    for i in $(seq 1 15); do
        holder=$(port_holder "$PORT")
        [ -z "$holder" ] && break
        sleep 1
    done
    [ -z "${holder:-}" ] \
        || die "port_released_$pass" "port $PORT remains held by $holder"
    check_pass "clean_stop_$pass" \
        "real stop completed, process exited, worlds saved"
    check_pass "port_released_$pass" "port $PORT free before next boot"
}

# Actual chunk unload/reload while the initial real viewer remains connected.
scmd "execute in minecraft:the_nether run forceload add 0 0"
scmd "execute in minecraft:the_nether run fill -2 79 -2 2 79 2 minecraft:stone"
scmd "execute in minecraft:the_nether run tp $PLAYER 0.5 80 0.5 0 0"
sleep 3
scmd "forceload remove 512 512"
sleep 8
assert_server chunk_actually_unloaded "HSQA_BR_CHUNK_UNLOADED" \
    "execute in minecraft:overworld unless loaded $PLAQUE_X $PLAQUE_Y $PLAQUE_Z run say HSQA_BR_CHUNK_UNLOADED" \
    "target chunk was genuinely unloaded with viewer in Nether"
scmd "execute in minecraft:overworld run forceload add 512 512"
sleep 5
assert_runtime_state 0
snapshot_saved_data 0
clean_stop_server 0
stop_client_process

for pass in 1 2; do
    boot_server "$pass"
    start_client "$pass"
    assert_runtime_state "$pass"
    snapshot_saved_data "$pass"
    clean_stop_server "$pass"
    stop_client_process
done

WORLD_BYTES=$(du -sb "$WORLD" | awk '{print $1}')
[ "$WORLD_BYTES" -le "$MAX_WORLD_BYTES" ] \
    || die world_size "world artifact is $WORLD_BYTES bytes, limit is $MAX_WORLD_BYTES"
check_pass world_size "full world is bounded at $WORLD_BYTES bytes"
preserve_world
[ -s "$EV_DIR/$LEVEL_NAME.tar.gz" ] \
    || die world_archive "bounded full-world artifact was not written"
check_pass world_archive \
    "full exact world archived with SHA-256 ($(cut -d' ' -f1 "$EV_DIR/$LEVEL_NAME.tar.gz.sha256"))"

DURATION=$(( $(date +%s) - START_EPOCH ))
check_pass duration "blessing restart gate completed in ${DURATION}s"
teardown || die process_cleanup "restart client/server/Xvfb, tmux, display or port survived exact teardown; recovery record retained"
[ "$TEARDOWN_PENDING_SIGNAL" -eq 0 ] \
    || on_signal "$TEARDOWN_PENDING_SIGNAL"
finish_result PASS
write_reproduction "# Reproduce: Blessing restart persistence
tools/hearthstead-qa blessing-restart

Role: $ROLE
Port: $PORT
Instance: $INST
Exact world: $WORLD
World token: $WORLD_SCORE
Settlement: $EXPECTED_SETTLEMENT
Building: $EXPECTED_BUILDING
Duration: ${DURATION}s

The evidence directory preserves all three server/client logs, three raw
hearthstead_settlements.dat snapshots with mtime/hash metadata, normalized
state JSON, stable entity/viewer UUIDs, and a bounded full-world archive.
"
echo "blessing restart ok: chunk unload + 2 clean restarts + 3 viewer joins; duration=${DURATION}s; world=$WORLD"
