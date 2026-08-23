#!/usr/bin/env bash
# Dedicated-server E2E: install a real NeoForge server, boot with the built
# jar, drive founding via console, restart, verify persistence + zero
# client-classloading errors.
#
# Ordered fact ladder (AC-13): preflight port -> server reaches "Done (" ->
# only then anything about settlers. Each fact gets its own line so a future
# failure names its true first cause instead of a downstream symptom (this is
# exactly how KF-002 was misdiagnosed: settlers-not-spawning was asserted
# before the server-came-up fact).
#
# Args: <mod-dir> <artifact-dir>
set -u
MOD="$1"; OUT="$2"
. "$(dirname "${BASH_SOURCE[0]}")/lib_harness.sh"

ROLE="dedicated"
PORT="${HSQA_DEDICATED_PORT:-25571}"
ev_init "$ROLE"

TEARDOWN_DONE=0
teardown() {
    [ "$TEARDOWN_DONE" = 1 ] && return
    TEARDOWN_DONE=1
    # Kill the whole process group (server_pid was launched with `set -m`,
    # so it is its own group leader) — this reaches the java child even
    # though the wrapper shell's argv never mentions the instance path.
    [ -n "${SERVER_PID:-}" ] && kill -9 -- "-$SERVER_PID" 2>/dev/null
    pkill -9 -f "hsqa.instanceDir=.*/$ROLE" 2>/dev/null || true
    clear_pidfile "$ROLE"
}
trap teardown EXIT INT TERM

# FACT 1: port free before anything launches.
if ! MSG=$(preflight_port "$PORT" "$ROLE"); then die port_preflight "$MSG"; fi
check_pass port_preflight "port $PORT free before launch"

JAR=$(ls "$MOD"/build/libs/hearthstead-*.jar 2>/dev/null | grep -v sources | head -1)
[ -n "$JAR" ] || die build_jar "no mod jar built — run build first"
check_pass build_jar "$(basename "$JAR")"

bash "$(dirname "${BASH_SOURCE[0]}")/server_install.sh" "$MOD" > "$EV_LOGS/install.log" 2>&1 \
    || die install "shared NeoForge install failed — see logs/install.log"
check_pass install "shared install present"

INST=$(bash "$(dirname "${BASH_SOURCE[0]}")/server_instance.sh" "$ROLE" "$PORT" "$MOD" 2>"$EV_LOGS/instance.log" | tail -1)
[ -d "$INST" ] || die instance "server_instance.sh did not produce a usable instance — see logs/instance.log"
check_pass instance "$INST"

boot() { # duration commands-file tag
    local dur="$1" cmds="$2" tag="$3"
    set -m   # so the backgrounded pipeline gets its own process group (PGID == PID)
    ( sleep 20
      [ -f "$cmds" ] && while IFS= read -r line; do
            case "$line" in SLEEP*) sleep "${line#SLEEP }";; *) echo "$line";; esac
        done < "$cmds"
      sleep 5; echo "stop" ) | (cd "$INST" && timeout "$dur" ./run.sh nogui) > "$EV_LOGS/boot-$tag.out" 2>&1 &
    SERVER_PID=$!
    set +m
    register_pid "$ROLE" "-$SERVER_PID"
    wait "$SERVER_PID" 2>/dev/null || true
    cp "$INST/logs/latest.log" "$EV_LOGS/dedicated-$tag.log" 2>/dev/null || true
}

cat > "$INST/cmds1.txt" <<'EOF'
forceload add 0 0
fill -6 -55 -6 6 -55 6 minecraft:stone
fill -6 -54 -6 -6 -52 6 minecraft:stone_bricks
fill 6 -54 -6 6 -52 6 minecraft:stone_bricks
fill -6 -54 -6 6 -52 -6 minecraft:stone_bricks
fill -6 -54 6 6 -52 6 minecraft:stone_bricks
SLEEP 2
setblock 0 -54 0 hearthstead:hearth
SLEEP 90
hearthstead info
execute if entity @e[type=hearthstead:settler] run say E2E_SETTLERS_ALIVE
EOF
boot 140 "$INST/cmds1.txt" first

# FACT 2: the server actually came up. This MUST be checked before anything
# about settlers, or a dead server reads as "settlers did not spawn" (KF-002).
if ! grep -q 'Done (' "$EV_LOGS/dedicated-first.log" 2>/dev/null; then
    REASON=$(grep -m1 -E 'FAILED TO BIND|Address already in use|Exception|Error' "$EV_LOGS/dedicated-first.log" 2>/dev/null || echo "no Done( line and no obvious exception — see logs/dedicated-first.log")
    die server_started "server never reached Done( : $REASON"
fi
check_pass server_started "$(grep -m1 'Done (' "$EV_LOGS/dedicated-first.log")"

# FACT 3: only now, settlers.
grep -q "E2E_SETTLERS_ALIVE" "$EV_LOGS/dedicated-first.log" || die settlers_spawned "server was up but settlers did not spawn"
check_pass settlers_spawned "E2E_SETTLERS_ALIVE echoed"

grep -q "population 3" "$EV_LOGS/dedicated-first.log" || die settlement_info "settlement info missing"
check_pass settlement_info "population 3"

if grep -qE "ClassNotFoundException|NoClassDefFoundError" "$EV_LOGS/dedicated-first.log"; then
    die no_client_classloading "client classloading error(s) on dedicated server"
fi
check_pass no_client_classloading "no ClassNotFoundException/NoClassDefFoundError"

cat > "$INST/cmds2.txt" <<'EOF'
SLEEP 8
hearthstead info
execute if entity @e[type=hearthstead:settler] run say E2E_SETTLERS_PERSISTED
EOF
boot 60 "$INST/cmds2.txt" second

if ! grep -q 'Done (' "$EV_LOGS/dedicated-second.log" 2>/dev/null; then
    die server_restarted "server never reached Done( on restart"
fi
check_pass server_restarted "$(grep -m1 'Done (' "$EV_LOGS/dedicated-second.log")"

grep -q "E2E_SETTLERS_PERSISTED" "$EV_LOGS/dedicated-second.log" || die persistence_settlers "settlers lost after restart"
check_pass persistence_settlers "E2E_SETTLERS_PERSISTED echoed"

grep -q "population 3" "$EV_LOGS/dedicated-second.log" || die persistence_info "settlement record lost after restart"
check_pass persistence_info "population 3 after restart"

[ -f "$INST/world/data/hearthstead_settlements.dat" ] || die saveddata_file "SavedData file missing"
check_pass saveddata_file "world/data/hearthstead_settlements.dat present"

finish_result PASS
write_reproduction "# Reproduce: dedicated
tools/hearthstead-qa dedicated
Instance: $INST (port $PORT)
"
echo "dedicated E2E ok: boot+found+restart persistence verified, no client classloading"
