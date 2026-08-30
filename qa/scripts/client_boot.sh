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
TEARDOWN_STATUS=0
GRADLE_ROLE="$ROLE"; GRADLE_PID=""; GRADLE_PGID=""; GRADLE_RECORD=""
XVFB_ROLE="${ROLE}-xvfb"; XVFB_PID=""; XVFB_PGID=""; XVFB_RECORD=""
teardown() {
    local cleanup_status=0
    [ "$TEARDOWN_DONE" = 1 ] && return "$TEARDOWN_STATUS"
    TEARDOWN_DONE=1
    hsqa_stop_tracked "$GRADLE_ROLE" "$GRADLE_RECORD" || cleanup_status=1
    hsqa_stop_tracked "$XVFB_ROLE" "$XVFB_RECORD" || cleanup_status=1
    for _ in $(seq 1 20); do
        DISPLAY="$DISPLAY_NUM" xdotool getmouselocation >/dev/null 2>&1 || break
        sleep 0.1
    done
    DISPLAY="$DISPLAY_NUM" xdotool getmouselocation >/dev/null 2>&1 \
        && { echo "FATAL: display $DISPLAY_NUM remained live after teardown" >&2; cleanup_status=1; }
    TEARDOWN_STATUS="$cleanup_status"
    return "$cleanup_status"
}
on_signal() {
    local status="$1" cleanup_status=0 final_status
    trap - EXIT INT TERM
    teardown || cleanup_status=1
    hsqa_finish_interrupted "$status" client \
        "tools/hearthstead-qa client" "$cleanup_status"
    final_status=$?
    exit "$final_status"
}
on_exit() {
    hsqa_exit_after_cleanup "$?" client "tools/hearthstead-qa client" teardown
}
trap on_exit EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

hsqa_launch_tracked_group "${ROLE}-xvfb" client-boot-xvfb on_signal \
    XVFB_PID XVFB_PGID XVFB_RECORD XVFB_ROLE \
    Xvfb "$DISPLAY_NUM" -screen 0 1280x720x24 \
    > "$EV_LOGS/xvfb.log" 2>&1 \
    || die xvfb "could not safely launch/register Xvfb"
sleep 2
kill -0 "$XVFB_PID" 2>/dev/null || die xvfb "Xvfb failed to start — see logs/xvfb.log"
check_pass xvfb "Xvfb :97 up (pid $XVFB_PID)"

# Written deterministically every run, same as playtest.sh/live.sh (finding
# 12) — the client rewrites options.txt on its own exit, so a prior run's
# leftovers must never be what this run happens to boot with.
RUN_DIR=$(hsqa_require_plain_mod_run "$MOD") \
    || die run_directory "module run directory is symlinked or unsafe"
OPTIONS_FILE=$(hsqa_require_plain_mod_run_file "$MOD" options.txt) \
    || die options_path "module options.txt path is symlinked or unsafe"
cat > "$OPTIONS_FILE" <<'OPTS'
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

hsqa_launch_tracked_group "$ROLE" client-boot-client on_signal \
    GRADLE_PID GRADLE_PGID GRADLE_RECORD GRADLE_ROLE \
    bash -c 'cd "$1" || exit 1; exec env DISPLAY="$2" timeout --kill-after=10 --foreground 700 ./gradlew --no-daemon runClient' \
    -- "$MOD" "$DISPLAY_NUM" > "$EV_LOGS/client-run.log" 2>&1 \
    || die client_launch "could not safely launch/register client"

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

teardown || die process_cleanup "client/Xvfb process or display survived exact teardown; recovery record retained"
finish_result PASS
write_reproduction "# Reproduce: client
tools/hearthstead-qa client
"
echo "client boot ok under Xvfb; screenshot: shots/screenshot-title.png"
