"""Streaming detector that finds speech-like sound events in a quiet night.

The detector works on mono float32 audio in [-1, 1] at any sample rate.
It keeps an adaptive estimate of the room's noise floor, marks frames that are
clearly louder than that floor *and* carry most of their energy in the speech
band (which rejects most snoring, fans and traffic rumble), and groups those
frames into events with a pre-roll and a hang-over so words are not clipped.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field

import numpy as np


@dataclass
class DetectorConfig:
    frame_ms: float = 30.0
    # How many dB above the noise floor a frame must be to count as sound.
    threshold_db: float = 10.0
    # Share of frame energy that must lie in the speech band (0 disables the check).
    speech_band: tuple[float, float] = (300.0, 3400.0)
    min_speech_ratio: float = 0.45
    # Event start: this many active frames within the last `start_window` frames.
    start_active: int = 5
    start_window: int = 10
    preroll_s: float = 2.0
    hangover_s: float = 2.5
    min_active_s: float = 0.25  # sleep talk is often one short word
    max_event_s: float = 120.0
    # Noise floor adaptation time constant, seconds.
    # Room silence = 20th percentile of frame levels over 30 s: a snore cannot mask the next words.
    floor_window_s: float = 30.0
    floor_percentile: float = 0.2
    calibration_s: float = 3.0
    # Floor never goes below this, so dead-silent inputs do not trigger on hiss.
    min_floor_db: float = -70.0


@dataclass
class Event:
    start_s: float          # offset of the first sample from the start of the stream
    samplerate: int
    audio: np.ndarray
    peak_db: float
    active_s: float         # time the detector actually heard speech-like sound
    floor_db: float

    @property
    def duration_s(self) -> float:
        return len(self.audio) / self.samplerate


def frame_db(frame: np.ndarray) -> float:
    rms = float(np.sqrt(np.mean(frame.astype(np.float64) ** 2)))
    return 20.0 * np.log10(max(rms, 1e-10))


def speech_ratio(frame: np.ndarray, samplerate: int, band: tuple[float, float]) -> float:
    spectrum = np.abs(np.fft.rfft(frame * np.hanning(len(frame)))) ** 2
    freqs = np.fft.rfftfreq(len(frame), 1.0 / samplerate)
    total = spectrum[freqs > 60.0].sum()
    if total <= 0:
        return 0.0
    in_band = spectrum[(freqs >= band[0]) & (freqs <= band[1])].sum()
    return float(in_band / total)


@dataclass
class _Current:
    start_frame: int
    frames: list = field(default_factory=list)
    active: int = 0
    silent_run: int = 0
    peak_db: float = -120.0


class Detector:
    def __init__(self, samplerate: int, config: DetectorConfig | None = None):
        self.sr = samplerate
        self.cfg = config or DetectorConfig()
        c = self.cfg
        self.frame_len = max(1, int(samplerate * c.frame_ms / 1000))
        fps = samplerate / self.frame_len
        self._preroll = deque(maxlen=max(1, int(c.preroll_s * fps)))
        self._recent = deque(maxlen=c.start_window)
        self._hang_frames = max(1, int(c.hangover_s * fps))
        self._min_active = max(1, int(c.min_active_s * fps))
        self._max_frames = max(1, int(c.max_event_s * fps))
        self._history = deque(maxlen=max(1, int(c.floor_window_s * fps)))
        self._calib_frames = int(c.calibration_s * fps)
        self._calib: list[float] = []
        self._floor: float | None = None
        self._buf = np.zeros(0, dtype=np.float32)
        self._frame_idx = 0
        self._cur: _Current | None = None

    @property
    def floor_db(self) -> float | None:
        return self._floor

    @property
    def in_event(self) -> bool:
        return self._cur is not None

    def process(self, chunk: np.ndarray) -> list[Event]:
        """Feed audio of any length; returns events that finished inside it."""
        self._buf = np.concatenate([self._buf, np.asarray(chunk, dtype=np.float32).ravel()])
        events = []
        n = len(self._buf) // self.frame_len
        for i in range(n):
            frame = self._buf[i * self.frame_len:(i + 1) * self.frame_len]
            ev = self._step(frame)
            if ev is not None:
                events.append(ev)
        self._buf = self._buf[n * self.frame_len:]
        return events

    def flush(self) -> list[Event]:
        """Close an event that is still open at the end of the stream."""
        if self._cur is None:
            return []
        ev = self._finish()
        return [ev] if ev is not None else []

    def _is_active(self, frame: np.ndarray, db: float) -> bool:
        if self._floor is None or db < self._floor + self.cfg.threshold_db:
            return False
        if self.cfg.min_speech_ratio > 0:
            return speech_ratio(frame, self.sr, self.cfg.speech_band) >= self.cfg.min_speech_ratio
        return True

    def _step(self, frame: np.ndarray) -> Event | None:
        c = self.cfg
        idx = self._frame_idx
        self._frame_idx += 1
        db = frame_db(frame)

        if self._floor is None:
            self._calib.append(db)
            self._history.append(db)
            self._preroll.append(frame.copy())
            if len(self._calib) >= max(1, self._calib_frames):
                # Median is robust to a cough during calibration.
                self._floor = max(float(np.median(self._calib)), c.min_floor_db)
            return None

        active = self._is_active(frame, db)
        self._history.append(db)
        if idx % 10 == 0 and len(self._history) >= self._calib_frames:
            self._floor = max(float(np.percentile(self._history, c.floor_percentile * 100)), c.min_floor_db)
        result = None

        if self._cur is None:
            self._recent.append(active)
            if sum(self._recent) >= c.start_active:
                pre = list(self._preroll)
                self._cur = _Current(start_frame=idx - len(pre), frames=pre + [frame.copy()],
                                     active=sum(self._recent), peak_db=db)
                self._recent.clear()
                self._preroll.clear()
            else:
                self._preroll.append(frame.copy())
            return None

        cur = self._cur
        cur.frames.append(frame.copy())
        cur.peak_db = max(cur.peak_db, db)
        if active:
            cur.active += 1
            cur.silent_run = 0
        else:
            cur.silent_run += 1
        if cur.silent_run >= self._hang_frames or len(cur.frames) >= self._max_frames:
            result = self._finish()
        return result

    def _finish(self) -> Event | None:
        cur = self._cur
        self._cur = None
        self._recent.clear()
        # Let the tail become pre-roll for a possible next event.
        for f in cur.frames[-self._preroll.maxlen:]:
            self._preroll.append(f)
        if cur.active < self._min_active:
            return None
        fps = self.sr / self.frame_len
        return Event(
            start_s=max(0, cur.start_frame) * self.frame_len / self.sr,
            samplerate=self.sr,
            audio=np.concatenate(cur.frames),
            peak_db=cur.peak_db,
            active_s=cur.active / fps,
            floor_db=self._floor,
        )
