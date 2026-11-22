#!/usr/bin/env python3
"""Render the approved 100-unit rhythm ring, oversampled with opaque RGB output."""
import math
from pathlib import Path
from PIL import Image, ImageDraw

SIZE = 4096
SCALE = SIZE / 100
GROUND = (11, 18, 22)
image = Image.new("RGB", (SIZE, SIZE), GROUND)
draw = ImageDraw.Draw(image)
def box(values):
    return tuple(round(v * SCALE) for v in values)

grid = tuple(round(a * .93 + b * .07) for a, b in zip(GROUND, (89, 227, 200)))
for i in range(17):
    position = round(i * 6.25 * SCALE)
    draw.line((position, 0, position, SIZE), fill=grid, width=round(.3 * SCALE))
    draw.line((0, position, SIZE, position), fill=grid, width=round(.3 * SCALE))
# Filled annular sectors have flat caps like the SVG's dashed strokes.
def arc(start, length, color):
    angles = [start / 30 - math.pi / 2 + (length / 30) * i / 512 for i in range(513)]
    points = [(round((50 + r * math.cos(a)) * SCALE), round((50 + r * math.sin(a)) * SCALE))
              for r, sequence in [(34.5, angles), (25.5, angles[::-1])] for a in sequence]
    draw.polygon(points, fill=color)

background = image.copy()
draw.ellipse(box((15.5, 15.5, 84.5, 84.5)), fill="#1A272D")
# Ring inner radius is 25.5. Preserve the grid inside the ring.
hole = Image.new("L", image.size)
ImageDraw.Draw(hole).ellipse(box((24.5, 24.5, 75.5, 75.5)), fill=255)
image.paste(background, mask=hole)
draw = ImageDraw.Draw(image)
for cycle in range(3):
    arc(cycle * 62.83, 38.9, "#FFB648")
    arc(cycle * 62.83 + 41.4, 18.93, "#2FB9A5")
diamond = Image.new("RGBA", (round(20 * SCALE), round(20 * SCALE)))
ImageDraw.Draw(diamond).rounded_rectangle(box((3, 3, 17, 17)), radius=round(1.5 * SCALE), fill="#E9F1EF")
diamond = diamond.rotate(45, resample=Image.Resampling.BICUBIC)
image.paste(diamond, (round(40 * SCALE), round(40 * SCALE)), diamond)
output = Path(__file__).resolve().parents[1] / "Cadence/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
image.resize((1024, 1024), Image.Resampling.LANCZOS).save(output)
