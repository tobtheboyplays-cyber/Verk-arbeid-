#!/bin/bash
cd /c/Users/tobia/Hearthstead-Claude/Verk-arbeid-/hearthstead-neoforge
./gradlew.bat test --tests "com.hearthstead.client.ui2.handbook.*" --tests "com.hearthstead.client.screen.HandbookChapterLanguageTest" --tests "com.hearthstead.client.screen.ResponsiveScreenLayoutTest" --tests "com.hearthstead.settlement.journey.HandbookLanguageContractTest" --tests "com.hearthstead.settlement.journey.NewPlayerGuidanceContractTest" "-PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-agent-handbook" "-Dorg.gradle.daemon.idletimeout=300000" --console=plain > /c/Users/tobia/Hearthstead-Claude/build-agent-handbook-test.log 2>&1
echo "exit $?"
grep -E ' error:|tests completed|BUILD|FAILED|Test Executor' /c/Users/tobia/Hearthstead-Claude/build-agent-handbook-test.log | head -20
cd /c/Users/tobia/Hearthstead-Claude/build-agent-handbook/test-results/test
for f in TEST-com.hearthstead*Handbook*.xml TEST-com.hearthstead.client.ui2.handbook*.xml TEST-*Responsive*.xml TEST-*NewPlayer*.xml; do [ -f "$f" ] && python - "$f" <<'PY'
import sys,re,html
s=open(sys.argv[1],encoding='utf-8').read()
for m in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<failure message="([^"]*)"',s):
    print('==',m.group(1)); print(html.unescape(m.group(2))[:2500])
PY
done
cd /c/Users/tobia/Hearthstead-Claude/build-agent-handbook/test-results/test 2>/dev/null && for f in $(grep -l "<failure" *.xml 2>/dev/null); do python - "$f" <<'PY'
import sys,re,html
s=open(sys.argv[1],encoding='utf-8').read()
for m in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<failure message="([^"]*)"',s):
    print('==',m.group(1)); print(html.unescape(m.group(2))[:1500])
PY
done
