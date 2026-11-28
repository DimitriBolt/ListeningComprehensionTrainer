"""Кавычки — пара (S05c, D63): сверка нарезки на 90 ответах учителя пробы S02c.

Ответы — `android_app/app/src/test/resources/02c-replies.txt` (те же, что в тестах приложения). Прежняя нарезка (до S05c) —
`fixtures/02c_plans_s05b.json`: её записал этот скрипт прежней копией `plan()` до правки. Для каждого ответа сравнивается:
- нарезка готового текста `plan(clean_for_speech(text))` — группы и паузы;
- конец реплики по D48 (`first_question_end(text + " ")`).

Пишет `fixtures/02c_plans.json` — новую нарезку: её же читает Kotlin-тест `SpeechPlannerCopyTest` (сверка копий) — и
`docs/research/05c-quotes.md`. Ключ и сеть не нужны.

    python tools/probe/quotes_check.py
"""
from __future__ import annotations

import json
from pathlib import Path

import speech_planner as sp
import tutor_dialog as td

ROOT = Path(__file__).resolve().parents[2]
REPLIES = ROOT / "android_app/app/src/test/resources/02c-replies.txt"
BEFORE = Path(__file__).resolve().parent / "fixtures/02c_plans_s05b.json"
AFTER = Path(__file__).resolve().parent / "fixtures/02c_plans.json"
REPORT = ROOT / "docs/research/05c-quotes.md"


def replies() -> list[str]:
    return [t.strip() for t in REPLIES.read_text(encoding="utf-8").split("\n=====\n") if t.strip()]


# Случаи брифа S05c (D63): их нарезку и конец реплики сверяет и Kotlin-тест (те же числа из этого файла).
CASES = [
    'His favorite book is "The Call of the Wild".',
    'Have you read "Who Moved My Cheese?" yet? I liked it.',
    'I read "The Call of the Wild," a book about a big dog. Do you like dogs?',
    'My mom loves "Eat, Pray, Love" very much.',
    'He said, "I will go to the big lake with my son, and we will catch many fish there." Do you like fishing?',
    'She said "I am happy. Are you happy? I am.',
    'Her new song "Hello" is on the radio. Have you heard it?',
    'The sign says “Stop!” and you stop. Why do people stop?',
    'I think you meant "I will go to the lake", right? What will you do there?',
    'His story “The Call of the Wild” is famous. Do you like it?',
]


def now(text: str) -> dict:
    return {"text": text, "d48": td.first_question_end(text + " ", final=True),
            "groups": [[c.text, c.pause_ms] for c in sp.plan(sp.clean_for_speech(text))]}


def has_quotes(text: str) -> bool:
    return '"' in text or "“" in text or "”" in text


def fmt(groups: list) -> str:
    return " · ".join(f"{t} ({p})" for t, p in groups)


def main() -> None:
    texts = replies()
    before = json.loads(BEFORE.read_text(encoding="utf-8"))
    assert [b["text"] for b in before] == texts, "ответы в fixtures/02c_plans_s05b.json не те"
    after = [now(t) for t in texts]
    AFTER.write_text(json.dumps({"replies": after, "cases": [now(c) for c in CASES]}, ensure_ascii=False, indent=1) + "\n",
                     encoding="utf-8")

    quoted = [i for i, t in enumerate(texts) if has_quotes(t)]
    changed = [i for i in range(len(texts)) if before[i]["groups"] != after[i]["groups"] or before[i]["d48"] != after[i]["d48"]]
    unquoted_changed = [i for i in changed if i not in quoted]
    lines = [
        "# Кавычки — пара (S05c, D63): нарезка 90 ответов S02c до и после",
        "",
        "Сгенерировано `python tools/probe/quotes_check.py` (без ключа и сети). Ответы — "
        "`android_app/app/src/test/resources/02c-replies.txt`; до — `tools/probe/fixtures/02c_plans_s05b.json`, после — "
        "`tools/probe/fixtures/02c_plans.json` (его же сверяет Kotlin-тест `SpeechPlannerCopyTest`).",
        "",
        f"- ответов: {len(texts)}; с двойными кавычками: {len(quoted)}",
        f"- нарезка или конец реплики (D48) изменились: **{len(changed)}**, все — с кавычками; без кавычек изменилось: "
        f"**{len(unquoted_changed)}**",
        f"- с кавычками, но без изменений: {len(quoted) - len([i for i in changed if i in quoted])}",
        "",
        "Пауза — в скобках, мс. `·` — граница группы.",
        "",
    ]
    for i in changed:
        b, a = before[i], after[i]
        lines += [f"## Ответ {i + 1}", "", f"> {texts[i]}".replace("\n", "\n> "), ""]
        if b["groups"] != a["groups"]:
            lines += [f"- до: {fmt(b['groups'])}", f"- после: {fmt(a['groups'])}"]
        if b["d48"] != a["d48"]:
            lines += [f"- конец реплики (D48): до — знак {b['d48']}, после — {a['d48']}"]
        lines.append("")
    unchanged = [i for i in quoted if i not in changed]
    if unchanged:
        lines += ["## С кавычками, без изменений", ""]
        lines += [f"- ответ {i + 1}: {fmt(after[i]['groups'])}" for i in unchanged]
        lines.append("")
    REPORT.write_text("\n".join(lines), encoding="utf-8")
    print(f"ответов {len(texts)}, с кавычками {len(quoted)}, изменились {len(changed)} (без кавычек {len(unquoted_changed)})")


if __name__ == "__main__":
    main()
