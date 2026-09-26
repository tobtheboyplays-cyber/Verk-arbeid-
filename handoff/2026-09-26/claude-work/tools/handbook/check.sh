#!/bin/bash
# One command for any lane: regenerate the handbook data, then run every handbook guard.
#   bash C:/Users/tobia/Hearthstead-Claude/tools/handbook/check.sh [your-build-dir]
# The build dir defaults to build-agent-handbook; pass your lane's private dir.
BUILD="${1:-C:/Users/tobia/Hearthstead-Claude/build-agent-handbook}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PYTHONIOENCODING=utf-8 python "$HERE/facts/gen_facts.py" || exit 1
(cd "$HERE" && PYTHONIOENCODING=utf-8 python gen_handbook.py) || exit 1
cd /c/Users/tobia/Hearthstead-Claude/Verk-arbeid-/hearthstead-neoforge || exit 1
./gradlew.bat test --tests "com.hearthstead.client.ui2.handbook.*" \
  --tests "com.hearthstead.client.screen.HandbookChapterLanguageTest" \
  --tests "com.hearthstead.settlement.journey.HandbookLanguageContractTest" \
  "-PhearthsteadBuildDir=$BUILD" "-Dorg.gradle.daemon.idletimeout=300000" --console=plain
