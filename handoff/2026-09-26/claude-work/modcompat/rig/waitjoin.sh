#!/bin/bash
# waits for world join / crash; prints mixin/crash lines
for i in $(seq 1 ${1:-120}); do
  grep -q 'joined the game\|Exception in thread\|---- Minecraft Crash\|Loading errors\|ModLoadingException' /tmp/hsmc-client.log 2>/dev/null && break
  pgrep -f "hsmc-run\|build-agent-modcompat" >/dev/null || { echo "JVM GONE"; break; }
  sleep 5
done
grep -m6 'joined the game\|Exception in thread\|Crash\|Loading errors\|ModLoadingException' /tmp/hsmc-client.log | cut -c1-240
tail -n 2 /tmp/hsmc-client.log | cut -c1-200
