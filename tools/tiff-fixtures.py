"""Writes the TIFF decoder's test fixtures with ImageMagick (libtiff), an encoder independent of the app.

Run by hand from the repository root when the fixture set changes: py tools/tiff-fixtures.py
Patterns are formulas the tests recompute (TiffTest), so no reference images are stored.
"""
import pathlib
import subprocess
import tempfile

OUT = pathlib.Path("app/src/test/resources/tiff")
W, H = 37, 23


def rgb(x, y):
    return (x * 6) & 0xFF, (y * 10) & 0xFF, (x * 3 + y * 5) & 0xFF


def busy(x, y):
    """Enough variety for LZW codes to reach 12 bits and clear the table."""
    return (x * x + y * 7) & 0xFF, (x * y + 3 * x) & 0xFF, (x ^ (y * 3)) & 0xFF


def gray(x, y):
    return x * 4 + y * 4


def ppm(path, w, h, pixel):
    body = bytearray()
    for y in range(h):
        for x in range(w):
            body += bytes(pixel(x, y))
    path.write_bytes(b"P6 %d %d 255\n" % (w, h) + body)


def pgm(path, w, h, pixel):
    body = bytes(pixel(x, y) for y in range(h) for x in range(w))
    path.write_bytes(b"P5 %d %d 255\n" % (w, h) + body)


def magick(*args):
    subprocess.run(["magick", *map(str, args)], check=True)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        src = tmp / "rgb.ppm"
        ppm(src, W, H, rgb)
        g = tmp / "gray.pgm"
        pgm(g, W, H, gray)
        b = tmp / "busy.ppm"
        ppm(b, 128, 96, busy)
        # Four colors in quadrants, for a palette image.
        quad = tmp / "quad.ppm"
        colors = [(200, 30, 30), (30, 200, 30), (30, 30, 200), (240, 240, 40)]
        ppm(quad, W, H, lambda x, y: colors[(x >= W // 2) + 2 * (y >= H // 2)])
        # Left half black, right half white.
        half = tmp / "half.pgm"
        pgm(half, W, H, lambda x, y: 255 if x >= W // 2 else 0)
        strip = ["-define", "tiff:rows-per-strip=5"]
        tile = ["-define", "tiff:tile-geometry=16x16"]
        pred = ["-define", "tiff:predictor=2"]
        jobs = {
            "rgb-none-strips.tif": [src, "-endian", "LSB", "-compress", "None", *strip],
            "rgb-lzw-predictor-msb.tif": [src, "-endian", "MSB", "-compress", "LZW", *pred, *strip],
            "rgb-packbits.tif": [src, "-compress", "RLE", *strip],
            "rgb-deflate-tiles.tif": [src, "-compress", "Zip", *tile],
            "rgb-lzw-planar.tif": [src, "-interlace", "Plane", "-compress", "LZW", *strip],
            "rgb16-none-tiles-msb.tif": [src, "-depth", "16", "-endian", "MSB", "-compress", "None", *tile],
            "rgba-deflate.tif": [src, "-alpha", "set", "-channel", "A", "-evaluate", "set", "50%", "+channel",
                                 "-define", "tiff:alpha=unassociated", "-compress", "Zip"],
            "gray8-deflate-predictor.tif": [g, "-compress", "Zip", *pred, *strip],
            "gray16-lzw-msb.tif": [g, "-depth", "16", "-endian", "MSB", "-compress", "LZW"],
            "palette-lzw.tif": [quad, "-type", "Palette", "-compress", "LZW"],
            "bilevel-packbits.tif": [half, "-type", "Bilevel", "-depth", "1", "-compress", "RLE"],
            "busy-lzw.tif": [b, "-compress", "LZW", "-define", "tiff:predictor=1"],
            "jpeg-rgb.tif": [src, "-compress", "JPEG", "-quality", "95", *strip],
            "jpeg-ycbcr.tif": [src, "-colorspace", "YCbCr", "-compress", "JPEG", "-quality", "95", *strip],
            "jpeg-tiles.tif": [src, "-compress", "JPEG", "-quality", "95", *tile],
        }
        for name, args in jobs.items():
            magick(*args, OUT / name)
        magick(src, "-compress", "LZW", "TIFF64:" + str(OUT / "rgb-lzw-bigtiff.tif"))
        # Two pages: the decoder shows the first.
        magick(src, quad, "-compress", "Zip", OUT / "two-pages.tif")


if __name__ == "__main__":
    main()
