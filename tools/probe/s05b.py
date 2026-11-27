"""Пробы S05b — короткие слова (`docs/stages/05b-brief.md`).

Две части (`--part`, по умолчанию обе):

- `commands` — команды Stop / Again / Slower и обычные короткие ответы на синтезе голоса (не голос пользователя), тремя
  голосами. Каждая фраза распознаётся четырьмя путями: поток `gpt-live-transcribe` и файл `gpt-transcribe`, с подсказкой
  команд (`TranscriptionPrompt.COMMANDS_HINT`, D84) и без. Узнавание команд — копией `VoiceCommand.of` (`s05.command_of`):
  правило приложения (терпимое, с условиями на буквы), правило брифа (без условий) и только список (как в S05). Файл —
  кусок как у короткого звука посреди ответа (D85): 500 мс до фразы и 300 мс после.
- `corrector` — поправщик на 6 длинных репликах S05 и 12 коротких из набора S02f: что прозвучит после выравнивания
  (D87, копия `Correction.check`) против прежней поправки целиком.

Итог — `docs/research/05b-probes.md` (+ `.json`). Звук синтеза — `tools/probe/out/05b/` (вне Git), повторный прогон его
не синтезирует заново.
"""

from __future__ import annotations

import asyncio
import difflib
import json
import re
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

import probe
import s05
from probe import ProbeError, log, redact

PROMPT_KT = s05.KT_ROOT / "conversation" / "TranscriptionPrompt.kt"
DOCS_MD = probe.REPO_ROOT / "docs" / "research" / "05b-probes.md"
DOCS_JSON = probe.REPO_ROOT / "docs" / "research" / "05b-probes.json"
PARTS = ("commands", "corrector")

# ---- 1. команды и короткие ответы --------------------------------------------------------------------------------

COMMANDS = [u for u in s05.COMMAND_UTTERANCES if u[1]]  # те же 16 команд, что в S05 (× 3 голоса = 48)
ANSWERS = [  # 30 обычных коротких ответов: ни один не должен стать командой
    "At noon.", "Green.", "Yes, I do.", "Stop the car.", "It is slow.", "I stopped.", "Spot.", "Rain.", "Spain.",
    "Shop.", "Top.", "Driving license.", "No.", "Pizza.", "At night.", "My wife.", "In the morning.", "Coffee.",
    "Slow food.", "Again tomorrow.", "I like it.", "Step by step.", "Agent.", "Seven.", "The park.", "Fishing.",
    "Tucson.", "Not yet.", "Blue.", "A gain of five.",
]
VOICES = s05.COMMAND_VOICES
TEACHER = s05.COMMAND_TEACHER
FILE_PAD_BEFORE_S = 0.5  # как кусок короткого звука (D85): буфер 500 мс
FILE_PAD_AFTER_S = 0.3   # и 300 мс после конца звука
VARIANTS = [("live", False), ("live", True), ("file", False), ("file", True)]
RULES = ("strict", "brief", "list")  # правило приложения, правило брифа, только список (S05)


def kotlin_str(name: str) -> str:
    m = re.search(rf'const val {name}\s*=\s*"([^"]+)"', PROMPT_KT.read_text(encoding="utf-8"))
    if not m:
        raise ProbeError(f"не нашёл {name} в TranscriptionPrompt.kt")
    return m.group(1)


def prompt(hint: bool) -> str:
    """Как `TranscriptionPrompt.build` (с подсказкой) и как до S05b (без неё)."""
    head = kotlin_str("CONTEXT") + (" " + kotlin_str("COMMANDS_HINT") if hint else "")
    return f'{head} The teacher just said: "{TEACHER}"'


def words(s: str) -> list[str]:
    return s05.cmd_normalize(s or "").replace("'", "").split()


def word_acc(said: str, heard: str | None) -> float:
    """Доля сказанных слов, которые распознаны по порядку (без регистра и знаков)."""
    a, b = words(said), words(heard or "")
    if not a:
        return 1.0
    m = difflib.SequenceMatcher(a=a, b=b, autojunk=False)
    return sum(bl.size for bl in m.get_matching_blocks()) / len(a)


SILENT_PEAK = 1000  # синтез иногда отдаёт тишину (пик ~80 из 32767): такой звук не годится, синтезировать заново


def peak(pcm: bytes) -> int:
    import numpy as np
    x = np.frombuffer(pcm, dtype=np.int16)
    return int(np.abs(x.astype(np.int32)).max()) if len(x) else 0


def _synth(ctx, voice: str, texts: list[str], spend: dict) -> tuple[list[bytes], set[str]]:
    """PCM 24 кГц каждой фразы; из кэша `out/05b/synth`, иначе синтез голосом [voice] (тишина — ещё до 2 попыток).
    Второе — фразы, синтезированные в этот раз (их распознавание из прошлого прогона не годится)."""
    def path(t):
        return ctx.out / "05b" / "synth" / f"{voice}_{s05.cmd_normalize(t).replace(' ', '_')}.wav"
    out: dict[str, bytes] = {}
    for t in texts:
        if path(t).exists():
            pcm = probe.wav_to_pcm(path(t).read_bytes())[0]
            if peak(pcm) >= SILENT_PEAK:
                out[t] = pcm
    fresh = {t for t in texts if t not in out}
    for _attempt in range(3):
        missing = [t for t in texts if t not in out]
        if not missing:
            break
        got = asyncio.run(s05._synth_all(ctx.key, ctx.timeout, voice, missing))
        spend["synth"] += sum(probe.rt_cost(s["usage"]) for s in got)
        for t, s in zip(missing, got):
            if s["error"] or not s["pcm"]:
                raise ProbeError(f"синтез «{t}» ({voice}): {s['error'] or 'нет звука'}")
            if peak(s["pcm"]) >= SILENT_PEAK:
                ctx.save_wav(f"05b/synth/{voice}_{s05.cmd_normalize(t).replace(' ', '_')}.wav", s["pcm"], probe.RT_RATE)
                out[t] = s["pcm"]
            else:
                log(f"  синтез «{t}» ({voice}) — тишина, ещё раз")
    if any(t not in out for t in texts):
        raise ProbeError(f"синтез {voice}: тишина три раза подряд")
    return [out[t] for t in texts], fresh


def _clip16(pcm24: bytes, before_s: float, after_s: float):
    import numpy as np
    from scipy.signal import resample_poly
    x = np.frombuffer(pcm24, dtype=np.int16).astype(np.float64)
    y = np.clip(np.round(resample_poly(x, 2, 3)), -32768, 32767).astype(np.int16)
    return np.concatenate([np.zeros(int(16000 * before_s), np.int16), y, np.zeros(int(16000 * after_s), np.int16)])


def _file_all(ctx, clips: list, text_prompt: str, spend: dict) -> list[dict]:
    def one(clip):
        wav = probe.pcm_to_wav(clip.tobytes(), 16000)
        t0 = time.perf_counter()
        r = ctx.api.transcribe(wav, [("model", probe.M_STT_NEW), ("languages[]", "en"), ("prompt", text_prompt)])
        return {"text": r.get("text"), "error": r.get("error"), "ms": round((time.perf_counter() - t0) * 1000),
                "seconds": len(clip) / 16000}
    with ThreadPoolExecutor(8) as ex:
        rows = list(ex.map(one, clips))
    spend["file"] += sum(r["seconds"] for r in rows) / 60 * probe.PRICE["stt_new_min"]
    return rows


def part_commands(ctx, spend: dict) -> dict:
    """Прошлые строки (тот же звук из кэша, тот же `prompt`) берутся без сети; заново — только для нового звука."""
    phrases, maxw = s05.command_phrases(), s05.command_max_words()
    items = [(u, w) for u, w in COMMANDS] + [(a, None) for a in ANSWERS]
    prev = {(r["voice"], r["path"], r["hint"], r["said"]): r for r in (load().get("commands") or {}).get("rows", [])}
    rows = []
    for voice in VOICES:
        pcms, fresh = _synth(ctx, voice, [u for u, _ in items], spend)
        for path, hint in VARIANTS:
            p = prompt(hint)
            keep = {u: prev[(voice, path, hint, u)] for u, _ in items
                    if u not in fresh and (voice, path, hint, u) in prev}
            if len(keep) == len(items):
                rows += [{**keep[u], "got": {r: s05.command_of(keep[u]["heard"] or "", phrases, maxw,
                                                              tolerant=None if r == "list" else r) for r in RULES}}
                         for u, _ in items]
                continue
            todo = [i for i, (u, _) in enumerate(items) if u not in keep]
            sub_pcms = [pcms[i] for i in todo]
            if path == "live":
                sessions = asyncio.run(s05._transcribe_all(ctx.key, ctx.timeout, [s05._pcm16_from24(x) for x in sub_pcms], p))
                got_h = [s05._final_text(s) for s in sessions]
                spend["live"] += sum(s05._live_seconds(s) for s in sessions) * s05.probe_live_price() / 60
                got_e = [s.get("error") for s in sessions]
                got_ms = [None] * len(todo)
            else:
                res = _file_all(ctx, [_clip16(x, FILE_PAD_BEFORE_S, FILE_PAD_AFTER_S) for x in sub_pcms], p, spend)
                got_h = [r["text"] for r in res]
                got_e = [r["error"] for r in res]
                got_ms = [r["ms"] for r in res]
            heard = [keep[u]["heard"] if u in keep else None for u, _ in items]
            errs = [keep[u]["error"] if u in keep else None for u, _ in items]
            ms = [keep[u]["ms"] if u in keep else None for u, _ in items]
            for k, i in enumerate(todo):
                heard[i], errs[i], ms[i] = got_h[k], got_e[k], got_ms[k]
            for (said, want), h, e, m in zip(items, heard, errs, ms):
                got = {r: s05.command_of(h or "", phrases, maxw, tolerant=None if r == "list" else r) for r in RULES}
                rows.append({"voice": voice, "path": path, "hint": hint, "said": said, "want": want, "heard": h,
                             "error": e, "ms": m, "got": got, "acc": round(word_acc(said, h), 3)})
            ok = sum(1 for r in rows[-len(items):] if r["got"]["strict"] == r["want"])
            log(f"  {voice:6} {path:4} {'hint' if hint else 'no hint':7}: верно {ok} из {len(items)}")
    return {"teacher": TEACHER, "voices": VOICES, "prompt_hint": prompt(True), "prompt_plain": prompt(False),
            "rows": rows, "summary": summarize(rows)}


def summarize(rows: list[dict]) -> list[dict]:
    out = []
    for path, hint in VARIANTS:
        rs = [r for r in rows if r["path"] == path and r["hint"] == hint]
        cmd = [r for r in rs if r["want"]]
        ans = [r for r in rs if not r["want"]]
        s = {"path": path, "hint": hint, "commands_total": len(cmd), "answers_total": len(ans),
             "answers_acc": round(sum(r["acc"] for r in ans) / max(1, len(ans)), 3),
             "answers_exact": sum(1 for r in ans if r["acc"] == 1.0 and len(words(r["heard"])) == len(words(r["said"]))),
             "empty": sum(1 for r in rs if not (r["heard"] or "").strip())}
        for rule in RULES:
            s[f"commands_ok_{rule}"] = sum(1 for r in cmd if r["got"][rule] == r["want"])
            s[f"false_{rule}"] = sum(1 for r in ans if r["got"][rule])
        ms = sorted(r["ms"] for r in rs if r["ms"] is not None)
        if ms:
            s["ms_median"], s["ms_p90"] = ms[len(ms) // 2], ms[min(len(ms) - 1, int(len(ms) * 0.9))]
        out.append(s)
    return out


# ---- 2. поправщик: участок с ошибкой (D87) -----------------------------------------------------------------------

def short_cases() -> list[dict]:
    """12 коротких из набора S02f: 9 с ошибкой (по одной на каждые 3) и 3 правильных."""
    import corrector as cr
    cases = cr.load_cases()
    errs = [c for c in cases if c["kind"] != "ok"][::3][:9]
    oks = [c for c in cases if c["kind"] == "ok"][:3]
    return errs + oks


def part_corrector(ctx, spend: dict) -> dict:
    import corrector as cr
    prompt_text = cr.corrector_prompt()
    max_tokens = int(s05.kotlin_const("CORRECTOR_MAX_OUTPUT_TOKENS"))
    items = [{"student": t, "long": True, "has_error": e} for t, e in s05.LONG_UTTERANCES] + \
            [{"student": c["student"], "long": False, "has_error": c["kind"] != "ok", "expected": c["expected"]}
             for c in short_cases()]
    rows = []
    for it in items:
        body = {**cr.corrector_body(prompt_text, it["student"]), "max_output_tokens": max_tokens}
        r = cr.corrector_call(ctx.api, body)
        spend["corrector"] += probe.resp_cost(r["usage"])
        ans = r["text"] or ""
        kind, val = cr.check(it["student"], ans) if r["text"] else ("failed", None)
        # Как было до S05b: поправка целиком (проверка сходства та же).
        whole = ans.strip().strip('"“”').strip() if kind in ("fix", "span") else None
        row = {**it, "words": len(it["student"].split()), "answer": ans, "status": r.get("status"), "verdict": kind,
               "spoken": cr.phrase(val) if kind == "fix" else None,
               "fix_words": len(str(val).split()) if kind == "fix" else (val if kind == "span" else 0),
               "whole_words": len(whole.split()) if whole else 0, "seconds": round(r["seconds"], 2)}
        rows.append(row)
        log(f"  {row['words']:2} сл., {'длинная' if it['long'] else 'короткая'}: {kind} ({row['fix_words']} сл.)")
    long_fix = [r for r in rows if r["long"] and r["verdict"] == "fix"]
    return {"rows": rows,
            "long_fix_max_words": max((r["fix_words"] for r in long_fix), default=0),
            "long_whole_max_words": max((r["whole_words"] for r in rows if r["long"]), default=0),
            "short_changed": sum(1 for r in rows if not r["long"] and r["verdict"] == "fix"
                                 and cr.norm(str(r["spoken"])) != cr.norm(cr.phrase(r["answer"].strip().strip('"“”'))))}


# ---- запуск и отчёт ----------------------------------------------------------------------------------------------

def estimate(parts: list[str]) -> float:
    e = 0.0
    if "commands" in parts:
        n = len(VOICES) * (len(COMMANDS) + len(ANSWERS))
        e += n * 0.0009 + n * 2 * (3.0 * 0.017 / 60) + n * 2 * (1.5 * 0.0045 / 60)
    if "corrector" in parts:
        e += (len(s05.LONG_UTTERANCES) + 12) * 0.0002
    return e


def probe_s05b(ctx, ask=input) -> bool:
    parts = list(PARTS) if not getattr(ctx.args, "part", None) else [p for p in ctx.args.part if p in PARTS]
    est = estimate(parts)
    print(f"Проба S05b: {', '.join(parts)}. Оценка (сверху, без кэша синтеза): ≈ ${est:.2f}.")
    if not ctx.args.yes and ask("Запустить? [y/N] ").strip().lower() not in ("y", "yes", "д", "да"):
        print("Отменено.")
        return False
    ctx.key
    data = load()
    spend = {"synth": 0.0, "live": 0.0, "file": 0.0, "corrector": 0.0}
    t0 = time.perf_counter()
    fns = {"commands": part_commands, "corrector": part_corrector}
    for p in parts:
        log(f"\n== {p} ==")
        data[p] = {**fns[p](ctx, spend), "date": datetime.now().isoformat(timespec="seconds")}
    data.setdefault("spend_runs", []).append({"date": datetime.now().isoformat(timespec="seconds"), "parts": parts,
                                              "spend": {k: round(v, 5) for k, v in spend.items()},
                                              "elapsed_s": round(time.perf_counter() - t0, 1)})
    DOCS_JSON.parent.mkdir(parents=True, exist_ok=True)
    DOCS_JSON.write_text(redact(json.dumps(data, ensure_ascii=False, indent=1)), encoding="utf-8")
    DOCS_MD.write_text(redact(render(data)), encoding="utf-8")
    log(f"\nПотрачено ≈ ${sum(spend.values()):.3f}. Итог: {DOCS_MD}")
    return True


def load() -> dict:
    try:
        return json.loads(DOCS_JSON.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _c(s) -> str:
    return "" if s is None else str(s).replace("|", "\\|").replace("\n", " ")


def render(d: dict) -> str:
    total = sum(sum(r["spend"].values()) for r in d.get("spend_runs", []))
    L = ["# Пробы S05b — короткие слова", "",
         "Код — `tools/probe/s05b.py`, запуск — `python tools/probe/probe.py s05b` (`--part commands|corrector`).", "",
         f"Всего потрачено на пробы S05b: ≈ ${total:.3f}.", ""]
    c = d.get("commands")
    if c:
        n_cmd = len(COMMANDS) * len(VOICES)
        n_ans = len(ANSWERS) * len(VOICES)
        L += ["## Команды и короткие ответы (D83, D84, D85)", "",
              f"Синтез Realtime голосами {', '.join(VOICES)} (не голос пользователя): {len(COMMANDS)} команд S05 и "
              f"{len(ANSWERS)} обычных коротких ответов, каждая фраза каждым голосом — {n_cmd} команд и {n_ans} ответов. "
              f"Учитель только что сказал: «{c['teacher']}». Поток — сессия `gpt-live-transcribe` с `prompt`, 0,6 с тишины "
              f"до и 1,0 с после (как S05). Файл — WAV 16 кГц в `gpt-transcribe`, 0,5 с до и 0,3 с после (кусок короткого "
              f"звука D85). {c['date']}.", "",
              f"- Подсказка: «{kotlin_str('COMMANDS_HINT')}» (`TranscriptionPrompt.COMMANDS_HINT`).",
              "- Правила узнавания: **приложение** — список + терпимое слово с условиями (Again — первая гласная и «g», "
              "Stop — начало «st»); **бриф** — то же без условий на буквы; **список** — только список S05.", "",
              "| Путь | Подсказка | Команды: приложение | бриф | список | Ложных: приложение | бриф | список | "
              "Слов ответов верно | Ответов дословно | Пусто | мс (медиана / p90) |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for s in c["summary"]:
            L.append(f"| {s['path']} | {'да' if s['hint'] else 'нет'} | {s['commands_ok_strict']}/{s['commands_total']} | "
                     f"{s['commands_ok_brief']} | {s['commands_ok_list']} | {s['false_strict']}/{s['answers_total']} | "
                     f"{s['false_brief']} | {s['false_list']} | {s['answers_acc'] * 100:.1f} % | "
                     f"{s['answers_exact']}/{s['answers_total']} | {s['empty']} | "
                     f"{s.get('ms_median', '—')} / {s.get('ms_p90', '—')} |")
        L += ["", "### Промахи и ложные команды (правило приложения), ошибки распознавания ответов", "",
              "| Голос | Путь | Подсказка | Сказано | Распознано | Команда | Ждали | Слов верно |", "|---|---|---|---|---|---|---|---|"]
        for r in c["rows"]:
            bad = r["got"]["strict"] != r["want"] or r["acc"] < 1.0 or r["got"]["brief"] != r["want"]
            if bad:
                L.append(f"| {r['voice']} | {r['path']} | {'да' if r['hint'] else 'нет'} | {_c(r['said'])} | {_c(r['heard'])} | "
                         f"{r['got']['strict'] or '—'}{' (бриф: ' + (r['got']['brief'] or '—') + ')' if r['got']['brief'] != r['got']['strict'] else ''} | "
                         f"{r['want'] or '—'} | {r['acc']:.2f} |")
        L.append("")
    k = d.get("corrector")
    if k:
        L += ["## Поправщик: участок с ошибкой (D87)", "",
              f"{len(s05.LONG_UTTERANCES)} длинных реплик S05 (33–43 слова без знаков) и 12 коротких из набора S02f "
              f"(9 с ошибкой, 3 правильных). Проверка — копия `Correction.check` (S05b). {k['date']}.", "",
              f"- Длинные: самая длинная поправка теперь — **{k['long_fix_max_words']} слов** (целиком было бы до "
              f"{k['long_whole_max_words']}).",
              f"- Короткие: поправка изменилась в {k['short_changed']} из 12 (должно быть 0).", "",
              "| Слов | Длинная | Ошибка | Итог | Слов поправки (было) | Что прозвучит | Ответ поправщика |", "|---|---|---|---|---|---|---|"]
        for r in k["rows"]:
            L.append(f"| {r['words']} | {'да' if r['long'] else 'нет'} | {'да' if r['has_error'] else 'нет'} | {r['verdict']} | "
                     f"{r['fix_words']} ({r['whole_words']}) | {_c(r['spoken'])} | {_c(r['answer'])} |")
        L.append("")
    return "\n".join(L)
