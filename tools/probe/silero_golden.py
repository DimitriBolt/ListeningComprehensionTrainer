"""S03c: golden-фикстура для обёртки Silero в приложении.

Синтезированный звук (голоса пользователя здесь нет, D69): фраза пробы S03b голосом espeak-ng,
2 с тишины, 2 с белого шума (зерно 3), 16 кГц моно. Вероятности кадров считает обёртка пробы
(`endpoint.Silero`, onnxruntime для Python); Kotlin-тест `SileroGoldenTest` сверяет с ними свою обёртку
на onnxruntime для JVM.

    python tools/probe/silero_golden.py          # нужен espeak-ng, numpy, scipy, onnxruntime

Пишет `android_app/app/src/test/resources/vad/golden.wav` и `golden_probs.txt` (вероятность кадра в строке).
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import endpoint  # noqa: E402
import probe  # noqa: E402

PHRASE = "I wanted to tell you about my trip to the mountains."
OUT = probe.REPO_ROOT / "android_app" / "app" / "src" / "test" / "resources" / "vad"
RATE = 16_000


def synth() -> "object":
    import numpy as np
    from scipy.signal import resample_poly
    with tempfile.TemporaryDirectory() as d:
        wav = Path(d) / "phrase.wav"
        subprocess.run(["espeak-ng", "-v", "en-us", "-s", "150", "-w", str(wav), PHRASE], check=True)
        with wave.open(str(wav)) as w:
            rate = w.getframerate()
            x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64)
    if rate != RATE:
        x = resample_poly(x, RATE, rate)
    x = x / max(1.0, np.abs(x).max()) * 12_000  # громкость обычной речи в микрофоне
    lead = np.zeros(RATE // 2)
    silence = np.zeros(2 * RATE)
    noise = np.random.default_rng(3).normal(0, 600, 2 * RATE)  # ровный шум ≈ 56 дБ по шкале приложения
    pcm = np.concatenate([lead, x, silence, noise])
    return np.clip(np.round(pcm), -32768, 32767).astype(np.int16)


def main() -> None:
    pcm = synth()
    OUT.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT / "golden.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
    probs = endpoint.Silero().probs(pcm)
    (OUT / "golden_probs.txt").write_text("".join(f"{p:.6f}\n" for p in probs), encoding="utf-8")
    speech = sum(p >= 0.5 for p in probs)
    print(f"{len(pcm) / RATE:.2f} с, кадров {len(probs)}, речь (p ≥ 0,5) в {speech} кадрах → {OUT}")


if __name__ == "__main__":
    main()
