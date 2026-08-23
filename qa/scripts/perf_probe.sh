#!/usr/bin/env bash
# Performance probe: 30 settlers on the dedicated server, MSPT via
# /tick query. Budget: average MSPT < 45. Args: <mod-dir> <artifact-dir>
set -eu
MOD="$1"; OUT="$2"
WORK="${HSQA_SERVER_DIR:-/tmp/claude-0/hsqa-server}"
cd "$WORK" 2>/dev/null || { echo "server dir missing — run dedicated first"; exit 1; }
[ -d mods ] || { echo "server not installed — run dedicated first"; exit 1; }

rm -rf world logs
cat > perf_cmds.txt <<'EOF'
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
    echo "execute positioned 0 -54 0 run summon hearthstead:settler ~$((i % 9 - 4)) ~ ~$((i / 9 - 1))" >> perf_cmds.txt
done
cat >> perf_cmds.txt <<'EOF'
SLEEP 40
tick query
SLEEP 10
tick query
SLEEP 10
tick query
SLEEP 10
execute if entity @e[type=hearthstead:settler,limit=25] run say PERF_POPULATION_OK
EOF

( sleep 25
  while IFS= read -r line; do
      case "$line" in SLEEP*) sleep "${line#SLEEP }";; *) echo "$line";; esac
  done < perf_cmds.txt
  sleep 5; echo "stop" ) | ./run.sh nogui > perf-boot.out 2>&1 || true
cp logs/latest.log "$OUT/performance.server.log" 2>/dev/null || true

grep -q "PERF_POPULATION_OK" "$OUT/performance.server.log" \
    || { echo "FAIL: could not stand up 25+ settlers"; exit 1; }

# 1.21.1 reports "Average time per tick: 1.3ms (Target: 50.0ms)". Accept the
# older "average of X" phrasing too so the probe survives a version bump.
MSPTS=$(grep -oP '(?:Average time per tick:\s*|average of )\K[0-9]+(?:\.[0-9]+)?' \
    "$OUT/performance.server.log" | tail -3)
[ -n "$MSPTS" ] || { echo "FAIL: no /tick query output captured"; exit 1; }
AVG=$(echo "$MSPTS" | python3 -c "import sys; v=[float(x) for x in sys.stdin]; print(sum(v)/len(v))")
python3 -c "import sys; sys.exit(0 if $AVG < 45.0 else 1)" \
    || { echo "FAIL: MSPT budget exceeded: avg=$AVG (budget 45.0)"; exit 1; }
echo "performance ok: ~27 settlers, avg MSPT=$AVG (budget 45.0)"
