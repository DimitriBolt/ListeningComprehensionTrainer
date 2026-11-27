"""Проба S02e–S02g `tutor-dialog`: учитель-собеседник по разделу «Методика» (docs/ARCHITECTURE.md, D41–D55).

С S02g: свои слова учителя (`own_words`, D51), самая длинная фраза, вид реплики; судья (`judge_prompt.txt`)
размечает тип вопроса, связь со сказанным и содержание (D52).

С S02h: уровень ученика (D56). Каждый сценарий идёт на своём уровне: цель длины уходит служебной строкой, как в
приложении (`StudentLevel.note`); цели длины — по уровню; S5 — после выбора нет нового выбора; разметка курсива
(D57). Итог — `docs/research/02h-dialogs.md`.

С S02i: при «не понял» в запросе только строка «не понял» с указанием, без цели длины (D58); короткий прогон только по
пунктам брифа — S3 и S6 на уровнях 1 и 5, S1 и S4 на уровне 5; новые меры — чисел в реплике (D61) и названия книг в
кавычках (D60); синтез проверяет фразы с названием в кавычках. Итог — `docs/research/02i-dialogs.md`.


Тот же промпт (файл приложения `tutor_prompt.txt`, D46), та же модель и те же параметры запроса, что
в приложении (`ResponsesTutor.requestBody`): `gpt-6-luna`, Responses API, поток, `reasoning.effort: none`,
`web_search` (`low`), `max_output_tokens`, `store: false`; история — реплики ученика и вычищенные ответы.
Реплики ученика заданы заранее (низкий уровень, с ошибками). По каждой реплике учителя — слова,
предложения, вопрос в конце, поиск, время до первой группы; по сценариям — проверки ожиданий брифа.
Плюс короткая проверка: читает ли Realtime (синтез приложения) русское слово в английской фразе и
проходит ли оно сверку.


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

# С S02i прогоны пишутся в 02i-dialogs; прогоны S02e–S02h остаются в своих файлах.
DOCS_MD = probe.REPO_ROOT / "docs" / "research" / "02i-dialogs.md"
DOCS_JSON = probe.REPO_ROOT / "docs" / "research" / "02i-dialogs.json"
JUDGE_PROMPT_FILE = probe.PROBE_DIR / "judge_prompt.txt"
MODEL_CONFIG_KT = (probe.REPO_ROOT / "android_app" / "app" / "src" / "main" / "java" / "com" / "example"
                   / "listeningtrainer" / "config" / "ModelConfig.kt")

# Копии ModelConfig.kt (тест сверяет): модель, усилие, поиск, предел длины, окно истории.
MODEL = probe.M_TUTOR_NEW
EFFORT = "none"
SEARCH_CONTEXT = "low"
MAX_OUTPUT_TOKENS = 300
HISTORY_MESSAGES = 30
# Синтез приложения (ModelConfig.SPEECH_VOICE, SPEECH_SPEED; промпт 1 = SPEECH_INSTRUCTIONS).
SPEECH_VOICE = "verse"
SPEECH_SPEED = 0.88

# Уровень ученика (S02h, D56) — копия ModelConfig.LEVEL_TARGETS (тест сверяет): уровень → (фраз от, до, слов от, до).
LEVEL_TARGETS = {1: (3, 4, 25, 40), 2: (4, 5, 35, 50), 3: (5, 6, 45, 60), 4: (6, 7, 55, 70), 5: (7, 8, 65, 80)}
# Прогон S02i (бриф п. 5): «не понял» и молчание на уровнях 1 и 5, погода с поиском и Jack London на уровне 5.
LEVEL_RUNS = [("S3", 1), ("S6", 1), ("S3", 5), ("S6", 5), ("S1", 5), ("S4", 5)]

# Цели брифа S02h (п. 4) и прежние S02g. Слова — свои слова учителя, без поправки (own_words).
GOAL_AFTER_NU_MAX = 20           # после «не понял» (S3 шаги 2–4, S6) — на любом уровне
GOAL_SENTENCE_MEDIAN = 10        # самая длинная фраза реплики: медиана (справочно) …
GOAL_SENTENCE_MAX = 14           # … и максимум — на любом уровне
GOAL_QUESTION_SHARE = 0.90
GOAL_LINKED_SHARE = 0.90
GOAL_CONTENT_SHARE = 0.90
GOAL_TYPE_SHARE = 0.25           # среди вопросов обычных реплик: проверка понимания и личный — каждого ≥ 25 %
# Цели S02i (бриф п. 5).
GOAL_NUMBERS_MAX = 2             # чисел в одной реплике — все реплики (D61)
GOAL_L5_MEDIAN_MIN = 58          # обычные реплики на уровне 5: медиана own_words не меньше итогового прогона S02h
# Судья (S02g): тип вопроса, связь со сказанным, содержание. Отдельный короткий запрос без поиска.
JUDGE_MODEL = probe.M_TUTOR_NEW
JUDGE_MAX_OUTPUT_TOKENS = 60
JUDGE_TYPES = ("check", "personal", "clarify", "other", "none")

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
        "topic_choice_first": True, "no_search": True,
    },
    {
        "id": "S6", "title": "Молчание",
        "expect": "учитель повторяет проще и ждёт ответа",
        "turns": ["Hello.", "...", "...", "..."],
        "no_russian_before": 4,
    },
    {
        "id": "S7", "title": "Разговор о питомце",
        "expect": "ученик даёт короткие сведения; учитель каждый раз добавляет содержание (деталь, пример), вопросы "
                  "чередуются: проверка понимания и личный (S02g)",
        "turns": ["I have a dog.", "His name is Max.", "He is five.", "He likes to run."],
        "no_corrections": True,
    },
]

# Фразы для проверки русского слова в синтезе (S02e–S02h; так их нарезал бы SpeechPlanner: группа со словом).
RUSSIAN_PHRASES = ['"Noon" is "полдень".', '"Temperature" is "температура".', 'Night is "ночь".',
                   '"Soup" is "суп".', '"Cook" means "готовить".', 'Happy is "счастливый".']
# S02i (D60): группы с названием в кавычках — кавычки не звучат, сверка проходит. Синтез проверяет их.
TITLE_PHRASES = ['His favorite book is', '"The Call of the Wild".', 'He also wrote "White Fang".']
SYNTH_PHRASES = TITLE_PHRASES
RUSSIAN_REPEATS = 2
# Названия книг Jack London (S4): в реплике — в кавычках, без курсива (D60).
JACK_LONDON_TITLES = ["The Call of the Wild", "White Fang", "Martin Eden", "The Sea-Wolf", "The Sea Wolf",
                      "To Build a Fire", "The Iron Heel", "Burning Daylight", "Love of Life", "The Star Rover",
                      "Smoke Bellew", "Before Adam", "The People of the Abyss"]

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
        # Свои слова учителя без поправки — как ReplyText.Metrics.ownWords (S02g, D51).
        "own_words": words(t[(_after_correction(t + " ") or 0):]),
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
# Счёт «не понял» подряд (копия NotUnderstood.kt, S02f; S05b — без «again», «repeat», D88)
# --------------------------------------------------------------------------------------------------

_NU_LEAD = re.compile(r"^(?:(?:sorry|excuse me|oh|um|uh|hmm|teacher|please|but|i m sorry)\s+)+")
_NU_TRAIL = re.compile(r"(?:\s+(?:please|sorry|teacher|again))+$")
_NU_WHOLE = {"", "what", "pardon", "pardon me", "sorry", "huh", "excuse me", "what what",
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


RUSSIAN_FROM = 3
_NU_SHORT = "Keep it short: 1 or 2 short sentences, 15 words at most, then your question."


def note(count: int) -> str | None:
    """Копия NotUnderstood.note (S02i, D58): строка сама говорит модели, что делать."""
    if count <= 0:
        return None
    head = f"(App note: the student did not understand you {count} {'time' if count == 1 else 'times'} in a row."
    if count < RUSSIAN_FROM:
        return f"{head} Say the same thing again, simpler and with other words. {_NU_SHORT} Only English: not one Russian letter.)"
    return f'{head} Say one key word in Russian, like this: "Noon" is "полдень". Then go on in English. {_NU_SHORT})'



def level_note(level: int) -> str:
    """Копия StudentLevel.note (S02h, D56): цель длины уровня."""
    s0, s1, w0, w1 = LEVEL_TARGETS[level]
    return f"(App note: target length: {s0} to {s1} sentences, {w0} to {w1} words.)"


def request_text(student: str, level: int | None, count: int) -> str:
    """Реплика ученика в запросе — как ResponsesTutor.reply (D58): строка «не понял», а при счёте 0 — цель длины."""
    extra = note(count) or (level_note(level) if level else None)
    return "\n".join([student] + ([extra] if extra else []))


_MARKUP = re.compile(r"(?<!\*)\*(?!\*)|(?<![^\W_]|_)_|_(?![^\W_]|_)")


def markup(text: str) -> int:
    """Сколько знаков разметки курсива (одиночная «*», «_» по краю слова) в тексте (D57)."""
    return len(_MARKUP.findall(text or ""))


_NUM_WORDS = {"two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
              "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
              "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion", "dozen"}
_NUM_TOKEN = re.compile(r"\d+(?:[.,:]\d+)*(?:st|nd|rd|th|s)?|[A-Za-z]+(?:-[A-Za-z]+)*")


def numbers(text: str) -> int:
    """Сколько чисел в тексте (S02i, D61): группа цифр («3:30», «1,000», «68.5») — одно число; число словами —
    тоже («twenty-five», «one hundred and five» — одно). «one» само по себе не считается: чаще это местоимение."""
    toks = [t for t in _NUM_TOKEN.findall(text or "")]

    def numeric(t, nxt):
        if t[0].isdigit():
            return True
        parts = t.lower().split("-")
        if all(x in _NUM_WORDS or x == "one" for x in parts) and any(x in _NUM_WORDS for x in parts):
            return True
        return t.lower() == "one" and nxt is not None and numeric(nxt, None) and not nxt[0].isdigit()

    n, prev = 0, False
    for i, t in enumerate(toks):
        nxt = toks[i + 1] if i + 1 < len(toks) else None
        cur = numeric(t, nxt)
        if cur and not (prev and not t[0].isdigit()):  # число словами сразу после числа словами — то же число
            n += 1
        # «and» после hundred/thousand/million («one hundred and five») не разрывает число.
        prev = (cur and not t[0].isdigit()) or (prev and t.lower() == "and" and i > 0
                                                and toks[i - 1].lower() in ("hundred", "thousand", "million")
                                                and nxt is not None and numeric(nxt, None) and not nxt[0].isdigit())
    return n


def titles(text: str) -> dict:
    """Названия книг Jack London в тексте (S02i, D60): сколько упомянуто, сколько в кавычках, сколько в курсиве."""
    seen = quoted = italic = 0
    for t in JACK_LONDON_TITLES:
        for m in re.finditer(re.escape(t), text or ""):
            seen += 1
            before, after = text[:m.start()], text[m.end():]
            if re.search(r'["“]$', before) and re.match(r'[.,!?]?["”]', after):
                quoted += 1
            if re.search(r"[*_]$", before) or re.match(r"[*_]", after):
                italic += 1
    return {"seen": seen, "quoted": quoted, "italic": italic}


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


def run_scenario(api, prompt: str, sc: dict, budget: Budget, corr_prompt: str | None = None,
                 level: int | None = None) -> dict:
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
        body = request_body(prompt, history + [{"role": "user", "content": request_text(student, level, count)}])
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
            "markup_model": markup(a["text"]), "markup_spoken": markup(spoken),
            "numbers": numbers(own_text(spoken)), "titles": titles(spoken), "titles_model": titles(a["text"]),
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
        log(f"  {sc['id']}.{i}: {step['own_words']} своих сл., вопрос {'да' if step['question'] else 'нет'}"
            f"{', поиск' if step['searched'] else ''}{', RU' if step['russian'] else ''}"
            f"{', чисел ' + str(step['numbers']) if step['numbers'] else ''}"
            f"{', не понял ×' + str(count) if count else ''}"
            f"{', поправка: ' + step['correction_source'] if step['correction_source'] else ''}"
            f"{', поправщик: ' + corr['outcome'] if corr else ''}"
            f"{', ОБРЕЗАН' if incomplete else ''}{', до первого вопроса' if stopped else ''}"
            f"{'  ОШИБКА: ' + error if error else ''}")
        if error:
            break
        history.append({"role": "user", "content": student})
        history.append({"role": "assistant", "content": spoken})
    return {"id": sc["id"], "level": level, "steps": steps, "checks": check_scenario(sc, steps)}


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
            problems = clarify_problems(s1["teacher"], s1["student"])
            if s1["searched"]:
                problems.insert(0, "искал")
            add("шаг 1: вопрос с выбором, до него не больше одной фразы о теме — без цифр, дат, имён и поиска",
                not problems, "; ".join(problems))
    if sc.get("no_search"):
        found = [s["step"] for s in steps if s["searched"]]
        add("нет поиска ни на одном шаге", not found, f"искал на шагах {found}" if found else "")
    for s in steps:
        if s["russian_words"] > 1:
            add(f"шаг {s['step']}: не больше одного русского слова", False, f"{s['russian_words']}")
    return out


_DIGIT = re.compile(r"\d")
_MONTHS = re.compile(r"\b(January|February|March|April|May|June|July|August|September|October|November|December|"
                     r"century|centuries)\b", re.I)


def clarify_problems(teacher: str, student: str) -> list[str]:
    """Уточняющая реплика (S02g): одна фраза о теме без цифр, дат и имён, затем вопрос с выбором. Имя — слово с
    большой буквы не в начале фразы, которого нет у ученика (тема ученика — «Arizona» — именем не считается)."""
    m = _CORRECTION.match(teacher)
    rest = teacher[m.end():] if m else teacher
    sents = sentences(rest)
    out = []
    if not sents or not sents[-1].strip().endswith("?"):
        return ["нет вопроса в конце"]
    q = sents[-1]
    if " or " not in f" {q.lower()} ":
        out.append("в вопросе нет выбора")
    statements = sents[:-1]
    if len(statements) > 1:
        out.append(f"фраз до вопроса: {len(statements)}")
    known = {w.lower() for w in _WORD.findall(student)} | {"i"}
    for st in sents:
        body = st.strip()
        if _DIGIT.search(body) or _MONTHS.search(body):
            out.append("цифры или даты")
            break
    names = sorted({w for st in sents for w in _WORD.findall(st)[1:] if w[0].isupper() and w.lower() not in known})
    if names:
        out.append("имена: " + ", ".join(names))
    return out


# --------------------------------------------------------------------------------------------------
# Судья (S02g): тип вопроса, связь со сказанным, содержание
# --------------------------------------------------------------------------------------------------

def judge_prompt() -> str:
    return JUDGE_PROMPT_FILE.read_text(encoding="utf-8").strip()


def own_text(teacher: str) -> str:
    """Свой текст учителя — без поправки в начале (судья её не видит)."""
    return teacher[(_after_correction(teacher + " ") or 0):].strip()


def judge_body(prompt: str, student: str, teacher: str) -> dict:
    return {"model": JUDGE_MODEL, "instructions": prompt,
            "input": [{"role": "user", "content": f"Student: {student}\nTeacher: {own_text(teacher)}"}],
            "reasoning": {"effort": EFFORT}, "max_output_tokens": JUDGE_MAX_OUTPUT_TOKENS, "store": False}


def parse_judge(text: str | None) -> dict | None:
    """Строка JSON судьи → {type, linked, content}; None — разобрать нельзя."""
    m = re.search(r"\{.*\}", text or "", re.S)
    if not m:
        return None
    try:
        d = json.loads(m.group(0))
    except json.JSONDecodeError:
        return None
    t = str(d.get("type", "")).strip().lower()
    if t not in JUDGE_TYPES or not isinstance(d.get("linked"), bool) or not isinstance(d.get("content"), bool):
        return None
    return {"type": t, "linked": d["linked"], "content": d["content"]}


def judge_steps(api, prompt: str, scenarios: list[dict], budget: "Budget") -> None:
    """Разметка судьи для каждой реплики (параллельно); пишет step["judge"] и step["kind"]."""
    import corrector as cr
    steps = [st for sc in scenarios for st in sc["steps"] if not st["error"]]

    def one(st):
        r = cr.corrector_call(api, judge_body(prompt, st["student"], st["teacher"]))
        return st, r

    with ThreadPoolExecutor(max_workers=8) as pool:
        for st, r in pool.map(one, steps):
            budget.spent += probe.resp_cost(r["usage"]) if r.get("usage") else 0.0
            j = parse_judge(r.get("text"))
            st["judge"] = j or {"type": None, "linked": None, "content": None,
                                "error": r.get("error") or f"не разобрано: {r.get('text')!r}"}
    for st in steps:
        st["kind"] = step_kind(st)


def step_kind(st: dict) -> str:
    """Обычная реплика, уточнение или ответ после «не понял» (S02g, цели длины)."""
    if st.get("not_understood"):
        return "after_nu"
    if (st.get("judge") or {}).get("type") == "clarify":
        return "clarify"
    return "normal"


# --------------------------------------------------------------------------------------------------
# Русское слово в синтезе приложения
# --------------------------------------------------------------------------------------------------

async def _russian_synth(ctx) -> list[dict]:
    out = []
    async with probe.Realtime(ctx.key, ctx.timeout, probe.REALTIME_URL.format(model=probe.M_REALTIME)) as rt:
        await rt.update_session(probe.synth_session(SPEECH_VOICE, SPEECH_SPEED, 1))
        for i, text in enumerate([p for p in SYNTH_PHRASES for _ in range(RUSSIAN_REPEATS)], 1):
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

def _median(xs):
    return statistics.median(xs) if xs else None


def _share(xs) -> float | None:
    xs = [x for x in xs if x is not None]
    return sum(1 for x in xs if x) / len(xs) if xs else None


def summarize(scenarios: list[dict]) -> dict:
    steps = [s for sc in scenarios for s in sc["steps"] if not s["error"]]
    normal = [s for s in steps if s.get("kind", step_kind(s)) == "normal"]
    after_nu = [s for s in steps if s.get("kind", step_kind(s)) == "after_nu"]
    clarify = [s for s in steps if s.get("kind", step_kind(s)) == "clarify"]
    judged = [s for s in steps if (s.get("judge") or {}).get("type")]
    q = [s["question"] for s in steps]
    checks = [c for sc in scenarios for c in sc["checks"]]
    corr = [c for sc in scenarios if sc["id"] in ("S1", "S2") for c in sc["checks"] if c["check"].startswith("шаг")
            and "поправка" in c["check"]]
    tfg_search = [s["t_first_group"] for s in steps if s["searched"] and s["t_first_group"] is not None]
    tfg_plain = [s["t_first_group"] for s in steps if not s["searched"] and s["t_first_group"] is not None]
    tfg = tfg_search + tfg_plain
    s4 = []
    for sc in scenarios:
        if sc["id"] == "S4" and (sc.get("level") or 1) == 1:
            by = {s["step"]: s for s in sc["steps"] if not s["error"]}
            if 1 in by and 4 in by:
                s4.append(by[4]["own_words"] > by[1]["own_words"])
    nq = [s["judge"]["type"] for s in normal if (s.get("judge") or {}).get("type") not in (None, "none")]
    types = _count(nq)
    alt = []  # чередование: соседние обычные реплики одного разговора — разного типа (справочно)
    for sc in scenarios:
        prev = None
        for s in sc["steps"]:
            t = (s.get("judge") or {}).get("type")
            if s.get("kind") != "normal" or t in (None, "none"):
                continue
            if prev is not None:
                alt.append(t != prev)
            prev = t
    tokens = [s["output_tokens"] for s in steps if s.get("output_tokens") is not None]
    # S02h: по уровням (уровень сценария; прогоны до S02h — без уровня, считаются уровнем 1).
    levels = sorted({sc.get("level") or 1 for sc in scenarios})
    lvl_steps = {lv: [s for sc in scenarios if (sc.get("level") or 1) == lv for s in sc["steps"] if not s["error"]]
                 for lv in levels}
    by_level = {}
    for lv, ss in lvl_steps.items():
        nn = [s for s in ss if s.get("kind", step_kind(s)) == "normal"]
        tk = [s["output_tokens"] for s in ss if s.get("output_tokens") is not None]
        tks = [s["output_tokens"] for s in ss if s.get("output_tokens") is not None and s["searched"]]
        by_level[lv] = {
            "normal": len(nn), "own_median_normal": _median([s["own_words"] for s in nn]),
            "own_min_normal": min((s["own_words"] for s in nn), default=None),
            "own_max_normal": max((s["own_words"] for s in nn), default=None),
            "sentences_median_normal": _median([s["sentences"] for s in nn if "sentences" in s]),
            "max_sentence_words": max((s["max_sentence_words"] for s in ss), default=None),
            "own_max_after_nu": max((s["own_words"] for s in ss if s.get("kind", step_kind(s)) == "after_nu"), default=None),
            "max_output_tokens": max(tk) if tk else None, "max_output_tokens_search": max(tks) if tks else None,
        }
    # S5: после выбора ученика (шаг 2) — рассказ, а не новый выбор (судья: не уточнение).
    s5_choice = []
    for sc in scenarios:
        if sc["id"] == "S5":
            st2 = next((x for x in sc["steps"] if x["step"] == 2 and not x["error"]), None)
            if st2 is not None and (st2.get("judge") or {}).get("type"):
                s5_choice.append(st2["judge"]["type"] != "clarify")
    summary = {
        "replies": len(steps), "normal": len(normal), "after_nu": len(after_nu), "clarify": len(clarify),
        "median_words": _median([s["words"] for s in steps]), "max_words": max((s["words"] for s in steps), default=None),
        "own_median_normal": _median([s["own_words"] for s in normal]),
        "own_max": max((s["own_words"] for s in steps), default=None),
        "own_max_after_nu": max((s["own_words"] for s in after_nu), default=None),
        "own_median_clarify": _median([s["own_words"] for s in clarify]),
        "median_sentence_words_max": _median([s["max_sentence_words"] for s in steps]),
        "max_sentence_words": max((s["max_sentence_words"] for s in steps), default=None),
        "s4_longer": s4,
        "question_share": _share(q),
        "linked_share": _share([(s.get("judge") or {}).get("linked") for s in steps]) if judged else None,
        "content_share_normal": _share([(s.get("judge") or {}).get("content") for s in normal]) if judged else None,
        "normal_question_types": types,
        "check_share": types.get("check", 0) / len(nq) if nq else None,
        "personal_share": types.get("personal", 0) / len(nq) if nq else None,
        "alternation_share": _share(alt),
        "judge_errors": sum(1 for s in steps if (s.get("judge") or {}).get("error")),
        "corrections_ok": sum(c["ok"] for c in corr), "corrections_total": len(corr),
        "checks_ok": sum(c["ok"] for c in checks), "checks_total": len(checks),
        "truncated": sum(1 for s in steps if s["incomplete"]),
        "max_output_tokens_seen": max(tokens) if tokens else None,
        "false_correction_steps": sum(len(re.findall(r"\d+", c["note"])) for sc in scenarios for c in sc["checks"]
                                      if c["check"] == "нет ложных поправок" and not c["ok"]),
        "s3_russian_ok": [all(c["ok"] for c in sc["checks"] if "русск" in c["check"]) for sc in scenarios if sc["id"] == "S3"],
        "s5_topic_ok": [all(c["ok"] for c in sc["checks"] if c["check"].startswith("шаг 1: вопрос с выбором"))
                        for sc in scenarios if sc["id"] == "S5"],
        "s5_no_search_ok": [all(c["ok"] for c in sc["checks"] if c["check"] == "нет поиска ни на одном шаге")
                            for sc in scenarios if sc["id"] == "S5"],
        "corrector_outcomes": _count([s["corrector"]["outcome"] for s in steps if s.get("corrector")]),
        "correction_sources": _count([s["correction_source"] for s in steps if s.get("correction_source")]),
        "ended_at_question": sum(1 for s in steps if s.get("ended_at_question")),
        "searches": sum(1 for s in steps if s["searched"]),
        "errors": sum(1 for sc in scenarios for s in sc["steps"] if s["error"]),
        "median_first_group_plain": _median(tfg_plain), "median_first_group_search": _median(tfg_search),
        "max_first_group": max(tfg) if tfg else None,
        "by_level": by_level,
        "s5_no_new_choice": s5_choice,
        "markup_model": sum(s.get("markup_model", 0) for s in steps),
        "markup_spoken": sum(s.get("markup_spoken", 0) for s in steps),
        "max_output_tokens_search": max((s["output_tokens"] for s in steps
                                         if s["searched"] and s.get("output_tokens") is not None), default=None),
    }
    g = summary
    goals = {}
    for lv, b in by_level.items():
        lo, hi = LEVEL_TARGETS[lv][2:]
        goals[f"own_median_L{lv}"] = b["own_median_normal"] is not None and lo <= b["own_median_normal"] <= hi
    summary["goals"] = {
        **goals,
        "after_nu": g["own_max_after_nu"] is not None and g["own_max_after_nu"] <= GOAL_AFTER_NU_MAX,
        "sentence": g["max_sentence_words"] is not None and g["max_sentence_words"] <= GOAL_SENTENCE_MAX,
        "question_share": g["question_share"] is not None and g["question_share"] >= GOAL_QUESTION_SHARE,
        "linked": g["linked_share"] is not None and g["linked_share"] >= GOAL_LINKED_SHARE,
        "content": g["content_share_normal"] is not None and g["content_share_normal"] >= GOAL_CONTENT_SHARE,
        "types": (g["check_share"] is not None and g["check_share"] >= GOAL_TYPE_SHARE
                  and g["personal_share"] >= GOAL_TYPE_SHARE),
        "corrections": g["corrections_total"] > 0 and g["corrections_ok"] == g["corrections_total"],
        "false_corrections": g["false_correction_steps"] == 0,
        "s3_russian_step4": bool(g["s3_russian_ok"]) and all(g["s3_russian_ok"]),
        "s5_step1": bool(g["s5_topic_ok"]) and all(g["s5_topic_ok"]),
        "s5_no_search": bool(g["s5_no_search_ok"]) and all(g["s5_no_search_ok"]),
        "s5_no_new_choice": bool(s5_choice) and all(s5_choice),
        "markup": g["markup_spoken"] == 0,
        "truncated": g["truncated"] == 0,
    }
    # S02i (бриф п. 5): цели этого прогона.
    s3_by = {}
    s6_by = {}
    for sc in scenarios:
        lv = sc.get("level") or 1
        if sc["id"] == "S3":
            s3_by.setdefault(lv, []).append(all(c["ok"] for c in sc["checks"] if "русск" in c["check"]))
        if sc["id"] == "S6":
            s6_by.setdefault(lv, []).append(all(c["ok"] for c in sc["checks"] if c["check"].startswith("без русского")))
    nums = [s.get("numbers", 0) for s in steps]
    tt = [s.get("titles") or {"seen": 0, "quoted": 0, "italic": 0} for s in steps]
    tm = [s.get("titles_model") or {"seen": 0, "quoted": 0, "italic": 0} for s in steps]
    l5 = by_level.get(5, {}).get("own_median_normal")
    summary.update({
        "s3_russian_by_level": s3_by, "s6_no_russian_by_level": s6_by,
        "max_numbers": max(nums, default=None), "numbers_over": sum(1 for x in nums if x > GOAL_NUMBERS_MAX),
        "numbers_median": _median(nums),
        "titles_seen": sum(x["seen"] for x in tt), "titles_quoted": sum(x["quoted"] for x in tt),
        "titles_italic_model": sum(x["italic"] for x in tm),
        "own_after_nu_by_level": {lv: b["own_max_after_nu"] for lv, b in by_level.items()},
    })
    summary["goals_02i"] = {
        "after_nu": g["own_max_after_nu"] is not None and g["own_max_after_nu"] <= GOAL_AFTER_NU_MAX,
        "s3_russian_step4": bool(s3_by) and all(all(v) and len(v) > 0 for v in s3_by.values()),
        "s6_no_russian_before_3": bool(s6_by) and all(all(v) for v in s6_by.values()),
        "numbers": bool(nums) and summary["numbers_over"] == 0,
        "titles_quoted": summary["titles_quoted"] == summary["titles_seen"] and summary["titles_italic_model"] == 0,
        "l5_median": l5 is not None and l5 >= GOAL_L5_MEDIAN_MIN,
        "truncated": g["truncated"] == 0,
    }
    summary["goals_met"] = all(summary["goals_02i"].values())
    return summary


def _count(xs: list) -> dict:
    out: dict = {}
    for x in xs:
        out[x] = out.get(x, 0) + 1
    return out


def estimate(repeats: int) -> dict:
    """Оценка сверху, $: реплика ≈ 3500 токенов входа (промпт + история) и 100 выхода; с поиском — +8000
    входа и $0.01; поиск — в половине реплик S1 и S5 (с запасом); поправщик ≈ 400 входа и 10 выхода; судья
    ≈ 600 входа и 25 выхода; синтез русских фраз Realtime."""
    runs = plan_runs()
    n = sum(len(sc["turns"]) for sc, _ in runs) * repeats
    plain = 3500 * probe.PRICE_LUNA["in"] + 100 * probe.PRICE_LUNA["out"]
    side = 1000 * probe.PRICE_LUNA["in"] + 35 * probe.PRICE_LUNA["out"]
    search = 8000 * probe.PRICE_LUNA["in"] + probe.PRICE_WEB_SEARCH
    n_search = (3 * sum(1 for sc, _ in runs if sc["id"] == "S1") + 2 * sum(1 for sc, _ in runs if sc["id"] == "S5")) * repeats
    rt = len(SYNTH_PHRASES) * RUSSIAN_REPEATS * (3 * 20 * probe.PRICE["rt_audio_out"] + 250 * probe.PRICE["rt_text_in"])
    total = n * (plain + side) + n_search * search + rt
    return {"requests": n, "total": total}


def plan_runs() -> list[tuple[dict, int]]:
    """Сценарии прогона с уровнем (S02h): [LEVEL_RUNS] — (id или None = все, уровень)."""
    out = []
    for sid, lv in LEVEL_RUNS:
        out += [(sc, lv) for sc in SCENARIOS if sid is None or sc["id"] == sid]
    return out


def prompt_hash(prompt_template: str) -> str:
    return hashlib.sha256(prompt_template.encode("utf-8")).hexdigest()[:8]


def load_runs(path: Path) -> list[dict]:
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8")).get("runs", [])
        except (json.JSONDecodeError, AttributeError):
            return []
    return []


def _paths(ctx) -> tuple[Path, Path]:
    docs_json = Path(ctx.args.docs).with_suffix(".json") if ctx.args.docs else DOCS_JSON
    docs_md = Path(ctx.args.docs) if ctx.args.docs else DOCS_MD
    return docs_json, docs_md


def _save(runs: list[dict], docs_json: Path, docs_md: Path) -> None:
    for i, r in enumerate(runs, 1):
        r["n"] = i
    docs_json.parent.mkdir(parents=True, exist_ok=True)
    docs_json.write_text(redact(json.dumps({"runs": runs}, ensure_ascii=False, indent=1)), encoding="utf-8")
    docs_md.write_text(redact(render(runs)), encoding="utf-8")


def _log_summary(run: dict) -> None:
    s = run["summary"]
    failed = [k for k, v in (s.get("goals_02i") or s["goals"]).items() if not v]
    lv = ", ".join(f"L{k} {b['own_median_normal']}" for k, b in (s.get("by_level") or {}).items())
    log(f"\nПрогон {run['n']}: own_words обычных — медиана по уровням {lv}, максимум {s['own_max']}; "
        f"после «не понял» до {s['own_max_after_nu']}; вопрос {_pct(s['question_share'])}, связан "
        f"{_pct(s['linked_share'])}, содержание {_pct(s['content_share_normal'])}; проверка {_pct(s['check_share'])}, "
        f"личный {_pct(s['personal_share'])}; чисел макс. {s.get('max_numbers')}, названий в кавычках "
        f"{s.get('titles_quoted')} из {s.get('titles_seen')}; цели {'достигнуты' if s['goals_met'] else 'НЕ достигнуты: ' + ', '.join(failed)}."
        f" Потрачено ≈ ${run['spent']:.3f}.")


def rejudge(ctx) -> bool:
    """Заново разметить последний прогон (новый промпт судьи), без запросов учителю."""
    docs_json, docs_md = _paths(ctx)
    runs = load_runs(docs_json)
    if not runs:
        print("Нет прогонов для разметки.")
        return False
    run = runs[-1]
    budget = Budget(ctx.args.budget)
    jp = judge_prompt()
    judge_steps(ctx.api, jp, run["scenarios"], budget)
    run["judge_prompt"], run["judge_prompt_hash"] = jp, prompt_hash(jp)
    run["spent"] += budget.spent
    run["summary"] = summarize(run["scenarios"])
    _save(runs, docs_json, docs_md)
    _log_summary(run)
    return True


def probe_tutor_dialog(ctx, ask=input) -> bool:
    if getattr(ctx.args, "rejudge", False):
        return rejudge(ctx)
    repeats = max(1, ctx.args.repeats)
    est = estimate(repeats)
    runs_plan = plan_runs()
    print(f"Проба tutor-dialog: {len(runs_plan)} сценариев с уровнями × {repeats}, ≈ {est['requests']} реплик учителя "
          f"(с поправщиком и судьёй), плюс {len(SYNTH_PHRASES) * RUSSIAN_REPEATS} фраз Realtime с названием в кавычках.")
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
    jp = judge_prompt()
    budget = Budget(ctx.args.budget)
    started = datetime.now().isoformat(timespec="seconds")
    log("Название в кавычках в синтезе (Realtime, голос приложения)")
    russian = russian_check(ctx, budget)
    scenarios = []
    for rep in range(1, repeats + 1):
        for sc, lv in runs_plan:
            if budget.over:
                log(f"!!! бюджет ${budget.limit:.2f} исчерпан — остальные сценарии пропущены")
                break
            log(f"{sc['id']} уровень {lv} #{rep}: {sc['title']}")
            res = run_scenario(ctx.api, prompt, sc, budget, corr_prompt, level=lv)
            res["rep"] = rep
            scenarios.append(res)
    log("Судья: тип вопроса, связь со сказанным, содержание")
    judge_steps(ctx.api, jp, scenarios, budget)
    run = {
        "started": started, "finished": datetime.now().isoformat(timespec="seconds"),
        "label": ctx.args.label or "", "model": MODEL, "max_output_tokens": MAX_OUTPUT_TOKENS,
        "date_in_prompt": date, "prompt_hash": prompt_hash(template), "prompt": template,
        "corrector_prompt_hash": prompt_hash(corr_prompt), "judge_prompt_hash": prompt_hash(jp), "judge_prompt": jp,
        "repeats": repeats, "spent": budget.spent, "budget": budget.limit, "budget_exhausted": budget.over,
        "russian_synthesis": russian, "scenarios": scenarios, "summary": summarize(scenarios),
    }
    docs_json, docs_md = _paths(ctx)
    runs = load_runs(docs_json) + [run]
    _save(runs, docs_json, docs_md)
    _log_summary(run)
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


_TYPE_RU = {"check": "проверка", "personal": "личный", "clarify": "уточнение", "other": "другой", "none": "нет вопроса"}
_KIND_RU = {"normal": "обычная", "after_nu": "после «не понял»", "clarify": "уточнение"}


def _g(s: dict, key: str) -> str:
    """Отметка цели: сначала цели S02i, затем прежние (справочно)."""
    g = s.get("goals_02i") or {}
    if key in g:
        return "✓" if g[key] else "✗"
    return ("✓" if s["goals"][key] else "✗ (справочно)") if key in s.get("goals", {}) else ""


def render(runs: list[dict]) -> str:
    L = ["# S02i — мелкая доводка учителя: диалоги по сценариям", "",
         "Прогоны S02e–S02h — в `docs/research/02e-dialogs.md` … `02h-dialogs.md`. Бриф — `docs/stages/02i-brief.md`, "
         "п. 5: при «не понял» в запросе только строка «не понял» с указанием, без цели длины (D58); названия книг в "
         "кавычках (D60); не больше двух чисел в реплике (D61).", "",
         "Проба `python tools/probe/probe.py tutor-dialog`. Промпт — файл приложения "
         "`android_app/app/src/main/resources/tutor_prompt.txt` (D46), запрос — как в "
         f"`ResponsesTutor`: `{MODEL}`, `reasoning.effort: {EFFORT}`, поток, `web_search` ({SEARCH_CONTEXT}), "
         "`max_output_tokens`, `store: false`. Как в приложении: поправщик одновременно с учителем (его поправка "
         "звучит первой), реплика кончается первым вопросом (D48); служебная строка — «не понял» при счёте ≥ 1, иначе "
         "цель длины уровня (D58). Реплики ученика заданы заранее; каждый сценарий — отдельный разговор на своём "
         "уровне: " + ", ".join(f"{sid} на {lv}" for sid, lv in LEVEL_RUNS) + ".", "",
         "Цель длины по уровню (свои слова, без поправки): "
         + "; ".join(f"{k} — {v[0]}–{v[1]} фраз, {v[2]}–{v[3]} слов" for k, v in LEVEL_TARGETS.items()) + ".", "",
         "Меры:",
         "- **own_words** — свои слова учителя, без поправки; **после «не понял»** — реплики при счёте ≥ 1 "
         "(S3 шаги 2–4, S6 шаги 2–4);",
         "- **чисел** — в своём тексте учителя: группа цифр («3:30», «1,000») — одно число, число словами "
         "(«twenty-five», «one hundred and five») — тоже; «one» само по себе не считается;",
         "- **названия** — книги Jack London в реплике: в двойных кавычках или нет; курсив — в тексте модели "
         "до чистки;",
         f"- **судья** — `{JUDGE_MODEL}` (`tools/probe/judge_prompt.txt`): тип вопроса, связь, содержание (справочно).",
         "",
         "## Сводка по прогонам", "",
         "| Прогон | Метка | Промпт | Реплик | после «не понял» макс. (L1 / L5) | RU S3 на шаге 4 (L1 / L5) | "
         "S6 без RU до счёта 3 (L1 / L5) | чисел макс. (реплик > 2) | названий в кавычках | курсив модели | "
         "own_words обычных L5, медиана | Вопрос в конце | Поиск | Обрезано | $ | Цели |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in runs:
        s = r["summary"]
        bl = {int(k): v for k, v in (s.get("by_level") or {}).items()}
        nu = {int(k): v for k, v in (s.get("own_after_nu_by_level") or {}).items()}
        s3 = {int(k): v for k, v in (s.get("s3_russian_by_level") or {}).items()}
        s6 = {int(k): v for k, v in (s.get("s6_no_russian_by_level") or {}).items()}
        L.append(f"| {r['n']} | {_cell(r.get('label'))} | `{r['prompt_hash']}` | {s['replies']} | "
                 f"{s.get('own_max_after_nu')} ({nu.get(1, '—')} / {nu.get(5, '—')}) | "
                 f"{_okn(s3.get(1))} / {_okn(s3.get(5))} | {_okn(s6.get(1))} / {_okn(s6.get(5))} | "
                 f"{s.get('max_numbers')} ({s.get('numbers_over')}) | {s.get('titles_quoted')} из {s.get('titles_seen')} | "
                 f"{s.get('titles_italic_model')} | {(bl.get(5) or {}).get('own_median_normal', '—')} | "
                 f"{_pct(s['question_share'])} | {s['searches']} | {s['truncated']} | {r['spent']:.3f} | "
                 f"{'**да**' if s['goals_met'] else 'нет'} |")
    L.append("")
    for r in reversed(runs):
        L += render_run(r)
    return "\n".join(L) + "\n"


def render_run(r: dict) -> list[str]:
    s = r["summary"]
    types = s.get("normal_question_types") or {}
    bl = {int(k): v for k, v in (s.get("by_level") or {}).items()}
    L = [f"## Прогон {r['n']} — {r['started']}" + (f" ({_cell(r['label'])})" if r.get("label") else ""), "",
         f"Промпт `{r['prompt_hash']}`, судья `{r.get('judge_prompt_hash', '—')}`, дата в промпте: {r['date_in_prompt']}; "
         f"`max_output_tokens` {r['max_output_tokens']}; повторов {r['repeats']}; потрачено ≈ ${r['spent']:.3f} "
         f"(бюджет ${r['budget']:.2f}" + (", **исчерпан**" if r["budget_exhausted"] else "") + ").", "",
         "### Цели S02i", "", "| Мера | Значение | Цель |", "|---|---|---|",
         *[f"| после «не понял», own_words максимум: уровень {lv} | {v} | ≤ {GOAL_AFTER_NU_MAX} |"
           for lv, v in sorted((int(k), v) for k, v in (s.get("own_after_nu_by_level") or {}).items())],
         f"| после «не понял», максимум по всем | {s['own_max_after_nu']} | ≤ {GOAL_AFTER_NU_MAX} {_g(s, 'after_nu')} |",
         *[f"| S3 уровень {lv}: русское слово ровно на шаге 4 | {_okn(v)} | все {_g(s, 's3_russian_step4')} |"
           for lv, v in sorted((int(k), v) for k, v in (s.get("s3_russian_by_level") or {}).items())],
         *[f"| S6 уровень {lv}: русского до счёта 3 нет | {_okn(v)} | все {_g(s, 's6_no_russian_before_3')} |"
           for lv, v in sorted((int(k), v) for k, v in (s.get("s6_no_russian_by_level") or {}).items())],
         f"| чисел в реплике: максимум (медиана); реплик больше {GOAL_NUMBERS_MAX} | {s.get('max_numbers')} "
         f"({s.get('numbers_median')}); {s.get('numbers_over')} | ≤ {GOAL_NUMBERS_MAX}, все {_g(s, 'numbers')} |",
         f"| названия книг: в кавычках / упомянуто; курсив в тексте модели | {s.get('titles_quoted')} / "
         f"{s.get('titles_seen')}; {s.get('titles_italic_model')} | все в кавычках, курсива 0 {_g(s, 'titles_quoted')} |",
         f"| уровень 5: own_words обычных, медиана | {(bl.get(5) or {}).get('own_median_normal', '—')} | "
         f"≥ {GOAL_L5_MEDIAN_MIN} {_g(s, 'l5_median')} |",
         f"| обрезано пределом длины | {s['truncated']} | 0 {_g(s, 'truncated')} |", "",
         "### Прочие меры (справочно)", "",
         "| Мера | Значение | Цель |", "|---|---|---|",
         f"| реплик учителя: обычных / после «не понял» / уточнений | {s['replies']}: {s['normal']} / {s['after_nu']} / "
         f"{s['clarify']} | |",
         *[f"| уровень {lv}: own_words обычных — медиана (мин.–макс.), фраз — медиана; реплик | "
           f"{b['own_median_normal']} ({b['own_min_normal']}–{b['own_max_normal']}), {b['sentences_median_normal']}; "
           f"{b['normal']} | {LEVEL_TARGETS[lv][2]}–{LEVEL_TARGETS[lv][3]} {_g(s, f'own_median_L{lv}')} |"
           for lv, b in sorted(bl.items())],
         *[f"| уровень {lv}: самая длинная фраза; выходных токенов макс. (с поиском) | {b['max_sentence_words']}; "
           f"{b['max_output_tokens']} ({b['max_output_tokens_search'] or '—'}) | |" for lv, b in sorted(bl.items())],
         f"| own_words: максимум | {s['own_max']} | |",
         f"| own_words после «не понял»: максимум | {s['own_max_after_nu']} | ≤ {GOAL_AFTER_NU_MAX} {_g(s, 'after_nu')} |",
         f"| own_words уточнений: медиана | {s['own_median_clarify']} | |",
         f"| самая длинная фраза: медиана / максимум | {s['median_sentence_words_max']} / {s['max_sentence_words']} | "
         f"— / ≤ {GOAL_SENTENCE_MAX} {_g(s, 'sentence')} |",
         f"| S4 (уровень 1): шаг 4 длиннее шага 1 | {_okn(s['s4_longer'])} | справочно |",
         f"| вопрос в конце | {_pct(s['question_share'])} | ≥ {round(GOAL_QUESTION_SHARE * 100)} % {_g(s, 'question_share')} |",
         f"| вопрос связан со сказанным (судья) | {_pct(s['linked_share'])} | ≥ {round(GOAL_LINKED_SHARE * 100)} % {_g(s, 'linked')} |",
         f"| содержание в обычных репликах (судья) | {_pct(s['content_share_normal'])} | ≥ {round(GOAL_CONTENT_SHARE * 100)} % "
         f"{_g(s, 'content')} |",
         f"| вопросы обычных реплик: проверка / личный | {_pct(s['check_share'])} / {_pct(s['personal_share'])} | "
         f"каждого ≥ {round(GOAL_TYPE_SHARE * 100)} % {_g(s, 'types')} |",
         f"| типы вопросов обычных реплик | {_cell(', '.join(f'{_TYPE_RU.get(k, k)} {v}' for k, v in types.items()))} | |",
         f"| чередование: соседние обычные вопросы разного типа | {_pct(s.get('alternation_share'))} | справочно |",
         f"| поправки в сценариях 1–2 | {s['corrections_ok']} из {s['corrections_total']} | все {_g(s, 'corrections')} |",
         f"| ложных поправок (реплик) | {s['false_correction_steps']} | 0 {_g(s, 'false_corrections')} |",
         f"| S3: русское слово ровно на шаге 4 | {_okn(s['s3_russian_ok'])} | все {_g(s, 's3_russian_step4')} |",
         f"| S5 шаг 1: вопрос с выбором, без фактов, дат, имён и поиска | {_okn(s['s5_topic_ok'])} | все {_g(s, 's5_step1')} |",
         f"| S5: после выбора ученика (шаг 2) нового выбора нет (судья) | {_okn(s.get('s5_no_new_choice'))} | все "
         f"{_g(s, 's5_no_new_choice')} |",
         f"| S5: нет поиска ни на одном шаге | {_okn(s['s5_no_search_ok'])} | все {_g(s, 's5_no_search')} |",
         f"| разметка курсива (`*`, `_`): написала модель / в звучащем тексте | {s.get('markup_model')} / "
         f"{s.get('markup_spoken')} | — / 0 {_g(s, 'markup')} |",
         f"| обрезано пределом длины | {s['truncated']} | 0 {_g(s, 'truncated')} |",
         f"| выходных токенов: максимум | {s.get('max_output_tokens_seen')} | < {r['max_output_tokens']} |",
         f"| поправщик: итоги | {_cell(', '.join(f'{k} {v}' for k, v in (s.get('corrector_outcomes') or {}).items()))} | |",
         f"| оборвано на первом вопросе (приложение) | {s['ended_at_question']} | |",
         f"| реплик с поиском | {s['searches']} | |",
         f"| первая группа, медиана: без поиска / с поиском, с | {_s(s['median_first_group_plain'])} / "
         f"{_s(s['median_first_group_search'])} (худшее {_s(s['max_first_group'])}) | |",
         f"| ошибок запроса / разметки судьи | {s['errors']} / {s['judge_errors']} | 0 |",
         f"| проверки сценариев | {s['checks_ok']} из {s['checks_total']} | |", ""]
    L += ["### Фразы в синтезе (Realtime, голос приложения)", "",
          "| Фраза | Расшифровка синтеза | Сверка |", "|---|---|---|"]
    for x in r["russian_synthesis"]:
        L.append(f"| {_cell(x['text'])} | {_cell(x.get('transcript') or x.get('error'))} | {x['verdict']} |")
    L.append("")
    for sc in r["scenarios"]:
        meta = next(m for m in SCENARIOS if m["id"] == sc["id"])
        lvh = f" — уровень {sc['level']}" if sc.get("level") else ""
        L += [f"### {sc['id']}{lvh} #{sc['rep']} — {meta['title']}", "", f"Ожидание: {meta['expect']}.", "",
              "| # | Ученик | Учитель (что прозвучит) | own_words | Фраза | Чисел | Вид | Вопрос (судья) | Связан | "
              "Содержание | Поиск | RU | Поправщик | Первая группа, с |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for st in sc["steps"]:
            teacher = _cell(st["teacher"]) if not st["error"] else f"**ошибка:** {_cell(st['error'])}"
            if st["incomplete"]:
                teacher += f" *(обрезан: {st['incomplete']})*"
            if st.get("ended_at_question"):
                teacher += f" *(оборван на первом вопросе; модель написала: «{_cell(st['model_text'])}»)*"
            if st.get("teacher_own_correction_dropped"):
                teacher += f" *(своя поправка учителя не прозвучит: «{_cell(st['teacher_own_correction_dropped'])}»)*"
            c = st.get("corrector")
            cinfo = "—" if not c else c["outcome"]
            j = st.get("judge") or {}
            jt = _TYPE_RU.get(j.get("type"), "?") if j.get("type") else ("ошибка" if j else "—")
            L.append(f"| {st['step']} | {_cell(st['student'])} | {teacher} | {st.get('own_words', '—')} | "
                     f"{st['max_sentence_words']} | {st.get('numbers', '—')} | {_KIND_RU.get(st.get('kind'), '—')} | {jt} | "
                     f"{'—' if j.get('linked') is None else _yes(j['linked'])} | "
                     f"{'—' if j.get('content') is None else _yes(j['content'])} | {'да' if st['searched'] else '—'} | "
                     f"{'да' if st['russian'] else '—'} | {cinfo} | {_s(st['t_first_group'])} |")
        if sc["checks"]:
            L += ["", "Проверки: " + "; ".join(
                f"{'✓' if c['ok'] else '✗'} {c['check']}" + (f" ({_cell(c['note'])})" if c["note"] and not c["ok"] else "")
                for c in sc["checks"]) + "."]
        L.append("")
    L += ["<details><summary>Промпт этого прогона</summary>", "", "```text", r["prompt"], "```", "", "</details>", ""]
    return L
