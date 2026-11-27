"""Проба S02f `corrector`: поправка ошибки ученика — правилом в промпте учителя (вариант A) или
отдельным быстрым запросом «поправщик» (вариант B). Бриф `docs/stages/02f-brief.md`, п. 1.

Набор фраз — `tools/probe/corrector_cases.txt` (≈ 25 с типичными ошибками, ≈ 15 правильных; рядом —
правильный вариант и ключ). На каждую фразу **оба запроса уходят одновременно**, как будет в приложении:
- A — учитель: промпт приложения `tutor_prompt.txt`, запрос как в `ResponsesTutor` (поток), одна реплика;
  что прозвучит — по правилу «реплика кончается первым вопросом» (D48); есть ли «I think you meant» и верно ли;
- B — поправщик: `gpt-6-luna`, `reasoning: none`, без инструментов, `store: false`, малый
  `max_output_tokens`, свой промпт `corrector_prompt.txt` (ресурс рядом с промптом учителя, как D46);
  ответ — исправленная фраза или `OK`.
Меры: найдено ошибок, ложных поправок, изменён смысл, длина поправки в словах; у B — время ответа и
насколько он позже первого куска текста учителя (`first_token`) на той же фразе.

Итог — `docs/research/02f-corrector.md` (все прогоны) и сырые данные `docs/research/02f-corrector.json`.

Документация OpenAI (прочитана 2026-11-24): developers.openai.com/api/reference/resources/responses —
`POST /v1/responses` без `stream` возвращает объект ответа: `output` — список элементов, у сообщения
(`type: message`) `content` — части `output_text` с полем `text`; `usage` — `input_tokens`,
`input_tokens_details.cached_tokens`, `output_tokens`; `max_output_tokens` — предел всех выходных токенов
(не меньше 16); `instructions` — системный промпт; `store: false` — ответ не хранится.
"""
from __future__ import annotations

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
import tutor_dialog as td

CASES_FILE = probe.PROBE_DIR / "corrector_cases.txt"
CORRECTOR_PROMPT_FILE = probe.REPO_ROOT / "android_app" / "app" / "src" / "main" / "resources" / "corrector_prompt.txt"
DOCS_MD = probe.REPO_ROOT / "docs" / "research" / "02f-corrector.md"
DOCS_JSON = probe.REPO_ROOT / "docs" / "research" / "02f-corrector.json"

# Запрос поправщика (копия того, что будет в приложении, если проба за B).
MODEL = probe.M_TUTOR_NEW
EFFORT = "none"
MAX_OUTPUT_TOKENS = 250  # ModelConfig.CORRECTOR_MAX_OUTPUT_TOKENS (S05, D80)

# Правило решения брифа (п. 1).
RULE_FOUND = 0.90          # найдено не меньше 90 % ошибок
RULE_FALSE = 1 / 15        # ложных поправок не больше 1 из 15
RULE_LATE_S = 0.5          # ответ готов не позже 0,5 с после первого куска текста учителя …
RULE_LATE_SHARE = 0.90     # … в 90 % случаев


# --------------------------------------------------------------------------------------------------
# Набор фраз
# --------------------------------------------------------------------------------------------------

def load_cases(path: Path = CASES_FILE) -> list[dict]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")]
        if len(parts) != 4:
            raise ValueError(f"строка набора: ожидалось 4 поля через «|»: {line!r}")
        kind, student, expected, key = parts
        out.append({"kind": kind, "student": student, "expected": expected,
                    "key": [[alt.strip() for alt in part.split("/") if alt.strip()]
                            for part in key.split(";") if part.strip()]})
    return out


# --------------------------------------------------------------------------------------------------
# Оценка поправки
# --------------------------------------------------------------------------------------------------

def norm(s: str) -> str:
    """Без регистра и знаков: «doesn't» → «doesn t»."""
    s = s.replace("’", "'").lower()
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]", " ", s)).strip()


def norm_words(s: str) -> list[str]:
    return norm(s).split()


def corrector_says_ok(answer: str | None, student: str) -> bool:
    """Поправки нет: пустой ответ, ровно `OK` (знаки не считаются) или фраза ученика без изменений —
    без учёта регистра и знаков (правило проверки ответа в приложении, бриф п. 2)."""
    if answer is None:
        return True
    a = norm(answer)
    return a in ("", "ok") or a == norm(student)


def clean_answer(text: str) -> str:
    """Ответ поправщика без кавычек вокруг и без «I think you meant» (если модель его добавила)."""
    t = text.strip()
    t = re.sub(r"^\s*I think you meant,?\s*", "", t, flags=re.I)
    t = re.sub(r",?\s*right\s*\?\s*$", "", t, flags=re.I) if t[:1] in "\"“" else t
    return t.strip().strip("\"“”").strip()


def key_found(correction: str, key: list[list[str]]) -> bool:
    n = f" {norm(correction)} "
    return bool(key) and all(any(f" {norm(alt)} " in n for alt in part) for part in key)


def new_words(correction: str, student: str, expected: str) -> list[str]:
    """Слова поправки, которых нет ни у ученика, ни в правильном варианте: признак, что поправлен смысл
    («hot» → «warm»). Служебные слова и формы глагола в правильном варианте есть, поэтому не считаются."""
    known = set(norm_words(student)) | set(norm_words(expected))
    return [w for w in norm_words(correction) if w not in known]


def containment(correction: str, student: str) -> float:
    """Копия Correction.similarity: доля слов реплики ученика, которые остались в поправке (порог «слишком
    непохожая», бриф п. 2). До прогона 2 здесь считалась обратная доля — слов поправки в реплике ученика."""
    sw = norm_words(student)
    if not sw:
        return 1.0
    fix_words = norm_words(correction)
    cw = set(fix_words)
    # S05 (D80): лучшее окно слов ученика длиной в поправку — на длинной реплике поправляется одно предложение.
    n = min(len(sw), max(1, len(fix_words)))
    count = sum(1 for w in sw[:n] if w in cw)
    best = count
    for i in range(n, len(sw)):
        count += (sw[i] in cw) - (sw[i - n] in cw)
        best = max(best, count)
    return best / n


# Копия Correction.kt (приложение): проверка ответа поправщика и фраза, которая звучит.
MIN_SIMILARITY = 0.5
WAIT_MS = 800  # ModelConfig.CORRECTOR_WAIT_MS


def check(student: str, answer: str) -> tuple[str, object]:
    """("fix", текст) | ("none", None) | ("rejected", сходство) — как Correction.check."""
    a = answer.strip()
    if re.match(r"^\s*I think you meant,?\s*", a, re.I):
        a = re.sub(r"^\s*I think you meant,?\s*", "", a, flags=re.I)
        a = re.sub(r",?\s*right\s*\?\s*$", "", a, flags=re.I)
    a = a.strip().strip('"“”').strip()
    n = norm(a)
    if n in ("", "ok") or n == norm(student):
        return "none", None
    sim = containment(a, student)
    return ("rejected", sim) if sim < MIN_SIMILARITY else ("fix", a)


def phrase(fix: str) -> str:
    """Копия Correction.phrase."""
    return f'I think you meant "{fix.strip().rstrip(".").strip()}", right?'


def grade(case: dict, correction: str | None) -> dict:
    """Одна поправка (None — поправки нет) против ожидания."""
    corrected = correction is not None
    out = {"corrected": corrected, "correction": correction,
           "words": len(norm_words(correction)) if corrected else 0}
    if case["kind"] == "ok":
        out["found"] = None
        out["false"] = corrected
    else:
        out["found"] = corrected and key_found(correction, case["key"])
        out["false"] = False
    nw = new_words(correction, case["student"], case["expected"]) if corrected else []
    out["new_words"] = nw
    out["meaning_changed"] = bool(nw)
    out["containment"] = round(containment(correction, case["student"]), 3) if corrected else None
    return out


# --------------------------------------------------------------------------------------------------
# Запросы
# --------------------------------------------------------------------------------------------------

def corrector_prompt() -> str:
    return CORRECTOR_PROMPT_FILE.read_text(encoding="utf-8").strip()


def corrector_body(prompt: str, student: str) -> dict:
    """Запрос поправщика (без истории: одна реплика ученика)."""
    return {"model": MODEL, "instructions": prompt, "input": [{"role": "user", "content": student}],
            "reasoning": {"effort": EFFORT}, "max_output_tokens": MAX_OUTPUT_TOKENS, "store": False}


def output_text(resp: dict) -> str:
    parts = []
    for item in resp.get("output") or []:
        if item.get("type") == "message":
            for c in item.get("content") or []:
                if c.get("type") == "output_text":
                    parts.append(c.get("text") or "")
    return "".join(parts)


def corrector_call(api, body: dict) -> dict:
    """POST /v1/responses без потока. Время — от отправки до полного ответа."""
    t0 = time.perf_counter()
    try:
        r = api.client.post("/responses", json=body)
    except Exception as e:  # сеть, таймаут — тоже результат пробы
        return {"t_start": t0, "seconds": time.perf_counter() - t0, "error": redact(f"{type(e).__name__}: {e}"),
                "text": None, "usage": None}
    dt = time.perf_counter() - t0
    if r.status_code == 401:
        raise ProbeError(probe.auth_error_text(401))
    if r.status_code >= 400:
        return {"t_start": t0, "seconds": dt, "error": redact(f"HTTP {r.status_code}: {r.text[:300]}"),
                "text": None, "usage": None}
    resp = r.json()
    return {"t_start": t0, "seconds": dt, "error": None, "text": output_text(resp), "usage": resp.get("usage"),
            "status": resp.get("status")}


def teacher_call(api, body: dict) -> dict:
    t0 = time.perf_counter()
    r = probe.responses_stream(api, body)
    r["t_start"] = t0
    return r


def run_pair(api, tutor_prompt: str, corr_prompt: str, case: dict) -> dict:
    """Учитель (A) и поправщик (B) на одной фразе — одновременно, как в приложении."""
    teacher_body = td.request_body(tutor_prompt, [{"role": "user", "content": case["student"]}])
    c_body = corrector_body(corr_prompt, case["student"])
    gate = threading.Barrier(2)

    def a():
        gate.wait()
        return teacher_call(api, teacher_body)

    def b():
        gate.wait()
        return corrector_call(api, c_body)

    with ThreadPoolExecutor(max_workers=2) as pool:
        fa, fb = pool.submit(a), pool.submit(b)
        ra, rb = fa.result(), fb.result()

    # --- A: что прозвучит (D48) и поправка в начале
    events, stopped = td.app_stop(ra["events"])
    an = probe.analyze_stream(events)
    full = probe.analyze_stream(ra["events"])
    text = td.clean_for_speech(an["text"]).strip()
    if stopped:
        cut = td.first_question_end(text + " ")
        text = text[:cut] if cut else text
    a_fix = td.reply_metrics(text)["correction"]
    cost_a = sum(probe.resp_cost(u) for u in full["usages"]) + full["n_search_actions"] * probe.PRICE_WEB_SEARCH
    a_first = full["t_first_token"]
    res_a = {"teacher": text, "error": ra["error"] or ("; ".join(full["errors"]) or None),
             "searched": full["n_search_calls"] > 0, "t_first_token": a_first, "cost": cost_a,
             **grade(case, a_fix)}

    # --- B
    raw = rb["text"]
    fix = None if rb["error"] or corrector_says_ok(raw, case["student"]) else clean_answer(raw)
    if fix is not None and corrector_says_ok(fix, case["student"]):
        fix = None
    late = None
    if a_first is not None and not rb["error"]:
        late = (rb["t_start"] + rb["seconds"]) - (ra["t_start"] + a_first)
    res_b = {"raw": raw, "error": rb["error"], "seconds": rb["seconds"], "late_vs_first_token": late,
             "output_tokens": (rb["usage"] or {}).get("output_tokens"), "cost": probe.resp_cost(rb["usage"]),
             **grade(case, fix)}
    return {"case": case, "A": res_a, "B": res_b}


# --------------------------------------------------------------------------------------------------
# Итоги
# --------------------------------------------------------------------------------------------------

def _p90(xs: list[float]) -> float | None:
    if not xs:
        return None
    s = sorted(xs)  # ближайший ранг: ceil(0.9 · n)
    return s[max(0, -(-9 * len(s) // 10) - 1)]


def summarize_variant(rows: list[dict], v: str) -> dict:
    errs = [r[v] for r in rows if r["case"]["kind"] != "ok" and not r[v]["error"]]
    oks = [r[v] for r in rows if r["case"]["kind"] == "ok" and not r[v]["error"]]
    fixes = [r[v] for r in rows if r[v]["corrected"] and not r[v]["error"]]
    out = {
        "errors_total": len(errs), "found": sum(1 for x in errs if x["found"]),
        "ok_total": len(oks), "false": sum(1 for x in oks if x["false"]),
        "meaning_changed": sum(1 for x in fixes if x["meaning_changed"]),
        "corrections": len(fixes),
        "median_words": statistics.median([x["words"] for x in fixes]) if fixes else None,
        "max_words": max((x["words"] for x in fixes), default=None),
        "failed": sum(1 for r in rows if r[v]["error"]),
    }
    out["found_share"] = out["found"] / out["errors_total"] if out["errors_total"] else None
    out["false_share"] = out["false"] / out["ok_total"] if out["ok_total"] else None
    if v == "B":
        secs = [r["B"]["seconds"] for r in rows if not r["B"]["error"]]
        late = [r["B"]["late_vs_first_token"] for r in rows if r["B"]["late_vs_first_token"] is not None]
        out["median_s"] = statistics.median(secs) if secs else None
        out["p90_s"] = _p90(secs)
        out["median_late_s"] = statistics.median(late) if late else None
        out["p90_late_s"] = _p90(late)
        out["in_time_share"] = sum(1 for x in late if x <= RULE_LATE_S) / len(late) if late else None
        good = [x["containment"] for x in fixes if x["found"] and not x["meaning_changed"]]
        bad = [x["containment"] for x in fixes if x["meaning_changed"] or x["false"]]
        out["containment_good_min"] = min(good) if good else None
        out["containment_bad_max"] = max(bad) if bad else None
    else:
        ft = [r["A"]["t_first_token"] for r in rows if r["A"]["t_first_token"] is not None]
        out["median_first_token_s"] = statistics.median(ft) if ft else None
        out["p90_first_token_s"] = _p90(ft)
    return out


def decide(b: dict) -> dict:
    checks = {
        "found": b["found_share"] is not None and b["found_share"] >= RULE_FOUND,
        "false": b["false_share"] is not None and b["false_share"] <= RULE_FALSE + 1e-9,
        "in_time": b["in_time_share"] is not None and b["in_time_share"] >= RULE_LATE_SHARE,
    }
    return {"checks": checks, "build_corrector": all(checks.values())}


# --------------------------------------------------------------------------------------------------
# Прогон
# --------------------------------------------------------------------------------------------------

def estimate(n_cases: int, repeats: int) -> float:
    """Оценка сверху, $: учитель ≈ 6000 токенов входа без кэша и 60 выхода на реплику; поиск — у 3 фраз
    на повтор (+8000 входа и $0.01); поправщик ≈ 400 входа и 40 выхода."""
    n = n_cases * repeats
    teacher = n * (6000 * probe.PRICE_LUNA["in"] + 60 * probe.PRICE_LUNA["out"])
    search = 3 * repeats * (8000 * probe.PRICE_LUNA["in"] + probe.PRICE_WEB_SEARCH)
    corr = n * (400 * probe.PRICE_LUNA["in"] + 40 * probe.PRICE_LUNA["out"])
    return teacher + search + corr


def load_runs(path: Path) -> list[dict]:
    return td.load_runs(path)


def probe_corrector(ctx, ask=input) -> bool:
    cases = load_cases()
    repeats = max(1, ctx.args.repeats)
    est = estimate(len(cases), repeats)
    n_err = sum(1 for c in cases if c["kind"] != "ok")
    print(f"Проба corrector: {len(cases)} фраз ({n_err} с ошибкой, {len(cases) - n_err} правильных) × {repeats}; "
          f"на каждую — учитель и поправщик одновременно ({2 * len(cases) * repeats} запросов).")
    print(f"Оценка (сверху): ≈ ${est:.2f}; прогон остановится, если потрачено ${ctx.args.budget:.2f}.")
    if not ctx.args.yes:
        ans = ask("Запустить? [y/N] ").strip().lower()
        if ans not in ("y", "yes", "д", "да"):
            print("Отменено.")
            return False
    ctx.key
    date = datetime.now().strftime("%A, %B %d, %Y")
    tutor_template = probe.TUTOR_PROMPT_FILE.read_text(encoding="utf-8").strip()
    tutor_prompt = probe.tutor_prompt(date)
    corr = corrector_prompt()
    spent, rows, started = 0.0, [], datetime.now().isoformat(timespec="seconds")
    over = False
    for rep in range(1, repeats + 1):
        for i, case in enumerate(cases, 1):
            if spent >= ctx.args.budget:
                over = True
                break
            r = run_pair(ctx.api, tutor_prompt, corr, case)
            r["rep"] = rep
            rows.append(r)
            spent += r["A"]["cost"] + r["B"]["cost"]
            b, a = r["B"], r["A"]
            late = "" if b["late_vs_first_token"] is None else f" ({b['late_vs_first_token']:+.2f} s)"
            log(f"  #{rep}.{i:02d} [{case['kind']}] B: {_verdict(case, b)} {b['seconds']:.2f} s{late} | A: {_verdict(case, a)}")
        if over:
            log(f"!!! бюджет ${ctx.args.budget:.2f} исчерпан — остальное пропущено")
            break
    sa, sb = summarize_variant(rows, "A"), summarize_variant(rows, "B")
    run = {"started": started, "finished": datetime.now().isoformat(timespec="seconds"), "label": ctx.args.label or "",
           "model": MODEL, "max_output_tokens": MAX_OUTPUT_TOKENS, "repeats": repeats, "spent": spent,
           "budget": ctx.args.budget, "budget_exhausted": over,
           "tutor_prompt_hash": td.prompt_hash(tutor_template), "corrector_prompt_hash": td.prompt_hash(corr),
           "corrector_prompt": corr, "rows": rows, "A": sa, "B": sb, "decision": decide(sb)}
    docs_json = Path(ctx.args.docs).with_suffix(".json") if ctx.args.docs else DOCS_JSON
    docs_md = Path(ctx.args.docs) if ctx.args.docs else DOCS_MD
    runs = load_runs(docs_json) + [run]
    for i, r in enumerate(runs, 1):
        r["n"] = i
    docs_json.parent.mkdir(parents=True, exist_ok=True)
    docs_json.write_text(redact(json.dumps({"runs": runs}, ensure_ascii=False, indent=1)), encoding="utf-8")
    docs_md.write_text(redact(render(runs)), encoding="utf-8")
    d = run["decision"]
    log(f"\nПрогон {run['n']}: B нашёл {sb['found']}/{sb['errors_total']}, ложных {sb['false']}/{sb['ok_total']}, "
        f"вовремя {_pct(sb['in_time_share'])}; A нашёл {sa['found']}/{sa['errors_total']}, ложных "
        f"{sa['false']}/{sa['ok_total']}. Решение: {'строить поправщик' if d['build_corrector'] else 'НЕ строить'}. "
        f"Потрачено ≈ ${spent:.3f}.")
    log(f"Итог: {docs_md}")
    return True


# --------------------------------------------------------------------------------------------------
# Отчёт
# --------------------------------------------------------------------------------------------------

def _verdict(case: dict, g: dict) -> str:
    if g.get("error"):
        return "сбой"
    if case["kind"] == "ok":
        return "ложная" if g["false"] else "OK ✓"
    if not g["corrected"]:
        return "пропуск"
    return ("✓" if g["found"] else "мимо") + (" (смысл)" if g["meaning_changed"] else "")


def _pct(x) -> str:
    return "—" if x is None else f"{round(100 * x)} %"


def _s(x) -> str:
    return "—" if x is None else f"{x:.2f}".replace(".", ",")


def _cell(t) -> str:
    return td._cell(t if isinstance(t, str) else ("" if t is None else str(t)))


def render(runs: list[dict]) -> str:
    L = ["# S02f — поправщик: правило в промпте учителя (A) или отдельный запрос (B)", "",
         "Проба `python tools/probe/probe.py corrector` (бриф `docs/stages/02f-brief.md`, п. 1). Набор — "
         "`tools/probe/corrector_cases.txt`. На каждую фразу учитель (A) и поправщик (B) запрашиваются "
         "**одновременно**, как будет в приложении.", "",
         "- **A** — учитель: промпт приложения `tutor_prompt.txt`, запрос как в `ResponsesTutor`, одна реплика; "
         "поправка — «I think you meant “…”» в начале того, что прозвучит (D48).",
         f"- **B** — поправщик: `{MODEL}`, `reasoning: {EFFORT}`, без инструментов, `store: false`, "
         f"`max_output_tokens: {MAX_OUTPUT_TOKENS}`, промпт `android_app/app/src/main/resources/corrector_prompt.txt`; "
         "ответ — исправленная фраза или `OK`.", "",
         "Как считается:",
         "- **найдена** — поправка есть, и в ней ключ из набора (без регистра и знаков);",
         "- **ложная** — поправка правильной фразы;",
         "- **смысл изменён** — в поправке есть слово, которого нет ни у ученика, ни в правильном варианте "
         "(«hot» → «warm»);",
         "- **опоздание B** — конец ответа поправщика минус первый кусок текста учителя на той же фразе "
         "(`first_token`), с; меньше нуля — поправка готова раньше учителя;",
         "- **сходство** — доля слов реплики ученика, которые остались в поправке (порог «слишком непохожая», `Correction.MIN_SIMILARITY` = 0,5; пересчитано для прогона 1 после того, как мера сменилась).", "",
         f"Правило решения: поправщик строится, если одновременно найдено ≥ {round(RULE_FOUND * 100)} % ошибок, "
         f"ложных ≤ 1 из 15 (по всем повторам — доля ≤ {RULE_FALSE:.3f}) и в ≥ {round(RULE_LATE_SHARE * 100)} % "
         f"случаев опоздание ≤ {RULE_LATE_S} с.", "",
         "## Сводка по прогонам", "",
         "| Прогон | Метка | Промпт B | Найдено A | Найдено B | Ложных A | Ложных B | Смысл A / B | "
         "B: медиана / p90, с | Опоздание B: p90, с; вовремя | Решение |",
         "|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in runs:
        a, b, d = r["A"], r["B"], r["decision"]
        L.append(f"| {r['n']} | {_cell(r.get('label'))} | `{r['corrector_prompt_hash']}` | "
                 f"{a['found']}/{a['errors_total']} | {b['found']}/{b['errors_total']} | "
                 f"{a['false']}/{a['ok_total']} | {b['false']}/{b['ok_total']} | "
                 f"{a['meaning_changed']} / {b['meaning_changed']} | {_s(b['median_s'])} / {_s(b['p90_s'])} | "
                 f"{_s(b['p90_late_s'])}; {_pct(b['in_time_share'])} | "
                 f"{'**B**' if d['build_corrector'] else 'не B'} |")
    L.append("")
    for r in reversed(runs):
        L += render_run(r)
    return "\n".join(L) + "\n"


def render_run(r: dict) -> list[str]:
    a, b, d = r["A"], r["B"], r["decision"]
    ok = lambda x: "✓" if x else "✗"  # noqa: E731
    L = [f"## Прогон {r['n']} — {r['started']}" + (f" ({_cell(r['label'])})" if r.get("label") else ""), "",
         f"Промпт учителя `{r['tutor_prompt_hash']}`, поправщика `{r['corrector_prompt_hash']}`; повторов "
         f"{r['repeats']}; потрачено ≈ ${r['spent']:.3f} (бюджет ${r['budget']:.2f}).", "",
         "| Мера | A — учитель | B — поправщик | Правило |", "|---|---|---|---|",
         f"| найдено ошибок | {a['found']} из {a['errors_total']} ({_pct(a['found_share'])}) | "
         f"{b['found']} из {b['errors_total']} ({_pct(b['found_share'])}) | ≥ {round(RULE_FOUND * 100)} % "
         f"{ok(d['checks']['found'])} |",
         f"| ложных поправок | {a['false']} из {a['ok_total']} | {b['false']} из {b['ok_total']} | "
         f"≤ 1 из 15 {ok(d['checks']['false'])} |",
         f"| смысл изменён (из всех поправок) | {a['meaning_changed']} из {a['corrections']} | "
         f"{b['meaning_changed']} из {b['corrections']} | |",
         f"| длина поправки, слов: медиана / макс. | {a['median_words']} / {a['max_words']} | "
         f"{b['median_words']} / {b['max_words']} | |",
         f"| сбоев запроса | {a['failed']} | {b['failed']} | |",
         f"| время: учитель `first_token` / поправщик ответ, медиана (p90), с | "
         f"{_s(a['median_first_token_s'])} ({_s(a['p90_first_token_s'])}) | {_s(b['median_s'])} ({_s(b['p90_s'])}) | |",
         f"| опоздание B после первого куска текста учителя, медиана / p90, с | | "
         f"{_s(b['median_late_s'])} / {_s(b['p90_late_s'])} | |",
         f"| B готов не позже {RULE_LATE_S} с после учителя | | {_pct(b['in_time_share'])} | "
         f"≥ {round(RULE_LATE_SHARE * 100)} % {ok(d['checks']['in_time'])} |",
         f"| сходство с репликой ученика: худшая верная поправка / лучшая неверная | | "
         f"{_s(b['containment_good_min'])} / {_s(b['containment_bad_max'])} | |", "",
         f"**Решение по правилу брифа: {'поправщик строится (вариант B)' if d['build_corrector'] else 'поправщик не строится'}.**", ""]
    # По фразам: повторы в одну строку.
    by: dict[str, list[dict]] = {}
    for row in r["rows"]:
        by.setdefault(row["case"]["student"], []).append(row)
    L += ["| Вид | Ученик | Ожидание | B — ответы (по повторам) | B | A — поправка учителя | A |",
          "|---|---|---|---|---|---|---|"]
    for student, rows in by.items():
        c = rows[0]["case"]
        b_ans = " · ".join(_cell(x["B"]["raw"] if x["B"]["raw"] is not None else x["B"]["error"]) for x in rows)
        a_ans = " · ".join(_cell(x["A"]["correction"] or "—") for x in rows)
        L.append(f"| {c['kind']} | {_cell(student)} | {_cell(c['expected'] if c['kind'] != 'ok' else 'OK')} | {b_ans} | "
                 f"{' '.join(_verdict(c, x['B']) for x in rows)} | {a_ans} | {' '.join(_verdict(c, x['A']) for x in rows)} |")
    L += ["", "<details><summary>Промпт поправщика этого прогона</summary>", "", "```text",
          r["corrector_prompt"], "```", "", "</details>", ""]
    return L
