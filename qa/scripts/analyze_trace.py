#!/usr/bin/env python3
"""Behavior-trace analyzer: detects goal thrashing, stuck navigation,
ignored starvation, and teleport anomalies in hearthstead-trace.jsonl.
Each finding gets a stable failure id FB-<hash> for `hearthstead-qa
reproduce`. Exit 1 on any finding."""
import hashlib
import json
import sys
from collections import defaultdict


def fid(kind, uuid, detail):
    h = hashlib.sha256(f"{kind}|{uuid}|{detail}".encode()).hexdigest()[:10]
    return f"FB-{h}"


def main(path):
    per = defaultdict(list)
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            per[rec["uuid"]].append(rec)

    findings = []
    for uuid, recs in per.items():
        recs.sort(key=lambda r: r["tick"])
        name = recs[-1].get("name", "?")

        # Activity thrashing: many flips inside a 200-tick window with no
        # meaningful movement.
        window = []
        for r in recs:
            window.append(r)
            window = [w for w in window if r["tick"] - w["tick"] <= 200]
            flips = sum(1 for a, b in zip(window, window[1:])
                        if a["activity"] != b["activity"])
            moved = max(abs(a["x"] - b["x"]) + abs(a["z"] - b["z"])
                        for a, b in zip(window, window[1:])) if len(window) > 1 else 99
            if flips > 6 and moved < 1.0:
                findings.append((fid("thrash", uuid, r["tick"] // 200),
                                 f"{name}: activity thrashing ({flips} flips/"
                                 f"200t, no movement) around tick {r['tick']}"))
                window = []

        # Stuck navigation: nav active but no movement for >200 ticks.
        anchor = None
        for r in recs:
            if not r.get("navDone", True):
                if anchor is None:
                    anchor = r
                elif (abs(r["x"] - anchor["x"]) + abs(r["z"] - anchor["z"]) > 1.5
                      or abs(r["y"] - anchor["y"]) > 1.0):
                    anchor = r
                elif r["tick"] - anchor["tick"] > 200:
                    findings.append((fid("stucknav", uuid, anchor["tick"]),
                                     f"{name}: navigating without progress "
                                     f"ticks {anchor['tick']}..{r['tick']} at "
                                     f"({r['x']:.0f},{r['y']:.0f},{r['z']:.0f})"))
                    anchor = r
            else:
                anchor = None

        # Ignored starvation: hunger critically low for a long stretch while
        # never entering EATING.
        low_since = None
        for r in recs:
            if r["hunger"] < 15:
                if low_since is None:
                    low_since = r["tick"]
                elif (r["tick"] - low_since > 600
                      and not any(x["activity"] == "EATING" for x in recs
                                  if low_since <= x["tick"] <= r["tick"])):
                    findings.append((fid("starve", uuid, low_since),
                                     f"{name}: hunger<15 ignored for "
                                     f"{r['tick'] - low_since} ticks"))
                    low_since = None
            else:
                low_since = None

        # Teleport anomaly: >16 blocks between consecutive samples.
        for a, b in zip(recs, recs[1:]):
            if b["tick"] - a["tick"] <= 40:
                dist = ((a["x"] - b["x"]) ** 2 + (a["z"] - b["z"]) ** 2) ** 0.5
                if dist > 16:
                    findings.append((fid("teleport", uuid, a["tick"]),
                                     f"{name}: moved {dist:.0f} blocks in "
                                     f"{b['tick'] - a['tick']} ticks at {a['tick']}"))

    # Deduplicate by id.
    seen = {}
    for i, msg in findings:
        seen.setdefault(i, msg)
    for i, msg in sorted(seen.items()):
        print(f"{i}: {msg}")
    total = sum(len(v) for v in per.values())
    if seen:
        print(f"behavior analysis: {len(seen)} finding(s) over {total} samples, "
              f"{len(per)} settlers")
        sys.exit(1)
    print(f"behavior analysis clean: {total} samples, {len(per)} settlers, 0 findings")


if __name__ == "__main__":
    main(sys.argv[1])
