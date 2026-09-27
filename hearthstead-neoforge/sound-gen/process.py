"""Post-process ElevenLabs candidates and pick the best ones per target.

    python process.py --event combat.swing_light     # process + score + sheet
    python process.py --cat combat
    python process.py --all

For every raw candidate in WORK/raw/<event>/ this:
  1. decodes to 44.1 kHz mono, removes DC/sub rumble (high-pass),
  2. applies the target's light lo-fi EQ,
  3. trims leading/trailing silence and caps the length (short Minecraft tails),
  4. fades in/out, normalises to the target padded-LUFS with a -1 dBTP ceiling,
  5. scores it (raw clipping, length fit, burst count, noise floor, tail),
  6. picks the best N mutually-different candidates,
  7. writes processed OGGs to WORK/proc/<event>/ and a waveform+spectrogram
     contact sheet to WORK/sheets/<event>.png for the human pass.
Picks are stored in WORK/picks.json; `--override event=c03,c01` pins a manual choice.
"""
import argparse, json, os, sys, glob
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import audiolib as A  # noqa: E402
from targets import TARGETS  # noqa: E402

WORK = r"C:\Users\tobia\Hearthstead-Claude\sound-gen-work"
PICKS = os.path.join(WORK, "picks.json")


def load_picks():
    if os.path.exists(PICKS):
        with open(PICKS, encoding="utf-8") as fh:
            return json.load(fh)
    return {}


def save_picks(p):
    with open(PICKS, "w", encoding="utf-8") as fh:
        json.dump(p, fh, indent=1, sort_keys=True)


def bursts(x, gap=0.08):
    """Count separate sound events (energy islands above -24 dB rel peak)."""
    e = A.env_db(x, 0.01)
    on = e > e.max() - 24
    n, prev, quiet = 0, False, 0
    for v in on:
        if v and (not prev) and (n == 0 or quiet * 0.01 >= gap):
            n += 1
        quiet = 0 if v else quiet + 1
        prev = v
    return n


def loop_xfade(x, xf=0.25):
    n = int(xf * A.SR)
    if len(x) < 3 * n:
        return x
    head, body, tail = x[:n], x[n:-n], x[-n:]
    ramp = np.linspace(0, 1, n)
    mixed = tail * (1 - ramp) + head * ramp
    return np.concatenate([body, mixed])


def process_one(t, path):
    raw = A.decode_declipped(path) if path.endswith(".wav") else A.decode(path)
    raw_clip = A.flat_tops(path)                     # flat-top (truly clipped) samples
    x = A.hp(raw, t.get("hp", 40))
    x = A.lofi(x, t.get("lofi", 0.3))
    x = A.saturate(x, t.get("drive", 2.0 if t.get("impact") else 0.0))
    if t.get("loop"):
        x = loop_xfade(x)
        x = A.normalize(x, t["lufs"])
    else:
        x = A.trim(x, rel_db=t.get("trim_db", -42))
        if t.get("impact"):
            # contact sounds start ON the transient: drop pre-noise / flams quieter than -24 dB re peak
            e = A.env_db(x, 0.002)
            first = int(np.argmax(e > e.max() - 24))
            x = x[max(0, int((first * 0.002 - 0.003) * A.SR)):]
        x = A.cap_length(x, t.get("maxlen"))
        x = A.fade(x, 0.002, t.get("fadeout", 0.05))
        x = A.normalize(x, t["lufs"])
    st = A.stats(x)
    st["raw_clip"] = raw_clip
    st["bursts"] = bursts(x)
    e = A.env_db(x, 0.01)
    st["floor"] = round(float(np.percentile(e, 10) - e.max()), 1)  # how quiet the quietest 10% is
    st["attack"] = round(float(np.argmax(e) * 0.01), 3)
    pk = int(np.argmax(e)); tail = np.where(e[pk:] < e.max() - 30)[0]
    st["dec30"] = round(float(tail[0] * 0.01) if len(tail) else len(x) / A.SR - pk * 0.01, 3)
    return x, st


def score(t, st):
    s = 100.0
    fc = st["raw_clip"]                                       # flat-top sample pairs in the raw take
    # vanilla hits are mastered hot (zombie woodbreak ~900 flat samples); only heavy clipping is a fault
    # takes are declipped (cubic spline over full-scale runs) before scoring, so only
    # heavy clipping, where the waveform shape is really lost, still costs points
    lim = (700, 2500) if (t.get("impact") or t["cat"] in ("combat", "raid", "voice", "tavern")) else (250, 900)
    s -= 0 if fc < lim[0] else (8 if fc < lim[1] else 30)
    want = t.get("maxlen") or t["dur"]
    if not t.get("loop"):
        ratio = st["dur"] / max(0.05, want)
        if ratio < 0.35: s -= 25                               # too thin
        if st["dur"] > want * 1.01: s -= 5
    exp_b = t.get("bursts")
    if exp_b and st["bursts"] != exp_b:
        s -= min(30, 12 * abs(st["bursts"] - exp_b))
    if t.get("impact") and st["attack"] > 0.12:
        s -= 15                                               # contact sounds must hit at the start
    if st["floor"] > -18 and not t.get("loop") and not t.get("sustained"):
        s -= 10                                               # noisy bed under a one-shot
    if st["hf"] > 0.25 and t.get("lofi", 0.3) > 0:
        s -= 5                                                # still too hi-fi / hissy
    # graded terms so equally "clean" takes still rank:
    if not t.get("loop") and not t.get("sustained"):
        s -= 0.25 * max(0.0, st["floor"] + 50)                # quieter bed under the hit is better
    if t.get("impact"):
        s -= 20 * max(0.0, st["dec30"] - 0.5 * want)          # vanilla hits are gone within ~0.1-0.3 s
    s -= 3 * max(0, st["bursts"] - (exp_b or st["bursts"]))
    return round(s, 1)


def spectral_sig(x):
    from scipy import signal
    f, p = signal.welch(x, A.SR, nperseg=1024)
    b = np.log10(np.maximum(1e-12, np.array([p[(f >= lo) & (f < hi)].sum() for lo, hi in
                    zip([0, 150, 300, 600, 1200, 2400, 4800, 9600], [150, 300, 600, 1200, 2400, 4800, 9600, 22050])])))
    return (b - b.mean()) / (b.std() + 1e-9)


def pick(t, results, n):
    ranked = sorted(results, key=lambda r: -r["score"])
    chosen = []
    for r in ranked:
        if r["score"] < 50:
            continue
        if all(np.corrcoef(r["sig"], c["sig"])[0, 1] < 0.995 or abs(r["st"]["dur"] - c["st"]["dur"]) > 0.05
               for c in chosen):
            chosen.append(r)
        if len(chosen) >= n:
            break
    if len(chosen) < n:  # fill with the best remaining if strict filters left gaps
        for r in ranked:
            if r not in chosen and r["score"] >= 40:
                chosen.append(r)
            if len(chosen) >= n:
                break
    return chosen


def sheet(t, results, chosen, out):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    k = len(results)
    fig, axes = plt.subplots(k, 2, figsize=(11, 1.25 * k + 0.6), squeeze=False,
                             gridspec_kw={"width_ratios": [1, 1.3]})
    for i, r in enumerate(results):
        x = r["x"]; tt = np.arange(len(x)) / A.SR
        a0, a1 = axes[i]
        a0.plot(tt, x, lw=0.4, color="#2a6" if r in chosen else "#888")
        a0.set_ylim(-1, 1); a0.set_yticks([])
        a0.set_title(f"{r['name']}  s={r['score']}  {r['st']['dur']}s  b={r['st']['bursts']}  "
                     f"clip={r['st']['raw_clip']}  cen={r['st']['centroid']}", fontsize=7, loc="left")
        if len(x) > 512:
            a1.specgram(x, NFFT=512, Fs=A.SR, noverlap=384, cmap="magma", vmin=-120)
        a1.set_ylim(0, 16000); a1.set_yticks([]); a0.tick_params(labelsize=6); a1.tick_params(labelsize=6)
    fig.suptitle(f"{t['event']} — {t['prompt'][:110]}", fontsize=8)
    fig.tight_layout()
    fig.savefig(out, dpi=80)
    plt.close(fig)


def run(t, overrides=None, quiet=False):
    raw_dir = os.path.join(WORK, "raw", t["event"])
    files = sorted(glob.glob(os.path.join(raw_dir, "c*.wav")) + glob.glob(os.path.join(raw_dir, "c*.mp3")))
    if not files:
        return None
    proc_dir = os.path.join(WORK, "proc", t["event"])
    os.makedirs(proc_dir, exist_ok=True)
    results = []
    for f in files:
        name = os.path.splitext(os.path.basename(f))[0]
        x, st = process_one(t, f)
        r = {"name": name, "x": x, "st": st, "score": score(t, st), "sig": spectral_sig(x)}
        A.encode_ogg(x, os.path.join(proc_dir, name + ".ogg"))
        results.append(r)
    picks = load_picks()
    if overrides:
        chosen = [r for nm in overrides for r in results if r["name"] == nm]
    elif t["event"] in picks and picks[t["event"]].get("manual"):
        chosen = [r for nm in picks[t["event"]]["files"] for r in results if r["name"] == nm]
    else:
        chosen = pick(t, results, t["n"])
    picks[t["event"]] = {"files": [r["name"] for r in chosen], "manual": bool(overrides) or
                         bool(picks.get(t["event"], {}).get("manual")),
                         "stats": {r["name"]: dict(r["st"], score=r["score"]) for r in results}}
    save_picks(picks)
    os.makedirs(os.path.join(WORK, "sheets"), exist_ok=True)
    sheet(t, results, chosen, os.path.join(WORK, "sheets", t["event"] + ".png"))
    if not quiet:
        print(f"{t['event']}: picked {[r['name'] for r in chosen]} of {len(results)}  " +
              "  ".join(f"{r['name']}:{r['score']}" for r in results))
    return chosen


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--event", action="append")
    ap.add_argument("--cat", action="append")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--override", action="append", help="event=c01,c03")
    a = ap.parse_args()
    ov = {}
    for o in a.override or []:
        ev, names = o.split("=")
        ov[ev] = names.split(",")
    sel = [t for t in TARGETS if a.all or (a.event and t["event"] in a.event) or (a.cat and t["cat"] in a.cat)
           or t["event"] in ov]
    for t in sel:
        run(t, ov.get(t["event"]))


if __name__ == "__main__":
    main()
