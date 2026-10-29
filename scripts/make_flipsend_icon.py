#!/usr/bin/env python3
"""Build FlipSend launcher/tray icons from a true black-and-white mark.

Pixels are hard-thresholded to #000 / #FFF plus fully transparent only.
A gray mark was rejected: the fill must be pure black.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
MASTER = ROOT / "docs/play-assets/icon-1024-bw.png"


def hard_bw(im: Image.Image) -> Image.Image:
    src = im.convert("RGBA")
    pix = src.load()
    w, h = src.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = pix[x, y]
            if a < 128:
                pix[x, y] = (0, 0, 0, 0)
                continue
            if (r + g + b) / 3 >= 128:
                pix[x, y] = (255, 255, 255, 255)
            else:
                pix[x, y] = (0, 0, 0, 255)
    return src


def squircle(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], fill) -> None:
    x0, y0, x1, y1 = box
    radius = int((x1 - x0) * 0.225)
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def stroke_round_rect(draw: ImageDraw.ImageDraw, box, radius: int, width: int, fill) -> None:
    draw.rounded_rectangle(box, radius=radius, outline=fill, width=width)


def draw_mark(size: int, *, background: bool) -> Image.Image:
    """Phone → PC mark matching the suite FlipSend avatar."""
    s = 2048
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if background:
        squircle(d, (0, 0, s - 1, s - 1), (0, 0, 0, 255))

    white = (255, 255, 255, 255)
    w = 72

    # Phone (portrait), left — capsule outline + home bar.
    phone = (400, 520, 760, 1528)
    stroke_round_rect(d, phone, radius=150, width=w, fill=white)
    d.rounded_rectangle((500, 1376, 660, 1432), radius=28, fill=white)

    # Arrow phone → PC (line + chevron, not a filled blob).
    r = w // 2

    def fat_line(a, b):
        d.line((a, b), fill=white, width=w)
        for x, y in (a, b):
            d.ellipse((x - r, y - r, x + r, y + r), fill=white)

    fat_line((790, 1024), (1040, 1024))
    fat_line((980, 940), (1110, 1024))
    fat_line((980, 1108), (1110, 1024))

    # Display (landscape rounded rect) with a short stand.
    screen = (1140, 680, 1700, 1180)
    stroke_round_rect(d, screen, radius=90, width=w, fill=white)
    d.line((1420, 1180, 1420, 1320), fill=white, width=w)
    d.rounded_rectangle((1270, 1310, 1570, 1380), radius=36, fill=white)

    out = im.resize((size, size), Image.Resampling.LANCZOS)
    return hard_bw(out)


def circle_mask(im: Image.Image) -> Image.Image:
    w, h = im.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, w - 1, h - 1), fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(im, (0, 0))
    out.putalpha(mask)
    return hard_bw(out)


def write_png(path: Path, im: Image.Image) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "PNG")


def write_ico(path: Path, master: Image.Image) -> None:
    import io
    import struct

    sizes = [16, 32, 48, 64, 256]
    payloads = []
    for n in sizes:
        buf = io.BytesIO()
        hard_bw(master.resize((n, n), Image.Resampling.LANCZOS)).save(buf, format="PNG")
        payloads.append((n, buf.getvalue()))
    offset = 6 + 16 * len(payloads)
    out = bytearray()
    out += struct.pack("<HHH", 0, 1, len(payloads))
    for n, data in payloads:
        # 256 is stored as 0 in the ICO directory.
        dim = 0 if n >= 256 else n
        out += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    for _, data in payloads:
        out += data
    path.write_bytes(out)


def main() -> None:
    master = draw_mark(1024, background=True)
    write_png(MASTER, master)

    res = ROOT / "android/stellarsend/src/main/res"
    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for name, px in densities.items():
        icon = hard_bw(master.resize((px, px), Image.Resampling.LANCZOS))
        write_png(res / f"mipmap-{name}/ic_launcher.png", icon)
        write_png(res / f"mipmap-{name}/ic_launcher_round.png", circle_mask(icon))

    # Adaptive foreground: white mark only, inset into the 66% safe zone.
    fg_canvas = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    glyph = draw_mark(288, background=False)
    fg_canvas.paste(glyph, ((432 - 288) // 2, (432 - 288) // 2), glyph)
    write_png(res / "drawable/ic_launcher_foreground.png", hard_bw(fg_canvas))

    write_png(ROOT / "docs/play-assets/icon-512.png", hard_bw(master.resize((512, 512), Image.Resampling.LANCZOS)))
    win = hard_bw(master.resize((256, 256), Image.Resampling.LANCZOS))
    write_png(ROOT / "desktop/src/StellarSend.App/Assets/icon.png", win)
    write_ico(ROOT / "desktop/src/StellarSend.App/Assets/icon.ico", master)
    print("wrote", MASTER)


if __name__ == "__main__":
    main()
