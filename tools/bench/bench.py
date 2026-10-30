"""Benchmarks the debug app on a device: synthetic books, scripted reading sessions, traces, energy.

Run from the repository root with Python 3.10+ (standard library only):

    py tools/bench/bench.py make --out <dir>                 # synthetic books (magick; cjxl, avifenc, 7z where found)
    py tools/bench/bench.py push --src <dir>                 # to the device's Download/emanata-test
    py tools/bench/bench.py run --plan <plan.json> --out <dir> [--trace] [--energy]
    py tools/bench/bench.py report <dir>/results.jsonl       # tables of medians
    py tools/bench/bench.py slices <trace.pftrace>           # app trace sections by total time (needs the perfetto package)

A plan is a JSON list of sessions, each the extras of BenchActivity (see its KDoc), e.g.
    [{"do": "read", "path": "/storage/emulated/0/Download/emanata-test/synth-2k-jpeg.cbz",
      "label": "2k jpeg", "layout": "paged", "turns": 10, "reps": 3}]
Results, traces and battery statistics go to --out; nothing is written to the device but the
pushed books, the app's own results file, and traces under /data/misc/perfetto-traces.

Time a release build: `./gradlew assembleBench`, then install app-bench.apk over the debug build
(same id and key, data kept). The debug build is debuggable, which slows the app's own code.

--energy fakes unplugged power (dumpsys battery unplug) and resets the battery statistics before
each session, then reads the app's estimated drain; the battery state is always reset after.
Estimates come from the device's power profile, not a meter.
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
        run(["cjxl", str(png), str(out), "-d", "1.0", "-e", "7", "--quiet"])
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
        if "Bench done" in adb("logcat", "-d", "-s", "Bench:I", capture=True):
            break
    else:
        print("timed out", session.get("label"))
    text = adb("exec-out", "cat", RESULTS, capture=True, check=False)
    return [json.loads(line) for line in text.splitlines() if line.strip().startswith("{")]


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
    for n, session in enumerate(plan):
        tag = f"{n:02}-" + re.sub(r"[^A-Za-z0-9]+", "-", session.get("label", "s"))
        trace = f"{TRACE_DIR}/emanata-{tag}.pftrace"
        if args.trace:
            subprocess.run([ADB, "shell", "perfetto", "--txt", "-c", "-", "-o", trace, "--background"],
                           input=TRACE_CONFIG.encode(), check=True, stdout=subprocess.DEVNULL)
            time.sleep(2)
        if args.energy:
            adb("shell", "dumpsys", "battery", "unplug")
            adb("shell", "dumpsys", "batterystats", "--reset")
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
            if args.trace:
                adb("shell", "pkill", "-INT", "perfetto", check=False)
                time.sleep(3)
                adb("pull", trace, str(out / f"{tag}.pftrace"), check=False)
                adb("shell", "rm", "-f", trace, check=False)
        with results.open("a") as f:
            for line in lines:
                line["session"] = tag
                f.write(json.dumps(line) + "\n")
        print(tag, "->", ", ".join(summary(line) for line in lines), flush=True)


def summary(r: dict) -> str:
    if r.get("kind") == "grid":
        return f"grid first {r['firstCoverMs']} ms, last full {r['lastFullMs']} ms ({r['onScreenFull']}/{r['onScreen']})"
    return (f"open {r['openVisibleMs']}/{r['openSharpMs']} ms, turn median {r['turnVisible']['median']}/{r['turnSharp']['median']} ms "
            f"p90 {r['turnSharp']['p90']} ms")


# ---------------------------------------------------------------- reports

def median(xs):
    xs = [x for x in xs if x is not None and x >= 0]
    return round(statistics.median(xs)) if xs else -1


def report(args) -> None:
    rows = [json.loads(line) for line in Path(args.results).read_text().splitlines() if line.strip()]
    reads = [r for r in rows if r.get("kind") == "read"]
    groups: dict[tuple, list[dict]] = {}
    for r in reads:
        p = r["plan"]
        groups.setdefault((p["label"], p["layout"], p["input"], p["pace"], p["fresh"], p.get("doubleTap")), []).append(r)
    print("| book | layout | input | pace | fresh | double-tap | n | open visible | open sharp | turn visible | turn sharp | turn sharp p90 | tap to move | ready | CPU open | CPU/turn | PSS | graphics | jank | energy mAh |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for (label, layout, inp, pace, fresh, double_tap), rs in groups.items():
        turns = sum(r["turnCount"] for r in rs) or 1
        print("| " + " | ".join(str(x) for x in [
            label, layout, inp, pace, "yes" if fresh else "", {True: "on", False: "off"}.get(double_tap, ""), len(rs),
            median(r["openVisibleMs"] for r in rs), median(r["openSharpMs"] for r in rs),
            median(r["turnVisible"]["median"] for r in rs), median(r["turnSharp"]["median"] for r in rs),
            median(r["turnSharp"]["p90"] for r in rs), median(r["turnMoved"]["median"] for r in rs),
            f"{sum(r['turnsReady'] for r in rs)}/{turns}",
            median(r["openCpuMs"] for r in rs), round(sum(r["turnCpuMs"] for r in rs) / turns),
            median(r["afterTurns"]["pssMb"] for r in rs), median(r["afterTurns"]["graphicsMb"] for r in rs),
            f"{sum(r['turnFrames']['janky'] for r in rs)}/{sum(r['turnFrames']['frames'] for r in rs)}",
            median((r.get("energyMah") or -1) * 1000 for r in rs) / 1000 if any(r.get("energyMah") for r in rs) else "",
        ]) + " |")
    grids = [r for r in rows if r.get("kind") == "grid"]
    if grids:
        print()
        print("| folder | fresh | first cover | first full | last full | full / on screen | CPU ms |")
        print("|---|---|---|---|---|---|---|")
        for r in grids:
            p = r["plan"]
            print(f"| {p['label']} | {'yes' if p['fresh'] else ''} | {r['firstCoverMs']} | {r['firstFullMs']} | {r['lastFullMs']} | "
                  f"{r['onScreenFull']}/{r['onScreen']} | {r['cpuMs']} |")


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


def adb(*cmd: str, capture: bool = False, check: bool = True) -> str:
    r = subprocess.run([ADB, *cmd], check=check, capture_output=capture, text=capture)
    return r.stdout if capture else ""


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(required=True)
    p = sub.add_parser("make")
    p.add_argument("--out", required=True)
    p.add_argument("--pages-2k", type=int, default=16)
    p.add_argument("--pages-4k", type=int, default=8)
    p.set_defaults(func=make)
    p = sub.add_parser("push")
    p.add_argument("--src", required=True)
    p.set_defaults(func=push)
    p = sub.add_parser("run")
    p.add_argument("--plan", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--trace", action="store_true")
    p.add_argument("--energy", action="store_true")
    p.add_argument("--timeout", type=int, default=600)
    p.set_defaults(func=run_plan)
    p = sub.add_parser("report")
    p.add_argument("results")
    p.set_defaults(func=report)
    p = sub.add_parser("slices")
    p.add_argument("trace")
    p.add_argument("--top", type=int, default=40)
    p.set_defaults(func=slices)
    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
