"""A single HTML page per night to listen to clips in the morning."""

from __future__ import annotations

import html
from pathlib import Path

from .storage import read_log

PAGE = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Ночь {title}</title>
<style>
:root {{ --bg:#fafaf7; --fg:#1d1d1b; --muted:#6b6b66; --line:#e2e1da; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#141414; --fg:#ecebe6; --muted:#9a9993; --line:#2c2c2a; }} }}
body {{ background:var(--bg); color:var(--fg); font:16px/1.5 system-ui, sans-serif;
       max-width:720px; margin:0 auto; padding:24px 16px; }}
h1 {{ font-size:22px; margin:0 0 4px; }}
.sub {{ color:var(--muted); margin:0 0 24px; }}
.clip {{ border-top:1px solid var(--line); padding:14px 0; }}
.meta {{ color:var(--muted); font-size:14px; }}
.text {{ margin:6px 0; }}
audio {{ width:100%; }}
</style></head><body>
<h1>Ночь {title}</h1>
<p class="sub">{summary}</p>
{clips}
</body></html>
"""


def build_report(session_dir: Path) -> Path:
    session_dir = Path(session_dir)
    rows = read_log(session_dir)
    blocks = []
    total = 0.0
    for r in rows:
        clip = session_dir / r["file"]
        if not clip.exists():
            continue
        total += float(r["speech_s"])
        txt = clip.with_suffix(".txt")
        text = txt.read_text(encoding="utf-8").strip() if txt.exists() else ""
        time = r["started_at"].split("T")[-1]
        blocks.append(
            f'<div class="clip"><div class="meta">{time} · {r["duration_s"]} с</div>'
            + (f'<p class="text">{html.escape(text)}</p>' if text else "")
            + f'<audio controls preload="none" src="{html.escape(clip.name)}"></audio></div>'
        )
    summary = (f"Эпизодов: {len(blocks)}, речи примерно {total:.0f} с"
               if blocks else "Этой ночью ничего не записано")
    out = session_dir / "index.html"
    out.write_text(PAGE.format(title=html.escape(session_dir.name), summary=summary,
                               clips="\n".join(blocks)), encoding="utf-8")
    return out
