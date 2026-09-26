#!/bin/bash
# start client in background; MODE=sp|mp|title
nohup env MODE=${MODE:-mp} bash /mnt/c/Users/tobia/Hearthstead-Claude/modcompat/rig/client.sh > /dev/null 2>&1 &
sleep 3; pgrep -f "hsmc-args.txt" | head -1 > /root/hsmc-cli.pid; echo "client pid $(cat /root/hsmc-cli.pid)"
