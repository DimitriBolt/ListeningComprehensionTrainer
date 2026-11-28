"""Пробы S05 — перебивание и команды голосом (`docs/stages/05-brief.md`).

Четыре части (`--part`, по умолчанию все):

- `commands` — как `gpt-live-transcribe` распознаёт команды Stop / Again / Slower (D82). Фразы читает синтез
  Realtime тремя голосами (не голос пользователя), звук идёт в сессию транскрипции в темпе реального времени с
  `prompt` приложения, как в приложении. Узнавание — копией `VoiceCommand.of`; список фраз читается из
  `VoiceCommand.kt`, без копии.
- `slower` — синтез голосом приложения при скорости «Slower» против обычной: длительность, сверка расшифровки,
  смена скорости `session.update` посреди сессии (так делает приложение).
- `interrupted` — учитель после прерванной реплики (D81): в истории прозвучавшая часть с пометкой
  `[interrupted by the student]` (`BargeIn.INTERRUPTED_MARK` в приложении), затем новая реплика ученика.
  Естественно ли отвечает и не повторяет ли прерванное целиком.
- `corrector-long` — поправщик на длинной склеенной реплике (D80): статус ответа, токены, проверка приложения.

Итог — `docs/research/05-probes.md` (+ `.json`). Звук — `tools/probe/out/05/` (вне Git).
"""

from __future__ import annotations

import asyncio
import json
import re
import statistics
import time
from datetime import datetime
from pathlib import Path

import probe
from probe import ProbeError, log, redact

KT_ROOT = probe.REPO_ROOT / "android_app" / "app" / "src" / "main" / "java" / "com" / "example" / "listeningtrainer"
VOICE_COMMAND_KT = KT_ROOT / "conversation" / "VoiceCommand.kt"
BARGE_IN_KT = KT_ROOT / "conversation" / "BargeIn.kt"
MODEL_CONFIG_KT = KT_ROOT / "config" / "ModelConfig.kt"
DOCS_MD = probe.REPO_ROOT / "docs" / "research" / "05-probes.md"
DOCS_JSON = probe.REPO_ROOT / "docs" / "research" / "05-probes.json"
PARTS = ("commands", "slower", "interrupted", "corrector-long")

# ---- копии из Kotlin (сверяет tests/test_s05.py) ------------------------------------------------------------------


def kotlin_const(name: str, path: Path = MODEL_CONFIG_KT) -> str:
    m = re.search(rf"const val {name}\s*=\s*([^\n]+)", path.read_text(encoding="utf-8"))
    if not m:
        raise ProbeError(f"не нашёл {name} в {path.name}")
    return m.group(1).strip().rstrip("f").rstrip("L").strip('"')


def command_phrases(path: Path = VOICE_COMMAND_KT) -> dict[str, list[str]]:
    """`VoiceCommand.PHRASES` из Kotlin: {"stop": [...], "again": [...], "slower": [...], "faster": [...]}."""
    src = path.read_text(encoding="utf-8")
    out = {}
    for name in ("STOP", "AGAIN", "SLOWER", "FASTER"):
        m = re.search(rf"\b{name} to listOf\((.*?)\)", src, re.S)
        if not m:
            raise ProbeError(f"не нашёл {name} в VoiceCommand.kt")
        out[name.lower()] = re.findall(r'"([^"]+)"', m.group(1))
    return out


def command_max_words(path: Path = VOICE_COMMAND_KT) -> int:
    return int(kotlin_const("MAX_WORDS", path))


def interrupted_mark(path: Path = BARGE_IN_KT) -> str:
    return kotlin_const("INTERRUPTED_MARK", path)


def cmd_normalize(text: str) -> str:
    """Копия VoiceCommand.normalize."""
    t = text.replace("’", "'").lower()
    t = re.sub(r"[^\w\s']|_", " ", t)
    return re.sub(r"\s+", " ", t).strip()


def edits(a: str, b: str) -> int:
    """Копия VoiceCommand.edits (Левенштейн)."""
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev[len(b)]


def tolerant_of(word: str, strict: bool = True) -> str | None:
    """Копия VoiceCommand.tolerant (S05b, D84). `strict=False` — правило брифа без условий на буквы (для пробы)."""
    if not word or " " in word:
        return None
    w = word.replace("'", "")
    slow = kotlin_const("SLOW_PREFIX", VOICE_COMMAND_KT)
    if w.startswith(slow):
        return "slower"
    if w.startswith(kotlin_const("FASTER_PREFIX", VOICE_COMMAND_KT)) and \
            edits(w, "faster") <= int(kotlin_const("FASTER_MAX_EDITS", VOICE_COMMAND_KT)):
        return "faster"  # S07
    if (not strict or (w[0] in "aeiou" and "g" in w)) and edits(w, "again") <= int(kotlin_const("AGAIN_MAX_EDITS", VOICE_COMMAND_KT)):
        return "again"
    if (not strict or w.startswith("st")) and edits(w, "stop") <= int(kotlin_const("STOP_MAX_EDITS", VOICE_COMMAND_KT)):
        return "stop"
    return None


def command_of(text: str, phrases: dict[str, list[str]] | None = None, max_words: int | None = None,
               tolerant: str | None = "strict") -> str | None:
    """Копия VoiceCommand.of: имя команды или None. `tolerant`: "strict" — как приложение (S05b), "brief" — правило
    брифа без условий на буквы, None — только список (как в S05)."""
    phrases = phrases or command_phrases()
    max_words = max_words or command_max_words()
    n = cmd_normalize(text)
    if not n or len(n.split(" ")) > max_words:
        return None
    for name, lst in phrases.items():
        if n in lst:
            return name
    if tolerant is None:
        return None
    return tolerant_of(n, strict=tolerant == "strict")


# ---- 1. команды -------------------------------------------------------------------------------------------------

COMMAND_UTTERANCES = [
    # (что говорит «ученик», какая команда ожидается)
    ("Stop.", "stop"), ("Wait.", "stop"), ("Stop, please.", "stop"), ("Wait a moment.", "stop"),
    ("Again.", "again"), ("Repeat.", "again"), ("Say again.", "again"), ("One more time.", "again"),
    ("Again, please.", "again"), ("Repeat, please.", "again"), ("Say it again.", "again"),
    ("Slower.", "slower"), ("Slow down.", "slower"), ("More slowly.", "slower"), ("Slower, please.", "slower"),
    ("Speak slowly.", "slower"),
    # не команды
    ("What?", None), ("I don't understand.", None), ("At noon.", None), ("I stopped.", None),
    ("Wait for me here.", None), ("Yes, again tomorrow.", None), ("Slow food.", None), ("Stop the car now.", None),
]
COMMAND_VOICES = ["alloy", "ash", "coral"]
COMMAND_TEACHER = "Do you like hot weather or cold weather?"
PAD_BEFORE_S = 0.6
PAD_AFTER_S = 1.0


def transcription_prompt(teacher: str) -> str:
    import turn_end
    return f'{turn_end.transcription_context()} The teacher just said: "{teacher}"'


async def _synth_all(key: str, timeout: float, voice: str, texts: list[str], speed: float = 1.0) -> list[dict]:
    out = []
    async with probe.Realtime(key, timeout, probe.REALTIME_URL.format(model=probe.M_REALTIME)) as rt:
        await rt.update_session(probe.synth_session(voice, speed, 1))
        for t in texts:
            out.append(await probe.rt_say(rt, t))
    return out


def _pcm16_from24(pcm24: bytes):
    import numpy as np
    from scipy.signal import resample_poly
    x = np.frombuffer(pcm24, dtype=np.int16).astype(np.float64)
    y = np.clip(np.round(resample_poly(x, 2, 3)), -32768, 32767).astype(np.int16)
    pad1, pad2 = np.zeros(int(16000 * PAD_BEFORE_S), np.int16), np.zeros(int(16000 * PAD_AFTER_S), np.int16)
    return np.concatenate([pad1, y, pad2])


async def _transcribe_all(key: str, timeout: float, pcms: list, prompt: str, parallel: int = 8) -> list[dict]:
    import turn_end
    sem = asyncio.Semaphore(parallel)

    async def one(pcm):
        async with sem:
            return await turn_end.stream_turn(key, timeout, pcm, None, prompt)
    return await asyncio.gather(*[one(p) for p in pcms])


def _final_text(sess: dict) -> str | None:
    main = sess.get("main_item")
    for e in sess.get("events", []):
        if e["type"].endswith("transcription.completed") and (main is None or e.get("item_id") == main):
            return (e.get("transcript") or "").strip()
    return None


def _live_seconds(sess: dict) -> float:
    s = 0.0
    for e in sess.get("events", []):
        if e["type"].endswith("transcription.completed"):
            s += float(((e.get("usage") or {}).get("seconds")) or 0)
    return s


def part_commands(ctx, spend: dict) -> dict:
    phrases, maxw = command_phrases(), command_max_words()
    prompt = transcription_prompt(COMMAND_TEACHER)
    texts = [u for u, _ in COMMAND_UTTERANCES]
    rows = []
    for voice in COMMAND_VOICES:
        synth = asyncio.run(_synth_all(ctx.key, ctx.timeout, voice, texts))
        spend["synth"] += sum(probe.rt_cost(s["usage"]) for s in synth)
        pcms = []
        for (u, _), s in zip(COMMAND_UTTERANCES, synth):
            if s["error"] or not s["pcm"]:
                raise ProbeError(f"синтез «{u}» ({voice}): {s['error'] or 'нет звука'}")
            pcms.append(_pcm16_from24(s["pcm"]))
            ctx.save_wav(f"05/commands/{voice}_{cmd_normalize(u).replace(' ', '_')}.wav", s["pcm"], probe.RT_RATE)
        sessions = asyncio.run(_transcribe_all(ctx.key, ctx.timeout, pcms, prompt))
        for (u, want), s, sess in zip(COMMAND_UTTERANCES, synth, sessions):
            heard = _final_text(sess)
            spend["live"] += _live_seconds(sess) * probe_live_price() / 60
            got = command_of(heard or "", phrases, maxw)
            rows.append({"voice": voice, "said": u, "want": want, "synth_transcript": s["transcript"],
                         "heard": heard, "got": got, "ok": got == want, "error": sess.get("error")})
            log(f"  {voice:6} {u!r:24} → {heard!r:28} → {got or '-':7} {'ok' if got == want else 'ПРОМАХ'}")
    n_cmd = sum(1 for r in rows if r["want"])
    return {
        "teacher": COMMAND_TEACHER, "voices": COMMAND_VOICES, "rows": rows,
        "commands_ok": sum(1 for r in rows if r["want"] and r["ok"]), "commands_total": n_cmd,
        "false_commands": sum(1 for r in rows if not r["want"] and r["got"]),
        "non_commands_total": len(rows) - n_cmd,
    }


def probe_live_price() -> float:
    return float(kotlin_const("LIVE_TRANSCRIBE_PRICE_PER_MIN"))


# ---- 2. медленнее ------------------------------------------------------------------------------------------------

SLOWER_GROUPS = [
    "Yesterday I went", "to the market.", "I bought apples", "and some bread.", "The weather was warm,",
    "so I walked home.", "What did you do", "yesterday?",
]


async def _slower_run(key: str, timeout: float, voice: str, normal: float, slow: float) -> list[dict]:
    """Одна сессия, как в приложении: группа обычной скоростью → `session.update` slow → группа → обратно."""
    rows = []
    async with probe.Realtime(key, timeout, probe.REALTIME_URL.format(model=probe.M_REALTIME)) as rt:
        await rt.update_session(probe.synth_session(voice, normal, 1))
        current = normal
        for g in SLOWER_GROUPS:
            for speed in (normal, slow):
                if speed != current:
                    t0 = time.perf_counter()
                    await rt.update_session(probe.synth_session(voice, speed, 1))
                    upd = time.perf_counter() - t0
                    current = speed
                else:
                    upd = None
                r = await probe.rt_say(rt, g)
                rows.append({"group": g, "speed": speed, "update_s": upd, "audio_s": r["audio_seconds"],
                             "first_audio_s": r["t_first_audio"], "transcript": r["transcript"],
                             "check": probe.compare_texts(g, r["transcript"])["verdict"], "usage": r["usage"],
                             "error": r["error"], "pcm": r["pcm"]})
    return rows


def part_slower(ctx, spend: dict) -> dict:
    voice = kotlin_const("SPEECH_VOICE")
    normal = float(kotlin_const("SPEECH_SPEED"))
    slow = float(kotlin_const("SLOWER_SPEECH_SPEED"))
    rows = asyncio.run(_slower_run(ctx.key, ctx.timeout, voice, normal, slow))
    for i, r in enumerate(rows):
        spend["synth"] += probe.rt_cost(r["usage"])
        if r["pcm"]:
            ctx.save_wav(f"05/slower/{i // 2:02d}_{r['speed']}.wav", r["pcm"], probe.RT_RATE)
        r.pop("pcm")
        upd = "" if r["update_s"] is None else f"  (session.update {r['update_s']:.2f} s)"
        log(f"  {r['speed']:.2f} {r['group']!r:22} {r['audio_s']:.2f} s  {r['check']}{upd}")
    ratios = []
    for i in range(0, len(rows), 2):
        a, b = rows[i], rows[i + 1]
        if a["audio_s"] and b["audio_s"]:
            ratios.append(b["audio_s"] / a["audio_s"])
    good = ("verbatim", "contractions")
    return {
        "voice": voice, "normal": normal, "slow": slow, "rows": rows,
        "ratio_median": statistics.median(ratios) if ratios else None,
        "verbatim_normal": sum(1 for r in rows if r["speed"] == normal and r["check"] in good),
        "verbatim_slow": sum(1 for r in rows if r["speed"] == slow and r["check"] in good),
        "groups": len(SLOWER_GROUPS),
        "update_s_max": max((r["update_s"] for r in rows if r["update_s"] is not None), default=None),
    }


# ---- 3. прервано -------------------------------------------------------------------------------------------------

INTERRUPTED_SCENARIOS = [
    # id, что сказал ученик, сколько групп ответа прозвучало до перебивания, чем ученик перебил
    {"id": "I1", "what": "ученик продолжил рассказ (пауза после законченного предложения)",
     "first": "Yesterday I got up early.", "spoken_groups": 3, "then": "I made coffee and went to the park."},
    {"id": "I2", "what": "ученик переспросил слово посреди ответа",
     "first": "Tell me about the Grand Canyon.", "spoken_groups": 4, "then": "Wait, what is a canyon?"},
    {"id": "I3", "what": "ученик ответил раньше вопроса",
     "first": "I like summer.", "spoken_groups": 2, "then": "Because I like swimming."},
]
INTERRUPTED_REPEATS = 2


def _sentences(text: str) -> list[str]:
    import tutor_dialog as td
    return [s for s in td.sentences(text) if len(cmd_normalize(s).split()) >= 3]


def repeat_share(prev: str, reply: str) -> float:
    """Доля предложений прерванного ответа (из 3+ слов), которые новый ответ повторил дословно (без знаков)."""
    ss = _sentences(prev)
    if not ss:
        return 0.0
    r = f" {cmd_normalize(reply)} "
    return sum(1 for s in ss if f" {cmd_normalize(s)} " in r) / len(ss)


def _teacher(api, prompt: str, history: list[dict], student: str, level: int = 1) -> dict:
    import tutor_dialog as td
    body = td.request_body(prompt, history + [{"role": "user", "content": td.request_text(student, level, 0)}])
    r = probe.responses_stream(api, body)
    full = probe.analyze_stream(r["events"])
    events, stopped = td.app_stop(r["events"])
    a = probe.analyze_stream(events)
    text = td.clean_for_speech(a["text"]).strip()
    if stopped:
        cut = td.first_question_end(text + " ")
        text = text[:cut] if cut else text
    cost = sum(probe.resp_cost(u) for u in full["usages"]) + full["n_search_actions"] * probe.PRICE_WEB_SEARCH
    return {"text": text, "cost": cost, "error": r["error"]}


def part_interrupted(ctx, spend: dict) -> dict:
    import tutor_dialog as td
    from speech_planner import plan
    mark = interrupted_mark()
    prompt = probe.tutor_prompt(datetime.now().strftime("%A, %B %d, %Y"))
    rows = []
    for sc in INTERRUPTED_SCENARIOS:
        for rep in range(1, INTERRUPTED_REPEATS + 1):
            first = _teacher(ctx.api, prompt, [], sc["first"])
            spend["tutor"] += first["cost"]
            groups = [g.text for g in plan(first["text"])]
            spoken = " ".join(x.replace("**", "") for x in groups[:sc["spoken_groups"]]).strip()
            history = [{"role": "user", "content": sc["first"]}, {"role": "assistant", "content": f"{spoken} {mark}"}]
            second = _teacher(ctx.api, prompt, history, sc["then"])
            spend["tutor"] += second["cost"]
            m = td.reply_metrics(second["text"])
            row = {
                "id": sc["id"], "rep": rep, "what": sc["what"], "first_student": sc["first"],
                "first_reply": first["text"], "groups": len(groups), "spoken": spoken, "then": sc["then"],
                "reply": second["text"], "repeat_share": round(repeat_share(first["text"], second["text"]), 2),
                "repeat_share_spoken": round(repeat_share(spoken, second["text"]), 2),
                "mark_leak": bool(re.search(r"interrupt", second["text"], re.I)),
                "question": m["question"], "own_words": m["own_words"],
                "error": first["error"] or second["error"],
            }
            rows.append(row)
            log(f"  {sc['id']}.{rep}: повтор {row['repeat_share']:.2f}, вопрос {'да' if row['question'] else 'нет'}, "
                f"{row['own_words']} сл.{', ПОМЕТКА В ОТВЕТЕ' if row['mark_leak'] else ''}")
    return {"mark": mark, "rows": rows,
            "full_repeats": sum(1 for r in rows if r["repeat_share"] >= 0.5),
            "mark_leaks": sum(1 for r in rows if r["mark_leak"]),
            "questions": sum(1 for r in rows if r["question"])}


# ---- 4. поправщик на длинной реплике ------------------------------------------------------------------------------

LONG_UTTERANCES = [
    # как пишет поток: без знаков препинания; (реплика, есть ли ошибка)
    ("yesterday I got up early and I make coffee then I go to the park with my dog it was very nice weather and "
     "we walk for one hour after that I come back home and cook breakfast for my wife", True),
    ("I want to tell you about my trip to the mountains last summer we drove four hours to Flagstaff and we stay "
     "in a small hotel near the forest every morning we walked on the trails and in the evening we eat pizza", True),
    ("my favorite food is pizza I like pizza with mushrooms and cheese my wife likes pizza with pineapple but I "
     "think it is strange on Fridays we usually order pizza and watch a movie together at home", False),
    ("I work as an engineer in a big company I write programs for computers every day I have many meetings and "
     "sometimes it is boring but I like my colleagues they are very friendly and help me a lot", False),
    ("last weekend my son and me went to the lake we wanted to catch fish but the water was very cold and the "
     "fish don't want to eat so we catched nothing but we had a good time and we saw two eagles", True),
    ("in the morning I usually drink coffee and read the news on my phone then I take a shower and drive to my "
     "office it takes about twenty minutes when there is no traffic", False),
]
LONG_REPEATS = 2


def part_corrector_long(ctx, spend: dict) -> dict:
    import corrector as cr
    prompt = cr.corrector_prompt()
    max_tokens = int(kotlin_const("CORRECTOR_MAX_OUTPUT_TOKENS"))
    rows = []
    for text, has_error in LONG_UTTERANCES:
        for rep in range(1, LONG_REPEATS + 1):
            body = {**cr.corrector_body(prompt, text), "max_output_tokens": max_tokens}
            r = cr.corrector_call(ctx.api, body)
            spend["corrector"] += probe.resp_cost(r["usage"])
            kind, val = cr.check(text, r["text"] or "") if r["text"] else ("failed", None)
            rows.append({"student": text, "words": len(text.split()), "has_error": has_error, "rep": rep,
                         "status": r.get("status"), "error": r["error"], "answer": r["text"], "verdict": kind,
                         "similarity": round(cr.containment(r["text"], text), 2) if r["text"] else None,
                         "output_tokens": (r["usage"] or {}).get("output_tokens"), "seconds": round(r["seconds"], 2)})
            row = rows[-1]
            log(f"  {row['words']} сл., ошибка {'да' if has_error else 'нет'}: {row['status']} → {kind}"
                f" ({row['output_tokens']} ток., {row['seconds']} с)")
    return {"max_output_tokens": max_tokens, "rows": rows,
            "incomplete": sum(1 for r in rows if r["status"] != "completed"),
            "fixes_on_errors": sum(1 for r in rows if r["has_error"] and r["verdict"] == "fix"),
            "errors_total": sum(1 for r in rows if r["has_error"]),
            "false_fixes": sum(1 for r in rows if not r["has_error"] and r["verdict"] == "fix"),
            "rejected": sum(1 for r in rows if r["verdict"] == "rejected"),
            "ok_total": sum(1 for r in rows if not r["has_error"]),
            "output_tokens_max": max((r["output_tokens"] or 0 for r in rows), default=0)}


# ---- запуск и отчёт ----------------------------------------------------------------------------------------------

def estimate(parts: list[str]) -> float:
    e = 0.0
    if "commands" in parts:
        e += len(COMMAND_VOICES) * len(COMMAND_UTTERANCES) * (3.0 * 0.017 / 60 + 0.0008)
    if "slower" in parts:
        e += len(SLOWER_GROUPS) * 2 * 0.001
    if "interrupted" in parts:
        e += len(INTERRUPTED_SCENARIOS) * INTERRUPTED_REPEATS * 2 * 0.001
    if "corrector-long" in parts:
        e += len(LONG_UTTERANCES) * LONG_REPEATS * 0.0002
    return e


def probe_s05(ctx, ask=input) -> bool:
    parts = list(PARTS) if not getattr(ctx.args, "part", None) else ctx.args.part
    est = estimate(parts)
    print(f"Проба S05: {', '.join(parts)}. Оценка (сверху): ≈ ${est:.2f}.")
    if not ctx.args.yes:
        if ask("Запустить? [y/N] ").strip().lower() not in ("y", "yes", "д", "да"):
            print("Отменено.")
            return False
    ctx.key
    data = load()
    spend = {"synth": 0.0, "live": 0.0, "tutor": 0.0, "corrector": 0.0}
    t0 = time.perf_counter()
    fns = {"commands": part_commands, "slower": part_slower, "interrupted": part_interrupted,
           "corrector-long": part_corrector_long}
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


def _cell(s) -> str:
    return "—" if s is None else str(s).replace("|", "\\|").replace("\n", " ")


def render(d: dict) -> str:
    L = ["# Пробы S05 — перебивание и команды голосом", "",
         "Код — `tools/probe/s05.py`, запуск — `python tools/probe/probe.py s05` (`--part commands|slower|interrupted|"
         "corrector-long`). Числа ниже — из последнего прогона каждой части.", ""]
    total = sum(sum(r["spend"].values()) for r in d.get("spend_runs", []))
    L += [f"Всего потрачено на пробы S05: ≈ ${total:.3f}.", ""]
    c = d.get("commands")
    if c:
        L += ["## Команды голосом (D82)", "",
              f"Фразы читает синтез Realtime голосами {', '.join(c['voices'])}; звук — в сессию `gpt-live-transcribe` с "
              f"`prompt` приложения (учитель только что сказал: «{c['teacher']}»), {PAD_BEFORE_S} с тишины до и "
              f"{PAD_AFTER_S} с после. Узнавание — копией `VoiceCommand.of` по списку из `VoiceCommand.kt`. {c['date']}.", "",
              f"- **Команды узнаны:** {c['commands_ok']} из {c['commands_total']}.",
              f"- **Ложные команды** (обычная короткая реплика принята за команду): {c['false_commands']} из "
              f"{c['non_commands_total']}.", "",
              "| Голос | Сказано | Распознано | Команда | Ждали | |", "|---|---|---|---|---|---|"]
        for r in c["rows"]:
            L.append(f"| {r['voice']} | {_cell(r['said'])} | {_cell(r['heard'])} | {r['got'] or '—'} | {r['want'] or '—'} | "
                     f"{'✓' if r['ok'] else '✗'} |")
        L.append("")
    s = d.get("slower")
    if s:
        L += ["## «Slower» — синтез медленнее", "",
              f"Голос `{s['voice']}`, одна сессия: каждая группа обычной скоростью {s['normal']} и медленной {s['slow']}, "
              f"скорость меняется `session.update` между ответами (документация OpenAI: `audio.output.speed` от 0,25 до 1,5, "
              f"только между ответами). {s['date']}.", "",
              f"- **Длительность медленной / обычной:** медиана {s['ratio_median']:.2f}." if s["ratio_median"] else "",
              f"- **Сверка (дословно):** обычная {s['verbatim_normal']} из {s['groups']}, медленная {s['verbatim_slow']} из "
              f"{s['groups']}.",
              f"- **`session.update` со сменой скорости:** до {s['update_s_max']:.2f} с." if s["update_s_max"] else "", "",
              "| Группа | Скорость | Звук, с | Сверка | Расшифровка |", "|---|---|---|---|---|"]
        for r in s["rows"]:
            L.append(f"| {_cell(r['group'])} | {r['speed']} | {r['audio_s']:.2f} | {r['check']} | {_cell(r['transcript'])} |")
        L.append("")
    i = d.get("interrupted")
    if i:
        L += ["## Учитель после перебивания (D81)", "",
              f"В истории учителя — прозвучавшие группы ответа и пометка `{i['mark']}`, затем новая реплика ученика. "
              f"Повтор — доля предложений прерванного ответа (3+ слова), которые новый ответ сказал дословно. {i['date']}.", "",
              f"- **Повторил прерванное целиком (доля ≥ 0,5):** {i['full_repeats']} из {len(i['rows'])}.",
              f"- **Пометка попала в ответ:** {i['mark_leaks']} из {len(i['rows'])}.",
              f"- **Вопрос в конце:** {i['questions']} из {len(i['rows'])}.", ""]
        for r in i["rows"]:
            L += [f"**{r['id']}.{r['rep']} — {r['what']}.** Повтор {r['repeat_share']:.2f}, своих слов {r['own_words']}.", "",
                  f"- Ученик: {r['first_student']}",
                  f"- Учитель (весь ответ, {r['groups']} групп): {r['first_reply']}",
                  f"- Прозвучало до перебивания: {r['spoken']} `{i['mark']}`",
                  f"- Ученик перебил: {r['then']}",
                  f"- Учитель: {r['reply']}", ""]
    k = d.get("corrector-long")
    if k:
        L += ["## Поправщик на длинной реплике (D80)", "",
              f"Склеенные реплики 33–40 слов без знаков (так пишет поток), `max_output_tokens` {k['max_output_tokens']}, "
              f"проверка ответа — копия `Correction.check` (сходство по лучшему окну, S05). {k['date']}.", "",
              f"- **Не `completed`:** {k['incomplete']} из {len(k['rows'])}; выходных токенов — до {k['output_tokens_max']}.",
              f"- **Поправка на репликах с ошибкой:** {k['fixes_on_errors']} из {k['errors_total']}; ложных на правильных — "
              f"{k['false_fixes']} из {k['ok_total']}; отклонено проверкой — {k['rejected']}.", "",
              "| Слов | Ошибка | Статус | Итог | Сходство | Токенов | с | Ответ |", "|---|---|---|---|---|---|---|---|"]
        for r in k["rows"]:
            L.append(f"| {r['words']} | {'да' if r['has_error'] else 'нет'} | {r['status']} | {r['verdict']} | "
                     f"{_cell(r['similarity'])} | {_cell(r['output_tokens'])} | {r['seconds']} | {_cell(r['answer'])} |")
        L.append("")
    return "\n".join(x for x in L if x is not None) + "\n"
