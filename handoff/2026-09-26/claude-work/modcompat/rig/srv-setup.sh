#!/bin/bash
# srv-setup.sh [df|nodf] : private copy of the real test server at /root/hsmc-srv (never touches /root/hs-realsrv)
set -e
S=/root/hsmc-srv; B=/mnt/c/Users/tobia/Hearthstead-Claude/build-agent-modcompat; T=/mnt/c/Users/tobia/Hearthstead-Claude/mods-test
if [ ! -d $S/libraries ]; then
  mkdir -p $S
  (cd /root/hs-realsrv && tar cf - --exclude=./world --exclude=./logs --exclude=./mods . ) | (cd $S && tar xf -)
fi
rm -rf $S/world; cp -r /mnt/c/Users/tobia/Hearthstead-Claude/world-copies/server-20260925 $S/world; rm -f $S/world/session.lock
mkdir -p $S/mods; rm -f $S/mods/*.jar
cp $B/libs/hearthstead-*.jar $S/mods/
[ "$1" = df ] && cp $T/DiagonalFences-*.jar $T/PuzzlesLib-*.jar $S/mods/
sed -i -e 's/^server-port=.*/server-port=25651/' -e 's/^rcon.port=.*/rcon.port=25652/' -e 's/^query.port=.*/query.port=25651/' \
  -e 's/^rcon.password=.*/rcon.password=hsmc-local/' -e 's/^level-name=.*/level-name=world/' -e 's/^view-distance=.*/view-distance=8/' $S/server.properties
echo "-Xmx2G" > $S/user_jvm_args.txt
echo "srv ready: $(ls $S/mods | tr '\n' ' ')"; grep -E "^(server-port|rcon.port|level-name|online-mode|motd)" $S/server.properties
