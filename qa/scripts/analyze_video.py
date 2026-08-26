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


def extract_still(video, ts, dest):
    """One full-res frame. -ss BEFORE -i: input seeking, fast on long files."""
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-ss", f"{ts:.3f}", "-i", str(video), "-frames:v", "1", str(dest)])


def cmd_ingest(args):
    video = Path(args.video)
    if not video.is_file():
        sys.exit(f"no such file: {video}")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out = Path(args.out) if args.out else \
        Path("qa/reports/artifacts/video-analysis") / stamp
    (out / "sheets").mkdir(parents=True, exist_ok=True)
    (out / "scenes").mkdir(exist_ok=True)
    (out / "marks").mkdir(exist_ok=True)

    data, dur, w, h, has_audio = probe(video)
    (out / "probe.json").write_text(json.dumps(data, indent=2))
    print(f"video: {video.name}  {w}x{h}  {hms(dur)}  audio={has_audio}")

    # ---- LAYER 1: interval contact sheets, timestamp burned per tile ----
    interval = args.interval
    # fps=1/interval snaps output pts to the interval grid, so %{pts} on each
    # tile IS the source-time of that tile (within one interval).
    vf = (
        f"fps=1/{interval},"
        f"scale={TILE_W}:{TILE_H}:force_original_aspect_ratio=decrease,"
        f"pad={TILE_W}:{TILE_H}:(ow-iw)/2:(oh-ih)/2,"
        f"drawtext=fontfile={FONT}:text='%{{pts\\:hms}}':x=6:y=h-th-6:"
        f"fontsize=22:fontcolor=white:borderw=2:bordercolor=black,"
        f"tile={GRID_COLS}x{GRID_ROWS}"
    )
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-i", str(video), "-vf", vf, "-vsync", "vfr",
         str(out / "sheets" / "sheet-%03d.png")])
    sheets = sorted((out / "sheets").glob("sheet-*.png"))
    sheet_span = interval * TILES_PER_SHEET
    print(f"sheets: {len(sheets)} ({interval}s interval, "
          f"{sheet_span}s per sheet)")

    # ---- LAYER 0: one whole-video overview grid (single Read) ----
    n = 64
    step = dur / n
    ov_vf = (
        f"fps=1/{step:.4f},"
        f"scale=320:180:force_original_aspect_ratio=decrease,"
        f"pad=320:180:(ow-iw)/2:(oh-ih)/2,"
        f"drawtext=fontfile={FONT}:text='%{{pts\\:hms}}':x=4:y=h-th-4:"
        f"fontsize=18:fontcolor=white:borderw=2:bordercolor=black,"
        f"tile=8x8"
    )
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-i", str(video), "-vf", ov_vf, "-vsync", "vfr",
         "-frames:v", "1", str(out / "overview.png")])

    # ---- LAYER 2: scene cuts (visual hard changes) ----
    scene_ts = []
    r = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", str(video),
         "-vf", f"select='gt(scene,{args.scene})',metadata=print",
         "-f", "null", "-"],
        capture_output=True, text=True)
    for m in re.finditer(r"pts_time:([0-9.]+)", r.stderr):
        scene_ts.append(float(m.group(1)))
    dropped_scenes = max(0, len(scene_ts) - SCENE_CAP)
    for ts in scene_ts[:SCENE_CAP]:
        extract_still(video, ts, out / "scenes" / f"scene-{hms(ts).replace(':', '')}-{ts:.1f}s.png")
    print(f"scenes: {min(len(scene_ts), SCENE_CAP)} extracted"
          + (f" ({dropped_scenes} beyond cap NOT extracted -- raise --scene "
             f"threshold and re-run if the capped list matters)" if dropped_scenes else ""))

    # ---- LAYER 3: audio coming alive after silence -> speech/event marks --
    marks, speech_share = [], None
    if has_audio:
        r = subprocess.run(
            ["ffmpeg", "-hide_banner", "-i", str(video),
             "-af", "silencedetect=noise=-30dB:d=1.0", "-f", "null", "-"],
            capture_output=True, text=True)
        silence = []   # (start, end)
        cur = None
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
        silent_total = sum(e - s for s, e in silence)
        speech_share = 1.0 - silent_total / dur if dur else 0.0
        # Non-silent segment STARTS are the marks: sound after quiet.
        pos = 0.0
        for s, e in silence + [(dur, dur)]:
            if s - pos > 0.6:
                marks.append(pos)
            pos = e
        dropped_marks = max(0, len(marks) - MARK_CAP)
        for ts in marks[:MARK_CAP]:
            extract_still(video, ts, out / "marks" / f"mark-{hms(ts).replace(':', '')}-{ts:.1f}s.png")
        print(f"audio marks: {min(len(marks), MARK_CAP)}"
              + (f" ({dropped_marks} beyond cap)" if dropped_marks else "")
              + f", non-silent share {speech_share:.0%}")

    # ---- LAYER 4: the owner's narration, timestamped ----
    if has_audio and not args.no_transcript:
        transcribe(video, out, language=args.language, model_name=args.model)

    # ---- the map ----
    lines = [
        f"# Video analysis — {video.name}",
        "",
        f"- duration **{hms(dur)}**, {w}x{h}, audio={'yes' if has_audio else 'NO'}",
        f"- ingested {stamp}, interval {interval}s, scene threshold {args.scene}",
        "",
        "## How to read this (for Claude)",
        "1. Read `overview.png` first — the whole session in one image.",
        "2. Scan `sheets/` in order; each sheet covers the range below.",
        "3. On anything interesting: `still <video> <ts>` for full res,",
        "   `burst <video> <ts>` for motion (animation verdicts need bursts).",
        "4. `scenes/` = hard visual cuts; `marks/` = audio-after-silence.",
        "5. `transcript.md` is the owner's narration with [hh:mm:ss] per",
        "   line — read it IN FULL first; it is the feedback, the frames",
        "   are its evidence. For every like/dislike, pull the sheet tile",
        "   covering that timestamp, then still/burst as needed.",
        "",
        "## Sheets",
    ]
    for i, s in enumerate(sheets, 1):
        lo, hi_t = (i - 1) * sheet_span, min(i * sheet_span, int(dur) + interval)
        lines.append(f"- `sheets/{s.name}` — {hms(lo)} → {hms(min(hi_t, dur))}")
    lines += ["", "## Scene cuts"]
    lines += [f"- {hms(t)} ({t:.1f}s)" for t in scene_ts[:SCENE_CAP]] or ["- none"]
    if dropped_scenes:
        lines.append(f"- …plus {dropped_scenes} beyond the {SCENE_CAP} cap (not extracted)")
    lines += ["", "## Audio marks (sound after ≥1s silence)"]
    if has_audio:
        if speech_share is not None and speech_share > 0.85:
            lines.append(f"- NOTE: audio is non-silent {speech_share:.0%} of the "
                         "time (constant game audio?) — marks carry little signal here")
        lines += [f"- {hms(t)} ({t:.1f}s)" for t in marks[:MARK_CAP]] or ["- none"]
    else:
        lines.append("- video has no audio track")
    (out / "index.md").write_text("\n".join(lines) + "\n")
    print(f"index: {out / 'index.md'}")
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
    p.add_argument("--scene", type=float, default=0.4)
    p.add_argument("--out")
    p.add_argument("--language", default="no",
                   help="narration language for whisper (default Norwegian)")
    p.add_argument("--model", default="small",
                   help="faster-whisper model (small = the CPU/Norwegian balance)")
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
