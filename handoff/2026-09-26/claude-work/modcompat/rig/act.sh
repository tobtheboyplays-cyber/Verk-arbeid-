#!/bin/bash
# act.sh cmd "<chat command>" | key <keys> | click X Y | move X Y | drag X1 Y1 X2 Y2 | scroll X Y up|down N | shot NAME | wait S
export DISPLAY=:79
OUT=/mnt/c/Users/tobia/Hearthstead-Claude/modcompat/shots
mkdir -p $OUT
focus() { local w; w=$(xdotool search --name "Minecraft" | head -1); [ -n "$w" ] && xdotool windowfocus "$w" 2>/dev/null; }
while [ $# -gt 0 ]; do
  a=$1; shift
  case $a in
    cmd) focus; xdotool key t; sleep 0.5; xdotool type --delay 15 "$1"; sleep 0.1; xdotool key Return; sleep 0.5; shift;;
    key) focus; xdotool key $1; sleep 0.3; shift;;
    click) focus; xdotool mousemove $1 $2; sleep 0.15; xdotool click 1; sleep 0.3; shift 2;;
    use) focus; xdotool click 3; sleep 0.4;;
    rclick) focus; xdotool mousemove $1 $2; sleep 0.15; xdotool click 3; sleep 0.3; shift 2;;
    move) xdotool mousemove $1 $2; sleep 0.2; shift 2;;
    drag) focus; xdotool mousemove $1 $2 mousedown 1; for i in 1 2 3 4 5 6 7 8 9 10 11 12; do xdotool mousemove $(( $1 + ($3-$1)*i/12 )) $(( $2 + ($4-$2)*i/12 )); sleep 0.03; done; xdotool mouseup 1; sleep 0.3; shift 4;;
    scroll) focus; xdotool mousemove $1 $2; b=4; [ "$3" = down ] && b=5; for i in $(seq 1 $4); do xdotool click $b; sleep 0.25; done; shift 4;;
    shot) import -window root "$OUT/$1.png"; echo "$OUT/$1.png"; shift;;
    wait) sleep $1; shift;;
  esac
done
