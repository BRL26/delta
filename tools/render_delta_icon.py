#!/usr/bin/env python3
"""Render the Delta launcher icon to the legacy mipmap bitmaps.

Adaptive icons cover API 26+, but the app's minSdk is 24, and those two releases take
the PNG/WebP in mipmap-*/ instead -- which is why they would otherwise still be showing
the old artwork. This draws the same mark the adaptive foreground uses, on the same
background colour, so the icon does not change shape when a device crosses that line.

The triangle's geometry is the 24-unit one from ic_delta_symbol.xml scaled up and
centred, matching ic_launcher_foreground.xml.
"""
import pathlib
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
RES = REPO / "app/src/main/res"

# Same fill as ic_launcher_background.xml, so the legacy icon and the adaptive one match.
BG = "#3DDC84"
FG = "#FFFFFF"

# Launcher icon sizes per density bucket, in dp-scaled pixels.
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

# Triangle in a 108-unit canvas, identical to ic_launcher_foreground.xml, including the
# upward nudge that corrects for the mass of a hollow triangle sitting below its box.
APEX = (54, 24)
LEFT = (27, 73)
RIGHT = (81, 73)


def svg(size: int, round_icon: bool) -> str:
    if round_icon:
        # The launcher masks the legacy round icon far less than the adaptive one, so the
        # background is inset to a circle rather than left as a full bleed square.
        inset = size * 0.02
        background = (
            f'<circle cx="{size / 2}" cy="{size / 2}" r="{size / 2 - inset}" fill="{BG}"/>'
        )
    else:
        background = f'<rect width="{size}" height="{size}" fill="{BG}"/>'

    scale = size / 108.0

    def pt(p):
        return f"{p[0] * scale:.2f},{p[1] * scale:.2f}"

    # Weights carried over from the in-app mark: thin left, heavier right.
    thin = 1.6 * scale
    thick = 3.2 * scale

    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {size} {size}">
  {background}
  <g fill="none" stroke="{FG}" stroke-linejoin="round">
    <line x1="{APEX[0] * scale}" y1="{APEX[1] * scale}" x2="{LEFT[0] * scale}" y2="{LEFT[1] * scale}"
          stroke-width="{thin:.2f}" stroke-linecap="round"/>
    <line x1="{LEFT[0] * scale}" y1="{LEFT[1] * scale}" x2="{RIGHT[0] * scale}" y2="{RIGHT[1] * scale}"
          stroke-width="{thin:.2f}" stroke-linecap="round"/>
    <line x1="{RIGHT[0] * scale}" y1="{RIGHT[1] * scale}" x2="{APEX[0] * scale}" y2="{APEX[1] * scale}"
          stroke-width="{thick:.2f}" stroke-linecap="butt"/>
  </g>
</svg>
"""


def main() -> int:
    tmp = pathlib.Path("/tmp/opencode/delta-icon")
    tmp.mkdir(parents=True, exist_ok=True)

    written = []
    for density, size in DENSITIES.items():
        out_dir = RES / f"mipmap-{density}"
        if not out_dir.is_dir():
            print(f"skip {density}: no {out_dir.name}", file=sys.stderr)
            continue
        for name, is_round in (("ic_launcher", False), ("ic_launcher_round", True)):
            src = tmp / f"{density}-{name}.svg"
            src.write_text(svg(size, is_round))
            dst = out_dir / f"{name}.webp"
            # rsvg does the vector rasterising; magick does the webp encode.
            png = tmp / f"{density}-{name}.png"
            subprocess.run(
                ["rsvg-convert", "-w", str(size), "-h", str(size), "-o", str(png), str(src)],
                check=True,
            )
            subprocess.run(
                ["magick", str(png), "-quality", "95", str(dst)],
                check=True,
            )
            written.append(dst)

    for path in written:
        print(path.relative_to(REPO), path.stat().st_size, "bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
