#!/bin/bash
# gametest.sh df|nodf "<batch prefixes>" : GameTest server with/without Diagonal Fences in a private dir. Needs the lock.
T=/mnt/c/Users/tobia/Hearthstead-Claude/mods-test
rm -rf /root/hsmc-gt/gametestworld /root/hsmc-gt/world; mkdir -p /root/hsmc-gt/mods; rm -f /root/hsmc-gt/mods/*.jar
[ "$1" = df ] && cp $T/DiagonalFences-*.jar $T/PuzzlesLib-*.jar /root/hsmc-gt/mods/
export HSMC_BATCHES="$2"
cd /mnt/c/Users/tobia/Hearthstead-Claude/Verk-arbeid-/hearthstead-neoforge || exit 1
nice -n 10 ./gradlew runGameTestServer --offline --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m \
  -I /mnt/c/Users/tobia/Hearthstead-Claude/modcompat/rig/gametest-init.gradle \
  -PhearthsteadBuildDir=/mnt/c/Users/tobia/Hearthstead-Claude/build-agent-modcompat --project-cache-dir /root/hsmc-pc > /root/hsmc-gt-$1.log 2>&1
echo "rc=$?"
grep -aE "HSQA_GAMETEST_FILTER|Loading [0-9]+ mods|diagonalfences|tests? (passed|failed)|required tests|All .* passed|failed!|has failed|Exception" /root/hsmc-gt-$1.log | cut -c1-260 | tail -40
