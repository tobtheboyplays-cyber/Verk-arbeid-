#!/usr/bin/env python3
"""Summarise [uiprofile] lines from a Minecraft log into a before/after table.

Runs anywhere Python does -- including the owner's Windows machine, which is
the point: the frame numbers this repository can measure come off a software
rasteriser in a container, and software-rasterised milliseconds are not the
milliseconds a player's GPU produces. The RELATIVE change is comparable; the
absolute FPS is not. So the absolute number has to be taken on the real
machine, and this reads it.

Usage
-----
1. Add this to the instance's JVM arguments (CurseForge: Settings -> Java ->
   "JVM Arguments"; any launcher has the same field):

       -Dhearthstead.uiprofile=true

   With the flag absent, UiProfiler's handlers fold to a constant-false branch
   and nothing is measured or logged, so it is safe to leave the mod as-is for
   normal play -- but equally, nothing appears in the log until the flag is on.

2. Play. Open the Hearth and a settler sheet on a settlement of the size you
   actually care about, and leave each one open for ten seconds or so; a line
   is emitted every 120 rendered frames per screen.

3. Run:

       python uiprofile_report.py "%APPDATA%\\.minecraft\\logs\\latest.log"

   or, for a CurseForge instance:

       python uiprofile_report.py "C:\\Users\\<you>\\curseforge\\minecraft\\Instances\\<instance>\\logs\\latest.log"

   Pass two logs to diff them:

       python uiprofile_report.py before.log after.log

Reading the output
------------------
`mean_ms` is what the screen costs the frame; at 60fps the whole frame budget
is 16.7ms, so a screen at 4ms is spending a quarter of it on itself.
`alloc_kb` is garbage produced per frame -- the number that shows up as a
stutter a second later rather than as a slow frame, and the one that fell
hardest in this pass.
"""
import collections
import re
import statistics
import sys

LINE = re.compile(
    r"\[uiprofile\]\s+screen=(?P<screen>\S+)\s+frames=(?P<frames>\d+)\s+"
    r"mean_ms=(?P<mean>[\d.]+)\s+p50_ms=(?P<p50>[\d.]+)\s+p95_ms=(?P<p95>[\d.]+)\s+"
    r"max_ms=(?P<max>[\d.]+)\s+alloc_kb_per_frame=(?P<alloc>-?[\d.]+)\s+"
    r"fps=(?P<fps>\d+)\s+scale=(?P<scale>[\d.]+)\s+size=(?P<size>\S+)")


def read(path):
    """screen -> list of sample dicts. Encoding is forgiving: game logs are
    not guaranteed UTF-8 on Windows, and a mojibake byte in some unrelated
    chat line must not lose the whole run."""
    out = collections.defaultdict(list)
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = LINE.search(line)
            if m:
                out[m.group("screen")].append({
                    "mean": float(m.group("mean")),
                    "p50": float(m.group("p50")),
                    "p95": float(m.group("p95")),
                    "max": float(m.group("max")),
                    "alloc": float(m.group("alloc")),
                    "fps": int(m.group("fps")),
                    "scale": m.group("scale"),
                    "size": m.group("size"),
                })
    return out


def summarise(samples):
    """Median across windows, not mean: one window catching a resource reload
    or a chunk rebuild would otherwise drag the whole figure with it."""
    return {
        "windows": len(samples),
        "mean": statistics.median(s["mean"] for s in samples),
        "p50": statistics.median(s["p50"] for s in samples),
        "p95": statistics.median(s["p95"] for s in samples),
        "alloc": statistics.median(s["alloc"] for s in samples),
        "fps": statistics.median(s["fps"] for s in samples),
        "scale": samples[-1]["scale"],
        "size": samples[-1]["size"],
    }


def table(title, data):
    print(f"\n== {title} ==")
    if not data:
        print("  no [uiprofile] lines found -- is -Dhearthstead.uiprofile=true set,")
        print("  and was a screen left open long enough for 160 frames?")
        return
    print(f"  {'screen':28} {'win':>4} {'mean':>7} {'p50':>7} {'p95':>7} "
          f"{'alloc':>9} {'fps':>5}  viewport")
    for screen, samples in sorted(data.items()):
        s = summarise(samples)
        print(f"  {screen:28} {s['windows']:4d} {s['mean']:6.2f}m {s['p50']:6.2f}m "
              f"{s['p95']:6.2f}m {s['alloc']:8.0f}K {s['fps']:5.0f}  "
              f"{s['size']}@{s['scale']}")


def delta(before, after):
    print("\n== change ==")
    shared = sorted(set(before) & set(after))
    if not shared:
        print("  no screen appears in both logs")
        return
    print(f"  {'screen':28} {'mean ms':>18} {'alloc KB/frame':>22} {'fps':>14}")
    for screen in shared:
        b, a = summarise(before[screen]), summarise(after[screen])

        def move(x, y, unit="", lower_is_better=True):
            if x == 0:
                return f"{x:.0f}->{y:.0f}{unit}"
            pct = (y - x) / x * 100.0
            good = pct < 0 if lower_is_better else pct > 0
            return f"{x:.2f}->{y:.2f}{unit} ({pct:+.0f}%{'' if good else ' !'})"

        print(f"  {screen:28} {move(b['mean'], a['mean']):>18} "
              f"{move(b['alloc'], a['alloc']):>22} "
              f"{move(b['fps'], a['fps'], lower_is_better=False):>14}")


def main(argv):
    if not 2 <= len(argv) <= 3:
        print(__doc__)
        return 2
    if len(argv) == 2:
        table(argv[1], read(argv[1]))
        return 0
    before, after = read(argv[1]), read(argv[2])
    table(f"before  ({argv[1]})", before)
    table(f"after   ({argv[2]})", after)
    delta(before, after)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
