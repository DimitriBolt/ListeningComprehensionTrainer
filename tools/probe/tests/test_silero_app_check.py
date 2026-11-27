"""S03c: правило приложения в сверке (`silero_app_check.app_rule`) — копия `VadTurnDetector`, без сети и модели."""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import probe  # noqa: E402

sys.modules.setdefault("probe", probe)
import silero_app_check as chk  # noqa: E402

CFG = probe.REPO_ROOT / "android_app" / "app" / "src" / "main" / "java" / "com" / "example" / "listeningtrainer" / "config" / "ModelConfig.kt"


def run(probs, stop_ms=1e9):
    return chk.app_rule(probs, stop_ms, 0.5, 3000, 200)


def test_silence_from_the_start_ends_after_94_frames_without_speech():
    o = run([0.1] * 200)
    assert o.end_s == 94 * 32 / 1000 and not o.speech_heard


def test_speech_then_silence_ends_3000_ms_after_the_last_speech_frame():
    o = run([0.1] * 10 + [0.9] * 20 + [0.2] * 200)
    assert o.end_s == (30 + 94) * 32 / 1000 and o.speech_heard


def test_heard_needs_200_ms_threshold_is_inclusive():
    assert not run([0.9] * 6 + [0.0] * 200).speech_heard
    assert run([0.5] * 7 + [0.0] * 200).speech_heard


def test_button_before_the_end_means_no_end():
    o = run([0.9] * 20 + [0.1] * 200, stop_ms=2_500)
    assert o.end_s is None and o.speech_heard


def test_settings_are_the_app_settings():
    text = CFG.read_text(encoding="utf-8")
    assert re.search(r"VAD_SPEECH_PROB = 0\.5f", text)
    assert re.search(r"TURN_END_SILENCE_MS = 3000L", text)
    assert re.search(r"TURN_MIN_SPEECH_MS = 200L", text)
    assert re.search(r"VAD_FRAME_SAMPLES = 512\b", text)
