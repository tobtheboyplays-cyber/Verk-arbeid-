#!/bin/bash
# setup.sh <pack> : fresh disposable world copy + mods for the pack into the private game dir /root/hsmc-run
# packs: base | atmosphere | atmosphere-noshader | building | content | all
set -e
P=$1; G=/root/hsmc-run; M=/mnt/c/Users/tobia/Hearthstead-Claude/modcompat
mkdir -p $G/saves $G/mods $G/resourcepacks $G/shaderpacks $G/config
rm -rf $G/saves/MC-Test; cp -r /mnt/c/Users/tobia/Hearthstead-Claude/world-copies/server-20260925 $G/saves/MC-Test; rm -f $G/saves/MC-Test/session.lock
rm -f $G/mods/*.jar
atm="voicechat sound-physics AmbientSounds CreativeCore sodium iris entity_model entity_texture"
bld="mcw- supplementaries moonlight"
con="FarmersDelight BrewinAndChewin SereneSeasons GlitchCore"
case $P in
  base|vanilla-mp) sel="";;
  friends) sel="";; atmosphere|atmosphere-noshader) sel="$atm";; building) sel="$bld";; content) sel="$con";; all) sel="$atm $bld $con";;
esac
for s in $sel; do cp $M/mods/$s*.jar $G/mods/; done
T=/mnt/c/Users/tobia/Hearthstead-Claude/mods-test
[ "$P" = friends ] && cp $T/*.jar $G/mods/ && cp $T/*.zip $G/resourcepacks/
[ "$P" = friends-nodf ] && cp $T/*.jar $G/mods/ && rm -f $G/mods/DiagonalFences* $G/mods/PuzzlesLib* && cp $T/*.zip $G/resourcepacks/
cp -n $M/packs/FreshAnimations_*.zip $G/resourcepacks/ 2>/dev/null || true
cp -n $M/packs/ComplementaryReimagined_*.zip $G/shaderpacks/ 2>/dev/null || true
RP='["vanilla","mod_resources"]'
case $P in friends*) RP='["vanilla","mod_resources","file/FreshAnimations_v1.10.4.zip","file/FA+All_Extensions-v1.8.1.zip","file/Brays-FA-ChestFix-v2.0.zip"]';; esac
case $P in atmosphere*|all) RP='["vanilla","mod_resources","file/FreshAnimations_v1.10.4.zip"]';; esac
SH=false; case $P in atmosphere|all) SH=true;; esac
printf 'shaderPack=ComplementaryReimagined_r5.9.3.zip\nenableShaders=%s\n' $SH > $G/config/iris.properties
printf 'version:3955\nguiScale:0\nonboardAccessibility:false\nnarrator:0\ntutorialStep:none\nskipMultiplayerWarning:true\njoinedFirstServer:true\npauseOnLostFocus:false\nsoundCategory_master:0.0\nrenderDistance:8\nsimulationDistance:8\nmaxFps:260\nenableVsync:false\nresourcePacks:%s\n' "$RP" > $G/options.txt
echo "setup $P: $(ls $G/mods | tr '\n' ' ')"; cat $G/config/iris.properties
