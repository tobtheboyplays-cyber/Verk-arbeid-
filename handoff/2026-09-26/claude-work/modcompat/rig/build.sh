#!/bin/bash
# Compile the shared tree into the private mod-compat build dir; write run-arg files (no launch, no daemon).
export HSQA_SP=MC-Test
cd /mnt/c/Users/tobia/Hearthstead-Claude/Verk-arbeid-/hearthstead-neoforge || exit 1
nice -n 15 ./gradlew classes prepareClientRun --offline --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m \
  -I /mnt/c/Users/tobia/Hearthstead-Claude/modcompat/rig/init.gradle \
  -PhearthsteadBuildDir=/mnt/c/Users/tobia/Hearthstead-Claude/build-agent-modcompat --project-cache-dir /root/hsmc-pc > /root/hsmc-build.log 2>&1
echo "build rc=$?"; tail -5 /root/hsmc-build.log
