#!/bin/bash
export HSQA_SP=MC-Test
cd /mnt/c/Users/tobia/Hearthstead-Claude/Verk-arbeid-/hearthstead-neoforge || exit 1
nice -n 15 ./gradlew jar prepareClientRun --offline --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m \
  -I /mnt/c/Users/tobia/Hearthstead-Claude/modcompat/rig/init.gradle \
  -PhearthsteadBuildDir=/mnt/c/Users/tobia/Hearthstead-Claude/build-agent-modcompat --project-cache-dir /root/hsmc-pc > /root/hsmc-jar.log 2>&1
echo "jar rc=$?"; tail -4 /root/hsmc-jar.log; ls -la /mnt/c/Users/tobia/Hearthstead-Claude/build-agent-modcompat/libs/
