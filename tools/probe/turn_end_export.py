"""S04, п. 3: данные для проверки кодом приложения (`S04TurnEndRecordingsTest`) — в `diagnostics/s04/` (вне Git, D69).

Для каждой из 48 записей S03b: WAV с тем же хвостом шума, что в пробе `turn-end`, и лента частичного текста потока
(способ а: дельты настоящей сессии `gpt-live-transcribe` из кэша пробы, время прихода = время звука). Плюс итог пробы для
того же варианта (J2 / 1000 / 8000 / 1000) — для сверки. Без сети: всё из кэша пробы.

    python tools/probe/turn_end_export.py          # потом: S04_TURN=1 ./gradlew testDebugUnitTest --tests '*S04TurnEnd*'
    python tools/probe/turn_end_export.py --check  # сравнить diagnostics/s04-kotlin.json с пробой
"""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import probe  # noqa: E402

sys.modules.setdefault("probe", probe)
import endpoint  # noqa: E402
import turn_end as te  # noqa: E402

OUT = probe.REPO_ROOT / "diagnostics" / "s04"
KOTLIN = probe.REPO_ROOT / "diagnostics" / "s04-kotlin.json"
SETTING = te.Setting("a", "J2", 1000, 8000, 1000)


def main() -> None:
    trecs = te.load(endpoint.DEFAULT_RECORDINGS)
    sil = endpoint.Silero()
    for t in trecs:
        t.probs = sil.probs(t.pcm)
    raw = asyncio.run(te.run_sessions(trecs, None, 45, endpoint.Spend(0), 12))
    sources = {"a": {b: te.DeltaText(r) for b, r in raw["a"].items()}}
    per = te.run_all(trecs, sources, te.J3Cache(), [SETTING])[SETTING]
    if "--check" in sys.argv:
        check(trecs, per)
        return
    OUT.mkdir(parents=True, exist_ok=True)
    items = []
    for t in trecs:
        (OUT / f"{t.base}.wav").write_bytes(probe.pcm_to_wav(t.pcm.tobytes(), te.SR))
        src = sources["a"].get(t.base)
        acc = ""
        timeline = []
        for ts, d in (src.deltas if src else []):
            acc += d
            timeline.append({"t": round(ts, 3), "text": acc.strip()})
        r = per[t.base]
        items.append({"base": t.base, "condition": t.rec.condition, "line": t.rec.line, "kind": t.rec.kind,
                      "last_word_end_s": t.last_word_end, "partial": timeline, "final": src.final if src else None,
                      "probe": {"end_s": r.end_s, "ended_by": r.ended_by, "interrupted": r.interrupted, "cancels": r.cancels}})
    (OUT / "index.json").write_text(json.dumps({"setting": SETTING.__dict__, "teacher_audible_s": te.TEACHER_AUDIBLE_S,
                                                "records": items}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(items)} записей → {OUT}")


def check(trecs, per) -> None:
    kt = {r["base"]: r for r in json.loads(KOTLIN.read_text(encoding="utf-8"))["records"]}
    diff = []
    for t in trecs:
        p, k = per[t.base], kt[t.base]
        a, b = p.end_s, k.get("end_s")
        same = (a is None and b is None) or (a is not None and b is not None and abs(a - b) <= 0.033)
        if not same or bool(p.interrupted) != bool(k.get("interrupted")) or len(p.cancels) != len(k.get("cancels", [])):
            diff.append((t.base, a, b, p.interrupted, k.get("interrupted"), len(p.cancels), len(k.get("cancels", []))))
    print(f"записей {len(trecs)}; расхождений с пробой: {len(diff)}")
    for d in diff:
        print("  ", d)


if __name__ == "__main__":
    main()
