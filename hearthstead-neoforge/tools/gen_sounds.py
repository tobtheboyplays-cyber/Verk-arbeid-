#!/usr/bin/env python3
"""Hearthstead sound-effects generator.

Synthesizes every mod sound 100% from scratch (additive synthesis, filtered
noise, exponential envelopes, Schroeder reverb) — no samples, no external
audio. Output: 44100 Hz mono WAV intermediates, encoded to OGG Vorbis (q~4)
via ffmpeg into  src/main/resources/assets/hearthstead/sounds/  plus a
matching sounds.json.

Fully deterministic: all randomness comes from random.Random seeded from
MASTER_SEED = 1420 (per-sound sub-seeds derived from the sound name).

Usage:
    python3 tools/gen_sounds.py              generate + encode + sounds.json + verify
    python3 tools/gen_sounds.py --verify     verify existing OGGs only (prints table)
    python3 tools/gen_sounds.py --wav-dir D  keep intermediate WAVs in directory D
    python3 tools/gen_sounds.py --only chop  regenerate only sounds whose name contains "chop"

Requires: Python 3.11 stdlib only, ffmpeg/ffprobe on PATH.
"""

import argparse
import json
import math
import os
import random
import subprocess
import sys
import tempfile
import wave
from array import array

SR = 44100
TWO_PI = 2.0 * math.pi
MASTER_SEED = 1420

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ASSETS_DIR = os.path.normpath(os.path.join(
    SCRIPT_DIR, "..", "src", "main", "resources", "assets", "hearthstead"))
SOUNDS_DIR = os.path.join(ASSETS_DIR, "sounds")
SOUNDS_JSON = os.path.join(ASSETS_DIR, "sounds.json")


# ---------------------------------------------------------------------------
# Core DSP building blocks (small, reusable)
# ---------------------------------------------------------------------------

def n_samples(dur):
    return int(round(dur * SR))


def white_noise(rng, dur):
    return [rng.uniform(-1.0, 1.0) for _ in range(n_samples(dur))]


def one_pole_lp(x, cutoff):
    """Simple one-pole low-pass filter."""
    a = 1.0 - math.exp(-TWO_PI * cutoff / SR)
    y = 0.0
    out = []
    ap = out.append
    for s in x:
        y += a * (s - y)
        ap(y)
    return out


def one_pole_hp(x, cutoff):
    """One-pole high-pass = input minus its low-passed copy."""
    lp = one_pole_lp(x, cutoff)
    return [s - l for s, l in zip(x, lp)]


def lp_sweep(x, c0, c1):
    """One-pole low-pass with exponentially swept cutoff c0 -> c1."""
    n = len(x)
    if n == 0:
        return []
    y = 0.0
    out = []
    ap = out.append
    ratio = c1 / c0
    for i, s in enumerate(x):
        c = c0 * (ratio ** (i / n))
        a = 1.0 - math.exp(-TWO_PI * c / SR)
        y += a * (s - y)
        ap(y)
    return out


def biquad_bp(x, f0, q):
    """RBJ-cookbook constant-0dB-peak band-pass biquad."""
    w0 = TWO_PI * f0 / SR
    alpha = math.sin(w0) / (2.0 * q)
    cosw = math.cos(w0)
    a0 = 1.0 + alpha
    b0 = alpha / a0
    b2 = -alpha / a0
    a1 = (-2.0 * cosw) / a0
    a2 = (1.0 - alpha) / a0
    x1 = x2 = y1 = y2 = 0.0
    out = []
    ap = out.append
    for s in x:
        y = b0 * s + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1 = x1, s
        y2, y1 = y1, y
        ap(y)
    return out


def env_exp(n, attack=0.005, tau=0.3):
    """Linear attack then exponential decay, as a list of n gains."""
    na = max(1, int(attack * SR))
    out = []
    ap = out.append
    for i in range(n):
        t = i / SR
        a = min(1.0, i / na)
        ap(a * math.exp(-max(0.0, t - attack) / tau))
    return out


def flutter_env(n, rng, rate=35.0, depth=0.6):
    """Smoothed random amplitude flutter in [1-depth, 1]. For rustle/fire life."""
    a = 1.0 - math.exp(-TWO_PI * rate / SR)
    y = 0.0
    raw = []
    for _ in range(n):
        y += a * (rng.uniform(0.0, 1.0) - y)
        raw.append(y)
    lo = min(raw)
    hi = max(raw)
    span = (hi - lo) or 1.0
    return [1.0 - depth + depth * ((v - lo) / span) for v in raw]


def soft_clip(x, drive=1.2):
    """Gentle tanh saturation (adds warmth, tames transient spikes)."""
    norm = math.tanh(drive)
    return [math.tanh(s * drive) / norm for s in x]


def peak_of(x):
    return max((abs(v) for v in x), default=0.0)


def normalize(x, peak=0.70):
    m = peak_of(x)
    if m <= 0.0:
        return list(x)
    g = peak / m
    return [v * g for v in x]


def mix_at(dest, src, t, gain=1.0):
    """Add src into dest starting at time t (seconds); grows dest as needed."""
    i0 = n_samples(t)
    need = i0 + len(src)
    if need > len(dest):
        dest.extend([0.0] * (need - len(dest)))
    for j, v in enumerate(src):
        dest[i0 + j] += v * gain
    return dest


def schroeder_reverb(x, wet=0.15, fb=0.72, tail=0.35):
    """Small Schroeder reverb: 3 parallel feedback combs + 2 series allpasses.

    Returns len(x) + tail seconds of audio (dry/wet mixed).
    """
    n = len(x) + n_samples(tail)
    src = list(x) + [0.0] * (n - len(x))
    acc = [0.0] * n
    for d, g in ((1116, fb), (1277, fb * 0.98), (1422, fb * 0.95)):
        buf = [0.0] * d
        idx = 0
        for i in range(n):
            y = (src[i - d] if i >= d else 0.0) + g * buf[idx]
            buf[idx] = y
            acc[i] += y
            idx += 1
            if idx == d:
                idx = 0
    for d in (225, 556):
        buf = [0.0] * d
        idx = 0
        g = 0.5
        for i in range(n):
            vd = buf[idx]
            v = acc[i] + g * vd
            buf[idx] = v
            acc[i] = vd - g * v
            idx += 1
            if idx == d:
                idx = 0
    scale = wet * 0.34  # 3 combs sum ~3x hot; keep the wet path subtle
    return [(1.0 - wet) * src[i] + scale * acc[i] for i in range(n)]


def finalize(x, dur, peak=0.70):
    """Trim/pad to exact duration, normalize to target peak, 5 ms edge fades."""
    n = n_samples(dur)
    x = list(x[:n]) + [0.0] * max(0, n - len(x))
    x = normalize(x, peak)
    f = n_samples(0.005)
    for i in range(min(f, n)):
        g = i / f
        x[i] *= g
        x[n - 1 - i] *= g
    p = peak_of(x)
    assert p <= 0.95, f"peak {p:.3f} exceeds 0.95 after finalize"
    return x


# ---------------------------------------------------------------------------
# Instrument-level helpers
# ---------------------------------------------------------------------------

BELL_PARTIALS = (
    # (freq ratio, amplitude, decay-time ratio)
    (1.00, 1.00, 1.00),
    (2.76, 0.55, 0.52),
    (5.40, 0.26, 0.27),
    (8.93, 0.10, 0.16),
)


def bell_tone(dur, f0, rng, tau=0.45, partials=BELL_PARTIALS, detune=0.002):
    """Struck-bell: inharmonic partials with per-partial exponential decay."""
    n = n_samples(dur)
    out = [0.0] * n
    for ratio, amp, tr in partials:
        f = f0 * ratio * (1.0 + rng.uniform(-detune, detune))
        if f > SR * 0.45:
            continue
        pt = max(0.02, tau * tr)
        ph = rng.uniform(0.0, TWO_PI)
        w = TWO_PI * f / SR
        inv = 1.0 / (pt * SR)
        for i in range(n):
            out[i] += amp * math.exp(-i * inv) * math.sin(w * i + ph)
    na = max(1, n_samples(0.002))  # fast strike attack, no click
    for i in range(min(na, n)):
        out[i] *= i / na
    return normalize(out, 1.0)


HORN_HARMONICS = ((1, 1.00), (3, 0.50), (5, 0.30), (7, 0.17), (9, 0.09))


def horn_note(dur, f0, rng, vib_depth=0.007, vib_rate=5.2, sustain_tau=None):
    """Warm medieval horn: summed odd harmonics, brass-like attack overshoot,
    vibrato that eases in, gentle low-pass for roundness. sustain_tau adds a
    slow decrescendo arc on held notes (keeps them breathing, not organ-like)."""
    n = n_samples(dur)
    ph = 0.0
    vph = rng.uniform(0.0, TWO_PI)
    atk = 0.05
    rel = 0.12
    out = []
    ap = out.append
    for i in range(n):
        t = i / SR
        vib = 1.0 + vib_depth * min(1.0, t / 0.35) * math.sin(TWO_PI * vib_rate * t + vph)
        ph += TWO_PI * f0 * vib / SR
        s = 0.0
        for k, a in HORN_HARMONICS:
            s += a * math.sin(k * ph)
        e = min(1.0, t / atk) ** 1.5
        e *= 1.0 + 0.16 * math.exp(-max(0.0, t - atk) / 0.07) * min(1.0, t / atk)
        if sustain_tau and t > 0.35:
            e *= math.exp(-(t - 0.35) / sustain_tau)
        if t > dur - rel:
            e *= math.exp(-(t - (dur - rel)) / 0.045)
        ap(s * e)
    out = one_pole_lp(out, 3600)
    return normalize(out, 1.0)


def drum_thump(rng, dur=1.3, f_hi=72.0, f_lo=54.0):
    """Deep ceremonial drum: sine with fast pitch drop, fast attack, slow
    decay, plus a short pink-ish (low-passed) noise burst for skin texture."""
    n = n_samples(dur)
    ph = 0.0
    out = []
    ap = out.append
    for i in range(n):
        t = i / SR
        f = f_lo + (f_hi - f_lo) * math.exp(-t / 0.10)
        ph += TWO_PI * f / SR
        e = min(1.0, t / 0.004) * math.exp(-t / 0.42)
        ap(math.sin(ph) * e)
    skin = one_pole_lp(white_noise(rng, 0.22), 420)
    for i, v in enumerate(skin):
        t = i / SR
        out[i] += 0.22 * v * min(1.0, t / 0.003) * math.exp(-t / 0.05)
    return soft_clip(out, 1.25)


def fire_whoosh(rng, dur=1.5):
    """Rising fire swell: noise through a rising low-pass sweep, asymmetric
    envelope (slow rise, quicker fall), light flutter for flame life."""
    n = n_samples(dur)
    x = white_noise(rng, dur)
    x = lp_sweep(x, 340, 2300)
    x = one_pole_hp(x, 150)
    fl = flutter_env(n, rng, rate=24.0, depth=0.28)
    peak_pos = 0.52
    for i in range(n):
        p = i / n
        if p < peak_pos:
            e = (p / peak_pos) ** 2.2
        else:
            e = math.cos((p - peak_pos) / (1.0 - peak_pos) * math.pi / 2) ** 1.5
        x[i] *= e * fl[i]
    return normalize(x, 1.0)


def crowd_murmur(rng, dur):
    """Very low, distant crowd-ish texture: band-limited noise with slow
    random amplitude pulses, hann-windowed overall."""
    n = n_samples(dur)
    x = white_noise(rng, dur)
    x = one_pole_lp(x, 260)
    x = one_pole_hp(x, 90)
    fl = flutter_env(n, rng, rate=5.5, depth=0.8)
    for i in range(n):
        w = 0.5 - 0.5 * math.cos(TWO_PI * i / n)
        x[i] *= fl[i] * w
    return normalize(x, 1.0)


def noise_burst(rng, dur, center, q, attack=0.003, tau=None,
                flutter_rate=70.0, flutter_depth=0.6):
    """Band-passed noise burst with amplitude flutter — rustle raw material."""
    n = n_samples(dur)
    x = biquad_bp(white_noise(rng, dur), center, q)
    env = env_exp(n, attack=attack, tau=tau if tau else dur / 3.0)
    fl = flutter_env(n, rng, rate=flutter_rate, depth=flutter_depth)
    return normalize([x[i] * env[i] * fl[i] for i in range(n)], 1.0)


def ping(dur, f0, tau, rng, amp=1.0):
    """Tiny damped sine ping (used for the farmer 'snip')."""
    n = n_samples(dur)
    ph = rng.uniform(0.0, TWO_PI)
    w = TWO_PI * f0 / SR
    inv = 1.0 / (tau * SR)
    out = [amp * math.exp(-i * inv) * math.sin(w * i + ph) for i in range(n)]
    na = max(1, n_samples(0.001))
    for i in range(min(na, n)):
        out[i] *= i / na
    return out


# ---------------------------------------------------------------------------
# Sound renderers — each returns an un-normalized float buffer;
# finalize() trims to the target duration and applies fades/normalization.
# ---------------------------------------------------------------------------

def render_hearth_founded(rng, dur):
    """Ceremony: deep drum -> warm fire whoosh -> bright bell arpeggio."""
    mix = []
    mix_at(mix, drum_thump(rng), 0.0, 1.0)
    mix_at(mix, fire_whoosh(rng), 0.22, 0.44)
    # A4 - C#5 - E5, each note a touch louder than the last, riding above
    # the tail of the whoosh
    for i, (t, f) in enumerate(((1.22, 440.00), (1.50, 554.37), (1.78, 659.26))):
        mix_at(mix, bell_tone(1.15, f, rng, tau=0.46), t, 0.52 + 0.05 * i)
    return schroeder_reverb(mix, wet=0.15, tail=0.4)


def render_profession_assigned(rng, dur):
    """Parchment rustle then a crisp two-note confirmation chime (E5 -> A5)."""
    mix = []
    t = 0.015
    for _ in range(3):
        blen = rng.uniform(0.07, 0.13)
        c = rng.uniform(2300, 3100)
        mix_at(mix, noise_burst(rng, blen, c, 1.1, flutter_rate=80.0,
                                flutter_depth=0.7), t, rng.uniform(0.5, 0.7))
        t += blen * rng.uniform(0.55, 0.8)
    mix_at(mix, bell_tone(0.70, 659.26, rng, tau=0.30), 0.42, 0.75)
    mix_at(mix, bell_tone(0.75, 880.00, rng, tau=0.34), 0.64, 0.90)
    return schroeder_reverb(mix, wet=0.12, tail=0.25)


def render_settler_recruited(rng, dur):
    """Small medieval horn fanfare (A3-D4-F#4) over faint crowd murmur."""
    mix = []
    for t, f, d, g, st in ((0.00, 220.00, 0.46, 0.80, None),
                           (0.40, 293.66, 0.46, 0.85, None),
                           (0.80, 369.99, 1.05, 1.00, 1.1)):
        mix_at(mix, horn_note(d, f, rng, vib_depth=0.009 if st else 0.007,
                              sustain_tau=st), t, g)
    mix_at(mix, crowd_murmur(rng, 1.9), 0.05, 0.085)
    return schroeder_reverb(mix, wet=0.15, tail=0.35)


FARMER_VARIANTS = (
    {"center": 3300.0, "snip": 4300.0},
    {"center": 3850.0, "snip": 4800.0},
    {"center": 2900.0, "snip": 3900.0},
)


def render_farmer_work(rng, dur, variant=0):
    """Plant/straw rustle: two high band-passed bursts plus a soft 'snip'."""
    p = FARMER_VARIANTS[variant]
    mix = []
    c = p["center"]
    mix_at(mix, noise_burst(rng, 0.13, c * rng.uniform(0.96, 1.04), 1.3,
                            flutter_rate=95.0, flutter_depth=0.65),
           0.02 + rng.uniform(0.0, 0.02), 0.85)
    mix_at(mix, noise_burst(rng, 0.16, c * rng.uniform(0.92, 1.0), 1.3,
                            flutter_rate=85.0, flutter_depth=0.65),
           0.27 + rng.uniform(0.0, 0.02), 0.95)
    # soft snip: tiny ping + a breath of high noise, aligned with burst 2
    t_snip = 0.29 + rng.uniform(0.0, 0.015)
    mix_at(mix, ping(0.05, p["snip"], 0.012, rng), t_snip, 0.40)
    mix_at(mix, noise_burst(rng, 0.02, p["snip"] * 1.4, 2.0, attack=0.001,
                            tau=0.006, flutter_depth=0.2), t_snip, 0.30)
    return mix


CHOP_VARIANTS = (
    {"f0": 252.0, "tau": 0.070},
    {"f0": 288.0, "tau": 0.058},
    {"f0": 226.0, "tau": 0.082},
)


def render_chop(rng, dur, variant=0):
    """Axe-on-wood 'thock': sharp click + short resonant wooden body + thud."""
    p = CHOP_VARIANTS[variant]
    f0 = p["f0"] * rng.uniform(0.99, 1.01)
    tau = p["tau"]
    n = n_samples(0.32)
    body = [0.0] * n
    # 2-3 wooden modes with a fast initial pitch settle (1.08x -> 1.0)
    for ratio, amp, tr in ((1.0, 1.0, 1.0), (1.62, 0.38, 0.6), (2.41, 0.16, 0.4)):
        ph = rng.uniform(0.0, TWO_PI)
        acc = ph
        pt = tau * tr
        for i in range(n):
            t = i / SR
            f = f0 * ratio * (1.0 + 0.08 * math.exp(-t / 0.012))
            acc += TWO_PI * f / SR
            body[i] += amp * math.exp(-t / pt) * math.sin(acc)
    na = n_samples(0.0015)
    for i in range(min(na, n)):
        body[i] *= i / na
    mix = []
    # NOTE: content starts after 8 ms so the mandatory 5 ms fade-in cannot
    # blunt the attack transient.
    mix_at(mix, body, 0.010, 1.0)
    # impact click: very short high-passed noise
    click = one_pole_hp(white_noise(rng, 0.006), 2400)
    ce = env_exp(len(click), attack=0.0005, tau=0.0015)
    mix_at(mix, [click[i] * ce[i] for i in range(len(click))], 0.008, 0.9)
    # low thud noise for mass
    thud = one_pole_lp(white_noise(rng, 0.09), 650)
    te = env_exp(len(thud), attack=0.002, tau=0.03)
    mix_at(mix, [thud[i] * te[i] for i in range(len(thud))], 0.009, 0.55)
    return soft_clip(mix, 1.2)


def render_guard_alert(rng, dur):
    """Urgent hand-bell rung twice; second strike slightly detuned."""
    partials = ((1.0, 1.0, 1.0), (2.0, 0.62, 0.62), (2.74, 0.50, 0.48),
                (4.5, 0.26, 0.28))
    mix = []
    for t, f, g in ((0.02, 738.0, 1.0), (0.82, 738.0 * 0.986, 0.95)):
        mix_at(mix, bell_tone(1.12, f, rng, tau=0.40, partials=partials), t, g)
        strike = biquad_bp(white_noise(rng, 0.012), 3300, 1.0)
        se = env_exp(len(strike), attack=0.0008, tau=0.004)
        mix_at(mix, [strike[i] * se[i] for i in range(len(strike))], t, 0.28)
    mix = one_pole_lp(mix, 6800)  # keep urgency without harshness
    return schroeder_reverb(mix, wet=0.12, tail=0.3)


HM_VARIANTS = (
    {"f0": 176.0, "dur": 0.70, "glide": 0.15},
    {"f0": 191.0, "dur": 0.66, "glide": 0.13},
)


def render_settler_hm(rng, dur, variant=0):
    """Soft 'hm?': triangle-ish hum gliding up ~15%, vibrato, two formant
    band boosts, very gentle envelope."""
    p = HM_VARIANTS[variant]
    f0 = p["f0"]
    d = p["dur"]
    n = n_samples(d)
    ph = 0.0
    vph = rng.uniform(0.0, TWO_PI)
    src = []
    ap = src.append
    for i in range(n):
        t = i / SR
        g = min(1.0, max(0.0, (t - 0.30 * d) / (0.55 * d)))
        g = g * g * (3.0 - 2.0 * g)  # smoothstep — question-like rise at end
        vib = 1.0 + 0.012 * min(1.0, t / 0.15) * math.sin(TWO_PI * 4.6 * t + vph)
        ph += TWO_PI * f0 * (1.0 + p["glide"] * g) * vib / SR
        # triangle series: sin - 1/9 sin3 + 1/25 sin5
        ap(math.sin(ph) - math.sin(3 * ph) / 9.0 + math.sin(5 * ph) / 25.0)
    voiced = one_pole_lp(src, 700)
    f1 = biquad_bp(src, 300.0, 4.5)   # nasal/closed-mouth formant
    f2 = biquad_bp(src, 1000.0, 6.0)  # upper hum formant
    breath = one_pole_lp(white_noise(rng, d), 1200)
    out = []
    ap = out.append
    for i in range(n):
        t = i / SR
        e = min(1.0, t / 0.09)
        e = 0.5 - 0.5 * math.cos(math.pi * min(1.0, e))  # smooth onset
        e *= 0.85 + 0.15 * math.sin(math.pi * t / d)     # gentle mid swell
        rel = 0.16
        if t > d - rel:
            e *= 0.5 + 0.5 * math.cos(math.pi * (t - (d - rel)) / rel)
        ap((0.55 * voiced[i] + 1.25 * f1[i] + 0.50 * f2[i]
            + 0.02 * breath[i]) * e)
    return out


def render_seed_press(rng, dur):
    """Soft soil pat: a low dull thump plus a short damp puff. No metal."""
    mix = []
    thud = one_pole_lp(white_noise(rng, 0.09), 380)
    te = env_exp(len(thud), attack=0.004, tau=0.05)
    mix_at(mix, [thud[i] * te[i] for i in range(len(thud))], 0.01, 0.75)
    puff = one_pole_lp(white_noise(rng, 0.05), 900)
    pe = env_exp(len(puff), attack=0.002, tau=0.02)
    mix_at(mix, [puff[i] * pe[i] for i in range(len(puff))], 0.015, 0.35)
    return mix


def render_crop_pull(rng, dur):
    """Dry stalk rustle ending in a short snap."""
    mix = []
    mix_at(mix, noise_burst(rng, 0.14, 3600 * rng.uniform(0.95, 1.05), 1.2,
                            flutter_rate=90.0, flutter_depth=0.7), 0.0, 0.75)
    t_snap = 0.13 + rng.uniform(0.0, 0.01)
    snap = one_pole_hp(white_noise(rng, 0.02), 2800)
    se = env_exp(len(snap), attack=0.0005, tau=0.008)
    mix_at(mix, [snap[i] * se[i] for i in range(len(snap))], t_snap, 0.6)
    return mix


def render_bag_stow(rng, dur):
    """Soft cloth rustle and a small thump as the item settles in the bag."""
    mix = []
    mix_at(mix, noise_burst(rng, 0.10, 1800 * rng.uniform(0.9, 1.1), 0.9,
                            flutter_rate=60.0, flutter_depth=0.6), 0.0, 0.5)
    thud = one_pole_lp(white_noise(rng, 0.06), 500)
    te = env_exp(len(thud), attack=0.003, tau=0.03)
    mix_at(mix, [thud[i] * te[i] for i in range(len(thud))], 0.03, 0.4)
    return mix


def render_water_pour(rng, dur):
    """A steady trickle: filtered noise with slow flutter, swelling in and out."""
    n = n_samples(dur)
    x = biquad_bp(white_noise(rng, dur), 5200, 0.7)
    fl = flutter_env(n, rng, rate=18.0, depth=0.5)
    out = []
    for i in range(n):
        t = i / SR
        g = min(1.0, t / 0.15) * min(1.0, (dur - t) / 0.25)
        out.append(x[i] * fl[i] * g)
    return out


BLADE_VARIANTS = (
    {"f0": 2600.0, "tau": 0.050},
    {"f0": 3050.0, "tau": 0.045},
)


def render_blade_hit(rng, dur, variant=0):
    """Metallic contact: a sharp noise click plus a short high ring."""
    p = BLADE_VARIANTS[variant]
    mix = []
    click = one_pole_hp(white_noise(rng, 0.01), 3500)
    ce = env_exp(len(click), attack=0.0003, tau=0.004)
    mix_at(mix, [click[i] * ce[i] for i in range(len(click))], 0.0, 1.0)
    n = n_samples(p["tau"] * 4)
    ph = rng.uniform(0.0, TWO_PI)
    ring = [math.exp(-(i / SR) / p["tau"]) * math.sin(TWO_PI * p["f0"] * (i / SR) + ph)
            for i in range(n)]
    mix_at(mix, ring, 0.001, 0.55)
    return soft_clip(mix, 1.3)


def render_yawn(rng, dur):
    """A slow, swelling mumble-voice yawn: a low downward glide, then a soft trail."""
    n = n_samples(dur)
    f0 = 150.0
    ph = 0.0
    src = []
    ap = src.append
    for i in range(n):
        t = i / SR
        f = f0 * (1.0 - 0.25 * min(1.0, t / dur))
        ph += TWO_PI * f / SR
        ap(math.sin(ph) - math.sin(3 * ph) / 9.0 + math.sin(5 * ph) / 25.0)
    f1 = biquad_bp(src, 350.0, 4.0)
    out = []
    for i in range(n):
        t = i / SR
        e = min(1.0, t / (0.35 * dur))
        rel = 0.3 * dur
        if t > dur - rel:
            e *= max(0.0, (dur - t) / rel)
        out.append((0.6 * src[i] + 1.1 * f1[i]) * e)
    return out


def render_ladder_creak(rng, dur):
    """A short wooden creak: swept band-pass noise, pitch-jittered per call."""
    n = n_samples(dur)
    x = white_noise(rng, dur)
    f0 = rng.uniform(320.0, 420.0)
    f1 = f0 * rng.uniform(0.75, 0.9)
    x = biquad_bp(x, (f0 + f1) / 2.0, 3.0)
    x = lp_sweep(x, f0 * 3.0, f1 * 1.5)
    e = env_exp(n, attack=0.01, tau=dur * 0.4)
    return [x[i] * e[i] for i in range(n)]


PANIC_VARIANTS = (
    {"f0": 320.0, "dur": 0.38, "glide": 0.45},
    {"f0": 355.0, "dur": 0.34, "glide": 0.55},
    {"f0": 300.0, "dur": 0.42, "glide": 0.38},
)


def render_settler_panic(rng, dur, variant=0):
    """A short, breathy fright yelp: a fast upward pitch snap over a hiss
    of breath noise -- frightened, not pained (that register is settler_hm's
    downward-glide territory; this one snaps UP and is over quickly)."""
    p = PANIC_VARIANTS[variant]
    f0 = p["f0"]
    d = p["dur"]
    n = n_samples(d)
    ph = 0.0
    src = []
    ap = src.append
    for i in range(n):
        t = i / SR
        g = min(1.0, t / (0.12 * d))  # the snap happens fast, right at onset
        g = g * g
        ph += TWO_PI * f0 * (1.0 + p["glide"] * g) / SR
        ap(math.sin(ph) - math.sin(3 * ph) / 9.0 + math.sin(5 * ph) / 25.0)
    voiced = one_pole_lp(src, 1100)
    formant = biquad_bp(src, 1400.0, 3.5)
    breath = one_pole_hp(white_noise(rng, d), 1800)
    out = []
    for i in range(n):
        t = i / SR
        e = min(1.0, t / 0.02)  # near-instant onset -- a startled sound
        rel = 0.55 * d
        if t > d - rel:
            e *= max(0.0, (d - t) / rel)
        be = e * (0.6 + 0.4 * max(0.0, 1.0 - t / (0.3 * d)))  # breath fades first
        out.append((0.55 * voiced[i] + 0.7 * formant[i]) * e + 0.35 * breath[i] * be)
    return soft_clip(out, 1.15)


SHIELD_VARIANTS = (
    {"f0": 118.0, "tau": 0.075, "rattle_f": 900.0},
    {"f0": 104.0, "tau": 0.085, "rattle_f": 1050.0},
)


def render_shield_thud(rng, dur, variant=0):
    """A deep wooden shield impact: a low body thump plus a brief board
    rattle -- distinct from blade_hit's bright metal click."""
    p = SHIELD_VARIANTS[variant]
    mix = []
    n = n_samples(p["tau"] * 5)
    thump = [math.exp(-(i / SR) / p["tau"]) * math.sin(TWO_PI * p["f0"] * (i / SR))
             for i in range(n)]
    mix_at(mix, thump, 0.0, 1.0)
    knock = one_pole_lp(white_noise(rng, 0.03), 260)
    ke = env_exp(len(knock), attack=0.0005, tau=0.012)
    mix_at(mix, [knock[i] * ke[i] for i in range(len(knock))], 0.0, 0.5)
    rattle = biquad_bp(white_noise(rng, 0.10), p["rattle_f"], 2.5)
    re_ = env_exp(len(rattle), attack=0.004, tau=0.05)
    mix_at(mix, [rattle[i] * re_[i] for i in range(len(rattle))], 0.02, 0.3)
    return soft_clip(mix, 1.25)


CHEER_VARIANTS = (
    {"f0": 210.0, "dur": 0.55, "glide": 0.30},
    {"f0": 232.0, "dur": 0.60, "glide": 0.35},
    {"f0": 198.0, "dur": 0.65, "glide": 0.28},
)


def render_cheer(rng, dur, variant=0):
    """A bright mumble-voice cheer: an energetic upward glide with a
    brighter formant than settler_hm, and a firmer onset -- a hop-and-shout
    read rather than a question."""
    p = CHEER_VARIANTS[variant]
    f0 = p["f0"]
    d = p["dur"]
    n = n_samples(d)
    ph = 0.0
    vph = rng.uniform(0.0, TWO_PI)
    src = []
    ap = src.append
    for i in range(n):
        t = i / SR
        g = min(1.0, t / (0.4 * d))
        g = g * g * (3.0 - 2.0 * g)
        vib = 1.0 + 0.02 * min(1.0, t / 0.1) * math.sin(TWO_PI * 6.0 * t + vph)
        ph += TWO_PI * f0 * (1.0 + p["glide"] * g) * vib / SR
        ap(math.sin(ph) - math.sin(3 * ph) / 9.0 + math.sin(5 * ph) / 25.0)
    voiced = one_pole_lp(src, 1400)
    f1 = biquad_bp(src, 900.0, 4.0)
    f2 = biquad_bp(src, 2400.0, 5.0)  # brighter upper formant than settler_hm
    out = []
    for i in range(n):
        t = i / SR
        e = min(1.0, t / 0.04)
        rel = 0.28 * d
        if t > d - rel:
            e *= max(0.0, (d - t) / rel)
        out.append((0.5 * voiced[i] + 0.9 * f1[i] + 0.5 * f2[i]) * e)
    return soft_clip(out, 1.1)


def render_settler_eat(rng, dur):
    """A soft, wet chew: a damp noise burst plus a tiny mouth-click."""
    mix = []
    chew = one_pole_lp(white_noise(rng, 0.08), 700)
    ce = env_exp(len(chew), attack=0.005, tau=0.04)
    mix_at(mix, [chew[i] * ce[i] for i in range(len(chew))], 0.0, 0.6)
    click = one_pole_hp(white_noise(rng, 0.008), 2000)
    cke = env_exp(len(click), attack=0.001, tau=0.006)
    mix_at(mix, [click[i] * cke[i] for i in range(len(click))], 0.05, 0.3)
    return mix



# ------------------------------------------------------------ courier ----
# SLICE A2a. The carry grammar's sound layer: everything a settler moving
# real goods should be heard doing. Wood and cloth, never metal -- a crate
# is not a tool.

HAUL_STEP_VARIANTS = (
    {"f0": 78.0, "tau": 0.055, "scuff": 1500.0},
    {"f0": 86.0, "tau": 0.048, "scuff": 1750.0},
    {"f0": 71.0, "tau": 0.062, "scuff": 1350.0},
)


def render_haul_step(rng, dur, variant=0):
    """A laden footfall: heavier and duller than an unloaded step, with a
    short scuff as the foot drags under the weight."""
    p = HAUL_STEP_VARIANTS[variant]
    mix = []
    n = n_samples(p["tau"] * 4)
    thud = [math.exp(-(i / SR) / p["tau"]) * math.sin(TWO_PI * p["f0"] * (i / SR))
            for i in range(n)]
    mix_at(mix, thud, 0.0, 1.0)
    body = one_pole_lp(white_noise(rng, 0.05), 320)
    be = env_exp(len(body), attack=0.001, tau=0.02)
    mix_at(mix, [body[i] * be[i] for i in range(len(body))], 0.0, 0.55)
    scuff = biquad_bp(white_noise(rng, 0.07), p["scuff"], 1.2)
    se = env_exp(len(scuff), attack=0.008, tau=0.03)
    mix_at(mix, [scuff[i] * se[i] for i in range(len(scuff))], 0.02, 0.22)
    return soft_clip(mix, 1.2)


def render_crate_grip(rng, dur):
    """Hands closing on a wooden crate: a dry short creak plus cloth."""
    mix = []
    f0 = rng.uniform(430.0, 520.0)
    creak = biquad_bp(white_noise(rng, 0.10), f0, 3.5)
    creak = lp_sweep(creak, f0 * 2.4, f0 * 1.2)
    ce = env_exp(len(creak), attack=0.012, tau=0.045)
    mix_at(mix, [creak[i] * ce[i] for i in range(len(creak))], 0.0, 0.7)
    cloth = noise_burst(rng, 0.07, 2100 * rng.uniform(0.9, 1.1), 1.0,
                        flutter_rate=70.0, flutter_depth=0.6)
    mix_at(mix, cloth, 0.01, 0.35)
    return mix


def render_haul_strain(rng, dur):
    """A strained working breath -- the mumble voice under load. Lower and
    breathier than settler_hm, with no question-like rise."""
    n = n_samples(dur)
    f0 = 132.0
    ph = 0.0
    src = []
    for i in range(n):
        t = i / SR
        # sags slightly as the breath runs out
        f = f0 * (1.0 - 0.10 * (t / dur))
        ph += TWO_PI * f / SR
        src.append(math.sin(ph) - math.sin(3 * ph) / 9.0)
    voiced = one_pole_lp(src, 620)
    chest = biquad_bp(src, 260.0, 4.0)
    breath = one_pole_lp(white_noise(rng, dur), 1500)
    out = []
    for i in range(n):
        t = i / SR
        e = min(1.0, t / 0.06)
        rel = 0.45 * dur
        if t > dur - rel:
            e *= max(0.0, (dur - t) / rel)
        out.append((0.5 * voiced[i] + 0.95 * chest[i]) * e + 0.22 * breath[i] * e)
    return soft_clip(out, 1.15)


def render_crate_creak(rng, dur):
    """Loaded wood flexing: a slower, deeper creak than crate_grip."""
    n = n_samples(dur)
    f0 = rng.uniform(240.0, 300.0)
    x = biquad_bp(white_noise(rng, dur), f0, 4.5)
    x = lp_sweep(x, f0 * 3.0, f0 * 1.1)
    fl = flutter_env(n, rng, rate=11.0, depth=0.45)
    out = []
    for i in range(n):
        t = i / SR
        g = min(1.0, t / 0.05) * min(1.0, (dur - t) / 0.12)
        out.append(x[i] * fl[i] * g)
    return out


def render_crate_down(rng, dur):
    """A crate set down: solid wooden thump, then a small settle knock."""
    mix = []
    n = n_samples(0.24)
    thump = [math.exp(-(i / SR) / 0.06) * math.sin(TWO_PI * 96.0 * (i / SR))
             for i in range(n)]
    mix_at(mix, thump, 0.0, 1.0)
    board = one_pole_lp(white_noise(rng, 0.05), 420)
    bh = env_exp(len(board), attack=0.0006, tau=0.018)
    mix_at(mix, [board[i] * bh[i] for i in range(len(board))], 0.0, 0.6)
    settle = biquad_bp(white_noise(rng, 0.04), 700.0, 2.0)
    se = env_exp(len(settle), attack=0.001, tau=0.012)
    mix_at(mix, [settle[i] * se[i] for i in range(len(settle))], 0.09, 0.3)
    return soft_clip(mix, 1.25)


def render_item_pickup(rng, dur):
    """Something small lifted off a surface: a soft tick and cloth lift."""
    mix = []
    tick = one_pole_hp(white_noise(rng, 0.012), 2600)
    te = env_exp(len(tick), attack=0.0004, tau=0.006)
    mix_at(mix, [tick[i] * te[i] for i in range(len(tick))], 0.0, 0.7)
    lift = noise_burst(rng, 0.06, 1600 * rng.uniform(0.9, 1.1), 0.9,
                       flutter_rate=55.0, flutter_depth=0.5)
    mix_at(mix, lift, 0.008, 0.4)
    return mix


def render_chest_stow(rng, dur):
    """An item put down inside a chest: a wooden knock with contents shift."""
    mix = []
    n = n_samples(0.16)
    knock = [math.exp(-(i / SR) / 0.035) * math.sin(TWO_PI * 168.0 * (i / SR))
             for i in range(n)]
    mix_at(mix, knock, 0.0, 0.9)
    shift = biquad_bp(white_noise(rng, 0.09), 1250.0, 1.4)
    sh = env_exp(len(shift), attack=0.004, tau=0.035)
    mix_at(mix, [shift[i] * sh[i] for i in range(len(shift))], 0.03, 0.35)
    return soft_clip(mix, 1.15)


# ---------------------------------------------------------------------------
# Sound registry / sounds.json
# ---------------------------------------------------------------------------

# (file stem, target duration s, renderer, kwargs, target peak)

# ---------------------------------------------------------------- craft ----
# One sound per work MOTION, so you can tell what somebody is doing with your
# eyes shut (job standard, point 6). Each is built from a different physical
# story rather than the same thud re-tuned: metal ringing, air moving, a blade
# rasping, wood knocking. That is what makes them distinguishable at a
# distance, where a mix of subtle variations would smear into one noise.

def render_leap_slam(rng, dur):
    """A body landing hard: a deep floor thump under a bright armour crash."""
    mix = []
    thump = one_pole_lp(white_noise(rng, 0.30), 160)
    te = env_exp(len(thump), attack=0.002, tau=0.075)
    mix_at(mix, [thump[i] * te[i] for i in range(len(thump))], 0.010, 1.0)
    crash = one_pole_hp(white_noise(rng, 0.10), 2200)
    ce = env_exp(len(crash), attack=0.0008, tau=0.030)
    mix_at(mix, [crash[i] * ce[i] for i in range(len(crash))], 0.012, 0.55)
    # A low ring afterwards, so the ground sounds like it took something.
    n = n_samples(0.40)
    ring = [0.0] * n
    acc = rng.uniform(0.0, TWO_PI)
    for i in range(n):
        tt = i / SR
        acc += TWO_PI * 92.0 / SR
        ring[i] = math.exp(-tt / 0.16) * math.sin(acc)
    mix_at(mix, ring, 0.016, 0.35)
    return soft_clip(mix, 1.3)


def render_armour_clink(rng, dur, variant=0):
    """Guard on patrol: mail and buckles settling as weight shifts."""
    mix = []
    for k in range(2 + variant):
        f0 = rng.uniform(2100.0, 3400.0)
        n = n_samples(0.06)
        ring = [0.0] * n
        acc = rng.uniform(0.0, TWO_PI)
        for i in range(n):
            tt = i / SR
            acc += TWO_PI * f0 / SR
            ring[i] = math.exp(-tt / 0.012) * math.sin(acc)
        mix_at(mix, ring, 0.010 + k * rng.uniform(0.035, 0.070),
               rng.uniform(0.28, 0.45))
    # A soft leather creak underneath, so it is a person and not a bell.
    creak = one_pole_lp(white_noise(rng, 0.10), 520)
    ce = env_exp(len(creak), attack=0.008, tau=0.045)
    mix_at(mix, [creak[i] * ce[i] for i in range(len(creak))], 0.012, 0.30)
    return soft_clip(mix, 1.0)


def render_pick_strike(rng, dur, variant=0):
    """Miner: steel point into stone -- bright tick, dry crack, grit falling."""
    mix = []
    tick = one_pole_hp(white_noise(rng, 0.005), 3600)
    te = env_exp(len(tick), attack=0.0004, tau=0.0012)
    mix_at(mix, [tick[i] * te[i] for i in range(len(tick))], 0.008, 0.85)
    # The crack: a narrow band around a stone-ish mode, gone almost at once.
    crack = biquad_bp(white_noise(rng, 0.05), 900 * rng.uniform(0.95, 1.06), 5.0)
    ce = env_exp(len(crack), attack=0.001, tau=0.018)
    mix_at(mix, [crack[i] * ce[i] for i in range(len(crack))], 0.010, 0.9)
    # Grit: a sparse scatter of tiny high ticks, the tail that says "stone".
    for _ in range(6 + variant):
        g = one_pole_hp(white_noise(rng, 0.004), 5000)
        ge = env_exp(len(g), attack=0.0004, tau=0.002)
        mix_at(mix, [g[i] * ge[i] for i in range(len(g))],
               0.035 + rng.uniform(0.0, 0.16), rng.uniform(0.10, 0.30))
    return soft_clip(mix, 1.15)


def render_anvil_ring(rng, dur, variant=0):
    """Smith and mason: hammer on iron. Inharmonic partials, long bright decay."""
    n = n_samples(0.85)
    body = [0.0] * n
    f0 = (1180.0 if variant == 0 else 990.0) * rng.uniform(0.99, 1.01)
    # Deliberately inharmonic ratios -- a harmonic stack sounds like a bell,
    # a struck anvil does not.
    for ratio, amp, tr in ((1.0, 1.0, 1.0), (2.76, 0.55, 0.7),
                           (5.40, 0.30, 0.42), (8.93, 0.14, 0.25)):
        acc = rng.uniform(0.0, TWO_PI)
        for i in range(n):
            t = i / SR
            acc += TWO_PI * f0 * ratio / SR
            body[i] += amp * math.exp(-t / (0.30 * tr)) * math.sin(acc)
    na = n_samples(0.0012)
    for i in range(min(na, n)):
        body[i] *= i / na
    mix = []
    mix_at(mix, body, 0.010, 0.62)
    clank = one_pole_hp(white_noise(rng, 0.008), 2800)
    ke = env_exp(len(clank), attack=0.0004, tau=0.002)
    mix_at(mix, [clank[i] * ke[i] for i in range(len(clank))], 0.008, 0.8)
    return soft_clip(mix, 1.25)


def render_bellows_puff(rng, dur):
    """Smelter: air forced through a fire. A swell, not a hit."""
    n = n_samples(0.55)
    air = lp_sweep(white_noise(rng, 0.55), 400, 2200)
    out = []
    for i in range(min(n, len(air))):
        t = i / SR
        # Slow in, slower out: bellows have mass and the fire answers late.
        env = math.sin(math.pi * min(1.0, t / 0.5)) ** 1.4
        out.append(air[i] * env)
    mix = []
    mix_at(mix, out, 0.010, 0.7)
    # Fire answering: sparse crackle riding the back half of the swell.
    for _ in range(9):
        c = one_pole_bp_safe(rng, 1800)
        ce = env_exp(len(c), attack=0.0006, tau=0.004)
        mix_at(mix, [c[i] * ce[i] for i in range(len(c))],
               0.18 + rng.uniform(0.0, 0.30), rng.uniform(0.10, 0.26))
    return soft_clip(mix, 1.1)


def one_pole_bp_safe(rng, f0):
    """A very short band-passed tick, used for fire crackle."""
    return biquad_bp(white_noise(rng, 0.006), f0 * rng.uniform(0.7, 1.5), 3.0)


def render_saw_stroke(rng, dur, variant=0):
    """Sawyer and carpenter: a rasping cut, loud in the middle of the stroke."""
    n = n_samples(0.45)
    rasp = biquad_bp(white_noise(rng, 0.45), 1500 * rng.uniform(0.95, 1.05), 1.4)
    out = []
    for i in range(min(n, len(rasp))):
        t = i / SR
        # Teeth: a fast tremolo over a stroke-shaped envelope. The stroke
        # peaks in the middle, because that is where the blade is moving
        # fastest and biting hardest.
        stroke = math.sin(math.pi * min(1.0, t / 0.42)) ** 1.2
        teeth = 0.72 + 0.28 * math.sin(TWO_PI * (42.0 + 8.0 * variant) * t)
        out.append(rasp[i] * stroke * teeth)
    return soft_clip(out, 1.05)


def render_oven_slide(rng, dur):
    """Baker: a wooden peel scraping over stone, then the loaf going down."""
    n = n_samples(0.34)
    scrape = biquad_bp(white_noise(rng, 0.34), 780, 1.1)
    out = []
    for i in range(min(n, len(scrape))):
        t = i / SR
        env = math.sin(math.pi * min(1.0, t / 0.30)) ** 1.1
        out.append(scrape[i] * env * (0.8 + 0.2 * math.sin(TWO_PI * 26.0 * t)))
    mix = []
    mix_at(mix, out, 0.010, 0.55)
    # The soft whump of dough meeting hot stone.
    whump = one_pole_lp(white_noise(rng, 0.10), 380)
    we = env_exp(len(whump), attack=0.004, tau=0.035)
    mix_at(mix, [whump[i] * we[i] for i in range(len(whump))], 0.30, 0.65)
    return soft_clip(mix, 1.1)


def render_knead_press(rng, dur):
    """Cook: the heel of a hand into dough. Soft, low, and no click at all."""
    thud = one_pole_lp(white_noise(rng, 0.16), 300)
    te = env_exp(len(thud), attack=0.010, tau=0.045)
    mix = []
    mix_at(mix, [thud[i] * te[i] for i in range(len(thud))], 0.010, 0.8)
    # A faint board knock underneath, so it has a surface to be pressed onto.
    knock = biquad_bp(white_noise(rng, 0.05), 240, 3.0)
    ke = env_exp(len(knock), attack=0.002, tau=0.020)
    mix_at(mix, [knock[i] * ke[i] for i in range(len(knock))], 0.014, 0.35)
    return soft_clip(mix, 1.0)


def render_cleaver_chop(rng, dur, variant=0):
    """Butcher and tanner: a wet stroke that ends on the board underneath."""
    mix = []
    wet = one_pole_lp(white_noise(rng, 0.06), 900)
    we = env_exp(len(wet), attack=0.001, tau=0.016)
    mix_at(mix, [wet[i] * we[i] for i in range(len(wet))], 0.009, 0.75)
    # The board: a short wooden mode, a hair after the meat.
    n = n_samples(0.18)
    board = [0.0] * n
    f0 = (420.0 if variant == 0 else 365.0) * rng.uniform(0.98, 1.02)
    for ratio, amp in ((1.0, 1.0), (1.71, 0.35)):
        acc = rng.uniform(0.0, TWO_PI)
        for i in range(n):
            t = i / SR
            acc += TWO_PI * f0 * ratio / SR
            board[i] += amp * math.exp(-t / 0.045) * math.sin(acc)
    mix_at(mix, board, 0.016, 0.45)
    return soft_clip(mix, 1.15)


def render_loom_clack(rng, dur, variant=0):
    """Weaver and fletcher: two light wooden clacks, close together."""
    mix = []
    for k, (at, amp) in enumerate(((0.010, 1.0), (0.115 + 0.02 * variant, 0.7))):
        n = n_samples(0.10)
        body = [0.0] * n
        f0 = (940.0 if k == 0 else 1120.0) * rng.uniform(0.98, 1.02)
        acc = rng.uniform(0.0, TWO_PI)
        for i in range(n):
            t = i / SR
            acc += TWO_PI * f0 / SR
            body[i] += math.exp(-t / 0.020) * math.sin(acc)
        mix_at(mix, body, at, 0.55 * amp)
        tick = one_pole_hp(white_noise(rng, 0.004), 3000)
        te = env_exp(len(tick), attack=0.0004, tau=0.0012)
        mix_at(mix, [tick[i] * te[i] for i in range(len(tick))], at - 0.001, 0.45 * amp)
    return soft_clip(mix, 1.05)


SOUND_SPECS = [
    ("hearth_founded",      3.0, render_hearth_founded,     {}, 0.70),
    ("profession_assigned", 1.2, render_profession_assigned, {}, 0.70),
    ("settler_recruited",   2.2, render_settler_recruited,  {}, 0.70),
    ("farmer_work",         0.6, render_farmer_work, {"variant": 0}, 0.62),
    ("farmer_work2",        0.6, render_farmer_work, {"variant": 1}, 0.62),
    ("farmer_work3",        0.6, render_farmer_work, {"variant": 2}, 0.62),
    ("chop",                0.4, render_chop, {"variant": 0}, 0.70),
    ("chop2",               0.4, render_chop, {"variant": 1}, 0.70),
    ("chop3",               0.4, render_chop, {"variant": 2}, 0.70),
    ("guard_alert",         2.0, render_guard_alert,        {}, 0.70),
    # CHAINS-1 / job standard point 6: a distinct sound per work motion.
    ("leap_slam",           0.55, render_leap_slam,          {}, 0.85),
    ("armour_clink",        0.25, render_armour_clink, {"variant": 0}, 0.45),
    ("armour_clink2",       0.28, render_armour_clink, {"variant": 1}, 0.45),
    ("pick_strike",         0.30, render_pick_strike, {"variant": 0}, 0.68),
    ("pick_strike2",        0.30, render_pick_strike, {"variant": 1}, 0.68),
    ("anvil_ring",          0.90, render_anvil_ring, {"variant": 0}, 0.72),
    ("anvil_ring2",         0.90, render_anvil_ring, {"variant": 1}, 0.72),
    ("bellows_puff",        0.60, render_bellows_puff,       {}, 0.55),
    ("saw_stroke",          0.50, render_saw_stroke, {"variant": 0}, 0.58),
    ("saw_stroke2",         0.50, render_saw_stroke, {"variant": 1}, 0.58),
    ("oven_slide",          0.45, render_oven_slide,         {}, 0.55),
    ("knead_press",         0.25, render_knead_press,        {}, 0.50),
    ("cleaver_chop",        0.25, render_cleaver_chop, {"variant": 0}, 0.66),
    ("cleaver_chop2",       0.25, render_cleaver_chop, {"variant": 1}, 0.66),
    ("loom_clack",          0.30, render_loom_clack, {"variant": 0}, 0.52),
    ("settler_hm",          0.7, render_settler_hm, {"variant": 0}, 0.35),
    ("settler_hm2",         0.7, render_settler_hm, {"variant": 1}, 0.35),
    # SLICE ANIM-1 additions.
    ("seed_press",          0.15, render_seed_press,   {}, 0.55),
    ("crop_pull",           0.20, render_crop_pull,    {}, 0.60),
    ("bag_stow",            0.14, render_bag_stow,     {}, 0.55),
    ("water_pour",          1.20, render_water_pour,   {}, 0.45),
    ("blade_hit",           0.20, render_blade_hit, {"variant": 0}, 0.65),
    ("blade_hit2",          0.20, render_blade_hit, {"variant": 1}, 0.65),
    ("yawn",                0.90, render_yawn,         {}, 0.35),
    ("ladder_creak",        0.30, render_ladder_creak, {}, 0.40),
    ("settler_eat",         0.20, render_settler_eat,  {}, 0.55),
    ("settler_panic",       0.38, render_settler_panic, {"variant": 0}, 0.55),
    ("settler_panic2",      0.34, render_settler_panic, {"variant": 1}, 0.55),
    ("settler_panic3",      0.42, render_settler_panic, {"variant": 2}, 0.55),
    ("shield_thud",         0.30, render_shield_thud, {"variant": 0}, 0.75),
    ("shield_thud2",        0.30, render_shield_thud, {"variant": 1}, 0.75),
    ("cheer",               0.55, render_cheer, {"variant": 0}, 0.55),
    ("cheer2",              0.60, render_cheer, {"variant": 1}, 0.55),
    ("cheer3",              0.65, render_cheer, {"variant": 2}, 0.55),
    ("haul_step",           0.22, render_haul_step, {"variant": 0}, 0.60),
    ("haul_step2",          0.22, render_haul_step, {"variant": 1}, 0.60),
    ("haul_step3",          0.22, render_haul_step, {"variant": 2}, 0.60),
    ("crate_grip",          0.16, render_crate_grip,  {}, 0.55),
    ("haul_strain",         0.55, render_haul_strain, {}, 0.50),
    ("crate_creak",         0.45, render_crate_creak, {}, 0.45),
    ("crate_down",          0.30, render_crate_down,  {}, 0.75),
    ("item_pickup",         0.10, render_item_pickup, {}, 0.50),
    ("chest_stow",          0.20, render_chest_stow,  {}, 0.60),
]

SOUNDS_JSON_DATA = {
    "settlement_founded": {
        "sounds": [{"name": "hearthstead:hearth_founded", "volume": 1.0}],
        "subtitle": "subtitles.hearthstead.settlement_founded",
    },
    "profession_assigned": {
        "sounds": [{"name": "hearthstead:profession_assigned", "volume": 0.9}],
        "subtitle": "subtitles.hearthstead.profession_assigned",
    },
    "settler_recruited": {
        "sounds": [{"name": "hearthstead:settler_recruited", "volume": 0.9}],
        "subtitle": "subtitles.hearthstead.settler_recruited",
    },
    "farmer_work": {
        "sounds": [
            {"name": "hearthstead:farmer_work", "volume": 0.85},
            {"name": "hearthstead:farmer_work2", "volume": 0.85},
            {"name": "hearthstead:farmer_work3", "volume": 0.85},
        ],
        "subtitle": "subtitles.hearthstead.farmer_work",
    },
    "chop": {
        "sounds": [
            {"name": "hearthstead:chop", "volume": 0.9},
            {"name": "hearthstead:chop2", "volume": 0.9},
            {"name": "hearthstead:chop3", "volume": 0.9},
        ],
        "subtitle": "subtitles.hearthstead.chop",
    },
    "guard_alert": {
        "sounds": [{"name": "hearthstead:guard_alert", "volume": 0.95}],
        "subtitle": "subtitles.hearthstead.guard_alert",
    },
    "leap_slam": {
        "sounds": [{"name": "hearthstead:leap_slam", "volume": 1.0}],
        "subtitle": "subtitles.hearthstead.leap_slam",
    },
    "armour_clink": {
        "sounds": [
            {"name": "hearthstead:armour_clink", "volume": 0.7},
            {"name": "hearthstead:armour_clink2", "volume": 0.7},
        ],
        "subtitle": "subtitles.hearthstead.armour_clink",
    },
    "pick_strike": {
        "sounds": [
            {"name": "hearthstead:pick_strike", "volume": 0.9},
            {"name": "hearthstead:pick_strike2", "volume": 0.9},
        ],
        "subtitle": "subtitles.hearthstead.pick_strike",
    },
    "anvil_ring": {
        "sounds": [
            {"name": "hearthstead:anvil_ring", "volume": 0.85},
            {"name": "hearthstead:anvil_ring2", "volume": 0.85},
        ],
        "subtitle": "subtitles.hearthstead.anvil_ring",
    },
    "bellows_puff": {
        "sounds": [{"name": "hearthstead:bellows_puff", "volume": 0.8}],
        "subtitle": "subtitles.hearthstead.bellows_puff",
    },
    "saw_stroke": {
        "sounds": [
            {"name": "hearthstead:saw_stroke", "volume": 0.8},
            {"name": "hearthstead:saw_stroke2", "volume": 0.8},
        ],
        "subtitle": "subtitles.hearthstead.saw_stroke",
    },
    "oven_slide": {
        "sounds": [{"name": "hearthstead:oven_slide", "volume": 0.85}],
        "subtitle": "subtitles.hearthstead.oven_slide",
    },
    "knead_press": {
        "sounds": [{"name": "hearthstead:knead_press", "volume": 0.8}],
        "subtitle": "subtitles.hearthstead.knead_press",
    },
    "cleaver_chop": {
        "sounds": [
            {"name": "hearthstead:cleaver_chop", "volume": 0.9},
            {"name": "hearthstead:cleaver_chop2", "volume": 0.9},
        ],
        "subtitle": "subtitles.hearthstead.cleaver_chop",
    },
    "loom_clack": {
        "sounds": [{"name": "hearthstead:loom_clack", "volume": 0.75}],
        "subtitle": "subtitles.hearthstead.loom_clack",
    },
    "settler_hm": {
        "sounds": [
            {"name": "hearthstead:settler_hm", "volume": 0.6},
            {"name": "hearthstead:settler_hm2", "volume": 0.6},
        ],
        "subtitle": "subtitles.hearthstead.settler_hm",
    },
    "seed_press": {
        "sounds": [{"name": "hearthstead:seed_press", "volume": 0.6}],
        "subtitle": "subtitles.hearthstead.seed_press",
    },
    "crop_pull": {
        "sounds": [{"name": "hearthstead:crop_pull", "volume": 0.65}],
        "subtitle": "subtitles.hearthstead.crop_pull",
    },
    "bag_stow": {
        "sounds": [{"name": "hearthstead:bag_stow", "volume": 0.6}],
        "subtitle": "subtitles.hearthstead.bag_stow",
    },
    "water_pour": {
        "sounds": [{"name": "hearthstead:water_pour", "volume": 0.5}],
        "subtitle": "subtitles.hearthstead.water_pour",
    },
    "blade_hit": {
        "sounds": [
            {"name": "hearthstead:blade_hit", "volume": 0.7},
            {"name": "hearthstead:blade_hit2", "volume": 0.7},
        ],
        "subtitle": "subtitles.hearthstead.blade_hit",
    },
    "yawn": {
        "sounds": [{"name": "hearthstead:yawn", "volume": 0.45}],
        "subtitle": "subtitles.hearthstead.yawn",
    },
    "ladder_creak": {
        "sounds": [{"name": "hearthstead:ladder_creak", "volume": 0.45}],
        "subtitle": "subtitles.hearthstead.ladder_creak",
    },
    "settler_eat": {
        "sounds": [{"name": "hearthstead:settler_eat", "volume": 0.6}],
        "subtitle": "subtitles.hearthstead.settler_eat",
    },
    "settler_panic": {
        "sounds": [
            {"name": "hearthstead:settler_panic", "volume": 0.6},
            {"name": "hearthstead:settler_panic2", "volume": 0.6},
            {"name": "hearthstead:settler_panic3", "volume": 0.6},
        ],
        "subtitle": "subtitles.hearthstead.settler_panic",
    },
    "shield_thud": {
        "sounds": [
            {"name": "hearthstead:shield_thud", "volume": 0.8},
            {"name": "hearthstead:shield_thud2", "volume": 0.8},
        ],
        "subtitle": "subtitles.hearthstead.shield_thud",
    },
    "cheer": {
        "sounds": [
            {"name": "hearthstead:cheer", "volume": 0.6},
            {"name": "hearthstead:cheer2", "volume": 0.6},
            {"name": "hearthstead:cheer3", "volume": 0.6},
        ],
        "subtitle": "subtitles.hearthstead.cheer",
    },
    "haul_step": {
        "sounds": [
            {"name": "hearthstead:haul_step", "volume": 0.7},
            {"name": "hearthstead:haul_step2", "volume": 0.7},
            {"name": "hearthstead:haul_step3", "volume": 0.7},
        ],
        "subtitle": "subtitles.hearthstead.haul_step",
    },
    "crate_grip": {
        "sounds": [{"name": "hearthstead:crate_grip", "volume": 0.6}],
        "subtitle": "subtitles.hearthstead.crate_grip",
    },
    "haul_strain": {
        "sounds": [{"name": "hearthstead:haul_strain", "volume": 0.6}],
        "subtitle": "subtitles.hearthstead.haul_strain",
    },
    "crate_creak": {
        "sounds": [{"name": "hearthstead:crate_creak", "volume": 0.5}],
        "subtitle": "subtitles.hearthstead.crate_creak",
    },
    "crate_down": {
        "sounds": [{"name": "hearthstead:crate_down", "volume": 0.8}],
        "subtitle": "subtitles.hearthstead.crate_down",
    },
    "item_pickup": {
        "sounds": [{"name": "hearthstead:item_pickup", "volume": 0.55}],
        "subtitle": "subtitles.hearthstead.item_pickup",
    },
    "chest_stow": {
        "sounds": [{"name": "hearthstead:chest_stow", "volume": 0.65}],
        "subtitle": "subtitles.hearthstead.chest_stow",
    },
}


# ---------------------------------------------------------------------------
# I/O: WAV write, OGG encode, verification
# ---------------------------------------------------------------------------

def write_wav(path, samples):
    pcm = array("h", (max(-32767, min(32767, int(round(v * 32767.0))))
                      for v in samples))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def read_wav_samples(path):
    with wave.open(path, "rb") as w:
        assert w.getsampwidth() == 2 and w.getnchannels() == 1
        pcm = array("h")
        pcm.frombytes(w.readframes(w.getnframes()))
    return [v / 32768.0 for v in pcm]


def encode_ogg(wav_path, ogg_path):
    # -fflags/-flags:a +bitexact and -map_metadata -1 make the container and
    # its comment tags (encoder version string, stream serial) deterministic
    # across runs and machines -- otherwise re-running the generator rewrites
    # every pre-existing .ogg's stream metadata by a handful of bytes with no
    # change to the underlying (deterministic) PCM, the same class of issue
    # as KF-007. Proven by running the generator twice in a row and diffing.
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-fflags", "+bitexact",
         "-flags:a", "+bitexact", "-i", wav_path,
         "-c:a", "libvorbis", "-qscale:a", "4", "-ar", str(SR), "-ac", "1",
         "-flags:a", "+bitexact", "-map_metadata", "-1", "-fflags", "+bitexact",
         ogg_path],
        check=True)


def stats_of(samples):
    peak = peak_of(samples)
    rms = math.sqrt(sum(v * v for v in samples) / len(samples)) if samples else 0.0

    def db(v):
        return 20.0 * math.log10(v) if v > 1e-9 else float("-inf")

    return peak, db(peak), db(rms)


def ffprobe_info(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json",
         "-show_streams", "-show_format", path],
        capture_output=True, text=True, check=True)
    info = json.loads(out.stdout)
    st = info["streams"][0]
    return {
        "codec": st.get("codec_name"),
        "rate": int(st.get("sample_rate", 0)),
        "channels": int(st.get("channels", 0)),
        "duration": float(info["format"]["duration"]),
    }


def verify(specs):
    """ffprobe every OGG, decode it, print a stats table. Returns ok bool."""
    print(f"\n{'file':<26} {'dur(s)':>7} {'want':>5} {'codec':>7} {'rate':>6} "
          f"{'ch':>3} {'peak dB':>8} {'rms dB':>8}  verdict")
    print("-" * 88)
    all_ok = True
    tmp = tempfile.mkdtemp(prefix="hs_verify_")
    try:
        for name, want_dur, _fn, _kw, _pk in specs:
            ogg = os.path.join(SOUNDS_DIR, name + ".ogg")
            if not os.path.isfile(ogg):
                print(f"{name + '.ogg':<26} MISSING")
                all_ok = False
                continue
            inf = ffprobe_info(ogg)
            dec = os.path.join(tmp, name + ".wav")
            subprocess.run(
                ["ffmpeg", "-y", "-v", "error", "-i", ogg,
                 "-acodec", "pcm_s16le", dec], check=True)
            peak, pdb, rdb = stats_of(read_wav_samples(dec))
            problems = []
            if inf["codec"] != "vorbis":
                problems.append("codec")
            if inf["rate"] != SR:
                problems.append("rate")
            if inf["channels"] != 1:
                problems.append("channels")
            if abs(inf["duration"] - want_dur) > 0.15:
                problems.append("duration")
            if peak > 0.99:
                problems.append("clipping")
            verdict = "OK" if not problems else "FAIL:" + ",".join(problems)
            all_ok &= not problems
            print(f"{name + '.ogg':<26} {inf['duration']:>7.2f} {want_dur:>5.1f} "
                  f"{inf['codec']:>7} {inf['rate']:>6} {inf['channels']:>3} "
                  f"{pdb:>8.1f} {rdb:>8.1f}  {verdict}")
    finally:
        for f in os.listdir(tmp):
            os.unlink(os.path.join(tmp, f))
        os.rmdir(tmp)
    print()
    return all_ok


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def generate(specs, wav_dir):
    os.makedirs(SOUNDS_DIR, exist_ok=True)
    os.makedirs(wav_dir, exist_ok=True)
    print(f"{'file':<26} {'dur(s)':>7} {'peak':>6} {'peak dB':>8} {'rms dB':>8}")
    print("-" * 60)
    for name, dur, fn, kwargs, tgt_peak in specs:
        rng = random.Random(f"hearthstead:{MASTER_SEED}:{name}")
        buf = fn(rng, dur, **kwargs)
        buf = finalize(buf, dur, peak=tgt_peak)
        peak, pdb, rdb = stats_of(buf)
        wav = os.path.join(wav_dir, name + ".wav")
        write_wav(wav, buf)
        encode_ogg(wav, os.path.join(SOUNDS_DIR, name + ".ogg"))
        print(f"{name + '.ogg':<26} {len(buf) / SR:>7.2f} {peak:>6.3f} "
              f"{pdb:>8.1f} {rdb:>8.1f}")


def write_sounds_json():
    with open(SOUNDS_JSON, "w", encoding="utf-8") as f:
        json.dump(SOUNDS_JSON_DATA, f, indent=2)
        f.write("\n")
    print(f"wrote {SOUNDS_JSON}")


def main():
    ap = argparse.ArgumentParser(description="Generate Hearthstead sounds")
    ap.add_argument("--verify", action="store_true",
                    help="only verify existing OGGs (no generation)")
    ap.add_argument("--wav-dir", default=None,
                    help="keep intermediate WAVs in this directory")
    ap.add_argument("--only", default=None,
                    help="only (re)generate sounds whose name contains this")
    args = ap.parse_args()

    specs = SOUND_SPECS
    if args.only:
        specs = [s for s in SOUND_SPECS if args.only in s[0]]
        if not specs:
            sys.exit(f"no sound matches --only {args.only!r}")

    if args.verify:
        sys.exit(0 if verify(specs) else 1)

    if args.wav_dir:
        generate(specs, args.wav_dir)
    else:
        with tempfile.TemporaryDirectory(prefix="hs_wav_") as td:
            generate(specs, td)
    if not args.only:
        write_sounds_json()
    ok = verify(specs)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
