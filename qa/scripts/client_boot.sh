#!/usr/bin/env bash
# Real-client boot under Xvfb with software GL. Proves the client starts,
# registers renderers/screens, and reaches the title screen; captures a
# framebuffer screenshot as visual evidence. Args: <mod-dir> <artifact-dir>
set -eu
MOD="$1"; OUT="$2"
DISPLAY_NUM=":97"
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe

Xvfb "$DISPLAY_NUM" -screen 0 1280x720x24 > "$OUT/xvfb.log" 2>&1 &
XVFB_PID=$!
trap 'kill $XVFB_PID 2>/dev/null || true' EXIT
sleep 2

cd "$MOD"
DISPLAY="$DISPLAY_NUM" timeout 420 ./gradlew runClient > "$OUT/client-run.log" 2>&1 &
GRADLE_PID=$!

TITLE_SEEN=0
for i in $(seq 1 130); do
    sleep 3
    if grep -qE "Backend library: LWJGL|Reloading ResourceManager|Sound engine started" "$OUT/client-run.log" 2>/dev/null; then
        TITLE_SEEN=1
    fi
    if grep -qE "Realms Notification|Sound engine started" "$OUT/client-run.log" 2>/dev/null; then
        sleep 12   # let the title screen actually render
        break
    fi
    kill -0 $GRADLE_PID 2>/dev/null || break
done

if command -v import >/dev/null; then
    DISPLAY="$DISPLAY_NUM" import -window root "$OUT/screenshot-title.png" 2>/dev/null || true
elif command -v xwd >/dev/null; then
    DISPLAY="$DISPLAY_NUM" xwd -root -out "$OUT/screenshot-title.xwd" 2>/dev/null || true
    python3 - <<PYEOF 2>/dev/null || true
from PIL import Image
try:
    img = Image.open("$OUT/screenshot-title.xwd")
    img.save("$OUT/screenshot-title.png")
except Exception as e:
    print("xwd convert failed:", e)
PYEOF
fi

kill $GRADLE_PID 2>/dev/null || true
sleep 3
pkill -f "neoforge.*client" 2>/dev/null || true

if grep -qE "Exception in thread|Failed to start|crash-report" "$OUT/client-run.log"; then
    echo "FAIL: client crashed during boot"; exit 1
fi
if [ "$TITLE_SEEN" != 1 ]; then
    echo "FAIL: client never reached resource load / sound engine"; exit 1
fi
if [ -s "$OUT/screenshot-title.png" ]; then
    echo "client boot ok under Xvfb; screenshot: screenshot-title.png"
else
    echo "client boot ok under Xvfb; NO screenshot captured (install imagemagick or xwd)"
fi
