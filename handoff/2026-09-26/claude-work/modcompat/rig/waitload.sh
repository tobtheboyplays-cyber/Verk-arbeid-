#!/bin/bash
# waits until CPU and RAM both <= ${1:-85}% for two consecutive samples (max ~40 min)
L="C:\Users\tobia\AppData\Local\Temp\claude\C--Users-tobia-OneDrive-Documents-Claude-apper\4f4a9c83-1374-46cf-9243-77f4104be392\scratchpad\load.ps1"
ok=0
for i in $(seq 1 80); do
  s=$(powershell.exe -NoProfile -File "$L" 2>/dev/null | tr -d '\r')
  c=$(echo "$s" | sed -E 's/.*CPU ([0-9]+)%.*/\1/'); r=$(echo "$s" | sed -E 's/.*RAM ([0-9]+)%.*/\1/')
  if [ "$c" -le ${1:-85} ] && [ "$r" -le ${1:-85} ]; then ok=$((ok+1)); else ok=0; fi
  [ $ok -ge 2 ] && { echo "LOAD OK: $s"; exit 0; }
  sleep 30
done
echo "TIMEOUT last: $s"
