"""Measurement + post-processing helpers for the ElevenLabs sound lane.

Decoding/encoding uses ffmpeg; loudness uses pyloudnorm (BS.1770).
Short clips are padded with silence before the integrated measure so the
400 ms gating blocks see the whole transient ("padded LUFS")."""
import subprocess, numpy as np, pyloudnorm as pyln
from scipy import signal

SR = 44100
_meter = pyln.Meter(SR)


def decode(path, sr=SR):
    r = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-f", "f32le",
                        "-ar", str(sr), "-"], capture_output=True, check=True)
    return np.frombuffer(r.stdout, np.float32).astype(np.float64)


def encode_ogg(x, path, q=4):
    pcm = np.clip(x, -1, 1).astype(np.float32).tobytes()
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1",
                    "-i", "-", "-c:a", "libvorbis", "-q:a", str(q), "-ac", "1", path],
                   input=pcm, check=True)


def _declip_channel(v):
    """Rebuild clipped (full-scale) runs with a cubic spline through the good neighbours."""
    from scipy.interpolate import CubicSpline
    bad = np.abs(v) >= 0.99997
    if not bad.any():
        return v
    y = v.copy()
    idx = np.where(bad)[0]
    # group into runs
    splits = np.where(np.diff(idx) > 1)[0] + 1
    for run in np.split(idx, splits):
        a, b = run[0], run[-1]
        lo, hi = max(0, a - 6), min(len(v), b + 7)
        xs = [i for i in range(lo, hi) if not bad[i]]
        if len(xs) < 4:
            continue
        cs = CubicSpline(xs, v[xs])
        y[a:b + 1] = cs(np.arange(a, b + 1))
    return y


def decode_declipped(path):
    """Raw ElevenLabs PCM wav -> declipped mono float at SR (peaks may exceed 1.0; normalise later)."""
    import wave
    with wave.open(path) as w:
        ch, sr = w.getnchannels(), w.getframerate()
        d = np.frombuffer(w.readframes(w.getnframes()), np.int16).reshape(-1, ch).astype(np.float64) / 32767.0
    mono = np.mean([_declip_channel(d[:, c]) for c in range(ch)], axis=0)
    if sr != SR:
        from fractions import Fraction
        f = Fraction(SR, sr)
        mono = signal.resample_poly(mono, f.numerator, f.denominator)
    return mono


def flat_tops(path):
    """Truly clipped samples in a raw take: consecutive equal full-scale samples, any channel."""
    import wave
    if path.endswith(".wav"):
        with wave.open(path) as w:
            ch = w.getnchannels(); d = np.frombuffer(w.readframes(w.getnframes()), np.int16).reshape(-1, ch)
        n = 0
        for c in range(ch):
            v = d[:, c].astype(np.int32)
            full = np.abs(v) >= 32767
            n += int(np.sum(full[1:] & (v[1:] == v[:-1])))
        return n
    x = decode(path)
    full = np.abs(x) >= 0.999
    return int(np.sum(full[1:] & full[:-1]))


def lufs(x):
    pad = np.zeros(int(0.5 * SR))
    y = np.concatenate([pad, x, pad])
    v = _meter.integrated_loudness(y)
    return float(v) if np.isfinite(v) else -99.0


def peak_db(x):
    return float(20 * np.log10(np.abs(x).max() + 1e-12))


def true_peak_db(x):
    return peak_db(signal.resample_poly(x, 4, 1))


def env_db(x, win=0.01):
    n = int(win * SR); m = max(1, len(x) // n)
    r = np.sqrt(np.mean(x[: m * n].reshape(m, n) ** 2, axis=1) + 1e-14) if len(x) >= n else np.array([1e-7])
    return 20 * np.log10(r)


def clip_ratio(x, thr=0.999):
    return float(np.mean(np.abs(x) >= thr))


def spectral_centroid(x):
    if len(x) < 2048: return 0.0
    f, p = signal.welch(x, SR, nperseg=2048)
    return float((f * p).sum() / (p.sum() + 1e-18))


def hf_ratio(x, cut=8000):
    if len(x) < 2048: return 0.0
    f, p = signal.welch(x, SR, nperseg=2048)
    return float(p[f >= cut].sum() / (p.sum() + 1e-18))


def trim(x, rel_db=-45, abs_db=-60, pre=0.004, post=0.03):
    e = env_db(x, 0.005)
    thr = max(e.max() + rel_db, abs_db)
    on = np.where(e > thr)[0]
    if len(on) == 0: return x
    a = max(0, int((on[0] * 0.005 - pre) * SR)); b = min(len(x), int(((on[-1] + 1) * 0.005 + post) * SR))
    return x[a:b].copy()


def fade(x, fin=0.003, fout=0.04):
    x = x.copy(); n1 = min(int(fin * SR), len(x) // 4); n2 = min(int(fout * SR), len(x) // 2)
    if n1 > 0: x[:n1] *= np.linspace(0, 1, n1)
    if n2 > 0: x[-n2:] *= np.linspace(1, 0, n2) ** 2
    return x


def cap_length(x, max_s, fout=0.08):
    if max_s and len(x) > max_s * SR:
        x = x[: int(max_s * SR)].copy()
        n = int(fout * SR); x[-n:] *= np.linspace(1, 0, n) ** 2
    return x


def hp(x, f):
    return signal.sosfilt(signal.butter(2, f, "highpass", fs=SR, output="sos"), x)


def lp(x, f, order=2):
    return signal.sosfilt(signal.butter(order, f, "lowpass", fs=SR, output="sos"), x)


def high_shelf(x, f0, gain_db, q=0.707):
    """RBJ biquad high shelf."""
    A_ = 10 ** (gain_db / 40); w0 = 2 * np.pi * f0 / SR; alpha = np.sin(w0) / (2 * q); c = np.cos(w0)
    b0 = A_ * ((A_ + 1) + (A_ - 1) * c + 2 * np.sqrt(A_) * alpha)
    b1 = -2 * A_ * ((A_ - 1) + (A_ + 1) * c)
    b2 = A_ * ((A_ + 1) + (A_ - 1) * c - 2 * np.sqrt(A_) * alpha)
    a0 = (A_ + 1) - (A_ - 1) * c + 2 * np.sqrt(A_) * alpha
    a1 = 2 * ((A_ - 1) - (A_ + 1) * c)
    a2 = (A_ + 1) - (A_ - 1) * c - 2 * np.sqrt(A_) * alpha
    return signal.lfilter([b0 / a0, b1 / a0, b2 / a0], [1, a1 / a0, a2 / a0], x)


def lofi(x, amount):
    """The 'made for Minecraft' tone (STYLE.md): a gentle high-shelf cut above 8 kHz
    (0 to -6 dB) and a soft low-pass that walks from 18 kHz down to 12 kHz.
    No bit-crushing: Vorbis q4 at 44.1 kHz already gives the vanilla grain, and
    audible crush artefacts read as cheap, not retro."""
    if amount <= 0: return x
    y = high_shelf(x, 8000, -6.0 * amount)
    return lp(y, 18000 - 6000 * amount, 2)


def saturate(x, drive_db):
    """Soft tanh drive for density on impacts (vanilla hits are mastered hot)."""
    if drive_db <= 0: return x
    g = 10 ** (drive_db / 20); pk = np.abs(x).max() + 1e-12
    y = np.tanh(x / pk * g) / np.tanh(g)
    return y * pk


def normalize(x, target_lufs, ceiling_db=-1.0):
    g = target_lufs - lufs(x)
    y = x * 10 ** (g / 20)
    pk = true_peak_db(y)
    if pk > ceiling_db:
        # soft-limit the transient rather than dropping the whole clip
        y = soft_limit(y, ceiling_db)
    return y


def soft_limit(x, ceiling_db=-1.0):
    c = 10 ** (ceiling_db / 20)
    return c * np.tanh(x / c)


def stats(x):
    return dict(dur=round(len(x) / SR, 3), lufs=round(lufs(x), 1), peak=round(peak_db(x), 1),
                clip=round(clip_ratio(x), 5), centroid=int(spectral_centroid(x)),
                hf=round(hf_ratio(x), 3))
