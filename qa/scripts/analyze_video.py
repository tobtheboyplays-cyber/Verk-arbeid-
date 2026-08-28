#!/usr/bin/env python3
"""Turn a long gameplay video into evidence Claude can actually read.

Claude cannot watch video. It CAN read images. This tool converts an owner's
recorded session (any length, any container ffmpeg can open) into a layered,
navigable set of stills, so a 60-90 minute film becomes reviewable in tens of
Read calls instead of not at all:

  LAYER 0  overview.png      one 8x8 grid sampling the WHOLE video evenly --
                             a single Read gives the shape of the session.
  LAYER 1  sheets/*.png      contact sheets: every Nth second (default 4s),
                             30 tiles per sheet, timestamp burned into every
                             tile. One Read = ~2 minutes of gameplay.
  LAYER 2  scenes/*.png      full-resolution frames at hard visual cuts
                             (scene score > threshold) -- menu opens, screen
                             changes, teleports.
  LAYER 3  marks/*.png       full-res frames where AUDIO comes alive after
                             silence -- with a talking player, these are the
                             "se her!" moments; with constant game audio the
                             index says so and the layer is ignored.
  LAYER 4  transcript.md    the owner's own voice, timestamped -- THE
                             feedback channel. faster-whisper (small, int8,
                             VAD-filtered) transcribes every spoken segment;
                             each line carries [hh:mm:ss] so a complaint maps
                             straight to a sheet tile, a still and a burst.
  index.md                   the map: duration, per-sheet time ranges, scene
                             and audio-mark timestamp lists.

Then, once something interesting is FOUND on a sheet, drill in:

  still <video> <ts>              one full-res frame at an exact time
  burst <video> <ts> [--seconds 8 --fps 2]
                                  a 2fps strip around a moment (animation
                                  judgement needs motion, not one pose)

Usage:
  python3 qa/scripts/analyze_video.py ingest <video> [--interval 4]
      [--scene 0.4] [--out DIR]
  python3 qa/scripts/analyze_video.py still <video> <mm:ss | seconds>
  python3 qa/scripts/analyze_video.py burst <video> <mm:ss> [--seconds 8]
      [--fps 2]

Output root defaults to qa/reports/artifacts/video-analysis/<UTC-stamp>/.
Everything is plain ffmpeg + stdlib; no network, no model calls.
"""
import argparse
import json
import math
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
TILE_W, TILE_H = 426, 240          # 16:9 tile; 6x5 grid -> 2556x1200 sheet
GRID_COLS, GRID_ROWS = 6, 5
TILES_PER_SHEET = GRID_COLS * GRID_ROWS
SCENE_CAP = 150                     # Minecraft camera motion is noisy; cap
MARK_CAP = 120


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw)


def probe(video):
    out = run(["ffprobe", "-v", "quiet", "-print_format", "json",
               "-show_format", "-show_streams", str(video)]).stdout
    data = json.loads(out)
    dur = float(data["format"]["duration"])
    vstream = next(s for s in data["streams"] if s["codec_type"] == "video")
    has_audio = any(s["codec_type"] == "audio" for s in data["streams"])
    return data, dur, int(vstream["width"]), int(vstream["height"]), has_audio


def hms(t):
    t = int(t)
    return f"{t // 3600:02d}:{(t % 3600) // 60:02d}:{t % 60:02d}"


def parse_ts(s):
    parts = str(s).split(":")
    if len(parts) == 1:
        return float(parts[0])
    if len(parts) == 2:
        return int(parts[0]) * 60 + float(parts[1])
    return int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])


def extract_still(video, ts, dest, max_w=None):
    """One frame. -ss BEFORE -i: input seeking, fast on long files. max_w
    downscales (1280 is plenty for navigation stills; full res on demand
    via the `still` subcommand)."""
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
           "-ss", f"{ts:.3f}", "-i", str(video), "-frames:v", "1"]
    if max_w:
        cmd += ["-vf", f"scale='min({max_w},iw)':-2"]
    run(cmd + [str(dest)])


def cmd_ingest(args):
    """The fast path: ONE keyframe-only video decode, everything else derived.

    The first version of this command decoded the full stream FOUR times
    (sheets, overview, scene detection, silencedetect) -- on a 1440p60 HEVC
    recording that is ~24,000 frames decoded per pass to keep ~130. This
    version decodes ONLY keyframes (-skip_frame nokey; a screen recorder
    keyframes every 2-4s, which matches the sampling interval anyway) and
    does it ONCE: sheets, the overview grid and scene detection are all
    computed from the extracted tiles with PIL, and audio work runs on a
    16kHz mono wav extracted in ~2s. Transcription (the long pole) is
    launched as a PARALLEL subprocess before frame work starts, so wall
    time ~= max(tiles, whisper), not their sum. Measured on the first real
    owner session (6:41, 2560x1440@60 HEVC): frames+sheets+scenes in well
    under a minute against ~13 minutes for the old path."""
    import itertools
    from PIL import Image, ImageChops, ImageStat

    video = Path(args.video)
    if not video.is_file():
        sys.exit(f"no such file: {video}")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out = Path(args.out) if args.out else \
        Path("qa/reports/artifacts/video-analysis") / stamp
    tiles_dir = out / "tiles"
    for d in ("tiles", "sheets", "scenes", "marks"):
        (out / d).mkdir(parents=True, exist_ok=True)

    data, dur, w, h, has_audio = probe(video)
    (out / "probe.json").write_text(json.dumps(data, indent=2))
    print(f"video: {video.name}  {w}x{h}  {hms(dur)}  audio={has_audio}")

    # ---- transcription FIRST, in parallel: it is the long pole ----
    tproc = None
    if has_audio and not args.no_transcript:
        tproc = subprocess.Popen(
            [sys.executable, __file__, "transcribe", str(video),
             "--language", args.language, "--model", args.model,
             "--out", str(out)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    interval = args.interval
    # ---- the ONE video decode: keyframes only, small timestamped tiles ----
    vf = (
        f"fps=1/{interval},"
        f"scale={TILE_W}:{TILE_H}:force_original_aspect_ratio=decrease,"
        f"pad={TILE_W}:{TILE_H}:(ow-iw)/2:(oh-ih)/2,"
        f"drawtext=fontfile={FONT}:text='%{{pts\\:hms}}':x=6:y=h-th-6:"
        f"fontsize=22:fontcolor=white:borderw=2:bordercolor=black"
    )
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-skip_frame", "nokey", "-i", str(video),
         "-vf", vf, "-vsync", "vfr", str(tiles_dir / "t-%04d.png")])
    tiles = sorted(tiles_dir.glob("t-*.png"))
    print(f"tiles: {len(tiles)} (keyframe-only decode, {interval}s interval)")

    # ---- sheets: PIL paste, no second decode ----
    sheet_span = interval * TILES_PER_SHEET
    sheets = []
    for i in range(0, len(tiles), TILES_PER_SHEET):
        chunk = tiles[i:i + TILES_PER_SHEET]
        sheet = Image.new("RGB", (TILE_W * GRID_COLS, TILE_H * GRID_ROWS), 0)
        for j, tp in enumerate(chunk):
            with Image.open(tp) as im:
                sheet.paste(im, ((j % GRID_COLS) * TILE_W,
                                 (j // GRID_COLS) * TILE_H))
        name = out / "sheets" / f"sheet-{i // TILES_PER_SHEET + 1:03d}.png"
        sheet.save(name)
        sheets.append(name)
    print(f"sheets: {len(sheets)} ({sheet_span}s per sheet)")

    # ---- overview: 64 tiles sampled evenly, same source ----
    if tiles:
        picks = [tiles[min(int(k * len(tiles) / 64), len(tiles) - 1)]
                 for k in range(min(64, len(tiles)))]
        ov = Image.new("RGB", (320 * 8, 180 * 8), 0)
        for j, tp in enumerate(picks):
            with Image.open(tp) as im:
                ov.paste(im.resize((320, 180)),
                         ((j % 8) * 320, (j // 8) * 180))
        ov.save(out / "overview.png")

    # ---- scenes: consecutive-tile difference, decode-free; stills via
    #      fast keyframe seeks only for the winners ----
    scene_ts = []
    prev = None
    for idx, tp in enumerate(tiles):
        im = Image.open(tp).convert("L").resize((64, 36))
        if prev is not None:
            diff = ImageStat.Stat(ImageChops.difference(im, prev)).mean[0]
            if diff > args.scene_diff:
                scene_ts.append(idx * interval)
        prev = im
    # 24 navigation stills, extracted in PARALLEL and downscaled to 1280w.
    # The first fast version did 100+ sequential full-res seeks here and in
    # marks -- ~2.5 of the 3.3 minutes of the whole run. Sheets carry the
    # coverage; these are jump points, and `still` fetches full res on
    # demand.
    from concurrent.futures import ThreadPoolExecutor
    keep = scene_ts[:24]
    dropped = max(0, len(scene_ts) - len(keep))
    with ThreadPoolExecutor(max_workers=4) as ex:
        list(ex.map(lambda ts: extract_still(
            video, ts,
            out / "scenes" / f"scene-{hms(ts).replace(':', '')}-{ts:.0f}s.png",
            max_w=1280), keep))
    print(f"scenes: {len(keep)} extracted"
          + (f" ({dropped} beyond cap, timestamps in index)" if dropped else ""))

    # ---- audio marks from the wav (audio-only decode, seconds) ----
    marks, speech_share = [], None
    wav = out / "audio16k.wav"
    if has_audio:
        if not wav.exists():
            run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                 "-i", str(video), "-vn", "-ac", "1", "-ar", "16000", str(wav)])
        r = subprocess.run(
            ["ffmpeg", "-hide_banner", "-i", str(wav),
             "-af", "silencedetect=noise=-30dB:d=1.0", "-f", "null", "-"],
            capture_output=True, text=True)
        silence, cur = [], None
        for line in r.stderr.splitlines():
            ms = re.search(r"silence_start: ([0-9.]+)", line)
            me = re.search(r"silence_end: ([0-9.]+)", line)
            if ms:
                cur = float(ms.group(1))
            elif me and cur is not None:
                silence.append((cur, float(me.group(1))))
                cur = None
        if cur is not None:
            silence.append((cur, dur))
        speech_share = 1.0 - sum(e - s for s, e in silence) / dur if dur else 0.0
        pos = 0.0
        for s0, e0 in silence + [(dur, dur)]:
            if s0 - pos > 0.6:
                marks.append(pos)
            pos = e0
        # Timestamps only -- the transcript names WHAT was said at each mark
        # and the sheets show the scene; extracting 50+ stills here was the
        # other half of the old runtime for evidence nothing read twice.
        print(f"audio marks: {min(len(marks), MARK_CAP)} (timestamps in index), "
              f"non-silent share {max(speech_share, 0):.0%}")

    # ---- wait for the parallel transcription ----
    if tproc is not None:
        tout, _ = tproc.communicate()
        print(tout.strip().splitlines()[-1] if tout.strip() else "transcribe: done")

    # ---- the map ----
    lines = [
        f"# Video analysis — {video.name}",
        "",
        f"- duration **{hms(dur)}**, {w}x{h}, audio={'yes' if has_audio else 'NO'}",
        f"- ingested {stamp}, interval {interval}s (keyframe-sampled)",
        "",
        "## How to read this (for Claude)",
        "1. Read `transcript.md` IN FULL first — the narration IS the",
        "   feedback; every frame is its evidence.",
        "2. Read `overview.png` — the whole session in one image.",
        "3. For each transcript moment, open the sheet covering that",
        "   timestamp; drill with `still`/`burst` as needed.",
        "",
        "## Sheets",
    ]
    for i, sh in enumerate(sheets, 1):
        lo = (i - 1) * sheet_span
        hi_t = min(i * sheet_span, dur)
        lines.append(f"- `sheets/{sh.name}` — {hms(lo)} → {hms(hi_t)}")
    lines += ["", "## Scene cuts (tile-diff)"]
    lines += [f"- {hms(t)}" for t in scene_ts[:SCENE_CAP]] or ["- none"]
    lines += ["", "## Audio marks (sound after ≥1s silence)"]
    if has_audio:
        if speech_share is not None and speech_share > 0.85:
            lines.append(f"- NOTE: non-silent {speech_share:.0%} of runtime — "
                         "constant audio, marks carry little signal")
        lines += [f"- {hms(t)}" for t in marks[:MARK_CAP]] or ["- none"]
    else:
        lines.append("- video has no audio track")
    (out / "index.md").write_text("\n".join(lines) + "\n")
    print(f"DONE -> {out}")


def transcribe(video, out, language="no", model_name="small"):
    """The owner narrates; this turns the narration into timestamped text.

    faster-whisper on CPU (4 threads, int8). VAD on, so long stretches of
    pure game audio are skipped rather than hallucinated into words -- with
    intermittent speech this also makes a long video several times faster to
    transcribe. Returns the transcript path or None if the import fails
    (the tool must still work as a pure frame extractor without it)."""
    try:
        from faster_whisper import WhisperModel
    except ImportError:
        print("transcribe: faster-whisper not installed -- skipping")
        return None
    wav = out / "audio16k.wav"
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-i", str(video), "-vn", "-ac", "1", "-ar", "16000", str(wav)])
    model = WhisperModel(model_name, device="cpu", compute_type="int8",
                         cpu_threads=4)
    segs, info = model.transcribe(str(wav), language=language,
                                  vad_filter=True,
                                  vad_parameters={"min_silence_duration_ms": 700})
    lines = [f"# Transcript — {Path(video).name}",
             f"(model={model_name}, language={info.language}, "
             f"prob={info.language_probability:.2f}; VAD-filtered)", ""]
    n = 0
    for seg in segs:
        text = seg.text.strip()
        if text:
            lines.append(f"[{hms(seg.start)}] {text}")
            n += 1
    path = out / "transcript.md"
    path.write_text("\n".join(lines) + "\n")
    wav.unlink(missing_ok=True)
    print(f"transcript: {n} spoken segments -> {path}")
    return path


def cmd_still(args):
    ts = parse_ts(args.ts)
    out = Path(args.out) if args.out else Path(args.video).with_suffix("")
    out = Path(str(out) + f"-still-{ts:.1f}s.png")
    extract_still(Path(args.video), ts, out)
    print(out)


def cmd_burst(args):
    ts = parse_ts(args.ts)
    half = args.seconds / 2.0
    start = max(0.0, ts - half)
    outdir = Path(args.out) if args.out else \
        Path(args.video).parent / f"burst-{ts:.1f}s"
    outdir.mkdir(parents=True, exist_ok=True)
    vf = (f"fps={args.fps},"
          f"drawtext=fontfile={FONT}:text='%{{pts\\:hms}} +{start:.1f}s':"
          f"x=8:y=h-th-8:fontsize=28:fontcolor=white:borderw=2:bordercolor=black")
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-ss", f"{start:.3f}", "-i", str(args.video),
         "-t", f"{args.seconds:.3f}", "-vf", vf,
         str(outdir / "frame-%03d.png")])
    frames = sorted(outdir.glob("frame-*.png"))
    print(f"{len(frames)} frames ({args.fps}fps, {hms(start)} -> "
          f"{hms(start + args.seconds)}) -> {outdir}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("ingest", help="full layered analysis of a video")
    p.add_argument("video")
    p.add_argument("--interval", type=int, default=4)
    p.add_argument("--scene-diff", type=float, default=28.0,
                   help="mean 0-255 luma diff between consecutive tiles that counts as a cut")
    p.add_argument("--out")
    p.add_argument("--language", default="no",
                   help="narration language for whisper (default Norwegian)")
    p.add_argument("--model", default="base",
                   help="faster-whisper model. base = the 1-minute default "
                        "(measured: small took 135s on a 6:41 session, base "
                        "~2.5x faster); pass small when transcript nuance "
                        "matters more than wall time")
    p.add_argument("--no-transcript", action="store_true")
    p.set_defaults(fn=cmd_ingest)
    p = sub.add_parser("transcribe", help="narration -> timestamped text only")
    p.add_argument("video")
    p.add_argument("--language", default="no")
    p.add_argument("--model", default="small")
    p.add_argument("--out")
    p.set_defaults(fn=lambda a: transcribe(Path(a.video),
        Path(a.out) if a.out else Path(a.video).parent,
        language=a.language, model_name=a.model))
    p = sub.add_parser("still", help="one full-res frame at a timestamp")
    p.add_argument("video"); p.add_argument("ts"); p.add_argument("--out")
    p.set_defaults(fn=cmd_still)
    p = sub.add_parser("burst", help="frame strip around a moment")
    p.add_argument("video"); p.add_argument("ts")
    p.add_argument("--seconds", type=float, default=8.0)
    p.add_argument("--fps", type=float, default=2.0)
    p.add_argument("--out")
    p.set_defaults(fn=cmd_burst)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
