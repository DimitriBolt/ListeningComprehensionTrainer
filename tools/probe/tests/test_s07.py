"""Проба S07 без сети: копия PauseRule против Kotlin (числа ModelConfig, те же случаи, что PauseRuleTest.kt), нарезка, слоги."""

import numpy as np

import s05
import s07


def test_pause_rule_numbers_are_the_app_ones():
    assert float(s05.kotlin_const("PAUSE_GROUP_SHARE")) == 0.25
    assert int(s05.kotlin_const("PAUSE_MAX_MS")) == 3000
    assert int(s05.kotlin_const("PAUSE_WINDOW_MS")) == s07.WINDOW_MS
    assert float(s05.kotlin_const("PAUSE_EDGE_DB")) == s07.EDGE_DB
    assert float(s05.kotlin_const("SLOWER_PAUSE_FACTOR")) == 1.5


def test_pause_ms_like_kotlin():
    share = float(s05.kotlin_const("PAUSE_GROUP_SHARE"))
    assert s07.pause_ms(1000, 700, share) == 1175
    assert s07.pause_ms(1000, 2500, share) == 1625
    assert s07.pause_ms(1000, 2000, share, scale=1.15) == 1725
    assert s07.pause_ms(1600, 4000, share, scale=2.5) == 3000
    assert s07.pause_ms(1000, 40, share, factor=1.5) == 1515
    assert s07.pause_ms(1600, 4000, share, scale=2.5, factor=1.5) == 4500
    assert s07.pause_ms(250, 0, share) == 250


def _tone(ms, amp, rate=24000):
    x = np.full(rate * ms // 1000, amp, np.int16)
    x[1::2] = -amp
    return x


def test_speech_ms_like_kotlin():
    pcm = np.concatenate([np.zeros(7200, np.int16), _tone(1200, 8000), np.zeros(4800, np.int16)])
    assert s07.speech_ms(pcm.tobytes()) == 1200
    pcm = np.concatenate([_tone(100, 40), _tone(500, 8000), _tone(300, 800), _tone(500, 8000), _tone(100, 40)])
    assert s07.speech_ms(pcm.tobytes()) == 1300
    assert s07.speech_ms(b"") == 0
    assert s07.speech_ms(np.zeros(24000, np.int16).tobytes()) == 0


def test_reply_groups_stop_at_the_first_question():
    groups = s07.reply_groups("That sounds lovely! What did you enjoy most at the park? I liked it.")
    assert [g for g, _ in groups] == ["That sounds lovely!", "What did you enjoy", "most at the park?"]
    assert [p for _, p in groups] == [1000, 250, 1000]


def test_syllables_estimate():
    assert s07.syllables("What did you enjoy") == 5
    assert s07.syllables("the park") == 2


def test_faster_items_expect_what_the_app_list_says():
    for said, want in s07.FASTER_ITEMS:
        assert s05.command_of(said) == want, said
