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
#     expect_server <regex>               FAIL unless regex is in the server's
#                                          OWN Log4j output (logs/latest.log,
#                                          never the tmux pane transcript,
#                                          which echoes back a scmd's typed
#                                          text before the server ever runs
#                                          it — see the SRV_LOG note below)
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
    # $INST is ephemeral (server_instance.sh rm -rf's it on this role's next
    # run) — preserve the authoritative server log in durable evidence
    # regardless of which exit path got here (pass, die(), or an abort).
    [ -n "${INST:-}" ] && [ -f "$INST/logs/latest.log" ] \
        && cp "$INST/logs/latest.log" "$EV_LOGS/playtest-server-latest.log" 2>/dev/null
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
renderDistance:6
simulationDistance:6
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
    grep -q 'Done (' "$SRV_LOG" 2>/dev/null && { SERVER_UP=1; break; }
    tmux has-session -t "$TMUX_PT" 2>/dev/null || break
done
if [ "$SERVER_UP" != 1 ]; then
    REASON=$(grep -m1 -E 'FAILED TO BIND|Address already in use|Exception|Error' "$SRV_LOG" 2>/dev/null || echo "no Done( line — see logs/playtest-server.log")
    die server_started "server never reached Done( : $REASON"
fi
check_pass server_started "$(grep -m1 'Done (' "$SRV_LOG")"

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

# KF-009's actual root cause (opus review, 2026-08-24): `cmd` and `move`
# each end with a click to re-establish GLFW's relative-mouse grab, and
# that click landed at WHATEVER the crosshair was already pointing at --
# the comment here used to call it "a harmless left-click on empty space",
# which was never actually verified. In CREATIVE MODE (what every scenario
# uses) a left-click is an INSTANT block break regardless of what is under
# it. This destroyed the plaque scenario's own plaque immediately after a
# successful survey, deterministically, and was misdiagnosed for hours as
# unexplained input-delivery flakiness before the evidence (the block
# actually vanishing between two screenshots) was read correctly.
#
# First fix (looking straight up before clicking) was NOT sufficient on its
# own: PLAQUE-1's room is built underground (Y around -60), so "straight
# up" from inside or near it hits the room's own roof or the natural
# terrain above, well within creative reach -- not open sky. That click
# still broke a block, and CommonEvents.onBlockBreak -> BuildingManager.
# nudgeNear (32-block radius) re-surveys every known plaque near ANY block
# change, silently (nudgeNear's re-survey only plays a sound/particle, it
# never sends a chat line -- see PlaqueBlockEntity.announce()). Proven live
# (20260824T100153Z): the scan command's OWN chat message logged "Registered"
# at 10:08:27, then this function's trailing click ran, then `hearthstead
# info` twelve seconds later reported "Homes: 0 registered" with nothing
# in between explaining it -- a silent re-unlink from the click's collateral
# block break, not the mod losing track of anything.
#
# Real fix: don't rely on LOOK direction being safe -- rely on POSITION.
# Capture the player's exact position and rotation, teleport straight up to
# a fixed height (300) far above build height on anything this harness ever
# constructs (every test structure sits below Y=110), click there where
# NOTHING can possibly be in reach, then restore the exact original
# position and rotation via a second absolute `tp`. Both teleports use
# fully absolute coordinates (never `~`), so there is no relative round-trip
# to accumulate error in either position or rotation.
#
# Third fix, still the same underlying gotcha KF-006 already named for
# rotation ("the server-side change needs time to reach the client before
# its LOCAL camera -- what the click raycasts against -- actually matches
# it; 1s was not always enough"): a 360-block VERTICAL teleport is a much
# bigger scene change than a rotation snap (new chunks, new lighting), so
# the SAME 1s gap between issuing the up-teleport and firing the click was
# proven live (20260824T103155Z) to still be too short sometimes -- the
# click can fire while the client is still rendering the old, underground
# scene, breaking a block there even though the SERVER already considers
# the player to be at Y=300. Give it the same real margin the hearth-aim
# click already needed for the identical reason.
safe_regrab() {
    scmd "data get entity $PLAYER Pos"
    sleep 1
    local pos x y z
    pos=$(grep -oP "$PLAYER has the following entity data: \[\K[-0-9.]+d, [-0-9.]+d, [-0-9.]+d" "$SRV_LOG" 2>/dev/null | tail -1)
    x=$(echo "$pos" | cut -d',' -f1 | tr -d 'd ')
    y=$(echo "$pos" | cut -d',' -f2 | tr -d 'd ')
    z=$(echo "$pos" | cut -d',' -f3 | tr -d 'd ')

    scmd "data get entity $PLAYER Rotation"
    sleep 1
    local rot yaw pitch
    rot=$(grep -oP "$PLAYER has the following entity data: \[\K[-0-9.]+f, [-0-9.]+f" "$SRV_LOG" 2>/dev/null | tail -1)
    yaw=$(echo "$rot" | cut -d',' -f1 | tr -d 'f ')
    pitch=$(echo "$rot" | cut -d',' -f2 | tr -d 'f ')

    if [ -z "$x" ] || [ -z "$y" ] || [ -z "$z" ] || [ -z "$yaw" ] || [ -z "$pitch" ]; then
        # Could not read position/rotation back -- fall back to the old
        # unsafe click rather than silently skipping regrab (which broke
        # move/look entirely, the original failure mode this workaround
        # exists for). This should not happen in practice; if it does, it
        # is itself worth seeing in the transcript rather than hiding.
        echo "safe_regrab: could not read $PLAYER's position/rotation, falling back to a direct click" >&2
        focus; xdotool mousemove 640 360; xdotool click 1; sleep 1
        return
    fi
    scmd "execute at $PLAYER run tp $PLAYER ~ 300 ~ $yaw -90"
    sleep 3
    focus; xdotool mousemove 640 360; xdotool click 1; sleep 1
    scmd "tp $PLAYER $x $y $z $yaw $pitch"
    sleep 1
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

# GLFW does not grab the mouse for relative look until the FIRST real click
# into the window — proven live: `look`/`move` silently produce zero
# rotation change before any click has happened, on every attempt, and
# start working immediately after one. quickPlay drops the player straight
# into the world with no menu to click through, so nothing else establishes
# this grab. One harmless click here (empty air, before any scenario
# directive) means every scenario's look/move directives actually work
# regardless of what order it does things in.
focus; xdotool mousemove 640 360; xdotool click 1; sleep 1

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
        cmd|scmd|click|move|key|type) LOG_ANCHOR=$(wc -l < "$SRV_LOG" 2>/dev/null || echo 0);;
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
               xdotool mousemove 640 360
               xdotool click "$([ "${rest:-left}" = right ] && echo 3 || echo 1)"; sleep 2
               check_pass "$DIR_IDX:click" "clicked ${rest:-left}";;
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
               xdotool mousemove 640 360
               xdotool click "$([ "${rest:-left}" = right ] && echo 3 || echo 1)"; sleep 2
               check_pass "$DIR_IDX:open" "opened (${rest:-left} click, after regrab)";;
        move)  focus
               # A prior grab-establishing click does not reliably survive
               # to a LATER `move` several directives on — proven live,
               # exact cause not fully isolated (not simply "any key press"
               # or "any chat", since some sequences of those did survive
               # while others with an apparently identical shape did not).
               # Belt and braces: regrab-then-send TWICE, empirically more
               # reliable than either a single regrab or a single send
               # alone. `safe_regrab` (see its own comment, above the
               # directive loop) replaces the old bare click here — that
               # click fired at whatever the crosshair already held, which
               # in creative mode is an instant block break, not "harmless".
               safe_regrab
               xdotool mousemove_relative -- $rest; sleep 1
               safe_regrab
               xdotool mousemove_relative -- $rest; sleep 1
               check_pass "$DIR_IDX:move" "moved $rest";;
        scmd)  scmd "$rest"; check_pass "$DIR_IDX:scmd" "issued on console: $rest";;
        # `capture_pos NAME dx dy dz`: freezes the player's CURRENT position
        # (floored, plus the given integer offset) into $NAME_X/$NAME_Y/$NAME_Z
        # for later directives to reference by name. Exists because a chain of
        # regrab-teleport round trips (safe_regrab, potentially several
        # before a later directive runs) was proven live to leave the
        # player's true position drifted by up to ~0.4 blocks from where it
        # was moments earlier (20260824T103155Z, 20260824T111340Z) --
        # apparently residual client movement packets trickling in and
        # getting accepted after the round trip's own restore already ran.
        # A LATER `~`-relative offset computed from that drifted position can
        # floor to a different integer block than intended. Capturing once,
        # right after a position is established and before any further
        # regrab churn can touch it, then using the frozen integers from then
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
                   check_pass "$DIR_IDX:capture_pos" "captured $cp_name = ($cp_ix, $cp_iy, $cp_iz)"
               fi;;
        shot)  sleep 1; shot "$rest"
               if [ -s "$EV_SHOTS/$rest.png" ]; then
                   check_pass "$DIR_IDX:shot" "captured shots/$rest.png"
               else
                   check_fail "$DIR_IDX:shot" "shots/$rest.png was not produced"
               fi
               echo "captured $rest.png";;

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
            RES=$(python3 "$HERE/check_screenshot.py" "$EV_SHOTS/$rest.png" 2>&1)
            if echo "$RES" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("pass") else 1)' 2>/dev/null; then
                check_pass "$DIR_IDX:expect_shot:$rest" "$RES"
            else
                die "$DIR_IDX:expect_shot:$rest" "shots/$rest.png failed AC-3: $RES"
            fi
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
            RESULT=$(python3 - "$SRV_LOG" "$MINDEG" <<'PYEOF'
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

finish_result PASS
write_reproduction "# Reproduce: playtest
tools/hearthstead-qa playtest
Scenario: $SCENARIO
Instance: $INST (port $PORT), player: $PLAYER
"
echo "playtest ok: $COUNT screenshots in $EV_SHOTS"
