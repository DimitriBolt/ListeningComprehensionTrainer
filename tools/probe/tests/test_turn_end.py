"""Проба S04 (turn-end): судьи, точки commit, симуляция конца реплики — без сети и без записей."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import probe  # noqa: E402

sys.modules.setdefault("probe", probe)
import turn_end as te  # noqa: E402

F = te.FRAME_MS / 1000


def frames(*parts):
    """('s', секунды) — речь, ('q', секунды) — тишина → вероятности кадров 32 мс."""
    out = []
    for kind, sec in parts:
        out += [0.9 if kind == "s" else 0.05] * round(sec / F)
    return out


class FakeText(te.TextSource):
    """Текст, который «пришёл» к моменту t: список (с какого t, текст)."""

    def __init__(self, timeline, final="final"):
        self.timeline = timeline
        self.final = final

    def at(self, t, pause_start, cand_s):
        txt = ""
        for since, text in self.timeline:
            if t >= since:
                txt = text
        return txt, t

    def final_text(self, end_s):
        return self.final

    def teacher_text_ready(self, end_s, ready):
        return end_s + te.STT_S


class NoJ3(te.J3Cache):
    def __init__(self, answers=None, latency=0.0):
        super().__init__()
        self.answers = answers or {}
        self.latency = latency

    def get(self, text):
        if text in self.answers:
            return te.JudgeCall(self.answers[text], self.latency)
        self.missing.add(text)
        return None


def test_j1_needs_end_punctuation_but_not_an_ellipsis():
    assert te.j1("At noon.")
    assert te.j1("Do you like it?")
    assert not te.j1("At noon")
    assert not te.j1("Yesterday I got up early. Um...")
    assert not te.j1("Um…")


def test_j2_last_word():
    assert te.j2("At noon")
    assert te.j2("Yes, I do.") and te.j2("It is") and not te.j2("My favorite food is")
    assert te.j2("I like to read books in the evening.")
    for t in ("Yesterday I", "My favorite food is", "I wanted to tell you about", "I think that", "early. Um…", "and then"):
        assert not te.j2(t), t
    assert not te.j2("")


def test_judge_combinations_and_empty_text():
    j3 = NoJ3({"pizza": True})
    assert te.judge("J0", "", j3) == (True, 0.0)
    assert te.judge("J2", "", j3) == (False, 0.0)          # текста ещё нет — ждать
    assert te.judge("J1+J2", "At noon.", j3) == (True, 0.0)
    assert te.judge("J1+J2", "My food is.", j3) == (False, 0.0)
    assert te.judge("J1|J3", "Pizza.", j3) == (True, 0.0)  # J1 «закончена» — J3 не спрашивают
    assert te.judge("J2>J3", "I think that", j3) == (False, 0.0)
    assert te.judge("J3", "pizza", j3) == (True, 0.0)
    assert te.judge("J3", "unknown", j3) == (None, 0.0) and "unknown" in j3.missing


def test_commit_points_after_every_pause_with_speech():
    p = frames(("s", 1.0), ("q", 1.0), ("s", 0.064), ("q", 1.0), ("s", 1.0), ("q", 2.0))
    pts = te.commit_points_ms(p, 500)
    # после первой речи; шорох 64 мс — не повод резать; после второй речи
    assert len(pts) == 2
    assert abs(pts[0] - 1500) <= 32
    assert abs(pts[1] - (1000 + 1000 + 64 + 1000 + 1000 + 500)) <= 64


def test_j0_ends_on_the_candidate_and_short_answer_is_fast():
    p = frames(("s", 1.0), ("q", 6.0))
    r = te.simulate(p, FakeText([(0, "at noon")]), "J0", 700, 8000, 1000, NoJ3(), last_word_end=1.0)
    assert r.ended_by == "judge"
    assert abs(r.end_s - 1.7) < 0.04
    assert abs(r.equiv_end_s - r.end_s) < 1e-9            # а) учитель через 2,4 с после конца
    assert not r.interrupted and not r.cancels


def test_incomplete_waits_rejudges_and_hits_the_limit():
    p = frames(("s", 1.0), ("q", 9.0))
    r = te.simulate(p, FakeText([(0, "yesterday i")]), "J2", 1000, 5000, 1000, NoJ3(), last_word_end=1.0)
    assert r.ended_by == "limit"
    assert abs(r.end_s - 6.0) < 0.04
    assert len(r.decisions) == 4                            # 1000, 2000, 3000, 4000 мс тишины


def test_fresh_text_on_rejudge_ends_earlier():
    # последнее слово пришло в дельтах только через 1,2 с после конца речи
    p = frames(("s", 1.0), ("q", 6.0))
    src = FakeText([(0, "at"), (2.2, "at noon")])
    r = te.simulate(p, src, "J2", 500, 8000, 500, NoJ3(), last_word_end=1.0)
    assert r.ended_by == "judge"
    assert abs(r.end_s - 2.5) < 0.04                        # решения на 0,5 / 1,0 / 1,5 — «at», на 1,5 с паузы — «at noon»


def test_thinking_pause_judged_incomplete_does_not_interrupt():
    p = frames(("s", 2.0), ("q", 4.5), ("s", 1.5), ("q", 6.0))
    src = FakeText([(0, "yesterday i"), (6.6, "yesterday i went to the shop")])
    r = te.simulate(p, src, "J2", 700, 8000, 1000, NoJ3(), last_word_end=8.0)
    assert not r.interrupted and not r.cancels
    assert abs(r.end_s - 8.7) < 0.05


def test_false_end_then_student_resumes_before_the_teacher_is_cancelled():
    # J0 кончает на 0,7 с паузы; ученик заговорил через 1,5 с паузы — раньше учителя (0,7 + 2,4) → отмена
    p = frames(("s", 2.0), ("q", 1.5), ("s", 1.0), ("q", 4.0))
    r = te.simulate(p, FakeText([(0, "x")]), "J0", 700, 8000, 1000, NoJ3(), last_word_end=4.5)
    assert len(r.cancels) == 1
    assert not r.interrupted
    assert abs(r.end_s - 5.2) < 0.05


def test_false_end_on_a_long_pause_interrupts():
    p = frames(("s", 2.0), ("q", 5.0), ("s", 1.0), ("q", 4.0))
    r = te.simulate(p, FakeText([(0, "x")]), "J0", 700, 8000, 1000, NoJ3(), last_word_end=8.0)
    assert r.interrupted
    assert abs(r.end_s - 2.7) < 0.05


def test_judge_latency_is_part_of_the_delay_and_speech_during_it_cancels_the_decision():
    p = frames(("s", 1.0), ("q", 6.0))
    j3 = NoJ3({"at noon": True}, latency=0.8)
    r = te.simulate(p, FakeText([(0, "at noon")]), "J3", 700, 8000, 1000, j3, last_word_end=1.0)
    assert abs(r.end_s - 2.5) < 0.05
    # заговорил через 1,0 с паузы — вердикт (0,7 + 0,8) не успел: решения нет, ничего не отменяли
    p2 = frames(("s", 1.0), ("q", 1.0), ("s", 1.0), ("q", 6.0))
    r2 = te.simulate(p2, FakeText([(0, "at noon")]), "J3", 700, 8000, 1000, j3, last_word_end=3.0)
    assert not r2.cancels and not r2.interrupted
    assert abs(r2.end_s - 4.5) < 0.05


def test_no_speech_is_silence_after_8_s_and_not_heard():
    r = te.simulate(frames(("q", 9.0)), None, "J0", 700, 8000, 1000, NoJ3(), last_word_end=None)
    assert r.ended_by == "silence_start" and not r.heard and r.end_s is None


def test_commit_text_ready_after_candidate_plus_commit_to_text():
    run = {"commits": [{"t": 1.5, "audio_ms": 1500}],
           "events": [{"type": "input_audio_buffer.committed", "t": 1.55, "item_id": "x"},
                      {"type": "conversation.item.input_audio_transcription.completed", "t": 1.95, "item_id": "x", "transcript": "At noon"}]}
    src = te.CommitText(run, [1500])
    text, ready = src.at(2.0, pause_start=1.0, cand_s=1.0)  # кандидат 1000 мс: кусок готов на 0,45 с позже
    assert text == "At noon"
    assert abs(ready - 2.45) < 1e-6


def test_settings_grid_and_rule():
    s = te.all_settings()
    assert len({x.judge for x in s}) == len(te.JUDGES)
    assert sum(1 for x in s if x.judge == "J0") == len(te.MODES) * len(te.CANDIDATE_MS)
    good = {"all": {"interrupted_paused": 2, "interrupted": 2, "paused_lines": 24, "latency_med": 1.4, "latency_p90": 2.4,
                    "long_end_pct": 10.0, "long_end": 4, "speech_lines": 40, "no_end": 0, "false_speech": 1,
                    "silent_lines": 8, "accuracy": 0.93}}
    assert te.passes(good) == (True, [])
    bad = {"all": dict(good["all"], interrupted_paused=3, interrupted=3, accuracy=0.9)}
    ok, why = te.passes(bad)
    assert not ok and len(why) == 2


def test_padding_keeps_the_recording_and_adds_its_own_noise():
    import numpy as np
    x = (np.arange(16000 * 3) % 100).astype(np.int16)
    y = te.pad_with_own_noise(x, 1.0, 2.0, 5.0)
    assert len(y) == 16000 * 5
    assert (y[: 16000 * 2 - 480] == x[: 16000 * 2 - 480]).all()


def test_accuracy_treats_contractions_as_the_full_form():
    assert te.accuracy("I think that … it is a good idea.", "I think that it's a good idea") == 1.0
    assert te.accuracy("At noon.", "Add nogen") == 0.0


def test_j2_lists_match_the_app():
    import re
    kt = (probe.REPO_ROOT / "android_app/app/src/main/java/com/example/listeningtrainer/conversation/TurnEndRule.kt").read_text(encoding="utf-8")

    def kset(name):
        body = re.search(name + r" = setOf\((.*?)\n    \)", kt, re.S).group(1)
        return set(re.findall(r'"([^"]+)"', body))
    assert kset("UNFINISHED") == te.J2_WORDS
    assert kset("AUXILIARIES") == te.AUXILIARIES
    assert set(re.findall(r'"([^"]+)"', re.search(r"PRONOUNS = setOf\((.*?)\)", kt).group(1))) == te.PRONOUNS
