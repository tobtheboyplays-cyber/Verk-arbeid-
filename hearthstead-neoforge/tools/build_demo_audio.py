#!/usr/bin/env python3
"""Build the first-raid Hearthstead sound set from traceable CC0 raw foley.

This is a workstation tool, not runtime code. Spotify Pedalboard is used for
pitch, EQ, compression, and short-room treatment; no dependency is packaged
in the mod JAR. Every recipe below names its physical source recordings.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf
from pedalboard import (
    Compressor,
    Gain,
    HighpassFilter,
    LowpassFilter,
    Pedalboard,
    PitchShift,
    Reverb,
)
from scipy.signal import resample_poly


ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "tools" / "audio_sources" / "cc0_raw"
IMPACT = RAW / "kenney_impact"
RPG = RAW / "kenney_rpg"
OUT = ROOT / "src" / "main" / "resources" / "assets" / "hearthstead" / "sounds"
REPORT = ROOT / "build" / "reports" / "hearthstead" / "demo_audio_report.json"
SAMPLE_RATE = 44_100


@dataclass(frozen=True)
class Layer:
    source: Path
    gain_db: float
    offset: float = 0.0
    pitch: float = 0.0
    highpass_hz: float = 35.0
    lowpass_hz: float = 12_000.0
    reverse: bool = False


@dataclass(frozen=True)
class Recipe:
    duration: float
    peak_dbfs: float
    layers: tuple[Layer, ...]
    room: float = 0.0


def impact(name: str) -> Path:
    return IMPACT / name


def rpg(name: str) -> Path:
    return RPG / name


def L(source: Path, gain: float, offset: float = 0.0, pitch: float = 0.0,
      hp: float = 35.0, lp: float = 12_000.0, reverse: bool = False) -> Layer:
    return Layer(source, gain, offset, pitch, hp, lp, reverse)


# Durations for existing events deliberately retain their runtime contracts.
# New UI and sack-frame sounds have short, explicit contact windows.
RECIPES: dict[str, Recipe] = {
    # Physical book, wood, leather, and metal UI language. No hover event.
    "ui_open": Recipe(0.42, -13.0, (
        L(rpg("bookOpen.ogg"), -5.0, pitch=-1.5, lp=8_500),
        L(rpg("cloth1.ogg"), -12.0, 0.015, pitch=-2.0, lp=7_000),
        L(rpg("metalClick.ogg"), -15.0, 0.055, pitch=-2.0, hp=250, lp=9_000),
    ), room=0.035),
    "ui_close": Recipe(0.38, -13.0, (
        L(rpg("bookClose.ogg"), -5.0, pitch=-2.0, lp=8_000),
        L(impact("impactWood_light_001.ogg"), -12.0, 0.015, pitch=-4.0, lp=7_000),
        L(rpg("cloth2.ogg"), -14.0, 0.025, pitch=-2.0, lp=6_500),
    ), room=0.025),
    "ui_confirm": Recipe(0.30, -14.0, (
        L(rpg("metalClick.ogg"), -7.0, hp=300, lp=9_000),
        L(rpg("handleCoins2.ogg"), -18.0, 0.035, pitch=2.0, hp=500, lp=10_000),
        L(impact("impactBell_heavy_004.ogg"), -18.0, 0.060, pitch=8.0, hp=500, lp=9_000),
    ), room=0.03),
    "ui_error": Recipe(0.34, -13.0, (
        L(rpg("metalLatch.ogg"), -7.0, pitch=-4.0, lp=7_500),
        L(impact("impactWood_heavy_004.ogg"), -12.0, 0.035, pitch=-7.0, lp=3_800),
        L(rpg("bookClose.ogg"), -17.0, 0.055, pitch=-5.0, lp=5_500),
    ), room=0.02),

    # Hearth, recruitment, and emblem/job commitment.
    "hearth_founded": Recipe(3.00, -8.5, (
        L(impact("impactPlank_medium_003.ogg"), -11.0, pitch=-4.0, lp=6_000),
        L(impact("impactBell_heavy_000.ogg"), -8.0, 0.080, pitch=-5.0, hp=90, lp=9_000),
        L(impact("impactBell_heavy_001.ogg"), -10.0, 0.560, pitch=0.0, hp=100, lp=9_500),
        L(impact("impactBell_heavy_003.ogg"), -13.0, 1.020, pitch=5.0, hp=160, lp=10_000),
        L(rpg("bookPlace2.ogg"), -14.0, 0.200, pitch=-3.0, lp=6_000),
    ), room=0.22),
    "profession_assigned": Recipe(1.20, -10.5, (
        L(rpg("clothBelt.ogg"), -9.0, pitch=-2.0, lp=7_000),
        L(rpg("metalClick.ogg"), -10.0, 0.035, pitch=-1.0, hp=250, lp=9_500),
        L(impact("impactBell_heavy_004.ogg"), -14.0, 0.120, pitch=7.0, hp=350, lp=9_500),
    ), room=0.08),
    "settler_recruited": Recipe(2.20, -9.5, (
        L(rpg("bookPlace1.ogg"), -10.0, pitch=-2.0, lp=6_500),
        L(impact("impactWood_medium_002.ogg"), -11.0, 0.030, pitch=-3.0, lp=6_500),
        L(impact("impactBell_heavy_002.ogg"), -13.0, 0.180, pitch=2.0, hp=160, lp=9_000),
        L(impact("impactBell_heavy_004.ogg"), -16.0, 0.620, pitch=7.0, hp=300, lp=10_000),
        L(rpg("handleCoins.ogg"), -19.0, 0.340, pitch=-1.0, hp=450, lp=9_000),
    ), room=0.16),

    # Lumber: real axe/wood contact, physical pickup, and framed sack beats.
    "chop": Recipe(0.40, -9.5, (
        L(rpg("chop.ogg"), -5.0, pitch=-1.5, hp=55, lp=9_000),
        L(impact("impactWood_heavy_000.ogg"), -7.5, 0.006, pitch=-3.0, lp=6_000),
        L(impact("impactWood_medium_003.ogg"), -13.0, 0.026, pitch=1.0, hp=180, lp=8_500),
    )),
    "chop2": Recipe(0.40, -9.5, (
        L(rpg("chop.ogg"), -5.5, pitch=0.5, hp=60, lp=9_500),
        L(impact("impactWood_heavy_002.ogg"), -7.0, 0.004, pitch=-1.0, lp=6_500),
        L(impact("impactPlank_medium_001.ogg"), -15.0, 0.030, pitch=-2.0, lp=7_000),
    )),
    "chop3": Recipe(0.40, -9.5, (
        L(rpg("chop.ogg"), -5.0, pitch=-3.0, hp=50, lp=8_500),
        L(impact("impactWood_heavy_004.ogg"), -7.5, 0.008, pitch=-5.0, lp=5_500),
        L(impact("impactWood_light_003.ogg"), -14.0, 0.034, pitch=2.0, hp=200, lp=8_500),
    )),
    "item_pickup": Recipe(0.10, -14.0, (
        L(rpg("handleSmallLeather.ogg"), -7.0, pitch=1.0, hp=180, lp=8_000),
        L(impact("impactWood_light_004.ogg"), -15.0, 0.018, pitch=3.0, hp=400, lp=9_000),
    )),
    "bag_stow": Recipe(0.14, -13.0, (
        L(rpg("cloth3.ogg"), -7.0, pitch=-2.0, hp=100, lp=7_000),
        L(rpg("handleSmallLeather2.ogg"), -10.0, 0.018, pitch=-1.0, hp=160, lp=8_000),
        L(impact("impactSoft_medium_001.ogg"), -15.0, 0.042, pitch=-2.0, lp=5_000),
    )),
    "bag_down": Recipe(0.34, -11.0, (
        L(rpg("dropLeather.ogg"), -6.0, pitch=-3.0, lp=7_000),
        L(impact("impactSoft_heavy_001.ogg"), -9.0, 0.028, pitch=-4.0, lp=5_000),
        L(impact("impactWood_light_000.ogg"), -15.0, 0.055, pitch=-5.0, lp=6_500),
    )),
    "bag_up": Recipe(0.34, -12.0, (
        L(rpg("clothBelt2.ogg"), -7.0, pitch=-1.0, hp=100, lp=7_500),
        L(rpg("handleSmallLeather.ogg"), -9.0, 0.038, pitch=0.5, hp=160, lp=8_000),
        L(impact("impactWood_light_002.ogg"), -16.0, 0.080, pitch=-2.0, lp=6_500),
    )),

    # Farmer: soil body, handle contact, seed press, and root/crop pull.
    "farmer_work": Recipe(0.60, -11.0, (
        L(impact("impactSoft_heavy_000.ogg"), -6.0, pitch=-3.0, lp=5_000),
        L(impact("footstep_grass_001.ogg"), -10.0, 0.015, pitch=-2.0, lp=6_500),
        L(impact("impactWood_light_001.ogg"), -14.0, 0.035, pitch=-1.0, hp=180, lp=7_000),
    )),
    "farmer_work2": Recipe(0.60, -11.0, (
        L(impact("impactSoft_heavy_002.ogg"), -6.0, pitch=-1.0, lp=5_500),
        L(impact("footstep_grass_003.ogg"), -10.0, 0.018, pitch=0.0, lp=7_000),
        L(impact("impactWood_light_003.ogg"), -14.0, 0.038, pitch=1.0, hp=190, lp=7_500),
    )),
    "farmer_work3": Recipe(0.60, -11.0, (
        L(impact("impactSoft_heavy_004.ogg"), -6.0, pitch=-5.0, lp=4_500),
        L(impact("footstep_grass_004.ogg"), -10.0, 0.012, pitch=-3.0, lp=6_000),
        L(impact("impactWood_medium_004.ogg"), -15.0, 0.032, pitch=-4.0, lp=6_000),
    )),
    "seed_press": Recipe(0.15, -13.5, (
        L(rpg("handleSmallLeather2.ogg"), -8.0, pitch=2.0, hp=180, lp=8_000),
        L(impact("footstep_grass_000.ogg"), -14.0, 0.022, pitch=1.0, lp=6_500),
        L(impact("impactSoft_medium_004.ogg"), -16.0, 0.035, pitch=3.0, lp=5_500),
    )),
    "crop_pull": Recipe(0.20, -12.5, (
        L(impact("footstep_grass_002.ogg"), -7.0, pitch=1.0, hp=90, lp=7_000, reverse=True),
        L(rpg("cloth4.ogg"), -11.0, 0.035, pitch=2.0, hp=140, lp=7_500),
        L(impact("impactSoft_medium_002.ogg"), -16.0, 0.080, pitch=-1.0, lp=5_000),
    )),

    # Courier: feet carry weight; crate and chest contacts stay short.
    "haul_step": Recipe(0.22, -12.5, (
        L(impact("footstep_wood_000.ogg"), -6.0, pitch=-3.0, lp=7_000),
        L(impact("impactSoft_medium_000.ogg"), -12.0, 0.012, pitch=-4.0, lp=4_500),
        L(rpg("cloth1.ogg"), -18.0, 0.060, pitch=-3.0, hp=120, lp=6_000),
    )),
    "haul_step2": Recipe(0.22, -12.5, (
        L(impact("footstep_wood_002.ogg"), -6.0, pitch=-1.0, lp=7_500),
        L(impact("impactSoft_medium_002.ogg"), -12.0, 0.014, pitch=-2.0, lp=5_000),
    )),
    "haul_step3": Recipe(0.22, -12.5, (
        L(impact("footstep_wood_004.ogg"), -6.0, pitch=-4.0, lp=6_500),
        L(impact("impactSoft_medium_004.ogg"), -12.0, 0.010, pitch=-5.0, lp=4_500),
    )),
    "crate_grip": Recipe(0.16, -13.0, (
        L(rpg("handleSmallLeather.ogg"), -7.0, pitch=-2.0, hp=120, lp=7_500),
        L(impact("impactWood_light_000.ogg"), -13.0, 0.026, pitch=-3.0, lp=6_500),
    )),
    "haul_strain": Recipe(0.55, -13.0, (
        L(rpg("clothBelt.ogg"), -7.0, pitch=-3.0, lp=6_500),
        L(rpg("creak2.ogg"), -12.0, 0.035, pitch=-4.0, lp=5_500),
        L(impact("impactWood_light_002.ogg"), -17.0, 0.090, pitch=-4.0, lp=6_000),
    )),
    "crate_creak": Recipe(0.45, -13.0, (
        L(rpg("creak1.ogg"), -7.0, pitch=-2.0, hp=90, lp=7_000),
        L(impact("impactPlank_medium_002.ogg"), -16.0, 0.030, pitch=-5.0, lp=5_500),
    )),
    "crate_down": Recipe(0.30, -9.5, (
        L(impact("impactWood_heavy_001.ogg"), -6.0, pitch=-4.0, lp=6_000),
        L(impact("impactSoft_heavy_003.ogg"), -9.0, 0.012, pitch=-5.0, lp=4_000),
        L(rpg("cloth2.ogg"), -15.0, 0.035, pitch=-3.0, lp=6_500),
    )),
    "chest_stow": Recipe(0.20, -11.5, (
        L(rpg("doorClose_2.ogg"), -8.0, pitch=1.0, hp=80, lp=7_500),
        L(rpg("metalLatch.ogg"), -11.0, 0.035, pitch=1.0, hp=300, lp=9_000),
        L(impact("impactWood_light_004.ogg"), -15.0, 0.055, pitch=-1.0, lp=6_500),
    )),

    # First raid: natural bell/metal/wood impacts, not oscillator cues.
    "guard_alert": Recipe(2.00, -8.5, (
        L(impact("impactBell_heavy_000.ogg"), -6.0, pitch=-4.0, hp=80, lp=8_500),
        L(impact("impactMetal_heavy_002.ogg"), -11.0, 0.020, pitch=-5.0, lp=7_000),
        L(impact("impactBell_heavy_001.ogg"), -10.0, 0.420, pitch=-1.0, hp=100, lp=9_000),
    ), room=0.16),
    "blade_hit": Recipe(0.20, -9.5, (
        L(impact("impactMetal_medium_000.ogg"), -6.0, pitch=-1.0, hp=160, lp=9_000),
        L(impact("impactSoft_medium_001.ogg"), -11.0, 0.006, pitch=-3.0, lp=4_500),
    )),
    "blade_hit2": Recipe(0.20, -9.5, (
        L(impact("impactMetal_medium_003.ogg"), -6.0, pitch=1.0, hp=180, lp=9_500),
        L(impact("impactSoft_medium_003.ogg"), -11.0, 0.008, pitch=-1.0, lp=5_000),
    )),
    "shield_thud": Recipe(0.30, -9.0, (
        L(impact("impactWood_heavy_002.ogg"), -6.0, pitch=-5.0, lp=5_500),
        L(impact("impactMetal_light_001.ogg"), -10.0, 0.018, pitch=-2.0, hp=280, lp=8_500),
        L(impact("impactSoft_heavy_001.ogg"), -12.0, 0.010, pitch=-4.0, lp=4_000),
    )),
    "shield_thud2": Recipe(0.30, -9.0, (
        L(impact("impactWood_heavy_004.ogg"), -6.0, pitch=-3.0, lp=6_000),
        L(impact("impactMetal_light_003.ogg"), -10.0, 0.016, pitch=0.0, hp=300, lp=9_000),
        L(impact("impactSoft_heavy_004.ogg"), -12.0, 0.012, pitch=-2.0, lp=4_500),
    )),
    "guard_experience": Recipe(0.32, -12.0, (
        L(rpg("metalClick.ogg"), -8.0, hp=350, lp=9_500),
        L(rpg("handleCoins2.ogg"), -14.0, 0.030, pitch=2.0, hp=500, lp=10_000),
        L(impact("impactBell_heavy_004.ogg"), -16.0, 0.075, pitch=9.0, hp=550, lp=10_000),
    ), room=0.025),
    "leap_slam": Recipe(0.55, -8.5, (
        L(impact("impactSoft_heavy_003.ogg"), -5.0, pitch=-6.0, lp=4_000),
        L(impact("impactMetal_heavy_004.ogg"), -9.0, 0.010, pitch=-4.0, lp=7_000),
        L(impact("impactWood_heavy_003.ogg"), -10.0, 0.018, pitch=-7.0, lp=5_000),
    ), room=0.035),
    "armour_clink": Recipe(0.25, -12.0, (
        L(impact("impactMetal_light_000.ogg"), -7.0, pitch=-1.0, hp=240, lp=9_000),
        L(rpg("metalClick.ogg"), -13.0, 0.030, pitch=-2.0, hp=400, lp=9_500),
    )),
    "armour_clink2": Recipe(0.28, -12.0, (
        L(impact("impactMetal_light_004.ogg"), -7.0, pitch=1.0, hp=260, lp=9_500),
        L(rpg("metalLatch.ogg"), -14.0, 0.028, pitch=1.0, hp=400, lp=9_500),
    )),
}


def read_mono(path: Path) -> np.ndarray:
    if not path.is_file():
        raise FileNotFoundError(path)
    data, rate = sf.read(path, dtype="float32", always_2d=True)
    mono = data.mean(axis=1)
    if rate != SAMPLE_RATE:
        divisor = math.gcd(rate, SAMPLE_RATE)
        mono = resample_poly(mono, SAMPLE_RATE // divisor, rate // divisor)
    # Preserve contact onset but remove archive padding that would make a
    # processed layer feel late. Keep 2 ms either side of detected material.
    active = np.flatnonzero(np.abs(mono) >= 10 ** (-58.0 / 20.0))
    if active.size:
        pad = int(0.002 * SAMPLE_RATE)
        lo = max(0, int(active[0]) - pad)
        hi = min(len(mono), int(active[-1]) + pad + 1)
        mono = mono[lo:hi]
    return np.asarray(mono, dtype=np.float32)


def process_layer(layer: Layer) -> np.ndarray:
    audio = read_mono(layer.source)
    if layer.reverse:
        audio = audio[::-1].copy()
    plugins = []
    if abs(layer.pitch) >= 0.01:
        plugins.append(PitchShift(semitones=layer.pitch))
    plugins.extend((
        HighpassFilter(cutoff_frequency_hz=layer.highpass_hz),
        LowpassFilter(cutoff_frequency_hz=layer.lowpass_hz),
        Compressor(threshold_db=-19.0, ratio=2.2, attack_ms=2.0, release_ms=55.0),
        Gain(gain_db=layer.gain_db),
    ))
    return np.asarray(Pedalboard(plugins)(audio, SAMPLE_RATE), dtype=np.float32)


def fade(audio: np.ndarray, start_ms: float, end_ms: float) -> None:
    a = min(len(audio), max(1, int(start_ms * SAMPLE_RATE / 1000.0)))
    b = min(len(audio), max(1, int(end_ms * SAMPLE_RATE / 1000.0)))
    audio[:a] *= np.linspace(0.0, 1.0, a, dtype=np.float32)
    audio[-b:] *= np.linspace(1.0, 0.0, b, dtype=np.float32)


def render(recipe: Recipe) -> np.ndarray:
    frames = round(recipe.duration * SAMPLE_RATE)
    mixed = np.zeros(frames, dtype=np.float32)
    for layer in recipe.layers:
        material = process_layer(layer)
        start = max(0, round(layer.offset * SAMPLE_RATE))
        count = min(len(material), frames - start)
        if count > 0:
            mixed[start:start + count] += material[:count]
    mixed = np.asarray(Pedalboard([
        HighpassFilter(cutoff_frequency_hz=32.0),
        LowpassFilter(cutoff_frequency_hz=12_500.0),
        Compressor(threshold_db=-18.0, ratio=2.4, attack_ms=2.5, release_ms=65.0),
    ])(mixed, SAMPLE_RATE), dtype=np.float32)
    if recipe.room > 0.0:
        wet = min(0.22, recipe.room)
        mixed = np.asarray(Pedalboard([Reverb(
            room_size=min(0.55, 0.22 + recipe.room),
            damping=0.72,
            wet_level=wet,
            dry_level=1.0 - wet * 0.35,
            width=0.35,
            freeze_mode=0.0,
        )])(mixed, SAMPLE_RATE), dtype=np.float32)
    fade(mixed, 1.5, min(35.0, recipe.duration * 90.0))
    peak = float(np.max(np.abs(mixed))) if mixed.size else 0.0
    if peak <= 1e-8:
        raise ValueError("recipe rendered silence")
    wanted = 10.0 ** (recipe.peak_dbfs / 20.0)
    mixed *= wanted / peak
    return np.clip(mixed, -0.999, 0.999)


def db(value: float) -> float:
    return 20.0 * math.log10(max(value, 1e-12))


def active_edges(audio: np.ndarray, threshold_db: float = -48.0) -> tuple[float, float]:
    active = np.flatnonzero(np.abs(audio) >= 10 ** (threshold_db / 20.0))
    if not active.size:
        return math.inf, math.inf
    lead = float(active[0]) / SAMPLE_RATE
    tail = float(len(audio) - 1 - active[-1]) / SAMPLE_RATE
    return lead, tail


def ffmpeg_lufs(path: Path) -> float | None:
    exe = shutil.which("ffmpeg")
    if not exe:
        return None
    proc = subprocess.run(
        [exe, "-hide_banner", "-nostats", "-i", str(path), "-af",
         "ebur128=peak=true", "-f", "null", "NUL" if sys.platform == "win32" else "/dev/null"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    marker = "Integrated loudness:"
    summary = proc.stderr.rsplit(marker, 1)
    if len(summary) != 2:
        return None
    for line in summary[1].splitlines():
        line = line.strip()
        if line.startswith("I:") and "LUFS" in line:
            try:
                return float(line.split()[1])
            except (ValueError, IndexError):
                return None
    return None


def inspect(path: Path, recipe: Recipe) -> dict[str, object]:
    data, rate = sf.read(path, dtype="float32", always_2d=True)
    mono = data.mean(axis=1)
    duration = len(mono) / rate
    peak = db(float(np.max(np.abs(mono))))
    rms = db(float(np.sqrt(np.mean(np.square(mono, dtype=np.float64)))))
    lead, tail = active_edges(mono)
    # EBU R128's 400 ms gate reports -70 LUFS for shorter one-shots. Use it
    # only where the standard has enough programme material; peak and RMS are
    # the useful loudness evidence for contact sounds below that window.
    measured_lufs = ffmpeg_lufs(path) if duration >= 0.40 else None
    # FFmpeg reports its -70 LUFS floor when the 400 ms gate cannot form a
    # meaningful block (common for a sparse contact that is exactly 400 ms).
    # Preserve that distinction instead of presenting the floor as a real
    # loudness measurement.
    lufs = None if measured_lufs is None or measured_lufs <= -69.0 else measured_lufs
    failures = []
    if rate != SAMPLE_RATE:
        failures.append(f"sample_rate={rate}")
    if data.shape[1] != 1:
        failures.append(f"channels={data.shape[1]}")
    if abs(duration - recipe.duration) > 0.012:
        failures.append(f"duration={duration:.3f}s expected={recipe.duration:.3f}s")
    if peak > -6.0:
        failures.append(f"peak={peak:.1f}dBFS clips mix headroom")
    if peak < -16.5:
        failures.append(f"peak={peak:.1f}dBFS too quiet")
    if rms > -12.0:
        failures.append(f"rms={rms:.1f}dBFS too dense")
    if rms < -40.0:
        failures.append(f"rms={rms:.1f}dBFS too quiet")
    if lufs is not None and not -40.0 <= lufs <= -19.0:
        failures.append(f"integrated_loudness={lufs:.1f}LUFS out of family")
    if lead > 0.055:
        failures.append(f"leading_silence={lead:.3f}s")
    # Many contacts deliberately keep the old animation-period duration while
    # the foley itself decays early. Reject a mostly-empty file, but do not
    # mistake that authored recovery window for accidental silence.
    if tail > max(0.075, recipe.duration * 0.56):
        failures.append(f"trailing_silence={tail:.3f}s")
    source_hashes = {
        str(layer.source.relative_to(ROOT)).replace("\\", "/"):
            hashlib.sha256(layer.source.read_bytes()).hexdigest()
        for layer in recipe.layers
    }
    return {
        "file": path.name,
        "duration_seconds": round(duration, 4),
        "sample_rate": rate,
        "channels": data.shape[1],
        "peak_dbfs": round(peak, 2),
        "rms_dbfs": round(rms, 2),
        "integrated_lufs": None if lufs is None else round(lufs, 2),
        "leading_silence_seconds": None if math.isinf(lead) else round(lead, 4),
        "trailing_silence_seconds": None if math.isinf(tail) else round(tail, 4),
        "sources": [str(layer.source.relative_to(ROOT)).replace("\\", "/")
                    for layer in recipe.layers],
        "source_sha256": source_hashes,
        "verdict": "PASS" if not failures else "FAIL",
        "failures": failures,
    }


def verify() -> int:
    rows = []
    missing = []
    for name, recipe in RECIPES.items():
        for layer in recipe.layers:
            if not layer.source.is_file():
                missing.append(str(layer.source))
        path = OUT / f"{name}.ogg"
        if path.is_file():
            rows.append(inspect(path, recipe))
        else:
            rows.append({"file": path.name, "verdict": "FAIL",
                         "failures": ["missing output"]})
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "sample_rate": SAMPLE_RATE,
        "tooling_only_dependency": "spotify/pedalboard 0.9.24",
        "source_policy": "CC0 raw foley only; see tools/audio_sources/manifest.json",
        "missing_sources": missing,
        "sounds": rows,
    }
    REPORT.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"{'sound':26} {'dur':>6} {'peak':>7} {'rms':>7} {'LUFS':>7} verdict")
    print("-" * 66)
    for row in rows:
        print(f"{row['file']:<26} {row.get('duration_seconds', 0):>6} "
              f"{row.get('peak_dbfs', 0):>7} {row.get('rms_dbfs', 0):>7} "
              f"{str(row.get('integrated_lufs')):>7} {row['verdict']}")
        for failure in row.get("failures", []):
            print(f"  - {failure}")
    if missing:
        for source in missing:
            print(f"MISSING SOURCE: {source}")
    failed = missing or any(row["verdict"] != "PASS" for row in rows)
    print(f"report: {REPORT}")
    return 1 if failed else 0


def build(only: str | None) -> None:
    selected = {name: recipe for name, recipe in RECIPES.items()
                if only is None or only.lower() in name.lower()}
    if not selected:
        raise SystemExit(f"no recipe matches --only {only!r}")
    OUT.mkdir(parents=True, exist_ok=True)
    for name, recipe in selected.items():
        audio = render(recipe)
        path = OUT / f"{name}.ogg"
        sf.write(path, audio, SAMPLE_RATE, format="OGG", subtype="VORBIS")
        print(f"wrote {path.relative_to(ROOT)} ({recipe.duration:.2f}s)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify", action="store_true",
                        help="verify existing outputs without rebuilding")
    parser.add_argument("--only", help="build recipes whose name contains text")
    args = parser.parse_args()
    if not args.verify:
        build(args.only)
    return verify()


if __name__ == "__main__":
    raise SystemExit(main())
