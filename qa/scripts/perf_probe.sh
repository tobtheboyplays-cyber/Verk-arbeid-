#!/usr/bin/env bash
# Performance probe: 30 settlers on its own isolated dedicated-server
# instance, MSPT via /tick query. Budget: average MSPT < 45.
#
# Ordered fact ladder (AC-13), same discipline as dedicated_e2e.sh: preflight
# port -> server reaches "Done (" -> only then settler population -> only
# then MSPT. Uses its own port and instance dir so a back-to-back
# `dedicated` then `performance` run cannot clash (KF-003 was this exact
# collision under the old shared single-instance design).
#
# Args: <mod-dir> <artifact-dir>
set -u
MOD="$1"; OUT="$2"
. "$(dirname "${BASH_SOURCE[0]}")/lib_harness.sh"

ROLE="performance"
PORT="${HSQA_PERFORMANCE_PORT:-25572}"
ev_init "$ROLE"

TEARDOWN_DONE=0
teardown() {
    [ "$TEARDOWN_DONE" = 1 ] && return
    TEARDOWN_DONE=1
    [ -n "${SERVER_PID:-}" ] && kill -9 -- "-$SERVER_PID" 2>/dev/null
    pkill -9 -f "hsqa.instanceDir=.*/$ROLE" 2>/dev/null || true
    clear_pidfile "$ROLE"
}
trap teardown EXIT INT TERM

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

cat > "$INST/perf_cmds.txt" <<'EOF'
forceload add 0 0
fill -12 -55 -12 12 -55 12 minecraft:stone
fill -12 -54 -12 -12 -50 12 minecraft:stone_bricks
fill 12 -54 -12 12 -50 12 minecraft:stone_bricks
fill -12 -54 -12 12 -50 -12 minecraft:stone_bricks
fill -12 -54 12 12 -50 12 minecraft:stone_bricks
setblock 0 -54 0 hearthstead:hearth
SLEEP 30
execute positioned 0 -54 0 run summon hearthstead:settler ~2 ~ ~1
EOF
for i in $(seq 1 26); do
    echo "execute positioned 0 -54 0 run summon hearthstead:settler ~$((i % 9 - 4)) ~ ~$((i / 9 - 1))" >> "$INST/perf_cmds.txt"
done
cat >> "$INST/perf_cmds.txt" <<'EOF'
SLEEP 40
tick query
SLEEP 10
tick query
SLEEP 10
tick query
SLEEP 10
execute if entity @e[type=hearthstead:settler,limit=25] run say PERF_POPULATION_OK
EOF

set -m
( sleep 20
  while IFS= read -r line; do
      case "$line" in SLEEP*) sleep "${line#SLEEP }";; *) echo "$line";; esac
  done < "$INST/perf_cmds.txt"
  sleep 5; echo "stop" ) | (cd "$INST" && timeout --foreground 180 ./run.sh nogui) > "$EV_LOGS/perf-boot.out" 2>&1 &
SERVER_PID=$!
set +m
register_pid "$ROLE" "-$SERVER_PID"
wait "$SERVER_PID" 2>/dev/null || true
cp "$INST/logs/latest.log" "$EV_LOGS/performance.server.log" 2>/dev/null || true

# FACT: server up, before anything about settlers or MSPT.
if ! grep -q 'Done (' "$EV_LOGS/performance.server.log" 2>/dev/null; then
    REASON=$(grep -m1 -E 'FAILED TO BIND|Address already in use|Exception|Error' "$EV_LOGS/performance.server.log" 2>/dev/null || echo "no Done( line — see logs/performance.server.log")
    die server_started "server never reached Done( : $REASON"
fi
check_pass server_started "$(grep -m1 'Done (' "$EV_LOGS/performance.server.log")"

grep -q "PERF_POPULATION_OK" "$EV_LOGS/performance.server.log" \
    || die population "could not stand up 25+ settlers"
check_pass population "PERF_POPULATION_OK echoed (>=25 settlers alive)"

# 1.21.1 reports "Average time per tick: 1.3ms (Target: 50.0ms)". Accept the
# older "average of X" phrasing too so the probe survives a version bump.
MSPTS=$(grep -oP '(?:Average time per tick:\s*|average of )\K[0-9]+(?:\.[0-9]+)?' \
    "$EV_LOGS/performance.server.log" | tail -3)
[ -n "$MSPTS" ] || die tick_query "no /tick query output captured"
AVG=$(echo "$MSPTS" | python3 -c "import sys; v=[float(x) for x in sys.stdin]; print(sum(v)/len(v))")
if ! python3 -c "import sys; sys.exit(0 if $AVG < 45.0 else 1)"; then
    die mspt_budget "MSPT budget exceeded: avg=$AVG (budget 45.0)"
fi
check_pass mspt_budget "avg MSPT=$AVG (budget 45.0)"

finish_result PASS
write_reproduction "# Reproduce: performance
tools/hearthstead-qa performance
Instance: $INST (port $PORT)
"
echo "performance ok: ~27 settlers, avg MSPT=$AVG (budget 45.0)"
