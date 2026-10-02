"""Derives the GUI navigation icons from the coin texture: a bold green "A" (go to the
BUY interface) and a bold red "V" (go to the SELL interface) in the bottom-right corner."""
import pathlib
from PIL import Image

TEX = pathlib.Path(__file__).resolve().parent.parent / "resourcepack/assets/vanillaeco/textures/item"

GLYPHS = {
    "A": ["..XXXX..",
          ".XXXXXX.",
          "XXX..XXX",
          "XX....XX",
          "XX....XX",
          "XXXXXXXX",
          "XXXXXXXX",
          "XX....XX",
          "XX....XX",
          "XX....XX"],
    "V": ["XX....XX",
          "XX....XX",
          "XX....XX",
          "XX....XX",
          "XXX..XXX",
          ".XX..XX.",
          ".XXXXXX.",
          "..XXXX..",
          "..XXXX..",
          "...XX..."],
}

# (fill, highlight for the top rows, outline)
COLORS = {
    "achat": ("A", (64, 200, 64), (128, 240, 120), (16, 56, 16)),
    "vente": ("V", (216, 40, 40), (250, 110, 100), (64, 8, 8)),
}


# The 48px texture is shown at 16px in the GUI: glyph pixels are 2x2 and the outline
# 2px thick so the letter stays readable (about the size of a vanilla stack count).
SCALE, BORDER = 2, 2


def stamp(base, glyph, fill, light, outline):
    img = base.copy()
    rows = GLYPHS[glyph]
    h, w = len(rows) * SCALE, len(rows[0]) * SCALE
    # glyph + outline flush with the bottom-right corner of the texture
    ox, oy = img.width - w - BORDER, img.height - h - BORDER
    on = {(x, y) for y in range(h) for x in range(w) if rows[y // SCALE][x // SCALE] == "X"}
    ring = range(-BORDER, BORDER + 1)
    for x, y in {(x + dx, y + dy) for x, y in on for dx in ring for dy in ring} - on:
        img.putpixel((ox + x, oy + y), outline + (255,))
    for x, y in on:
        img.putpixel((ox + x, oy + y), (light if y < 2 * SCALE else fill) + (255,))
    return img


coin = Image.open(TEX / "piece.png").convert("RGBA")
for name, (glyph, fill, light, outline) in COLORS.items():
    stamp(coin, glyph, fill, light, outline).save(TEX / f"piece_{name}.png")
    print(f"piece_{name}.png")
