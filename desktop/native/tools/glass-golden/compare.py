"""Compares the native glass with the Tauri one rendered by Chromium over the same backdrop.

    python3 compare.py <reference.png> <native.png> [x0 y0 x1 y1]

Prints the mean absolute difference (0..255) and the share of pixels off by more than 12, and
writes compare.png (reference, native, difference x6) next to the native image.
"""
import sys
from pathlib import Path
from PIL import Image, ImageChops

ref = Image.open(sys.argv[1]).convert("RGB")
nat = Image.open(sys.argv[2]).convert("RGB")
box = tuple(int(v) for v in sys.argv[3:7]) if len(sys.argv) >= 7 else (0, 265, ref.width, 400)
r, n = ref.crop(box), nat.crop(box)
d = ImageChops.difference(r, n)
px = list(d.get_flattened_data() if hasattr(d, "get_flattened_data") else d.getdata())
mean = sum(sum(p) for p in px) / len(px) / 3
off = sum(1 for p in px if max(p) > 12) / len(px) * 100
print(f"mean abs diff {mean:.2f}/255, pixels off by >12: {off:.1f}%")
w, h = r.size
out = Image.new("RGB", (w, h * 3))
out.paste(r, (0, 0)); out.paste(n, (0, h)); out.paste(d.point(lambda v: min(255, v * 6)), (0, 2 * h))
out.save(Path(sys.argv[2]).with_name("compare.png"))
