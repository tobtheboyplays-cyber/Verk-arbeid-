"""ElevenLabs Sound Effects generator for the Bannerhold (modid hearthstead) sound lane.

Usage:
    python gen.py --probe                      # one cheap call, measures real credit cost
    python gen.py --event combat.swing_light   # generate candidates for one target
    python gen.py --cat combat --pri 1         # every P1 combat target
    python gen.py --all --pri 1 --dry          # show plan + credit estimate, no calls

Security: the API key is read ONLY from the secrets file below, inside this
process, and sent ONLY to https://api.elevenlabs.io as the xi-api-key header.
It is never printed, logged, written or passed on a command line.

Raw candidates go to WORK (outside the repo). Nothing here touches the mod.
"""
import argparse, json, os, sys, time, datetime, wave, threading
from concurrent.futures import ThreadPoolExecutor
import requests

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from targets import TARGETS, candidates_for, est_credits  # noqa: E402

KEY_FILE = r"C:\Users\tobia\Hearthstead-Claude\secrets\elevenlabs.key"
API = "https://api.elevenlabs.io"
WORK = r"C:\Users\tobia\Hearthstead-Claude\sound-gen-work"
LEDGER = os.path.join(HERE, "LEDGER.md")
LEDGER_JSON = os.path.join(WORK, "ledger.json")
HARD_CAP = 118_000          # raised by the lead 26 Sep for the merchant/peddler redo (was 110k)
FORMATS = ["pcm_48000", "mp3_44100_192", "mp3_44100_128"]


def _key():
    with open(KEY_FILE, "r", encoding="utf-8") as fh:
        k = fh.read().strip()
    if not k:
        raise SystemExit("key file is empty")
    return k


def _session():
    s = requests.Session()
    s.headers.update({"xi-api-key": _key(), "Content-Type": "application/json"})
    return s


def _load_ledger():
    if os.path.exists(LEDGER_JSON):
        with open(LEDGER_JSON, encoding="utf-8") as fh:
            return json.load(fh)
    return {"total": 0, "calls": 0, "entries": [], "format": None, "baseline": None}


def _save_ledger(led):
    os.makedirs(WORK, exist_ok=True)
    with open(LEDGER_JSON, "w", encoding="utf-8") as fh:
        json.dump(led, fh, indent=1)
    by_event = {}
    for e in led["entries"]:
        b = by_event.setdefault(e["event"], [0, 0])
        b[0] += 1; b[1] += e["credits"]
    lines = ["# ElevenLabs credit ledger (sound lane)", "",
             f"Hard cap: {HARD_CAP:,} credits (ask the lead before going past it).", "",
             f"**Total used: {led['total']:,} credits over {led['calls']} generations** (ledger, 40 credits per requested "
             f"second, TTS labels at 1 per character).", "",
             f"Account's own counter at last read: {led.get('account_used', 'n/a')} of {led.get('limit', 'n/a')} "
             "(it lags and has read lower than the ledger; the ledger is the conservative figure used for the cap).", "",
             f"Output format in use: `{led.get('format')}` (interleaved stereo, folded to mono in post).", "",
             "## Per target", "", "| Target | Generations | Credits |", "|---|---:|---:|"]
    for ev in sorted(by_event):
        lines.append(f"| `{ev}` | {by_event[ev][0]} | {by_event[ev][1]:,} |")
    lines += ["", "## Log (newest last)", "", "| Time | Target | Cand | Dur (s) | Credits | Running |",
              "|---|---|---:|---:|---:|---:|"]
    run = 0
    for e in led["entries"]:
        run += e["credits"]
        lines.append(f"| {e['time']} | `{e['event']}` | {e['cand']} | {e['dur']} | {e['credits']} | {run:,} |")
    with open(LEDGER, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")


def _used(s):
    """Account character_count (credits used this period), or None if the key lacks that scope."""
    try:
        r = s.get(API + "/v1/user/subscription", timeout=20)
        if r.ok:
            j = r.json()
            return int(j.get("character_count")), int(j.get("character_limit"))
    except Exception:
        pass
    return None


def _write_pcm_wav(path, data, sr, dur):
    # ElevenLabs returns interleaved stereo s16le for pcm_* sound effects; infer the channel count
    # from the byte length against the requested duration so a mono reply also works.
    ch = 2 if abs(len(data) / (sr * 2 * dur) - 2) < abs(len(data) / (sr * 2 * dur) - 1) else 1
    with wave.open(path, "wb") as w:
        w.setnchannels(ch); w.setsampwidth(2); w.setframerate(sr); w.writeframes(data)


def generate(s, led, event, idx, prompt, dur, infl, loop=False):
    out_dir = os.path.join(WORK, "raw", event)
    os.makedirs(out_dir, exist_ok=True)
    body = {"text": prompt, "duration_seconds": max(0.5, round(dur, 2)),
            "prompt_influence": infl, "model_id": "eleven_text_to_sound_v2"}
    if loop:
        body["loop"] = True
    fmts = [led["format"]] if led.get("format") else FORMATS
    last_err = None
    for fmt in fmts:
        r = s.post(API + "/v1/sound-generation", params={"output_format": fmt}, json=body, timeout=180)
        if r.ok:
            led["format"] = fmt
            if fmt.startswith("pcm_"):
                path = os.path.join(out_dir, f"c{idx:02d}.wav")
                _write_pcm_wav(path, r.content, int(fmt.split("_")[1]), body["duration_seconds"])
            else:
                path = os.path.join(out_dir, f"c{idx:02d}.mp3")
                with open(path, "wb") as fh:
                    fh.write(r.content)
            break
        last_err = f"{r.status_code} {r.text[:300]}"
        if r.status_code in (401, 429) or r.status_code >= 500:
            break
    else:
        path = None
    if path is None:
        raise RuntimeError(f"generation failed for {event}: {last_err}")
    # 40 credits per requested second (verified against the account on the probe call);
    # the run as a whole is reconciled against the account's character_count in main().
    cost = int(round(40 * body["duration_seconds"]))
    with LOCK:
        led["total"] += cost; led["calls"] += 1
        led["entries"].append({"time": datetime.datetime.now().strftime("%m-%d %H:%M:%S"), "event": event,
                               "cand": idx, "dur": body["duration_seconds"], "credits": cost})
        meta = {"prompt": prompt, "duration_seconds": body["duration_seconds"], "prompt_influence": infl,
                "loop": loop, "format": led["format"], "credits": cost, "file": os.path.basename(path)}
        with open(os.path.join(out_dir, f"c{idx:02d}.json"), "w", encoding="utf-8") as fh:
            json.dump(meta, fh, indent=1)
        _save_ledger(led)
    return path, cost


LOCK = threading.Lock()


def run_job(s, led, t, i, p):
    with LOCK:
        if led["total"] + est_credits(t, p) > HARD_CAP:
            raise SystemExit(f"STOP: next call would pass the {HARD_CAP:,} credit cap (at {led['total']:,}). Ask the lead.")
    for attempt in range(4):
        try:
            path, cost = generate(s, led, t["event"], i, p["prompt"], p["dur"], p["infl"], t.get("loop", False))
            print(f"  {t['event']} c{i:02d} {p['dur']}s -> {cost} cr (total {led['total']:,})", flush=True)
            return
        except (RuntimeError, requests.RequestException) as e:
            msg = str(e)
            print("  !", t["event"], msg[:200], flush=True)
            if "429" in msg or "failed for" not in msg or msg.split(": ")[-1].startswith("5"):
                time.sleep(8 * (attempt + 1)); continue
            return


def jobs_for(t, extra=0):
    plan = candidates_for(t, extra)
    out_dir = os.path.join(WORK, "raw", t["event"])
    have = {f[:3] for f in os.listdir(out_dir) if f.endswith((".wav", ".mp3"))} if os.path.isdir(out_dir) else set()
    return [(t, i, p) for i, p in enumerate(plan) if f"c{i:02d}" not in have]


def run_target(s, led, t, extra=0, dry=False):
    plan = candidates_for(t, extra)
    out_dir = os.path.join(WORK, "raw", t["event"])
    have = {f[:3] for f in os.listdir(out_dir)} if os.path.isdir(out_dir) else set()
    todo = [(i, p) for i, p in enumerate(plan) if f"c{i:02d}" not in have]
    if dry:
        return sum(est_credits(t, p) for _, p in todo), len(todo)
    for i, p in todo:
        est = est_credits(t, p)
        if led["total"] + est > HARD_CAP:
            raise SystemExit(f"STOP: next call would pass the {HARD_CAP:,} credit cap (at {led['total']:,}). Ask the lead.")
        for attempt in range(3):
            try:
                path, cost = generate(s, led, t["event"], i, p["prompt"], p["dur"], p["infl"], t.get("loop", False))
                print(f"  {t['event']} c{i:02d} {p['dur']}s -> {cost} cr (total {led['total']:,})", flush=True)
                break
            except RuntimeError as e:
                msg = str(e)
                print("  !", msg[:200], flush=True)
                if "429" in msg or msg.split(": ")[-1].startswith("5"):
                    time.sleep(10 * (attempt + 1)); continue
                break
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--event", action="append")
    ap.add_argument("--cat", action="append")
    ap.add_argument("--pri", type=int)
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--extra", type=int, default=0, help="extra candidates beyond the plan")
    ap.add_argument("--dry", action="store_true")
    ap.add_argument("--probe", action="store_true")
    ap.add_argument("--workers", type=int, default=4)
    a = ap.parse_args()
    led = _load_ledger()
    if a.probe:
        s = _session()
        u = _used(s)
        print("subscription read:", "ok" if u else "unavailable (key lacks user scope)",
              f"used {u[0]:,}/{u[1]:,}" if u else "")
        led["baseline"] = u[0] if u else None
        t = next(x for x in TARGETS if x["event"] == "fx.coin_sale")
        p = candidates_for(t)[0]   # the probe doubles as the first real candidate
        path, cost = generate(s, led, t["event"], 0, p["prompt"], p["dur"], p["infl"])
        print("probe:", os.path.basename(path), "cost", cost, "format", led["format"])
        return
    sel = [t for t in TARGETS
           if (a.all or (a.event and t["event"] in a.event) or (a.cat and t["cat"] in a.cat))
           and (a.pri is None or t["pri"] <= a.pri)]
    if not sel:
        raise SystemExit("no targets selected")
    if a.dry:
        tot = n = 0
        for t in sel:
            c, k = run_target(None, led, t, a.extra, dry=True); tot += c; n += k
            print(f"{t['event']:34s} P{t['pri']} {k:2d} gens ~{c:5d} cr")
        print(f"TOTAL {n} gens ~{tot:,} credits (ledger so far {led['total']:,})")
        return
    s = _session()
    start = _used(s)
    jobs = [j for t in sel for j in jobs_for(t, a.extra)]
    est = sum(est_credits(t, p) for t, _, p in jobs)
    if led["total"] + est > HARD_CAP:
        raise SystemExit(f"STOP: this run (~{est:,}) would pass the {HARD_CAP:,} cap (at {led['total']:,}). Ask the lead.")
    print(f"{len(jobs)} generations, ~{est:,} credits, {a.workers} parallel", flush=True)
    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        list(ex.map(lambda j: run_job(s, led, *j), jobs))
    end = _used(s)
    if start and end:
        led["account_used"] = end[0]; led["limit"] = end[1]
        print(f"account delta this run: {end[0] - start[0]:,} (ledger estimate {est:,})")
        _save_ledger(led)
    print(f"done. ledger total {led['total']:,} credits, {led['calls']} calls")


if __name__ == "__main__":
    main()
