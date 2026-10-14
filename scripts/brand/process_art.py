#!/usr/bin/env python3
"""Turn the owner-run Codex art batch into Apollo web/app assets.

Input: the unchanged native outputs `<nn>-<id>.png` (see the art spec). Output:
derived WebP files; the originals are never modified.

Purple items (01-13) are re-inked to exactly one colour, #8D4BA3, on a
transparent background: the paper becomes alpha, so the same file reads as a
purple engraving on the light marble surfaces and glows like a negative on the
black dark theme (owner decision 2026-10-14: "purple lines on black").
Gold items (14+) are only resized and converted.

Website items go to website/assets/img, app-* items to app/src/main/res/drawable-nodpi.

Usage: process_art.py <codex-out dir> [--out website/assets/img]
Requires Pillow.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[2]
INK = (0x8D, 0x4B, 0xA3)
MAX_WIDTH = {"web-hero": 1400, "web-og": 1200, "app-landing": 900, "app-about-banner": 1080}
DEFAULT_WIDTH = 1000
APP_WIDTH = 720  # square app illustrations
APP_RES = ROOT / "app/src/main/res/drawable-nodpi"
NAME = re.compile(r"^(\d{2})-([a-z0-9-]+)\.png$")
PAPER_FLOOR = 10
SKIP = {"logo-gold-trippy-wide"}  # dropped by the owner (2026-10-13)


def ink_coverage(img: Image.Image) -> Image.Image:
    """0 = paper, 255 = full ink, from luminance with a slight level stretch."""
    gray = ImageOps.grayscale(img)
    gray = ImageOps.autocontrast(gray, cutoff=0.5)
    coverage = ImageOps.invert(gray)
    # Paper grain below this level would show as a faint box on coloured surfaces.
    return coverage.point(lambda v: 0 if v <= PAPER_FLOOR else round((v - PAPER_FLOOR) * 255 / (255 - PAPER_FLOOR)))


def colorize(coverage: Image.Image) -> Image.Image:
    """Solid #8D4BA3 whose alpha is the ink coverage."""
    ink = Image.new("RGBA", coverage.size, INK + (255,))
    ink.putalpha(coverage)
    return ink


def resize(img: Image.Image, item_id: str) -> Image.Image:
    width = MAX_WIDTH.get(item_id, DEFAULT_WIDTH)
    if img.width <= width:
        return img
    return img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("source", type=Path)
    parser.add_argument("--out", type=Path, default=ROOT / "website/assets/img")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    for path in sorted(args.source.glob("*.png")):
        match = NAME.match(path.name)
        if not match:
            continue
        number, item_id = int(match.group(1)), match.group(2)
        if item_id in SKIP:
            continue
        stem = f"{match.group(1)}-{item_id}"
        is_app = item_id.startswith("app-")
        img = Image.open(path).convert("RGB")
        if is_app and item_id not in MAX_WIDTH:
            img = img.resize((APP_WIDTH, round(img.height * APP_WIDTH / img.width)), Image.LANCZOS)
        else:
            img = resize(img, item_id)
        if number >= 14:  # gold emblem art: keep colours
            img.save(args.out / f"{stem}.webp", "WEBP", quality=88, method=6)
            print(f"{stem}.webp")
            continue
        art = colorize(ink_coverage(img))
        if is_app:  # Android resource name: art_<id>
            APP_RES.mkdir(parents=True, exist_ok=True)
            base = "art_" + item_id.removeprefix("app-").replace("-", "_")
            art.save(APP_RES / f"{base}.webp", "WEBP", quality=80, method=6)
            print(f"{base}.webp")
            continue
        if item_id == "web-og":  # social previews need an opaque image
            art = Image.alpha_composite(Image.new("RGBA", art.size, (0, 0, 0, 255)), art).convert("RGB")
        art.save(args.out / f"{stem}.webp", "WEBP", quality=82, method=6)
        print(f"{stem}.webp")


if __name__ == "__main__":
    main()
