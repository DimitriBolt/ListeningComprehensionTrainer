"""S03c, п. 2: сверка детектора приложения с пробой Python на записях S03b.

Kotlin-тест `S03bRecordingsTest` (тот же код, что в приложении) пишет `diagnostics/s03c-kotlin.json`: по каждой
записи вероятности кадров, конец записи и «слышал речь». Здесь то же самое считает проба: вероятности —
`endpoint.Silero` (onnxruntime для Python), правило — как в приложении (`app_rule`, копия `VadTurnDetector`),
кнопка — `Rec.press_ms`, меры — `endpoint.evaluate` с разметкой конца речи из `docs/research/03b-endpoint.json`.

    python tools/probe/silero_app_check.py       # сначала ./gradlew testDebugUnitTest (пишет s03c-kotlin.json)

Печатает расхождение вероятностей и концов (в кадрах) и меры обоих.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import endpoint  # noqa: E402
import probe  # noqa: E402

FRAME_MS = 32
KOTLIN = probe.REPO_ROOT / "diagnostics" / "s03c-kotlin.json"


def app_rule(probs: list[float], stop_ms: float, speech_prob: float, silence_ms: int, min_speech_ms: int,
             piece_samples: int = 1280) -> endpoint.Outcome:
    """Как `VadTurnDetector` в приложении: речь — p ≥ speech_prob; конец — silence_ms без речи после последнего
    кадра речи (или с начала); «слышал» — речи ≥ min_speech_ms к концу записи. Звук кусками по 80 мс, как
    в `AudioRecorder`: кусок, начатый до кнопки, обрабатывается целиком; конец позже кнопки не считается."""
    since = speech = 0
    end = None
    for i, p in enumerate(probs):
        piece = (512 * (i + 1) - 1) // piece_samples          # в каком куске кадр становится полным
        if piece * piece_samples * 1000 / endpoint.SAMPLE_RATE >= stop_ms:
            break                                              # этот кусок уже после кнопки
        if p >= speech_prob:
            speech += 1
            since = 0
        else:
            since += 1
        if end is None and since * FRAME_MS >= silence_ms:
            end = (i + 1) * FRAME_MS
            last_piece = piece
        if end is not None and piece > last_piece:
            break                                              # запись кончилась на куске с концом
    if end is not None and end > stop_ms:
        end = None
    return endpoint.Outcome(None if end is None else end / 1000, speech * FRAME_MS >= min_speech_ms)


def main() -> None:
    if not KOTLIN.is_file():
        raise SystemExit(f"Нет {KOTLIN}: сначала ./gradlew testDebugUnitTest (тест S03bRecordingsTest)")
    kt = json.loads(KOTLIN.read_text(encoding="utf-8"))
    st = kt["settings"]
    kt_by = {r["base"]: r for r in kt["recordings"]}
    labels = {r["base"]: r for r in json.loads(endpoint.DOCS_JSON.read_text(encoding="utf-8"))["recordings"]}

    recs = endpoint.load_recordings(endpoint.DEFAULT_RECORDINGS)
    for r in recs:
        lab = labels[r.base]
        if lab.get("last_word_end_s") is not None:
            r.words = [{"word": "", "start": lab.get("first_word_start_s") or 0.0, "end": lab["last_word_end_s"]}]
    sil = endpoint.Silero()
    py_out, kt_out = {}, {}
    worst_dp, worst_base, end_diff = 0.0, "", []
    for r in recs:
        k = kt_by[r.base]
        probs = sil.probs(r.pcm)
        n = min(len(probs), len(k["probs"]))
        dp = max((abs(probs[i] - k["probs"][i]) for i in range(n)), default=0.0)
        if dp > worst_dp:
            worst_dp, worst_base = dp, r.base
        o = app_rule(probs, r.stop_ms, st["speech_prob"], st["silence_ms"], st["min_speech_ms"])
        py_out[r.base] = o
        kt_out[r.base] = endpoint.Outcome(None if k["end_ms"] is None else k["end_ms"] / 1000, k["heard"])
        a, b = o.end_s, kt_out[r.base].end_s
        if (a is None) != (b is None):
            end_diff.append((r.base, a, b, None))
        elif a is not None:
            end_diff.append((r.base, a, b, round((a - b) * 1000 / FRAME_MS)))
    mp, mk = endpoint.evaluate(recs, py_out)["all"], endpoint.evaluate(recs, kt_out)["all"]
    frames = [d for *_, d in end_diff if d is not None]
    print(f"записей {len(recs)}; вероятности: max |Δp| = {worst_dp:.2e} ({worst_base})")
    print(f"конец записи: совпал у {sum(1 for d in frames if d == 0)} из {len(frames)}, "
          f"max расхождение {max((abs(d) for d in frames), default=0)} кадр(ов); "
          f"разное «кончил / не кончил»: {[b for b, *_, d in end_diff if d is None] or 'нет'}")
    for name, m in (("Python (проба)", mp), ("Kotlin (приложение)", mk)):
        print(f"{name}: нет конца {m['no_end']} из {m['speech_lines']}, ложная речь {m['false_speech']} из "
              f"{m['silent_lines']}, задержка медиана {endpoint.fmt(m['latency_med'])} с, ложный конец {m['false_end']}")
    # Для справки: автомат пробы S03b (гистерезис 0,15, отрезок ≥ 250 мс) при тех же 0,5 / 3000 мс.
    hyst = {r.base: endpoint.segment_state_machine(FRAME_MS, sil.probs(r.pcm), 0.5, 3000, r.stop_ms) for r in recs}
    mh = endpoint.evaluate(recs, hyst)["all"]
    print(f"справка, автомат пробы S03b 0,5 / 3000 мс: нет конца {mh['no_end']}, ложная речь {mh['false_speech']}, "
          f"задержка медиана {endpoint.fmt(mh['latency_med'])} с, ложный конец {mh['false_end']}")


if __name__ == "__main__":
    main()
