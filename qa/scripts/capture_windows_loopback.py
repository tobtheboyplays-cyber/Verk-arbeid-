#!/usr/bin/env python3
"""Capture the real Windows default-output mix for native-client QA.

Run this with the project-local PyAudioWPatch environment. The result is an
uncompressed stereo WAV that can be aligned with the client video without
altering or normalising the evidence.
"""

from __future__ import annotations

import argparse
import json
import threading
import time
import wave
from pathlib import Path

import pyaudiowpatch as pyaudio


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--duration", type=float, required=True)
    parser.add_argument("--device-index", type=int)
    parser.add_argument("--chunk", type=int, default=1024)
    args = parser.parse_args()
    if not 0.1 <= args.duration <= 7_200:
        parser.error("--duration must be between 0.1 and 7200 seconds")
    if not 128 <= args.chunk <= 16_384:
        parser.error("--chunk must be between 128 and 16384 frames")
    return args


def main() -> int:
    args = parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    audio = pyaudio.PyAudio()
    try:
        if args.device_index is None:
            device = audio.get_default_wasapi_loopback()
        else:
            device = audio.get_device_info_by_index(args.device_index)
        channels = int(device["maxInputChannels"])
        rate = int(device["defaultSampleRate"])
        if channels < 1:
            raise RuntimeError("selected loopback device has no input channels")
        channels = min(channels, 2)
        sample_format = pyaudio.paInt16
        sample_width = audio.get_sample_size(sample_format)
        target_frames = int(args.duration * rate)
        captured_frames = 0
        captured_chunks: list[bytes] = []
        complete = threading.Event()
        frame_width = channels * sample_width

        def receive(in_data: bytes, frame_count: int,
                    _time_info: object, _status: object):
            nonlocal captured_frames
            remaining = target_frames - captured_frames
            if remaining <= 0:
                complete.set()
                return (in_data, pyaudio.paComplete)
            accepted = min(frame_count, remaining)
            captured_chunks.append(in_data[:accepted * frame_width])
            captured_frames += accepted
            if captured_frames >= target_frames:
                complete.set()
                return (in_data, pyaudio.paComplete)
            return (in_data, pyaudio.paContinue)

        started = time.monotonic()
        stream = audio.open(
            format=sample_format,
            channels=channels,
            rate=rate,
            input=True,
            input_device_index=int(device["index"]),
            frames_per_buffer=args.chunk,
            stream_callback=receive,
        )
        try:
            # WASAPI loopback may remain dormant while the endpoint has no
            # active render stream. Never let that stall the release gate:
            # callback delivery gets the requested duration plus a bounded
            # startup allowance, then fails closed with no claimed PASS.
            if not complete.wait(args.duration + 5.0):
                raise RuntimeError(
                    "WASAPI loopback delivered no complete capture window")
        finally:
            if stream.is_active():
                stream.stop_stream()
            stream.close()
        if captured_frames != target_frames:
            raise RuntimeError(
                f"WASAPI loopback captured {captured_frames} of "
                f"{target_frames} requested frames")
        with wave.open(str(args.output), "wb") as wav:
            wav.setnchannels(channels)
            wav.setsampwidth(sample_width)
            wav.setframerate(rate)
            for chunk in captured_chunks:
                wav.writeframesraw(chunk)
        print(json.dumps({
            "status": "PASS",
            "output": str(args.output.resolve()),
            "device_index": int(device["index"]),
            "device_name": str(device["name"]),
            "channels": channels,
            "sample_rate": rate,
            "frames": captured_frames,
            "requested_seconds": args.duration,
            "wall_seconds": round(time.monotonic() - started, 3),
        }, ensure_ascii=False))
        return 0
    finally:
        audio.terminate()


if __name__ == "__main__":
    raise SystemExit(main())
