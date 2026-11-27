"""Проба S05 без сети: копии из Kotlin (список команд, пометка, числа ModelConfig) и меры."""

import s05


def test_command_list_is_read_from_kotlin():
    p = s05.command_phrases()
    assert set(p) == {"stop", "again", "slower"}
    assert "say again" in p["again"] and "slow down" in p["slower"] and "wait" in p["stop"]
    assert s05.command_max_words() == 3


def test_command_of_is_a_copy_of_voice_command_of():
    # те же случаи, что VoiceCommandTest.kt
    assert s05.command_of("Repeat please:") == "again"
    assert s05.command_of("Slower, please.") == "slower"
    assert s05.command_of("Wait a moment.") == "stop"
    for t in ("What?", "I don't understand.", "Stop the car now.", "Slow food.", "", "Please say it again slowly"):
        assert s05.command_of(t) is None, t


def test_probe_utterances_expect_what_the_app_list_says():
    """Ожидания пробы совпадают со списком приложения на тексте как он сказан (без ошибок распознавания)."""
    for said, want in s05.COMMAND_UTTERANCES:
        assert s05.command_of(said) == want, said


def test_mark_and_model_config_numbers():
    assert s05.interrupted_mark() == "[interrupted by the student]"
    assert float(s05.kotlin_const("SLOWER_SPEECH_SPEED")) == 0.7
    assert s05.kotlin_const("SPEECH_VOICE") == "verse"
    assert int(s05.kotlin_const("CORRECTOR_MAX_OUTPUT_TOKENS")) == 250


def test_repeat_share():
    prev = "You got up early yesterday, so you had time. Many birds sing before the sun comes up. What do birds do?"
    assert s05.repeat_share(prev, "Coffee is nice. What did you see?") == 0
    assert s05.repeat_share(prev, prev) == 1
    assert abs(s05.repeat_share(prev, "Many birds sing before the sun comes up. Nice.") - 1 / 3) < 1e-9


# S05b (D84): те же слова, что VoiceCommandTest.kt
TOLERANT_CASES = {
    "slower": ["Slowware.", "Slowlyer.", "Slow.", "slows", "Slowly!"],
    "again": ["Igain.", "Agane", "Agin.", "Again!", "Egain"],
    "stop": ["Stap.", "Stops.", "Stup", "Stoop.", "Stomp"],
    None: ["Stopped.", "Spot.", "Shop.", "Top.", "Step on it.", "A gain of five.", "Rain.", "Spain.", "Gain.", "Brain.",
           "Again tomorrow, yes.", "Green.", "Agent.", "Slow food.", "It is slow.", "Stop the car.", "Stone."],
}


def test_tolerant_copy_like_kotlin():
    for want, texts in TOLERANT_CASES.items():
        for t in texts:
            assert s05.command_of(t) == want, t
    # правило брифа без условий на буквы — то, от чего защищают условия
    assert s05.command_of("Rain.", tolerant="brief") == "again"
    assert s05.command_of("Shop.", tolerant="brief") == "stop"
    assert s05.command_of("Igain.", tolerant=None) is None
    assert s05.edits("agane", "again") == 2
