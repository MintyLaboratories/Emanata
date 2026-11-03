"""Benchmarks the debug app on a device: synthetic books, scripted reading sessions, traces, energy.

Run from the repository root with Python 3.10+ (standard library only):

    py tools/bench/bench.py make --out <dir>                 # synthetic books (magick; cjxl, avifenc, 7z where found)
    py tools/bench/bench.py push --src <dir>                 # to the device's Download/emanata-test
    py tools/bench/bench.py fixtures --out <dir>             # JPEG XL decode fixtures (magick, cjxl, djxl)
    py tools/bench/bench.py run --plan <plan.json> --out <dir> [--trace] [--energy]
    py tools/bench/bench.py ab --a <a.apk> --b <b.apk> --plan <plan.json> --out <dir> [--rounds 6]
    py tools/bench/bench.py report <dir>/results.jsonl       # medians with spread
    py tools/bench/bench.py slices <trace.pftrace>           # app trace sections by total time (needs the perfetto package)

A plan is a JSON list of sessions, each the extras of BenchActivity (see its KDoc), e.g.
    [{"do": "read", "path": "/storage/emulated/0/Download/emanata-test/synth-2k-jpeg.cbz",
      "label": "2k jpeg", "layout": "paged", "turns": 10, "reps": 3}]
Results, traces and battery statistics go to --out; nothing is written to the device but the
pushed books, the app's own results file, and traces under /data/misc/perfetto-traces.

A session's "engine" (kotlin, native, gpu), "pixelthreads" (workers for pages shown),
"laterthreads" (for pages read ahead) and "pinned" ("on"/"off": workers on the large or little
cores) pick how pages are resampled, so one build A/Bs them (plans/engines.json interleaves the
setups per book). "jxl" ("native"/"coder") picks how JPEG XL pages decode (plans/jxl.json);
"jxllaterthreads", "jxllater" ("big"/"little") and "jxllaternice" (0-19) place native JPEG XL
decodes of pages read ahead (plans/jxl-later.json). "platformcores" ("any", "big", "prime") pins
the platform's one-thread decodes (JPEG, PNG, WebP), "platformahead" ("on"/"off") pins those of
pages read ahead too, "decoders" sets how many pages decode at
once, "preload" the pages read ahead and "seams" ("on"/"off") joining spreads by their seams,
which runs the seam scans (both settings, put back after the run); "behind" ("on"/"off")
prepares the screen behind or not (plans/behind.json).

`ab` installs two builds in turn (A B B A, A B B A, ...) and runs the plan on each, so drift in
temperature or background work falls on both alike; results carry "variant": "a" or "b". After
each install the build calibrates (per-device winners, CalibrationActivity) before its sessions;
`run` calibrates once before its first.
Sessions start with a warm-up (see BenchPlan.warmup) that reports leave out.

Time a release build: `./gradlew assembleBench`, then install app-bench.apk over the debug build
(same id and key, data kept). The debug build is debuggable, which slows the app's own code.

--energy fakes unplugged power (dumpsys battery unplug) and resets the battery statistics before
each session, then reads the app's estimated drain; the battery state is always reset after.
Estimates come from the device's power profile, not a meter: compare at least 5 sessions a side.

A session's CPU time runs until the reader's background work (page size and seam scans,
decodes) is done after its last turn ("settle"), so work a build defers still counts; decodes
besides the reader's own ("aside": seam sides, quick previews, sizes) are counted apart.
Each session records the battery level and current; `run` and `ab` stop below --min-battery
percent (default 20; 0 turns the check off), where a device may slow down to save power.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import re
import shlex
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

PACKAGE = "minty.emanata.debug"
BENCH = f"{PACKAGE}/com.infinity.comicreader.debug.BenchActivity"
DEVICE_DIR = "/storage/emulated/0/Download/emanata-test"
TRACE_DIR = "/data/misc/perfetto-traces"
RESULTS = f"/storage/emulated/0/Android/data/{PACKAGE}/files/bench/results.jsonl"
# Battery percent below which no session starts (--min-battery); 0 turns the check off.
MIN_BATTERY = 20
ADB = os.environ.get("ADB", "adb")

# ---------------------------------------------------------------- synthetic books

SIZES = {"2k": (2000, 3000), "4k": (4000, 6000)}


def page_art(w: int, h: int, seed: int) -> str:
    """MVG line art of one comic page: panels, shapes, speech bubbles, lines of 'text'."""
    rnd = random.Random(seed)
    s = w / 2000
    out = [f"viewbox 0 0 {w} {h}", "stroke-linejoin round"]
    margin, gutter = int(80 * s), int(40 * s)
    rows = rnd.randint(3, 4)
    y = margin
    row_h = (h - 2 * margin - (rows - 1) * gutter) // rows
    for _ in range(rows):
        cols = rnd.randint(1, 3)
        col_w = (w - 2 * margin - (cols - 1) * gutter) // cols
        x = margin
        for _ in range(cols):
            x2, y2 = x + col_w, y + row_h
            for _ in range(rnd.randint(4, 9)):
                cx, cy = rnd.randint(x, x2), rnd.randint(y, y2)
                r = rnd.randint(int(20 * s), int(160 * s))
                color = "#%02x%02x%02x" % (rnd.randint(0, 255), rnd.randint(0, 255), rnd.randint(0, 255))
                out.append(f"fill {color} stroke black stroke-width {max(1, int(4 * s))}")
                if rnd.random() < 0.5:
                    out.append(f"circle {cx},{cy} {cx + r},{cy}")
                else:
                    pts = " ".join(f"{cx + rnd.randint(-r, r)},{cy + rnd.randint(-r, r)}" for _ in range(rnd.randint(3, 6)))
                    out.append(f"polygon {pts}")
            for _ in range(rnd.randint(6, 20)):
                out.append(f"stroke black stroke-width {max(1, int(rnd.uniform(1, 5) * s))} fill none")
                out.append(f"line {rnd.randint(x, x2)},{rnd.randint(y, y2)} {rnd.randint(x, x2)},{rnd.randint(y, y2)}")
            if rnd.random() < 0.8:
                bw, bh = int(col_w * rnd.uniform(0.3, 0.6)), int(row_h * rnd.uniform(0.15, 0.3))
                bx, by = x + rnd.randint(0, max(1, col_w - bw)), y + rnd.randint(0, max(1, row_h // 3))
                out.append(f"fill white stroke black stroke-width {max(1, int(3 * s))}")
                out.append(f"ellipse {bx + bw // 2},{by + bh // 2} {bw // 2},{bh // 2} 0,360")
                out.append("stroke none fill #202020")
                lines = max(1, bh // int(36 * s))
                for i in range(lines):
                    ly = by + bh // 4 + i * int(30 * s)
                    if ly > by + bh * 3 // 4:
                        break
                    words_x = bx + bw // 5
                    while words_x < bx + bw * 4 // 5:
                        ww = rnd.randint(int(15 * s), int(60 * s))
                        out.append(f"rectangle {words_x},{ly} {min(words_x + ww, bx + bw * 4 // 5)},{ly + int(14 * s)}")
                        words_x += ww + int(12 * s)
            out.append(f"fill none stroke black stroke-width {max(2, int(8 * s))}")
            out.append(f"rectangle {x},{y} {x2},{y2}")
            x = x2 + gutter
        y += row_h + gutter
    return "\n".join(out) + "\n"


def render_page(w: int, h: int, seed: int, png: Path) -> None:
    """A page: smooth painted color (plasma, enlarged), the line art over it, light grain."""
    with tempfile.TemporaryDirectory() as tmp:
        mvg = Path(tmp, "art.mvg")
        mvg.write_text(page_art(w, h, seed))
        run(["magick", "-seed", str(seed), "-size", f"{w // 8}x{h // 8}", "plasma:white-#c0d0e0",
             "-resize", f"{w}x{h}!", "-blur", "0x2",
             "(", "-size", f"{w}x{h}", "xc:none", "-draw", f"@{mvg}", ")", "-composite",
             "-attenuate", "0.25", "+noise", "Gaussian", "-depth", "8", "-strip", str(png)])


def encode(png: Path, fmt: str, out: Path) -> bool:
    """[png] as [fmt] in [out]; false when the encoder is missing."""
    if fmt == "jpeg":
        run(["magick", str(png), "-quality", "90", "-sampling-factor", "4:2:0", str(out)])
    elif fmt == "png":
        shutil.copyfile(png, out)
    elif fmt == "webp":
        run(["magick", str(png), "-quality", "90", "-define", "webp:method=4", str(out)])
    elif fmt == "jxl":
        if not shutil.which("cjxl"):
            return False
        # Quality 91, as JXL comics are commonly published.
        run(["cjxl", str(png), str(out), "-q", "91", "-e", "7", "--quiet"])
    elif fmt in ("avif", "avifl"):
        if not shutil.which("avifenc"):
            return False
        extra = ["--progressive"] if fmt == "avifl" else []
        run(["avifenc", "-q", "70", "-s", "6", "-j", "all", *extra, str(png), str(out)], quiet=True)
    else:
        raise ValueError(fmt)
    return True


EXT = {"jpeg": "jpg", "png": "png", "webp": "webp", "jxl": "jxl", "avif": "avif", "avifl": "avif"}


def make(args) -> None:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    pages = {"2k": args.pages_2k, "4k": args.pages_4k}
    for size, (w, h) in SIZES.items():
        art = out / f"pages-{size}"
        art.mkdir(exist_ok=True)
        for i in range(pages[size]):
            png = art / f"page{i + 1:03}.png"
            if not png.exists():
                render_page(w, h, 1000 * w + i, png)
                print("rendered", png, flush=True)
        for fmt in ("jpeg", "png", "webp", "jxl", "avif", "avifl"):
            book = out / f"synth-{size}-{fmt}.cbz"
            if book.exists():
                continue
            enc = out / f"enc-{size}-{fmt}"
            enc.mkdir(exist_ok=True)
            files = []
            for i in range(pages[size]):
                f = enc / f"page{i + 1:03}.{EXT[fmt]}"
                if not f.exists() and not encode(art / f"page{i + 1:03}.png", fmt, f):
                    print("skipped", fmt, "(no encoder)")
                    files = []
                    break
                files.append(f)
            if files:
                # Comic archives usually store their already-compressed pages.
                with zipfile.ZipFile(book, "w", zipfile.ZIP_STORED) as z:
                    for f in files:
                        z.write(f, f.name)
                print("packed", book, flush=True)
    wide = out / "synth-2k-jpeg-wide.cbz"
    if not wide.exists() and pages["2k"] >= 12:
        # A two-page spread as one wide page among single pages, for "Rotate for spreads".
        enc = out / "enc-2k-jpeg"
        spread = out / "spread-2k.jpg"
        run(["magick", str(out / "pages-2k" / "page005.png"), str(out / "pages-2k" / "page006.png"), "+append",
             "-quality", "90", "-sampling-factor", "4:2:0", str(spread)])
        with zipfile.ZipFile(wide, "w", zipfile.ZIP_STORED) as z:
            for i in (1, 2, 3, 4):
                z.write(enc / f"page{i:03}.jpg", f"page{i:03}.jpg")
            z.write(spread, "page005.jpg")
            for i in range(7, 13):
                z.write(enc / f"page{i:03}.jpg", f"page{i:03}.jpg")
        print("packed", wide, flush=True)
    seven = shutil.which("7z")
    if seven:
        enc = out / "enc-2k-jpeg"
        for solid in ("on", "off"):
            book = out / f"synth-2k-jpeg-{'solid' if solid == 'on' else 'plain'}.cb7"
            if not book.exists():
                run([seven, "a", "-t7z", "-mx=5", f"-ms={solid}", str(book), str(enc / "*.jpg")], quiet=True)
                print("packed", book, flush=True)
    else:
        print("skipped cb7 (no 7z)")
    print("CBR: no free RAR writer exists; use real RAR books for those sessions.")


def fixtures(args) -> None:
    """Small JPEG XL pages of each kind (gray, wide gamut, ICC, alpha straight and premultiplied,
    animated, recompressed JPEG), each beside its reference `<name>.png` or `.jpg`, for
    DecodeCheckService's `fixtures` check; pushed with `--push`."""
    for tool in ("magick", "cjxl", "djxl"):
        if not shutil.which(tool):
            raise SystemExit(f"{tool} not found")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    w, h = 600, 900
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        color, gray, alpha, second = t / "color.png", t / "gray.png", t / "alpha.png", t / "second.png"
        render_page(w, h, 7, color)
        run(["magick", str(color), "-colorspace", "Gray", "-type", "Grayscale", str(gray)])
        # Alpha from opaque at the top to clear at the bottom, with a fully clear band.
        run(["magick", str(color), "(", "-size", f"{w}x{h}", "gradient:white-black", "-fill", "black",
             "-draw", f"rectangle 0,{h * 3 // 4} {w // 2},{h}", ")", "-alpha", "off", "-compose", "CopyOpacity",
             "-composite", f"PNG32:{alpha}"])
        run(["magick", str(color), "-rotate", "180", str(second)])
        anim = t / "anim.png"
        run(["magick", "-delay", "10", str(color), str(second), f"APNG:{anim}"])
        photo = t / "photo.jpg"
        run(["magick", str(color), "-quality", "90", "-sampling-factor", "4:2:0", str(photo)])

        def jxl(name: str, src: Path, *opts: str) -> Path:
            f = out / f"{name}.jxl"
            run(["cjxl", str(src), str(f), "--quiet", *opts])
            return f

        jxl("color-lossy", color, "-q", "91")
        shutil.copyfile(color, out / "color-lossy.png")
        jxl("gray-lossy", gray, "-q", "91")
        shutil.copyfile(gray, out / "gray-lossy.png")
        jxl("gray-lossless", gray, "-d", "0")
        shutil.copyfile(gray, out / "gray-lossless.png")
        # Display P3 by enum; the reference is libjxl's own rendering in sRGB.
        p3 = jxl("p3-lossy", color, "-q", "91", "-x", "color_space=DisplayP3")
        run(["djxl", str(p3), str(out / "p3-lossy.png"), "--color_space=RGB_D65_SRG_Rel_SRG", "--quiet"])
        icc = t / "p3.icc"
        run(["djxl", str(p3), str(t / "p3.png"), "--icc_out", str(icc), "--quiet"])
        # A profile as ICC bytes in the source file: --icc, else the P3 one (which cjxl may store as an enum).
        tagged = t / "tagged.png"
        run(["magick", str(color), "-profile", str(Path(args.icc) if args.icc else icc), str(tagged)])
        p3icc = jxl("icc-lossy", tagged, "-q", "91")
        run(["djxl", str(p3icc), str(out / "icc-lossy.png"), "--color_space=RGB_D65_SRG_Rel_SRG", "--quiet"])
        # Lossless and not sRGB: the native path has no color manager and must hand it on.
        p3ll = jxl("p3-lossless", color, "-d", "0", "-x", "color_space=DisplayP3")
        run(["djxl", str(p3ll), str(out / "p3-lossless.png"), "--color_space=RGB_D65_SRG_Rel_SRG", "--quiet"])
        jxl("alpha-lossless", alpha, "-d", "0")
        shutil.copyfile(alpha, out / "alpha-lossless.png")
        # cjxl only flags alpha as premultiplied, so the pixels it gets are premultiplied first.
        premultiplied = t / "premultiplied.png"
        run(["magick", str(alpha), "-channel", "RGB", "-fx", "u*u.a", "+channel", f"PNG32:{premultiplied}"])
        jxl("alpha-premultiplied", premultiplied, "-d", "0", "--premultiply=1")
        shutil.copyfile(alpha, out / "alpha-premultiplied.png")
        jxl("alpha-lossy", alpha, "-q", "91")
        shutil.copyfile(alpha, out / "alpha-lossy.png")
        # The reference is libjxl's own APNG, whose default image is the first frame.
        animated = jxl("animated", anim, "-d", "0")
        run(["djxl", str(animated), str(out / "animated.png"), "--quiet"])
        jxl("jpeg", photo, "--lossless_jpeg=1")
        shutil.copyfile(photo, out / "jpeg.jpg")
    print("fixtures in", out)
    if args.push:
        dest = f"{DEVICE_DIR}/fixtures"
        adb("shell", "mkdir", "-p", dest)
        for f in sorted(out.iterdir()):
            adb("push", str(f), f"{dest}/{f.name}")


def push(args) -> None:
    adb("shell", "mkdir", "-p", DEVICE_DIR)
    for f in sorted(Path(args.src).glob("synth-*.cb*")):
        adb("push", str(f), f"{DEVICE_DIR}/{f.name}")


# ---------------------------------------------------------------- sessions

TRACE_CONFIG = """
buffers { size_kb: 131072 fill_policy: RING_BUFFER }
buffers { size_kb: 4096 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" target_buffer: 0 ftrace_config {
  ftrace_events: "sched/sched_switch" ftrace_events: "power/cpu_frequency" ftrace_events: "power/cpu_idle"
  ftrace_events: "sched/sched_wakeup" ftrace_events: "task/task_newtask" ftrace_events: "task/task_rename"
  atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "dalvik" atrace_categories: "am"
  atrace_categories: "hal" atrace_categories: "input" atrace_apps: "%s"
  buffer_size_kb: 16384 drain_period_ms: 250 } } }
data_sources { config { name: "linux.process_stats" target_buffer: 1 process_stats_config { scan_all_processes_on_start: true } } }
data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 1 } }
data_sources { config { name: "android.power" target_buffer: 1 android_power_config { battery_poll_ms: 250
  collect_power_rails: true battery_counters: BATTERY_COUNTER_CURRENT battery_counters: BATTERY_COUNTER_VOLTAGE } } }
data_sources { config { name: "linux.sys_stats" target_buffer: 1 sys_stats_config { thermal_period_ms: 1000 } } }
duration_ms: %d
""" % (PACKAGE, 600_000)


def extras(session: dict) -> list[str]:
    """[session] as `am start` extras, each quoted for the device's shell."""
    out = []
    for k, v in session.items():
        if isinstance(v, bool):
            out += ["--ez", k, "true" if v else "false"]
        elif isinstance(v, int):
            out += ["--ei", k, str(v)]
        else:
            out += ["--es", k, shlex.quote(str(v))]
    return out


def bench_once(session: dict, timeout_s: int) -> list[dict]:
    """Runs one session through BenchActivity; its result lines."""
    adb("shell", "rm", "-f", RESULTS, check=False)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", BENCH, *extras(session))
    end = time.time() + timeout_s
    while time.time() < end:
        time.sleep(2)
        log = adb("logcat", "-d", "-s", "Bench:*", capture=True)
        if "A run is going on" in log:
            # Another run (say a second copy of this script) holds the device: results would mix.
            raise SystemExit("another benchmark run is going on; stop it first")
        if "Bench done" in log:
            break
    else:
        print("timed out", session.get("label"))
        # A start with nothing to do puts back the reading settings a run that died left changed.
        adb("shell", "am", "force-stop", PACKAGE, check=False)
        adb("shell", "am", "start", "-W", "-n", BENCH, check=False)
    text = adb("exec-out", "cat", RESULTS, capture=True, check=False)
    return [json.loads(line) for line in text.splitlines() if line.strip().startswith("{")]


def battery_level() -> int | None:
    """The device's battery level in percent, as `dumpsys battery` reports it; None when not found."""
    m = re.search(r"^\s*level: (\d+)", adb("shell", "dumpsys", "battery", capture=True, check=False), re.M)
    return int(m.group(1)) if m else None


def battery_refusal(level: int | None, minimum: int) -> str | None:
    """Why no session may start at battery [level] percent with --min-battery [minimum]; None when one may."""
    if level is None or level >= minimum:
        return None
    return f"battery at {level}%, below --min-battery {minimum}%: charge the device first (--min-battery 0 overrides)"


def app_uid() -> int | None:
    m = re.search(r"uid:(\d+)", adb("shell", "cmd", "package", "list", "packages", "-U", PACKAGE, capture=True))
    return int(m.group(1)) if m else None


def estimated_mah(stats: str, uid: int | None) -> float | None:
    """The app's estimated drain in mAh from `dumpsys batterystats`; None when not found."""
    if uid is None:
        return None
    name = f"u0a{uid - 10000}" if uid >= 10000 else str(uid)
    m = re.search(rf"UID {re.escape(name)}: ([\d.]+)", stats)
    return float(m.group(1)) if m else None


def run_plan(args) -> None:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    plan = json.loads(Path(args.plan).read_text())
    results = out / "results.jsonl"
    uid = app_uid()
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    if not getattr(args, "variant", None):
        # `ab` calibrates after each install.
        calibrate(out)
    for n, session in enumerate(plan):
        refusal = battery_refusal(battery_level(), args.min_battery)
        if refusal:
            raise SystemExit(refusal)
        tag = f"{n:02}-" + re.sub(r"[^A-Za-z0-9]+", "-", session.get("label", "s"))
        trace = f"{TRACE_DIR}/emanata-{tag}.pftrace"
        tracer = None
        if args.trace:
            # --background prints the tracing process's id: only that one is stopped.
            started = subprocess.run([ADB, "shell", "perfetto", "--txt", "-c", "-", "-o", trace, "--background"],
                                     input=TRACE_CONFIG.encode(), check=True, capture_output=True)
            tracer = started.stdout.decode().strip().splitlines()[-1].strip()
            if not tracer.isdigit():
                raise SystemExit(f"perfetto gave no process id: {tracer!r}")
            time.sleep(2)
        if args.energy:
            adb("shell", "dumpsys", "battery", "unplug")
            adb("shell", "dumpsys", "batterystats", "--reset")
        lines: list[dict] = []
        try:
            lines = bench_once(session, args.timeout)
        finally:
            if args.energy:
                stats = adb("shell", "dumpsys", "batterystats", capture=True, check=False)
                adb("shell", "dumpsys", "battery", "reset")
                state = adb("shell", "dumpsys", "battery", capture=True)
                if "AC powered: true" not in state and "USB powered: true" not in state:
                    print("warning: battery state after reset shows no power source", file=sys.stderr)
                (out / f"{tag}-batterystats.txt").write_text(stats)
                mah = estimated_mah(stats, uid)
                for line in lines:
                    line["energyMah"] = mah
            if tracer:
                adb("shell", "kill", "-INT", tracer, check=False)
                for _ in range(30):
                    time.sleep(1)
                    if adb("shell", f"kill -0 {tracer} 2>/dev/null && echo up", capture=True, check=False).strip() != "up":
                        break
                adb("pull", trace, str(out / f"{tag}.pftrace"), check=False)
                adb("shell", "rm", "-f", trace, check=False)
        with results.open("a") as f:
            for line in lines:
                line["session"] = tag
                if getattr(args, "variant", None):
                    line["variant"] = args.variant
                f.write(json.dumps(line) + "\n")
        print(tag, "->", ", ".join(summary(line) for line in lines), flush=True)


def summary(r: dict) -> str:
    if r.get("kind") == "grid":
        return f"grid first {r['firstCoverMs']} ms, last full {r['lastFullMs']} ms ({r['onScreenFull']}/{r['onScreen']})"
    return (f"open {r['openVisibleMs']}/{r['openSharpMs']} ms, turn median {r['turnVisible']['median']}/{r['turnSharp']['median']} ms "
            f"p90 {r['turnSharp']['p90']} ms")


# ---------------------------------------------------------------- reports

def known(xs):
    return [x for x in xs if x is not None and x >= 0]


def median(xs):
    xs = known(xs)
    return round(statistics.median(xs)) if xs else -1


def spread(xs) -> str:
    """Median and range of [xs], e.g. "312 (290-401)"; "-" when none is known."""
    xs = known(xs)
    if not xs:
        return "-"
    m = round(statistics.median(xs))
    return f"{m} ({round(min(xs))}-{round(max(xs))})" if len(xs) > 1 else str(m)


def headroom(rs) -> str:
    """The thermal headroom range over [rs] sessions (before each to after its turns), and the worst thermal status."""
    states = [r[k] for r in rs for k in ("before", "afterTurns", "after") if k in r]
    hs = [st.get("headroom") for st in states if st.get("headroom") is not None]
    status = max((st.get("thermal", -1) for st in states), default=-1)
    return (f"{min(hs):.2f}-{max(hs):.2f}" if hs else "-") + f" / {status}"


def battery(rs) -> str:
    """The battery level range over [rs] sessions, from before each to after it, and whether it was plugged in throughout."""
    states = [r[k] for r in rs for k in ("before", "after") if k in r]
    levels = [st["batteryPct"] for st in states if st.get("batteryPct", -1) >= 0]
    if not levels:
        return "-"
    plugged = "" if all(st.get("plugged") for st in states) else " unplugged"
    return f"{min(levels)}-{max(levels)}{plugged}"


def report(args) -> None:
    rows = [json.loads(line) for line in Path(args.results).read_text().splitlines() if line.strip()]
    reads = [r for r in rows if r.get("kind") == "read" and not r.get("warmup")]
    groups: dict[tuple, list[dict]] = {}
    for r in reads:
        p = r["plan"]
        key = (p["label"], r.get("variant", ""), p["layout"], p["input"] + (" back" if p.get("back") else ""), p.get("tapMs", ""), p["pace"],
               r.get("cache") or ("fresh" if p["fresh"] else ""), p.get("crop"), p.get("doubleTap"))
        groups.setdefault(key, []).append(r)
    print("Medians over sessions, range in brackets; turn times are each session's median. Warm-up sessions left out.")
    print()
    print("| book | variant | layout | input | tap ms | pace | cache | crop | double-tap | sessions | turns | open visible | open sharp "
          "| turn visible | turn sharp | turn sharp p90 | tap to move | ready ahead | stand-in frames | CPU/session | settle ms | decodes "
          "| aside decodes | CPU/decode | PSS | graphics | janky (>2x) | late | headroom / thermal | battery % | current mA | energy µAh |")
    print("|" + "---|" * 32)
    for (label, variant, layout, inp, tap_ms, pace, cache, crop, double_tap), rs in groups.items():
        turns = sum(r["turnSharp"]["n"] for r in rs)
        print("| " + " | ".join(str(x) for x in [
            label, variant, layout, inp, tap_ms if inp.startswith("tap") else "", pace, cache, "on" if crop else "",
            {True: "on", False: "off"}.get(double_tap, ""), len(rs), turns,
            spread(r["openVisibleMs"] for r in rs), spread(r["openSharpMs"] for r in rs),
            spread(r["turnVisible"]["median"] for r in rs if r["turnVisible"]["n"]),
            spread(r["turnSharp"]["median"] for r in rs if r["turnSharp"]["n"]),
            median(r["turnSharp"]["p90"] for r in rs if r["turnSharp"]["n"]),
            median(r["turnMoved"]["median"] for r in rs if r["turnMoved"]["n"]),
            f"{sum(r['turnsReady'] for r in rs)}/{turns}",
            sum(r.get("turnUnsharpFrames", 0) for r in rs),
            spread(r.get("cpuMs") for r in rs), median(r.get("settleMs") for r in rs), median(r.get("decodes") for r in rs),
            median(sum(r["asideDecodes"].values()) for r in rs if "asideDecodes" in r), spread(r.get("cpuPerDecodeMs") for r in rs),
            median(r["afterTurns"]["pssMb"] for r in rs), median(r["afterTurns"]["graphicsMb"] for r in rs),
            f"{sum(r['turnFrames']['janky'] for r in rs)}/{sum(r['turnFrames']['frames'] for r in rs)}",
            sum(r["turnFrames"].get("late", 0) for r in rs),
            headroom(rs), battery(rs), median(r["before"].get("currentMa") for r in rs if "before" in r),
            spread([(r.get("energyMah") or -1) * 1000 for r in rs]) if any(r.get("energyMah") for r in rs) else "",
        ]) + " |")
    grids = [r for r in rows if r.get("kind") == "grid"]
    if grids:
        print()
        print("| folder | variant | fresh | first cover | first full | last full | full / on screen | CPU ms | headroom / thermal |")
        print("|---|---|---|---|---|---|---|---|---|")
        for r in grids:
            p = r["plan"]
            print(f"| {p['label']} | {r.get('variant', '')} | {'yes' if p['fresh'] else ''} | {r['firstCoverMs']} | {r['firstFullMs']} | "
                  f"{r['lastFullMs']} | {r['onScreenFull']}/{r['onScreen']} | {r['cpuMs']} | {headroom([r])} |")


def ab(args) -> None:
    """Runs [args.plan] on two builds in turn, A B B A per two rounds."""
    order = []
    for n in range(args.rounds):
        order += ["a", "b"] if n % 2 == 0 else ["b", "a"]
    for n, variant in enumerate(order):
        apk = args.a if variant == "a" else args.b
        print(f"== round {n // 2 + 1}, {variant}: installing {Path(apk).name}", flush=True)
        install(apk)
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        calibrate(Path(args.out), variant)
        if args.rest:
            time.sleep(args.rest)
        args.variant = variant
        run_plan(args)


def slices(args) -> None:
    from perfetto.trace_processor import TraceProcessor  # pip install perfetto

    tp = TraceProcessor(trace=args.trace)
    q = f"""
        select s.name as name, count(*) as n, sum(s.dur) / 1e6 as total_ms, max(s.dur) / 1e6 as max_ms, t.name as thread
        from slice s join thread_track tt on s.track_id = tt.id join thread t using (utid) join process p using (upid)
        where p.name like '{PACKAGE}%'
        group by s.name, t.name order by total_ms desc limit {args.top}"""
    for r in tp.query(q):
        print(f"{r.total_ms:10.1f} ms  {r.n:5}x  max {r.max_ms:8.1f}  [{r.thread}] {r.name}")


# ---------------------------------------------------------------- plumbing

def run(cmd: list[str], quiet: bool = False) -> None:
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL if quiet else None)


def adb(*cmd: str, capture: bool = False, check: bool = True, timeout: float | None = None) -> str:
    r = subprocess.run([ADB, *cmd], check=check, capture_output=capture, text=capture, timeout=timeout)
    return r.stdout if capture else ""


def install(apk: str, tries: int = 2) -> None:
    """
    Installs [apk] over the app, trying again when an install stalls. Many installs in a row can
    bring up a Play Protect prompt on the device, which holds the install until answered or until
    it gives up by itself (about ten minutes).
    """
    for n in range(tries):
        try:
            adb("install", "-r", apk, timeout=900)
            return
        except subprocess.TimeoutExpired:
            print(f"install stalled (try {n + 1} of {tries})", flush=True)
    raise SystemExit(f"could not install {apk}")


def calibrate(out: Path | None = None, variant: str = "", timeout_s: int = 300) -> None:
    """
    Calibrates a freshly installed build now (CalibrationActivity) and waits for it to end, so
    no calibration runs between sessions and both builds of an A/B read with their own winners.
    A run already pending is dropped for this one. Appends what it kept (timings, winners) and
    its log lines to [out]/calibration.jsonl. A build without calibration is left as it is.
    """
    adb("logcat", "-c")
    started = subprocess.run([ADB, "shell", "am", "start", "-n", f"{PACKAGE}/com.infinity.comicreader.debug.CalibrationActivity",
                              "--es", "do", "recalibrate"], capture_output=True, text=True)
    if "Error" in started.stdout + started.stderr:
        print("no calibration in this build", flush=True)
        return
    end = time.time() + timeout_s
    while time.time() < end:
        time.sleep(3)
        log = adb("logcat", "-d", "-s", "Calibration:I", capture=True, check=False)
        m = re.search(r"ended (true|false)", log)
        if m:
            print(f"calibrated: {m.group(1)}", flush=True)
            if out is not None:
                out.mkdir(parents=True, exist_ok=True)
                kept = [json.loads(s) for s in re.findall(r"^.*?Calibration\s*:\s*(\{.*\})\s*$", log, re.M)]
                lines = [s.split(": ", 1)[-1] for s in log.splitlines() if "Calibration" in s and "{" not in s]
                with (out / "calibration.jsonl").open("a") as f:
                    f.write(json.dumps({"variant": variant, "ended": m.group(1) == "true", "kept": kept[-1] if kept else None,
                                        "log": lines}) + "\n")
            break
    else:
        print("calibration did not end", flush=True)
    adb("shell", "input", "keyevent", "KEYCODE_BACK", check=False)


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(required=True)
    p = sub.add_parser("make")
    p.add_argument("--out", required=True)
    p.add_argument("--pages-2k", type=int, default=16)
    p.add_argument("--pages-4k", type=int, default=8)
    p.set_defaults(func=make)
    p = sub.add_parser("fixtures")
    p.add_argument("--out", required=True)
    p.add_argument("--push", action="store_true", help="also to the device's Download/emanata-test/fixtures")
    p.add_argument("--icc", help="an RGB display profile no enum describes, for icc-lossy")
    p.set_defaults(func=fixtures)
    p = sub.add_parser("push")
    p.add_argument("--src", required=True)
    p.set_defaults(func=push)
    p = sub.add_parser("run")
    p.add_argument("--plan", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--trace", action="store_true")
    p.add_argument("--energy", action="store_true")
    p.add_argument("--timeout", type=int, default=600)
    p.add_argument("--min-battery", type=int, default=MIN_BATTERY, help="percent below which no session starts (0: no check)")
    p.set_defaults(func=run_plan)
    p = sub.add_parser("ab")
    p.add_argument("--a", required=True, help="the build A apk")
    p.add_argument("--b", required=True, help="the build B apk")
    p.add_argument("--plan", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--rounds", type=int, default=6, help="runs of each build")
    p.add_argument("--rest", type=int, default=20, help="seconds to idle after each install")
    p.add_argument("--trace", action="store_true")
    p.add_argument("--energy", action="store_true")
    p.add_argument("--timeout", type=int, default=600)
    p.add_argument("--min-battery", type=int, default=MIN_BATTERY, help="percent below which no session starts (0: no check)")
    p.set_defaults(func=ab)
    p = sub.add_parser("report")
    p.add_argument("results")
    p.set_defaults(func=report)
    p = sub.add_parser("slices")
    p.add_argument("trace")
    p.add_argument("--top", type=int, default=40)
    p.set_defaults(func=slices)
    return ap


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
