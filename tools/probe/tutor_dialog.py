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
from datetime import datetime
from pathlib import Path

import probe
from probe import ProbeError, log, redact
from speech_planner import clean_for_speech, plan

DOCS_MD = probe.REPO_ROOT / "docs" / "research" / "02e-dialogs.md"
DOCS_JSON = probe.REPO_ROOT / "docs" / "research" / "02e-dialogs.json"
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
        "expect": "сначала поправка — весь вопрос правильно, — затем уточняющий вопрос; поиск только после уточнения",
        "turns": ["What is temperature in Tuscon?", "At noon.", "Yes, it is hot."],
        "corrections": {1: ["the temperature", "tucson"]},
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
        "expect": "проще, проще, затем одно слово по-русски",
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
        "expect": "не лекция: одна деталь и вопрос",
        "turns": ["Tell me about the history of Arizona.", "The old times, please.", "Yes."],
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


def drop_unfinished(text: str) -> str:
    """Копия ReplyText.dropUnfinished."""
    last = None
    for last in _SENTENCE_END.finditer(text):
        pass
    return text if last is None else text[:last.end()]


def _norm(s: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s']", " ", s.lower())).strip()


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


def run_scenario(api, prompt: str, sc: dict, budget: Budget) -> dict:
    history: list[dict] = []
    steps = []
    for i, student in enumerate(sc["turns"], 1):
        if budget.over:
            break
        history.append({"role": "user", "content": student})
        r = probe.responses_stream(api, request_body(prompt, history))
        a = probe.analyze_stream(r["events"])
        error = r["error"] or ("; ".join(a["errors"]) if a["errors"] and a["status"] != "incomplete" else None)
        incomplete = None
        for t, e in r["events"]:
            if e.get("type") == "response.incomplete":
                incomplete = ((e.get("response") or {}).get("incomplete_details") or {}).get("reason")
        text = clean_for_speech(a["text"]).strip()
        if incomplete == "max_output_tokens":
            text = drop_unfinished(text)
        cost = sum(probe.resp_cost(u) for u in a["usages"]) + a["n_search_actions"] * probe.PRICE_WEB_SEARCH
        budget.spent += cost
        usage = (a["usages"] or [None])[-1] or {}
        step = {
            "step": i, "student": student, "teacher": text, "raw_chars": len(a["text"]),
            "error": error, "incomplete": incomplete,
            "searched": a["n_search_calls"] > 0, "n_search": a["n_search_calls"],
            "search_queries": [q for s in a["searches"] for q in (s.get("queries") or [])],
            "t_first_group": a["t_first_group"], "t_end": a["t_end"],
            "output_tokens": usage.get("output_tokens"), "cost": cost,
            "groups": len(plan(text)) if text else 0,
            **reply_metrics(text),
        }
        steps.append(step)
        log(f"  {sc['id']}.{i}: {step['words']} сл., вопрос {'да' if step['question'] else 'нет'}"
            f"{', поиск' if step['searched'] else ''}{', RU' if step['russian'] else ''}"
            f"{', ОБРЕЗАН' if incomplete else ''}{'  ОШИБКА: ' + error if error else ''}")
        if error:
            break
        history.append({"role": "assistant", "content": text})
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
    if sc.get("no_corrections"):
        fixes = [s["step"] for s in steps if s["correction"]]
        add("нет поправок (ученик говорит правильно)", not fixes, f"поправки на шагах {fixes}" if fixes else "")
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
    }
    summary["goals_met"] = all(summary["goals"].values())
    return summary


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
            res = run_scenario(ctx.api, prompt, sc, budget)
            res["rep"] = rep
            scenarios.append(res)
    run = {
        "started": started, "finished": datetime.now().isoformat(timespec="seconds"),
        "label": ctx.args.label or "", "model": MODEL, "max_output_tokens": MAX_OUTPUT_TOKENS,
        "date_in_prompt": date, "prompt_hash": prompt_hash(template), "prompt": template,
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


def _pct(x) -> str:
    return "—" if x is None else f"{round(100 * x)} %"


def _s(x) -> str:
    return "—" if x is None else f"{x:.1f}".replace(".", ",")


def _yes(b) -> str:
    return "да" if b else "нет"


def _cell(text: str) -> str:
    return (text or "").replace("|", "\\|").replace("\n", " ")


def render(runs: list[dict]) -> str:
    L = ["# S02e — учитель-собеседник: диалоги по сценариям", "",
         "Проба `python tools/probe/probe.py tutor-dialog` (бриф `docs/stages/02e-brief.md`, п. 4). Промпт — файл "
         "приложения `android_app/app/src/main/resources/tutor_prompt.txt` (D46), запрос — как в "
         f"`ResponsesTutor`: `{MODEL}`, `reasoning.effort: {EFFORT}`, поток, `web_search` ({SEARCH_CONTEXT}), "
         "`max_output_tokens`, `store: false`. Реплики ученика заданы заранее. Каждый сценарий — отдельный "
         "разговор.", "",
         f"Цели: медиана слов в реплике ≤ {GOAL_MEDIAN_WORDS}, максимум ≤ {GOAL_MAX_WORDS}, вопрос в конце "
         f"≥ {round(GOAL_QUESTION_SHARE * 100)} % реплик, каждая заложенная ошибка в сценариях 1–2 поправлена (D47).", "",
         "Слова считаются как в журнале приложения (`ReplyText.metrics`): все слова реплики, вместе с поправкой. "
         "«Макс. в фразе» — самое длинное предложение **без** поправки (предел промпта 6–8 слов). Время до первой "
         "группы — от отправки запроса до момента, когда потоковая нарезка отдала бы первую группу в синтез "
         "(без распознавания и синтеза). Проверки сценариев — автоматические и грубые; окончательно судят по "
         "репликам.", "",
         "## Сводка по прогонам", "",
         "| Прогон | Метка | Промпт | Реплик | Медиана слов | Макс. слов | Вопрос в конце | Макс. в фразе | "
         "Поправки | Проверки | Обрезано | Цели |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in runs:
        s = r["summary"]
        L.append(f"| {r['n']} | {_cell(r.get('label'))} | `{r['prompt_hash']}` | {s['replies']} | {s['median_words']} | "
                 f"{s['max_words']} | {_pct(s['question_share'])} | {s['max_sentence_words']} | "
                 f"{s['corrections_ok']}/{s['corrections_total']} | {s['checks_ok']}/{s['checks_total']} | "
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
         f"| самое длинное предложение без поправки, слов (медиана по репликам / максимум) | "
         f"{s['median_sentence_words_max']} / {s['max_sentence_words']} | 6–8 |",
         f"| проверки сценариев | {s['checks_ok']} из {s['checks_total']} | |",
         f"| обрезано пределом длины | {s['truncated']} | 0 |",
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
              "| # | Ученик | Учитель | Слов | Предл. | Макс. в фразе | Вопрос | Поиск | RU | Первая группа, с |",
              "|---|---|---|---|---|---|---|---|---|---|"]
        for st in sc["steps"]:
            teacher = _cell(st["teacher"]) if not st["error"] else f"**ошибка:** {_cell(st['error'])}"
            if st["incomplete"]:
                teacher += f" *(обрезан: {st['incomplete']})*"
            L.append(f"| {st['step']} | {_cell(st['student'])} | {teacher} | {st['words']} | {st['sentences']} | "
                     f"{st['max_sentence_words']} | {_yes(st['question'])} | {'да' if st['searched'] else '—'} | "
                     f"{'да' if st['russian'] else '—'} | {_s(st['t_first_group'])} |")
        if sc["checks"]:
            L += ["", "Проверки: " + "; ".join(
                f"{'✓' if c['ok'] else '✗'} {c['check']}" + (f" ({_cell(c['note'])})" if c["note"] and not c["ok"] else "")
                for c in sc["checks"]) + "."]
        L.append("")
    L += ["<details><summary>Промпт этого прогона</summary>", "", "```text", r["prompt"], "```", "", "</details>", ""]
    return L
