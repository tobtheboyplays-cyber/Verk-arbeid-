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

# Written deterministically every run, same as playtest.sh/live.sh (finding
# 12) — the client rewrites options.txt on its own exit, so a prior run's
# leftovers must never be what this run happens to boot with.
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

cd "$MOD"
set -m
DISPLAY="$DISPLAY_NUM" timeout --foreground 700 ./gradlew runClient > "$EV_LOGS/client-run.log" 2>&1 &
GRADLE_PID=$!
set +m
register_pid "$ROLE" "-$GRADLE_PID"

# Title-screen readiness: neither a specific log string nor mere window
# existence is reliable here. "Sound engine started" never appears (sound is
# unavailable in this environment) and "Realms Notification" depends on an
# outbound HTTPS call that can stall for minutes behind this environment's
# proxy. Window existence is worse: GLFW creates the window immediately on
# backend init, minutes before any content is actually rendered (proven: a
# screenshot taken right after window-appears was a 156-colour near-blank
# frame that correctly failed AC-3). So readiness IS AC-3 itself: poll by
# actually screenshotting and asking check_screenshot.py whether it looks
# like real rendered content yet. Budget is minutes, not a hang.
#
# Finding 2: NEVER `import -window root`. Xvfb's own virtual screen here is
# ALSO 1280x720 (`-screen 0 1280x720x24` above) — root capture coincidentally
# passes check_screenshot.py's size assertion regardless of what size the
# actual game window is, which is exactly how a genuinely 854x480 window
# (the `--width`/`--height` args not having taken effect) passed as "1280x720"
# undetected. Capture the RECORDED window id and assert ITS OWN reported
# geometry is 1280x720 before ever screenshotting it.
TITLE_SEEN=0
BUILD_FAILED=0
WIN=""
for i in $(seq 1 234); do
    sleep 3
    if grep -qE "BUILD FAILED|FAILURE: Build failed" "$EV_LOGS/client-run.log" 2>/dev/null; then
        BUILD_FAILED=1; break
    fi
    WIN=$(DISPLAY="$DISPLAY_NUM" xdotool search --name "Minecraft" 2>/dev/null | tail -1)
    if [ -n "$WIN" ]; then
        GEOM=$(DISPLAY="$DISPLAY_NUM" xdotool getwindowgeometry --shell "$WIN" 2>/dev/null)
        WIDTH=""; HEIGHT=""
        eval "$GEOM" 2>/dev/null
        if [ "${WIDTH:-0}" = 1280 ] && [ "${HEIGHT:-0}" = 720 ]; then
            DISPLAY="$DISPLAY_NUM" import -window "$WIN" "$EV_SHOTS/.probe.png" 2>/dev/null || true
            if [ -s "$EV_SHOTS/.probe.png" ] && python3 "$HERE/check_screenshot.py" "$EV_SHOTS/.probe.png" >/dev/null 2>&1; then
                mv "$EV_SHOTS/.probe.png" "$EV_SHOTS/screenshot-title.png"
                TITLE_SEEN=1
                check_pass window_geometry "window $WIN is ${WIDTH}x${HEIGHT} (recorded, not root)"
                break
            fi
        fi
    fi
    kill -0 "$GRADLE_PID" 2>/dev/null || break
done
rm -f "$EV_SHOTS/.probe.png"

if [ "$BUILD_FAILED" = 1 ]; then
    FIRST_ERROR=$(grep -m1 -E 'error:' "$EV_LOGS/client-run.log" 2>/dev/null || echo "(no 'error:' line — see logs/client-run.log)")
    die client_build "client build failed: $FIRST_ERROR"
fi

if grep -qE "Exception in thread|Failed to start|crash-report" "$EV_LOGS/client-run.log"; then
    die client_crash "client crashed during boot"
fi
if [ "$TITLE_SEEN" != 1 ]; then
    die title_reached "client never produced a real rendered frame (window/build/crash all inconclusive) — see logs/client-run.log"
fi
check_pass title_reached "Minecraft window rendered real content (passed AC-3) within budget"

if [ -s "$EV_SHOTS/screenshot-title.png" ]; then
    RES=$(python3 "$HERE/check_screenshot.py" "$EV_SHOTS/screenshot-title.png" 2>&1)
    check_pass screenshot_valid "$RES"
else
    die screenshot_captured "no screenshot captured (install imagemagick or xwd)"
fi

finish_result PASS
write_reproduction "# Reproduce: client
tools/hearthstead-qa client
"
echo "client boot ok under Xvfb; screenshot: shots/screenshot-title.png"
