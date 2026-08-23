#!/usr/bin/env bash
# Real-client boot under Xvfb with software GL. Proves the client starts,
# registers renderers/screens, and reaches the title screen; captures a
# framebuffer screenshot as visual evidence (AC-3). Args: <mod-dir> <artifact-dir>
set -u
MOD="$1"; OUT="$2"
HERE="$(dirname "${BASH_SOURCE[0]}")"
. "$HERE/lib_harness.sh"

ROLE="client"
DISPLAY_NUM=":97"
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe

ev_init "$ROLE"

TEARDOWN_DONE=0
teardown() {
    [ "$TEARDOWN_DONE" = 1 ] && return
    TEARDOWN_DONE=1
    [ -n "${GRADLE_PID:-}" ] && kill -9 -- "-$GRADLE_PID" 2>/dev/null
    pkill -9 -f "neoforge.*client" 2>/dev/null || true
    [ -n "${XVFB_PID:-}" ] && kill -9 "$XVFB_PID" 2>/dev/null
    clear_pidfile "${ROLE}-xvfb"
    clear_pidfile "${ROLE}"
}
trap teardown EXIT INT TERM

Xvfb "$DISPLAY_NUM" -screen 0 1280x720x24 > "$EV_LOGS/xvfb.log" 2>&1 &
XVFB_PID=$!
register_pid "${ROLE}-xvfb" "$XVFB_PID"
sleep 2
kill -0 "$XVFB_PID" 2>/dev/null || die xvfb "Xvfb failed to start — see logs/xvfb.log"
check_pass xvfb "Xvfb :97 up (pid $XVFB_PID)"

cd "$MOD"
set -m
DISPLAY="$DISPLAY_NUM" timeout 420 ./gradlew runClient > "$EV_LOGS/client-run.log" 2>&1 &
GRADLE_PID=$!
set +m
register_pid "$ROLE" "-$GRADLE_PID"

TITLE_SEEN=0
BUILD_FAILED=0
for i in $(seq 1 130); do
    sleep 3
    if grep -qE "BUILD FAILED|FAILURE: Build failed" "$EV_LOGS/client-run.log" 2>/dev/null; then
        BUILD_FAILED=1; break
    fi
    if grep -qE "Realms Notification|Sound engine started" "$EV_LOGS/client-run.log" 2>/dev/null; then
        sleep 12   # let the title screen actually render
        TITLE_SEEN=1
        break
    fi
    kill -0 "$GRADLE_PID" 2>/dev/null || break
done

if [ "$BUILD_FAILED" = 1 ]; then
    FIRST_ERROR=$(grep -m1 -E 'error:' "$EV_LOGS/client-run.log" 2>/dev/null || echo "(no 'error:' line — see logs/client-run.log)")
    die client_build "client build failed: $FIRST_ERROR"
fi

if command -v import >/dev/null; then
    DISPLAY="$DISPLAY_NUM" import -window root "$EV_SHOTS/screenshot-title.png" 2>/dev/null || true
fi

if grep -qE "Exception in thread|Failed to start|crash-report" "$EV_LOGS/client-run.log"; then
    die client_crash "client crashed during boot"
fi
if [ "$TITLE_SEEN" != 1 ]; then
    die title_reached "client never reached resource load / sound engine"
fi
check_pass title_reached "sound engine / resource reload seen in client-run.log"

if [ -s "$EV_SHOTS/screenshot-title.png" ]; then
    RES=$(python3 "$HERE/check_screenshot.py" "$EV_SHOTS/screenshot-title.png" 2>&1)
    if echo "$RES" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("pass") else 1)' 2>/dev/null; then
        check_pass screenshot_valid "$RES"
    else
        die screenshot_valid "title screenshot failed AC-3: $RES"
    fi
else
    die screenshot_captured "no screenshot captured (install imagemagick or xwd)"
fi

finish_result PASS
write_reproduction "# Reproduce: client
tools/hearthstead-qa client
"
echo "client boot ok under Xvfb; screenshot: shots/screenshot-title.png"
