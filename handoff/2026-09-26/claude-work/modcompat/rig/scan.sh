#!/bin/bash
# scan latest.log for mixin conflicts / errors
L=/root/hsmc-run/logs/latest.log
echo "== mods loaded"; grep -m1 -i "Found .* mods\|Loading .* mods" $L | cut -c1-200
echo "== mixin problems"; grep -iE "mixin.*(error|fail|conflict|could not|cannot)|InvalidInjection|MixinApplyError|@Redirect conflict|Critical injection" $L | sort | uniq -c | sort -rn | head -20 | cut -c1-300
echo "== ERROR lines (unique, top)"; grep -E "/ERROR\]|\[.*ERROR" $L | sed -E 's/^\[[^]]*\] //' | cut -c1-220 | sort | uniq -c | sort -rn | head -25
echo "== hearthstead warnings"; grep -iE "hearthstead.*(WARN|ERROR)|(WARN|ERROR).*hearthstead" $L | cut -c1-260 | sort | uniq -c | sort -rn | head -15
echo "== exceptions"; grep -E "Exception|Error:" $L | grep -v "^\s*at " | cut -c1-220 | sort | uniq -c | sort -rn | head -15
