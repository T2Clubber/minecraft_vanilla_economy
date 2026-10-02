"""Converts the 1024x1024 AI-rendered coin (50x50 pixel-art grid, checkerboard baked
into the JPEG) into a transparent 48x48 item texture."""
import sys
from statistics import median
from PIL import Image

SRC, DST, GRID, OUT = sys.argv[1], sys.argv[2], 50, 48

im = Image.open(SRC).convert("RGB")
cell = im.size[0] / GRID
px = im.load()

def sample(cx, cy):
    # median of the cell centre (avoids JPEG ringing on cell borders)
    x0, y0 = int(cx * cell + cell * 0.3), int(cy * cell + cell * 0.3)
    x1, y1 = int(cx * cell + cell * 0.7), int(cy * cell + cell * 0.7)
    pts = [px[x, y] for x in range(x0, x1) for y in range(y0, y1)]
    return tuple(int(median(c[i] for c in pts)) for i in range(3))

def is_grey(c):
    # checkerboard cells are neutral greys (~75 and ~197); the coin is always saturated
    return max(c) - min(c) < 14

grid = [[sample(x, y) for x in range(GRID)] for y in range(GRID)]

# flood fill from the borders through grey cells = background
background, stack = set(), [(x, y) for x in range(GRID) for y in (0, GRID - 1)] + \
    [(x, y) for y in range(GRID) for x in (0, GRID - 1)]
while stack:
    x, y = stack.pop()
    if (x, y) in background or not (0 <= x < GRID and 0 <= y < GRID) or not is_grey(grid[y][x]):
        continue
    background.add((x, y))
    stack += [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]
coin = [(x, y) for y in range(GRID) for x in range(GRID) if (x, y) not in background]
xs, ys = [p[0] for p in coin], [p[1] for p in coin]
x0, y0, w, h = min(xs), min(ys), max(xs) - min(xs) + 1, max(ys) - min(ys) + 1
print(f"coin bbox {w}x{h} at ({x0},{y0}), {len(coin)} opaque pixels")

out = Image.new("RGBA", (OUT, OUT), (0, 0, 0, 0))
ox, oy = (OUT - w) // 2, (OUT - h) // 2
for x, y in coin:
    out.putpixel((x - x0 + ox, y - y0 + oy), grid[y][x] + (255,))
out.save(DST)
