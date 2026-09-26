"""Deterministically generates the 16x16 Work Scepter item sprite."""
from pathlib import Path

from PIL import Image


OUT = (Path(__file__).resolve().parents[1] / "src" / "main" / "resources"
       / "assets" / "hearthstead" / "textures" / "item"
       / "work_scepter.png")


def main() -> None:
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = image.load()

    # Dark silhouette: a diagonal oak shaft with a forged survey head.
    dark = (49, 30, 18, 255)
    oak = (111, 71, 36, 255)
    oak_light = (166, 111, 54, 255)
    copper = (145, 72, 43, 255)
    copper_light = (220, 132, 77, 255)
    gold = (232, 185, 72, 255)
    amethyst = (126, 82, 178, 255)
    amethyst_light = (218, 176, 245, 255)

    for x, y in [(3, 13), (4, 12), (5, 11), (6, 10), (7, 9),
                 (8, 8), (9, 7), (10, 6)]:
        px[x, y] = dark
        if x + 1 < 16:
            px[x + 1, y] = oak
        if y - 1 >= 0:
            px[x, y - 1] = oak_light
    for x, y in [(2, 14), (3, 14), (2, 13), (4, 13)]:
        px[x, y] = dark if (x + y) % 2 else oak

    # Copper fork, gold retaining collar and faceted amethyst survey focus.
    for x, y in [(9, 5), (10, 5), (11, 5), (12, 5), (9, 6),
                 (12, 6), (10, 7), (11, 7), (8, 6), (13, 6)]:
        px[x, y] = copper
    for x, y in [(10, 5), (11, 5), (9, 6), (12, 6)]:
        px[x, y] = copper_light
    for x, y in [(8, 7), (9, 7), (10, 8), (9, 8)]:
        px[x, y] = gold
    for x, y in [(10, 2), (11, 2), (9, 3), (10, 3), (11, 3),
                 (12, 3), (10, 4), (11, 4)]:
        px[x, y] = amethyst
    px[10, 2] = amethyst_light
    px[9, 3] = amethyst_light
    px[12, 4] = dark
    px[8, 4] = dark

    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT, optimize=True)


if __name__ == "__main__":
    main()
