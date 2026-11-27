"""Проба S03b `endpoint` без сети и без ключа: разметка, детекторы, меры, правило выбора."""
import json
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import probe  # noqa: E402

sys.modules.setdefault("probe", probe)
import endpoint as ep  # noqa: E402

SR = ep.SAMPLE_RATE


def tone(sec, db, f=440.0):
    t = np.arange(int(sec * SR)) / SR
    return (10 ** (db / 20) * np.sqrt(2) * np.sin(2 * np.pi * f * t))


def noise(sec, db, seed=0):
    return np.random.default_rng(seed).normal(0, 10 ** (db / 20), int(sec * SR))


def rec(pcm, kind="short", text="At noon.", words=None, press_ms=None, pulses=(), base="ep_1_silence_01", cond="silence"):
    r = ep.Rec(base, cond, 1, kind, text, 1, {}, Path("x.wav"))
    r.pcm = np.clip(pcm, -32768, 32767).astype(np.int16)
    r.duration_ms = int(len(r.pcm) * 1000 / SR)
    r.press_ms = press_ms
    r.pulses_ms = list(pulses)
    r.words = words or []
    return r


# --- текст -----------------------------------------------------------------------------------

def test_accuracy_ignores_case_punctuation_and_um():
    assert ep.accuracy("Yesterday I … went to the shop.", "yesterday, I went to the shop") == 1.0
    assert ep.accuracy("Last weekend I visited my friend. We cooked dinner, um… and then", "last weekend I visited my friend we cooked dinner and then") == 1.0
    assert ep.accuracy("My dog is five.", "My dog is fine.") == 0.75
    assert ep.accuracy("", "anything") is None
    assert ep.accuracy("At noon.", "") == 0.0


# --- время ------------------------------------------------------------------------------------

def test_wall_time_is_aligned_by_the_end_of_the_recording():
    # 100 окон; последнее прочитано в 8300 мс по часам, это звук до 8000 мс → сдвиг 300 мс
    meta = {"durationMs": 8000, "levelWallMs": [300 + i * 80 for i in range(1, 101)][-100:]}
    meta["levelWallMs"][-1] = 8300
    assert ep.to_audio_ms(meta, 8300) == 8000
    assert ep.to_audio_ms(meta, 2300) == 2000
    assert ep.to_audio_ms(meta, None) is None


def test_load_recordings_takes_the_last_take(tmp_path):
    for at, take, n in ((1_764_201_600_000, 1, 8000), (1_764_201_700_000, 2, 16000)):
        base = f"ep_{at}_water_05"
        (tmp_path / f"{base}.wav").write_bytes(probe.pcm_to_wav(np.zeros(n, dtype=np.int16).tobytes(), SR))
        (tmp_path / f"{base}.json").write_text(json.dumps({
            "condition": "water", "line": 5, "kind": "pause", "text": "x", "take": take, "startedAtMs": at,
            "durationMs": n // 16, "buttonPressMs": n // 16 - 100, "pulsesMs": [2000], "levelWallMs": [n // 16]}))
    recs = ep.load_recordings(tmp_path)
    assert len(recs) == 1 and recs[0].take == 2 and recs[0].duration_ms == 1000
    assert recs[0].press_ms == 900 and recs[0].pulses_ms == [2000]


# --- порог громкости: эмуляция SilenceTurnDetector ---------------------------------------------

def test_threshold_detector_matches_the_app_rule():
    db = [40.0] * 5 + [70.0] * 10 + [40.0] * 60   # речь в окнах 5..14 → последний громкий конец 1200 мс
    o = ep.threshold_detector(db, stop_ms=99_000, threshold=59, silence_ms=3000)
    assert o.speech_heard and o.end_s == pytest.approx(4.24)  # первое окно, где тишина > 3000 мс: 4240
    # шум выше порога — конца нет
    assert ep.threshold_detector([62.0] * 200, 99_000, 59, 3000).end_s is None
    # молчание с начала — конец через 3 с, речи нет (как в приложении: «не услышал»)
    o = ep.threshold_detector([40.0] * 100, 99_000, 59, 3000)
    assert o.end_s == pytest.approx(3.04) and not o.speech_heard


def test_threshold_detector_stops_at_the_button_and_masks_pulses():
    db = [70.0] * 10 + [40.0] * 100
    assert ep.threshold_detector(db, stop_ms=2000, threshold=59, silence_ms=3000).end_s is None
    loud_pulse = [40.0] * 100
    loud_pulse[30] = 75.0
    assert ep.threshold_detector(loud_pulse, 99_000, 59, 3000).speech_heard
    masked = ep.masked_windows([30 * 80 + 10], len(loud_pulse))
    assert 30 in masked and ep.threshold_detector(loud_pulse, 99_000, 59, 3000, masked).speech_heard is False


def test_window_db_uses_the_app_scale():
    x = tone(1.0, 60.0)
    assert np.mean(ep.window_db(x.astype(np.int16))) == pytest.approx(60.0, abs=0.2)


# --- конечный автомат по вероятностям (Silero, WebRTC) -----------------------------------------

def test_segment_state_machine_end_after_silence():
    probs = [0.0] * 10 + [0.9] * 30 + [0.1] * 60   # кадры 32 мс: речь 320..1280 мс
    o = ep.segment_state_machine(32, probs, 0.5, 1000, stop_ms=99_000)
    assert o.speech_heard and o.speech_start_s == pytest.approx(0.32)
    assert o.end_s == pytest.approx((41 + 31) * 0.032, abs=0.04)  # тишина с 1312 мс + 1000 мс


def test_segment_state_machine_short_click_is_not_speech_and_pause_resets():
    click = [0.0] * 10 + [0.95] * 3 + [0.0] * 100
    o = ep.segment_state_machine(32, click, 0.5, 600, stop_ms=99_000)
    assert not o.speech_heard and o.end_s is None
    # пауза 800 мс посреди речи при пороге тишины 1000 — конца нет до конца речи
    probs = [0.9] * 20 + [0.1] * 25 + [0.9] * 20 + [0.0] * 40
    o = ep.segment_state_machine(32, probs, 0.5, 1000, stop_ms=99_000)
    assert o.end_s == pytest.approx((65 + 32) * 0.032, abs=0.04)
    # hysteresis: 0.4 (между neg=0.35 и 0.5) тишиной не считается
    hold = [0.9] * 20 + [0.4] * 60 + [0.0] * 40
    assert ep.segment_state_machine(32, hold, 0.5, 1000, stop_ms=99_000).end_s > 80 * 0.032


def test_mask_frames_zeroes_frames_after_a_pulse():
    p = ep.mask_frames([0.9] * 30, 32, [320])
    assert p[9] == 0.9 and p[10] == 0.0 and p[20] == 0.0 and p[21] == 0.9


def test_silero_hears_a_tone_burst_less_than_noise():
    pytest.importorskip("onnxruntime")
    if not ep.SILERO_MODEL.is_file():
        pytest.skip("нет модели Silero (out/endpoint/models)")
    s = ep.Silero()
    p = s.probs(np.zeros(SR, dtype=np.int16))
    assert len(p) == SR // 512 and max(p) < 0.3


# --- уточнение конца речи ---------------------------------------------------------------------

def test_refine_end_finds_the_energy_end_when_whisper_is_shifted():
    speech = np.concatenate([noise(0.4, 30), tone(0.5, 66, 700) + noise(0.5, 30, 1), noise(5.0, 30, 2)])
    r = rec(speech, words=[{"word": " At", "start": 1.35, "end": 1.5}, {"word": " noon.", "start": 1.5, "end": 1.98}], press_ms=5800)
    end, note = ep.refine_end(r)
    assert end == pytest.approx(0.9, abs=0.05), note


def test_refine_end_keeps_whisper_when_noise_hides_the_speech():
    x = noise(6.0, 60)
    r = rec(x, words=[{"word": " noon.", "start": 1.0, "end": 1.6}], press_ms=5800)
    end, note = ep.refine_end(r)
    assert end == 1.6 and "метка whisper" in note


# --- серверные события → срабатывания ---------------------------------------------------------

def test_server_outcome_uses_arrival_time_and_the_turn_with_the_last_word():
    r = rec(np.zeros(SR * 8), kind="pause", text="I wanted to tell you about … my trip to the mountains.",
            words=[{"word": "about", "start": 1.5, "end": 1.9}, {"word": "mountains", "start": 5.5, "end": 6.0}], press_ms=7600)
    run = {"events": [
        {"type": "input_audio_buffer.speech_started", "t": 0.6},
        {"type": "input_audio_buffer.speech_stopped", "t": 2.9},
        {"type": "input_audio_buffer.committed", "t": 2.91, "item_id": "a"},
        {"type": "conversation.item.input_audio_transcription.completed", "t": 3.3, "item_id": "a", "transcript": "I wanted to tell you about."},
        {"type": "input_audio_buffer.speech_started", "t": 4.5},
        {"type": "input_audio_buffer.speech_stopped", "t": 7.0},
        {"type": "input_audio_buffer.committed", "t": 7.01, "item_id": "b"},
        {"type": "conversation.item.input_audio_transcription.completed", "t": 7.4, "item_id": "b", "transcript": "My trip to the mountains."},
    ]}
    o = ep.server_outcome(r, run)
    assert o.end_s == 2.9 and o.speech_heard and o.ends_s == [2.9, 7.0]
    assert o.text == "I wanted to tell you about. My trip to the mountains."
    assert o.text_ready_s == 7.4
    m = ep.evaluate([r], {r.base: o})["all"]
    assert m["false_end"] == 1 and m["false_end_paused"] == 1 and m["accuracy"] == 1.0


def test_server_outcome_after_the_button_is_no_end():
    r = rec(np.zeros(SR * 5), words=[{"word": "noon", "start": 0.5, "end": 1.0}], press_ms=4000)
    run = {"events": [{"type": "input_audio_buffer.speech_started", "t": 0.6},
                      {"type": "input_audio_buffer.speech_stopped", "t": 4.5}]}
    o = ep.server_outcome(r, run)
    assert o.end_s is None
    assert ep.evaluate([r], {r.base: o})["all"]["no_end"] == 1


# --- меры и правило выбора ---------------------------------------------------------------------

def make_set(latency=0.8, false_end_paused=0, no_end=0, false_speech=0):
    recs, outs = [], {}
    for c in ep.CONDITIONS:
        for line, kind in enumerate(["short"] * 4 + ["pause"] * 4 + ["long"] * 2 + ["no_speech"] * 2, start=1):
            base = f"ep_{line}_{c}"
            words = [] if kind == "no_speech" else [{"word": "x", "start": 1.0, "end": 2.0}]
            r = rec(np.zeros(10), kind=kind, text="" if kind == "no_speech" else "x", words=words, base=base, cond=c)
            recs.append(r)
            if kind == "no_speech":
                outs[base] = ep.Outcome(None, false_speech > 0 and line == 11 and c == "water")
            elif kind in ("pause", "long") and line - 4 <= false_end_paused:
                outs[base] = ep.Outcome(1.0, True)
            elif line <= no_end:
                outs[base] = ep.Outcome(None, True)
            else:
                outs[base] = ep.Outcome(2.0 + latency, True)
    return recs, outs


def test_rule_passes_with_one_false_end_per_condition_and_fails_with_two():
    recs, outs = make_set(latency=0.8, false_end_paused=1)
    m = ep.evaluate(recs, outs)
    assert m["water"]["false_end_paused"] == 1 and m["water"]["paused_lines"] == 6
    assert ep.passes(m)[0]
    recs, outs = make_set(latency=0.8, false_end_paused=2)
    ok, why = ep.passes(ep.evaluate(recs, outs))
    assert not ok and any("ложный конец 2 из 6" in w for w in why)


def test_rule_fails_on_no_end_latency_and_false_speech():
    assert not ep.passes(ep.evaluate(*make_set(no_end=1)))[0]
    ok, why = ep.passes(ep.evaluate(*make_set(latency=1.6)))
    assert not ok and any("медиана" in w for w in why)
    m = ep.evaluate(*make_set(false_speech=1))
    assert m["all"]["false_speech"] == 1 and ep.passes(m)[0]  # 1 из 8 — можно


def test_percentiles():
    assert ep.pct([1, 2, 3, 4, 5], 0.5) == 3
    assert ep.pct([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 0.9) == pytest.approx(9.1)
    assert ep.pct([], 0.5) is None


def test_sessions_follow_the_docs():
    s = ep.server_session("server_vad:700")
    assert s["type"] == "transcription" and s["audio"]["input"]["format"] == {"type": "audio/pcm", "rate": 24000}
    assert s["audio"]["input"]["turn_detection"] == {"type": "server_vad", "silence_duration_ms": 700}
    assert ep.server_session("semantic_vad:low")["audio"]["input"]["turn_detection"] == {"type": "semantic_vad", "eagerness": "low"}
    live = ep.live_session("low")["audio"]["input"]
    assert live["turn_detection"] is None and live["transcription"]["model"] == "gpt-live-transcribe"


def test_to24k_keeps_duration():
    pytest.importorskip("scipy")
    assert len(ep.to24k(np.zeros(16000, dtype=np.int16))) == 24000 * 2


def test_shorten_pauses_cuts_the_middle_and_shifts_marks():
    # речь 0.5–1.5, пауза до 6.5 (5 с), речь 6.5–7.5, тишина до 10; пульсы в 2, 4, 8 с; кнопка 9.5 с
    x = np.concatenate([noise(0.5, 30), tone(1.0, 70, 600) + noise(1.0, 30, 1), noise(5.0, 30, 2),
                        tone(1.0, 70, 600) + noise(1.0, 30, 3), noise(2.5, 30, 4)])
    r = rec(x, kind="pause", text="I think that … it is a good idea.",
            words=[{"word": "that", "start": 1.0, "end": 1.5}, {"word": "idea", "start": 7.0, "end": 7.5}],
            press_ms=9500, pulses=[2000, 4000, 8000], base="ep_1_water_05", cond="water")
    r.line = 5  # цели по очереди: #5 — 2.0 с, #6 — 2.5 с, #7 — 1.5 с
    gaps = r.mid_pauses()
    assert len(gaps) == 1 and gaps[0][1] - gaps[0][0] == pytest.approx(5.0, abs=0.1)
    s = ep.shorten_pauses(r)
    assert s.base == "ep_1_water_05_p20" and s.pause_target_s == 2.0
    cut = (gaps[0][1] - gaps[0][0]) - 2.0
    assert s.duration_ms == pytest.approx(r.duration_ms - cut * 1000, abs=15)
    assert s.last_word_end == pytest.approx(7.5 - cut, abs=0.02)
    assert s.press_ms == pytest.approx(9500 - cut * 1000, abs=20)
    assert len(s.pulses_ms) == 2 and s.pulses_ms[0] == 2000  # пульс 4 с попал в вырез
    g2 = s.mid_pauses()
    assert len(g2) == 1 and g2[0][1] - g2[0][0] == pytest.approx(2.0, abs=0.1)
    assert ep.shorten_pauses(s) is s  # уже короче цели — без изменений
