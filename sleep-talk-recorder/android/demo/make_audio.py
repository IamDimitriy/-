"""Builds demo/night.wav: a quiet bedroom with three sleep-talk phrases, snoring and a knock.

Phrases are synthesised with espeak-ng (Russian voice), the room is soft noise, the snoring is a
low rumble the app should ignore. Run: python3 make_audio.py out.wav
"""
import subprocess
import sys
import tempfile
import wave

import numpy as np

RATE = 22050
rng = np.random.default_rng(3)

PHRASES = [
    "Нет, подожди, я ещё не доделал отчёт",
    "Где мои ключи?",
    "Мама, я не хочу в школу",
]


def room(seconds, level=0.003):
    return (rng.standard_normal(int(RATE * seconds)) * level).astype(np.float32)


def snore(seconds, level=0.25):
    """A low rumble on every breath: 1.5 s of sound every 4 s."""
    t = np.arange(int(RATE * seconds)) / RATE
    breath = ((t % 4.0) < 1.5) * np.sin(np.pi * np.clip((t % 4.0) / 1.5, 0, 1))
    return (level * breath * np.sin(2 * np.pi * 70 * t) * (0.6 + 0.4 * np.sin(2 * np.pi * 28 * t))).astype(np.float32)


def knock(times=3, level=0.5):
    """Knuckles on a wooden door: short decaying bursts of mid-frequency noise."""
    one = int(RATE * 0.25)
    out = np.zeros(one * times, np.float32)
    t = np.arange(int(RATE * 0.06)) / RATE
    burst = rng.standard_normal(len(t)) * np.exp(-t * 70) + np.sin(2 * np.pi * 180 * t) * np.exp(-t * 40)
    for k in range(times):
        out[k * one:k * one + len(t)] += (level * burst / np.abs(burst).max()).astype(np.float32)
    return out


def say(text):
    with tempfile.NamedTemporaryFile(suffix=".wav") as f:
        subprocess.run(["espeak-ng", "-v", "ru", "-s", "130", "-a", "60", "-w", f.name, text], check=True)
        with wave.open(f.name) as w:
            assert w.getsampwidth() == 2 and w.getnchannels() == 1
            rate = w.getframerate()
            data = np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(np.float32) / 32768
    if rate != RATE:
        data = np.interp(np.arange(0, len(data), rate / RATE), np.arange(len(data)), data).astype(np.float32)
    return data * 0.6  # sleepy, not shouting


def over_room(sound):
    return sound + room(len(sound) / RATE)


def main(out):
    parts = [
        room(6),
        over_room(say(PHRASES[0])), room(9),
        over_room(snore(60)), room(6),
        over_room(say(PHRASES[1])), room(9),
        over_room(say(PHRASES[2])), room(12),
        over_room(knock()), room(25),
    ]
    audio = np.clip(np.concatenate(parts), -1, 1)
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((audio * 32767).astype("<i2").tobytes())
    print(f"{out}: {len(audio) / RATE:.1f} s")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "night.wav")
