"""Command line: listen | extract | transcribe | report | devices."""

from __future__ import annotations

import argparse
import queue
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

from .detector import Detector, DetectorConfig
from .storage import Session, read_wav

DEFAULT_ROOT = Path("recordings")


def _config(args) -> DetectorConfig:
    return DetectorConfig(
        threshold_db=args.threshold,
        min_speech_ratio=0.0 if args.any_sound else args.speech_ratio,
        min_active_s=args.min_speech,
        hangover_s=args.hangover,
    )


def _until(value: str | None) -> datetime | None:
    if not value:
        return None
    hh, mm = map(int, value.split(":"))
    now = datetime.now()
    end = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
    return end if end > now else end + timedelta(days=1)


def _log(msg: str) -> None:
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


def cmd_listen(args) -> int:
    try:
        import sounddevice as sd
    except (ImportError, OSError) as e:
        print(f"Не удалось подключиться к микрофону: {e}\n"
              "Установите: pip install sounddevice (на Linux ещё: sudo apt install libportaudio2)",
              file=sys.stderr)
        return 1

    sr = args.samplerate
    det = Detector(sr, _config(args))
    session = Session(args.out)
    end = _until(args.until)
    q: queue.Queue = queue.Queue()

    def callback(indata, frames, t, status):
        if status:
            print(status, file=sys.stderr)
        q.put(indata[:, 0].copy())

    _log(f"Слушаю. Клипы сохраняются в {session.dir}. Остановить: Ctrl+C"
         + (f", авто-стоп в {end:%H:%M}" if end else ""))
    _log(f"Калибровка тишины {det.cfg.calibration_s:.0f} с — не шумите.")
    calibrated = False
    try:
        with sd.InputStream(samplerate=sr, channels=1, dtype="float32",
                            device=args.device, blocksize=int(sr * 0.1), callback=callback):
            while end is None or datetime.now() < end:
                try:
                    chunk = q.get(timeout=1.0)
                except queue.Empty:
                    continue
                for ev in det.process(chunk):
                    path = session.save(ev)
                    _log(f"Записано: {path.name} ({ev.duration_s:.1f} с, пик {ev.peak_db:.0f} dB)")
                if not calibrated and det.floor_db is not None:
                    calibrated = True
                    _log(f"Фон комнаты {det.floor_db:.0f} dBFS, порог +{det.cfg.threshold_db:.0f} dB.")
    except KeyboardInterrupt:
        pass
    for ev in det.flush():
        session.save(ev)
    _log(f"Готово. Эпизодов за ночь: {session.count}")
    _finish(session.dir, args)
    return 0


def cmd_extract(args) -> int:
    """Cut speech episodes out of a whole-night recording made by any app."""
    audio, sr = read_wav(Path(args.file))
    started = (datetime.fromisoformat(args.start) if args.start
               else datetime.fromtimestamp(Path(args.file).stat().st_mtime)
               - timedelta(seconds=len(audio) / sr))
    det = Detector(sr, _config(args))
    session = Session(args.out, started)
    step = sr * 10
    t0 = time.monotonic()
    for i in range(0, len(audio), step):
        for ev in det.process(audio[i:i + step]):
            session.save(ev)
    for ev in det.flush():
        session.save(ev)
    print(f"Обработано {len(audio) / sr / 3600:.1f} ч за {time.monotonic() - t0:.0f} с, "
          f"эпизодов: {session.count} → {session.dir}")
    _finish(session.dir, args)
    return 0


def _finish(session_dir: Path, args) -> None:
    if getattr(args, "transcribe", False):
        from .transcribe import transcribe_session
        transcribe_session(session_dir, args.model, args.language)
    from .report import build_report
    print(f"Отчёт: {build_report(session_dir)}")


def _latest(root: Path) -> Path:
    dirs = sorted(p for p in Path(root).iterdir() if p.is_dir()) if Path(root).exists() else []
    if not dirs:
        raise SystemExit(f"В {root} нет записанных ночей")
    return dirs[-1]


def cmd_transcribe(args) -> int:
    from .report import build_report
    from .transcribe import transcribe_session
    d = Path(args.session) if args.session else _latest(args.out)
    n = transcribe_session(d, args.model, args.language, force=args.force)
    print(f"Расшифровано клипов: {n}. Отчёт: {build_report(d)}")
    return 0


def cmd_report(args) -> int:
    from .report import build_report
    d = Path(args.session) if args.session else _latest(args.out)
    print(build_report(d))
    return 0


def cmd_devices(args) -> int:
    import sounddevice as sd
    print(sd.query_devices())
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="sleeptalk", description="Запись разговоров во сне")
    p.add_argument("--out", type=Path, default=DEFAULT_ROOT, help="папка для записей")
    sub = p.add_subparsers(dest="cmd", required=True)

    def detection(sp):
        g = sp.add_argument_group("чувствительность")
        g.add_argument("--threshold", type=float, default=10.0,
                       help="на сколько dB громче фона должен быть звук (меньше = чувствительнее)")
        g.add_argument("--speech-ratio", type=float, default=0.45,
                       help="доля энергии в речевом диапазоне 300–3400 Гц")
        g.add_argument("--any-sound", action="store_true",
                       help="писать любые громкие звуки, не только похожие на речь")
        g.add_argument("--min-speech", type=float, default=0.4,
                       help="минимум секунд звука, чтобы сохранить эпизод")
        g.add_argument("--hangover", type=float, default=2.5,
                       help="сколько секунд тишины завершает эпизод")
        g.add_argument("--transcribe", action="store_true", help="расшифровать после записи")
        g.add_argument("--model", default="small", help="модель Whisper")
        g.add_argument("--language", default="ru")

    sp = sub.add_parser("listen", help="слушать микрофон всю ночь")
    detection(sp)
    sp.add_argument("--device", default=None, help="номер или имя микрофона (см. devices)")
    sp.add_argument("--samplerate", type=int, default=16000)
    sp.add_argument("--until", help="остановиться в это время, например 07:30")
    sp.set_defaults(func=cmd_listen)

    sp = sub.add_parser("extract", help="вырезать эпизоды из WAV-записи всей ночи")
    sp.add_argument("file")
    sp.add_argument("--start", help="когда началась запись, ISO: 2026-10-08T23:40")
    detection(sp)
    sp.set_defaults(func=cmd_extract)

    sp = sub.add_parser("transcribe", help="расшифровать клипы ночи")
    sp.add_argument("session", nargs="?", help="папка ночи (по умолчанию последняя)")
    sp.add_argument("--model", default="small")
    sp.add_argument("--language", default="ru")
    sp.add_argument("--force", action="store_true")
    sp.set_defaults(func=cmd_transcribe)

    sp = sub.add_parser("report", help="собрать HTML-страницу ночи")
    sp.add_argument("session", nargs="?")
    sp.set_defaults(func=cmd_report)

    sp = sub.add_parser("devices", help="список микрофонов")
    sp.set_defaults(func=cmd_devices)
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    if getattr(args, "device", None) is not None and str(args.device).isdigit():
        args.device = int(args.device)
    return args.func(args)
