"""Подкоманда responses (S02c) против подставного сервера (httpx.MockTransport), который отдаёт
поток SSE в формате из документации OpenAI ([RESP-SSE] в probe.py). Настоящий API не вызывается:
что настоящий сервер отвечает так же, эти тесты НЕ подтверждают."""
import json
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import probe  # noqa: E402

httpx = pytest.importorskip("httpx")

FAKE_KEY = "sk-fake-0000000000000000"
CITE = " ([gasbuddy.com](https://www.gasbuddy.com/gasprices/arizona/tucson?utm_source=openai))"


def sse(events, pause=0.002):
    """Поток SSE: «event: …» + «data: {json}» + пустая строка; между событиями — пауза."""
    for i, e in enumerate(events):
        e = {"sequence_number": i, **e}
        yield f"event: {e['type']}\ndata: {json.dumps(e)}\n\n".encode()
        time.sleep(pause)


def text_events(text, rid="resp_1", ann=False, pieces=6):
    step = max(1, len(text) // pieces)
    ev = [{"type": "response.output_item.added", "output_index": 1,
           "item": {"type": "message", "id": "msg_1", "role": "assistant", "content": []}}]
    for i in range(0, len(text), step):
        ev.append({"type": "response.output_text.delta", "item_id": "msg_1", "output_index": 1,
                   "content_index": 0, "delta": text[i:i + step], "logprobs": []})
    annotations = []
    if ann:
        a = {"type": "url_citation", "start_index": text.index(" (["), "end_index": len(text) - len(" Do you drive?"),
             "url": "https://www.gasbuddy.com/gasprices/arizona/tucson?utm_source=openai", "title": "GasBuddy"}
        annotations.append(a)
        ev.append({"type": "response.output_text.annotation.added", "item_id": "msg_1", "output_index": 1,
                   "content_index": 0, "annotation_index": 0, "annotation": a})
    ev.append({"type": "response.output_text.done", "item_id": "msg_1", "output_index": 1, "content_index": 0,
               "text": text})
    ev.append({"type": "response.output_item.done", "output_index": 1, "item": {
        "type": "message", "id": "msg_1", "role": "assistant",
        "content": [{"type": "output_text", "text": text, "annotations": annotations}]}})
    return ev


def search_events(queries):
    return [
        {"type": "response.output_item.added", "output_index": 0,
         "item": {"type": "web_search_call", "id": "ws_1", "status": "in_progress"}},
        {"type": "response.web_search_call.in_progress", "item_id": "ws_1", "output_index": 0},
        {"type": "response.web_search_call.searching", "item_id": "ws_1", "output_index": 0},
        {"type": "response.web_search_call.completed", "item_id": "ws_1", "output_index": 0},
        {"type": "response.output_item.done", "output_index": 0, "item": {
            "type": "web_search_call", "id": "ws_1", "status": "completed",
            "action": {"type": "search", "queries": queries,
                       "sources": [{"type": "url", "url": "https://www.gasbuddy.com/x"}, {"type": "oai-weather"}]}}},
    ]


def wrap(rid, body_events, usage=None):
    usage = usage or {"input_tokens": 9000, "input_tokens_details": {"cached_tokens": 1000},
                      "output_tokens": 100, "output_tokens_details": {"reasoning_tokens": 0}, "total_tokens": 9100}
    return ([{"type": "response.created", "response": {"id": rid, "status": "in_progress"}},
             {"type": "response.in_progress", "response": {"id": rid, "status": "in_progress"}}]
            + body_events
            + [{"type": "response.completed", "response": {"id": rid, "status": "completed", "usage": usage}}])


class FakeOpenAI:
    """Ведёт себя как Responses и Chat Completions по документации. reject_none — отвергать web_search
    при reasoning.effort none (проверка лестницы усилий)."""

    def __init__(self, reject_none=False, no_search_at_none=False):
        self.reject_none = reject_none
        self.no_search_at_none = no_search_at_none
        self.requests = []

    def __call__(self, request):
        body = json.loads(request.content)
        self.requests.append(body)
        if request.url.path.endswith("/responses"):
            return self.responses(body)
        if request.url.path.endswith("/chat/completions"):
            return self.chat(body)
        return httpx.Response(404)

    def responses(self, body):
        assert body["stream"] is True and body["model"] == "gpt-6-luna"
        effort = body["reasoning"]["effort"]
        tools = [t["type"] if t["type"] != "function" else t["name"] for t in body["tools"]]
        if self.reject_none and effort == "none":
            return httpx.Response(400, json={"error": {
                "message": "The web_search tool is not supported with reasoning.effort 'none'.",
                "type": "invalid_request_error", "param": "tools", "code": "unsupported_parameter"}})
        if "previous_response_id" in body:
            out = body["input"][0]
            assert out["type"] == "function_call_output" and out["call_id"] == "call_9"
            ev = wrap("resp_2", text_events(f"Right now it is {out['output']}. Do you like hot days?", "resp_2"))
            return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=sse(ev))
        student = body["input"][0]["content"]
        if "get_weather" in tools and "weather" in student:
            ev = wrap("resp_f", [{"type": "response.output_item.added", "output_index": 0,
                                  "item": {"type": "function_call", "id": "fc_1", "call_id": "call_9",
                                           "name": "get_weather", "arguments": ""}},
                                 {"type": "response.function_call_arguments.delta", "item_id": "fc_1",
                                  "output_index": 0, "delta": '{"location":'},
                                 {"type": "response.function_call_arguments.done", "item_id": "fc_1",
                                  "output_index": 0, "arguments": '{"location":"Tucson"}'},
                                 {"type": "response.output_item.done", "output_index": 0, "item": {
                                     "type": "function_call", "id": "fc_1", "call_id": "call_9",
                                     "name": "get_weather", "arguments": '{"location":"Tucson"}'}}])
            return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=sse(ev))
        fresh = any(w in student.lower() for w in ("today", "news", "won", "weather", "rain"))
        if fresh and not (self.no_search_at_none and effort == "none"):
            text = "Gas in Tucson costs about $3.05 per gallon today" + CITE + ". Do you drive?"
            ev = wrap("resp_s", search_events([f"q {student}"]) + text_events(text, ann=True))
        else:
            ev = wrap("resp_a", text_events("I think you meant \"I went\", right? What did you buy?"),
                      {"input_tokens": 700, "output_tokens": 30})
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=sse(ev))

    def chat(self, body):
        last = body["messages"][-1]
        if body.get("tools") and last["role"] == "user" and any(
                w in last["content"].lower() for w in ("today", "news", "won", "weather", "rain")):
            name = "get_weather" if ("weather" in last["content"].lower() or "rain" in last["content"].lower()) \
                else "search_live_web"
            args = '{"location":"Tucson"}' if name == "get_weather" else '{"query":"x"}'
            msg = {"role": "assistant", "content": None, "tool_calls": [
                {"id": "call_1", "type": "function", "function": {"name": name, "arguments": args}}]}
        elif last["role"] == "tool":
            msg = {"role": "assistant", "content": "I could not find fresh information. Can you ask again?"}
        else:
            msg = {"role": "assistant", "content": "Nice! What did you do there?"}
        return httpx.Response(200, json={"choices": [{"message": msg, "finish_reason": "stop"}],
                                         "usage": {"prompt_tokens": 800, "completion_tokens": 40}})


def make_ctx(tmp_path, fake, extra=()):
    creds = tmp_path / "c.properties"
    creds.write_text(f"APIkey={FAKE_KEY}\n")
    args = probe.parse_args(["responses", "--credentials", str(creds), "--out", str(tmp_path / "out"),
                             "--docs", str(tmp_path / "02c.md"), "--timeout", "5", "--yes",
                             "--stub-search-delay", "0", *extra])
    ctx = probe.Ctx(args)
    api = probe.Api.__new__(probe.Api)
    api.httpx, api.key, api.timeout = httpx, FAKE_KEY, 5
    api.client = httpx.Client(base_url=probe.API_BASE, transport=httpx.MockTransport(fake),
                              headers={"Authorization": f"Bearer {FAKE_KEY}"})
    ctx._api = api
    return ctx


@pytest.fixture(autouse=True)
def fake_weather(monkeypatch):
    monkeypatch.setattr(probe, "fetch_weather", lambda loc: (f"{loc}: Sunny +31°C", 0.05))


def load(tmp_path):
    return json.loads((tmp_path / "out" / probe.RESP_JSON_NAME).read_text())


# ---- разбор потока ----------------------------------------------------------

def test_parse_sse_lines():
    lines = ["event: response.created", 'data: {"type": "response.created"}', "", ": keep-alive", "",
             "event: x", "data: [DONE]", ""]
    assert probe.parse_sse(lines) == [("response.created", '{"type": "response.created"}'), ("x", "[DONE]")]


def test_analyze_stream_timings_and_search():
    text = "Gas in Tucson costs about $3.05 per gallon today" + CITE + ". Do you drive? Tell me more."
    evs = wrap("r", search_events(["gas price Tucson"]) + text_events(text, ann=True, pieces=8))
    timed = [(0.1 * i, e) for i, e in enumerate(evs)]
    a = probe.analyze_stream(timed)
    assert a["text"] == text and a["status"] == "completed"
    assert a["n_search_calls"] == 1 and a["n_search_actions"] == 1
    s = a["searches"][0]
    assert s["queries"] == ["gas price Tucson"] and s["n_sources"] == 2 and "oai-weather" in s["source_types"]
    assert a["search_seconds"] == [pytest.approx(0.3)]  # от output_item.added (0,2) до completed (0,5)
    assert a["t_first_token"] < a["t_first_sentence"] <= a["t_end"]
    assert a["t_first_group"] is not None and a["t_first_group"] < a["t_end"]
    assert a["stream_equals_whole"] and not a["divergences"]
    assert all("gasbuddy" not in g[0] for g in a["groups"])
    assert "адрес http" in a["leftovers_raw"] and a["leftovers_clean"] == []
    assert len(a["message_annotations"]) == 1 and a["annotation_events"][0]["annotation"]["type"] == "url_citation"
    assert not a["text_before_search"]


def test_analyze_stream_failed_and_error():
    evs = [(0.0, {"type": "response.created", "response": {"id": "r"}}),
           (0.5, {"type": "error", "code": "server_error", "message": "boom"}),
           (0.6, {"type": "response.failed", "response": {"status": "failed",
                                                         "error": {"code": "server_error", "message": "x"}}})]
    a = probe.analyze_stream(evs)
    assert a["status"] == "failed" and len(a["errors"]) == 2 and a["t_end"] == 0.6 and a["text"] == ""


def test_costs():
    u = {"input_tokens": 9000, "input_tokens_details": {"cached_tokens": 1000}, "output_tokens": 100}
    assert probe.resp_cost(u, 1) == pytest.approx(8000 * 0.10e-6 + 1000 * 0.01e-6 + 100 * 0.50e-6 + 0.01)
    assert probe.chat_cost({"prompt_tokens": 800, "completion_tokens": 40}) == pytest.approx(800e-7 + 40 * 5e-7)
    assert probe.lesson_cost([{"cost": 0.001}], [{"cost": 0.02}]) == pytest.approx(12 * 0.001 + 3 * 0.02)


def test_estimate_within_budget():
    est = probe.estimate_responses_cost(list(probe.RESP_VARIANTS), 3)
    assert 0.3 < est["total"] < 1.5 and est["requests"] > 50


def test_prompt_is_the_app_file():
    # D46: проба читает тот же файл, что приложение (ResponsesTutor.systemPrompt) — без копий.
    f = Path(probe.__file__).parents[2] / "android_app/app/src/main/resources/tutor_prompt.txt"
    assert probe.TUTOR_PROMPT_FILE == f
    p = probe.tutor_prompt("Monday, November 23, 2026")
    assert p == f.read_text(encoding="utf-8").strip().replace("{DATE}", "Monday, November 23, 2026")
    assert "Today is Monday, November 23, 2026." in p and "{DATE}" not in p
    assert probe.tutor_prompt("d", probe.NO_LINKS_RULE).endswith(probe.NO_LINKS_RULE)
    kt = (Path(probe.__file__).parents[2] / "android_app/app/src/main/java/com/example/listeningtrainer/openai/"
          "ResponsesTutor.kt").read_text()
    assert 'PROMPT_RESOURCE = "/tutor_prompt.txt"' in kt and 'DATE_MARK = "{DATE}"' in kt


# ---- прогон целиком ------------------------------------------------------------

def test_responses_full_run(tmp_path):
    fake = FakeOpenAI()
    ctx = make_ctx(tmp_path, fake, ("--repeats", "1"))
    assert probe.probe_responses(ctx)
    d = load(tmp_path)
    assert d["effort"] == "none" and d["effort_attempts"][0]["n_search_calls"] == 1
    by = {(r["variant"], r["scenario"]): r for r in d["rows"]}
    assert len(d["rows"]) == 9 + 9 + 6 + 2 + 4
    assert by[("1", "B1")]["tools_used"][0]["name"] == "search_live_web"
    assert not by[("1", "B1")]["tools_used"][0]["has_data"]
    assert by[("1", "C1")]["tools_used"][0]["has_data"]  # погода — wttr.in (здесь подменена)
    assert by[("2", "A1")]["n_search_calls"] == 0 and by[("2", "B1")]["n_search_calls"] == 1
    assert by[("2", "B1")]["cost"] > 0.01
    v4 = by[("4", "C1")]
    assert v4["tools_used"][0]["name"] == "get_weather" and "Sunny" in v4["text"]
    assert v4["t_first_group"] is not None and len(v4["usages"]) == 2
    assert all(r["stream_equals_whole"] for r in d["rows"] if r["variant"] != "1")
    # что ушло на сервер
    r3 = [b for b in fake.requests if b.get("tools") and b["tools"][0].get("search_context_size") == "low"]
    assert r3 and all(b["reasoning"] == {"effort": "none"} for b in r3)
    assert all(b["include"] == ["web_search_call.action.sources"] for b in fake.requests if "include" in b)
    v5 = [b for b in fake.requests if "Never write links" in b.get("instructions", "")]
    assert len(v5) == 4
    md = (tmp_path / "02c.md").read_text()
    for head in ("## Главный вопрос", "**Да: при `none`", "## Сводка по вариантам", "## Цена 10-минутного занятия",
                 "## Группа A", "## Группа B", "## Погода", "## Сноски", "## Потоковая нарезка",
                 "## Рекомендация исполнителя", "## Приложение: все ответы", "совпали с нарезкой готового текста: **"):
        assert head in md, head
    assert FAKE_KEY not in md and FAKE_KEY not in (tmp_path / "out" / probe.RESP_JSON_NAME).read_text()


def test_effort_ladder_when_none_rejected(tmp_path):
    ctx = make_ctx(tmp_path, FakeOpenAI(reject_none=True), ("--repeats", "1", "--variants", "2"))
    probe.probe_responses(ctx)
    d = load(tmp_path)
    assert d["effort"] == "low"
    first = d["effort_attempts"][0]
    assert first["effort"] == "none" and first["http_status"] == 400
    assert "not supported with reasoning.effort 'none'" in first["error"] and "unsupported_parameter" in first["error"]
    assert all(r["effort"] == "low" for r in d["rows"])
    md = (tmp_path / "02c.md").read_text()
    assert "**При `none` поиск не работает**" in md and "**`low`**" in md


def test_effort_ladder_when_no_search_at_none(tmp_path):
    ctx = make_ctx(tmp_path, FakeOpenAI(no_search_at_none=True), ("--repeats", "1", "--variants", "2"))
    probe.probe_responses(ctx)
    d = load(tmp_path)
    assert d["effort"] == "low"
    assert [a["effort"] for a in d["effort_attempts"]] == ["none", "none", "low"]


def test_budget_stops_run(tmp_path):
    ctx = make_ctx(tmp_path, FakeOpenAI(), ("--repeats", "3", "--variants", "2", "--budget", "0.03"))
    probe.probe_responses(ctx)
    d = load(tmp_path)
    assert d["budget_exhausted"] and len(d["rows"]) < 27
    assert "бюджет исчерпан" in (tmp_path / "02c.md").read_text()


def test_confirmation_no_runs_nothing(tmp_path):
    fake = FakeOpenAI()
    ctx = make_ctx(tmp_path, fake)
    ctx.args.yes = False
    assert probe.probe_responses(ctx, ask=lambda _: "n") is False
    assert fake.requests == [] and not (tmp_path / "02c.md").exists()


def test_responses_401(tmp_path):
    ctx = make_ctx(tmp_path, lambda r: httpx.Response(401, json={"error": {"message": f"bad key {FAKE_KEY}"}}),
                   ("--variants", "2"))
    with pytest.raises(probe.ProbeError) as e:
        probe.probe_responses(ctx)
    assert "401" in str(e.value) and FAKE_KEY not in str(e.value)


def test_cli_main_responses(tmp_path, monkeypatch, capsys):
    fake = FakeOpenAI()
    real_ctx = probe.Ctx

    def ctx_factory(args):
        monkeypatch.setattr(probe, "Ctx", real_ctx)
        return make_ctx(tmp_path, fake, ("--repeats", "1", "--variants", "1"))

    monkeypatch.setattr(probe, "Ctx", ctx_factory)
    assert probe.main(["responses", "--yes"]) == 0
    out = capsys.readouterr().out
    assert "[S02c] Результаты пробы" in out
