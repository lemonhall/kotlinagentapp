#!/usr/bin/env python3
"""将 SVG 图标转换为 Android 所需的各尺寸 PNG"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

try:
    import cairosvg  # type: ignore
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "cairosvg"])
    import cairosvg  # type: ignore

SVG_PATH = Path("app/src/main/res/raw/ic_launcher_paw.svg")

# ic_launcher / ic_launcher_round 尺寸
LAUNCHER_SIZES: dict[str, int] = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# 自适应图标前景层尺寸
FOREGROUND_SIZES: dict[str, int] = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

RES_DIR = Path("app/src/main/res")


def main() -> int:
    svg_data = SVG_PATH.read_text(encoding="utf-8")

    # 前景层 SVG（去掉背景 rect）
    svg_foreground = re.sub(
        r"\s*<rect[^>]*fill=\"url\(#bg\)\"[^>]*/>\s*",
        "\n",
        svg_data,
        count=1,
    )

    for folder, size in LAUNCHER_SIZES.items():
        out_dir = RES_DIR / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        for name in ["ic_launcher.png", "ic_launcher_round.png"]:
            out_path = out_dir / name
            cairosvg.svg2png(
                bytestring=svg_data.encode("utf-8"),
                write_to=str(out_path),
                output_width=size,
                output_height=size,
            )
            print(f"  Generated {out_path} ({size}x{size})")

    for folder, size in FOREGROUND_SIZES.items():
        out_dir = RES_DIR / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / "ic_launcher_foreground.png"
        cairosvg.svg2png(
            bytestring=svg_foreground.encode("utf-8"),
            write_to=str(out_path),
            output_width=size,
            output_height=size,
        )
        print(f"  Generated {out_path} ({size}x{size})")

    print("Done!")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

