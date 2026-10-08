"""Saving detected events as WAV clips plus a CSV log per night."""

from __future__ import annotations

import csv
import wave
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np

from .detector import Event

LOG_NAME = "events.csv"
LOG_FIELDS = ["file", "started_at", "duration_s", "speech_s", "peak_db", "floor_db"]


def write_wav(path: Path, audio: np.ndarray, samplerate: int) -> None:
    pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(samplerate)
        w.writeframes(pcm.tobytes())


def read_wav(path: Path) -> tuple[np.ndarray, int]:
    """Read a PCM WAV file as mono float32."""
    with wave.open(str(path), "rb") as w:
        sr, ch, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if width == 1:
        data = (np.frombuffer(raw, np.uint8).astype(np.float32) - 128) / 128
    elif width == 2:
        data = np.frombuffer(raw, "<i2").astype(np.float32) / 32768
    elif width == 4:
        data = np.frombuffer(raw, "<i4").astype(np.float32) / 2**31
    else:
        raise ValueError(f"{path}: {width * 8}-bit WAV is not supported, use 16-bit")
    if ch > 1:
        data = data.reshape(-1, ch).mean(axis=1)
    return data, sr


class Session:
    """One night: a folder with clips and an events.csv log."""

    def __init__(self, root: Path, started_at: datetime | None = None):
        self.started_at = started_at or datetime.now()
        self.dir = Path(root) / self.started_at.strftime("%Y-%m-%d_%H%M")
        self.dir.mkdir(parents=True, exist_ok=True)
        self.log_path = self.dir / LOG_NAME
        self.count = 0
        if not self.log_path.exists():
            with open(self.log_path, "w", newline="", encoding="utf-8") as f:
                csv.writer(f).writerow(LOG_FIELDS)

    def save(self, event: Event) -> Path:
        at = self.started_at + timedelta(seconds=event.start_s)
        path = self.dir / f"{at:%H-%M-%S}.wav"
        n = 1
        while path.exists():
            path = self.dir / f"{at:%H-%M-%S}_{n}.wav"
            n += 1
        write_wav(path, event.audio, event.samplerate)
        with open(self.log_path, "a", newline="", encoding="utf-8") as f:
            csv.writer(f).writerow([
                path.name, at.isoformat(timespec="seconds"), f"{event.duration_s:.1f}",
                f"{event.active_s:.1f}", f"{event.peak_db:.1f}", f"{event.floor_db:.1f}",
            ])
        self.count += 1
        return path


def read_log(session_dir: Path) -> list[dict]:
    path = Path(session_dir) / LOG_NAME
    if not path.exists():
        return []
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))
