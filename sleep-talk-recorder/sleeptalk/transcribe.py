"""Optional speech-to-text for saved clips via faster-whisper (runs offline)."""

from __future__ import annotations

from pathlib import Path


def transcribe_session(session_dir: Path, model_size: str = "small", language: str | None = "ru",
                       force: bool = False) -> int:
    try:
        from faster_whisper import WhisperModel
    except ImportError as e:
        raise SystemExit("Для расшифровки установите: pip install faster-whisper") from e

    clips = sorted(Path(session_dir).glob("*.wav"))
    todo = [c for c in clips if force or not c.with_suffix(".txt").exists()]
    if not todo:
        return 0
    model = WhisperModel(model_size, device="auto", compute_type="int8")
    for clip in todo:
        segments, _ = model.transcribe(str(clip), language=language, vad_filter=True,
                                       condition_on_previous_text=False)
        text = " ".join(s.text.strip() for s in segments).strip()
        clip.with_suffix(".txt").write_text(text + "\n", encoding="utf-8")
        print(f"  {clip.name}: {text or '(неразборчиво)'}")
    return len(todo)
