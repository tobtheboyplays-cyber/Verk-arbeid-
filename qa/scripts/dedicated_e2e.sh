#!/usr/bin/env bash
# Dedicated-server E2E: install a real NeoForge server, boot with the built
# jar, drive founding via console, restart, verify persistence + zero
# client-classloading errors. Args: <mod-dir> <artifact-dir>
set -eu
MOD="$1"; OUT="$2"
WORK="${HSQA_SERVER_DIR:-/tmp/claude-0/hsqa-server}"
NEO_VERSION=$(grep -oP 'neoforge_version=\K.*' "$MOD/gradle.properties")
JAR=$(ls "$MOD"/build/libs/hearthstead-*.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || { echo "no mod jar built — run build first"; exit 1; }

mkdir -p "$WORK"
cd "$WORK"

if [ ! -f "$WORK/installed-$NEO_VERSION" ]; then
    rm -rf "$WORK"/{libraries,run.sh,run.bat,user_jvm_args.txt,installed-*} 2>/dev/null || true
    echo "installing NeoForge $NEO_VERSION server..."
    curl -sS -o installer.jar "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO_VERSION/neoforge-$NEO_VERSION-installer.jar"
    java -jar installer.jar --install-server . > install.log 2>&1
    touch "installed-$NEO_VERSION"
fi

echo "eula=true" > eula.txt
rm -rf world mods logs
mkdir -p mods
cp "$JAR" mods/
cat > server.properties <<EOF
online-mode=false
spawn-protection=0
level-type=minecraft\\:flat
max-tick-time=180000
EOF

boot() { # duration commands-file tag
    local dur="$1" cmds="$2" tag="$3"
    ( sleep 25
      [ -f "$cmds" ] && while IFS= read -r line; do
            case "$line" in SLEEP*) sleep "${line#SLEEP }";; *) echo "$line";; esac
        done < "$cmds"
      sleep 5; echo "stop" ) | ./run.sh nogui > "boot-$tag.out" 2>&1 || true
    cp logs/latest.log "$OUT/dedicated-$tag.log" 2>/dev/null || true
}

cat > cmds1.txt <<'EOF'
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
boot 140 cmds1.txt first

grep -q "E2E_SETTLERS_ALIVE" "$OUT/dedicated-first.log" || { echo "FAIL: settlers did not spawn on dedicated server"; exit 1; }
grep -q "population 3" "$OUT/dedicated-first.log" || { echo "FAIL: settlement info missing"; exit 1; }
if grep -qE "ClassNotFoundException|NoClassDefFoundError" "$OUT/dedicated-first.log"; then
    echo "FAIL: classloading errors on dedicated server"; exit 1
fi

cat > cmds2.txt <<'EOF'
SLEEP 8
hearthstead info
execute if entity @e[type=hearthstead:settler] run say E2E_SETTLERS_PERSISTED
EOF
boot 60 cmds2.txt second

grep -q "E2E_SETTLERS_PERSISTED" "$OUT/dedicated-second.log" || { echo "FAIL: settlers lost after restart"; exit 1; }
grep -q "population 3" "$OUT/dedicated-second.log" || { echo "FAIL: settlement record lost after restart"; exit 1; }
[ -f world/data/hearthstead_settlements.dat ] || { echo "FAIL: SavedData file missing"; exit 1; }

echo "dedicated E2E ok: boot+found+restart persistence verified, no client classloading"
