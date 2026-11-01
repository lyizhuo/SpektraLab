"""Launcher icons from the Figma node `53:4` (玻璃质感胶卷图标 1).

Run from anywhere; it reads the 175 px render the MCP produced and writes the
five density buckets Android expects. The source is the composited node (black
tile, glass film roll), not the 2048 JPEG the design context returns: that one
carries a white background the node does not have, and keying white out of a
glass render eats the highlights that make the icon.
"""

from __future__ import annotations

import os
import pathlib
from PIL import Image

SOURCE = pathlib.Path(os.environ["TEMP"]) / "latent-icon.png"
RES = pathlib.Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
BUCKETS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def main() -> int:
    if not SOURCE.exists():
        print(f"missing {SOURCE}")
        return 1
    src = Image.open(SOURCE).convert("RGBA")
    for bucket, px in BUCKETS.items():
        target = RES / f"mipmap-{bucket}"
        target.mkdir(parents=True, exist_ok=True)
        icon = src.resize((px, px), Image.LANCZOS)
        icon.save(target / "ic_launcher.png")
        icon.save(target / "ic_launcher_round.png")
        print(f"{bucket}: {px}px")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
