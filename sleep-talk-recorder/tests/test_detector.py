import numpy as np

from sleeptalk.cli import main
from sleeptalk.detector import Detector
from sleeptalk.storage import read_log, write_wav

SR = 16000
rng = np.random.default_rng(0)


def noise(seconds, level=0.002):
    return (rng.standard_normal(int(SR * seconds)) * level).astype(np.float32)


def speech(seconds, level=0.1):
    """Voiced-speech stand-in: 150 Hz pitch with formant-ish harmonics, syllable rhythm."""
    t = np.arange(int(SR * seconds)) / SR
    tone = sum(np.sin(2 * np.pi * 150 * k * t) / (1 + abs(150 * k - 800) / 400) for k in range(2, 20))
    syllables = 0.5 * (1 + np.sin(2 * np.pi * 4 * t))
    return (level * tone / np.abs(tone).max() * syllables).astype(np.float32)


def snore(seconds, level=0.2):
    t = np.arange(int(SR * seconds)) / SR
    return (level * np.sin(2 * np.pi * 70 * t) * (0.6 + 0.4 * np.sin(2 * np.pi * 30 * t))).astype(np.float32)


def run(audio, chunk=1600):
    det = Detector(SR)
    events = []
    for i in range(0, len(audio), chunk):
        events += det.process(audio[i:i + chunk])
    return events + det.flush()


def test_quiet_night_has_no_events():
    assert run(noise(60)) == []


def test_speech_is_captured_with_preroll():
    audio = np.concatenate([noise(20), noise(3) + speech(3), noise(20)])
    events = run(audio)
    assert len(events) == 1
    ev = events[0]
    assert 18.0 <= ev.start_s <= 20.5        # starts before the words (pre-roll)
    assert 4.0 <= ev.duration_s <= 9.0
    assert ev.active_s >= 1.5


def test_two_separate_phrases_are_two_events():
    audio = np.concatenate([noise(10), noise(2) + speech(2), noise(15), noise(2) + speech(2), noise(10)])
    assert len(run(audio)) == 2


def test_snoring_and_clicks_are_ignored():
    click = np.zeros(SR * 2, np.float32)
    click[100:140] = 0.8
    audio = np.concatenate([noise(10), noise(5) + snore(5), noise(5), noise(2) + click, noise(10)])
    assert run(audio) == []


def test_floor_adapts_to_louder_room():
    audio = np.concatenate([noise(5, 0.001), noise(60, 0.01), noise(2, 0.01) + speech(2, 0.3), noise(10, 0.01)])
    events = run(audio)
    assert len(events) == 1
    assert events[0].floor_db > -45  # followed the room up from ~-60 dBFS


def test_extract_writes_clips_log_and_report(tmp_path):
    night = tmp_path / "night.wav"
    write_wav(night, np.concatenate([noise(15), noise(2) + speech(2), noise(10)]), SR)
    assert main(["--out", str(tmp_path / "rec"), "extract", str(night), "--start", "2026-10-08T23:40"]) == 0
    session = tmp_path / "rec" / "2026-10-08_2340"
    rows = read_log(session)
    assert len(rows) == 1
    assert rows[0]["started_at"].startswith("2026-10-08T23:40:1")
    assert (session / rows[0]["file"]).exists()
    assert "Эпизодов: 1" in (session / "index.html").read_text(encoding="utf-8")
