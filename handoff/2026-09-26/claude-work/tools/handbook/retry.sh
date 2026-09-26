# Retries the handbook guards while other lanes' mid-edit breakage (compile errors,
# wiped build dirs, test-executor start failures) settles; stops on real test failures.
H=/c/Users/tobia/Hearthstead-Claude/tools/handbook
for i in 1 2 3 4 5 6 7 8 9 10; do
  out=$(bash $H/hbtest.sh 2>&1)
  echo "try $i: $(echo "$out" | head -3 | tr '\n' ' ')"
  if echo "$out" | grep -q "exit 0"; then echo "$out"; cd /c/Users/tobia/Hearthstead-Claude/build-agent-handbook/test-results/test && grep -ho 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' *.xml; exit 0; fi
  if echo "$out" | grep -q "failed to execute tests\|Could not start\|Could not complete\|compileJava FAILED\|compileTestJava FAILED\| error:"; then sleep 90; continue; fi
  echo "$out"; exit 1
done
exit 2
