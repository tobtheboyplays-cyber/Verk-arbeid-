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
#     sneak_click [left|right]            hold Shift and click at screen centre;
#                                          releases Shift before returning
#     click_at <x-percent> <y-percent>     left-click inside the game window
#     open_near_block <id> <radius> <button> <label>
#                                          find nearest same-Y block server-side,
#                                          aim at it, then physically click it
#     insert_plan_at <plaque x y z> <stand x y z> <target x y z>
#                                          verify selected Build Plan, aim at
#                                          the target centre, physically
#                                          right-click, and retry only while
#                                          server block state still lacks it
#     move <dx> <dy>                      move the mouse (look around)
#     shot <name>                         capture shots/<name>.png
#     shot_now <name>                     capture immediately, without shot's
#                                          one-second settle delay; reserved for
#                                          subsecond animation timing evidence
#     expect_server <regex>               FAIL unless regex is in the server's
#                                          OWN Log4j output (logs/latest.log,
#                                          never the tmux pane transcript,
#                                          which echoes back a scmd's typed
#                                          text before the server ever runs
#                                          it — see the SRV_LOG note below)
#     expect_shot <name>                  FAIL unless shots/<name>.png passes AC-3
#     expect_screen <fully.qualified.ClassName>
#                                          FAIL unless the client observer sees
#                                          that exact open screen with mouse released
#     expect_pixel_change <before> <after> <min-pct> [region]
#                                          FAIL unless the two named shots differ by
#                                          more than min-pct in `region` (lower-third|full)
#     expect_rotation_change <min-degrees>
#                                          FAIL unless the two most recent server-side
#                                          `data get entity $PLAYER Rotation` results
#                                          (bracket a `look` with two such scmd calls)
#                                          differ in yaw by more than min-degrees
#   $PLAYER in any directive's arguments is substituted with the joined player's name.
#   Every directive (not only expect_*) records its own outcome in
#   result.json (AC-14) — an unrecognised directive (a typo) is a hard FAIL,
#   not a silently-ignored line, because a typo'd directive would otherwise
#   remove an assertion invisibly.
#
# No `expect_block_near_player` directive: a non-destructive existence check
# via `fill <box> <block> replace <block>` was tried and rejected — proven
# live that a self-replace changes nothing, so the game never counts it as a
# success even when the block is right there; it would FAIL unconditionally
# regardless of truth. Query mod-authoritative state instead (e.g.
# `scmd hearthstead info` / `expect_server`), which is what default.txt does.
set -u
MOD="$1"; OUT="$2"; SCENARIO="${3:-${HSQA_PLAYTEST_SCENARIO:-}}"
HERE="$(dirname "${BASH_SOURCE[0]}")"
unset HSQA_TMUX_SELFTEST_MODE HSQA_TMUX_SOCKET
export HSQA_PIDDIR=/tmp/claude-0/hsqa-pids-v2
. "$HERE/lib_harness.sh"
. "$HERE/lib_tmux_harness.sh"
REPO="$HSQA_REPO"
[ -n "$SCENARIO" ] || SCENARIO="$REPO/qa/scenarios/default.txt"
if [ "${SCENARIO#/}" = "$SCENARIO" ]; then
    SCENARIO="$REPO/$SCENARIO"
fi
GUI_SCALE="${HSQA_GUI_SCALE:-3}"
case "$GUI_SCALE" in
    2|3|4) ;;
    *) echo "FAIL: HSQA_GUI_SCALE must be 2, 3 or 4; got: $GUI_SCALE"; exit 1;;
esac
CLIENT_LANGUAGE="${HSQA_LANGUAGE:-en_us}"
case "$CLIENT_LANGUAGE" in
    en_us|nb_no) ;;
    *) echo "FAIL: HSQA_LANGUAGE must be en_us or nb_no; got: $CLIENT_LANGUAGE"; exit 1;;
esac
DISPLAY_WIDTH="${HSQA_DISPLAY_WIDTH:-1280}"
DISPLAY_HEIGHT="${HSQA_DISPLAY_HEIGHT:-720}"
case "${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}" in
    1280x720|1920x1080) ;;
    *) echo "FAIL: supported display profiles are 1280x720 and 1920x1080; got: ${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}"; exit 1;;
esac
CENTER_X=$((DISPLAY_WIDTH / 2))
CENTER_Y=$((DISPLAY_HEIGHT / 2))

ROLE="playtest"
PORT="${HSQA_PLAYTEST_PORT:-25573}"
DISPLAY_NUM=":98"
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export DISPLAY="$DISPLAY_NUM"
# This suite does not judge sound. OpenAL's changing WSL/RDP default device
# has blocked the render thread for 30s while enumerating a replacement
# device (20260827T023842Z), which in turn prevented client teleport acks and
# made an input test fail for an unrelated host-audio event. The null backend
# keeps audio initialization deterministic without changing any game logic.
export ALSOFT_DRIVERS=null

ev_init "$ROLE"

command -v xdotool >/dev/null || { echo "FAIL: xdotool missing"; exit 1; }
[ -f "$SCENARIO" ] || { echo "FAIL: scenario not found: $SCENARIO"; exit 1; }

# The runtime sentinel below proves that the selected language was genuinely
# loaded. This static parity gate complements it by preventing one missing
# card/button/footer key from falling back to English while the Norwegian
# title alone still passes.
if ! LANGUAGE_ASSET_EVIDENCE=$(python3 - \
        "$MOD/src/main/resources/assets/hearthstead/lang/en_us.json" \
        "$MOD/src/main/resources/assets/hearthstead/lang/nb_no.json" 2>&1 <<'PYEOF'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    english = json.load(stream)
with open(sys.argv[2], encoding="utf-8") as stream:
    norwegian = json.load(stream)

prefix = "hearthstead.blessing."
english_keys = {key for key in english if key.startswith(prefix)}
norwegian_keys = {key for key in norwegian if key.startswith(prefix)}
missing_nb = sorted(english_keys - norwegian_keys)
extra_nb = sorted(norwegian_keys - english_keys)
identical = sorted(
    key for key in english_keys & norwegian_keys
    if english[key] == norwegian[key]
)
if not english_keys or missing_nb or extra_nb or identical:
    print(
        "Blessing language parity failed: "
        f"en={len(english_keys)} nb={len(norwegian_keys)} "
        f"missing_nb={missing_nb} extra_nb={extra_nb} identical={identical}"
    )
    raise SystemExit(1)
print(
    f"{len(english_keys)}/{len(english_keys)} Blessing keys present and "
    "distinct in en_us and nb_no"
)
PYEOF
); then
    die language_assets "$LANGUAGE_ASSET_EVIDENCE"
fi
check_pass language_assets "$LANGUAGE_ASSET_EVIDENCE"

TMUX_PT="hsqa-playtest"
TMUX_PT_OWNER="playtest-tmux"
TMUX_PT_ID=""
TEARDOWN_DONE=0
TEARDOWN_RUNNING=0
TEARDOWN_PENDING_SIGNAL=0
TEARDOWN_STATUS=0
GRADLE_ROLE="${ROLE}-client"; GRADLE_PID=""; GRADLE_PGID=""; GRADLE_RECORD=""
XVFB_ROLE="${ROLE}-xvfb"; XVFB_PID=""; XVFB_PGID=""; XVFB_RECORD=""
SERVER_EFFECTIVE_ROLE="${ROLE}-server"; SERVER_WINDOW_ID=""; SERVER_PANE_ID=""; SERVER_PANE_PID=""; SERVER_PGID=""; SERVER_RECORD=""
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
    # $INST is ephemeral (server_instance.sh rm -rf's it on this role's next
    # run) — preserve the authoritative server log in durable evidence
    # regardless of which exit path got here (pass, die(), or an abort).
    [ -n "${INST:-}" ] && [ -f "$INST/logs/latest.log" ] \
        && cp "$INST/logs/latest.log" "$EV_LOGS/playtest-server-latest.log" 2>/dev/null
    if hsqa_stop_tracked "$GRADLE_ROLE" "$GRADLE_RECORD"; then
        GRADLE_RECORD=''
    else
        cleanup_status=1
    fi
    if hsqa_stop_tracked "$SERVER_EFFECTIVE_ROLE" "$SERVER_RECORD"; then
        SERVER_RECORD=''
    else
        cleanup_status=1
    fi
    [ -z "$TMUX_PT_ID" ] \
        || hsqa_stop_owned_tmux_session "$TMUX_PT_ID" "$TMUX_PT_OWNER" \
        || cleanup_status=1
    hsqa_wait_port_free "$PORT" || cleanup_status=1
    hsqa_stop_tracked "$XVFB_ROLE" "$XVFB_RECORD" || cleanup_status=1
    for _ in $(seq 1 20); do
        DISPLAY="$DISPLAY_NUM" xdotool getmouselocation >/dev/null 2>&1 || break
        sleep 0.1
    done
    DISPLAY="$DISPLAY_NUM" xdotool getmouselocation >/dev/null 2>&1 \
        && cleanup_status=1
    [ -n "${FRAME_REQUEST:-}" ] && rm -f -- "$FRAME_REQUEST"
    [ -n "${FRAME_REQUEST_TMP:-}" ] && rm -f -- "$FRAME_REQUEST_TMP"
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
    hsqa_finish_interrupted "$status" playtest \
        "tools/hearthstead-qa playtest" "$cleanup_status"
    final_status=$?
    exit "$final_status"
}
on_exit() {
    hsqa_exit_after_cleanup "$?" playtest \
        "tools/hearthstead-qa playtest" teardown TEARDOWN_PENDING_SIGNAL
}
trap on_exit EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

# FACT 1: port free.
if ! MSG=$(preflight_port "$PORT" "$ROLE"); then die port_preflight "$MSG"; fi
check_pass port_preflight "port $PORT free before launch"

JAR=$(ls -t "$MOD"/build/libs/hearthstead-*.jar 2>/dev/null | grep -v sources | head -1)
[ -n "$JAR" ] || die build_jar "no mod jar built — run build first"
# BLOCKER_GATE (2026-08-24): this used to stop at "a jar exists", never at
# "a jar built from the CURRENT source" — so playtest silently tested
# whatever server code happened to be sitting in build/libs, however old.
# Proven live: five straight verification runs (113853Z through 122434Z)
# chased a phantom harness bug because the dedicated server was running a
# six-hour-old jar that predated three real source fixes, while
# runGameTestServer/runClient compiled fresh each time and so reflected
# them — the exact split that made "compiles clean, GameTest 19/19" and
# "playtest still fails the identical way" look like a contradiction
# instead of the stale-artifact problem it was. If ANY source file this
# jar should have been built from is newer than the jar itself, refuse to
# run rather than report on code that no longer exists.
NEWEST_SRC=$(find "$MOD/src" "$MOD/build.gradle" "$MOD/gradle.properties" \
    -type f -newer "$JAR" 2>/dev/null | head -1)
if [ -n "$NEWEST_SRC" ]; then
    die build_jar "stale jar: $(basename "$JAR") predates $NEWEST_SRC — run tools/hearthstead-qa full (or rebuild the jar) before playtest"
fi
check_pass build_jar "$(basename "$JAR")"

bash "$HERE/server_install.sh" "$MOD" > "$EV_LOGS/install.log" 2>&1 \
    || die install "shared NeoForge install failed — see logs/install.log"
check_pass install "shared install present"

INST=$(bash "$HERE/server_instance.sh" "$ROLE" "$PORT" "$MOD" 2>"$EV_LOGS/instance.log" | tail -1)
[ -d "$INST" ] || die instance "server_instance.sh did not produce a usable instance — see logs/instance.log"
check_pass instance "$INST"

# AUTHORITATIVE server log (finding 1): $EV_LOGS/playtest-server.log is a
# `tee` of the tmux PANE, which is a real terminal — it echoes back whatever
# `scmd()` TYPES the instant tmux sends the keystrokes, before Enter is even
# processed. Proven live: `expect_server:HS_SETTLER_PRESENT` matched the
# still-being-typed prompt line `> execute if entity @e[...] run say
# HS_SETTLER_PRESENT`, not anything the server actually executed — it would
# have passed with zero settlers. logs/latest.log is the server's own Log4j
# file output: it is written only by code that actually ran, never by a
# terminal echoing keystrokes, so it cannot self-satisfy this way. Every
# check of "did the server really say/do X" reads THIS file; the tee'd pane
# log is kept only for build/crash diagnostics and human debugging.
SRV_LOG="$INST/logs/latest.log"

hsqa_launch_tracked_group "${ROLE}-xvfb" playtest-xvfb on_signal \
    XVFB_PID XVFB_PGID XVFB_RECORD XVFB_ROLE \
    Xvfb "$DISPLAY_NUM" -screen 0 "${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}x24" \
    > "$EV_LOGS/xvfb.log" 2>&1 \
    || die xvfb "could not safely launch/register Xvfb"
sleep 2
kill -0 "$XVFB_PID" 2>/dev/null || die xvfb "Xvfb failed to start — see logs/xvfb.log"
check_pass xvfb "Xvfb :98 up (pid $XVFB_PID)"

# The client's game directory is run/ (that is where its logs and saves
# land), NOT run/client — options written anywhere else are silently
# ignored, which leaves the accessibility onboarding screen up and blocks
# quickPlay entirely (KF-006). Written deterministically every run so the
# window is always exactly the requested profile (regression risk: the client rewrites
# this file on its own exit).
# rawMouseInput:false matters more than it looks: with it true (the
# default), GLFW reads camera look from XInput2 raw motion events, which
# xdotool's XTest-synthesized motion never generates — `look`/`move`
# directives silently produce zero rotation change with it on (proven: a
# live diagnostic session showed the mouse's X11 pointer position moving
# correctly while server-side Rotation stayed exactly [0.0f, 0.0f]). With it
# false, GLFW falls back to ordinary pointer-motion deltas, which XTest does
# drive.
RUN_DIR=$(hsqa_require_plain_mod_run "$MOD") \
    || die run_directory "module run directory is symlinked or unsafe"
OPTIONS_FILE=$(hsqa_require_plain_mod_run_file "$MOD" options.txt) \
    || die options_path "module options.txt path is symlinked or unsafe"
FRAME_REQUEST="$RUN_DIR/hsqa-frame-request"
FRAME_REQUEST_TMP=""
rm -f -- "$FRAME_REQUEST"
cat > "$OPTIONS_FILE" <<OPTS
onboardAccessibility:false
skipMultiplayerWarning:true
pauseOnLostFocus:false
guiScale:$GUI_SCALE
lang:$CLIENT_LANGUAGE
fullscreen:false
overrideWidth:$DISPLAY_WIDTH
overrideHeight:$DISPLAY_HEIGHT
tutorialStep:none
rawMouseInput:false
mouseSensitivity:0.5
renderDistance:6
simulationDistance:6
OPTS
check_pass display_profile_requested \
    "${DISPLAY_WIDTH}x${DISPLAY_HEIGHT} with requested guiScale $GUI_SCALE in $CLIENT_LANGUAGE"

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
# -x/-y: a detached tmux session defaults to a narrow (~80-column) terminal;
# a Minecraft command longer than the pane width gets corrupted by the
# console's line-wrap redraw (proven live: a `fill` command arrived with an
# ANSI cursor-move escape spliced into its middle). A wide pane avoids it.
hsqa_create_owned_tmux_session "$TMUX_PT" "$TMUX_PT_OWNER" on_signal \
    TMUX_PT_ID || die server_launch "could not create an exact owned tmux session"
hsqa_launch_tracked_tmux_window "$TMUX_PT_ID" "$TMUX_PT_OWNER" server \
    "${ROLE}-server" playtest-server on_signal SERVER_WINDOW_ID \
    SERVER_PANE_ID SERVER_PANE_PID SERVER_PGID SERVER_RECORD SERVER_EFFECTIVE_ROLE \
    bash -c 'set -o pipefail; cd "$1" || exit 1; timeout --kill-after=10 --foreground 900 ./run.sh nogui 2>&1 | tee "$2"' \
    -- "$INST" "$EV_LOGS/playtest-server.log" \
    || die server_launch "could not safely launch/register exact tmux server group"
scmd_now() {
    hsqa_tmux_pane_record_owned "$SERVER_PANE_ID" "$SERVER_PANE_PID" \
        "$SERVER_RECORD" playtest-server \
        || die server_console_identity \
            "server console pane/record identity changed before command"
    hsqa_tmux send-keys -l -t "$SERVER_PANE_ID" "$1" \
        && hsqa_tmux send-keys -t "$SERVER_PANE_ID" Enter \
        || die server_console_send "could not send command to exact server pane"
}
scmd() { scmd_now "$1"; sleep 2; }

SERVER_UP=0
for _ in $(seq 1 90); do
    sleep 2
    grep -q 'Done (' "$SRV_LOG" 2>/dev/null && { SERVER_UP=1; break; }
    hsqa_tmux has-session -t "$TMUX_PT_ID" 2>/dev/null || break
done
if [ "$SERVER_UP" != 1 ]; then
    REASON=$(grep -m1 -E 'FAILED TO BIND|Address already in use|Exception|Error' "$SRV_LOG" 2>/dev/null || echo "no Done( line — see logs/playtest-server.log")
    die server_started "server never reached Done( : $REASON"
fi
check_pass server_started "$(grep -m1 'Done (' "$SRV_LOG")"

# HSQA_TEST_BAD_JOIN_PORT is a test-only hook (AC-8/N4): points the client at
# a port nothing is listening on, so the server comes up fine but the client
# can never join — proving the harness reports THAT, with a diagnostic
# screenshot, rather than misreporting it as a server or build problem.
JOIN_PORT="${HSQA_TEST_BAD_JOIN_PORT:-$PORT}"
hsqa_launch_tracked_group "${ROLE}-client" playtest-client on_signal \
    GRADLE_PID GRADLE_PGID GRADLE_RECORD GRADLE_ROLE \
    bash -c 'cd "$1" || exit 1; exec env HSQA_CLIENT_OBSERVER=1 HSQA_JOIN="127.0.0.1:$2" timeout --kill-after=10 --foreground 900 ./gradlew --no-daemon runClient' \
    -- "$MOD" "$JOIN_PORT" > "$EV_LOGS/playtest-client.log" 2>&1 \
    || die client_launch "could not safely launch/register client"

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
    if grep -qE "joined the game|logged in with entity id" "$SRV_LOG" 2>/dev/null; then
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
    "$SRV_LOG" 2>/dev/null | tail -1)
[ -n "$PLAYER" ] || PLAYER=$(grep -oP '\K\w+(?= joined the game)' \
    "$SRV_LOG" 2>/dev/null | tail -1)
[ -n "$PLAYER" ] || die player_joined "joined the game seen but player name could not be parsed"
check_pass player_joined "$(grep -m1 "joined the game" "$SRV_LOG")"

scmd "op $PLAYER"
# AC-1: server-side corroboration beyond the join line itself.
scmd "data get entity $PLAYER Pos"
sleep 1
POS_LINE=$(grep -m1 -A0 "has the following entity data" "$SRV_LOG" | tail -1)
check_pass player_pos_query "${POS_LINE:-data get entity $PLAYER Pos issued}"

# Capture the game window itself, never the root window: anything else that
# opens on this display would otherwise end up in the "evidence".
WIN=$(xdotool search --name "Minecraft" 2>/dev/null | tail -1 || true)
# D-H4: windowactivate needs a window manager (none here, EWMH absent) and
# `key --window` is silently discarded by GLFW — so focus via windowfocus and
# always send input through XTEST (xdotool's default), never targeted.
#
# Self-healing, not just best-effort: the original one-liner cached $WIN
# once at boot and swallowed windowfocus's own exit code unconditionally
# (`2>/dev/null || true`), so a focus failure anywhere later in a long
# scenario was completely invisible -- every subsequent click/key/type still
# fired via XTEST at whatever window (if any) actually had input focus,
# which is not necessarily $WIN and not necessarily Minecraft at all. This
# is a live suspect for interactions that intermittently fail to register
# deep into a long scenario despite a correct crosshair and a correct held
# item: nothing downstream could ever tell "focused" and "silently failed"
# apart. Now: check windowfocus's actual exit code, and on failure,
# re-search for the window by name once (a stale id resolves to nothing;
# a fresh search finds it again) before giving up.
focus() {
    if [ -n "$WIN" ] && xdotool windowfocus --sync "$WIN" 2>/dev/null; then
        return 0
    fi
    local found
    found=$(xdotool search --name "Minecraft" 2>/dev/null | tail -1)
    if [ -n "$found" ]; then
        WIN="$found"
        if xdotool windowfocus --sync "$WIN" 2>/dev/null; then
            return 0
        fi
    fi
    # RELEASE_GATE finding #6: this used to swallow a persistent failure
    # (`|| true`) rather than surface it -- a caller that types/clicks right
    # after would silently hit whatever window (if any) happened to already
    # have focus, indistinguishable in the transcript from a real success.
    # Loud now so a real failure is finally visible instead of guessed at.
    echo "focus: FAILED to focus a Minecraft window (last known WIN=$WIN, search found=${found:-<none>})" >&2
    return 1
}

# KF-009/KF-035: the old grab-restoring LEFT click could break the exact
# block a test was observing. Moving the player to Y=300 before that click
# avoided the block, but created a much larger asynchronous chunk/render
# transition: under load the restore ack arrived 28s late, and a following
# sky teleport never reached the render thread inside 30s
# (20260827T023842Z). The safety mechanism had become the flake.
#
# X11 Button8 maps through GLFW's scroll-button gap to mouse index 3
# (`key.mouse.4`). The opt-in observer enumerates the fully registered runtime
# KeyMappings and refuses every frame ack unless that index is unbound. More
# importantly, the only Button8 press is sent after the observer has proved
# PauseScreen is open and mouse capture released; it is never sent into the
# world. The following Escape closes PauseScreen and invokes grabMouse().
#
# A chat ack is NOT an input fence: network work runs before mouse handling,
# while GLFW events are polled at the end of a frame. The opt-in client QA
# observer acknowledges a nonce only after two later RenderFrame.Post events.
# That spans updateDisplay/poll plus the following
# handleAccumulatedMovement, and also exposes the actual screen/grab state.
FRAME_COUNTER=0
FRAME_ACK_LINE=""
wait_client_frame() { # <expected screen|any> <expected grabbed|any> <context>
    local expected_screen="$1" expected_grabbed="$2" context="$3"
    local anchor nonce line="" timeout deadline
    anchor=$(wc -l < "$EV_LOGS/playtest-client.log" 2>/dev/null || echo 0)
    FRAME_COUNTER=$((FRAME_COUNTER + 1))
    nonce="${ROLE}_${FRAME_COUNTER}_${RANDOM}"
    FRAME_REQUEST_TMP="$RUN_DIR/.hsqa-frame-request.${nonce}.$$"
    printf '%s\n' "$nonce" > "$FRAME_REQUEST_TMP" \
        || { echo "$context: could not write client-frame nonce" >&2; return 1; }
    mv -f -- "$FRAME_REQUEST_TMP" "$FRAME_REQUEST" \
        || { echo "$context: could not publish client-frame nonce" >&2; return 1; }
    FRAME_REQUEST_TMP=""
    timeout="${HSQA_CLIENT_FRAME_TIMEOUT:-45}"
    deadline=$((SECONDS + timeout))
    while [ "$SECONDS" -lt "$deadline" ]; do
        line=$(tail -n +"$((anchor + 1))" "$EV_LOGS/playtest-client.log" 2>/dev/null \
            | grep -F "HSQA_FRAME_ACK nonce=$nonce " | tail -1)
        if [ -n "$line" ]; then
            rm -f -- "$FRAME_REQUEST"
            if [[ "$line" != *" auxButtonBound=none "* ]]; then
                echo "$context: auxiliary X button has a runtime KeyMapping: $line" >&2
                return 1
            fi
            if [ "$expected_screen" != any ] \
                && [[ "$line" != *" screen=$expected_screen "* ]]; then
                echo "$context: unexpected client screen: $line" >&2
                return 1
            fi
            if [ "$expected_grabbed" != any ] \
                && [[ "$line" != *" grabbed=$expected_grabbed "* ]]; then
                echo "$context: unexpected mouse-grab state: $line" >&2
                return 1
            fi
            FRAME_ACK_LINE="$line"
            return 0
        fi
        if tail -n +"$((anchor + 1))" "$EV_LOGS/playtest-client.log" 2>/dev/null \
            | grep -Fq 'HSQA_FRAME_ERROR'; then
            rm -f -- "$FRAME_REQUEST"
            echo "$context: client observer reported an error" >&2
            return 1
        fi
        kill -0 "$GRADLE_PID" 2>/dev/null || {
            rm -f -- "$FRAME_REQUEST"
            echo "$context: client exited while waiting for its frame ack" >&2
            return 1
        }
        sleep 0.10
    done
    rm -f -- "$FRAME_REQUEST"
    echo "$context: no post-input client frame ack within ${timeout}s" >&2
    shot "FAILED-client-frame" 2>/dev/null || true
    echo "--- client log tail ---" >&2
    tail -12 "$EV_LOGS/playtest-client.log" >&2 2>/dev/null || true
    return 1
}

safe_regrab() {
    local focused original after post_error="" click_error=""
    local original_yaw original_pitch after_yaw after_pitch
    local flush_yaw flush_pitch
    wait_client_frame null any "safe_regrab precondition" \
        || die "${DIR_IDX:-setup}:safe_regrab_precondition" \
            "client is not in a verified in-world state before regrab"
    original=$(fresh_rotation) \
        || die "${DIR_IDX:-setup}:safe_regrab_pose" \
            "could not capture the complete pre-regrab rotation"
    read -r original_yaw original_pitch <<< "$original"
    focus || die "${DIR_IDX:-setup}:safe_regrab_focus" \
        "Minecraft window could not be focused before the auxiliary click"
    focused=$(xdotool getwindowfocus 2>/dev/null) \
        || die "${DIR_IDX:-setup}:safe_regrab_focus" \
            "xdotool could not read the focused window"
    [ "$focused" = "$WIN" ] \
        || die "${DIR_IDX:-setup}:safe_regrab_focus" \
            "focused window $focused does not match Minecraft window $WIN"
    # The observer turns the old comment-only screen contract into a measured
    # precondition. Open PauseScreen, prove mouse release, send the physical
    # auxiliary click ONLY while that screen is authoritatively open, then
    # close and prove screen=null + grabbed=true. No button press touches the
    # world, even if a future dependency binds the auxiliary mouse key.
    xdotool key --clearmodifiers Escape \
        || die "${DIR_IDX:-setup}:safe_regrab_release" \
            "xdotool could not open the release screen"
    wait_client_frame PauseScreen false "safe_regrab release" \
        || die "${DIR_IDX:-setup}:safe_regrab_release" \
            "PauseScreen/mouse release was not observed"
    xdotool click 8 || click_error="xdotool rejected auxiliary X button 8"
    wait_client_frame PauseScreen false "safe_regrab paused click" \
        || die "${DIR_IDX:-setup}:safe_regrab_click" \
            "auxiliary click was not consumed safely inside PauseScreen"
    xdotool key --clearmodifiers Escape \
        || die "${DIR_IDX:-setup}:safe_regrab_restore" \
            "xdotool could not close the release screen"
    wait_client_frame null true "safe_regrab restore" \
        || die "${DIR_IDX:-setup}:safe_regrab_restore" \
            "screen close did not produce a verified in-world mouse grab"
    [ -z "$click_error" ] \
        || die "${DIR_IDX:-setup}:safe_regrab_click" "$click_error"
    # grabMouse() and cursorEntered() set ignoreFirstMove=true. Consume that
    # one intentionally ignored event here; interpreting it as a dead grab
    # and clicking again would reset ignoreFirstMove forever.
    if ! xdotool mousemove_relative -- 1 0; then
        post_error="xdotool rejected the sacrificial cursor event"
    fi
    # A post-input frame ack drains that event before we inspect the
    # authoritative rotation. If ignoreFirstMove consumed it, there is
    # nothing to undo. If GLFW had already consumed ignoreFirstMove, +1 is a
    # real ~0.15-degree yaw input and must be explicitly reversed. Capturing
    # the pose BEFORE every pointer event keeps standalone regrabs (startup,
    # cmd and open) from accumulating invisible camera drift.
    if ! wait_client_frame null true "safe_regrab cursor flush"; then
        post_error="${post_error:+$post_error; }client did not acknowledge the sacrificial cursor frame"
    elif after=$(fresh_rotation); then
        if [ -z "$post_error" ]; then
            read -r after_yaw after_pitch <<< "$after"
            flush_yaw=$(signed_yaw_delta "$original_yaw" "$after_yaw")
            flush_pitch=$(signed_pitch_delta "$original_pitch" "$after_pitch")
            if ! awk -v y="$flush_yaw" -v p="$flush_pitch" 'BEGIN {
                if (p<0) p=-p;
                yaw_ok=(y>=-0.02 && y<=0.02) || (y>0.05 && y<=0.40);
                exit !(yaw_ok && p<=0.02)
            }'; then
                post_error="sacrificial +1 changed rotation unexpectedly: $original -> $after"
            fi
        fi
    else
        post_error="${post_error:+$post_error; }could not inspect rotation after the sacrificial cursor event"
    fi

    # Common finally path: after the first synthetic movement, no success or
    # error path may leave until the complete captured rotation is restored.
    # restore_rotation_exact first uses measured inverse X input and reserves
    # a same-position server rotation for exceptional cleanup only.
    if ! restore_rotation_exact "$original_yaw" "$original_pitch"; then
        die "${DIR_IDX:-setup}:safe_regrab_restore" \
            "could not restore the complete pre-regrab rotation after: ${post_error:-normal flush}"
    fi
    if [ "${ROTATION_FALLBACK_USED:-0}" = 1 ]; then
        post_error="${post_error:+$post_error; }rotation cleanup required the exceptional server fallback"
    fi
    if [ -n "$post_error" ]; then
        die "${DIR_IDX:-setup}:safe_regrab_flush" \
            "$post_error (complete rotation cleanup verified)"
    fi
}

# KF-035 already made live.sh's long-form driver self-verifying, but the
# release playtest still used two blind regrab+move attempts and marked the
# `move` directive PASS without observing any motion. Read each probe from a
# fresh, anchored server-log slice, then bounded-retry the proven regrab path.
# The last regrab is always followed by one final check; otherwise a recovery
# on the final allowed attempt would be reported as a false failure.
fresh_rotation() { # -> "yaw pitch" from one fresh, player-specific reply
    local anchor deadline rot yaw pitch
    anchor=$(wc -l < "$SRV_LOG" 2>/dev/null || echo 0)
    scmd "data get entity $PLAYER Rotation"
    deadline=$((SECONDS + ${HSQA_ROTATION_QUERY_TIMEOUT:-8}))
    while [ "$SECONDS" -lt "$deadline" ]; do
        rot=$(tail -n +"$((anchor + 1))" "$SRV_LOG" 2>/dev/null \
            | grep -oP "$PLAYER has the following entity data: \[\K[-0-9.]+f, [-0-9.]+f" \
            | tail -1)
        if [ -n "$rot" ]; then
            yaw=$(echo "$rot" | cut -d',' -f1 | tr -d 'f ')
            pitch=$(echo "$rot" | cut -d',' -f2 | tr -d 'f ')
            printf '%s %s\n' "$yaw" "$pitch"
            return 0
        fi
        sleep 0.2
    done
    return 1
}

fresh_yaw() {
    local rotation
    rotation=$(fresh_rotation) || return 1
    echo "${rotation%% *}"
}

yaw_delta() { # <yaw a> <yaw b>
    awk -v a="$1" -v b="$2" 'BEGIN {
        d=a-b; if (d<0) d=-d; while (d>=360) d-=360; if (d>180) d=360-d;
        printf "%.6f", d
    }'
}

signed_yaw_delta() { # <yaw before> <yaw after>, normalized to (-180, 180]
    awk -v a="$1" -v b="$2" 'BEGIN {
        d=b-a; while (d<=-180) d+=360; while (d>180) d-=360;
        printf "%.6f", d
    }'
}

signed_pitch_delta() { # <pitch before> <pitch after>
    awk -v a="$1" -v b="$2" 'BEGIN { printf "%.6f", b-a }'
}

pitch_delta() { # <pitch a> <pitch b>
    awk -v a="$1" -v b="$2" 'BEGIN {
        d=a-b; if (d<0) d=-d; printf "%.6f", d
    }'
}

wait_changed_yaw() { # <baseline yaw> -> first fresh "yaw pitch" >0.5 yaw away
    local baseline="$1" current current_yaw current_pitch delta
    local deadline=$((SECONDS + ${HSQA_INPUT_RESPONSE_TIMEOUT:-20}))
    while [ "$SECONDS" -lt "$deadline" ]; do
        current=$(fresh_rotation) || return 1
        read -r current_yaw current_pitch <<< "$current"
        delta=$(yaw_delta "$baseline" "$current_yaw")
        if awk -v d="$delta" 'BEGIN { exit !(d > 0.5) }'; then
            echo "$current"
            return 0
        fi
        sleep 0.25
    done
    return 1
}

wait_restored_rotation() { # <target yaw> <target pitch> -> complete rotation
    local target_yaw="$1" target_pitch="$2"
    local current current_yaw current_pitch dyaw dpitch
    local deadline=$((SECONDS + ${HSQA_INPUT_RESPONSE_TIMEOUT:-20}))
    while [ "$SECONDS" -lt "$deadline" ]; do
        current=$(fresh_rotation) || return 1
        read -r current_yaw current_pitch <<< "$current"
        dyaw=$(yaw_delta "$target_yaw" "$current_yaw")
        dpitch=$(pitch_delta "$target_pitch" "$current_pitch")
        if awk -v y="$dyaw" -v p="$dpitch" \
            'BEGIN { exit !(y<=0.02 && p<=0.02) }'; then
            echo "$current"
            return 0
        fi
        sleep 0.25
    done
    return 1
}

ROTATION_FALLBACK_USED=0
restore_rotation_exact() { # <target yaw> <target pitch>
    local target_yaw="$1" target_pitch="$2"
    local current current_yaw current_pitch correction_yaw correction_pitch
    local dx dy restored
    ROTATION_FALLBACK_USED=0

    current=$(fresh_rotation) || current=""
    if [ -n "$current" ]; then
        read -r current_yaw current_pitch <<< "$current"
        if awk -v y="$(yaw_delta "$target_yaw" "$current_yaw")" \
               -v p="$(pitch_delta "$target_pitch" "$current_pitch")" \
               'BEGIN { exit !(y<=0.02 && p<=0.02) }'; then
            return 0
        fi

        correction_yaw=$(signed_yaw_delta "$current_yaw" "$target_yaw")
        correction_pitch=$(signed_pitch_delta "$current_pitch" "$target_pitch")
        dx=$(awk -v d="$correction_yaw" \
            'BEGIN { v=d/0.15; printf "%d", v<0 ? int(v-0.5) : int(v+0.5) }')
        dy=$(awk -v d="$correction_pitch" \
            'BEGIN { v=d/0.15; printf "%d", v<0 ? int(v-0.5) : int(v+0.5) }')
        if { [ "$dx" -ne 0 ] || [ "$dy" -ne 0 ]; } \
            && focus \
            && xdotool mousemove_relative -- "$dx" "$dy" \
            && wait_client_frame null true "rotation rollback"; then
            restored=$(wait_restored_rotation "$target_yaw" "$target_pitch") \
                && return 0
        fi
    fi

    # Exceptional cleanup fallback. The release playtest's controlled player
    # is always unmounted with its own camera; same-position tp therefore has
    # no additional state to discard. Normal successful recovery never uses
    # this path. It exists so even an XTEST/focus failure cannot return a
    # half-applied probe to the scenario.
    ROTATION_FALLBACK_USED=1
    scmd "execute as $PLAYER at @s run tp @s ~ ~ ~ $target_yaw $target_pitch"
    restored=$(wait_restored_rotation "$target_yaw" "$target_pitch") \
        || return 1
    return 0
}

wait_move_rotation() { # <yaw0> <pitch0> <expected yaw> <expected pitch> <yaw tol> <pitch tol>
    local yaw0="$1" pitch0="$2" expected_yaw="$3" expected_pitch="$4"
    local tolerance_yaw="$5" tolerance_pitch="$6"
    local current yaw pitch observed_yaw observed_pitch
    local deadline=$((SECONDS + ${HSQA_INPUT_RESPONSE_TIMEOUT:-20}))
    while [ "$SECONDS" -lt "$deadline" ]; do
        current=$(fresh_rotation) || return 1
        read -r yaw pitch <<< "$current"
        observed_yaw=$(signed_yaw_delta "$yaw0" "$yaw")
        observed_pitch=$(signed_pitch_delta "$pitch0" "$pitch")
        if awk -v ey="$expected_yaw" -v ep="$expected_pitch" \
               -v ty="$tolerance_yaw" -v tp="$tolerance_pitch" \
               -v oy="$observed_yaw" -v op="$observed_pitch" 'BEGIN {
            dy=oy-ey; if (dy<0) dy=-dy;
            dp=op-ep; if (dp<0) dp=-dp;
            exit !(dy<=ty && dp<=tp)
        }'; then
            echo "$current"
            return 0
        fi
        sleep 0.25
    done
    return 1
}

ensure_grab() {
    local rotation0 rotation1 rotation2 probe_error
    local yaw0 pitch0 yaw1 pitch1
    local probe_yaw probe_pitch attempt=1
    local probe_observed
    # One bounded recovery after the initial check. More blind recovery
    # rounds would increase the chance of stale X events leaking into the
    # asserted action; a verified release/grab cycle either works or this is
    # a real wall worth surfacing.
    local max="${HSQA_ENSURE_GRAB_ATTEMPTS:-1}"
    while :; do
        focus || die "${DIR_IDX:-setup}:input_focus" \
            "Minecraft window could not be focused for the input probe"
        rotation0=$(fresh_rotation) \
            || die "${DIR_IDX:-setup}:input_probe" \
                "could not read fresh pre-probe rotation"
        read -r yaw0 pitch0 <<< "$rotation0"
        probe_error=""
        probe_observed=0
        if ! xdotool mousemove_relative -- 40 0; then
            probe_error="xdotool rejected the positive yaw probe"
        fi
        # Unlike the retired chat fence, this ack is emitted only after the
        # X event has crossed GLFW pollEvents and a later
        # handleAccumulatedMovement frame. A timed-out probe cannot leak into
        # the next baseline or asserted movement.
        if ! wait_client_frame null true "input probe"; then
            probe_error="${probe_error:+$probe_error; }client did not acknowledge the post-probe input frame"
        elif [ -z "$probe_error" ] && rotation1=$(wait_changed_yaw "$yaw0"); then
            probe_observed=1
            read -r yaw1 pitch1 <<< "$rotation1"
            probe_yaw=$(signed_yaw_delta "$yaw0" "$yaw1")
            probe_pitch=$(signed_pitch_delta "$pitch0" "$pitch1")
        fi

        # Common finally path for every branch after +40, including xdotool,
        # liveness, shape and timeout failures. No caller sees a half-applied
        # probe. Exceptional X/focus failures are cleaned by the controlled
        # same-position server fallback inside restore_rotation_exact.
        if ! restore_rotation_exact "$yaw0" "$pitch0"; then
            check_fail "${DIR_IDX:-setup}:input_probe_cleanup" \
                "could not restore complete pre-probe rotation after ${probe_error:-probe}"
            return 1
        fi
        if [ "${ROTATION_FALLBACK_USED:-0}" = 1 ]; then
            probe_error="${probe_error:+$probe_error; }rotation cleanup required the exceptional server fallback"
        fi
        rotation2=$(fresh_rotation) || rotation2="$rotation0"

        if [ -n "$probe_error" ]; then
            check_fail "${DIR_IDX:-setup}:input_probe_late" \
                "$probe_error; complete rotation cleanup verified"
            return 1
        fi
        if [ "$probe_observed" -eq 1 ]; then
            if ! awk -v y="$probe_yaw" -v p="$probe_pitch" 'BEGIN {
                if (p<0) p=-p; exit !(y>=4.5 && y<=7.5 && p<=0.02)
            }'; then
                check_fail "${DIR_IDX:-setup}:input_probe_shape" \
                    "horizontal +40 probe changed rotation unexpectedly: $rotation0 -> $rotation1; cleanup $rotation2"
                return 1
            fi
            if [ "$attempt" -gt 1 ]; then
                check_pass "${DIR_IDX:-setup}:input_regrab" \
                    "recovered after $attempt checks (probe $rotation0 -> $rotation1; undo $rotation2)"
            fi
            return 0
        fi
        if [ "$attempt" -ge "$((max + 1))" ]; then
            check_fail "${DIR_IDX:-setup}:input_dead" \
                "grab did not recover after $max regrab attempts"
            return 1
        fi
        echo "ensure_grab: check $attempt saw no rotation from $rotation0; regrabbing $attempt/$max" >&2
        safe_regrab
        attempt=$((attempt + 1))
    done
}

# Establishes the grab somewhere harmless first, then makes the LAST camera
# operation an absolute server teleport whose rotation is calculated from the
# player's EYE anchor to the exact centre of the block being interacted with.
# A plain `tp ... facing x y z` calculates from the feet: the first live proof
# produced pitch -36.869637 and visibly aimed above the wall even though the
# position was correct. `execute ... anchored eyes facing ... run tp` computes
# the client-ray rotation from the right origin, while the absolute destination
# passed to the inner tp keeps the feet at the frozen stand point.
# Restoring the position/rotation
# captured before regrab is not sufficient for precise world interaction:
# a real failed run restored the player to 299.247/-60/305.225 while the
# frozen stand point was 300.5/-60/304.5, leaving the plaque far left of the
# reticle. The second identical tp absorbs late client movement packets from
# the first large round trip. Pos/Rotation and an aim screenshot are captured
# immediately before the one physical click, so a miss is diagnosable as
# reticle/position/input rather than inferred from downstream state.
OPEN_AT_POS=""
OPEN_AT_ROT=""
open_at() { # stand-x stand-y stand-z target-x target-y target-z button label [expected-block-x,y,z]
    local sx="$1" sy="$2" sz="$3" tx="$4" ty="$5" tz="$6"
    local button="${7:-right}" label="${8:-open-at}"
    local expected_hit="${9:-}" button_num=3 pose_anchor
    [ "$button" = "left" ] && button_num=1

    focus
    safe_regrab
    scmd "tp $PLAYER $sx $sy $sz 0 0"
    sleep 2
    scmd "execute as $PLAYER at $PLAYER anchored eyes facing $tx $ty $tz run tp @s $sx $sy $sz ~ ~"
    sleep 3
    # Reassert once after the client has received the large position change;
    # this absorbs a late movement packet without changing the calculated aim.
    scmd "execute as $PLAYER at $PLAYER anchored eyes facing $tx $ty $tz run tp @s $sx $sy $sz ~ ~"
    sleep 1

    pose_anchor=$(wc -l < "$SRV_LOG" 2>/dev/null || echo 0)
    scmd "data get entity $PLAYER Pos"
    sleep 1
    OPEN_AT_POS=$(tail -n +"$((pose_anchor + 1))" "$SRV_LOG" 2>/dev/null \
        | grep -F "$PLAYER has the following entity data:" | tail -1)
    scmd "data get entity $PLAYER Rotation"
    sleep 1
    OPEN_AT_ROT=$(tail -n +"$((pose_anchor + 1))" "$SRV_LOG" 2>/dev/null \
        | grep -F "$PLAYER has the following entity data:" | tail -1)

    # A correct server rotation is not enough: a real entity can still stand
    # in the ray. Sample the client's actual pick result after the aim has
    # settled and before sending the one physical world click.
    wait_client_frame null true "open_at aim $label" \
        || die "${DIR_IDX:-setup}:open_at_aim" \
            "client did not acknowledge the final $label aim"
    if [ -n "$expected_hit" ] \
        && [[ "$FRAME_ACK_LINE" != *" hitType=block hitBlock=$expected_hit "* ]]; then
        die "${DIR_IDX:-setup}:open_at_hit" \
            "client pick ray did not hit expected block $expected_hit: $FRAME_ACK_LINE"
    fi

    focus || die "${DIR_IDX:-setup}:open_at_focus" \
        "Minecraft window could not be focused before $label click"
    shot "$label"
    xdotool mousemove --sync "$CENTER_X" "$CENTER_Y" \
        || die "${DIR_IDX:-setup}:open_at_pointer" \
            "could not position pointer before $label click"
    xdotool click "$button_num" \
        || die "${DIR_IDX:-setup}:open_at_click" \
            "physical $button click was rejected for $label"
    sleep 3
}
shot() { # <name>
    focus
    if [ -n "$WIN" ]; then
        import -window "$WIN" "$EV_SHOTS/$1.png" 2>/dev/null || import -window root "$EV_SHOTS/$1.png" 2>/dev/null || true
    else
        import -window root "$EV_SHOTS/$1.png" 2>/dev/null || true
    fi
}

shot playtest-00-title

# quickPlay drops straight into the world without a menu click. Establish
# the initial grab through the same non-world-mutating auxiliary-button path
# every later recovery uses; never spend a left click merely to gain focus.
safe_regrab

# options.txt records only the REQUEST. Minecraft clamps GUI scale when the
# resulting virtual canvas would be too small (e.g. requested scale 4 at
# 1280x720 becomes effective scale 3). The client-frame observer reports the
# actual render values; a visual gate must never label a clamped screenshot as
# proof of a scale it did not render.
ACTUAL_GUI_SCALE=$(printf '%s\n' "$FRAME_ACK_LINE" \
    | sed -n 's/.* guiScale=\([^ ]*\).*/\1/p')
ACTUAL_GUI_SIZE=$(printf '%s\n' "$FRAME_ACK_LINE" \
    | sed -n 's/.* gui=\([^ ]*\).*/\1/p')
ACTUAL_FRAMEBUFFER=$(printf '%s\n' "$FRAME_ACK_LINE" \
    | sed -n 's/.* framebuffer=\([^ ]*\).*/\1/p')
ACTUAL_LANGUAGE=$(printf '%s\n' "$FRAME_ACK_LINE" \
    | sed -n 's/.* language=\([^ ]*\).*/\1/p')
BLESSING_TITLE_RESOLVED=$(printf '%s\n' "$FRAME_ACK_LINE" \
    | sed -n 's/.* blessingTitleResolved=\([^ ]*\).*/\1/p')
BLESSING_TITLE_LANGUAGE_MATCH=$(printf '%s\n' "$FRAME_ACK_LINE" \
    | sed -n 's/.* blessingTitleLanguageMatch=\([^ ]*\).*/\1/p')
if [ -z "$ACTUAL_GUI_SCALE" ] || [ -z "$ACTUAL_GUI_SIZE" ] \
    || [ -z "$ACTUAL_FRAMEBUFFER" ] || [ -z "$ACTUAL_LANGUAGE" ] \
    || [ -z "$BLESSING_TITLE_RESOLVED" ] \
    || [ -z "$BLESSING_TITLE_LANGUAGE_MATCH" ]; then
    die display_profile "client frame ack omitted effective GUI/display/language values: $FRAME_ACK_LINE"
fi
if ! awk -v actual="$ACTUAL_GUI_SCALE" -v expected="$GUI_SCALE" \
        'BEGIN { exit !(actual == expected) }'; then
    die display_profile \
        "requested guiScale $GUI_SCALE was clamped to $ACTUAL_GUI_SCALE at ${DISPLAY_WIDTH}x${DISPLAY_HEIGHT} (effective GUI $ACTUAL_GUI_SIZE)"
fi
if [ "$ACTUAL_FRAMEBUFFER" != "${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}" ]; then
    die display_profile \
        "requested framebuffer ${DISPLAY_WIDTH}x${DISPLAY_HEIGHT}, client rendered $ACTUAL_FRAMEBUFFER"
fi
if [ "$ACTUAL_LANGUAGE" != "$CLIENT_LANGUAGE" ]; then
    die display_profile \
        "requested language $CLIENT_LANGUAGE, client selected $ACTUAL_LANGUAGE"
fi
if [ "$BLESSING_TITLE_RESOLVED" != true ] \
    || [ "$BLESSING_TITLE_LANGUAGE_MATCH" != true ]; then
    die display_profile \
        "language $ACTUAL_LANGUAGE did not resolve the expected Hearthstead translation"
fi
check_pass display_profile \
    "effective guiScale $ACTUAL_GUI_SCALE, GUI $ACTUAL_GUI_SIZE, framebuffer $ACTUAL_FRAMEBUFFER, language $ACTUAL_LANGUAGE with translated Blessing title"

# AC-14: every directive gets a recorded outcome, not just expect_* ones —
# a typo'd or silently-no-op directive must be visible in result.json, not
# just an "unknown directive" line nobody greps. DIR_IDX makes each entry's
# check name unique (the same verb can appear many times in one scenario).
#
# CAPTURED_VARS backs `capture_pos`: a scenario can freeze the player's
# CURRENT position (before any regrab-teleport churn has a chance to drift
# it) into named integer coordinates, then reference them later by name
# (e.g. `$PLAQUE_X`) instead of a live `~`-relative offset. See capture_pos
# below for why this exists.
declare -A CAPTURED_VARS
# The most recent move's own server-observed bracket. `ensure_grab` performs
# extra yaw probes by design, so expect_rotation_change must not infer its
# bracket from "the last two Rotation lines" after those probes were added.
LAST_MOVE_YAW_BEFORE=""
LAST_MOVE_YAW_AFTER=""
# BLOCKER_GATE (2026-08-24): `expect_server` used to grep the WHOLE
# cumulative log every time, so it could be satisfied by a line written
# minutes earlier in the same run rather than anything the directive right
# before it actually caused -- a false-PASS risk symmetric to the
# false-FAIL the stale-jar check above just closed. LOG_ANCHOR is the log's
# line count right before the most recent action-producing directive;
# `expect_server` only searches what was appended after it.
LOG_ANCHOR=0
DIR_IDX=0
while read -r verb rest; do
    rest="${rest//\$PLAYER/$PLAYER}"
    for _cv in "${!CAPTURED_VARS[@]}"; do
        rest="${rest//\$$_cv/${CAPTURED_VARS[$_cv]}}"
    done
    DIR_IDX=$((DIR_IDX + 1))
    case "${verb:-}" in
        cmd|scmd|click|sneak_click|click_at|open_near_block|insert_plan_at|move|key|type) LOG_ANCHOR=$(wc -l < "$SRV_LOG" 2>/dev/null || echo 0);;
    esac
    case "${verb:-}" in
        # '#'* not '#': only a bare '#' matched before, so a comment written
        # without a space after the hash ("#like this") fell through to the
        # unknown-directive die() added by finding 8 — a hard scenario FAIL
        # for a comment. No scenario uses that form today, which is exactly
        # why it would have gone unnoticed until someone wrote one.
        ''|'#'*) continue;;
        wait)  sleep "$rest"; check_pass "$DIR_IDX:wait" "slept ${rest}s";;
        key)   focus; xdotool key --clearmodifiers $rest; sleep 1
               check_pass "$DIR_IDX:key" "sent key: $rest";;
        type)  focus; xdotool type --delay 40 -- "$rest"; sleep 1
               check_pass "$DIR_IDX:type" "typed: $rest";;
        cmd)   focus; _kf=$?
               # Exit codes captured, not discarded (a "promising unexplored
               # direction" from the original KF-009 investigation). Added
               # while chasing what turned out to be a false failure: the
               # PLAQUE-1 section's final `hearthstead info` check kept
               # failing identically across five runs (113853Z-122434Z)
               # despite fixing two real, unrelated bugs, and this
               # instrumentation came back clean both times it ran -- the
               # BLOCKER_GATE that resolved it (2026-08-24) found the actual
               # cause was the dedicated server running a stale build/libs
               # jar that predated those fixes (see the freshness check
               # above JAR's selection), nothing about xdotool or focus()
               # delivery. Kept anyway: real, cheap, defensive visibility
               # for whatever the next thing that looks like this turns out
               # to actually be.
               _cmd_lines_before=$(wc -l < "$SRV_LOG" 2>/dev/null || echo 0)
               xdotool key --clearmodifiers t; _kt=$?; sleep 1
               xdotool type --delay 35 -- "/$rest"; _kty=$?; sleep 1
               xdotool key --clearmodifiers Return; _kr=$?; sleep 2
               if [ "$_kt" -ne 0 ] || [ "$_kty" -ne 0 ] || [ "$_kr" -ne 0 ]; then
                   echo "cmd: xdotool exit codes key-t=$_kt type=$_kty key-Return=$_kr (non-zero!)" >&2
               fi
               # If NOTHING reached the server, the chat key was swallowed --
               # essentially always because a screen was already open, and the
               # Game Menu is the one that does it. Proven live
               # (20260825T164216Z and two reruns): three identical failures
               # at the same directive, and shots/plaque-02-empty-clicked.png
               # shows the Game Menu sitting open while the command was typed
               # into it.
               #
               # The scenario's own "send it twice" mitigation cannot help,
               # because the second send hits the SAME stuck state. Escape is
               # what breaks the parity: it closes whatever is open, so the
               # retry starts from the in-world state chat actually needs.
               #
               # This changes no assertion. It only makes the keystroke
               # arrive; whether the command then does the right thing is
               # still entirely up to expect_server.
               _cmd_lines_after=$(wc -l < "$SRV_LOG" 2>/dev/null || echo 0)
               if [ "$_cmd_lines_after" -eq "$_cmd_lines_before" ]; then
                   echo "cmd: no server output for /$rest -- closing any open screen and retrying once" >&2
                   xdotool key --clearmodifiers Escape; sleep 1
                   xdotool key --clearmodifiers t; sleep 1
                   xdotool type --delay 35 -- "/$rest"; sleep 1
                   xdotool key --clearmodifiers Return; sleep 2
               fi
               # Opening chat releases the mouse grab (needed so chat text
               # can be clicked/selected); closing it does not reliably
               # re-establish relative-look capture on its own — proven
               # live: a `move`/`look` right after a `cmd` silently produced
               # zero rotation change until an explicit click. `safe_regrab`
               # (see its own comment, above the directive loop) restores
               # grab the same way every later move/look/click directive
               # needs, WITHOUT the crosshair's current target paying for it.
               safe_regrab
               check_pass "$DIR_IDX:cmd" "ran as player: /$rest";;
        click) focus
               xdotool mousemove "$CENTER_X" "$CENTER_Y"
               xdotool click "$([ "${rest:-left}" = right ] && echo 3 || echo 1)"; sleep 2
               check_pass "$DIR_IDX:click" "clicked ${rest:-left}";;
        sneak_click)
               focus || die "$DIR_IDX:sneak_click" "Minecraft window could not be focused"
               safe_regrab
               xdotool mousemove "$CENTER_X" "$CENTER_Y" \
                   || die "$DIR_IDX:sneak_click" "could not centre the pointer"
               _sc_down=0; _sc_click=0; _sc_up=0
               xdotool keydown Shift_L || _sc_down=$?
               sleep 0.10
               xdotool click "$([ "${rest:-left}" = right ] && echo 3 || echo 1)" \
                   || _sc_click=$?
               sleep 0.20
               # Always release the modifier, including after a rejected click.
               xdotool keyup Shift_L || _sc_up=$?
               if [ "$_sc_down" -ne 0 ] || [ "$_sc_click" -ne 0 ] \
                   || [ "$_sc_up" -ne 0 ]; then
                   die "$DIR_IDX:sneak_click" \
                       "xdotool failed: down=$_sc_down click=$_sc_click up=$_sc_up"
               fi
               check_pass "$DIR_IDX:sneak_click" \
                   "sneak-clicked ${rest:-left} and released Shift";;
        click_at)
               set -- $rest
               [ "$#" -eq 2 ] \
                   || die "$DIR_IDX:click_at" "expected x-percent y-percent; got: $rest"
               awk -v x="$1" -v y="$2" \
                   'BEGIN { exit !(x>0 && x<100 && y>0 && y<100) }' \
                   || die "$DIR_IDX:click_at" "percentages must be inside 0..100: $rest"
               focus \
                   || die "$DIR_IDX:click_at" "Minecraft window could not be focused"
               _ca_geom=$(xdotool getwindowgeometry --shell "$WIN" 2>/dev/null) \
                   || die "$DIR_IDX:click_at" "could not read Minecraft window geometry"
               _ca_x=$(printf '%s\n' "$_ca_geom" | sed -n 's/^X=//p')
               _ca_y=$(printf '%s\n' "$_ca_geom" | sed -n 's/^Y=//p')
               _ca_w=$(printf '%s\n' "$_ca_geom" | sed -n 's/^WIDTH=//p')
               _ca_h=$(printf '%s\n' "$_ca_geom" | sed -n 's/^HEIGHT=//p')
               awk -v x="$_ca_x" -v y="$_ca_y" -v w="$_ca_w" -v h="$_ca_h" \
                   'BEGIN { exit !(x ~ /^-?[0-9]+$/ && y ~ /^-?[0-9]+$/ \
                       && w ~ /^[1-9][0-9]*$/ && h ~ /^[1-9][0-9]*$/) }' \
                   || die "$DIR_IDX:click_at" \
                       "invalid Minecraft window geometry: $_ca_geom"
               _ca_px=$(awk -v x="$_ca_x" -v w="$_ca_w" -v p="$1" \
                   'BEGIN { printf "%d", x+w*p/100 }')
               _ca_py=$(awk -v y="$_ca_y" -v h="$_ca_h" -v p="$2" \
                   'BEGIN { printf "%d", y+h*p/100 }')
               xdotool mousemove --sync "$_ca_px" "$_ca_py" \
                   || die "$DIR_IDX:click_at" "could not position pointer at $rest"
               xdotool click 1 \
                   || die "$DIR_IDX:click_at" "left click was rejected at $rest"
               sleep 2
               check_pass "$DIR_IDX:click_at" \
                   "left-clicked window position $rest (pixel $_ca_px,$_ca_py)";;
        open)  # The world-interact twin of `click`, for the FIRST click that
               # opens a screen -- a plaque, a chest, a settler's sheet. It
               # regrabs first, the same way `move` does and for the same
               # reason: a grab established several directives ago does not
               # reliably survive, and a click into a dead grab is a silent
               # no-op. On 2026-08-26 the plaque insert failed twice in a row
               # that way, with all THREE of the scenario's retries clicking
               # into the same dead grab -- retrying does not revive it, which
               # is the whole point of regrabbing instead (KF-035).
               #
               # Deliberately separate from `click` rather than folded into
               # it: `click` is also used INSIDE an already-open screen
               # (crafting slots, inventory), where safe_regrab's own click
               # would land on the GUI instead of the world. Never use `open`
               # there, and never use `click` for the first interact.
               focus
               safe_regrab
               xdotool mousemove "$CENTER_X" "$CENTER_Y"
               xdotool click "$([ "${rest:-left}" = right ] && echo 3 || echo 1)"; sleep 2
               check_pass "$DIR_IDX:open" "opened (${rest:-left} click, after regrab)";;
        open_near_block)
               set -- $rest
               if [ "$#" -ne 4 ] || [[ ! "$1" =~ ^[a-z0-9_.-]+:[a-z0-9_./-]+$ ]] \
                   || [[ ! "$2" =~ ^[1-9][0-9]*$ ]] \
                   || { [ "$3" != right ] && [ "$3" != left ]; } \
                   || [[ ! "$4" =~ ^[A-Za-z0-9_.-]+$ ]]; then
                   die "$DIR_IDX:open_near_block" \
                       "expected <namespaced-block> <positive-radius> <left|right> <label>; got: $rest"
               fi
               _onb_block="$1"; _onb_radius="$2"; _onb_button="$3"; _onb_label="$4"
               if [ "$_onb_radius" -gt 8 ]; then
                   die "$DIR_IDX:open_near_block" \
                       "radius $_onb_radius exceeds the hard safety bound of 8"
               fi

               # Founding spawns three physical settlers that may push the
               # player sideways. The old cached camera direction could then
               # miss the Hearth or hit a settler. Find the exact nearby
               # block through server-authoritative probes; open_at still
               # performs the one real client click used by the scenario.
               _onb_pos_anchor=$(wc -l < "$SRV_LOG" 2>/dev/null || echo 0)
               scmd "data get entity $PLAYER Pos"
               _onb_pos=$(tail -n +"$((_onb_pos_anchor + 1))" "$SRV_LOG" 2>/dev/null \
                   | grep -oP "$PLAYER has the following entity data: \[\K[-0-9.]+d, [-0-9.]+d, [-0-9.]+d" \
                   | tail -1)
               _onb_px=$(echo "$_onb_pos" | cut -d',' -f1 | tr -d 'd ')
               _onb_py=$(echo "$_onb_pos" | cut -d',' -f2 | tr -d 'd ')
               _onb_pz=$(echo "$_onb_pos" | cut -d',' -f3 | tr -d 'd ')
               if [ -z "$_onb_px" ] || [ -z "$_onb_py" ] || [ -z "$_onb_pz" ]; then
                   die "$DIR_IDX:open_near_block" \
                       "could not read $PLAYER position before finding $_onb_block"
               fi

               _onb_nonce="${ROLE}_${DIR_IDX}_${RANDOM}"
               _onb_anchor=$(wc -l < "$SRV_LOG" 2>/dev/null || echo 0)
               for _onb_dx in $(seq "-${_onb_radius}" "$_onb_radius"); do
                   for _onb_dz in $(seq "-${_onb_radius}" "$_onb_radius"); do
                       scmd_now \
                           "execute at $PLAYER if block ~$_onb_dx ~ ~$_onb_dz $_onb_block run say HSQA_NEAR_BLOCK $_onb_nonce $_onb_dx $_onb_dz"
                   done
               done
               scmd_now "say HSQA_NEAR_BLOCK_DONE $_onb_nonce"
               _onb_deadline=$((SECONDS + 15))
               _onb_done=""
               while [ "$SECONDS" -lt "$_onb_deadline" ]; do
                   _onb_done=$(tail -n +"$((_onb_anchor + 1))" "$SRV_LOG" 2>/dev/null \
                       | grep -F "HSQA_NEAR_BLOCK_DONE $_onb_nonce" | tail -1)
                   [ -n "$_onb_done" ] && break
                   sleep 0.10
               done
               if [ -z "$_onb_done" ]; then
                   die "$DIR_IDX:open_near_block" \
                       "server did not finish the bounded $_onb_block scan"
               fi
               _onb_candidates=$(tail -n +"$((_onb_anchor + 1))" "$SRV_LOG" 2>/dev/null \
                   | grep -F "HSQA_NEAR_BLOCK $_onb_nonce " \
                   | sed -n "s/.*HSQA_NEAR_BLOCK $_onb_nonce \(-\{0,1\}[0-9][0-9]*\) \(-\{0,1\}[0-9][0-9]*\).*/\1 \2/p")
               read -r _onb_dx _onb_dz <<< "$(printf '%s\n' "$_onb_candidates" \
                   | awk 'NF == 2 {
                       distance = ($1 * $1) + ($2 * $2)
                       if (!found || distance < best) {
                           found = 1; best = distance; best_x = $1; best_z = $2
                       }
                   } END { if (found) print best_x, best_z }')"
               if [ -z "${_onb_dx:-}" ] || [ -z "${_onb_dz:-}" ]; then
                   die "$DIR_IDX:open_near_block" \
                       "server found no $_onb_block on player foot-Y within horizontal radius $_onb_radius"
               fi

               read -r _onb_bx _onb_by _onb_bz <<< "$(python3 - \
                   "$_onb_px" "$_onb_py" "$_onb_pz" "$_onb_dx" "$_onb_dz" <<'PYEOF'
import math
import sys
x, y, z = map(float, sys.argv[1:4])
dx, dz = map(int, sys.argv[4:6])
print(math.floor(x) + dx, math.floor(y), math.floor(z) + dz)
PYEOF
)"
               _onb_sx=$(python3 -c "print($_onb_bx + 0.5)")
               _onb_sy="$_onb_by"
               _onb_sz=$(python3 -c "print($_onb_bz - 3.5)")
               _onb_tx=$(python3 -c "print($_onb_bx + 0.5)")
               _onb_ty=$(python3 -c "print($_onb_by + 0.55)")
               _onb_tz=$(python3 -c "print($_onb_bz + 0.5)")
               open_at "$_onb_sx" "$_onb_sy" "$_onb_sz" \
                   "$_onb_tx" "$_onb_ty" "$_onb_tz" \
                   "$_onb_button" "$_onb_label" \
                   "$_onb_bx,$_onb_by,$_onb_bz"
               check_pass "$DIR_IDX:open_near_block" \
                   "found $_onb_block at $_onb_bx $_onb_by $_onb_bz and physically clicked it";;
        insert_plan_at)
               # This directive is intentionally state-aware instead of a
               # chain of blind clicks. Every attempt remains a real client
               # right-click, but another is sent only when the authoritative
               # plaque block data still lacks the plan. It also proves the
               # selected hotbar item before touching the world, separating a
               # bad slot from a bad reticle or a dropped input event.
               set -- $rest
               if [ "$#" -ne 9 ]; then
                   die "$DIR_IDX:insert_plan_at" "expected plaque xyz, stand xyz and target xyz; got: $rest"
               fi
               _ip_px="$1"; _ip_py="$2"; _ip_pz="$3"
               _ip_sx="$4"; _ip_sy="$5"; _ip_sz="$6"
               _ip_tx="$7"; _ip_ty="$8"; _ip_tz="$9"

               _ip_hotbar_anchor=$(wc -l < "$SRV_LOG" 2>/dev/null || echo 0)
               scmd "data get entity $PLAYER SelectedItemSlot"
               sleep 1
               _ip_slot=$(tail -n +"$((_ip_hotbar_anchor + 1))" "$SRV_LOG" 2>/dev/null \
                   | grep -F "$PLAYER has the following entity data:" | tail -1)
               scmd "data get entity $PLAYER SelectedItem"
               sleep 1
               _ip_item=$(tail -n +"$((_ip_hotbar_anchor + 1))" "$SRV_LOG" 2>/dev/null \
                   | grep -F "$PLAYER has the following entity data:" | tail -1)
               if ! echo "$_ip_item" | grep -Fq 'hearthstead:build_plan'; then
                   die "$DIR_IDX:insert_plan_hotbar" "selected item is not a Build Plan; slot=[$_ip_slot] item=[$_ip_item]"
               fi
               check_pass "$DIR_IDX:insert_plan_hotbar" "slot=[$_ip_slot] selected=hearthstead:build_plan"

               _ip_ok=0
               _ip_last_state=""
               _ip_attempt=0
               for _ip_try in 1 2 3; do
                   _ip_attempt="$_ip_try"
                   _ip_pre_anchor=$(wc -l < "$SRV_LOG" 2>/dev/null || echo 0)
                   scmd "data get block $_ip_px $_ip_py $_ip_pz"
                   sleep 1
                   _ip_last_state=$(tail -n +"$((_ip_pre_anchor + 1))" "$SRV_LOG" 2>/dev/null \
                       | grep -F "$_ip_px, $_ip_py, $_ip_pz has the following block data:" | tail -1)
                   if echo "$_ip_last_state" | grep -Fq 'hearthstead:build_plan'; then
                       _ip_ok=1
                       break
                   fi

                   open_at "$_ip_sx" "$_ip_sy" "$_ip_sz" \
                       "$_ip_tx" "$_ip_ty" "$_ip_tz" right \
                       "plaque-insert-attempt-${_ip_try}-aim" \
                       "$_ip_px,$_ip_py,$_ip_pz"
                   check_pass "$DIR_IDX:insert_plan_aim_${_ip_try}" \
                       "pos=[$OPEN_AT_POS] rotation=[$OPEN_AT_ROT] shot=plaque-insert-attempt-${_ip_try}-aim.png"

                   _ip_post_anchor=$(wc -l < "$SRV_LOG" 2>/dev/null || echo 0)
                   scmd "data get block $_ip_px $_ip_py $_ip_pz"
                   sleep 1
                   _ip_last_state=$(tail -n +"$((_ip_post_anchor + 1))" "$SRV_LOG" 2>/dev/null \
                       | grep -F "$_ip_px, $_ip_py, $_ip_pz has the following block data:" | tail -1)
                   if echo "$_ip_last_state" | grep -Fq 'hearthstead:build_plan'; then
                       _ip_ok=1
                       break
                   fi
                   echo "insert_plan_at: attempt $_ip_try left plaque unchanged; state=[$_ip_last_state]" >&2
               done
               if [ "$_ip_ok" -ne 1 ]; then
                   die "$DIR_IDX:insert_plan_at" \
                       "three aimed physical clicks failed; slot=[$_ip_slot] item=[$_ip_item] pos=[$OPEN_AT_POS] rotation=[$OPEN_AT_ROT] state=[$_ip_last_state]"
               fi
               check_pass "$DIR_IDX:insert_plan_at" \
                   "physical right-click inserted plan on attempt $_ip_attempt; pos=[$OPEN_AT_POS] rotation=[$OPEN_AT_ROT] state=[$_ip_last_state]";;
        move)  set -- $rest
               [ "$#" -eq 2 ] \
                   || die "$DIR_IDX:move" "expected dx dy; got: $rest"
               # Recovery retries belong only to setup. Once ensure_grab has
               # proven the channel, the asserted action is sent exactly once;
               # retrying the action itself until it passes would launder an
               # input flake into a false green result.
               ensure_grab \
                   || die "$DIR_IDX:move" \
                       "input grab stayed dead after bounded recovery"
               _mv_before=$(fresh_rotation) \
                   || die "$DIR_IDX:move" "could not read pre-move rotation"
               read -r _mv_yaw_before _mv_pitch_before <<< "$_mv_before"
               _mv_raw_yaw=$(awk -v dx="$1" 'BEGIN { printf "%.6f", dx*0.15 }')
               if ! awk -v e="$_mv_raw_yaw" \
                   'BEGIN { if (e<0) e=-e; exit !(e<180) }'; then
                   die "$DIR_IDX:move" \
                       "dx=$1 spans 180 degrees or more and cannot be proven from one final server rotation"
               fi
               _mv_expected_yaw="$_mv_raw_yaw"
               # A final server rotation cannot distinguish the full input
               # from an earlier partial input once pitch clamps at +/-90.
               # Reject such directives instead of claiming to prove them.
               _mv_target_pitch=$(awk -v p="$_mv_pitch_before" -v dy="$2" \
                   'BEGIN { printf "%.6f", p+(dy*0.15) }')
               if ! awk -v p="$_mv_target_pitch" \
                   'BEGIN { exit !(p>=-90 && p<=90) }'; then
                   die "$DIR_IDX:move" \
                       "dy=$2 would clamp pitch at +/-90 and cannot be fully proven from the final rotation"
               fi
               _mv_expected_pitch=$(awk -v p="$_mv_pitch_before" -v t="$_mv_target_pitch" \
                   'BEGIN { printf "%.6f", t-p }')
                if awk -v y="$_mv_expected_yaw" -v p="$_mv_expected_pitch" 'BEGIN {
                   if (y<0) y=-y; if (p<0) p=-p; exit !(y<=0.05 && p<=0.05)
               }'; then
                   die "$DIR_IDX:move" \
                        "movement is too small (or pitch is already clamped) to prove: $rest"
                fi
                # options.txt pins sensitivity to 0.5: vanilla's resulting
                # scale is 0.15 degrees per pixel on both axes. Compute the
                # final signed envelope before sending input so the polling
                # helper waits for the claimed conclusion, not merely the
                # first non-zero intermediate rotation.
                _mv_tolerance_yaw=$(awk -v e="$_mv_expected_yaw" 'BEGIN {
                    if (e<0) e=-e; t=e*0.20; if (t<0.20) t=0.20; printf "%.6f", t
                }')
                _mv_tolerance_pitch=$(awk -v e="$_mv_expected_pitch" 'BEGIN {
                    if (e<0) e=-e; t=e*0.20; if (t<0.20) t=0.20; printf "%.6f", t
                }')
                focus || die "$DIR_IDX:move" \
                    "Minecraft window lost focus before the asserted movement"
                xdotool mousemove_relative -- "$1" "$2" \
                    || die "$DIR_IDX:move" \
                        "xdotool rejected the asserted movement"
                wait_client_frame null true "asserted movement" \
                    || die "$DIR_IDX:move" \
                        "client did not acknowledge the asserted movement after a complete input frame"
                _mv_after=$(wait_move_rotation \
                    "$_mv_yaw_before" "$_mv_pitch_before" \
                    "$_mv_expected_yaw" "$_mv_expected_pitch" \
                    "$_mv_tolerance_yaw" "$_mv_tolerance_pitch") \
                    || die "$DIR_IDX:move" \
                        "asserted movement never reached its signed 2-axis envelope within the input deadline"
               read -r _mv_yaw_after _mv_pitch_after <<< "$_mv_after"
               _mv_signed_yaw=$(signed_yaw_delta "$_mv_yaw_before" "$_mv_yaw_after")
               _mv_signed_pitch=$(signed_pitch_delta "$_mv_pitch_before" "$_mv_pitch_after")
                 # Recheck the same envelope for a self-contained diagnostic.
                if ! awk -v ay="$_mv_signed_yaw" -v ey="$_mv_expected_yaw" -v ty="$_mv_tolerance_yaw" \
                        -v ap="$_mv_signed_pitch" -v ep="$_mv_expected_pitch" -v tp="$_mv_tolerance_pitch" \
                    'BEGIN {
                        dy=ay-ey; if (dy<0) dy=-dy;
                        dp=ap-ep; if (dp<0) dp=-dp;
                        exit !(dy<=ty && dp<=tp)
                    }'; then
                    die "$DIR_IDX:move" \
                        "asserted movement was outside its signed 2-axis envelope: yaw $_mv_signed_yaw vs $_mv_expected_yaw +/- $_mv_tolerance_yaw; pitch $_mv_signed_pitch vs $_mv_expected_pitch +/- $_mv_tolerance_pitch ($_mv_before -> $_mv_after)"
                fi
               LAST_MOVE_YAW_BEFORE="$_mv_yaw_before"
                LAST_MOVE_YAW_AFTER="$_mv_yaw_after"
                check_pass "$DIR_IDX:move" \
                    "moved $rest once; server rotation $_mv_before -> $_mv_after (yaw $_mv_signed_yaw vs $_mv_expected_yaw +/- $_mv_tolerance_yaw; pitch $_mv_signed_pitch vs $_mv_expected_pitch +/- $_mv_tolerance_pitch)";;
        scmd)  scmd "$rest"; check_pass "$DIR_IDX:scmd" "issued on console: $rest";;
        # `capture_pos NAME dx dy dz`: freezes the player's CURRENT position
        # (floored, plus the given integer offset) into $NAME_X/$NAME_Y/$NAME_Z
         # for later directives to reference by name. Exists because the
         # former teleport-based safe_regrab (retired in favour of unbound
         # X button 8) was proven live to leave the
        # player's true position drifted by up to ~0.4 blocks from where it
        # was moments earlier (20260824T103155Z, 20260824T111340Z) --
        # apparently residual client movement packets trickling in and
        # getting accepted after the round trip's own restore already ran.
        # A LATER `~`-relative offset computed from that drifted position can
        # floor to a different integer block than intended. Capturing once,
         # right after a position is established, then using the frozen integers from then
        # on, makes a later command's targeting immune to that drift entirely.
        capture_pos)
               set -- $rest
               cp_name=$1; cp_dx=$2; cp_dy=$3; cp_dz=$4
               scmd "data get entity $PLAYER Pos"
               sleep 1
               cp_pos=$(grep -oP "$PLAYER has the following entity data: \[\K[-0-9.]+d, [-0-9.]+d, [-0-9.]+d" "$SRV_LOG" 2>/dev/null | tail -1)
               cp_x=$(echo "$cp_pos" | cut -d',' -f1 | tr -d 'd ')
               cp_y=$(echo "$cp_pos" | cut -d',' -f2 | tr -d 'd ')
               cp_z=$(echo "$cp_pos" | cut -d',' -f3 | tr -d 'd ')
               if [ -z "$cp_x" ] || [ -z "$cp_y" ] || [ -z "$cp_z" ]; then
                   check_fail "$DIR_IDX:capture_pos" "could not read $PLAYER's position"
               else
                   cp_ix=$(python3 -c "import math; print(math.floor($cp_x)+$cp_dx)")
                   cp_iy=$(python3 -c "import math; print(math.floor($cp_y)+$cp_dy)")
                   cp_iz=$(python3 -c "import math; print(math.floor($cp_z)+$cp_dz)")
                   CAPTURED_VARS["${cp_name}_X"]="$cp_ix"
                   CAPTURED_VARS["${cp_name}_Y"]="$cp_iy"
                   CAPTURED_VARS["${cp_name}_Z"]="$cp_iz"
                   # Block-CENTRE twins, for STANDING on -- as opposed to the
                   # integer ones above, which are for ADDRESSING a block.
                   #
                   # `/tp <player> 299 -60 308` puts the player at exactly
                   # x=299.0, and that is not the middle of block 299: it is
                   # the SEAM between block 298 and block 299. A yaw-0 look
                   # from there sends the pick ray straight down that seam and
                   # which block it reports is decided by floating-point luck.
                   # That is why the plaque click missed one block to the left
                   # on some runs and landed on others with the scenario
                   # unchanged -- shots/plaque-01-before-click.png from the
                   # 14:06 run shows the block outline sitting on the stone
                   # brick BESIDE the plaque, before any click was sent.
                   #
                   # The 2026-08-24 round read this as position DRIFT and
                   # froze the coordinates to stop it. That removed a real
                   # second cause but left this one, because frozen integer
                   # coordinates are still seam coordinates. Standing at the
                   # centre removes the ambiguity instead of making it rarer.
                   CAPTURED_VARS["${cp_name}_CX"]="$(python3 -c "print($cp_ix + 0.5)")"
                   CAPTURED_VARS["${cp_name}_CY"]="$(python3 -c "print($cp_iy + 0.5)")"
                   CAPTURED_VARS["${cp_name}_CZ"]="$(python3 -c "print($cp_iz + 0.5)")"
                   check_pass "$DIR_IDX:capture_pos" "captured $cp_name = ($cp_ix, $cp_iy, $cp_iz)"
               fi;;
        shot)  sleep 1; shot "$rest"
               if [ -s "$EV_SHOTS/$rest.png" ]; then
                   check_pass "$DIR_IDX:shot" "captured shots/$rest.png"
               else
                   check_fail "$DIR_IDX:shot" "shots/$rest.png was not produced"
               fi
               echo "captured $rest.png";;

        shot_now)
               shot "$rest"
               if [ -s "$EV_SHOTS/$rest.png" ]; then
                   check_pass "$DIR_IDX:shot_now" \
                       "captured immediate shots/$rest.png"
               else
                   check_fail "$DIR_IDX:shot_now" \
                       "shots/$rest.png was not produced"
               fi
               echo "captured immediate $rest.png";;

        expect_server)
            # See SRV_LOG note above (finding 1): the real Log4j file, never
            # the tmux pane's own echo of what was just typed. Searches only
            # from LOG_ANCHOR (see its own comment, above the directive
            # loop) — never the whole cumulative file — so this can only be
            # satisfied by something the most recent action actually caused,
            # not a stale match from minutes earlier in the same run.
            FOUND=0
            for _ in $(seq 1 10); do
                tail -n +"$((LOG_ANCHOR + 1))" "$SRV_LOG" 2>/dev/null | grep -qE "$rest" && { FOUND=1; break; }
                sleep 1
            done
            if [ "$FOUND" = 1 ]; then
                check_pass "$DIR_IDX:expect_server:$rest" "$(tail -n +"$((LOG_ANCHOR + 1))" "$SRV_LOG" 2>/dev/null | grep -m1 -E "$rest")"
            else
                die "$DIR_IDX:expect_server:$rest" "server log never matched /$rest/ (since the most recent action)"
            fi
            ;;

        expect_shot)
            # AC-3 must validate the framebuffer profile this run actually
            # requested.  Falling back to check_screenshot.py's historical
            # 1280x720 defaults made legitimate 1920x1080 scale-4 evidence
            # fail even after the client and Xvfb had rendered it correctly.
            RES=$(python3 "$HERE/check_screenshot.py" "$EV_SHOTS/$rest.png" \
                --width "$DISPLAY_WIDTH" --height "$DISPLAY_HEIGHT" 2>&1)
            if echo "$RES" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("pass") else 1)' 2>/dev/null; then
                check_pass "$DIR_IDX:expect_shot:$rest" "$RES"
            else
                die "$DIR_IDX:expect_shot:$rest" "shots/$rest.png failed AC-3: $RES"
            fi
            ;;

        expect_screen)
            if [[ ! "$rest" =~ ^[A-Za-z_$][A-Za-z0-9_.$]*\.[A-Za-z_$][A-Za-z0-9_$]*$ ]]; then
                die "$DIR_IDX:expect_screen" \
                    "expected one fully-qualified screen class name, got: $rest"
            fi
            _es_simple="${rest##*.}"
            if ! wait_client_frame "$_es_simple" false \
                    "expect_screen $rest"; then
                die "$DIR_IDX:expect_screen:$rest" \
                    "client did not render the required screen"
            fi
            if [[ "$FRAME_ACK_LINE" != *" screenClass=$rest "* ]]; then
                die "$DIR_IDX:expect_screen:$rest" \
                    "client rendered a same-named screen from the wrong class: $FRAME_ACK_LINE"
            fi
            check_pass "$DIR_IDX:expect_screen:$rest" \
                "client rendered screenClass=$rest with mouse released"
            ;;

        expect_pixel_change)
            set -- $rest
            BEFORE="$1"; AFTER="$2"; MINPCT="${3:-2.0}"; REGION="${4:-lower-third}"
            RES=$(python3 "$HERE/pixel_diff.py" "$EV_SHOTS/$BEFORE.png" "$EV_SHOTS/$AFTER.png" --min-percent "$MINPCT" --region "$REGION" 2>&1)
            if echo "$RES" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("pass") else 1)' 2>/dev/null; then
                check_pass "$DIR_IDX:expect_pixel_change:$BEFORE->$AFTER" "$RES"
            else
                die "$DIR_IDX:expect_pixel_change:$BEFORE->$AFTER" "key input produced no visible change: $RES"
            fi
            ;;

        expect_rotation_change)
            MINDEG="${rest:-30}"
            RESULT=$(python3 - "$MINDEG" \
                "${LAST_MOVE_YAW_BEFORE:-}" "${LAST_MOVE_YAW_AFTER:-}" <<'PYEOF'
import sys
min_deg, move_y0, move_y1 = float(sys.argv[1]), sys.argv[2], sys.argv[3]
if not move_y0 or not move_y1:
    print("FAIL: no server-observed move bracket is available")
    sys.exit(1)
y0, y1 = float(move_y0), float(move_y1)
delta = abs(y0 - y1) % 360
delta = min(delta, 360 - delta)
if delta > min_deg:
    print(f"PASS: yaw {y0} -> {y1} (delta {delta:.1f} > {min_deg})")
    sys.exit(0)
print(f"FAIL: yaw {y0} -> {y1} (delta {delta:.1f} <= {min_deg})")
sys.exit(1)
PYEOF
)
            LAST_MOVE_YAW_BEFORE=""
            LAST_MOVE_YAW_AFTER=""
            if [[ "$RESULT" == PASS:* ]]; then
                check_pass "$DIR_IDX:expect_rotation_change" "$RESULT"
            else
                die "$DIR_IDX:expect_rotation_change" "$RESULT"
            fi
            ;;

        *)     die "$DIR_IDX:unknown_directive" "unrecognised scenario directive: '$verb $rest' — a typo silently removes an assertion, so this is a hard FAIL";;
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

teardown || die process_cleanup "playtest client/server/Xvfb, tmux, display or port survived exact teardown; recovery record retained"
[ "$TEARDOWN_PENDING_SIGNAL" -eq 0 ] \
    || on_signal "$TEARDOWN_PENDING_SIGNAL"
finish_result PASS
write_reproduction "# Reproduce: playtest
tools/hearthstead-qa playtest
Scenario: $SCENARIO
Instance: $INST (port $PORT), player: $PLAYER
"
echo "playtest ok: $COUNT screenshots in $EV_SHOTS"
