"""Compact sanity sheet: the PICKED takes of every target in a category, spectrogram per take."""
import sys, os, json, numpy as np
import audiolib as A
from targets import TARGETS
import matplotlib; matplotlib.use("Agg"); import matplotlib.pyplot as plt
W = r"C:\Users\tobia\Hearthstead-Claude\sound-gen-work"
cats = sys.argv[1].split(","); pri = int(sys.argv[2]) if len(sys.argv) > 2 else 9
p = json.load(open(os.path.join(W, "picks.json")))
ts = [t for t in TARGETS if t["cat"] in cats and (t["pri"] == pri if len(sys.argv) > 3 else t["pri"] <= pri) and p.get(t["event"], {}).get("files")]
cols = 6
fig, axes = plt.subplots(len(ts), cols, figsize=(cols * 2.3, len(ts) * 0.85 + 0.3), squeeze=False)
for r, t in enumerate(ts):
    fs = p[t["event"]]["files"]
    for c in range(cols):
        ax = axes[r][c]; ax.set_xticks([]); ax.set_yticks([])
        if c >= len(fs): ax.axis("off"); continue
        x = A.decode(os.path.join(W, "proc", t["event"], fs[c] + ".ogg"))
        if len(x) > 512:
            ax.specgram(x, NFFT=512, Fs=A.SR, noverlap=384, cmap="magma", vmin=-115)
        ax.set_ylim(0, 14000)
        ax.set_title(f"{t['event'][:22]} {fs[c]} {len(x)/A.SR:.2f}s", fontsize=5, loc="left", pad=1)
fig.tight_layout(pad=0.2)
out = os.path.join(W, "sheets", f"_overview_{'_'.join(cats)}_p{pri}.png")
fig.savefig(out, dpi=72); print(out)
