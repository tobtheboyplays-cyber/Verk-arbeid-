#!/bin/bash
# Launch the built client directly (no Gradle) on hidden display :79, private game dir /root/hsmc-run.
B=/mnt/c/Users/tobia/Hearthstead-Claude/build-agent-modcompat
D=:79
[ -e /tmp/.X79-lock ] || { Xvfb $D -screen 0 1280x720x24 >/tmp/hsmc-xvfb.log 2>&1 & sleep 1; }
export DISPLAY=$D ALSOFT_DRIVERS=null
unset WAYLAND_DISPLAY PULSE_SERVER
T=/mnt/c/Users/tobia/Hearthstead-Claude/videos/ui/map-rig/cmd-template.txt
CP=$(awk 'NR==9' $T | sed "s#/mnt/c/Users/tobia/Hearthstead-Claude/build-agent-uifilm#$B#g")
cd /root/hsmc-run
A=$B/moddev/clientRunProgramArgs.txt
case ${MODE:-sp} in
  mp) sed -e 's/^--quickPlaySingleplayer$/--quickPlayMultiplayer/' -e 's/^MC-Test$/localhost:25651/' $A > /root/hsmc-args.txt;;
  title) awk 'BEGIN{skip=0} /^--quickPlaySingleplayer$/{skip=1;next} skip==1{skip=0;next} {print}' $A > /root/hsmc-args.txt;;
  *) cp $A /root/hsmc-args.txt;;
esac
exec nice -n 10 /usr/lib/jvm/java-21-openjdk-amd64/bin/java -Xmx${XMX:-3g} \
  "-Dfml.modFolders=hearthstead%%$B/classes/java/main:hearthstead%%$B/resources/main" \
  @$B/moddev/clientRunVmArgs.txt -Dfile.encoding=UTF-8 -Duser.language=en \
  -cp "$CP" net.neoforged.devlaunch.Main @/root/hsmc-args.txt > /tmp/hsmc-client.log 2>&1
