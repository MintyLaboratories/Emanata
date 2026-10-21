"""Parametric generator for the Emanata launcher icon: a comic burst with a "!".

Emits, from one set of parameters:
  out/icon.svg                       full-bleed composed icon (108 x 108 dp canvas)
  out/layer_{background,foreground,monochrome}.svg
  out/res/drawable[-<density>]/ic_launcher_{background,foreground,monochrome}.xml
  out/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml
  previews/*.png                     contact sheets (with --previews; needs magick on PATH)

Layers follow the adaptive icon model: a 108 dp canvas whose inner 72 dp is visible
under the launcher's mask and whose central 66 dp circle is never clipped.
  background  backdrop + yellow burst with its red offset accent + shards
  foreground  burst outline + "!"
  monochrome  silhouette of burst and shards with the "!" knocked out
Low densities get fewer shards (see SHARDS_BY_DENSITY); a drawable-<density> folder is
written only where its layers differ from drawable/.
Spike lengths: the tail spike (tail_clock) reaches tail_reach, just inside the circle mask,
pointed (no miter limit) like a speech-bubble tail; the long spike (long_clock) reaches
long_reach; the rest shrink by spike_scale, then keep spike_len of their length past the valleys. Shards fill the valleys nearest shard_clocks, in that order.

Stdlib only. Run: py -3.14 -I emanata_icon.py [--previews] [--points 11 ...]
"""

import argparse
import math
import random
import shutil
import subprocess
import sys
from dataclasses import dataclass, fields
from pathlib import Path

CANVAS = 108.0
C = CANVAS / 2           # canvas center
SAFE_R = 33.0            # 66 dp safe zone radius: never clipped
VISIBLE_R = 36.0         # 72 dp visible area half-size

# Shard count kept per density bucket (None = all); fewer, larger details at low density.
SHARDS_BY_DENSITY = {"ldpi": 0, "mdpi": 3, "hdpi": 4, "xhdpi": None, "xxhdpi": None, "xxxhdpi": None}
# Launcher icon pixel size per density (48 dp).
PX_BY_DENSITY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


@dataclass
class Params:
    points: int = 9             # burst spikes
    radius: float = 29.0        # mean spike tip radius, dp
    jitter: float = 0.24        # spike radius / angle randomness, 0..1
    sharpness: float = 0.80     # 0 = blunt polygon, 1 = needle spikes
    seed: int = 3               # randomness seed (same seed, same burst)
    tip_max: float = 35.0       # outline (miter included) never reaches past this radius, dp
    spike_scale: float = 0.93   # radius factor for every spike but the tail and the long one
    spike_len: float = 0.667    # length beyond the neighboring valleys kept for those spikes
    tail_clock: float = 7.5     # clock position of the tail spike
    tail_reach: float = 35.5    # tail outline reach, dp; just inside the 36 dp circle mask
    long_clock: float = 1.0     # clock position of the long spike
    long_reach: float = 28.0    # 1 o'clock spike outline reach, dp; shorter pushes nearby valleys out
    shards: int = 6             # shard count at full density
    shard_clocks: str = "0.5,1.5,3,4,5.5,11"  # shard valleys by clock position, in order of preference
    shard_len: float = 8.0      # shard length, dp
    shard_thickness: float = 5.6  # shard base width, dp
    shard_max_r: float = 35.0   # keep shards inside this radius (visible area is 36)
    stroke: float = 3.0         # outline weight, dp
    miter: float = 6.0          # miter limit for spike tips
    excl: float = 34.0          # "!" height, dp
    excl_gap: float = 1.5       # room between the "!" and the burst's valleys, dp
    excl_tilt: float = 8.0      # "!" lean, degrees clockwise
    red_offset: float = 4.2     # red accent offset, dp
    red_angle: float = 205.0    # direction the red shows, degrees (0 = right, 90 = down)
    mono_r: float = 32.0        # monochrome silhouette fits this radius
    yellow: str = "#FFCC00"
    red: str = "#E8231B"
    ink: str = "#111111"
    backdrop: str = "#FFFFFF"


# ------------------------------------------------------------------ geometry

def polar(a, r, cx=C, cy=C):
    return (cx + r * math.cos(a), cy + r * math.sin(a))


def burst(p, clear=None):
    """
    Star polygon alternating spike tips and valleys; returns (points, valley indices).
    [clear](pt) is the distance from pt to the "!": each valley moves out until both of
    its edges keep stroke / 2 + excl_gap from it, so valleys beside the narrow "!" may
    dip deeper than those above and below it.
    """
    rng = random.Random(p.seed)
    n = p.points
    step = 2 * math.pi / n
    base = -math.pi / 2 + rng.uniform(-0.5, 0.5) * step
    inner = 1.0 - 0.6 * p.sharpness
    pts, valleys = [], []
    for i in range(n):
        a = base + i * step + rng.uniform(-0.22, 0.22) * step * p.jitter * 2.5
        r = p.radius * (1 + rng.uniform(-p.jitter, p.jitter * 1.4))
        pts.append(polar(a, r))
        a2 = base + (i + 0.5) * step + rng.uniform(-0.12, 0.12) * step
        r2 = p.radius * inner * (1 + rng.uniform(-p.jitter / 2, p.jitter / 2))
        valleys.append(len(pts))
        pts.append(polar(a2, r2))
    need = p.stroke / 2 + p.excl_gap

    def edges_clear(vi):
        for j in (vi - 1, (vi + 1) % len(pts)):
            (ax, ay), (bx, by) = pts[vi], pts[j]
            for k in range(11):
                t = k / 10
                if clear((ax + (bx - ax) * t, ay + (by - ay) * t)) < need:
                    return False
        return True

    tail = None

    def reach(i):
        return outline_reach(pts, i, p.stroke, math.inf if i == tail else p.miter)

    def settle(caps):
        for _ in range(4):
            if clear:
                for vi in valleys:
                    x, y = pts[vi]
                    a2, r2 = math.atan2(y - C, x - C), math.hypot(x - C, y - C)
                    while not edges_clear(vi) and r2 < p.tip_max:
                        r2 += 0.25
                        pts[vi] = polar(a2, r2)
            # Pull each tip in until its mitered outline stays within its cap. Stepped, not
            # solved: a tip blunted past the miter limit switches from bevel to miter.
            for i in range(0, len(pts), 2):
                x, y = pts[i]
                a, r = math.atan2(y - C, x - C), math.hypot(x - C, y - C)
                while reach(i) > caps[i] and r > 0:
                    r -= 0.05
                    pts[i] = polar(a, r)

    tips = range(0, len(pts), 2)
    settle({i: p.tip_max for i in tips})
    # Then reshape: every spike spike_scale of its reach, the tail and the long spike
    # pulled in from far out to their own reach.
    tail, long_ = special_tips(p, pts)
    special = {tail: p.tail_reach, long_: p.long_reach}
    def shortened(i):
        """spike_len of the tip's length beyond the mean radius of its two valleys."""
        v = sum(math.hypot(x - C, y - C) for x, y in (pts[i - 1], pts[(i + 1) % len(pts)])) / 2
        return v + (reach(i) * p.spike_scale - v) * p.spike_len

    caps = {i: special.get(i, shortened(i)) for i in tips}
    for i in special:
        x, y = pts[i]
        pts[i] = polar(math.atan2(y - C, x - C), special[i] + 10)
    settle(caps)
    return pts, valleys


def clock(pt):
    """Clock position of pt around the center: 0 at the top, 3 at the right."""
    return (math.degrees(math.atan2(pt[1] - C, pt[0] - C)) + 90) % 360 / 30


def clock_dist(a, b):
    d = abs(a - b) % 12
    return min(d, 12 - d)


def special_tips(p, pts):
    """Indices of the tail tip and the long tip: the tips nearest tail_clock and long_clock."""
    tips = range(0, len(pts), 2)
    return tuple(min(tips, key=lambda i: clock_dist(clock(pts[i]), c)) for c in (p.tail_clock, p.long_clock))


def outline_reach(poly, i, width, miter):
    """Distance from center to the outer edge of a stroke of [width] at vertex i (miter join)."""
    (px, py), a, b = poly[i], poly[i - 1], poly[(i + 1) % len(poly)]
    u = (a[0] - px, a[1] - py)
    v = (b[0] - px, b[1] - py)
    cos_t = (u[0] * v[0] + u[1] * v[1]) / (math.hypot(*u) * math.hypot(*v))
    half = math.acos(max(-1.0, min(1.0, cos_t))) / 2
    ratio = 1 / max(math.sin(half), 1e-6)
    ext = width / 2 * (ratio if ratio <= miter else 1.0)
    return math.hypot(px - C, py - C) + ext


def tail_tip(p, poly):
    """Kite filling the tail's stroke out to its full miter point, past the miter limit."""
    tail, _ = special_tips(p, poly)
    (px, py), a, b = poly[tail], poly[tail - 1], poly[(tail + 1) % len(poly)]
    u = unit((a[0] - px, a[1] - py))
    v = unit((b[0] - px, b[1] - py))
    w = p.stroke / 2

    def side(e, other):
        n = (-e[1], e[0])
        if n[0] * other[0] + n[1] * other[1] > 0:
            n = (-n[0], -n[1])
        return (px + n[0] * w, py + n[1] * w)

    bis = unit((u[0] + v[0], u[1] + v[1]))
    half = math.acos(max(-1.0, min(1.0, u[0] * v[0] + u[1] * v[1]))) / 2
    m = w / max(math.sin(half), 1e-6)
    return [(px, py), side(u, v), (px - bis[0] * m, py - bis[1] * m), side(v, u)]


def unit(vec):
    n = math.hypot(*vec)
    return (vec[0] / n, vec[1] / n)


def inside(pt, poly):
    x, y = pt
    hit = False
    for i in range(len(poly)):
        (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % len(poly)]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            hit = not hit
    return hit


def seg_dist(pt, a, b):
    (px, py), (ax, ay), (bx, by) = pt, a, b
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy or 1)))
    return math.hypot(px - ax - t * dx, py - ay - t * dy)


def clearance(pt, poly):
    """Distance from pt to the polygon edge, negative inside."""
    d = min(seg_dist(pt, poly[i], poly[(i + 1) % len(poly)]) for i in range(len(poly)))
    return -d if inside(pt, poly) else d


def shards(p, poly, valleys):
    """Wedges in the valleys nearest shard_clocks, tip toward the burst, in that order; valleys without room are skipped."""
    rng = random.Random(p.seed * 31 + 1)
    margin = p.stroke / 2 + 1.6
    wedges = {}
    for vi in valleys:
        vx, vy = poly[vi]
        a = math.atan2(vy - C, vx - C) + math.radians(rng.uniform(-6, 6))
        ux, uy = math.cos(a), math.sin(a)
        nx, ny = -uy, ux
        r0 = math.hypot(vx - C, vy - C)
        while r0 < p.shard_max_r:
            r1 = min(r0 + p.shard_len, p.shard_max_r)
            tip = (C + ux * r0, C + uy * r0)
            w = p.shard_thickness / 2
            b1 = (C + ux * r1 + nx * w, C + uy * r1 + ny * w)
            b2 = (C + ux * r1 - nx * w, C + uy * r1 - ny * w)
            mid1 = ((tip[0] + b1[0]) / 2, (tip[1] + b1[1]) / 2)
            mid2 = ((tip[0] + b2[0]) / 2, (tip[1] + b2[1]) / 2)
            if all(clearance(q, poly) >= margin for q in (tip, b1, b2, mid1, mid2)):
                if r1 - r0 >= p.shard_len * 0.55:
                    wedges[vi] = [tip, b1, b2]
                break
            r0 += 0.25
    out = []
    for c in (float(s) for s in p.shard_clocks.split(",")):
        vi = min(valleys, key=lambda v: clock_dist(clock(poly[v]), c))
        if clock_dist(clock(poly[vi]), c) > 0.75 or vi not in wedges:
            print(f"note: no shard fits near {c:g} o'clock", file=sys.stderr)
        elif wedges[vi] not in out:
            out.append(wedges[vi])
    return out[: p.shards]


def exclamation(p):
    """The "!": a tapered bar and a dot, leaning by excl_tilt; returns (bar, (cx, cy, r))."""
    h = p.excl
    dot_d = 0.27 * h
    gap = 0.09 * h
    top, bottom = -h / 2, h / 2 - dot_d - gap
    wt, wb = 0.36 * h, 0.15 * h
    t = math.radians(p.excl_tilt)
    ct, st = math.cos(t), math.sin(t)

    def place(x, y):
        return (C + x * ct - y * st, C + 1.0 + x * st + y * ct)

    bar = [place(-wt / 2, top), place(wt / 2, top), place(wb / 2, bottom), place(-wb / 2, bottom)]
    dx, dy = place(0, h / 2 - dot_d / 2)
    return bar, (dx, dy, dot_d / 2)


def translate(poly, dx, dy):
    return [(x + dx, y + dy) for x, y in poly]


def max_radius(polys):
    return max(math.hypot(x - C, y - C) for poly in polys for x, y in poly)


# ------------------------------------------------------------------ path data

def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def poly_d(poly):
    head, *rest = poly
    return f"M{f(head[0])},{f(head[1])}" + "".join(f"L{f(x)},{f(y)}" for x, y in rest) + "Z"


def circle_d(cx, cy, r):
    return (f"M{f(cx - r)},{f(cy)}A{f(r)},{f(r)} 0 1,0 {f(cx + r)},{f(cy)}"
            f"A{f(r)},{f(r)} 0 1,0 {f(cx - r)},{f(cy)}Z")


@dataclass
class Icon:
    burst: list
    yellow: list
    shards: list
    bar: list
    dot: tuple
    p: Params

    @property
    def excl_d(self):
        return poly_d(self.bar) + circle_d(*self.dot)


def excl_reach(bar, dot):
    return max(max_radius([bar]), math.hypot(dot[0] - C, dot[1] - C) + dot[2])


def build(p, shard_count=None):
    bar, dot = exclamation(p)
    def clear(pt):
        return min(clearance(pt, bar), math.hypot(pt[0] - dot[0], pt[1] - dot[1]) - dot[2])

    poly, valleys = burst(p, clear)
    a = math.radians(p.red_angle)
    yellow = translate(poly, -p.red_offset * math.cos(a), -p.red_offset * math.sin(a))
    sh = shards(p, poly, valleys)
    if shard_count is not None:
        sh = sh[:shard_count]
    return Icon(poly, yellow, sh, bar, dot, p)


# ------------------------------------------------------------------ SVG

def svg_background(ic):
    p = ic.p
    d = poly_d(ic.burst)
    sh = "".join(poly_d(s) for s in ic.shards)
    return (f'<rect width="108" height="108" fill="{p.backdrop}"/>'
            f'<clipPath id="burst"><path d="{d}"/></clipPath>'
            f'<g clip-path="url(#burst)"><path d="{d}" fill="{p.red}"/>'
            f'<path d="{poly_d(ic.yellow)}" fill="{p.yellow}"/></g>'
            + (f'<path d="{sh}" fill="{p.ink}"/>' if sh else ""))


def svg_foreground(ic):
    p = ic.p
    return (f'<path d="{poly_d(ic.burst)}" fill="none" stroke="{p.ink}" stroke-width="{f(p.stroke)}" '
            f'stroke-linejoin="miter" stroke-miterlimit="{f(p.miter)}"/>'
            f'<path d="{poly_d(tail_tip(p, ic.burst))}" fill="{p.ink}"/>'
            f'<path d="{ic.excl_d}" fill="{p.ink}"/>')


def body_reach(ic):
    """Farthest mitered tip outline, the tail left out."""
    p = ic.p
    tail, _ = special_tips(p, ic.burst)
    return max(outline_reach(ic.burst, i, p.stroke, p.miter) for i in range(0, len(ic.burst), 2) if i != tail)


def mono_scale(ic):
    """Fits the silhouette, tail aside, within mono_r."""
    p = ic.p
    reach = max(body_reach(ic), max_radius(ic.shards) if ic.shards else 0)
    return min(1.0, p.mono_r / reach)


def mono_paths(ic):
    """(silhouette d with the "!" as holes, shards d) in canvas units, before scaling."""
    return poly_d(ic.burst) + ic.excl_d, "".join(poly_d(s) for s in ic.shards)


def svg_monochrome(ic, color="#000000"):
    s = mono_scale(ic)
    body, sh = mono_paths(ic)
    tr = f"translate({f(C)},{f(C)}) scale({s:.4f}) translate({f(-C)},{f(-C)})"
    return (f'<g transform="{tr}"><path d="{body}" fill="{color}" fill-rule="evenodd"/>'
            f'<path d="{poly_d(ic.burst)}" fill="none" stroke="{color}" stroke-width="{f(ic.p.stroke)}" '
            f'stroke-linejoin="miter" stroke-miterlimit="{f(ic.p.miter)}"/>'
            f'<path d="{poly_d(tail_tip(ic.p, ic.burst))}" fill="{color}"/>'
            + (f'<path d="{sh}" fill="{color}"/>' if sh else "") + "</g>")


def svg_doc(body, size=None, view="0 0 108 108"):
    wh = f' width="{size}" height="{size}"' if size else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}"{wh}>{body}</svg>\n'


# ------------------------------------------------------------------ VectorDrawable

VD_HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- Generated by emanata_icon.py; edit the parameters, not this file. -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="108dp"\n    android:height="108dp"\n'
           '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')


def argb(hex_rgb):
    return "#FF" + hex_rgb.lstrip("#").upper()


def vd_path(d, fill=None, stroke=None, width=None, miter=None, even_odd=False, indent="    "):
    attrs = [f'android:pathData="{d}"']
    if fill:
        attrs.append(f'android:fillColor="{argb(fill)}"')
    if even_odd:
        attrs.append('android:fillType="evenOdd"')
    if stroke:
        attrs += [f'android:strokeColor="{argb(stroke)}"', f'android:strokeWidth="{f(width)}"',
                  'android:strokeLineJoin="miter"', f'android:strokeMiterLimit="{f(miter)}"']
    sep = "\n" + indent + "    "
    return f"{indent}<path{sep}" + sep.join(attrs) + " />\n"


def vd_background(ic):
    p = ic.p
    d = poly_d(ic.burst)
    out = VD_HEAD + vd_path("M0,0h108v108h-108z", fill=p.backdrop)
    out += ("    <group>\n"
            f'        <clip-path android:pathData="{d}" />\n'
            + vd_path(d, fill=p.red, indent="        ")
            + vd_path(poly_d(ic.yellow), fill=p.yellow, indent="        ")
            + "    </group>\n")
    if ic.shards:
        out += vd_path("".join(poly_d(s) for s in ic.shards), fill=p.ink)
    return out + "</vector>\n"


def vd_foreground(ic):
    p = ic.p
    return (VD_HEAD + vd_path(poly_d(ic.burst), stroke=p.ink, width=p.stroke, miter=p.miter)
            + vd_path(poly_d(tail_tip(p, ic.burst)), fill=p.ink)
            + vd_path(ic.excl_d, fill=p.ink) + "</vector>\n")


def vd_monochrome(ic):
    p = ic.p
    s = mono_scale(ic)
    body, sh = mono_paths(ic)
    out = (VD_HEAD + f'    <group\n        android:pivotX="54"\n        android:pivotY="54"\n'
           f'        android:scaleX="{s:.4f}"\n        android:scaleY="{s:.4f}">\n')
    out += vd_path(body, fill="#000000", even_odd=True, indent="        ")
    out += vd_path(poly_d(ic.burst), stroke="#000000", width=p.stroke, miter=p.miter, indent="        ")
    out += vd_path(poly_d(tail_tip(p, ic.burst)), fill="#000000", indent="        ")
    if sh:
        out += vd_path(sh, fill="#000000", indent="        ")
    return out + "    </group>\n</vector>\n"


ADAPTIVE = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
            '</adaptive-icon>\n')


# ------------------------------------------------------------------ previews

def mask_d(kind):
    """Launcher mask over the visible 72 dp square (18..90): circle or squircle (superellipse n=4)."""
    if kind == "circle":
        return circle_d(C, C, VISIBLE_R)
    n, pts = 4.0, []
    for i in range(256):
        t = 2 * math.pi * i / 256
        c, s = math.cos(t), math.sin(t)
        pts.append((C + VISIBLE_R * math.copysign(abs(c) ** (2 / n), c),
                    C + VISIBLE_R * math.copysign(abs(s) ** (2 / n), s)))
    return poly_d(pts)


THEMES = {  # tile, themed-icon background, themed-icon ink
    "light": ("#ECEFF4", "#D8E2FF", "#1A2C5B"),
    "dark": ("#1B1C1F", "#2B3A5E", "#D8E2FF"),
}


def icon_cell(ic, kind, mode, theme):
    """The masked visible area of the icon, in canvas units 18..90."""
    _, mono_bg, mono_ink = THEMES[theme]
    clip = f'<clipPath id="m{id(ic)}{kind}{mode}{theme}"><path d="{mask_d(kind)}"/></clipPath>'
    if mode == "color":
        content = svg_background(ic).replace('id="burst"', f'id="b{id(ic)}{kind}{theme}"') \
            .replace("url(#burst)", f"url(#b{id(ic)}{kind}{theme})") + svg_foreground(ic)
    else:
        content = f'<rect width="108" height="108" fill="{mono_bg}"/>' + svg_monochrome(ic, mono_ink)
    return clip + f'<g clip-path="url(#m{id(ic)}{kind}{mode}{theme})">{content}</g>'


def contact_sheet(variants, path):
    """Rows: mask x mode x theme; columns: launcher sizes, each from its density's variant."""
    sizes = [(48, "mdpi"), (72, "hdpi"), (96, "xhdpi"), (192, "xxxhdpi")]
    rows = [(k, m, t) for k in ("circle", "squircle") for m in ("color", "mono") for t in ("light", "dark")]
    pad, label_w, head_h = 24, 230, 40
    col_w = [s + 2 * pad for s, _ in sizes]
    row_h = max(s for s, _ in sizes) + 2 * pad
    width = label_w + sum(col_w)
    height = head_h + row_h * len(rows)
    body = [f'<rect width="{width}" height="{height}" fill="#FFFFFF"/>']
    font = 'font-family="Segoe UI, Arial, sans-serif" font-size="15"'
    x = label_w
    for (s, dens), w in zip(sizes, col_w):
        body.append(f'<text x="{x + w / 2}" y="26" text-anchor="middle" {font} fill="#333">{s} px · {dens}</text>')
        x += w
    for ri, (kind, mode, theme) in enumerate(rows):
        y0 = head_h + ri * row_h
        tile = THEMES[theme][0]
        body.append(f'<rect x="0" y="{y0}" width="{width}" height="{row_h}" fill="{tile}"/>')
        ink = "#333" if theme == "light" else "#DDD"
        body.append(f'<text x="14" y="{y0 + row_h / 2 + 5}" {font} fill="{ink}">'
                    f'{kind} · {"themed" if mode == "mono" else "color"} · {theme}</text>')
        x = label_w
        for (s, dens), w in zip(sizes, col_w):
            ic = variants[dens]
            ox, oy = x + (w - s) / 2, y0 + (row_h - s) / 2
            k = s / 72.0
            body.append(f'<g transform="translate({ox},{oy}) scale({k:.5f}) translate(-18,-18)">'
                        + icon_cell(ic, kind, mode, theme) + "</g>")
            x += w
    write_png("".join(body), path, width, height)


def keyline_sheet(ic, path, size=540):
    """Full-bleed layers at large size with the 72 dp visible and 66 dp safe circles marked."""
    guides = (f'<rect x="18" y="18" width="72" height="72" fill="none" stroke="#2979FF" stroke-width="0.35" stroke-dasharray="1.5,1"/>'
              f'<circle cx="54" cy="54" r="{SAFE_R}" fill="none" stroke="#00C853" stroke-width="0.35" stroke-dasharray="1.5,1"/>')
    panels = [
        svg_background(ic).replace("burst", "kb") + guides,
        f'<rect width="108" height="108" fill="#BDBDBD"/>' + svg_foreground(ic) + guides,
        f'<rect width="108" height="108" fill="#FFFFFF"/>' + svg_monochrome(ic) + guides,
        svg_background(ic).replace("burst", "kc") + svg_foreground(ic) + guides,
    ]
    k = size / 108.0
    body = ['<rect width="100%" height="100%" fill="#FFFFFF"/>']
    for i, panel in enumerate(panels):
        body.append(f'<g transform="translate({i * (size + 20)},0) scale({k:.5f})">{panel}</g>')
    write_png("".join(body), path, 4 * size + 60, size)


def write_png(body, path, width, height):
    svg = Path(path).with_suffix(".svg")
    svg.write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
                   f'viewBox="0 0 {width} {height}">{body}</svg>\n', encoding="utf-8")
    subprocess.run(["magick", "-background", "none", str(svg), str(path)], check=True)
    svg.unlink()


# ------------------------------------------------------------------ main

def report(ic):
    p = ic.p
    excl_r = excl_reach(ic.bar, ic.dot)
    burst_r = body_reach(ic)
    tail, long_ = special_tips(p, ic.burst)
    tail_r = outline_reach(ic.burst, tail, p.stroke, math.inf)
    long_r = outline_reach(ic.burst, long_, p.stroke, p.miter)
    shard_r = max_radius(ic.shards) if ic.shards else 0
    print(f"tail at {clock(ic.burst[tail]):.1f} o'clock reaches {tail_r:.1f} dp, "
          f"long spike at {clock(ic.burst[long_]):.1f} o'clock {long_r:.1f} dp")
    print(f'"!" reaches {excl_r:.1f} dp (safe zone {SAFE_R}), burst {burst_r:.1f} dp without the tail, '
          f"shards {shard_r:.1f} dp (visible {VISIBLE_R}), {len(ic.shards)} shards, "
          f"monochrome scale {mono_scale(ic):.3f}")
    if excl_r > SAFE_R:
        print('warning: "!" leaves the safe zone', file=sys.stderr)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for fld in fields(Params):
        ap.add_argument("--" + fld.name.replace("_", "-"), type=type(fld.default), default=fld.default)
    here = Path(__file__).resolve().parent
    ap.add_argument("--out", type=Path, default=here / "out")
    ap.add_argument("--previews", action="store_true", help="render contact sheets with magick")
    ap.add_argument("--preview-dir", type=Path, default=here / "previews")
    args = ap.parse_args(argv)
    p = Params(**{fld.name: getattr(args, fld.name) for fld in fields(Params)})

    full = build(p)
    report(full)
    out = args.out
    if out.exists():
        shutil.rmtree(out)
    (out / "res" / "mipmap-anydpi-v26").mkdir(parents=True)
    (out / "icon.svg").write_text(svg_doc(svg_background(full) + svg_foreground(full)), encoding="utf-8")
    (out / "layer_background.svg").write_text(svg_doc(svg_background(full)), encoding="utf-8")
    (out / "layer_foreground.svg").write_text(svg_doc(svg_foreground(full)), encoding="utf-8")
    (out / "layer_monochrome.svg").write_text(svg_doc(svg_monochrome(full)), encoding="utf-8")

    # drawable/ holds the full icon; a density folder only where its layers differ from it.
    def layers(ic):
        return {"ic_launcher_background.xml": vd_background(ic),
                "ic_launcher_foreground.xml": vd_foreground(ic),
                "ic_launcher_monochrome.xml": vd_monochrome(ic)}

    default = layers(full)
    written = []
    variants = {}
    for dens, count in [("", None)] + list(SHARDS_BY_DENSITY.items()):
        ic = build(p, count)
        if dens:
            variants[dens] = ic
        files = layers(ic)
        if dens and files == default:
            continue
        d = out / "res" / ("drawable-" + dens if dens else "drawable")
        d.mkdir()
        for name, xml in files.items():
            (d / name).write_text(xml, encoding="utf-8")
        written.append(d.name)
    print("drawables: " + ", ".join(written))
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (out / "res" / "mipmap-anydpi-v26" / name).write_text(ADAPTIVE, encoding="utf-8")
    print(f"wrote {out}")

    if args.previews:
        pv = args.preview_dir
        pv.mkdir(parents=True, exist_ok=True)
        contact_sheet(variants, pv / "contact_sheet.png")
        keyline_sheet(full, pv / "keylines.png")
        subprocess.run(["magick", "-background", "none", "-density", "480", str(out / "icon.svg"),
                        "-resize", "512x512", str(pv / "icon_512_fullbleed.png")], check=True)
        print(f"wrote {pv}")


if __name__ == "__main__":
    main()
