"""Проба S02e `tutor-dialog`: учитель-собеседник по разделу «Методика» (docs/ARCHITECTURE.md, D41–D47).

Тот же промпт (файл приложения `tutor_prompt.txt`, D46), та же модель и те же параметры запроса, что
в приложении (`ResponsesTutor.requestBody`): `gpt-6-luna`, Responses API, поток, `reasoning.effort: none`,
`web_search` (`low`), `max_output_tokens`, `store: false`; история — реплики ученика и вычищенные ответы.
Реплики ученика заданы заранее (низкий уровень, с ошибками). По каждой реплике учителя — слова,
предложения, вопрос в конце, поиск, время до первой группы; по сценариям — проверки ожиданий брифа.
Плюс короткая проверка: читает ли Realtime (синтез приложения) русское слово в английской фразе и
проходит ли оно сверку.

Итог — `docs/research/02e-dialogs.md` (все прогоны) и сырые данные `docs/research/02e-dialogs.json`.

Документация OpenAI (прочитана 2026-11-24): developers.openai.com/api/reference/resources/responses —
`max_output_tokens` — предел всех выходных токенов ответа, включая токены рассуждения; при нём ответ
кончается `status: incomplete`, `incomplete_details.reason: "max_output_tokens"` (другие причины —
`content_filter` и др.).
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import re
import statistics
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path

import probe
from probe import ProbeError, log, redact
from speech_planner import clean_for_speech, plan

# С S02f прогоны пишутся в 02f-dialogs (поправщик и счёт «не понял» — как в приложении); прогоны 1–6 S02e
# остаются в 02e-dialogs.
DOCS_MD = probe.REPO_ROOT / "docs" / "research" / "02f-dialogs.md"
DOCS_JSON = probe.REPO_ROOT / "docs" / "research" / "02f-dialogs.json"
MODEL_CONFIG_KT = (probe.REPO_ROOT / "android_app" / "app" / "src" / "main" / "java" / "com" / "example"
                   / "listeningtrainer" / "config" / "ModelConfig.kt")

# Копии ModelConfig.kt (тест сверяет): модель, усилие, поиск, предел длины, окно истории.
MODEL = probe.M_TUTOR_NEW
EFFORT = "none"
SEARCH_CONTEXT = "low"
MAX_OUTPUT_TOKENS = 200
HISTORY_MESSAGES = 30
# Синтез приложения (ModelConfig.SPEECH_VOICE, SPEECH_SPEED; промпт 1 = SPEECH_INSTRUCTIONS).
SPEECH_VOICE = "verse"
SPEECH_SPEED = 0.88

# Цели брифа S02e.
GOAL_MEDIAN_WORDS = 15
GOAL_MAX_WORDS = 30
GOAL_QUESTION_SHARE = 0.80

# --------------------------------------------------------------------------------------------------
# Сценарии: (id, название, ожидание, реплики ученика, проверки). Проверки — по шагам (с 1).
# --------------------------------------------------------------------------------------------------

SCENARIOS = [
    {
        "id": "S1", "title": "Вопрос с ошибкой",
        "expect": "сначала поправка — весь вопрос правильно, — затем уточняющий вопрос; поиск только после уточнения (D50: «сначала поиск» — известное ограничение)",
        # Как это запишет распознавание: ошибки написания «Tuscon» в живой речи нет, ошибка — пропущенное «the»
        # (ручная проверка S02e: с «Tuscon» проба поправку видела, на телефоне — нет).
        "turns": ["What is temperature in Tucson?", "At noon.", "Yes, it is hot."],
        "corrections": {1: ["the temperature"]},
        "no_search_before": 2,
    },
    {
        "id": "S2", "title": "Рассказ о дне с ошибками времени",
        "expect": "поправка — правильная фраза первой, затем вопрос",
        "turns": ["Yesterday I go to the shop and buy some apples.",
                  "The apples was very sweet.",
                  "My wife like apples too."],
        "corrections": {1: ["went"], 2: ["were"], 3: ["likes"]},
    },
    {
        "id": "S3", "title": "Ученик дважды не понимает",
        "expect": "проще, проще, затем одно слово по-русски — ровно на шаге 4 (третье «не понимаю», D43)",
        "turns": ["I like to cook.", "What?", "I don't understand.", "Sorry, I don't understand."],
        "russian_at": 4, "no_russian_before": 4,
    },
    {
        "id": "S4", "title": "Уверенные правильные ответы",
        "expect": "учитель чуть усложняет",
        "turns": ["I live in Tucson with my wife.", "I work at home. I write programs.",
                  "I like to read books in the evening.", "My favorite writer is Jack London."],
        "no_corrections": True,
    },
    {
        "id": "S5", "title": "Просьба о лекции",
        "expect": "не лекция: на шаге 1 только вопрос с выбором, без фактов и поиска (S02f); дальше по одному факту",
        "turns": ["Tell me about the history of Arizona.", "The old times, please.", "Yes."],
        "topic_choice_first": True,
    },
    {
        "id": "S6", "title": "Молчание",
        "expect": "учитель повторяет проще и ждёт ответа",
        "turns": ["Hello.", "...", "...", "..."],
        "no_russian_before": 4,
    },
]

# Фразы для проверки русского слова в синтезе (так их нарезал бы SpeechPlanner: группа со словом).
RUSSIAN_PHRASES = ['"Noon" is "полдень".', '"Temperature" is "температура".', 'Night is "ночь".',
                   '"Soup" is "суп".', '"Cook" means "готовить".', 'Happy is "счастливый".']
RUSSIAN_REPEATS = 2

# --------------------------------------------------------------------------------------------------
# Мера реплики (копия ReplyText.kt) и поправка
# --------------------------------------------------------------------------------------------------

_WORD = re.compile(r"[^\W_]+(?:['’][^\W\d_]+)?")
_SENTENCE_SPLIT = re.compile(r"[.!?][\"'”»)\]*]{0,3}\s+")
_SENTENCE_END = re.compile(r"[.!?]+[\"'”»)\]*]*(?=\s|$)")
_TRAILING = re.compile(r"[\"'”»)\]*\s]+$")
_CYRILLIC = re.compile(r"[\u0400-\u04ff]")
_CYRILLIC_WORD = re.compile(r"[\u0400-\u04ff]+(?:[-'’][\u0400-\u04ff]+)*")
# Поправка ученика в начале реплики (D47): I think you meant "…", right? («right?» модель иногда опускает).
_CORRECTION = re.compile(r"""^\s*I think you meant,?\s*["“](?P<fix>.+?)["”](?:\s*,?\s*right\s*\?)?""", re.I | re.S)


def words(text: str) -> int:
    return len(_WORD.findall(text))


def sentences(text: str) -> list[str]:
    """Предложения — как ReplyText.kt: разрез после . ! ? (и до трёх закрывающих знаков) и пробела."""
    t = text.strip()
    parts, last = [], 0
    for m in _SENTENCE_SPLIT.finditer(t):
        parts.append(t[last:m.end()])
        last = m.end()
    parts.append(t[last:])
    return [p for p in parts if _WORD.search(p)]


def reply_metrics(text: str) -> dict:
    """Копия ReplyText.metrics + поправка ученика (слова поправки отдельно, D47)."""
    t = text.strip()
    m = _CORRECTION.match(t)
    rest = t[m.end():].strip() if m else t
    rest_sentences = sentences(rest)
    return {
        "words": words(t),
        "sentences": len(sentences(t)),
        "question": _TRAILING.sub("", t).endswith("?"),
        "russian": bool(_CYRILLIC.search(t)),
        "russian_words": len(_CYRILLIC_WORD.findall(t)),
        "correction": m.group("fix") if m else None,
        "words_outside_correction": words(rest),
        "max_sentence_words": max((words(s) for s in rest_sentences), default=0),
    }


_QUESTION_END = re.compile(r"\?[\"'”»)\]*]*(?=\s)")
_CORRECTION_QUOTE = re.compile(r"^(\s*I think you meant,?\s*)[\"“]", re.I)
_CORRECTION_START = "i think you meant"


def _after_correction(text: str) -> int | None:
    """Копия ReplyText.afterCorrection: 0 — поправки нет; None — она ещё не пришла целиком."""
    lead = text.lstrip().lower()
    if not lead.startswith(_CORRECTION_START):
        return None if _CORRECTION_START.startswith(lead) else 0
    m = _CORRECTION_QUOTE.match(text)
    if not m:
        return None if not lead[len(_CORRECTION_START):].strip(" ,") else 0
    close = re.compile(r"[\"”]").search(text, m.end())
    if not close:
        return None
    after = close.end()
    rest = text[after:]
    tag = re.match(r"\s*,?\s*right\s*\?", rest, re.I)
    if tag:
        return after + tag.end()
    tail = re.sub(r"[\s,]+", "", rest).lower()
    return None if "right?".startswith(tail) else after


def first_question_end(text: str) -> int | None:
    """Копия ReplyText.firstQuestionEnd: реплика кончается первым вопросом (поправка — не вопрос)."""
    start = _after_correction(text)
    if start is None:
        return None
    m = _QUESTION_END.search(text, start)
    return m.end() if m else None


def _tail_is_footnote(tail: str) -> bool:
    from speech_planner import clean_for_speech as clean
    t = tail.lstrip()
    if not t:
        return True
    if t[0] not in "([":
        return False
    depth_sq = depth_rd = 0
    first_open = -1
    for i, ch in enumerate(t):  # копия SpeechText.openBracketLimit
        if ch == "[":
            if depth_sq == 0 and depth_rd == 0:
                first_open = i
            depth_sq += 1
        elif ch == "]" and depth_sq:
            depth_sq -= 1
        elif ch == "(":
            if depth_sq == 0 and depth_rd == 0:
                first_open = i
            depth_rd += 1
        elif ch == ")" and depth_rd:
            depth_rd -= 1
        if depth_sq == 0 and depth_rd == 0:
            first_open = -1
    limit = first_open if first_open >= 0 else len(t)
    return not clean(t[:limit]).strip()


def app_stop(events: list) -> tuple[list, bool]:
    """Где приложение (ResponsesTutor) оборвало бы поток по правилу «реплика кончается первым вопросом»:
    события до остановки и признак обрыва."""
    text, tail, at_q = "", "", False
    for i, (t, e) in enumerate(events):
        typ = e.get("type")
        if typ == "response.output_item.added" and (e.get("item") or {}).get("type") in ("web_search_call", "message"):
            if at_q or first_question_end(text + " ") is not None:
                return events[:i], True
        elif typ == "response.output_text.delta":
            d = e.get("delta") or ""
            if at_q:
                tail += d
                if not _tail_is_footnote(tail):
                    return events[:i], True
                continue
            text += d
            cut = first_question_end(text)
            if cut is not None:
                at_q, tail, text = True, text[cut:], text[:cut]
                if not _tail_is_footnote(tail):
                    return events[:i + 1], True
    return events, False


def drop_unfinished(text: str) -> str:
    """Копия ReplyText.dropUnfinished."""
    last = None
    for last in _SENTENCE_END.finditer(text):
        pass
    return text if last is None else text[:last.end()]


def _norm(s: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s']", " ", s.lower())).strip()


# --------------------------------------------------------------------------------------------------
# Счёт «не понял» подряд (копия NotUnderstood.kt, S02f)
# --------------------------------------------------------------------------------------------------

_NU_LEAD = re.compile(r"^(?:(?:sorry|excuse me|oh|um|uh|hmm|teacher|please|but|i m sorry)\s+)+")
_NU_TRAIL = re.compile(r"(?:\s+(?:please|sorry|teacher|again))+$")
_NU_WHOLE = {"", "what", "pardon", "pardon me", "sorry", "huh", "excuse me", "again", "repeat", "what what",
             "say", "say it", "say that", "say it again", "one more time", "slower", "slowly", "more slowly",
             "i m lost"}
_NU_START = re.compile(
    "^(?:"
    r"i (?:don t|do not|didn t|did not|can t|cannot|could not|couldn t) (?:understand|get it|follow|catch)"
    r"|i (?:don t|do not|didn t|did not) know what"
    r"|i (?:don t|do not) know this word"
    r"|what (?:do|did) you (?:mean|say)"
    r"|what is (?:that|this) word"
    r"|what does .+ mean"
    r"|(?:can|could) you (?:repeat|say (?:it|that)|speak (?:more )?slowly|say it slower)"
    r"|(?:please )?repeat"
    r")(?:\s|$)")


def _nu_norm(s: str) -> str:
    s = s.replace("’", "'").lower()
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]|_", " ", s)).strip()


def is_marker(text: str) -> bool:
    n = _NU_TRAIL.sub("", _NU_LEAD.sub("", _nu_norm(text))).strip()
    return n in _NU_WHOLE or bool(_NU_START.search(n))


def streak(student_texts: list[str]) -> int:
    n = 0
    for t in reversed(student_texts):
        if not is_marker(t):
            break
        n += 1
    return n


def note(count: int) -> str | None:
    if count <= 0:
        return None
    return f"(App note: the student did not understand you {count} {'time' if count == 1 else 'times'} in a row.)"


# --------------------------------------------------------------------------------------------------
# Запрос — как в приложении
# --------------------------------------------------------------------------------------------------

def request_body(prompt: str, history: list[dict]) -> dict:
    """Копия ResponsesTutor.requestBody (без stream — его добавляет responses_stream)."""
    return {
        "model": MODEL, "instructions": prompt,
        "input": [{"role": t["role"], "content": t["content"]} for t in history[-HISTORY_MESSAGES:]],
        "reasoning": {"effort": EFFORT}, "max_output_tokens": MAX_OUTPUT_TOKENS,
        "tools": [{"type": "web_search", "search_context_size": SEARCH_CONTEXT}],
        "include": ["web_search_call.action.sources"], "store": False,
    }


class Budget:
    def __init__(self, limit: float):
        self.limit = limit
        self.spent = 0.0

    @property
    def over(self) -> bool:
        return self.spent >= self.limit


def _teacher_start(an: dict) -> float | None:
    """Когда учитель начал отвечать (первый текст, поиск или конец) — от отправки запроса, с; отсюда
    приложение отсчитывает ожидание поправки (ConversationController, S02f)."""
    starts = [x for x in [an["t_first_token"]] + [s.get("t_start") for s in an["searches"]] if x is not None]
    return min(starts) if starts else an["t_end"]


def run_scenario(api, prompt: str, sc: dict, budget: Budget, corr_prompt: str | None = None) -> dict:
    """Сценарий как в приложении (S02f): счёт «не понял» — служебной строкой в запросе; с [corr_prompt]
    поправщик идёт одновременно с учителем, поправка звучит первой, если готова не позже 0,8 с после начала
    ответа учителя; своя поправка учителя тогда не звучит; в историю — что прозвучало."""
    import corrector as cr
    history: list[dict] = []
    students: list[str] = []
    steps = []
    for i, student in enumerate(sc["turns"], 1):
        if budget.over:
            break
        students.append(student)
        count = streak(students)
        nt = note(count)
        body = request_body(prompt, history + [{"role": "user", "content": student + (f"\n{nt}" if nt else "")}])
        rb = None
        if corr_prompt is not None:
            gate = threading.Barrier(2)

            def ta():
                gate.wait()
                t0 = time.perf_counter()
                return {**probe.responses_stream(api, body), "t_start": t0}

            def tb():
                gate.wait()
                return cr.corrector_call(api, cr.corrector_body(corr_prompt, student))

            with ThreadPoolExecutor(max_workers=2) as pool:
                fa, fb = pool.submit(ta), pool.submit(tb)
                r, rb = fa.result(), fb.result()
        else:
            t0 = time.perf_counter()
            r = {**probe.responses_stream(api, body), "t_start": t0}
        full = probe.analyze_stream(r["events"])
        model_text = clean_for_speech(full["text"]).strip()
        events, stopped = app_stop(r["events"])  # как приложение: реплика кончается первым вопросом
        a = probe.analyze_stream(events)
        error = r["error"] or ("; ".join(a["errors"]) if a["errors"] and a["status"] != "incomplete" else None)
        incomplete = None
        for t, e in r["events"]:
            if e.get("type") == "response.incomplete":
                incomplete = ((e.get("response") or {}).get("incomplete_details") or {}).get("reason")
        text = clean_for_speech(a["text"]).strip()
        if stopped:
            cut = first_question_end(text + " ")
            text = text[:cut] if cut else text
            incomplete = None
        if incomplete == "max_output_tokens":
            text = drop_unfinished(text)
        cost = sum(probe.resp_cost(u) for u in full["usages"]) + full["n_search_actions"] * probe.PRICE_WEB_SEARCH
        # --- поправщик, как в приложении
        corr = None
        fix = None
        t_first_group = a["t_first_group"]
        if rb is not None:
            cost += probe.resp_cost(rb["usage"])
            ts = _teacher_start(full)
            done = rb["t_start"] + rb["seconds"] - r["t_start"]  # от отправки запроса учителю, с
            late = None if ts is None else done - ts
            if rb["error"]:
                outcome = "failed"
            elif ts is not None and late > cr.WAIT_MS / 1000:
                outcome = "late"
            else:
                kind, val = cr.check(student, rb["text"] or "")
                outcome = {"fix": "correction", "none": "ok", "rejected": "rejected"}[kind]
                if kind == "fix":
                    fix = val
            decided = done if outcome != "late" else ts + cr.WAIT_MS / 1000
            corr = {"outcome": outcome, "raw": rb["text"], "error": rb["error"], "seconds": rb["seconds"],
                    "late_vs_teacher_start": late}
            if fix is not None:
                t_first_group = decided
            elif t_first_group is not None:
                t_first_group = max(t_first_group, decided)
        teacher_own = None
        if fix is not None:
            end = _after_correction(text + " ") or 0
            if end > 0:
                teacher_own = text[:end].strip()
                text = text[end:].strip()
            spoken = (cr.phrase(fix) + " " + text).strip()
        else:
            spoken = text
        budget.spent += cost
        usage = (full["usages"] or [None])[-1] or {}
        m = reply_metrics(spoken)
        step = {
            "step": i, "student": student, "teacher": spoken, "raw_chars": len(a["text"]),
            "not_understood": count,
            "ended_at_question": stopped, "model_text": model_text if stopped else None,
            "error": error, "incomplete": incomplete,
            "searched": a["n_search_calls"] > 0,
            "n_search": a["n_search_calls"],
            "search_queries": [q for s_ in a["searches"] for q in (s_.get("queries") or [])],
            "t_first_group": t_first_group, "t_end": a["t_end"],
            "output_tokens": usage.get("output_tokens"), "cost": cost,
            "groups": len(plan(spoken)) if spoken else 0,
            "corrector": corr, "correction_source": "corrector" if fix else ("teacher" if m["correction"] else None),
            "teacher_own_correction_dropped": teacher_own,
            **m,
        }
        steps.append(step)
        log(f"  {sc['id']}.{i}: {step['words']} сл., вопрос {'да' if step['question'] else 'нет'}"
            f"{', поиск' if step['searched'] else ''}{', RU' if step['russian'] else ''}"
            f"{', не понял ×' + str(count) if count else ''}"
            f"{', поправка: ' + step['correction_source'] if step['correction_source'] else ''}"
            f"{', поправщик: ' + corr['outcome'] if corr else ''}"
            f"{', ОБРЕЗАН' if incomplete else ''}{', до первого вопроса' if stopped else ''}"
            f"{'  ОШИБКА: ' + error if error else ''}")
        if error:
            break
        history.append({"role": "user", "content": student})
        history.append({"role": "assistant", "content": spoken})
    return {"id": sc["id"], "steps": steps, "checks": check_scenario(sc, steps)}


def check_scenario(sc: dict, steps: list[dict]) -> list[dict]:
    """Проверки ожиданий брифа. Автоматические и грубые: окончательно судит человек по репликам."""
    out = []
    by = {s["step"]: s for s in steps}

    def add(name, ok, note=""):
        out.append({"check": name, "ok": bool(ok), "note": note})

    for step, frags in sc.get("corrections", {}).items():
        s = by.get(step)
        if not s:
            add(f"шаг {step}: поправка", False, "шага нет")
            continue
        fix = s["correction"]
        ok = fix is not None and all(f in _norm(fix) for f in frags)
        add(f"шаг {step}: поправка первой, в ней «{' … '.join(frags)}»", ok, fix or "поправки нет")
    if "no_search_before" in sc:
        early = [s["step"] for s in steps if s["step"] < sc["no_search_before"] and s["searched"]]
        add(f"поиск не раньше шага {sc['no_search_before']}", not early,
            f"искал на шаге {early}" if early else "")
        first = by.get(1)
        if first:
            add("шаг 1: уточняющий вопрос", first["question"])
    if "russian_at" in sc:
        s = by.get(sc["russian_at"])
        add(f"шаг {sc['russian_at']}: одно слово по-русски", s and s["russian"] and s["russian_words"] == 1,
            f"русских слов: {s['russian_words']}" if s else "шага нет")
    if "no_russian_before" in sc:
        early = [s["step"] for s in steps if s["step"] < sc["no_russian_before"] and s["russian"]]
        add(f"без русского до шага {sc['no_russian_before']}", not early, f"на шагах {early}" if early else "")
    # Ложная поправка — любая поправка на шаге, где ошибки нет (S02f: цель — 0).
    wrong = [s["step"] for s in steps if s["correction"] and s["step"] not in sc.get("corrections", {})]
    add("нет ложных поправок", not wrong, f"поправки на шагах {wrong}" if wrong else "")
    if sc.get("topic_choice_first"):
        s1 = by.get(1)
        if s1:
            rest = s1["teacher"]
            m = _CORRECTION.match(rest)
            rest = rest[m.end():] if m else rest
            only_q = all(x.strip().endswith("?") for x in sentences(rest)) and bool(sentences(rest))
            add("шаг 1: только вопрос с выбором — без фактов и поиска", only_q and not s1["searched"],
                ("искал; " if s1["searched"] else "") + ("" if only_q else "есть утверждение"))
    for s in steps:
        if s["russian_words"] > 1:
            add(f"шаг {s['step']}: не больше одного русского слова", False, f"{s['russian_words']}")
    return out


# --------------------------------------------------------------------------------------------------
# Русское слово в синтезе приложения
# --------------------------------------------------------------------------------------------------

async def _russian_synth(ctx) -> list[dict]:
    out = []
    async with probe.Realtime(ctx.key, ctx.timeout, probe.REALTIME_URL.format(model=probe.M_REALTIME)) as rt:
        await rt.update_session(probe.synth_session(SPEECH_VOICE, SPEECH_SPEED, 1))
        for i, text in enumerate([p for p in RUSSIAN_PHRASES for _ in range(RUSSIAN_REPEATS)], 1):
            r = await probe.rt_say(rt, text)
            heard = r["transcript"]
            verdict = probe.compare_texts(text, heard)["verdict"] if not r["error"] else "error"
            wav = None
            if r["pcm"]:
                ctx.save_wav(f"02e-russian/{i}.wav", r["pcm"], probe.RT_RATE)
                wav = f"tools/probe/out/02e-russian/{i}.wav"
            out.append({"text": text, "transcript": heard, "verdict": verdict, "error": r["error"],
                        "audio_seconds": round(r["audio_seconds"], 2), "cost": probe.rt_cost(r["usage"]), "wav": wav})
            log(f"  RU {i}: {verdict} — {heard!r}")
    return out


def russian_check(ctx, budget: Budget) -> list[dict]:
    try:
        rows = asyncio.run(_russian_synth(ctx))
    except ProbeError as e:
        if "401" in str(e):
            raise
        return [{"text": "", "transcript": "", "verdict": "error", "error": str(e), "cost": 0.0}]
    budget.spent += sum(r["cost"] for r in rows)
    return rows


# --------------------------------------------------------------------------------------------------
# Прогон, итоги, отчёт
# --------------------------------------------------------------------------------------------------

def summarize(scenarios: list[dict]) -> dict:
    steps = [s for sc in scenarios for s in sc["steps"] if not s["error"]]
    ws = [s["words"] for s in steps]
    q = [s["question"] for s in steps]
    checks = [c for sc in scenarios for c in sc["checks"]]
    corr = [c for sc in scenarios if sc["id"] in ("S1", "S2") for c in sc["checks"] if c["check"].startswith("шаг")
            and "поправка" in c["check"]]
    tfg = [s["t_first_group"] for s in steps if s["t_first_group"] is not None]
    tfg_search = [s["t_first_group"] for s in steps if s["searched"] and s["t_first_group"] is not None]
    tfg_plain = [s["t_first_group"] for s in steps if not s["searched"] and s["t_first_group"] is not None]
    summary = {
        "replies": len(steps),
        "median_words": statistics.median(ws) if ws else None, "max_words": max(ws) if ws else None,
        "question_share": sum(q) / len(q) if q else None,
        "max_sentence_words": max((s["max_sentence_words"] for s in steps), default=None),
        "median_sentence_words_max": statistics.median([s["max_sentence_words"] for s in steps]) if steps else None,
        "corrections_ok": sum(c["ok"] for c in corr), "corrections_total": len(corr),
        "checks_ok": sum(c["ok"] for c in checks), "checks_total": len(checks),
        "truncated": sum(1 for s in steps if s["incomplete"]),
        "false_correction_steps": sum(len(re.findall(r"\d+", c["note"])) for sc in scenarios for c in sc["checks"]
                                      if c["check"] == "нет ложных поправок" and not c["ok"]),
        "s3_russian_ok": [all(c["ok"] for c in sc["checks"] if "русск" in c["check"]) for sc in scenarios if sc["id"] == "S3"],
        "s5_topic_ok": [all(c["ok"] for c in sc["checks"] if c["check"].startswith("шаг 1: только вопрос"))
                        for sc in scenarios if sc["id"] == "S5"],
        "corrector_outcomes": _count([s["corrector"]["outcome"] for s in steps if s.get("corrector")]),
        "correction_sources": _count([s["correction_source"] for s in steps if s.get("correction_source")]),
        "ended_at_question": sum(1 for s in steps if s.get("ended_at_question")),
        "searches": sum(1 for s in steps if s["searched"]),
        "errors": sum(1 for sc in scenarios for s in sc["steps"] if s["error"]),
        "median_first_group_plain": statistics.median(tfg_plain) if tfg_plain else None,
        "median_first_group_search": statistics.median(tfg_search) if tfg_search else None,
        "max_first_group": max(tfg) if tfg else None,
    }
    summary["goals"] = {
        "median_words": summary["median_words"] is not None and summary["median_words"] <= GOAL_MEDIAN_WORDS,
        "max_words": summary["max_words"] is not None and summary["max_words"] <= GOAL_MAX_WORDS,
        "question_share": summary["question_share"] is not None and summary["question_share"] >= GOAL_QUESTION_SHARE,
        "corrections": summary["corrections_total"] > 0 and summary["corrections_ok"] == summary["corrections_total"],
        "false_corrections": summary["false_correction_steps"] == 0,
        "s3_russian_step4": bool(summary["s3_russian_ok"]) and all(summary["s3_russian_ok"]),
        "s5_no_facts": bool(summary["s5_topic_ok"]) and all(summary["s5_topic_ok"]),
    }
    summary["goals_met"] = all(summary["goals"].values())
    return summary


def _count(xs: list) -> dict:
    out: dict = {}
    for x in xs:
        out[x] = out.get(x, 0) + 1
    return out


def estimate(repeats: int) -> dict:
    """Оценка сверху, $: реплика ≈ 2500 токенов входа (промпт + история) и 60 выхода; с поиском — +8000
    входа и $0.01; поиск — в половине реплик S1 и S5 (с запасом); синтез трёх фраз Realtime."""
    n = sum(len(sc["turns"]) for sc in SCENARIOS) * repeats
    plain = 2500 * probe.PRICE_LUNA["in"] + 60 * probe.PRICE_LUNA["out"]
    search = 8000 * probe.PRICE_LUNA["in"] + probe.PRICE_WEB_SEARCH
    n_search = 4 * repeats
    rt = len(RUSSIAN_PHRASES) * RUSSIAN_REPEATS * (3 * 20 * probe.PRICE["rt_audio_out"] + 250 * probe.PRICE["rt_text_in"])
    total = n * plain + n_search * search + rt
    return {"requests": n, "total": total}


def prompt_hash(prompt_template: str) -> str:
    return hashlib.sha256(prompt_template.encode("utf-8")).hexdigest()[:8]


def load_runs(path: Path) -> list[dict]:
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8")).get("runs", [])
        except (json.JSONDecodeError, AttributeError):
            return []
    return []


def probe_tutor_dialog(ctx, ask=input) -> bool:
    repeats = max(1, ctx.args.repeats)
    est = estimate(repeats)
    print(f"Проба tutor-dialog: {len(SCENARIOS)} сценариев × {repeats}, ≈ {est['requests']} реплик учителя, "
          f"плюс {len(RUSSIAN_PHRASES)} фразы Realtime с русским словом.")
    print(f"Оценка (сверху): ≈ ${est['total']:.2f}; прогон остановится, если потрачено ${ctx.args.budget:.2f}.")
    if not ctx.args.yes:
        ans = ask("Запустить? [y/N] ").strip().lower()
        if ans not in ("y", "yes", "д", "да"):
            print("Отменено.")
            return False
    ctx.key  # проверить ключ до начала
    date = datetime.now().strftime("%A, %B %d, %Y")
    template = probe.TUTOR_PROMPT_FILE.read_text(encoding="utf-8").strip()
    prompt = probe.tutor_prompt(date)
    import corrector as cr
    corr_prompt = cr.corrector_prompt()
    budget = Budget(ctx.args.budget)
    started = datetime.now().isoformat(timespec="seconds")
    log("Русское слово в синтезе (Realtime, голос приложения)")
    russian = russian_check(ctx, budget)
    scenarios = []
    for rep in range(1, repeats + 1):
        for sc in SCENARIOS:
            if budget.over:
                log(f"!!! бюджет ${budget.limit:.2f} исчерпан — остальные сценарии пропущены")
                break
            log(f"{sc['id']} #{rep}: {sc['title']}")
            res = run_scenario(ctx.api, prompt, sc, budget, corr_prompt)
            res["rep"] = rep
            scenarios.append(res)
    run = {
        "started": started, "finished": datetime.now().isoformat(timespec="seconds"),
        "label": ctx.args.label or "", "model": MODEL, "max_output_tokens": MAX_OUTPUT_TOKENS,
        "date_in_prompt": date, "prompt_hash": prompt_hash(template), "prompt": template,
        "corrector_prompt_hash": prompt_hash(corr_prompt),
        "repeats": repeats, "spent": budget.spent, "budget": budget.limit, "budget_exhausted": budget.over,
        "russian_synthesis": russian, "scenarios": scenarios, "summary": summarize(scenarios),
    }
    docs_json = Path(ctx.args.docs).with_suffix(".json") if ctx.args.docs else DOCS_JSON
    docs_md = Path(ctx.args.docs) if ctx.args.docs else DOCS_MD
    runs = load_runs(docs_json) + [run]
    for i, r in enumerate(runs, 1):
        r["n"] = i
    docs_json.parent.mkdir(parents=True, exist_ok=True)
    docs_json.write_text(redact(json.dumps({"runs": runs}, ensure_ascii=False, indent=1)), encoding="utf-8")
    docs_md.write_text(redact(render(runs)), encoding="utf-8")
    s = run["summary"]
    log(f"\nПрогон {run['n']}: медиана {s['median_words']} сл., максимум {s['max_words']}, вопрос "
        f"{_pct(s['question_share'])}, поправки {s['corrections_ok']}/{s['corrections_total']}; "
        f"цели {'достигнуты' if s['goals_met'] else 'НЕ достигнуты'}. Потрачено ≈ ${budget.spent:.3f}.")
    log(f"Итог: {docs_md}")
    return True


def _okn(xs) -> str:
    return "—" if not xs else f"{sum(1 for x in xs if x)}/{len(xs)}"


def _pct(x) -> str:
    return "—" if x is None else f"{round(100 * x)} %"


def _s(x) -> str:
    return "—" if x is None else f"{x:.1f}".replace(".", ",")


def _yes(b) -> str:
    return "да" if b else "нет"


def _cell(text: str) -> str:
    return (text or "").replace("|", "\\|").replace("\n", " ")


def render(runs: list[dict]) -> str:
    L = ["# S02f — учитель-собеседник с поправщиком: диалоги по сценариям", "",
         "Прогоны S02e (1–6, без поправщика) — в `docs/research/02e-dialogs.md`. С S02f (бриф `docs/stages/02f-brief.md`, "
         "п. 6) проба повторяет приложение: **поправщик** (`corrector_prompt.txt`) идёт одновременно с учителем, его "
         "поправка звучит первой, если готова не позже 0,8 с после начала ответа учителя, своя поправка учителя тогда "
         "не звучит, в историю — что прозвучало; **счёт «не понял» подряд** — служебной строкой в запросе "
         "(`NotUnderstood`). Время первой группы учитывает ожидание поправки.", "",
         "Проба `python tools/probe/probe.py tutor-dialog`. Промпт — файл "
         "приложения `android_app/app/src/main/resources/tutor_prompt.txt` (D46), запрос — как в "
         f"`ResponsesTutor`: `{MODEL}`, `reasoning.effort: {EFFORT}`, поток, `web_search` ({SEARCH_CONTEXT}), "
         "`max_output_tokens`, `store: false`. Реплики ученика заданы заранее. Каждый сценарий — отдельный "
         "разговор.", "",
         f"Цели: медиана слов в реплике ≤ {GOAL_MEDIAN_WORDS}, максимум ≤ {GOAL_MAX_WORDS}, вопрос в конце "
         f"≥ {round(GOAL_QUESTION_SHARE * 100)} % реплик, каждая заложенная ошибка в сценариях 1–2 поправлена (D47).", "",
         "С 6-го прогона в таблицах — то, что **прозвучит** в приложении: реплика кончается первым вопросом "
         "(`ReplyText.firstQuestionEnd`, ручная проверка S02e); что модель написала целиком — в скобках.", "",
         "Слова считаются как в журнале приложения (`ReplyText.metrics`): все слова реплики, вместе с поправкой. "
         "«Макс. в фразе» — самое длинное предложение **без** поправки (предел промпта 6–8 слов). Время до первой "
         "группы — от отправки запроса до момента, когда потоковая нарезка отдала бы первую группу в синтез "
         "(без распознавания и синтеза). Проверки сценариев — автоматические и грубые; окончательно судят по "
         "репликам.", "",
         "## Сводка по прогонам", "",
         "| Прогон | Метка | Промпт | Реплик | Медиана слов | Макс. слов | Вопрос в конце | Макс. в фразе | "
         "Поправки | Ложные | RU S3 шаг 4 | S5 без фактов | Проверки | Обрезано | Цели |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in runs:
        s = r["summary"]
        L.append(f"| {r['n']} | {_cell(r.get('label'))} | `{r['prompt_hash']}` | {s['replies']} | {s['median_words']} | "
                 f"{s['max_words']} | {_pct(s['question_share'])} | {s['max_sentence_words']} | "
                 f"{s['corrections_ok']}/{s['corrections_total']} | {s.get('false_correction_steps', '—')} | "
                 f"{_okn(s.get('s3_russian_ok'))} | {_okn(s.get('s5_topic_ok'))} | {s['checks_ok']}/{s['checks_total']} | "
                 f"{s['truncated']} | {'**да**' if s['goals_met'] else 'нет'} |")
    L.append("")
    for r in reversed(runs):
        L += render_run(r)
    return "\n".join(L) + "\n"


def render_run(r: dict) -> list[str]:
    s = r["summary"]
    L = [f"## Прогон {r['n']} — {r['started']}" + (f" ({_cell(r['label'])})" if r.get("label") else ""), "",
         f"Промпт `{r['prompt_hash']}`, дата в промпте: {r['date_in_prompt']}; `max_output_tokens` "
         f"{r['max_output_tokens']}; повторов {r['repeats']}; потрачено ≈ ${r['spent']:.3f} (бюджет ${r['budget']:.2f}"
         + (", **исчерпан**" if r["budget_exhausted"] else "") + ").", "",
         "| Мера | Значение | Цель |", "|---|---|---|",
         f"| реплик учителя | {s['replies']} | |",
         f"| медиана слов | {s['median_words']} | ≤ {GOAL_MEDIAN_WORDS} {'✓' if s['goals']['median_words'] else '✗'} |",
         f"| максимум слов | {s['max_words']} | ≤ {GOAL_MAX_WORDS} {'✓' if s['goals']['max_words'] else '✗'} |",
         f"| вопрос в конце | {_pct(s['question_share'])} | ≥ {round(GOAL_QUESTION_SHARE * 100)} % "
         f"{'✓' if s['goals']['question_share'] else '✗'} |",
         f"| поправки в сценариях 1–2 | {s['corrections_ok']} из {s['corrections_total']} | все "
         f"{'✓' if s['goals']['corrections'] else '✗'} |",
         f"| ложных поправок (реплик) | {s.get('false_correction_steps', '—')} | 0 "
         f"{'✓' if s['goals'].get('false_corrections') else '✗'} |",
         f"| S3: русское слово ровно на шаге 4 | {_okn(s.get('s3_russian_ok'))} | все "
         f"{'✓' if s['goals'].get('s3_russian_step4') else '✗'} |",
         f"| S5: шаг 1 — только вопрос, без фактов и поиска | {_okn(s.get('s5_topic_ok'))} | все "
         f"{'✓' if s['goals'].get('s5_no_facts') else '✗'} |",
         f"| поправщик: итоги | {_cell(', '.join(f'{k} {v}' for k, v in (s.get('corrector_outcomes') or {}).items()))} | |",
         f"| откуда прозвучавшие поправки | {_cell(', '.join(f'{k} {v}' for k, v in (s.get('correction_sources') or {}).items()))} | |",
         f"| самое длинное предложение без поправки, слов (медиана по репликам / максимум) | "
         f"{s['median_sentence_words_max']} / {s['max_sentence_words']} | 6–8 |",
         f"| проверки сценариев | {s['checks_ok']} из {s['checks_total']} | |",
         f"| обрезано пределом длины | {s['truncated']} | 0 |",
         f"| оборвано на первом вопросе (приложение) | {s.get('ended_at_question', '—')} | |",
         f"| реплик с поиском | {s['searches']} | |",
         f"| первая группа, медиана: без поиска / с поиском, с | {_s(s['median_first_group_plain'])} / "
         f"{_s(s['median_first_group_search'])} (худшее {_s(s['max_first_group'])}) | |",
         f"| ошибок запроса | {s['errors']} | 0 |", ""]
    L += ["### Русское слово в синтезе (Realtime, голос приложения)", "",
          "| Фраза | Расшифровка синтеза | Сверка |", "|---|---|---|"]
    for x in r["russian_synthesis"]:
        L.append(f"| {_cell(x['text'])} | {_cell(x.get('transcript') or x.get('error'))} | {x['verdict']} |")
    L.append("")
    for sc in r["scenarios"]:
        meta = next(m for m in SCENARIOS if m["id"] == sc["id"])
        L += [f"### {sc['id']} #{sc['rep']} — {meta['title']}", "", f"Ожидание: {meta['expect']}.", "",
              "| # | Ученик | Учитель (что прозвучит) | Слов | Предл. | Макс. в фразе | Вопрос | Поиск | RU | Не понял | "
              "Поправщик | Первая группа, с |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for st in sc["steps"]:
            teacher = _cell(st["teacher"]) if not st["error"] else f"**ошибка:** {_cell(st['error'])}"
            if st["incomplete"]:
                teacher += f" *(обрезан: {st['incomplete']})*"
            if st.get("ended_at_question"):
                teacher += f" *(оборван на первом вопросе; модель написала: «{_cell(st['model_text'])}»)*"
            if st.get("teacher_own_correction_dropped"):
                teacher += f" *(своя поправка учителя не прозвучит: «{_cell(st['teacher_own_correction_dropped'])}»)*"
            c = st.get("corrector")
            cinfo = "—" if not c else c["outcome"] + (f" ({_cell(c['raw'])})" if c["outcome"] in ("rejected", "late") and c.get("raw") else "")
            if c and c.get("late_vs_teacher_start") is not None:
                cinfo += f", {c['late_vs_teacher_start']:+.2f} с".replace(".", ",")
            L.append(f"| {st['step']} | {_cell(st['student'])} | {teacher} | {st['words']} | {st['sentences']} | "
                     f"{st['max_sentence_words']} | {_yes(st['question'])} | {'да' if st['searched'] else '—'} | "
                     f"{'да' if st['russian'] else '—'} | {st.get('not_understood') or '—'} | {cinfo} | {_s(st['t_first_group'])} |")
        if sc["checks"]:
            L += ["", "Проверки: " + "; ".join(
                f"{'✓' if c['ok'] else '✗'} {c['check']}" + (f" ({_cell(c['note'])})" if c["note"] and not c["ok"] else "")
                for c in sc["checks"]) + "."]
        L.append("")
    L += ["<details><summary>Промпт этого прогона</summary>", "", "```text", r["prompt"], "```", "", "</details>", ""]
    return L
