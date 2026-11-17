"""Сетевой код probe.py против подставных серверов (локальный WebSocket, httpx.MockTransport).

Проверяет разбор событий и обработку ошибок в нашем коде. Настоящий API не вызывается,
поэтому соответствие документации OpenAI эти тесты НЕ подтверждают.
"""
import asyncio
import base64
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import probe  # noqa: E402

websockets = pytest.importorskip("websockets")
httpx = pytest.importorskip("httpx")


async def fake_realtime(ws):
    """Ведёт себя как Realtime по документации: session.updated, ответ звуком, VAD-события."""
    await ws.send(json.dumps({"type": "session.created", "session": {}}))
    appended = 0
    async for msg in ws:
        ev = json.loads(msg)
        t = ev["type"]
        if t == "session.update":
            s = ev["session"]
            if s.get("type") == "transcription" and s["audio"]["input"].get("turn_detection"):
                await ws.send(json.dumps({"type": "error", "error": {
                    "type": "invalid_request_error", "message": "turn_detection not supported"}}))
            else:
                await ws.send(json.dumps({"type": "session.updated", "session": s}))
        elif t == "response.create":
            text = ev["response"]["input"][0]["content"][0]["text"]
            heard = "I think so." if text == "What do you think?" else text
            await ws.send(json.dumps({"type": "response.created"}))
            for _ in range(3):
                await ws.send(json.dumps({"type": "response.output_audio.delta",
                                          "delta": base64.b64encode(b"\x00\x01" * 2400).decode()}))
            await ws.send(json.dumps({"type": "response.output_audio.done"}))
            await ws.send(json.dumps({"type": "response.output_audio_transcript.done", "transcript": heard}))
            await ws.send(json.dumps({"type": "response.done", "response": {
                "status": "completed",
                "usage": {"total_tokens": 80, "input_tokens": 50, "output_tokens": 30,
                          "input_token_details": {"text_tokens": 50, "audio_tokens": 0, "cached_tokens": 0},
                          "output_token_details": {"text_tokens": 6, "audio_tokens": 24}}}}))
        elif t == "input_audio_buffer.append":
            appended += len(base64.b64decode(ev["audio"]))
            if appended == 4800 * 3:  # 0,3 с звука
                await ws.send(json.dumps({"type": "input_audio_buffer.speech_started",
                                          "audio_start_ms": 100, "item_id": "i1"}))
            if appended == 4800 * 6:
                await ws.send(json.dumps({"type": "input_audio_buffer.speech_stopped",
                                          "audio_end_ms": 550, "item_id": "i1"}))
                await ws.send(json.dumps({"type": "input_audio_buffer.committed", "item_id": "i1"}))
                await ws.send(json.dumps({"type": "conversation.item.input_audio_transcription.completed",
                                          "item_id": "i1", "transcript": "hello",
                                          "usage": {"type": "duration", "seconds": 1}}))


@pytest.fixture
def ws_url():
    import threading
    loop = asyncio.new_event_loop()
    th = threading.Thread(target=loop.run_forever, daemon=True)
    th.start()

    async def start():
        return await websockets.serve(fake_realtime, "127.0.0.1", 0)

    server = asyncio.run_coroutine_threadsafe(start(), loop).result(5)
    port = server.sockets[0].getsockname()[1]
    yield f"ws://127.0.0.1:{port}/"
    loop.call_soon_threadsafe(loop.stop)


def make_ctx(tmp_path, cmd="verbatim", extra=()):
    creds = tmp_path / "c.properties"
    creds.write_text("APIkey=sk-fake-0000000000000000\n")
    args = probe.parse_args([cmd, "--credentials", str(creds), "--out", str(tmp_path / "out"),
                             "--docs", str(tmp_path / "docs.md"), "--timeout", "5", *extra])
    return probe.Ctx(args)


def test_verbatim_against_fake(tmp_path, ws_url, monkeypatch):
    monkeypatch.setattr(probe, "REALTIME_URL", ws_url + "?model={model}")
    monkeypatch.setattr(probe, "load_chunks", lambda: ["Tell me more.", "What do you think?", "2026."])
    ctx = make_ctx(tmp_path, extra=("--variant", "auto"))
    probe.probe_verbatim(ctx)
    raw = json.loads((tmp_path / "out" / "results.json").read_text())["probes"]["verbatim"]["raw"]
    assert set(raw["runs"]) == {"1", "2"}  # 2 из 3 дословно < 98 % → второй промпт
    rows = raw["runs"]["1"]["rows"]
    assert [r["verdict"] for r in rows] == ["verbatim", "mismatch", "verbatim"]
    assert rows[0]["t_first_audio"] is not None and rows[0]["audio_seconds"] == pytest.approx(0.3)
    assert rows[0]["usage"]["output_token_details"]["audio_tokens"] == 24
    assert (tmp_path / "out" / "verbatim" / "v1_01.wav").is_file()
    pub = (tmp_path / "docs.md").read_text()
    assert "## 1. verbatim" in pub and str(tmp_path / "out") not in pub


def test_vadservice_against_fake(tmp_path, ws_url, monkeypatch):
    monkeypatch.setattr(probe, "REALTIME_URL", ws_url + "?model={model}")
    monkeypatch.setattr(probe, "REALTIME_TRANSCRIPTION_URL", ws_url + "?intent=transcription")
    ctx = make_ctx(tmp_path, "vadservice", ("--tail", "0.2"))
    pcm = probe.silence(0.5, probe.RT_RATE) + b"\x10\x00" * 24000 + probe.silence(1.5, probe.RT_RATE)
    monkeypatch.setattr(probe, "vad_audio24",
                        lambda c: (pcm, "тест", [("тишина", 0.0, 0.5), ("пауза посреди фразы", 0.5, 0.6)]))
    monkeypatch.setattr(probe.asyncio, "sleep", _fast_sleep)
    probe.probe_vadservice(ctx)
    raw = json.loads((tmp_path / "out" / "results.json").read_text())["probes"]["vadservice"]["raw"]
    conv, trans = raw["conversation"], raw["transcription"]
    assert conv["error"] is None
    assert conv["accepted"]["turn_detection"]["type"] == "semantic_vad"
    types = [e["type"] for e in conv["events"]]
    assert "input_audio_buffer.speech_stopped" in types
    assert "conversation.item.input_audio_transcription.completed" in types
    assert "turn_detection not supported" in trans["error"]
    md = (tmp_path / "docs.md").read_text()
    assert "посреди фразы" in md and "**да**" in md


_real_sleep = asyncio.sleep


async def _fast_sleep(s):
    await _real_sleep(min(s, 0.01))


def test_realtime_401(tmp_path, monkeypatch):
    async def reject(conn, request):
        return conn.respond(401, "nope")

    async def run():
        async with websockets.serve(lambda ws: None, "127.0.0.1", 0, process_request=reject) as srv:
            port = srv.sockets[0].getsockname()[1]
            async with probe.Realtime("sk-fake-0000000000000000", 5, f"ws://127.0.0.1:{port}/"):
                pass

    with pytest.raises(probe.ProbeError) as e:
        asyncio.run(run())
    assert "401" in str(e.value) and "sk-fake" not in str(e.value)


def fake_http(request: httpx.Request) -> httpx.Response:
    path = request.url.path
    if path.endswith("/audio/transcriptions"):
        body = request.content.decode("latin-1")
        if 'name="languages[]"' in body:
            return httpx.Response(200, json={"text": "Yesterday I went", "languages": [{"code": "en"}]})
        if 'name="language"' in body and "whisper-1" in body:
            return httpx.Response(200, json={"text": "Yesterday I went"})
        return httpx.Response(400, json={"error": {"message": "bad languages"}})
    if path.endswith("/audio/speech"):
        return httpx.Response(200, content=b"\x00\x00" * 24000)
    if path.endswith("/chat/completions"):
        body = json.loads(request.content)
        last = body["messages"][-1]
        if "gas" in (last.get("content") or "") and body.get("tools"):
            msg = {"role": "assistant", "content": None, "tool_calls": [{
                "id": "call_1", "type": "function",
                "function": {"name": "search_live_web", "arguments": '{"query":"gas price Tucson"}'}}]}
        else:
            msg = {"role": "assistant", "content": f"reply from {body['model']}"}
        return httpx.Response(200, json={"choices": [{"message": msg, "finish_reason": "stop"}],
                                         "usage": {"total_tokens": 10}})
    return httpx.Response(404)


def install_fake_api(ctx):
    api = probe.Api.__new__(probe.Api)
    api.httpx, api.key, api.timeout = httpx, "sk-fake", 5
    api.client = httpx.Client(base_url=probe.API_BASE, transport=httpx.MockTransport(fake_http))
    ctx._api = api


def test_transcribe_against_fake(tmp_path):
    ctx = make_ctx(tmp_path, "transcribe", ("--repeats", "2"))
    install_fake_api(ctx)
    probe.probe_transcribe(ctx)
    raw = json.loads((tmp_path / "out" / "results.json").read_text())["probes"]["transcribe"]["raw"]
    statuses = {v["label"].split(" ")[0]: v["status"] for v in raw["variants"]}
    assert statuses["languages[]=en"] == 200 and statuses["languages=en"] == 400
    assert len(raw["timing"]["gpt-transcribe"]) == 2
    assert all(r["status"] == 200 for r in raw["timing"]["gpt-transcribe"])


def test_tutor_against_fake(tmp_path):
    ctx = make_ctx(tmp_path, "tutor")
    install_fake_api(ctx)
    probe.probe_tutor(ctx)
    raw = json.loads((tmp_path / "out" / "results.json").read_text())["probes"]["tutor"]["raw"]
    luna = raw["results"]["gpt-6-luna"]
    assert luna[2]["tool_calls"][0]["function"]["name"] == "search_live_web"
    assert luna[2]["format_problems"] == []
    assert luna[0]["reply"] == "reply from gpt-6-luna"
    md = (tmp_path / "docs.md").read_text()
    assert "формат прежний" in md


def test_http_401_message(tmp_path):
    ctx = make_ctx(tmp_path, "tutor")
    api = probe.Api.__new__(probe.Api)
    api.client = httpx.Client(base_url=probe.API_BASE, transport=httpx.MockTransport(
        lambda r: httpx.Response(401, json={"error": {"message": "Incorrect API key provided: sk-fake-0000***"}})))
    ctx._api = api
    with pytest.raises(probe.ProbeError) as e:
        probe.run_probe(ctx, "tutor")
    assert "401" in str(e.value)
