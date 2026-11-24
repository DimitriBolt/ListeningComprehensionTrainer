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
    assert m["max_sentence_words"] == 5
    m = td.reply_metrics("It is 15 degrees. Is that warm or cold?")
    assert (m["words"], m["sentences"], m["question"], m["correction"]) == (9, 2, True, None)
    m = td.reply_metrics('"Noon" is "полдень". Okay.')
    assert (m["words"], m["sentences"], m["question"], m["russian"], m["russian_words"]) == (4, 2, False, True, 1)
    assert td.reply_metrics("I think you meant “I went home.” Where?")["correction"] == "I went home."
    assert td.reply_metrics("I don't know.")["words"] == 3
    assert td.drop_unfinished("It is warm. Do you like it? And the wind") == "It is warm. Do you like it?"
    assert td.drop_unfinished("No end here") == "No end here"


def test_checks():
    sc = td.SCENARIOS[0]
    good = [{"step": 1, "correction": "What is the temperature in Tucson?", "searched": False, "question": True,
             "russian": False, "russian_words": 0},
            {"step": 2, "correction": None, "searched": True, "question": True, "russian": False, "russian_words": 0}]
    assert all(c["ok"] for c in td.check_scenario(sc, good))
    bad = [dict(good[0], correction=None, searched=True)]
    assert [c["ok"] for c in td.check_scenario(sc, bad)] == [False, False, True]
    s3 = next(s for s in td.SCENARIOS if s["id"] == "S3")
    steps = [{"step": i, "correction": None, "searched": False, "question": True, "russian": i == 4,
              "russian_words": 2 if i == 4 else 0} for i in range(1, 5)]
    assert [c["ok"] for c in td.check_scenario(s3, steps)] == [False, True, False]


# ---- прогон против подставного сервера ---------------------------------------------------

class FakeTutor:
    """Отвечает по последней реплике ученика; на «Tuscon» — поправка и уточнение; на «noon» — поиск."""

    def __init__(self, cut=False):
        self.requests = []
        self.cut = cut

    def __call__(self, request):
        body = json.loads(request.content)
        self.requests.append(body)
        student = body["input"][-1]["content"]
        if "Tuscon" in student:
            text = 'I think you meant "What is the temperature in Tucson?", right? At night or at noon?'
            ev = wrap("r1", text_events(text))
        elif "noon" in student.lower():
            text = "It is 68 degrees" + " ([w.com](https://w.com/a?utm_source=openai))" + ". Is that warm or cold?"
            ev = wrap("r2", search_events(["weather Tucson"]) + text_events(text))
        elif self.cut and student == "What?":
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
    res = td.run_scenario(ctx.api, "PROMPT", td.SCENARIOS[0], td.Budget(1.0))
    st = res["steps"]
    assert [s["correction"] for s in st][:1] == ["What is the temperature in Tucson?"]
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
