"""Подкоманда tutor-dialog (S02e) против подставного сервера Responses (поток SSE по документации).
Настоящий API не вызывается: что настоящий сервер отвечает так же, эти тесты НЕ подтверждают."""
import json
import re
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import probe  # noqa: E402
import tutor_dialog as td  # noqa: E402

httpx = pytest.importorskip("httpx")

from test_responses import FAKE_KEY, search_events, sse, text_events, wrap  # noqa: E402

MODEL_CONFIG = td.MODEL_CONFIG_KT.read_text(encoding="utf-8")


def kt_const(name: str) -> str:
    m = re.search(rf"const val {name} = (.+)", MODEL_CONFIG)
    assert m, name
    return m.group(1).strip().strip('"').rstrip("f")


# ---- копии приложения ---------------------------------------------------------------------

def test_request_is_the_app_request():
    assert td.MODEL == kt_const("TUTOR_MODEL")
    assert td.EFFORT == kt_const("TUTOR_REASONING_EFFORT")
    assert td.SEARCH_CONTEXT == kt_const("TUTOR_WEB_SEARCH_CONTEXT_SIZE")
    assert td.MAX_OUTPUT_TOKENS == int(kt_const("TUTOR_MAX_OUTPUT_TOKENS"))
    assert td.HISTORY_MESSAGES == int(kt_const("TUTOR_HISTORY_MESSAGES"))
    assert td.SPEECH_VOICE == kt_const("SPEECH_VOICE") and td.SPEECH_SPEED == float(kt_const("SPEECH_SPEED"))
    hist = [{"role": "user", "content": f"q{i}"} for i in range(40)]
    body = td.request_body("PROMPT", hist)
    assert body["instructions"] == "PROMPT" and body["store"] is False
    assert body["max_output_tokens"] == td.MAX_OUTPUT_TOKENS and len(body["input"]) == 30
    assert body["tools"] == [{"type": "web_search", "search_context_size": "low"}]
    assert body["include"] == ["web_search_call.action.sources"] and body["reasoning"] == {"effort": "none"}


def test_metrics_like_reply_text_kt():
    # Те же числа, что в ReplyTextTest.kt.
    m = td.reply_metrics('I think you meant "What is the temperature in Tucson?", right? At night or at noon?')
    assert (m["words"], m["sentences"], m["question"], m["russian"]) == (16, 2, True, False)
    assert m["correction"] == "What is the temperature in Tucson?" and m["words_outside_correction"] == 5
    assert m["max_sentence_words"] == 5 and m["own_words"] == 5
    # own_words — как ReplyText.Metrics.ownWords (те же примеры, что в ReplyTextTest.kt).
    assert td.reply_metrics('I think you meant "I went home" Did you rest?')["own_words"] == 3
    assert td.reply_metrics("I think you meant it. Do you agree?")["own_words"] == 8
    m = td.reply_metrics("It is 15 degrees. Is that warm or cold?")
    assert (m["words"], m["sentences"], m["question"], m["correction"]) == (9, 2, True, None)
    m = td.reply_metrics('"Noon" is "полдень". Okay.')
    assert (m["words"], m["sentences"], m["question"], m["russian"], m["russian_words"]) == (4, 2, False, True, 1)
    assert td.reply_metrics("I think you meant “I went home.” Where?")["correction"] == "I went home."
    assert td.reply_metrics("I don't know.")["words"] == 3
    assert td.drop_unfinished("It is warm. Do you like it? And the wind") == "It is warm. Do you like it?"
    assert td.drop_unfinished("No end here") == "No end here"


def test_first_question_end_like_reply_text_kt():
    # Те же случаи, что в ReplyTextTest.kt.
    def cut(t):
        c = td.first_question_end(t)
        return t[:c] if c is not None else None
    assert cut("Now or tonight? It is 64 degrees.") == "Now or tonight?"
    assert cut('Do you like it?" I do.') == 'Do you like it?"'
    assert cut("Now or tonight?") is None and cut("It is 15 degrees.") is None
    fix = 'I think you meant "What is the temperature in Tucson?", right?'
    assert cut(f"{fix} At night or at noon? It is 68.") == f"{fix} At night or at noon?"
    assert cut(f"{fix} At night") is None
    assert cut('I think you meant "What is the temperature in Tucson? ') is None
    assert cut('I think you meant "What is the temperature in Tucson?", ri') is None
    assert cut("I think you me") is None
    assert cut("I think you meant “I went home.” Where? Then") == "I think you meant “I went home.” Where?"
    assert cut("I think it is hot. Do you? Yes.") == "I think it is hot. Do you?"


def test_app_stop_asked_then_searched():
    ev = [(0.0, e) for e in wrap("r", text_events("Now or tonight?") + search_events(["q"]) + text_events("It is 64."))]
    kept, stopped = td.app_stop(ev)
    assert stopped and not any((e.get("item") or {}).get("type") == "web_search_call" for _, e in kept)
    a = probe.analyze_stream(kept)
    assert a["text"] == "Now or tonight?" and a["n_search_calls"] == 0
    ev = [(0.0, e) for e in wrap("r", text_events("It is 15 degrees. Is that warm? ([a.com](https://a.com))"))]
    assert td.app_stop(ev)[1] is False  # после вопроса только сноска — обычный конец


def test_checks():
    sc = td.SCENARIOS[0]
    good = [{"step": 1, "correction": "What is the temperature in Tucson?", "searched": False, "question": True,
             "russian": False, "russian_words": 0},
            {"step": 2, "correction": None, "searched": True, "question": True, "russian": False, "russian_words": 0}]
    assert all(c["ok"] for c in td.check_scenario(sc, good))
    bad = [dict(good[0], correction=None, searched=True)]
    assert [c["ok"] for c in td.check_scenario(sc, bad)] == [False, False, True, True]
    wrong = [dict(good[0]), dict(good[1], correction="At noon.")]  # поправка правильной фразы — ложная
    assert [c["ok"] for c in td.check_scenario(sc, wrong)] == [True, True, True, False]
    s3 = next(s for s in td.SCENARIOS if s["id"] == "S3")
    steps = [{"step": i, "correction": None, "searched": False, "question": True, "russian": i == 4,
              "russian_words": 2 if i == 4 else 0} for i in range(1, 5)]
    assert [c["ok"] for c in td.check_scenario(s3, steps)] == [False, True, True, False]
    s5 = next(s for s in td.SCENARIOS if s["id"] == "S5")
    step = {"step": 1, "correction": None, "searched": False, "question": True, "russian": False, "russian_words": 0,
            "student": s5["turns"][0]}
    # S02g: одна фраза о теме (без цифр, дат, имён) и вопрос с выбором; нет поиска ни на одном шаге.
    for t in ["Old times or new times?", "Arizona has a long and interesting history. Do you want old times or new times?"]:
        assert [c["ok"] for c in td.check_scenario(s5, [dict(step, teacher=t)])] == [True, True, True], t
    lecture = dict(step, teacher="Native people lived there first. In 1912 Arizona became a state. Old or new?")
    assert [c["ok"] for c in td.check_scenario(s5, [lecture])] == [True, False, True]
    names = dict(step, teacher="The Hohokam people lived there. Do you want old times or new times?")
    assert td.check_scenario(s5, [names])[1]["note"] == "имена: Hohokam"
    searched = [dict(step, teacher="Old times or new times?"), dict(step, step=2, searched=True, teacher="Ok. Yes?")]
    assert [c["ok"] for c in td.check_scenario(s5, searched)] == [True, True, False]


# ---- прогон против подставного сервера ---------------------------------------------------

class FakeTutor:
    """Отвечает по последней реплике ученика; на «Tuscon» — поправка и уточнение; на «noon» — поиск."""

    def __init__(self, cut=False):
        self.requests = []
        self.corrector_requests = []
        self.judge_requests = []
        self.cut = cut

    def __call__(self, request):
        body = json.loads(request.content)
        student = body["input"][-1]["content"]
        if not body.get("stream") and body["instructions"].startswith("You rate"):  # судья (S02g)
            self.judge_requests.append(body)
            kind = "clarify" if " or " in student else "check"
            return httpx.Response(200, json={"status": "completed", "output": [
                {"type": "message", "content": [{"type": "output_text",
                                                 "text": json.dumps({"type": kind, "linked": True, "content": True})}]}],
                "usage": {"input_tokens": 500, "output_tokens": 20}})
        if not body.get("stream"):  # поправщик (S02f): ответ без потока
            self.corrector_requests.append(body)
            fix = "What is the temperature in Tucson?" if "temperature in" in student else "OK"
            return httpx.Response(200, json={"status": "completed", "output": [
                {"type": "message", "content": [{"type": "output_text", "text": fix}]}],
                "usage": {"input_tokens": 300, "output_tokens": 5}})
        self.requests.append(body)
        if "temperature in" in student:
            text = 'I think you meant "What is the temperature in Tucson?", right? At night or at noon?'
            ev = wrap("r1", text_events(text))
        elif "noon" in student.lower():
            text = "It is 68 degrees" + " ([w.com](https://w.com/a?utm_source=openai))" + ". Is that warm or cold?"
            ev = wrap("r2", search_events(["weather Tucson"]) + text_events(text))
        elif self.cut and student.split("\n")[0] == "What?":  # с S02f к реплике добавлен счёт «не понял»
            ev = [{"type": "response.created", "response": {"id": "r3"}}] + text_events("Say it again. And the") + [
                {"type": "response.incomplete", "response": {"status": "incomplete", "usage": {"output_tokens": 200},
                                                             "incomplete_details": {"reason": "max_output_tokens"}}}]
        elif "understand" in student:
            ev = wrap("r4", text_events('"Cook" is "готовить". Do you cook?'))
        else:
            ev = wrap("r5", text_events("Nice. What do you like?"), {"input_tokens": 700, "output_tokens": 12})
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=sse(ev, pause=0))


def make_ctx(tmp_path, fake, extra=()):
    creds = tmp_path / "c.properties"
    creds.write_text(f"APIkey={FAKE_KEY}\n")
    args = probe.parse_args(["tutor-dialog", "--credentials", str(creds), "--out", str(tmp_path / "out"),
                             "--docs", str(tmp_path / "02e.md"), "--timeout", "5", "--yes", *extra])
    ctx = probe.Ctx(args)
    api = probe.Api.__new__(probe.Api)
    api.httpx, api.key, api.timeout = httpx, FAKE_KEY, 5
    api.client = httpx.Client(base_url=probe.API_BASE, transport=httpx.MockTransport(fake),
                              headers={"Authorization": f"Bearer {FAKE_KEY}"})
    ctx._api = api
    return ctx


@pytest.fixture(autouse=True)
def no_realtime(monkeypatch):
    rows = [{"text": t, "transcript": t, "verdict": "verbatim", "error": None, "cost": 0.0001}
            for t in td.RUSSIAN_PHRASES]

    def check(ctx, budget):
        budget.spent += sum(r["cost"] for r in rows)
        return rows
    monkeypatch.setattr(td, "russian_check", check)


def test_scenario_history_like_the_app(tmp_path):
    fake = FakeTutor()
    ctx = make_ctx(tmp_path, fake)
    res = td.run_scenario(ctx.api, "PROMPT", td.SCENARIOS[0], td.Budget(1.0), corr_prompt="CORR")
    st = res["steps"]
    assert [s["correction"] for s in st][:1] == ["What is the temperature in Tucson?"]
    # Поправку дал поправщик, своя поправка учителя не прозвучит; в историю — что прозвучало.
    assert st[0]["correction_source"] == "corrector" and st[0]["corrector"]["outcome"] == "correction"
    assert st[0]["teacher"] == 'I think you meant "What is the temperature in Tucson?", right? At night or at noon?'
    assert st[0]["teacher_own_correction_dropped"]
    assert [b["input"][0]["content"] for b in fake.corrector_requests] == td.SCENARIOS[0]["turns"]
    assert all("tools" not in b and b["store"] is False for b in fake.corrector_requests)
    assert not st[0]["searched"] and st[1]["searched"] and st[1]["search_queries"] == ["weather Tucson"]
    # В историю идёт вычищенный ответ — без сноски, как в ResponsesTutor.
    assert st[1]["teacher"] == "It is 68 degrees. Is that warm or cold?"
    second = fake.requests[2]["input"]
    assert [m["role"] for m in second] == ["user", "assistant", "user", "assistant", "user"]
    assert second[3]["content"] == "It is 68 degrees. Is that warm or cold?"
    assert all(r["max_output_tokens"] == td.MAX_OUTPUT_TOKENS and r["instructions"] == "PROMPT" for r in fake.requests)
    assert all(c["ok"] for c in res["checks"])


def test_cut_reply_drops_unfinished_sentence(tmp_path):
    ctx = make_ctx(tmp_path, FakeTutor(cut=True))
    s3 = next(s for s in td.SCENARIOS if s["id"] == "S3")
    st = td.run_scenario(ctx.api, "P", s3, td.Budget(1.0))["steps"]
    assert st[1]["incomplete"] == "max_output_tokens" and st[1]["teacher"] == "Say it again." and not st[1]["error"]
    assert st[3]["russian"] and st[3]["russian_words"] == 1
    # Счёт «не понял» — как в приложении: служебная строка только в запросе.
    assert [s["not_understood"] for s in st] == [0, 1, 2, 3]


def test_full_run_writes_docs_and_keeps_old_runs(tmp_path):
    fake = FakeTutor()
    ctx = make_ctx(tmp_path, fake, ("--dialog-repeats", "1", "--label", "первый"))
    assert td.probe_tutor_dialog(ctx)
    ctx2 = make_ctx(tmp_path, fake, ("--dialog-repeats", "1", "--label", "второй"))
    assert td.probe_tutor_dialog(ctx2)
    data = json.loads((tmp_path / "02e.json").read_text(encoding="utf-8"))
    assert [r["label"] for r in data["runs"]] == ["первый", "второй"] and [r["n"] for r in data["runs"]] == [1, 2]
    run = data["runs"][-1]
    assert len(run["scenarios"]) == len(td.SCENARIOS)
    assert sum(len(s["steps"]) for s in run["scenarios"]) == sum(len(s["turns"]) for s in td.SCENARIOS)
    s = run["summary"]
    assert s["question_share"] > 0.8 and s["max_words"] <= 30 and s["corrections_total"] == 4
    # S02g: судья разметил каждую реплику; он видит свой текст учителя без поправки.
    n = sum(len(sc["turns"]) for sc in td.SCENARIOS)
    assert len(fake.judge_requests) == 2 * n and s["judge_errors"] == 0
    assert all("I think you meant" not in b["input"][0]["content"] for b in fake.judge_requests)
    assert all(st["kind"] in ("normal", "after_nu", "clarify") for sc in run["scenarios"] for st in sc["steps"])
    assert run["judge_prompt"] == td.judge_prompt() and s["linked_share"] == 1.0
    assert "own_words" in (tmp_path / "02e.md").read_text(encoding="utf-8")
    assert run["prompt"] == probe.TUTOR_PROMPT_FILE.read_text(encoding="utf-8").strip()
    md = (tmp_path / "02e.md").read_text(encoding="utf-8")
    assert "## Сводка по прогонам" in md and "## Прогон 1" in md and "## Прогон 2" in md
    assert "Ожидание:" in md and "Русское слово в синтезе" in md
    assert FAKE_KEY not in md and "utm_source" not in md
    # Промпт приложения с датой — в каждом запросе.
    assert "Today is " in fake.requests[0]["instructions"] and "{DATE}" not in fake.requests[0]["instructions"]


def test_budget_stops_the_run(tmp_path):
    ctx = make_ctx(tmp_path, FakeTutor(), ("--dialog-repeats", "1", "--dialog-budget", "0.00001"))
    assert td.probe_tutor_dialog(ctx)
    run = json.loads((tmp_path / "02e.json").read_text(encoding="utf-8"))["runs"][-1]
    assert run["budget_exhausted"] and len(run["scenarios"]) == 0


def test_cancel_asks_first(tmp_path):
    args = probe.parse_args(["tutor-dialog", "--credentials", str(tmp_path / "none"), "--docs", str(tmp_path / "x.md")])
    assert td.probe_tutor_dialog(probe.Ctx(args), ask=lambda _: "n") is False
    assert not (tmp_path / "x.md").exists()


def test_estimate_within_budget():
    est = td.estimate(2)
    assert est["requests"] == 2 * sum(len(s["turns"]) for s in td.SCENARIOS) and est["total"] < 1.0


# ---- S02f: счёт «не понял» (копия NotUnderstood.kt, те же примеры, что NotUnderstoodTest.kt) -----

def test_not_understood_like_kotlin():
    for t in ["What?", "what", "Pardon?", "Pardon me?", "Sorry?", "Sorry, what?", "Huh?", "Excuse me?",
              "I don't understand.", "I don’t understand", "Sorry, I don't understand.", "I do not understand you.",
              "I didn't understand.", "I don't understand the word noon.", "I don't get it.",
              "I don't know what you mean.", "I don't know what is noon.", "What do you mean?", "What did you say?",
              "Can you repeat, please?", "Could you say it again?", "Repeat, please.", "Please repeat.", "Again, please.",
              "Say it again.", "One more time.", "Slowly, please.", "What does noon mean?", "...", "", "  "]:
        assert td.is_marker(t), t
    for t in ["I like to cook.", "At noon.", "I don't know.", "Yes.", "No.", "What is the temperature in Tucson?",
              "What time is it?", "Sorry, I was late.", "I understand.", "Pasta.", "I know what you mean."]:
        assert not td.is_marker(t), t
    assert td.streak(["I like to cook.", "What?", "I don't understand.", "Sorry, I don't understand."]) == 3
    assert td.streak(["What?", "What?", "Pasta.", "Sorry?"]) == 1
    assert td.note(0) is None
    assert td.note(1) == "(App note: the student did not understand you 1 time in a row.)"
    assert td.note(3) == "(App note: the student did not understand you 3 times in a row.)"
    # Строка в промпте учителя — того же вида.
    assert td.note(2) in probe.TUTOR_PROMPT_FILE.read_text(encoding="utf-8")


def test_request_note_goes_only_to_the_request(tmp_path):
    fake = FakeTutor()
    ctx = make_ctx(tmp_path, fake)
    s3 = next(s for s in td.SCENARIOS if s["id"] == "S3")
    td.run_scenario(ctx.api, "P", s3, td.Budget(1.0))
    last = fake.requests[-1]["input"]
    assert last[-1]["content"] == "Sorry, I don't understand.\n" + td.note(3)
    assert [m["content"] for m in last if m["role"] == "user"][:-1] == s3["turns"][:3]


# ---- S02g: судья, уточнение, цели -------------------------------------------------------

def test_parse_judge():
    assert td.parse_judge('{"type": "check", "linked": true, "content": false}') == \
        {"type": "check", "linked": True, "content": False}
    assert td.parse_judge('```json\n{"type":"Personal","linked":true,"content":true}\n```')["type"] == "personal"
    for bad in [None, "", "check", '{"type": "quiz", "linked": true, "content": true}',
                '{"type": "check", "linked": "yes", "content": true}', "{broken"]:
        assert td.parse_judge(bad) is None, bad


def test_judge_sees_own_text_only():
    b = td.judge_body("J", "I have dog.", 'I think you meant "I have a dog", right? Dogs are friends. Do you walk?')
    assert b["input"][0]["content"] == "Student: I have dog.\nTeacher: Dogs are friends. Do you walk?"
    assert "tools" not in b and b["store"] is False and b["model"] == td.JUDGE_MODEL


def test_clarify_problems():
    st = "Tell me about the history of Arizona."
    assert td.clarify_problems("Arizona has a long history. Do you want old times or new times?", st) == []
    assert td.clarify_problems("Do you want old times or new times?", st) == []
    assert "в вопросе нет выбора" in td.clarify_problems("It is old. What do you want?", st)
    assert "фраз до вопроса: 2" in td.clarify_problems("It is old. It is big. Old or new?", st)
    assert "цифры или даты" in td.clarify_problems("It became a state in 1912. Old or new?", st)
    assert "цифры или даты" in td.clarify_problems("It joined in February. Old or new?", st)
    assert td.clarify_problems("It has a long history.", st) == ["нет вопроса в конце"]


def _step(n, own, kind="normal", jtype="check", **kw):
    base = {"step": n, "error": None, "words": own, "own_words": own, "max_sentence_words": 8, "question": True,
            "searched": False, "incomplete": None, "t_first_group": 0.8, "not_understood": 1 if kind == "after_nu" else 0,
            "kind": kind, "judge": {"type": jtype, "linked": True, "content": True}, "corrector": None,
            "correction_source": None, "output_tokens": 80}
    return {**base, **kw}


def test_summary_goals_s02g():
    s4 = {"id": "S4", "rep": 1, "checks": [], "steps": [_step(1, 28), _step(2, 30, jtype="personal"), _step(3, 33),
                                                     _step(4, 45, jtype="personal")]}
    s3 = {"id": "S3", "rep": 1, "checks": [], "steps": [_step(1, 30), _step(2, 12, "after_nu"), _step(3, 9, "after_nu")]}
    s = td.summarize([s4, s3])
    assert (s["normal"], s["after_nu"], s["own_median_normal"], s["own_max_after_nu"]) == (5, 2, 30, 12)
    assert s["s4_longer"] == [True] and s["check_share"] == 0.6 and s["personal_share"] == 0.4
    assert s["alternation_share"] == 1.0
    for k in ("own_median_normal", "own_max", "after_nu", "sentence", "s4_longer", "question_share", "linked",
              "content", "types", "truncated"):
        assert s["goals"][k], k
    short = td.summarize([{"id": "S4", "rep": 1, "checks": [], "steps": [_step(1, 10), _step(4, 8)]}])
    assert not short["goals"]["own_median_normal"] and not short["goals"]["s4_longer"]
    long_nu = td.summarize([{"id": "S3", "rep": 1, "checks": [], "steps": [_step(2, 25, "after_nu")]}])
    assert not long_nu["goals"]["after_nu"]


def test_s7_and_s5_no_search_in_scenarios():
    s7 = next(s for s in td.SCENARIOS if s["id"] == "S7")
    assert s7["turns"] == ["I have a dog.", "His name is Max.", "He is five.", "He likes to run."]
    assert next(s for s in td.SCENARIOS if s["id"] == "S5")["no_search"]


def test_rejudge_last_run(tmp_path):
    fake = FakeTutor()
    assert td.probe_tutor_dialog(make_ctx(tmp_path, fake, ("--dialog-repeats", "1")))
    n_teacher = len(fake.requests)
    assert td.probe_tutor_dialog(make_ctx(tmp_path, fake, ("--rejudge",)))
    data = json.loads((tmp_path / "02e.json").read_text(encoding="utf-8"))
    assert len(data["runs"]) == 1 and len(fake.requests) == n_teacher  # учителю — ни одного запроса
    assert len(fake.judge_requests) == 2 * sum(len(sc["turns"]) for sc in td.SCENARIOS)
