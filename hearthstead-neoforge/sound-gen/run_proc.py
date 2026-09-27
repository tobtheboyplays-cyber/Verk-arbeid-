"""Process + pick a set of targets and print one line each (used by the batch runs)."""
import sys, json
import process as PR
from targets import TARGETS
pri = int(sys.argv[1]) if len(sys.argv) > 1 else 1
exact = len(sys.argv) > 2 and sys.argv[2] == "exact"
for t in TARGETS:
    if (t['pri'] == pri) if exact else (t['pri'] <= pri):
        ch = PR.run(t, quiet=True)
        if ch is None:
            print(f"{t['event']:30s} NO CANDIDATES"); continue
        print(f"{t['event']:30s} {len(ch)}/{t['n']} {[r['name'] for r in ch]} {[r['score'] for r in ch]}")
