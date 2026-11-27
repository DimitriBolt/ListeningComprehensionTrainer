"""Проба corrector (S02f): набор фраз, оценка поправки, итоги и правило решения. Без сети."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import corrector as c  # noqa: E402


def test_cases_file():
    cases = c.load_cases()
    errs = [x for x in cases if x["kind"] != "ok"]
    oks = [x for x in cases if x["kind"] == "ok"]
    assert len(errs) >= 25 and len(oks) == 15
    for x in errs:
        assert x["key"], x
        assert c.key_found(x["expected"], x["key"]), x  # правильный вариант сам проходит по ключу
        assert not c.key_found(x["student"], x["key"]) or x["student"] != x["expected"], x
    for x in oks:
        assert x["student"] == x["expected"] and not x["key"]


def test_says_ok():
    assert c.corrector_says_ok("OK", "Green.")
    assert c.corrector_says_ok("OK.", "Green.")
    assert c.corrector_says_ok("green", "Green.")
    assert c.corrector_says_ok(None, "Green.")
    assert not c.corrector_says_ok("I have a dog.", "I have dog.")


def test_clean_answer():
    assert c.clean_answer('"I have a dog."') == "I have a dog."
    assert c.clean_answer('I think you meant "I have a dog.", right?') == "I have a dog."
    assert c.clean_answer("I have a dog.") == "I have a dog."


def test_grade_error_case():
    case = {"kind": "article", "student": "What is temperature in Tucson?",
            "expected": "What is the temperature in Tucson?", "key": [["the temperature"]]}
    g = c.grade(case, "What is the temperature in Tucson?")
    assert g["found"] and not g["false"] and not g["meaning_changed"] and g["words"] == 6
    g = c.grade(case, None)
    assert g["found"] is False and not g["corrected"]
    g = c.grade(case, "What is the weather in Tucson?")
    assert not g["found"] and g["meaning_changed"] and g["new_words"] == ["weather"]


def test_grade_ok_case_meaning():
    case = {"kind": "ok", "student": "Yes, it is hot.", "expected": "Yes, it is hot.", "key": []}
    g = c.grade(case, "Yes, it is warm.")
    assert g["false"] and g["meaning_changed"] and g["found"] is None
    assert not c.grade(case, None)["false"]


def test_key_alternatives_and_parts():
    key = [["went"], ["bought"]]
    assert c.key_found("Yesterday I went to the shop and bought some apples.", key)
    assert not c.key_found("Yesterday I went to the shop.", key)
    assert c.key_found("She does not like coffee.", [["doesn t", "does not"]])
    assert c.key_found("She doesn't like coffee.", [["doesn t", "does not"]])


def _row(kind, b_found=True, b_false=False, late=0.1, a_corr=None, b_err=None):
    case = {"kind": kind, "student": "x", "expected": "x", "key": [["y"]]}
    b = {"error": b_err, "corrected": kind != "ok" or b_false, "found": (b_found if kind != "ok" else None),
         "false": b_false, "meaning_changed": False, "words": 3, "containment": 0.8, "seconds": 0.6,
         "late_vs_first_token": late}
    a = {"error": None, "corrected": a_corr is not None, "found": bool(a_corr) if kind != "ok" else None,
         "false": False, "meaning_changed": False, "words": 3, "t_first_token": 0.7}
    return {"case": case, "A": a, "B": b}


def test_summary_and_decision():
    rows = [_row("article") for _ in range(9)] + [_row("article", b_found=False)]
    rows += [_row("ok", late=0.2) for _ in range(15)]
    s = c.summarize_variant(rows, "B")
    assert (s["found"], s["errors_total"], s["false"], s["ok_total"]) == (9, 10, 0, 15)
    assert s["in_time_share"] == 1.0
    assert c.decide(s)["build_corrector"]
    rows[-1] = _row("ok", b_false=True)
    rows[-2] = _row("ok", b_false=True)
    s = c.summarize_variant(rows, "B")
    assert s["false"] == 2 and not c.decide(s)["checks"]["false"]
    late = [_row("article", late=0.9) for _ in range(5)] + [_row("article") for _ in range(5)]
    s = c.summarize_variant(late + [_row("ok")], "B")
    assert s["in_time_share"] < 0.9 and not c.decide(s)["build_corrector"]


def test_p90():
    assert c._p90([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]) == 9
    assert c._p90([5]) == 5
    assert c._p90([]) is None


def test_corrector_body():
    b = c.corrector_body("P", "I have dog.")
    assert b["model"] == "gpt-6-luna" and b["reasoning"] == {"effort": "none"} and b["store"] is False
    assert "tools" not in b and b["max_output_tokens"] >= 16
    assert b["input"] == [{"role": "user", "content": "I have dog."}]
    assert c.output_text({"output": [{"type": "message", "content": [{"type": "output_text", "text": "OK"}]}]}) == "OK"


def test_render_smoke():
    rows = [dict(_row("article"), rep=1), dict(_row("ok"), rep=1)]
    for r in rows:
        r["B"]["raw"] = "OK"
        r["A"]["correction"] = None
        r["A"]["teacher"] = "Hi?"
    run = {"n": 1, "started": "t", "label": "", "repeats": 1, "spent": 0.01, "budget": 0.5,
           "tutor_prompt_hash": "a", "corrector_prompt_hash": "b", "corrector_prompt": "P", "rows": rows,
           "A": c.summarize_variant(rows, "A"), "B": c.summarize_variant(rows, "B")}
    run["decision"] = c.decide(run["B"])
    md = c.render([run])
    assert "Сводка по прогонам" in md and "Прогон 1" in md


def test_copies_of_the_app():
    import re
    kt = c.td.MODEL_CONFIG_KT.read_text(encoding="utf-8")

    def const(name):
        m = re.search(rf"const val {name} = (.+)", kt)
        v = m.group(1).strip().strip('"')
        return v[:-1] if re.fullmatch(r"[\d_]+L", v) else v
    assert c.MAX_OUTPUT_TOKENS == int(const("CORRECTOR_MAX_OUTPUT_TOKENS"))
    assert c.EFFORT == const("CORRECTOR_REASONING_EFFORT")
    assert const("CORRECTOR_MODEL") == "TUTOR_MODEL" and c.MODEL == const("TUTOR_MODEL")
    assert c.WAIT_MS == int(const("CORRECTOR_WAIT_MS").replace("_", ""))
    corr_kt = (c.td.MODEL_CONFIG_KT.parent.parent / "conversation" / "Correction.kt").read_text(encoding="utf-8")
    assert f"MIN_SIMILARITY = {c.MIN_SIMILARITY}" in corr_kt
    # Те же примеры, что CorrectionTest.kt.
    assert c.check("Green.", "OK.") == ("none", None)
    assert c.check("Yes, it is hot.", "yes it is hot") == ("none", None)
    assert c.check("I have dog.", 'I think you meant "I have a dog", right?') == ("fix", "I have a dog")
    assert c.check("Gas price?", "What is the gas price?") == ("fix", "What is the gas price?")
    kind, sim = c.check("Where you live?", "I live in Tucson.")
    assert kind == "rejected" and abs(sim - 1 / 3) < 1e-9
    assert c.phrase("I have a dog.") == 'I think you meant "I have a dog", right?'


def test_containment_long_utterance_window():
    """S05 (D80): одно исправленное предложение длинной реплики — сходство по лучшему окну; копия Correction.kt."""
    student = ("yesterday I got up early and I make coffee then I go to the park with my dog "
               "it was very nice weather and we walk for one hour after that I come back home and cook breakfast")
    assert c.check(student, "Then I went to the park with my dog.") == ("fix", "Then I went to the park with my dog.")
    assert c.check(student, "That sounds lovely! What did you cook?")[0] == "rejected"
    assert c.containment("I live in Tucson.", "Where you live?") == 1 / 3
