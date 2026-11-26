"""Проба S03b — конец реплики в шуме (`python tools/probe/probe.py endpoint`).

Сравнивает на записях пользователя (режим «Endpoint recordings (debug)», `tools/pull-recordings.sh`)
детекторы конца реплики. Бриф — `docs/stages/03b-brief.md`, итог — `docs/research/03b-endpoint.md` (+ `.json`):
только числа и тексты сценария, без звука.

Официальная документация, по которой написан код (прочитана 2026-11-26):
  [RTT]   https://developers.openai.com/api/docs/guides/realtime-transcription
          сессия `?intent=transcription`, `session.update` с `type: transcription`, PCM 24 кГц;
          `gpt-live-transcribe` — потоковый текст, детектор только на клиенте (`turn_detection: null`)
          и `input_audio_buffer.commit`; `gpt-transcribe` — текст после закрытой реплики.
  [VAD]   https://developers.openai.com/api/docs/guides/realtime-vad
          `server_vad` (`threshold`, `prefix_padding_ms`, `silence_duration_ms`), `semantic_vad`
          (`eagerness`: low / medium / high / auto = medium); в сессии транскрипции — у моделей с VAD.
  [STT]   https://developers.openai.com/api/docs/guides/speech-to-text
          `timestamp_granularities[]=word` — только `whisper-1` (`response_format=verbose_json`).
  [PRICE] модели: whisper-1 $0.006/мин, gpt-transcribe $0.0045/мин, gpt-live-transcribe $0.017/мин
          (страницы моделей developers.openai.com/api/docs/models/…).

Что меряется (бриф, п. 3):
  * разметка: конец речи — конец последнего слова `whisper-1` (word timestamps); пауза посреди мысли —
    промежуток между словами > 1 с;
  * детекторы на устройстве (без сети, на тех же записях): порог громкости (эмуляция нынешнего
    `SilenceTurnDetector` и другие пороги), Silero VAD (ONNX), WebRTC VAD;
  * серверные: сессия транскрипции `gpt-transcribe` с `server_vad` и `semantic_vad`, звук идёт потоком
    в темпе реального времени; когда готов текст и насколько он точен;
  * сверх брифа: `gpt-live-transcribe` (потоковый текст, конец реплики решает детектор на устройстве) —
    через сколько после `commit` готов текст;
  * нынешний путь: файл (m4a 32 кбит/с, как в приложении) в `gpt-transcribe` после конца записи.

Все ответы API кэшируются в `tools/probe/out/endpoint/cache/` — повторный прогон не тратит деньги.
"""
from __future__ import annotations

import asyncio
import base64
import difflib
import hashlib
import json
import math
import re
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

import probe  # noqa: E402  (тот же модуль, что и probe.py: ключ, Realtime, ProbeError, redact)

# ---------------------------------------------------------------------------------------------
# Пути, модели, цены
# ---------------------------------------------------------------------------------------------

DEFAULT_RECORDINGS = probe.REPO_ROOT / "diagnostics" / "recordings"
OUT = probe.PROBE_DIR / "out" / "endpoint"
CACHE = OUT / "cache"
MODELS_DIR = OUT / "models"
SILERO_MODEL = MODELS_DIR / "silero_vad.onnx"
DOCS_MD = probe.REPO_ROOT / "docs" / "research" / "03b-endpoint.md"
DOCS_JSON = probe.REPO_ROOT / "docs" / "research" / "03b-endpoint.json"

M_LABEL = "whisper-1"              # [STT]: word timestamps только у whisper-1
M_FILE = "gpt-transcribe"          # нынешний путь приложения (ModelConfig.TRANSCRIBE_MODEL)
M_SESSION = "gpt-transcribe"       # сессия транскрипции с server_vad / semantic_vad
M_LIVE = "gpt-live-transcribe"     # [RTT]: потоковый текст, VAD только на клиенте

PRICE_MIN = {M_LABEL: 0.006, M_FILE: 0.0045, M_LIVE: 0.017}

SAMPLE_RATE = 16_000
WINDOW_MS = 80                     # окно громкости приложения (ModelConfig.RECORD_LEVEL_WINDOW_MS)
FALSE_END_TOLERANCE_S = 0.2       # сработал раньше конца речи больше чем на 0,2 с — ложный конец
MID_PAUSE_S = 1.0                  # бриф: пауза посреди мысли — промежуток между словами > 1 с
FILLERS = {"um", "uh", "uhm", "umm", "erm", "er", "hmm", "mm", "ah"}

# Сетки настроек (бриф, п. 3)
THRESHOLD_GRID = [(59.0, 3000, True), (59.0, 3000, False), (56.0, 3000, True), (62.0, 3000, True),
                  (65.0, 3000, True), (59.0, 1500, True), (62.0, 1500, True)]
SILERO_THRESHOLDS = [0.3, 0.5, 0.7]
SILENCE_GRID_MS = [600, 800, 1000, 1250, 1500, 2000]
WEBRTC_MODES = [1, 2, 3]
SERVER_VAD_SILENCE = [500, 700, 1000, 1250, 1500]
SEMANTIC_EAGERNESS = ["low", "medium", "high", "auto"]
LIVE_DELAYS = ["minimal", "low"]
MIN_SPEECH_MS = 250                # меньше — щелчок, не речь (как min_speech_duration_ms у Silero)
LIVE_COMMIT_AFTER_MS = 1000        # live и файл: звук до конца последнего слова + 1 с
MASK_MS = 350                      # маска после пульса (D34, ModelConfig.LISTEN_PULSE_MIC_MASK_MS)

CONDITIONS = ["silence", "water", "street", "steady_noise"]
CONDITION_RU = {"silence": "тишина", "water": "вода", "street": "улица", "steady_noise": "ровный шум"}

# ---------------------------------------------------------------------------------------------
# Записи
# ---------------------------------------------------------------------------------------------


@dataclass
class Rec:
    base: str
    condition: str
    line: int
    kind: str
    text: str
    take: int
    meta: dict
    wav: Path
    pcm: object = None            # numpy int16, 16 кГц
    duration_ms: int = 0
    press_ms: float | None = None  # момент нажатия кнопки во времени звука файла
    pulses_ms: list = field(default_factory=list)
    words: list = field(default_factory=list)   # [{word, start, end}] в секундах; конец последнего — уточнён
    label_text: str = ""
    whisper_end: float | None = None            # конец последнего слова по whisper-1, до уточнения
    refine_note: str = ""

    @property
    def speech(self) -> bool:
        return self.kind != "no_speech"

    @property
    def last_word_end(self) -> float | None:
        return self.words[-1]["end"] if self.words else None

    @property
    def last_word_start(self) -> float | None:
        return self.words[-1]["start"] if self.words else None

    @property
    def first_word_start(self) -> float | None:
        return self.words[0]["start"] if self.words else None

    def mid_pauses(self) -> list[tuple[float, float]]:
        return [(a["end"], b["start"]) for a, b in zip(self.words, self.words[1:]) if b["start"] - a["end"] > MID_PAUSE_S]

    @property
    def stop_ms(self) -> float:
        """Где запись кончилась бы по кнопке (нажатие), иначе — конец файла (предохранитель)."""
        return self.press_ms if self.press_ms is not None else self.duration_ms


def to_audio_ms(meta: dict, wall_rel_ms: float | None) -> float | None:
    """Момент по часам телефона (мс от начала записи) → время звука файла. Равняем по концу записи:
    последнее окно прочитано в `levelWallMs[-1]`, это звук до `durationMs` (первые окна микрофон отдаёт пачкой)."""
    if wall_rel_ms is None:
        return None
    walls = meta.get("levelWallMs") or []
    if not walls:
        return float(wall_rel_ms)
    return float(meta["durationMs"]) - (float(walls[-1]) - float(wall_rel_ms))


def load_wav16(path: Path):
    import numpy as np
    pcm, rate = probe.wav_to_pcm(path.read_bytes())
    a = np.frombuffer(pcm, dtype=np.int16)
    if rate != SAMPLE_RATE:
        raise probe.ProbeError(f"{path.name}: частота {rate}, ждали {SAMPLE_RATE}")
    return a


def load_recordings(folder: Path) -> list[Rec]:
    """Последний дубль каждой пары (условие, реплика)."""
    folder = Path(folder)
    if not folder.is_dir():
        raise probe.ProbeError(f"Нет каталога записей {folder}. Запустите tools/pull-recordings.sh или распакуйте архив туда.")
    best: dict[tuple[str, int], Rec] = {}
    for js in sorted(folder.glob("ep_*.json")):
        meta = json.loads(js.read_text(encoding="utf-8"))
        wav = js.with_suffix(".wav")
        if not wav.is_file():
            continue
        r = Rec(js.stem, meta["condition"], int(meta["line"]), meta["kind"], meta["text"], int(meta["take"]), meta, wav)
        key = (r.condition, r.line)
        if key not in best or (r.meta["startedAtMs"], r.take) > (best[key].meta["startedAtMs"], best[key].take):
            best[key] = r
    recs = sorted(best.values(), key=lambda r: (CONDITIONS.index(r.condition) if r.condition in CONDITIONS else 9, r.line))
    for r in recs:
        r.pcm = load_wav16(r.wav)
        r.duration_ms = int(len(r.pcm) * 1000 / SAMPLE_RATE)
        m = r.meta
        r.press_ms = to_audio_ms(m, m.get("buttonPressMs"))
        r.pulses_ms = [to_audio_ms(m, p) for p in m.get("pulsesMs", [])]
    return recs


# ---------------------------------------------------------------------------------------------
# Текст: нормализация и точность
# ---------------------------------------------------------------------------------------------


def words_of(text: str) -> list[str]:
    t = text.lower().replace("’", "'").replace("…", " ")
    ws = re.findall(r"[a-z0-9']+", t)
    return [w for w in ws if w not in FILLERS]


def accuracy(reference: str, hypothesis: str) -> float | None:
    """Доля слов эталона, распознанных верно (совпадения при выравнивании по LCS); «um» не считается."""
    ref = words_of(reference)
    if not ref:
        return None
    hyp = words_of(hypothesis)
    sm = difflib.SequenceMatcher(a=ref, b=hyp, autojunk=False)
    return sum(b.size for b in sm.get_matching_blocks()) / len(ref)


# ---------------------------------------------------------------------------------------------
# Кэш ответов API
# ---------------------------------------------------------------------------------------------


def cache_path(kind: str, key: str) -> Path:
    h = hashlib.sha1(key.encode()).hexdigest()[:16]
    return CACHE / kind / f"{h}.json"


def cache_get(kind: str, key: str):
    p = cache_path(kind, key)
    return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else None


def cache_put(kind: str, key: str, value) -> None:
    p = cache_path(kind, key)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(value, ensure_ascii=False, indent=1), encoding="utf-8")


def wav_key(r: Rec, extra: str = "") -> str:
    return f"{r.base}|{hashlib.sha1(r.pcm.tobytes()).hexdigest()[:12]}|{extra}"


# ---------------------------------------------------------------------------------------------
# Разметка (whisper-1, word timestamps)
# ---------------------------------------------------------------------------------------------


class Spend:
    """Учёт расходов прогона и предел бюджета."""

    def __init__(self, budget: float):
        self.budget = budget
        self.spent = 0.0
        self.by: dict[str, float] = {}

    def add(self, what: str, usd: float) -> None:
        self.spent += usd
        self.by[what] = self.by.get(what, 0.0) + usd

    def cost(self, what: str, usd: float) -> None:
        """Цена вызова, взятого из кэша этого или прошлого прогона (для итога «сколько стоила проба»)."""
        self.cached = getattr(self, "cached", 0.0) + usd
        self.cached_by = getattr(self, "cached_by", {})
        self.cached_by[what] = self.cached_by.get(what, 0.0) + usd

    def check(self) -> None:
        if self.spent > self.budget:
            raise probe.ProbeError(f"Бюджет ${self.budget:.2f} исчерпан (потрачено ≈ ${self.spent:.3f}). Прогон остановлен.")


def http_transcribe(key: str, audio: bytes, filename: str, fields: list[tuple[str, str]], timeout: float) -> tuple[dict, float]:
    import httpx
    t0 = time.perf_counter()
    r = httpx.post(f"{probe.API_BASE}/audio/transcriptions", headers={"Authorization": f"Bearer {key}"},
                   files={"file": (filename, audio)}, data=probe._multipart_data(fields), timeout=timeout)
    dt = time.perf_counter() - t0
    if r.status_code == 401:
        raise probe.ProbeError(probe.auth_error_text(401))
    if r.status_code >= 400:
        raise probe.ProbeError(probe.redact(f"transcriptions {r.status_code}: {r.text[:300]}"))
    return r.json(), dt


def label(recs: list[Rec], key: str, spend: Spend, timeout: float) -> None:
    for r in recs:
        if not r.speech:
            continue
        ck = wav_key(r, M_LABEL)
        got = cache_get("label", ck)
        if got is None:
            wav = probe.pcm_to_wav(r.pcm.tobytes(), SAMPLE_RATE)
            got, _ = http_transcribe(key, wav, "a.wav", [
                ("model", M_LABEL), ("response_format", "verbose_json"), ("timestamp_granularities[]", "word"),
                ("language", "en"), ("prompt", r.text.replace("…", ""))], timeout)
            cache_put("label", ck, got)
            spend.add("разметка whisper-1", PRICE_MIN[M_LABEL] * len(r.pcm) / SAMPLE_RATE / 60)
        spend.cost("разметка whisper-1", PRICE_MIN[M_LABEL] * len(r.pcm) / SAMPLE_RATE / 60)
        r.words = [{"word": w["word"], "start": float(w["start"]), "end": float(w["end"])} for w in got.get("words", [])]
        r.label_text = got.get("text", "")
        if r.words:
            r.whisper_end = r.words[-1]["end"]
            refined, r.refine_note = refine_end(r)
            r.words[-1]["end"] = refined


def band_db(pcm, frame_ms: int = 10):
    """Энергия кадров 10 мс в полосе речи 300–3400 Гц, дБ (шум воды и машин — во многом вне её)."""
    import numpy as np
    from scipy.signal import butter, sosfilt
    sos = butter(4, [300, 3400], btype="bandpass", fs=SAMPLE_RATE, output="sos")
    y = sosfilt(sos, pcm.astype(np.float64))
    n = SAMPLE_RATE * frame_ms // 1000
    k = len(y) // n
    e = (y[: k * n].reshape(k, n) ** 2).mean(axis=1)
    return 10 * np.log10(np.maximum(e, 1e-3))


def refine_end(r: "Rec") -> tuple[float, str]:
    """Конец речи по энергии в полосе речи. Метки whisper-1 бывают сдвинуты целиком (пилот: «At noon» на
    1,35–1,98 с при речи на 0,4–0,85 с), поэтому whisper задаёт только окно поиска: от начала последнего слова
    − 2 с до его конца + 0,6 с. Шум — 30-й процентиль энергии от конца окна до кнопки (иначе — по всей записи).
    Конец — последний кадр 10 мс громче шума на max(8 дБ, 30 % размаха); кадры пульсов (150 мс) не считаются.
    Запас пик − шум < 10 дБ — оставляем метку whisper и пишем это в заметку."""
    import numpy as np
    w = r.words[-1]
    e = band_db(r.pcm)
    e = np.convolve(e, np.ones(3) / 3, mode="same")  # сглаживание 30 мс
    fr = 0.01
    for p in r.pulses_ms:
        if p is not None:
            e[int(p / 1000 / fr): int((p / 1000 + 0.15) / fr)] = -99
    stop = (r.press_ms or r.duration_ms) / 1000
    lo = max(0.0, w["start"] - 2.0)
    hi = min(stop, w["end"] + 0.6)
    tail = e[int(hi / fr): int(stop / fr)]
    tail = tail[tail > -99]
    if len(tail) < 30:
        tail = e[e > -99]
    floor = float(np.percentile(tail, 30))
    seg = e[int(lo / fr): int(hi / fr)]
    if len(seg) == 0:
        return w["end"], "пустое окно — метка whisper"
    peak = float(seg.max())
    if peak - floor < 10:
        return w["end"], f"мало запаса над шумом ({peak - floor:.0f} дБ) — метка whisper"
    thr = floor + max(8.0, 0.3 * (peak - floor))
    loud = np.nonzero(seg > thr)[0]
    end = (int(lo / fr) + int(loud[-1]) + 1) * fr
    return round(end, 3), f"энергия: шум {floor:.0f} дБ, пик {peak:.0f} дБ, конец {end:.2f} с (whisper {w['end']:.2f} с)"


# ---------------------------------------------------------------------------------------------
# Детекторы на устройстве (без сети)
# ---------------------------------------------------------------------------------------------


@dataclass
class Outcome:
    """Что сделал детектор на одной записи. Времена — секунды звука файла."""
    end_s: float | None          # первое срабатывание «реплика кончилась» до кнопки (None — не сработал)
    speech_heard: bool           # услышал ли речь
    speech_start_s: float | None = None
    ends_s: list = field(default_factory=list)  # все срабатывания (серверные: каждая закрытая реплика)
    text: str | None = None
    text_ready_s: float | None = None  # когда готов текст реплики с последним словом (время звука)
    note: str = ""


def window_db(pcm) -> list[float]:
    """Громкость окон 80 мс по шкале приложения (PcmLevel: 20·lg RMS в единицах отсчёта)."""
    import numpy as np
    n = SAMPLE_RATE * WINDOW_MS // 1000
    k = len(pcm) // n
    x = pcm[: k * n].astype(np.float64).reshape(k, n)
    rms = np.sqrt((x * x).mean(axis=1))
    with np.errstate(divide="ignore"):
        db = np.where(rms > 0, 20 * np.log10(np.maximum(rms, 1e-9)), 0.0)
    return db.tolist()


def masked_windows(pulses_ms: list, n_windows: int, mask_ms: float = MASK_MS) -> set[int]:
    """Окна, пересекающиеся с [пульс, пульс + маска) — как PulseEchoGate."""
    out = set()
    for p in pulses_ms:
        if p is None:
            continue
        for i in range(n_windows):
            a, b = i * WINDOW_MS, (i + 1) * WINDOW_MS
            if b > p and a < p + mask_ms:
                out.add(i)
    return out


def threshold_detector(db: list[float], stop_ms: float, threshold: float, silence_ms: int,
                       masked: set[int] = frozenset()) -> Outcome:
    """Эмуляция SilenceTurnDetector: время окна = конец окна по звуку; отсчёт тишины и от начала записи."""
    last = 0.0
    heard = False
    start = None
    for i, d in enumerate(db):
        now = (i + 1) * WINDOW_MS
        if now > stop_ms:
            break
        level = 0.0 if i in masked else d
        if level > threshold:
            last = now
            if not heard:
                start = i * WINDOW_MS / 1000
            heard = True
        elif now - last > silence_ms:
            return Outcome(now / 1000, heard, start, [now / 1000])
    return Outcome(None, heard, start)


def segment_state_machine(frame_ms: float, probs: list[float], threshold: float, silence_ms: int,
                          stop_ms: float, neg_delta: float = 0.15, min_speech_ms: int = MIN_SPEECH_MS) -> Outcome:
    """Конец реплики по вероятности речи кадров (логика VADIterator Silero): речь — prob ≥ threshold;
    после речи кадры < threshold − neg_delta копят тишину; тишина ≥ silence_ms → конец. Отрезок речи короче
    min_speech_ms (щелчок, пульс) речью не считается. До первой речи конец не наступает (молчание решает
    отдельный предел, как «не услышал»)."""
    neg = threshold - neg_delta
    triggered = False
    seg_start = None
    temp_end = None
    heard = False
    first_start = None
    for i, p in enumerate(probs):
        t = (i + 1) * frame_ms
        if t > stop_ms:
            break
        if p >= threshold:
            temp_end = None
            if not triggered:
                triggered, seg_start = True, i * frame_ms
            if not heard and t - seg_start >= min_speech_ms:
                heard, first_start = True, seg_start / 1000
            continue
        if triggered and p < neg:
            if temp_end is None:
                temp_end = t
            if not heard and temp_end - seg_start < min_speech_ms and t - temp_end >= frame_ms * 3:
                triggered, seg_start, temp_end = False, None, None  # щелчок, не речь
                continue
            if heard and t - temp_end >= silence_ms:
                return Outcome(t / 1000, True, first_start, [t / 1000])
    return Outcome(None, heard, first_start)


def fetch_silero() -> Path:
    """Скачивает колесо silero-vad (MIT) без зависимостей и достаёт из него ONNX-модель."""
    import tempfile
    import zipfile
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as d:
        subprocess.run([sys.executable, "-m", "pip", "download", "-q", "--no-deps", "silero-vad", "-d", d], check=True)
        whl = next(Path(d).glob("silero_vad-*.whl"))
        with zipfile.ZipFile(whl) as z:
            SILERO_MODEL.write_bytes(z.read("silero_vad/data/silero_vad.onnx"))
    return SILERO_MODEL


class Silero:
    """Silero VAD ONNX (v6, MIT) — вероятность речи по кадрам 512 отсчётов (32 мс) при 16 кГц."""
    FRAME = 512
    CONTEXT = 64

    def __init__(self, model: Path = SILERO_MODEL):
        import onnxruntime as ort
        if not Path(model).is_file():
            print("Модели Silero нет — скачиваю колесо silero-vad (pip download) …")
            fetch_silero()
        opts = ort.SessionOptions()
        opts.inter_op_num_threads = 1
        opts.intra_op_num_threads = 1
        self.session = ort.InferenceSession(str(model), sess_options=opts, providers=["CPUExecutionProvider"])

    def probs(self, pcm) -> list[float]:
        import numpy as np
        x = pcm.astype(np.float32) / 32768.0
        state = np.zeros((2, 1, 128), dtype=np.float32)
        ctx = np.zeros((1, self.CONTEXT), dtype=np.float32)
        sr = np.array(SAMPLE_RATE, dtype=np.int64)
        out = []
        for i in range(0, len(x) - self.FRAME + 1, self.FRAME):
            chunk = np.concatenate([ctx, x[i:i + self.FRAME][None, :]], axis=1)
            p, state = self.session.run(None, {"input": chunk, "state": state, "sr": sr})
            ctx = chunk[:, -self.CONTEXT:]
            out.append(float(p[0][0]))
        return out


def webrtc_flags(pcm, mode: int, frame_ms: int = 30) -> list[float]:
    import webrtcvad
    v = webrtcvad.Vad(mode)
    n = SAMPLE_RATE * frame_ms // 1000
    b = pcm.tobytes()
    return [1.0 if v.is_speech(b[i * 2:(i + n) * 2], SAMPLE_RATE) else 0.0 for i in range(0, len(pcm) - n + 1, n)]


def mask_frames(probs: list[float], frame_ms: float, pulses_ms: list, mask_ms: float = MASK_MS) -> list[float]:
    out = list(probs)
    for i in range(len(out)):
        a, b = i * frame_ms, (i + 1) * frame_ms
        if any(p is not None and b > p and a < p + mask_ms for p in pulses_ms):
            out[i] = 0.0
    return out


def run_device_detectors(recs: list[Rec]) -> dict[str, dict[str, Outcome]]:
    """setting → base → Outcome для всех детекторов на устройстве."""
    res: dict[str, dict[str, Outcome]] = {}
    sil = Silero()
    for r in recs:
        db = window_db(r.pcm)
        msk = masked_windows(r.pulses_ms, len(db))
        for thr, s_ms, use_mask in THRESHOLD_GRID:
            name = f"threshold {thr:.0f} dB / {s_ms} ms" + ("" if use_mask else ", no mask")
            res.setdefault(name, {})[r.base] = threshold_detector(db, r.stop_ms, thr, s_ms, msk if use_mask else set())
        probs = sil.probs(r.pcm)
        probs_m = mask_frames(probs, 32, r.pulses_ms)
        for t in SILERO_THRESHOLDS:
            for s_ms in SILENCE_GRID_MS:
                for m, pr in (("", probs), (", mask", probs_m)):
                    res.setdefault(f"silero p≥{t} / {s_ms} ms{m}", {})[r.base] = segment_state_machine(32, pr, t, s_ms, r.stop_ms)
        for mode in WEBRTC_MODES:
            fl = webrtc_flags(r.pcm, mode)
            for s_ms in SILENCE_GRID_MS:
                res.setdefault(f"webrtc mode {mode} / {s_ms} ms", {})[r.base] = segment_state_machine(30, fl, 0.5, s_ms, r.stop_ms, neg_delta=0.0)
    return res


# ---------------------------------------------------------------------------------------------
# Серверные варианты: сессия транскрипции, звук потоком в темпе реального времени
# ---------------------------------------------------------------------------------------------


def to24k(pcm) -> bytes:
    import numpy as np
    from scipy.signal import resample_poly
    y = resample_poly(pcm.astype(np.float64), 3, 2)
    return np.clip(np.round(y), -32768, 32767).astype(np.int16).tobytes()


def server_session(setting: str) -> dict:
    kind, _, value = setting.partition(":")
    td = ({"type": "server_vad", "silence_duration_ms": int(value)} if kind == "server_vad"
          else {"type": "semantic_vad", "eagerness": value})
    return {"type": "transcription", "audio": {"input": {
        "format": {"type": "audio/pcm", "rate": probe.RT_RATE},
        "transcription": {"model": M_SESSION, "languages": ["en"]},
        "turn_detection": td}}}


def live_session(delay: str) -> dict:
    return {"type": "transcription", "audio": {"input": {
        "format": {"type": "audio/pcm", "rate": probe.RT_RATE},
        "transcription": {"model": M_LIVE, "languages": ["en"], "delay": delay},
        "turn_detection": None}}}


CHUNK_MS = 100


async def stream_session(key: str, timeout: float, session: dict, pcm24: bytes, send_ms: float | None,
                         commit: bool, tail_s: float) -> dict:
    """Шлёт звук кусками по 100 мс в темпе реального времени до send_ms (или весь), при commit — затем
    `input_audio_buffer.commit`; слушает события ещё tail_s. Каждое событие — с временем от начала звука."""
    events: list[dict] = []
    out = {"events": events, "error": None, "accepted": None}
    bytes_per_ms = probe.RT_RATE * 2 // 1000
    total = len(pcm24) if send_ms is None else min(len(pcm24), int(send_ms) * bytes_per_ms)
    try:
        async with probe.Realtime(key, timeout, probe.REALTIME_TRANSCRIPTION_URL) as rt:
            got = await rt.update_session(session)
            out["accepted"] = (got.get("audio", {}).get("input", {}) or {})
            t0 = time.perf_counter()
            stop = asyncio.Event()

            async def reader():
                while not stop.is_set():
                    try:
                        ev = await rt.recv(0.25)
                    except (asyncio.TimeoutError, TimeoutError):
                        continue
                    t = ev.get("type", "")
                    if t in ("input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped",
                             "input_audio_buffer.committed", "conversation.item.input_audio_transcription.completed",
                             "conversation.item.input_audio_transcription.delta",
                             "conversation.item.input_audio_transcription.failed", "error"):
                        rec = {"type": t, "t": time.perf_counter() - t0}
                        for k in ("audio_start_ms", "audio_end_ms", "item_id", "transcript", "delta", "usage", "error"):
                            if k in ev:
                                rec[k] = ev[k]
                        events.append(rec)

            task = asyncio.create_task(reader())
            step = CHUNK_MS * bytes_per_ms
            sent = 0
            while sent < total:
                chunk = pcm24[sent:min(total, sent + step)]
                await rt.send({"type": "input_audio_buffer.append", "audio": base64.b64encode(chunk).decode()})
                sent += len(chunk)
                due = t0 + sent / bytes_per_ms / 1000
                await asyncio.sleep(max(0.0, due - time.perf_counter()))
            out["sent_s"] = sent / bytes_per_ms / 1000
            if commit:
                out["commit_t"] = time.perf_counter() - t0
                await rt.send({"type": "input_audio_buffer.commit"})
            end = time.perf_counter() + tail_s
            while time.perf_counter() < end:
                if commit and any(e["type"].endswith("completed") or e["type"].endswith("failed") for e in events):
                    break
                await asyncio.sleep(0.05)
            stop.set()
            await task
    except probe.ProbeError as e:
        if "401" in str(e):
            raise
        out["error"] = str(e)
    except Exception as e:  # сеть, разрыв
        out["error"] = probe.redact(f"{type(e).__name__}: {e}")
    return out


def server_outcome(r: Rec, run: dict) -> Outcome:
    """События сессии → срабатывания. Детектор «сработал» в момент прихода speech_stopped (звук идёт в
    реальном времени, поэтому время прихода = время звука). Срабатывания после кнопки не считаются."""
    ev = run.get("events", [])
    stop_s = r.stop_ms / 1000
    starts = [e for e in ev if e["type"] == "input_audio_buffer.speech_started"]
    stops = [e for e in ev if e["type"] == "input_audio_buffer.speech_stopped"]
    ends = [e["t"] for e in stops]
    end = next((t for t in ends if t <= stop_s), None)
    texts = {}
    done_t = {}
    for e in ev:
        if e["type"] == "conversation.item.input_audio_transcription.completed":
            texts[e.get("item_id")] = e.get("transcript", "")
            done_t[e.get("item_id")] = e["t"]
    committed = [e for e in ev if e["type"] == "input_audio_buffer.committed"]
    order = [c.get("item_id") for c in committed]
    text = " ".join(texts.get(i, "") for i in order).strip() if order else " ".join(texts.values()).strip()
    # текст с последним словом: реплика, закрытая первой после конца последнего слова
    ready = None
    lw = r.last_word_end
    if lw is not None:
        s_after = next((s for s in stops if s["t"] >= lw - 0.05), None)
        if s_after is not None:
            c = next((c for c in committed if c["t"] >= s_after["t"] - 0.01), None)
            if c is not None and c.get("item_id") in done_t:
                ready = done_t[c["item_id"]]
    note = run.get("error") or ""
    return Outcome(end, bool(starts) and starts[0]["t"] <= stop_s, starts[0]["t"] if starts else None,
                   ends, text or None, ready, note)


async def run_server(recs: list[Rec], key: str, timeout: float, spend: Spend, parallel: int) -> dict[str, dict[str, Outcome]]:
    jobs = []
    for setting in [f"server_vad:{v}" for v in SERVER_VAD_SILENCE] + [f"semantic_vad:{e}" for e in SEMANTIC_EAGERNESS]:
        for r in recs:
            jobs.append((setting, r))
    sem = asyncio.Semaphore(parallel)
    raw: dict[tuple[str, str], dict] = {}

    async def one(setting: str, r: Rec):
        ck = wav_key(r, setting)
        got = cache_get("server", ck)
        if got is None:
            async with sem:
                spend.check()
                got = await stream_session(key, timeout, server_session(setting), to24k(r.pcm), None, False, 3.0)
                if not got.get("error"):
                    cache_put("server", ck, got)
                spend.add(f"сессия {M_SESSION} ({setting.split(':')[0]})", PRICE_MIN[M_SESSION] * r.duration_ms / 60000)
        spend.cost(f"сессия {M_SESSION} ({setting.split(':')[0]})", PRICE_MIN[M_SESSION] * r.duration_ms / 60000)
        raw[(setting, r.base)] = got

    await asyncio.gather(*(one(s, r) for s, r in jobs))
    res: dict[str, dict[str, Outcome]] = {}
    for (setting, base), run in raw.items():
        r = next(x for x in recs if x.base == base)
        kind, _, v = setting.partition(":")
        name = f"server_vad {v} ms" if kind == "server_vad" else f"semantic_vad {v}"
        res.setdefault(name, {})[base] = server_outcome(r, run)
    return res


async def run_live(recs: list[Rec], key: str, timeout: float, spend: Spend, parallel: int) -> dict[str, dict]:
    """gpt-live-transcribe: звук потоком до (последнее слово + 1 с), затем commit; меряем commit → текст."""
    sem = asyncio.Semaphore(parallel)
    res: dict[str, dict] = {}

    async def one(delay: str, r: Rec):
        send_ms = r.last_word_end * 1000 + LIVE_COMMIT_AFTER_MS
        ck = wav_key(r, f"live:{delay}:{send_ms:.0f}")
        got = cache_get("live", ck)
        if got is None:
            async with sem:
                spend.check()
                got = await stream_session(key, timeout, live_session(delay), to24k(r.pcm), send_ms, True, 15.0)
                if not got.get("error"):
                    cache_put("live", ck, got)
                spend.add(f"{M_LIVE}", PRICE_MIN[M_LIVE] * send_ms / 60000)
        spend.cost(f"{M_LIVE}", PRICE_MIN[M_LIVE] * send_ms / 60000)
        done = next((e for e in got.get("events", []) if e["type"].endswith("transcription.completed")), None)
        first_delta = next((e for e in got.get("events", []) if e["type"].endswith("transcription.delta")), None)
        res.setdefault(f"live {delay}", {})[r.base] = {
            "text": done.get("transcript") if done else None,
            "commit_to_text_s": (done["t"] - got["commit_t"]) if done and "commit_t" in got else None,
            "first_delta_s": first_delta["t"] if first_delta else None,
            "error": got.get("error"),
        }

    await asyncio.gather(*(one(d, r) for d in LIVE_DELAYS for r in recs if r.speech and r.words))
    return res


def m4a(pcm, upto_ms: float) -> bytes:
    """Как приложение: AAC .m4a 32 кбит/с моно 16 кГц."""
    n = int(upto_ms * SAMPLE_RATE / 1000)
    wav = probe.pcm_to_wav(pcm[:n].tobytes(), SAMPLE_RATE)
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        dst = Path(d) / "a.m4a"  # mp4 пишется только в файл (нужна перемотка)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "wav", "-i", "pipe:0", "-c:a", "aac", "-b:a", "32k",
                        "-ac", "1", "-ar", "16000", str(dst)], input=wav, capture_output=True, check=True)
        return dst.read_bytes()


def run_file(recs: list[Rec], key: str, timeout: float, spend: Spend) -> dict[str, dict]:
    """Нынешний путь: файл m4a до (последнее слово + 1 с) → gpt-transcribe; время запроса и текст."""
    out = {}
    for r in recs:
        if not (r.speech and r.words):
            continue
        upto = r.last_word_end * 1000 + LIVE_COMMIT_AFTER_MS
        ck = wav_key(r, f"file:{upto:.0f}")
        got = cache_get("file", ck)
        if got is None:
            spend.check()
            body, dt = http_transcribe(key, m4a(r.pcm, upto), "a.m4a", [("model", M_FILE), ("languages[]", "en")], timeout)
            got = {"text": body.get("text", ""), "request_s": dt}
            cache_put("file", ck, got)
            spend.add(f"файл {M_FILE}", PRICE_MIN[M_FILE] * upto / 60000)
        spend.cost(f"файл {M_FILE}", PRICE_MIN[M_FILE] * upto / 60000)
        out[r.base] = got
    return out


# ---------------------------------------------------------------------------------------------
# Меры и правило выбора
# ---------------------------------------------------------------------------------------------


def pct(values: list[float], q: float) -> float | None:
    if not values:
        return None
    v = sorted(values)
    k = (len(v) - 1) * q
    f, c = math.floor(k), math.ceil(k)
    return v[f] if f == c else v[f] + (v[c] - v[f]) * (k - f)


def evaluate(recs: list[Rec], outcomes: dict[str, Outcome]) -> dict:
    """Меры одной настройки: по условиям и сводкой (бриф, п. 3, таблица мер)."""
    def block(rs: list[Rec]) -> dict:
        speech = [r for r in rs if r.speech and r.words]
        silent = [r for r in rs if not r.speech]
        paused = [r for r in speech if r.kind in ("pause", "long")]
        false_end, no_end, lat, ready, acc = [], [], [], [], []
        for r in speech:
            o = outcomes.get(r.base)
            if o is None:
                continue
            if o.end_s is None:
                no_end.append(r.base)
            elif o.end_s < r.last_word_end - FALSE_END_TOLERANCE_S:
                false_end.append(r.base)
            else:
                lat.append(o.end_s - r.last_word_end)
            if o.text_ready_s is not None:
                ready.append(o.text_ready_s - r.last_word_end)
            if o.text is not None:
                a = accuracy(r.text, o.text)
                if a is not None:
                    acc.append(a)
        false_speech = [r.base for r in silent if outcomes.get(r.base) and outcomes[r.base].speech_heard]
        n = len(speech)
        return {
            "speech_lines": n,
            "false_end": len(false_end), "false_end_pct": 100 * len(false_end) / n if n else None,
            "false_end_paused": sum(1 for r in paused if r.base in false_end), "paused_lines": len(paused),
            "no_end": len(no_end), "no_end_pct": 100 * len(no_end) / n if n else None,
            "latency_med": pct(lat, 0.5), "latency_p90": pct(lat, 0.9),
            "false_speech": len(false_speech), "silent_lines": len(silent),
            "text_ready_med": pct(ready, 0.5), "text_ready_p90": pct(ready, 0.9),
            "accuracy": statistics.mean(acc) if acc else None,
            "false_end_lines": [b for b in false_end], "no_end_lines": [b for b in no_end],
        }
    out = {"all": block(recs)}
    for c in CONDITIONS:
        rs = [r for r in recs if r.condition == c]
        if rs:
            out[c] = block(rs)
    return out


def passes(m: dict) -> tuple[bool, list[str]]:
    """Правило брифа п. 4 по каждому условию. «Не больше 1 из 8» читаем как: ложный конец — не больше 1 на
    условие среди реплик с паузой и длинных (их 6 на условие); ложная речь — не больше 1 из 8 записей без
    речи (2 на условие × 4) всего."""
    why = []
    for c in CONDITIONS:
        b = m.get(c)
        if not b:
            why.append(f"{c}: нет записей")
            continue
        if b["no_end"] > 0:
            why.append(f"{CONDITION_RU[c]}: нет конца {b['no_end']}")
        if b["false_end_paused"] > 1:
            why.append(f"{CONDITION_RU[c]}: ложный конец {b['false_end_paused']} из {b['paused_lines']}")
        if b["latency_med"] is None or b["latency_med"] > 1.5:
            why.append(f"{CONDITION_RU[c]}: задержка медиана {fmt(b['latency_med'])}")
        if b["latency_p90"] is None or b["latency_p90"] > 2.5:
            why.append(f"{CONDITION_RU[c]}: задержка p90 {fmt(b['latency_p90'])}")
    if m["all"]["false_speech"] > 1:
        why.append(f"ложная речь {m['all']['false_speech']} из {m['all']['silent_lines']}")
    return not why, why


def fmt(v, nd: int = 2, suffix: str = "") -> str:
    return "—" if v is None else f"{v:.{nd}f}{suffix}"


# ---------------------------------------------------------------------------------------------
# Графики для ручной сверки разметки
# ---------------------------------------------------------------------------------------------


def plot(r: Rec, path: Path, outcomes: dict[str, Outcome] | None = None) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np
    t = np.arange(len(r.pcm)) / SAMPLE_RATE
    db = window_db(r.pcm)
    fig, (a1, a2) = plt.subplots(2, 1, figsize=(14, 5.5), sharex=True)
    a1.plot(t, r.pcm / 32768, lw=0.3, color="#456")
    for w in r.words:
        a1.axvspan(w["start"], w["end"], color="#9c6", alpha=0.35)
        a1.text((w["start"] + w["end"]) / 2, 0.9, w["word"].strip(), ha="center", fontsize=8, rotation=30)
    for p in r.pulses_ms:
        a1.axvline(p / 1000, color="#c33", lw=0.8, ls=":")
    if r.press_ms is not None:
        a1.axvline(r.press_ms / 1000, color="k", lw=1.2)
    a1.set_ylim(-1, 1.1)
    a1.set_title(f"{r.base}  [{r.condition} #{r.line} {r.kind}]  «{r.text}»  — зелёное: слова whisper-1, красные: пульсы, чёрная: кнопка", fontsize=9)
    tw = (np.arange(len(db)) + 1) * WINDOW_MS / 1000
    a2.step(tw, db, where="post", color="#246")
    a2.axhline(59, color="#c90", lw=0.8)
    if r.last_word_end:
        a2.axvline(r.last_word_end, color="#393", lw=1.5, label="конец речи (уточнён)")
    if r.whisper_end:
        a2.axvline(r.whisper_end, color="#393", lw=1, ls=":", label="конец по whisper")
    eb = band_db(r.pcm)
    a3 = a2.twinx()
    a3.plot(np.arange(len(eb)) * 0.01, eb, lw=0.5, color="#a5a")
    a3.set_ylabel("полоса речи, дБ (10 мс)", color="#a5a")
    if outcomes:
        for i, (name, o) in enumerate(outcomes.items()):
            if o.end_s:
                a2.axvline(o.end_s, color=f"C{i + 3}", lw=1, ls="--", label=name)
        pass
    a2.legend(fontsize=7, loc="upper right")
    a2.set_ylabel("дБ (окно 80 мс)")
    a2.set_xlabel("с")
    fig.tight_layout()
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=80)
    plt.close(fig)


# ---------------------------------------------------------------------------------------------
# Оценка цены и прогон
# ---------------------------------------------------------------------------------------------


def estimate(recs: list[Rec]) -> dict:
    total_min = sum(r.duration_ms for r in recs) / 60000
    speech_min = sum(r.duration_ms for r in recs if r.speech) / 60000
    # live и файл — звук до последнего слова + 1 с ≈ 70 % записи с речью (оценка сверху — 100 %)
    e = {
        "разметка whisper-1": PRICE_MIN[M_LABEL] * speech_min,
        f"сессии {M_SESSION} (9 настроек)": PRICE_MIN[M_SESSION] * total_min * (len(SERVER_VAD_SILENCE) + len(SEMANTIC_EAGERNESS)),
        f"{M_LIVE} ({len(LIVE_DELAYS)} настройки)": PRICE_MIN[M_LIVE] * speech_min * len(LIVE_DELAYS),
        f"файл {M_FILE}": PRICE_MIN[M_FILE] * speech_min,
    }
    e["итого"] = sum(e.values())
    e["минут звука"] = total_min
    return e


def probe_endpoint(ctx) -> bool:
    args = ctx.args
    folder = Path(args.recordings) if getattr(args, "recordings", None) else DEFAULT_RECORDINGS
    recs = load_recordings(folder)
    if not recs:
        raise probe.ProbeError(f"В {folder} нет записей ep_*.json + .wav.")
    by_c = {c: sum(1 for r in recs if r.condition == c) for c in CONDITIONS}
    print(f"Записей (последний дубль): {len(recs)} — " + ", ".join(f"{c} {n}" for c, n in by_c.items()))
    est = estimate(recs)
    print("Оценка цены (сверху):")
    for k, v in est.items():
        print(f"  {k}: " + (f"{v:.1f}" if k == "минут звука" else f"${v:.3f}"))
    if not getattr(args, "yes", False) and not getattr(args, "offline", False):
        if input(f"Запустить? Бюджет ${args.endpoint_budget:.2f} [y/N] ").strip().lower() != "y":
            print("Отменено.")
            return False
    spend = Spend(args.endpoint_budget)
    key = ctx.key
    t0 = time.time()
    print("1/5 разметка whisper-1 …")
    label(recs, key, spend, ctx.timeout)
    print("2/5 детекторы на устройстве (порог, Silero, WebRTC) …")
    results = run_device_detectors(recs)
    live, filed = {}, {}
    if not getattr(args, "offline", False):
        print(f"3/5 серверные сессии ({len(recs)} записей × 9 настроек, в реальном времени, по {args.parallel} сразу) …")
        results.update(asyncio.run(run_server(recs, key, ctx.timeout, spend, args.parallel)))
        print("4/5 gpt-live-transcribe …")
        live = asyncio.run(run_live(recs, key, ctx.timeout, spend, args.parallel))
        print("5/5 нынешний путь: файл в gpt-transcribe …")
        filed = run_file(recs, key, ctx.timeout, spend)
    report(recs, results, live, filed, spend, est, time.time() - t0)
    shown = [n for n in ("threshold 59 dB / 3000 ms", "silero p≥0.5 / 1000 ms", "webrtc mode 3 / 1000 ms",
                         "server_vad 1000 ms", "semantic_vad high") if n in results]
    for r in recs:
        plot(r, OUT / "plots" / f"{r.base}.png", {n: results[n][r.base] for n in shown if r.base in results[n]})
    print(f"Графики для ручной сверки разметки: {OUT / 'plots'}")
    return True


# ---------------------------------------------------------------------------------------------
# Отчёт
# ---------------------------------------------------------------------------------------------


def family(name: str) -> str:
    for f in ("threshold", "silero", "webrtc", "server_vad", "semantic_vad"):
        if name.startswith(f):
            return f
    return "other"


def rank_key(m: dict, ok: bool) -> tuple:
    a = m["all"]
    return (not ok, a["no_end"], a["false_end"] + a["false_speech"], a["latency_p90"] if a["latency_p90"] is not None else 99,
            a["latency_med"] if a["latency_med"] is not None else 99)


def report(recs, results, live, filed, spend, est, elapsed) -> None:
    metrics = {name: evaluate(recs, outs) for name, outs in results.items()}
    verdict = {name: passes(m) for name, m in metrics.items()}
    # точность и «текст готов» нынешнего пути и live
    speech = [r for r in recs if r.speech and r.words]

    def live_stats(name):
        rows = live.get(name, {})
        acc = [accuracy(r.text, rows[r.base]["text"] or "") for r in speech if r.base in rows]
        lat = [rows[r.base]["commit_to_text_s"] for r in speech if r.base in rows and rows[r.base]["commit_to_text_s"] is not None]
        miss = sum(1 for r in speech if r.base in rows and rows[r.base]["text"] is None)
        per_c = {}
        for c in CONDITIONS:
            rc = [r for r in speech if r.condition == c and r.base in rows]
            per_c[c] = {"accuracy": statistics.mean([accuracy(r.text, rows[r.base]["text"] or "") for r in rc]) if rc else None,
                        "commit_med": pct([rows[r.base]["commit_to_text_s"] for r in rc if rows[r.base]["commit_to_text_s"] is not None], 0.5)}
        return {"accuracy": statistics.mean(acc) if acc else None, "commit_med": pct(lat, 0.5), "commit_p90": pct(lat, 0.9),
                "no_text": miss, "by_condition": per_c}

    file_acc = [accuracy(r.text, filed[r.base]["text"]) for r in speech if r.base in filed]
    file_req = [filed[r.base]["request_s"] for r in speech if r.base in filed]
    file_stats = {"accuracy": statistics.mean(file_acc) if file_acc else None, "request_med": pct(file_req, 0.5),
                  "request_p90": pct(file_req, 0.9),
                  "by_condition": {c: {"accuracy": statistics.mean([accuracy(r.text, filed[r.base]["text"]) for r in speech
                                                                    if r.condition == c and r.base in filed] or [float("nan")])}
                                   for c in CONDITIONS}}
    live_s = {name: live_stats(name) for name in live}

    data = {
        "date": time.strftime("%Y-%m-%d %H:%M"),
        "recordings": [{"base": r.base, "condition": r.condition, "line": r.line, "kind": r.kind, "text": r.text, "take": r.take,
                        "duration_ms": r.duration_ms, "press_ms": r.press_ms, "pulses_ms": r.pulses_ms,
                        "last_word_end_s": r.last_word_end, "first_word_start_s": r.first_word_start,
                        "mid_pauses_s": r.mid_pauses(), "label_text": r.label_text,
                        "whisper_end_s": r.whisper_end, "refine_note": r.refine_note,
                        "words": r.words} for r in recs],
        "metrics": metrics,
        "verdict": {k: {"ok": v[0], "why": v[1]} for k, v in verdict.items()},
        "outcomes": {name: {b: o.__dict__ for b, o in outs.items()} for name, outs in results.items()},
        "live": live, "live_stats": live_s, "file": filed, "file_stats": file_stats,
        "spend": {"this_run": spend.spent, "this_run_by": spend.by, "probe_total": getattr(spend, "cached", 0.0),
                  "probe_total_by": getattr(spend, "cached_by", {}), "estimate": est}, "elapsed_s": elapsed,
    }
    DOCS_JSON.write_text(json.dumps(data, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    DOCS_MD.write_text(render_md(recs, metrics, verdict, live_s, file_stats, spend, est, elapsed), encoding="utf-8")
    print(f"Итог: {DOCS_MD}\nСырые данные: {DOCS_JSON}\nПотрачено в этом прогоне ≈ ${spend.spent:.3f}, "
          f"цена всех вызовов пробы ≈ ${getattr(spend, 'cached', 0.0):.3f}")


def metric_row(name: str, m: dict, ok: bool) -> str:
    a = m["all"]
    return (f"| {'✓' if ok else '✗'} {name} | {fmt(a['false_end_pct'], 0, ' %')} | {fmt(a['latency_med'])} | {fmt(a['latency_p90'])} | "
            f"{fmt(a['no_end_pct'], 0, ' %')} | {a['false_speech']} из {a['silent_lines']} | {fmt(a['text_ready_med'])} | "
            f"{fmt(a['text_ready_p90'])} | {fmt(a['accuracy'] * 100 if a['accuracy'] is not None else None, 0, ' %')} |")


def condition_table(name: str, m: dict) -> list[str]:
    L = [f"**{name}**", "", "| Условие | ложный конец (с паузой) | задержка мед / p90, с | нет конца | ложная речь | текст готов мед, с | точность |",
         "|---|---|---|---|---|---|---|"]
    for c in CONDITIONS:
        b = m.get(c)
        if not b:
            continue
        L.append(f"| {CONDITION_RU[c]} | {b['false_end']} из {b['speech_lines']} ({b['false_end_paused']} из {b['paused_lines']}) | "
                 f"{fmt(b['latency_med'])} / {fmt(b['latency_p90'])} | {b['no_end']} | {b['false_speech']} из {b['silent_lines']} | "
                 f"{fmt(b['text_ready_med'])} | {fmt(b['accuracy'] * 100 if b['accuracy'] is not None else None, 0, ' %')} |")
    return L + [""]


def render_md(recs, metrics, verdict, live_s, file_stats, spend, est, elapsed) -> str:
    L = ["# Проба S03b — конец реплики в шуме", "",
         f"_Прогон: {time.strftime('%Y-%m-%d %H:%M')}; записей {len(recs)} (последний дубль каждой реплики); "
         f"цена всех вызовов пробы ≈ ${getattr(spend, 'cached', 0.0):.3f} (оценка сверху была ${est['итого']:.3f}; "
         f"остальное — из кэша прежних прогонов, в этом прогоне ≈ ${spend.spent:.3f}); {elapsed / 60:.0f} мин._", ""]
    L += ["## Сценарий (дословно на экране)", ""]
    from collections import OrderedDict
    seen = OrderedDict()
    for r in recs:
        seen.setdefault(r.line, (r.kind, r.text))
    for n, (k, t) in seen.items():
        L.append(f"{n}. {t or '(без речи: молчать, четыре пульса)'} — `{k}`")
    L += ["", "Условия: " + ", ".join(f"{CONDITION_RU[c]} ({sum(1 for r in recs if r.condition == c)})" for c in CONDITIONS), ""]
    L += ["## Как мерили", "",
          "- **Конец речи** — конец последнего слова `whisper-1` (`verbose_json`, метки слов). Пауза посреди мысли — промежуток между словами > 1 с.",
          "- **Конец речи уточнён по энергии**: метки `whisper-1` бывают сдвинуты целиком (на пилоте — на 1 с), поэтому они задают только "
          "окно поиска, а конец — последний кадр 10 мс в полосе 300–3400 Гц громче шума после реплики (подробности — `refine_end`, "
          "заметка по каждой записи — `refine_note` в JSON). Ручная сверка — ниже.",
          "- **Ложный конец** — детектор сработал раньше конца речи (больше чем на 0,2 с), то есть на паузе посреди мысли или в «um…». **Задержка** — от конца последнего слова до срабатывания. "
          "**Нет конца** — не сработал до нажатия кнопки. **Ложная речь** — в записи без речи детектор услышал речь.",
          "- Детекторы на устройстве гоняются по WAV без сети. Серверные — сессия транскрипции, звук потоком кусками по 100 мс в темпе "
          "реального времени, 24 кГц; срабатывание — приход `speech_stopped`.",
          "- **Текст готов** — от конца последнего слова до `…transcription.completed` реплики с последним словом.",
          "- Правило п. 4 брифа: «не больше 1 из 8» прочитано так: ложный конец — не больше 1 на условие среди реплик с паузой и длинных "
          "(их 6 на условие), ложная речь — не больше 1 из 8 записей без речи (2 на условие × 4).", ""]
    ordered = sorted(metrics, key=lambda n: rank_key(metrics[n], verdict[n][0]))
    L += ["## Сводка по всем записям (лучшие настройки каждого детектора и база)", "",
          "| Настройка | ложный конец | задержка мед, с | p90, с | нет конца | ложная речь | текст готов мед, с | p90, с | точность |",
          "|---|---|---|---|---|---|---|---|---|"]
    shown = []
    for fam in ("threshold", "silero", "webrtc", "server_vad", "semantic_vad"):
        names = [n for n in ordered if family(n) == fam]
        pick = names[:3]
        if fam == "threshold" and "threshold 59 dB / 3000 ms" in metrics and "threshold 59 dB / 3000 ms" not in pick:
            pick = ["threshold 59 dB / 3000 ms"] + pick[:2]
        if fam == "semantic_vad":
            pick = names
        for n in pick:
            L.append(metric_row(n, metrics[n], verdict[n][0]))
            shown.append(n)
    L += ["", "✓ — проходит правило п. 4 во всех условиях. Полная сетка — в `03b-endpoint.json` (`metrics`, `verdict`).", ""]
    L += ["## Нынешний путь и потоковый текст", "",
          f"- **Файл в `{M_FILE}`** (m4a 32 кбит/с до конца последнего слова + 1 с, запрос из облака, не с телефона): "
          f"запрос мед {fmt(file_stats['request_med'])} с, p90 {fmt(file_stats['request_p90'])} с; "
          f"точность {fmt(file_stats['accuracy'] * 100 if file_stats['accuracy'] is not None else None, 0, ' %')}. "
          "Текст готов от последнего слова = задержка детектора + запрос."]
    for name, s in live_s.items():
        L.append(f"- **`{M_LIVE}`, delay {name.split()[-1]}** (звук потоком, `commit` через 1 с после последнего слова): "
                 f"от `commit` до текста мед {fmt(s['commit_med'])} с, p90 {fmt(s['commit_p90'])} с; точность "
                 f"{fmt(s['accuracy'] * 100 if s['accuracy'] is not None else None, 0, ' %')}; без текста {s['no_text']}.")
    L += ["", "## По условиям: база и лучшие кандидаты", ""]
    best = [n for n in ordered if verdict[n][0]][:3]
    cand = (["threshold 59 dB / 3000 ms"] if "threshold 59 dB / 3000 ms" in metrics else []) + best
    for fam in ("silero", "server_vad", "semantic_vad"):
        f = [n for n in ordered if family(n) == fam]
        if f and f[0] not in cand:
            cand.append(f[0])
    for n in cand:
        L += condition_table(n, metrics[n])
    L += ["## Вывод по правилу п. 4", ""]
    good = [n for n in ordered if verdict[n][0]]
    if good:
        L.append("Годные настройки (лучшие сверху): " + ", ".join(f"`{n}`" for n in good[:8]) + ".")
    else:
        L.append("Годных нет. Лучшие два и что им мешает:")
        for n in ordered[:2]:
            L.append(f"- `{n}`: " + "; ".join(verdict[n][1]))
    L += ["", "Что мешает остальным (по одному лучшему на детектор):", ""]
    for fam in ("threshold", "silero", "webrtc", "server_vad", "semantic_vad"):
        f = [n for n in ordered if family(n) == fam]
        if f:
            L.append(f"- `{f[0]}`: " + ("проходит" if verdict[f[0]][0] else "; ".join(verdict[f[0]][1])))
    L += ["", "## Цена", "",
          f"- `{M_SESSION}` (сессия и файл): ${PRICE_MIN[M_SESSION]}/мин; `{M_LIVE}`: ${PRICE_MIN[M_LIVE]}/мин; `{M_LABEL}`: ${PRICE_MIN[M_LABEL]}/мин.",
          "- Детекторы на устройстве — $0.",
          "- Цена пробы по частям: " + ", ".join(f"{k} ${v:.3f}" for k, v in getattr(spend, "cached_by", {}).items()) + ".", ""]
    return "\n".join(L) + "\n"
