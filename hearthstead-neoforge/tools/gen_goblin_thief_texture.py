"""Original low-noise cuboid material atlas; no imported reference pixels."""
from pathlib import Path
from PIL import Image

OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/hearthstead/textures/entity/raider/goblin_thief.png"
COLORS = ((107, 119, 65), (66, 48, 34), (105, 77, 45), (234, 194, 78),
          (115, 128, 73), (44, 34, 27), (151, 117, 67), (67, 57, 36))

image = Image.new("RGBA", (256, 128))
for y in range(128):
    for x in range(256):
        material = (y // 64) * 4 + x // 64
        color = COLORS[material]
        # Broad quiet pixel patches, not grain; eyes stay clear and luminous.
        shade = 0 if material == 3 else ((x // 3 * 7 + y // 4 * 11) % 5 - 2) * 3
        image.putpixel((x, y), tuple(max(0, min(255, c + shade)) for c in color) + (255,))
OUT.parent.mkdir(parents=True, exist_ok=True)
image.save(OUT)
print(OUT)
