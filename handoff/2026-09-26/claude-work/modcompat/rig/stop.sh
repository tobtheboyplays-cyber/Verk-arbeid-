#!/bin/bash
# stop ONLY our own JVMs (client by args file, server by pid/dir) and our Xvfb :79
python3 /mnt/c/Users/tobia/Hearthstead-Claude/modcompat/rig/rcon.py "stop" 2>/dev/null | head -2
for p in $(pgrep -f "hsmc-args.txt"); do kill $p; done
sleep 8
[ -f /root/hsmc-srv.pid ] && kill $(cat /root/hsmc-srv.pid) 2>/dev/null
for p in $(pgrep -f "hsmc-args.txt"); do kill -9 $p; done
pkill -f "Xvfb :79" 2>/dev/null
sleep 1; echo "left: $(pgrep -af 'hsmc' | grep -v stop.sh | cut -c1-120)"
