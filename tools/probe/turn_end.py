"""S04: конец реплики — паузы обдумывания (`python probe.py turn-end`, бриф `docs/stages/04-brief.md`, п. 1).

На записях S03b (48, `diagnostics/recordings`, в Git не кладутся — D69) сравнивает, как конец реплики решают вместе:
1. **кандидат** — тишина по Silero приложения (p ≥ 0,5, речь ≥ 200 мс, как `VadTurnDetector`), 500–1500 мс;
2. **судья по тексту** «мысль закончена?» — J0 (без судьи), J1 (знак в конце), J2 (последнее слово), J3 (`gpt-6-luna`)
   и сочетания; «не закончена» — ждать дольше и судить заново по свежему тексту, но не дольше предела 5000–8000 мс;
3. **отмена** — ученик заговорил после объявленного конца, но раньше голоса учителя (через 2,4 с — медиана `audible`
   S03d): ответ отменяется, запись продолжается. Заговорил позже — учитель **перебил** бы.

Текст на момент кандидата — из настоящей сессии `gpt-live-transcribe` (`delay: low`, как приложение), звук в темпе
реального времени, два способа:
- **а)** частичный текст из дельт без `commit` (одна сессия на запись, `commit` — только в конце, итоговый текст);
- **б)** `commit` на каждой паузе ≥ 500 мс: текст куска готов через ≈ 0,5 с; дальше звук идёт в новый кусок, в его
  `prompt` — уже сказанное; итоговый текст — куски подряд.

**Хвост.** Запись кончается кнопкой (в тишине — через 1,8 с после речи), а пределу нужно до 8 с тишины. Поэтому к
каждой записи с речью после кнопки приклеен её же шум (кусок после речи, по кругу, со стыком 30 мс) — до 10,5 с после
последнего слова. Кнопки в прогоне нет: конец решает только детектор.

Все ответы API — в кэше `out/turn_end/cache/`: повторный прогон после правки мер бесплатен.
"""
from __future__ import annotations

import asyncio
import base64
import concurrent.futures
import hashlib
import json
import math
import re
import statistics
import time
from dataclasses import dataclass, field
from pathlib import Path

import endpoint
import probe

OUT = probe.PROBE_DIR / "out" / "turn_end"
CACHE = OUT / "cache"
DOCS_MD = probe.REPO_ROOT / "docs" / "research" / "04-turn-end.md"
DOCS_JSON = probe.REPO_ROOT / "docs" / "research" / "04-turn-end.json"
LABELS_JSON = endpoint.DOCS_JSON          # разметка S03b: слова, конец речи по энергии, паузы посреди мысли
PROMPT_KT = probe.REPO_ROOT / "android_app" / "app" / "src" / "main" / "java" / "com" / "example" / "listeningtrainer" / "conversation" / "TranscriptionPrompt.kt"

M_LIVE = "gpt-live-transcribe"            # ModelConfig.LIVE_TRANSCRIBE_MODEL
LIVE_DELAY = "low"                        # ModelConfig.LIVE_TRANSCRIBE_DELAY
M_JUDGE = "gpt-6-luna"                    # [LUNA]: reasoning.effort none (страница модели, прочитана 2026-11-26)
PRICE_LIVE_MIN = 0.017
PRICE_JUDGE_IN_M, PRICE_JUDGE_CACHED_M, PRICE_JUDGE_OUT_M = 0.10, 0.01, 0.50

SR = endpoint.SAMPLE_RATE
FRAME_MS = 32                             # кадр Silero, 512 отсчётов
SPEECH_PROB = 0.5                         # ModelConfig.VAD_SPEECH_PROB
MIN_SPEECH_MS = 200                       # ModelConfig.TURN_MIN_SPEECH_MS: «речь была» и «заговорил снова»
START_SILENCE_MS = 8000                   # ModelConfig.TURN_START_SILENCE_MS (D70)
CANDIDATE_MS = [500, 700, 1000, 1250, 1500]
LIMIT_MS = [5000, 6000, 7000, 8000]
REJUDGE_MS = [500, 1000]
COMMIT_EVERY_MS = 500                     # способ б: commit на каждой паузе не короче этого
TEACHER_AUDIBLE_S = 2.4                   # бриф: учитель звучит через 2,4 с после конца (медиана audible S03d)
STT_S = 0.47                              # commit → text, медиана S03d: входит в эти 2,4 с
PAD_AFTER_LAST_WORD_S = 10.5
CHUNK_MS = 80                             # кусок записи приложения
FALSE_END_TOL_S = endpoint.FALSE_END_TOLERANCE_S

MODES = {"a": "а) дельты без commit", "b": "б) commit на каждой паузе", "u": "в) как а, «um» в тексте"}
# Сверх брифа (способ в): поток выбрасывает «um», а пользователь часто говорит его в начале паузы обдумывания
# (разметка S03b, вода #9). Тот же способ а, но в `prompt` — просьба писать заполнители.
FILLER_HINT = "Write filler words such as um and uh exactly as spoken."

# ---------------------------------------------------------------------------------------------
# Судьи
# ---------------------------------------------------------------------------------------------

# J2: последнее слово, после которого мысль не кончается (бриф: союз, предлог, артикль, вспомогательный глагол,
# «um», «I»; сверх брифа — притяжательные и указательные слова перед существительным).
J2_WORDS = {
    # союзы
    "and", "but", "or", "so", "because", "that", "if", "when", "then", "than", "while", "although", "until",
    # предлоги
    "to", "of", "in", "on", "at", "for", "with", "about", "from", "by", "into", "after", "before", "near",
    "under", "over", "without", "around", "through", "between",
    # артикли
    "a", "an", "the",
    # вспомогательные и связки
    "is", "am", "are", "was", "were", "be", "been", "being", "do", "does", "did", "have", "has", "had",
    "will", "would", "can", "could", "should", "must", "may", "might", "shall", "going", "gonna", "wanna",
    "i'm", "it's", "i've", "i'll", "i'd", "don't", "didn't", "can't",
    # заполнители и «I»
    "um", "uh", "uhm", "umm", "erm", "er", "hmm", "mm", "ah", "eh", "i",
    # притяжательные и указательные
    "my", "your", "his", "her", "our", "their", "its", "this", "these", "those", "some", "very", "really",
}

J3_INSTRUCTIONS = (
    "You decide whether a beginner English learner has finished speaking. You get the live speech-to-text "
    "transcript of what the learner has said so far; the learner has just gone quiet. The learner is a slow "
    "speaker and often pauses in the middle of a sentence to think.\n"
    "Answer \"complete\" if this is a finished answer or thought that a teacher could reply to now. Short answers "
    "such as \"At noon.\", \"Yes, I do.\" or \"Pizza.\" are complete.\n"
    "Answer \"incomplete\" if the learner has stopped in the middle of a sentence and will probably go on: for "
    "example, it ends with a conjunction, a preposition, an article, \"um\", or the sentence is grammatically "
    "unfinished (\"Yesterday I\", \"My favorite food is\").\n"
    "Punctuation in the transcript is unreliable. Answer with one word: complete or incomplete."
)

JUDGES = ["J0", "J1", "J2", "J1+J2", "J3", "J1|J3", "J2>J3"]
JUDGE_RU = {
    "J0": "без судьи (кандидат = конец)",
    "J1": "знак . ? ! в конце",
    "J2": "последнее слово",
    "J1+J2": "J1 и J2 оба «закончена»",
    "J3": "gpt-6-luna",
    "J1|J3": "J1 «закончена» — сразу; иначе J3",
    "J2>J3": "J2 «не закончена» — сразу; иначе J3",
}


def norm_words(text: str) -> list[str]:
    t = text.lower().replace("’", "'").replace("…", " ")
    return re.findall(r"[a-z0-9']+", t)


def j1(text: str) -> bool:
    """Мысль закончена, если текст кончается на . ? ! (но не многоточием: «Um…» — не конец)."""
    t = text.strip().rstrip("\"')”»").rstrip()
    if not t or t.endswith("...") or t.endswith("…"):
        return False
    return t[-1] in ".?!"


AUXILIARIES = {"is", "am", "are", "was", "were", "do", "does", "did", "have", "has", "had", "will", "would", "can",
               "could", "should", "must", "may", "might", "shall", "don't", "didn't", "can't"}
PRONOUNS = {"i", "you", "he", "she", "it", "we", "they", "there"}


def j2(text: str) -> bool:
    """Мысль не закончена, если последнее слово — из J2_WORDS. Исключение: вспомогательный глагол сразу после
    местоимения — короткий ответ («Yes, I do.», «It is.», «I can.»)."""
    ws = norm_words(text)
    if not ws:
        return False
    last = ws[-1]
    if last in AUXILIARIES and len(ws) >= 2 and ws[-2] in PRONOUNS:
        return True
    return last not in J2_WORDS


@dataclass
class JudgeCall:
    complete: bool
    latency_s: float
    raw: str = ""
    cost: float = 0.0


class J3Cache:
    """Ответы gpt-6-luna по тексту (кэш на диске). Недостающие тексты копятся в `missing` и спрашиваются пачкой."""

    def __init__(self):
        self.mem: dict[str, JudgeCall] = {}
        self.missing: set[str] = set()
        self.calls_new = 0
        self.cost_new = 0.0

    @staticmethod
    def _path(text: str) -> Path:
        return CACHE / "judge" / (hashlib.sha1((M_JUDGE + "|" + J3_INSTRUCTIONS + "|" + text).encode()).hexdigest()[:16] + ".json")

    def get(self, text: str) -> JudgeCall | None:
        if text in self.mem:
            return self.mem[text]
        p = self._path(text)
        if p.is_file():
            d = json.loads(p.read_text(encoding="utf-8"))
            c = JudgeCall(d["complete"], d["latency_s"], d.get("raw", ""), d.get("cost", 0.0))
            self.mem[text] = c
            return c
        self.missing.add(text)
        return None

    def put(self, text: str, call: JudgeCall) -> None:
        self.mem[text] = call
        p = self._path(text)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps({"text": text, "complete": call.complete, "latency_s": call.latency_s,
                                 "raw": call.raw, "cost": call.cost}, ensure_ascii=False), encoding="utf-8")

    def fetch_missing(self, key: str, timeout: float, spend: endpoint.Spend, parallel: int = 8) -> int:
        todo = sorted(self.missing)
        self.missing.clear()
        if not todo:
            return 0
        spend.check()
        with concurrent.futures.ThreadPoolExecutor(parallel) as ex:
            for text, call in zip(todo, ex.map(lambda t: ask_j3(key, t, timeout), todo)):
                self.put(text, call)
                self.calls_new += 1
                self.cost_new += call.cost
                spend.add(f"судья {M_JUDGE}", call.cost)
        return len(todo)


def ask_j3(key: str, text: str, timeout: float) -> JudgeCall:
    """Один запрос, как будет в приложении: Responses API, без потока, без инструментов, `reasoning: none`."""
    import httpx
    body = {"model": M_JUDGE, "instructions": J3_INSTRUCTIONS, "input": text or "(nothing yet)",
            "reasoning": {"effort": "none"}, "max_output_tokens": 16, "store": False}
    for attempt in range(3):
        t0 = time.perf_counter()
        try:
            r = httpx.post(f"{probe.API_BASE}/responses", headers={"Authorization": f"Bearer {key}"}, json=body, timeout=timeout)
        except httpx.HTTPError:
            if attempt == 2:
                raise
            continue
        dt = time.perf_counter() - t0
        if r.status_code == 401:
            raise probe.ProbeError(probe.auth_error_text(401))
        if r.status_code >= 500 or r.status_code == 429:
            time.sleep(1.0)
            continue
        if r.status_code >= 400:
            raise probe.ProbeError(probe.redact(f"responses {r.status_code}: {r.text[:300]}"))
        d = r.json()
        out = "".join(c.get("text", "") for o in d.get("output", []) if o.get("type") == "message"
                      for c in o.get("content", []) if c.get("type") == "output_text").strip().lower()
        u = d.get("usage") or {}
        cached = (u.get("input_tokens_details") or {}).get("cached_tokens", 0)
        cost = ((u.get("input_tokens", 0) - cached) * PRICE_JUDGE_IN_M + cached * PRICE_JUDGE_CACHED_M
                + u.get("output_tokens", 0) * PRICE_JUDGE_OUT_M) / 1e6
        return JudgeCall(complete=not out.startswith("incomplete") and "incomplete" not in out, latency_s=dt, raw=out, cost=cost)
    raise probe.ProbeError("судья: сервер не ответил за 3 попытки")


def judge(name: str, text: str, j3: J3Cache) -> tuple[bool | None, float]:
    """(закончена?, время судьи, с). `None` — ответа J3 ещё нет в кэше (спросить и повторить прогон).
    Пустой текст (модель ещё ничего не написала) — «не закончена» для всех судей, кроме J0."""
    if name == "J0":
        return True, 0.0
    if not text.strip():
        return False, 0.0
    if name == "J1":
        return j1(text), 0.0
    if name == "J2":
        return j2(text), 0.0
    if name == "J1+J2":
        return j1(text) and j2(text), 0.0
    if name == "J1|J3" and j1(text):
        return True, 0.0
    if name == "J2>J3" and not j2(text):
        return False, 0.0
    c = j3.get(text)
    return (None, 0.0) if c is None else (c.complete, c.latency_s)


# ---------------------------------------------------------------------------------------------
# Записи: разметка S03b и хвост шума
# ---------------------------------------------------------------------------------------------


@dataclass
class TRec:
    rec: endpoint.Rec
    pcm: object                       # с хвостом (int16)
    probs: list = field(default_factory=list)
    last_word_end: float | None = None
    mid_pauses: list = field(default_factory=list)
    label_words: list = field(default_factory=list)
    pad_note: str = ""

    @property
    def base(self):
        return self.rec.base

    @property
    def speech(self):
        return self.rec.speech


def pad_with_own_noise(pcm, start_s: float, stop_s: float, total_s: float):
    """Звук до кнопки + её же шум после речи (кусок [start_s, stop_s)) по кругу до total_s, стык — 30 мс."""
    import numpy as np
    x = pcm[: int(stop_s * SR)].astype(np.float64)
    a = int(start_s * SR)
    seg = x[a:] if len(x) - a >= int(0.4 * SR) else x[max(0, len(x) - int(0.4 * SR)):]
    xf = int(0.03 * SR)
    ramp = np.linspace(0, 1, xf)
    y = x
    while len(y) < int(total_s * SR):
        y = np.concatenate([y[:-xf], y[-xf:] * (1 - ramp) + seg[:xf] * ramp, seg[xf:]])
    y = y[: int(total_s * SR)]
    return np.clip(np.round(y), -32768, 32767).astype(np.int16)


def load(folder: Path) -> list[TRec]:
    recs = endpoint.load_recordings(folder)
    labels = {r["base"]: r for r in json.loads(LABELS_JSON.read_text(encoding="utf-8"))["recordings"]}
    out = []
    for r in recs:
        lab = labels.get(r.base)
        if lab is None:
            raise probe.ProbeError(f"{r.base}: нет разметки в {LABELS_JSON.name}")
        t = TRec(r, r.pcm)
        if r.speech and lab.get("last_word_end_s") is not None:
            t.last_word_end = float(lab["last_word_end_s"])
            t.mid_pauses = [tuple(p) for p in lab.get("mid_pauses_s", [])]
            t.label_words = lab.get("words", [])
            stop = r.stop_ms / 1000
            t.pcm = pad_with_own_noise(r.pcm, t.last_word_end + 0.6, stop, t.last_word_end + PAD_AFTER_LAST_WORD_S)
            t.pad_note = f"до кнопки {stop:.2f} с, шум с {t.last_word_end + 0.6:.2f} с, хвост до {t.last_word_end + PAD_AFTER_LAST_WORD_S:.1f} с"
        out.append(t)
    return out


def commit_points_ms(probs: list[float], every_ms: int = COMMIT_EVERY_MS) -> list[int]:
    """Способ б: где приложение сделало бы `commit` — тишина `every_ms` после речи, если с прошлого `commit` речи
    набралось не меньше MIN_SPEECH_MS (иначе резать нечего). Время — конец кадра, мс звука."""
    points, since, speech_since_commit = [], None, 0
    for i, p in enumerate(probs):
        t = (i + 1) * FRAME_MS
        if p >= SPEECH_PROB:
            speech_since_commit += FRAME_MS
            since = t
            continue
        if since is not None and t - since >= every_ms and speech_since_commit >= MIN_SPEECH_MS:
            points.append(t)
            speech_since_commit = 0
            since = None
    return points


# ---------------------------------------------------------------------------------------------
# Текст на момент решения
# ---------------------------------------------------------------------------------------------


class TextSource:
    """Какой текст есть у приложения в момент `t` (с, время звука = время прихода события: звук шёл в темпе
    реального времени). `at(t, pause_start, cand_s)` → (текст, когда он готов)."""

    def at(self, t: float, pause_start: float, cand_s: float) -> tuple[str, float]:
        raise NotImplementedError

    def final_text(self, end_s: float) -> str:
        raise NotImplementedError

    def teacher_text_ready(self, end_s: float, decided_with_text_ready: float) -> float:
        """Когда у учителя есть итоговый текст: а) commit в конце + 0,47 с; б) текст уже был к решению."""
        raise NotImplementedError


class DeltaText(TextSource):
    """а) частичный текст: дельты, пришедшие к моменту t."""

    def __init__(self, run: dict):
        self.deltas = sorted((e["t"], e.get("delta", "")) for e in run.get("events", [])
                             if e["type"].endswith("transcription.delta") and e.get("item_id") == run.get("main_item"))
        if not self.deltas:  # item_id не сопоставился — все дельты
            self.deltas = sorted((e["t"], e.get("delta", "")) for e in run.get("events", []) if e["type"].endswith("transcription.delta"))
        done = [e for e in run.get("events", []) if e["type"].endswith("transcription.completed")]
        self.final = done[-1].get("transcript", "") if done else "".join(d for _, d in self.deltas)

    def at(self, t, pause_start, cand_s):
        return "".join(d for ts, d in self.deltas if ts <= t).strip(), t

    def final_text(self, end_s):
        return self.final

    def teacher_text_ready(self, end_s, decided_with_text_ready):
        return end_s + STT_S


class CommitText(TextSource):
    """б) куски по commit: текст паузы готов через (commit → text) этого куска после кандидата. Сессия делала commit
    на 500 мс тишины; для кандидата C тот же кусок готов на (C − 500) мс позже — как если бы commit был на C."""

    def __init__(self, run: dict, commit_ms: list[int]):
        ev = run.get("events", [])
        commits = run.get("commits", [])  # [{t, audio_ms}] — когда отправлен commit
        committed = [e for e in ev if e["type"] == "input_audio_buffer.committed"]
        done = {e.get("item_id"): e for e in ev if e["type"].endswith("transcription.completed")}
        self.chunks = []  # (audio_ms commit, ctt_s, text)
        for i, c in enumerate(commits):
            item = committed[i].get("item_id") if i < len(committed) else None
            d = done.get(item)
            ctt = (d["t"] - c["t"]) if d else None
            self.chunks.append((c["audio_ms"], ctt, (d.get("transcript", "") if d else "").strip()))
        self.ctts = [c[1] for c in self.chunks if c[1] is not None]

    def _glued(self, upto_ms: float) -> str:
        return " ".join(txt for ms, _, txt in self.chunks if ms <= upto_ms and txt).strip()

    def at(self, t, pause_start, cand_s):
        # кусок этой паузы — commit через 500 мс после её начала
        mine = [c for c in self.chunks if abs(c[0] / 1000 - (pause_start + COMMIT_EVERY_MS / 1000)) < 0.05]
        if not mine:
            return self._glued(t * 1000), t
        ms, ctt, _ = mine[0]
        ready = pause_start + cand_s + (ctt if ctt is not None else 2.5)
        return self._glued(ms), max(t, ready)

    def final_text(self, end_s):
        """Все куски реплики подряд: ложный конец её не обрезает — после отмены или следующей репликой текст тот же."""
        return self._glued(float("inf"))

    def teacher_text_ready(self, end_s, decided_with_text_ready):
        return decided_with_text_ready


# ---------------------------------------------------------------------------------------------
# Симуляция конца реплики (то же, что будет в приложении)
# ---------------------------------------------------------------------------------------------


@dataclass
class TurnResult:
    end_s: float | None = None            # окончательный конец (после него учитель прозвучал)
    ended_by: str = ""                    # judge | limit | silence_start (молчание с начала)
    teacher_s: float | None = None        # когда прозвучал бы учитель
    equiv_end_s: float | None = None      # teacher_s − 2,4: сопоставимо с концом записи сейчас
    interrupted: bool = False             # ложный конец, учитель заговорил раньше ученика
    cancels: list = field(default_factory=list)  # [(конец, когда ученик заговорил снова)]
    heard: bool = False
    decisions: list = field(default_factory=list)  # [(t, текст, закончена?, судья с)]
    missing_judge: bool = False
    text: str = ""


def simulate(probs: list[float], src: TextSource | None, judge_name: str, cand_ms: int, limit_ms: int,
             rejudge_ms: int, j3: J3Cache, last_word_end: float | None) -> TurnResult:
    """Кадр за кадром (32 мс), как будет в приложении:
    - речь — p ≥ 0,5; «речь была» — набралось ≥ 200 мс; до этого — молчание с начала 8000 мс (D70);
    - тишина `cand_ms` после последней речи — кандидат: текст на этот момент → судья;
      «закончена» — конец (с учётом времени текста и судьи); «не закончена» — судить снова каждые `rejudge_ms`
      по свежему тексту; тишина `limit_ms` — конец по пределу;
    - заговорил (речь ≥ 200 мс) до решения судьи — решения нет, реплика идёт дальше;
    - после конца учитель звучит через 2,4 с (б: на время текста раньше — текст уже был); заговорил ученик раньше —
      **отмена**, та же реплика продолжается; позже — конец окончательный (если он был ложным — «перебил»)."""
    res = TurnResult()
    speech_ms = 0
    last_speech = None          # конец последнего кадра речи, с
    pause_start = None
    next_mark = None            # когда (тишина, мс) судить
    pending = None              # (когда известен вердикт, закончена?, когда текст готов)
    ended = None                # (конец, учитель звучит, текст готов)
    resume_ms = 0
    for i, p in enumerate(probs):
        t = (i + 1) * FRAME_MS / 1000
        is_speech = p >= SPEECH_PROB
        if ended is not None:
            e, teacher, _ = ended
            if t >= teacher:
                break
            if is_speech:
                resume_ms += FRAME_MS
                if resume_ms >= MIN_SPEECH_MS:
                    res.cancels.append((round(e, 3), round(t, 3)))
                    ended, pending = None, None
                    last_speech, next_mark, pause_start = t, None, None
            continue
        if is_speech:
            speech_ms += FRAME_MS
            last_speech = t
            pending, next_mark, pause_start = None, None, None
            continue
        if speech_ms < MIN_SPEECH_MS:
            # молчание с начала (или шорох до 200 мс речи)
            if t - (last_speech or 0.0) >= START_SILENCE_MS / 1000:
                res.ended_by = "silence_start"
                return res
            continue
        res.heard = True
        silence_ms = (t - last_speech) * 1000
        if pause_start is None:
            pause_start = last_speech
            next_mark = cand_ms
        end_now = None
        # вердикт, которого ждали
        if pending is not None and t >= pending[0]:
            when, complete, text_ready = pending
            pending = None
            if complete:
                end_now = (when, "judge", text_ready)
            elif next_mark is not None and next_mark < silence_ms:
                next_mark = silence_ms  # судья думал дольше шага — судить сразу по свежему тексту
        if end_now is None and silence_ms >= limit_ms:
            end_now = (t, "limit", t)
        if end_now is None and pending is None and next_mark is not None and silence_ms >= next_mark:
            if judge_name == "J0" or src is None:
                complete, jlat, text, ready = True, 0.0, "", None  # текст не нужен: commit в конце, как сейчас
            else:
                text, ready = src.at(t, pause_start, cand_ms / 1000)
                complete, jlat = judge(judge_name, text, j3)
                if complete is None:
                    res.missing_judge = True
                    complete, jlat = False, 0.0
            res.decisions.append((round(t, 3), text, complete, round(jlat, 3)))
            pending = (max(t, ready or t) + jlat, complete, ready)
            next_mark = next_mark + rejudge_ms
            if pending[0] <= t:
                when, complete, text_ready = pending
                pending = None
                if complete:
                    end_now = (t, "judge", text_ready)
        if end_now is not None:
            e, why, text_ready = end_now
            res.ended_by = why
            text_at = e + STT_S if (src is None or text_ready is None) else src.teacher_text_ready(e, text_ready)
            teacher = max(e, text_at) + (TEACHER_AUDIBLE_S - STT_S)
            ended = (e, teacher, text_at)
            resume_ms = 0
    if ended is not None:
        e, teacher, _ = ended
        res.end_s, res.teacher_s = e, teacher
        res.equiv_end_s = teacher - TEACHER_AUDIBLE_S
        if last_word_end is not None and res.equiv_end_s < last_word_end - FALSE_END_TOL_S and e < last_word_end - FALSE_END_TOL_S:
            res.interrupted = True
        if src is not None:
            res.text = src.final_text(e)
    return res


# ---------------------------------------------------------------------------------------------
# Сессии gpt-live-transcribe (звук в темпе реального времени)
# ---------------------------------------------------------------------------------------------


def transcription_context() -> str:
    """`TranscriptionPrompt.CONTEXT` приложения — из Kotlin, без копии."""
    m = re.search(r'const val CONTEXT =\s*"([^"]+)"', PROMPT_KT.read_text(encoding="utf-8"))
    if not m:
        raise probe.ProbeError("не нашёл TranscriptionPrompt.CONTEXT")
    return m.group(1)


def live_session(prompt: str) -> dict:
    return {"type": "transcription", "audio": {"input": {
        "format": {"type": "audio/pcm", "rate": probe.RT_RATE},
        "transcription": {"model": M_LIVE, "languages": ["en"], "delay": LIVE_DELAY, "prompt": prompt},
        "turn_detection": None}}}


def continued_prompt(context: str, said: str) -> str:
    """Способ б: `prompt` следующего куска — контекст и уже сказанное в этой реплике."""
    return f'{context} The learner has already said: "{said}" and goes on.' if said else context


KEEP = ("input_audio_buffer.committed", "conversation.item.input_audio_transcription.completed",
        "conversation.item.input_audio_transcription.delta", "conversation.item.input_audio_transcription.failed",
        "error", "session.updated")


async def stream_turn(key: str, timeout: float, pcm16, commit_ms: list[int] | None, context: str) -> dict:
    """Звук записи кусками 80 мс в темпе реального времени (t = 0 — первый кусок). `commit_ms` — способ б: commit,
    когда отправлен звук до этой отметки; после каждого готового куска — `session.update` с новым `prompt`.
    В конце: а) commit всего; б) clear хвоста без речи. События — с временем прихода от начала звука."""
    import numpy as np
    from scipy.signal import resample_poly
    pcm24 = np.clip(np.round(resample_poly(pcm16.astype(np.float64), 3, 2)), -32768, 32767).astype(np.int16).tobytes()
    bytes_per_ms = probe.RT_RATE * 2 // 1000
    events: list[dict] = []
    out = {"events": events, "commits": [], "error": None, "sent_s": 0.0}
    try:
        async with probe.Realtime(key, timeout, probe.REALTIME_TRANSCRIPTION_URL) as rt:
            await rt.update_session(live_session(context))
            t0 = time.perf_counter()
            stop = asyncio.Event()
            glued: list[str] = []

            async def reader():
                while not stop.is_set():
                    try:
                        ev = await rt.recv(0.25)
                    except (asyncio.TimeoutError, TimeoutError):
                        continue
                    typ = ev.get("type", "")
                    if typ not in KEEP:
                        continue
                    rec = {"type": typ, "t": time.perf_counter() - t0}
                    for k in ("item_id", "transcript", "delta", "usage", "error"):
                        if k in ev:
                            rec[k] = ev[k]
                    events.append(rec)
                    if commit_ms is not None and typ.endswith("transcription.completed"):
                        glued.append((ev.get("transcript") or "").strip())
                        said = " ".join(g for g in glued if g)
                        await rt.send({"type": "session.update", "session": live_session(continued_prompt(context, said))})

            task = asyncio.create_task(reader())
            step = CHUNK_MS * bytes_per_ms
            sent = 0
            todo = list(commit_ms or [])
            while sent < len(pcm24):
                chunk = pcm24[sent:sent + step]
                await rt.send({"type": "input_audio_buffer.append", "audio": base64.b64encode(chunk).decode()})
                sent += len(chunk)
                sent_ms = sent / bytes_per_ms
                while todo and sent_ms >= todo[0]:
                    out["commits"].append({"t": time.perf_counter() - t0, "audio_ms": todo.pop(0)})
                    await rt.send({"type": "input_audio_buffer.commit"})
                due = t0 + sent_ms / 1000
                await asyncio.sleep(max(0.0, due - time.perf_counter()))
            out["sent_s"] = sent / bytes_per_ms / 1000
            if commit_ms is None:
                out["commits"].append({"t": time.perf_counter() - t0, "audio_ms": int(out["sent_s"] * 1000)})
                await rt.send({"type": "input_audio_buffer.commit"})
            else:
                await rt.send({"type": "input_audio_buffer.clear"})
            want = len(out["commits"])
            end = time.perf_counter() + 10.0
            while time.perf_counter() < end:
                done = sum(1 for e in events if e["type"].endswith("transcription.completed") or e["type"].endswith("failed"))
                if done >= want:
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
    committed = [e for e in events if e["type"] == "input_audio_buffer.committed"]
    if commit_ms is None and committed:
        out["main_item"] = committed[-1].get("item_id")
    return out


def session_key(t: TRec, mode: str, commit_ms, context: str) -> str:
    h = hashlib.sha1(t.pcm.tobytes()).hexdigest()[:12]
    return f"{t.base}|{h}|{mode}|{M_LIVE}|{LIVE_DELAY}|{context}|{commit_ms}"


async def run_sessions(trecs: list[TRec], key: str | None, timeout: float, spend: endpoint.Spend, parallel: int) -> dict:
    """mode → base → сырые события сессии (из кэша или новые). `key=None` — только кэш (`--offline`)."""
    sem = asyncio.Semaphore(parallel)
    context = transcription_context()
    res: dict[str, dict] = {m: {} for m in MODES}

    async def one(t: TRec, mode: str):
        commits = commit_points_ms(t.probs) if mode == "b" else None
        prompt = f"{context} {FILLER_HINT}" if mode == "u" else context
        ck = session_key(t, mode if mode != "u" else "a", commits, prompt)
        got = _cache_get(ck)
        secs = len(t.pcm) / SR
        if got is None and key is None:
            return
        if got is None:
            async with sem:
                spend.check()
                got = await stream_turn(key, timeout, t.pcm, commits, prompt)
                if not got.get("error"):
                    _cache_put(ck, got)
                spend.add(f"{M_LIVE} ({MODES[mode]})", PRICE_LIVE_MIN * secs / 60)
        spend.cost(f"{M_LIVE} ({MODES[mode]})", PRICE_LIVE_MIN * secs / 60, ck)
        res[mode][t.base] = got

    await asyncio.gather(*(one(t, m) for m in MODES for t in trecs if t.speech and t.last_word_end is not None))
    return res


def _cache_get(k: str):
    p = CACHE / "live" / (hashlib.sha1(k.encode()).hexdigest()[:16] + ".json")
    return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else None


def _cache_put(k: str, v) -> None:
    p = CACHE / "live" / (hashlib.sha1(k.encode()).hexdigest()[:16] + ".json")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(v, ensure_ascii=False), encoding="utf-8")


# ---------------------------------------------------------------------------------------------
# Меры и правило выбора (бриф п. 1)
# ---------------------------------------------------------------------------------------------

CONDITIONS = endpoint.CONDITIONS
CONDITION_RU = endpoint.CONDITION_RU


@dataclass(frozen=True)
class Setting:
    mode: str
    judge: str
    cand_ms: int
    limit_ms: int
    rejudge_ms: int

    @property
    def name(self) -> str:
        return f"{self.mode}) {self.judge} / кандидат {self.cand_ms} / предел {self.limit_ms} / шаг {self.rejudge_ms}"


def all_settings(with_j3: bool = True) -> list[Setting]:
    out = []
    for mode in MODES:
        for j in JUDGES:
            if not with_j3 and "J3" in j:
                continue
            for c in CANDIDATE_MS:
                for lim in LIMIT_MS:
                    for rj in REJUDGE_MS:
                        if j == "J0" and (lim != LIMIT_MS[0] or rj != REJUDGE_MS[0]):
                            continue  # J0 кончает на кандидате: предел и шаг не важны
                        out.append(Setting(mode, j, c, lim, rj))
    return out


def run_all(trecs: list[TRec], sources: dict, j3: J3Cache, settings: list[Setting]) -> dict[Setting, dict[str, TurnResult]]:
    res = {}
    for s in settings:
        per = {}
        for t in trecs:
            src = sources[s.mode].get(t.base) if t.speech else None
            if t.speech and src is None:
                continue  # сессии нет (--offline): запись в этом способе не считается
            per[t.base] = simulate(t.probs, src, s.judge, s.cand_ms, s.limit_ms, s.rejudge_ms, j3, t.last_word_end)
        res[s] = per
    return res


CONTRACTIONS = {"it's": "it is", "i'm": "i am", "don't": "do not", "didn't": "did not", "i've": "i have",
                "i'll": "i will", "can't": "can not", "cannot": "can not", "that's": "that is", "we're": "we are"}


def accuracy(reference: str, hypothesis: str) -> float | None:
    """Как `endpoint.accuracy` (доля слов сценария при выравнивании, «um» не считается), но сокращение и полная
    форма — одно и то же («it's» = «it is»: в S03d мера считала это ошибкой у потока и у файла)."""
    def expand(t: str) -> str:
        return " ".join(CONTRACTIONS.get(w, w) for w in norm_words(t))
    return endpoint.accuracy(expand(reference), expand(hypothesis))


FOREIGN = re.compile(r"[^\x00-\u024f\u2010-\u2027\u2026’“”«»]")


def extra_words(reference: str, hypothesis: str) -> int:
    """Слова текста, которых нет в сценарии (после выравнивания; сокращения = полная форма, «um» не считается)."""
    import difflib

    def expand(t: str) -> list[str]:
        return [w for w in " ".join(CONTRACTIONS.get(w, w) for w in norm_words(t)).split() if w not in endpoint.FILLERS]
    ref, hyp = expand(reference), expand(hypothesis)
    sm = difflib.SequenceMatcher(a=ref, b=hyp, autojunk=False)
    return len(hyp) - sum(b.size for b in sm.get_matching_blocks())


def block_metrics(trecs: list[TRec], per: dict[str, TurnResult]) -> dict:
    trecs = [t for t in trecs if t.base in per]
    speech = [t for t in trecs if t.speech and t.last_word_end is not None]
    silent = [t for t in trecs if not t.speech]
    paused = [t for t in speech if t.rec.kind in ("pause", "long")]
    lat, acc, long_end, no_end, inter, inter_p, canc, canc_turns, by, extra = [], [], [], [], [], [], 0, 0, {}, []
    for t in speech:
        r = per[t.base]
        canc += len(r.cancels)
        canc_turns += bool(r.cancels)
        if r.end_s is None:
            no_end.append(t.base)
            long_end.append(t.base)
            continue
        by[r.ended_by] = by.get(r.ended_by, 0) + 1
        if r.interrupted:
            inter.append(t.base)
            if t in paused:
                inter_p.append(t.base)
        else:
            d = r.equiv_end_s - t.last_word_end
            lat.append(d)
            if d > 3.0:
                long_end.append(t.base)
        a = accuracy(t.rec.text, r.text) if r.text is not None else None
        if a is not None:
            acc.append(a)
        if r.text:
            extra.append(extra_words(t.rec.text, r.text) + (99 if FOREIGN.search(r.text) else 0))
    false_speech = [t.base for t in silent if per[t.base].heard]
    n = len(speech)
    return {
        "speech_lines": n, "paused_lines": len(paused), "silent_lines": len(silent),
        "interrupted": len(inter), "interrupted_paused": len(inter_p), "interrupted_lines": inter,
        "cancelled_turns": canc_turns, "cancels": canc,
        "latency_med": endpoint.pct(lat, 0.5), "latency_p90": endpoint.pct(lat, 0.9), "latency_max": max(lat) if lat else None,
        "long_end": len(long_end), "long_end_pct": 100 * len(long_end) / n if n else None,
        "no_end": len(no_end), "false_speech": len(false_speech),
        "accuracy": statistics.mean(acc) if acc else None, "ended_by": by,
        "extra_words": sum(x % 99 for x in extra), "extra_lines": sum(1 for x in extra if x % 99),
        "foreign_lines": sum(1 for x in extra if x >= 99),
    }


def metrics(trecs: list[TRec], per: dict[str, TurnResult]) -> dict:
    out = {"all": block_metrics(trecs, per)}
    for c in CONDITIONS:
        rs = [t for t in trecs if t.rec.condition == c]
        if rs:
            out[c] = block_metrics(rs, per)
    return out


def passes(m: dict) -> tuple[bool, list[str]]:
    """Правило брифа п. 1, «во всех условиях вместе» — по сводке всех 48 записей."""
    a, why = m["all"], []
    if a["interrupted_paused"] > 2:
        why.append(f"перебил {a['interrupted_paused']} из {a['paused_lines']}")
    if a["interrupted"] > a["interrupted_paused"]:
        why.append(f"перебил на короткой реплике {a['interrupted'] - a['interrupted_paused']}")
    if a["latency_med"] is None or a["latency_med"] > 1.5:
        why.append(f"задержка медиана {endpoint.fmt(a['latency_med'])} с")
    if a["latency_p90"] is None or a["latency_p90"] > 2.5:
        why.append(f"задержка p90 {endpoint.fmt(a['latency_p90'])} с")
    if a["long_end_pct"] is None or a["long_end_pct"] > 10:
        why.append(f"долгий конец {a['long_end']} из {a['speech_lines']}")
    if a["no_end"] > 0:
        why.append(f"нет конца {a['no_end']}")
    if a["false_speech"] > 1:
        why.append(f"ложная речь {a['false_speech']} из {a['silent_lines']}")
    if a["accuracy"] is None or a["accuracy"] < 0.93:
        why.append(f"точность {endpoint.fmt(100 * a['accuracy'] if a['accuracy'] is not None else None, 0)} %")
    return not why, why


SIMPLICITY = {"J0": 0, "J1": 1, "J2": 1, "J1+J2": 2, "J2>J3": 3, "J1|J3": 3, "J3": 4}


def rank(s: Setting, m: dict, ok: bool, why: list[str]) -> tuple:
    a = m["all"]
    if ok:  # среди годных — самый простой: судья проще, способ а проще (одна сессия, текст уже есть), затем быстрее
        return (0, SIMPLICITY[s.judge], s.mode != "a", a["latency_med"], a["latency_p90"])
    # негодные: меньше плохих реплик (перебил или долгий конец), потом меньше нарушенных пунктов, потом быстрее
    return (1, a["interrupted"] + a["long_end"], len(why), a["latency_med"] or 99)


# ---------------------------------------------------------------------------------------------
# Дополнительные меры: хвост дельт, судьи на паузах, проверка судей на тексте
# ---------------------------------------------------------------------------------------------


def pauses_of(probs: list[float]) -> list[tuple[float, float | None]]:
    """Паузы после речи по Silero приложения (≥ 500 мс): (начало = конец последнего кадра речи, конец = начало
    следующей речи или None — до конца записи)."""
    out, speech_ms, last, in_pause = [], 0, None, False
    for i, p in enumerate(probs):
        t = (i + 1) * FRAME_MS / 1000
        if p >= SPEECH_PROB:
            if in_pause:
                out[-1] = (out[-1][0], t - FRAME_MS / 1000)
                in_pause = False
            speech_ms += FRAME_MS
            last = t
            continue
        if speech_ms >= MIN_SPEECH_MS and last is not None and not in_pause and t - last >= 0.5:
            out.append((last, None))
            in_pause = True
    return out


def delta_lag(trecs: list[TRec], sources: dict) -> dict:
    """а) Сколько после начала паузы приходит последняя дельта звука до паузы («придерживает ли модель хвост»)."""
    lags = {"mid": [], "final": []}
    for t in trecs:
        src = sources["a"].get(t.base)
        if src is None:
            continue
        for start, stop in pauses_of(t.probs):
            window_end = (stop if stop is not None else start + 3.0) + 0.15  # дельта следующей речи — не наша
            tail = [ts for ts, d in src.deltas if start - 0.3 <= ts <= min(window_end, start + 3.0)]
            lag = max(tail) - start if tail else 0.0
            kind = "final" if start >= t.last_word_end - FALSE_END_TOL_S else "mid"
            lags[kind].append(max(0.0, lag))
    out = {}
    for k, v in lags.items():
        out[k] = {"n": len(v), "med": endpoint.pct(v, 0.5), "p90": endpoint.pct(v, 0.9), "max": max(v) if v else None,
                  "ready_by": {c: sum(1 for x in v if x <= c / 1000) for c in CANDIDATE_MS}}
    return out


def judge_table(trecs: list[TRec], sources: dict, j3: J3Cache, cand_ms: int = 1000) -> dict:
    """Первое решение каждой паузы (кандидат `cand_ms`), по способам: на паузе посреди мысли судья должен сказать
    «не закончена», на паузе после последнего слова — «закончена»."""
    out = {}
    for mode in MODES:
        for j in JUDGES[1:]:
            mid_ok = mid_n = fin_ok = fin_n = 0
            lat = []
            for t in trecs:
                src = sources[mode].get(t.base)
                if src is None:
                    continue
                for start, stop in pauses_of(t.probs):
                    if stop is not None and stop - start < cand_ms / 1000:
                        continue
                    text, ready = src.at(start + cand_ms / 1000, start, cand_ms / 1000)
                    v, jl = judge(j, text, j3)
                    if v is None:
                        continue
                    if j in ("J3", "J1|J3", "J2>J3") and jl > 0:
                        lat.append(jl)
                    if start >= t.last_word_end - FALSE_END_TOL_S:
                        fin_n += 1
                        fin_ok += v
                    else:
                        mid_n += 1
                        mid_ok += not v
            out[f"{mode}|{j}"] = {"mid_ok": mid_ok, "mid_n": mid_n, "final_ok": fin_ok, "final_n": fin_n,
                                  "lat_med": endpoint.pct(lat, 0.5), "lat_p90": endpoint.pct(lat, 0.9)}
    return out


# Сверх брифа: судьи на фразах вне сценария (паузы после значимых слов, ответы одним словом). Текст — как пишет поток:
# без знаков. (фраза, мысль закончена?)
TEXT_CASES = [
    ("at noon", True), ("yes i do", True), ("pizza", True), ("in the morning", True), ("my dog is five", True),
    ("i don't know", True), ("no i don't", True), ("i like to read books in the evening", True),
    ("yesterday i went to the shop", True), ("i live in tucson", True), ("it was very hot", True),
    ("i think it is a good idea", True), ("what is the weather today", True), ("can you repeat please", True),
    ("i went there with my wife", True), ("two", True), ("i was tired", True), ("sorry what", True),
    ("yesterday i", False), ("my favorite food is", False), ("i wanted to tell you about", False),
    ("i think that", False), ("yesterday i got up early um", False), ("we cooked dinner um and then", False),
    ("i went to the", False), ("my sister lives in", False), ("last weekend i visited my", False),
    ("i like playing", False), ("yesterday i went to the shop and bought", False), ("i would like", False),
    ("because", False), ("i have two", False), ("we watched a movie and", False), ("my favorite color", False),
    ("the weather in my city is", False), ("i usually drink coffee but", False),
]


def text_check(j3: J3Cache) -> dict:
    out = {}
    for j in ("J2", "J3"):
        wrong, answered = [], 0
        for text, truth in TEXT_CASES:
            v, _ = judge(j, text, j3)
            if v is None:
                continue
            answered += 1
            if v != truth:
                wrong.append(text)
        out[j] = {"n": answered, "wrong": wrong}
    return out


# ---------------------------------------------------------------------------------------------
# Прогон
# ---------------------------------------------------------------------------------------------


def estimate(trecs: list[TRec]) -> dict:
    secs = sum(len(t.pcm) / SR for t in trecs if t.speech and t.last_word_end is not None)
    return {"минут звука в сессию": secs / 60,
            f"{M_LIVE}, три способа": 3 * PRICE_LIVE_MIN * secs / 60,
            f"судья {M_JUDGE} (≈ 600 запросов)": 600 * 400 * PRICE_JUDGE_IN_M / 1e6}


def probe_turn_end(ctx) -> bool:
    args = ctx.args
    folder = Path(args.recordings) if getattr(args, "recordings", None) else endpoint.DEFAULT_RECORDINGS
    trecs = load(folder)
    print(f"Записей: {len(trecs)} (с речью {sum(1 for t in trecs if t.speech)})")
    est = estimate(trecs)
    total = sum(v for k, v in est.items() if not k.startswith("минут"))
    for k, v in est.items():
        print(f"  {k}: " + (f"{v:.1f}" if k.startswith("минут") else f"${v:.3f}"))
    print(f"  итого ≈ ${total:.2f} (сверху; повторный прогон — из кэша)")
    budget = getattr(args, "turn_budget", 1.5)
    if not getattr(args, "yes", False) and not getattr(args, "offline", False):
        if input(f"Запустить? Бюджет ${budget:.2f} [y/N] ").strip().lower() != "y":
            print("Отменено.")
            return False
    spend = endpoint.Spend(budget)
    offline = getattr(args, "offline", False)
    key = None if offline else ctx.key
    t0 = time.time()
    print("1/4 Silero (как приложение) …")
    sil = endpoint.Silero()
    for t in trecs:
        t.probs = sil.probs(t.pcm)
    print(f"2/4 сессии {M_LIVE}: а) дельты без commit, б) commit на каждой паузе — по {args.parallel} сразу, в реальном времени …")
    raw = asyncio.run(run_sessions(trecs, key, ctx.timeout, spend, args.parallel))
    errors = [(m, b, r["error"]) for m in raw for b, r in raw[m].items() if r.get("error")]
    if errors:
        raise probe.ProbeError(f"сессии с ошибкой: {errors[:3]} (всего {len(errors)}); повторите — готовые в кэше")
    sources = {"a": {b: DeltaText(r) for b, r in raw["a"].items()},
               "u": {b: DeltaText(r) for b, r in raw["u"].items()},
               "b": {t.base: CommitText(raw["b"][t.base], commit_points_ms(t.probs)) for t in trecs if t.base in raw["b"]}}
    print("3/4 судьи и симуляция …")
    j3 = J3Cache()
    settings = all_settings(with_j3=not offline)
    for round_no in range(1, 12):
        results = run_all(trecs, sources, j3, settings)
        judge_table(trecs, sources, j3)
        text_check(j3)
        if offline:
            j3.missing.clear()
            break
        asked = j3.fetch_missing(key, ctx.timeout, spend)
        print(f"   круг {round_no}: новых вопросов судье {asked}")
        if not asked:
            break
    print("4/4 отчёт …")
    report(trecs, raw, sources, results, j3, spend, est, time.time() - t0, offline=offline)
    return True


# ---------------------------------------------------------------------------------------------
# Отчёт
# ---------------------------------------------------------------------------------------------


def f2(v, nd=2):
    return endpoint.fmt(v, nd)


def pct_s(v):
    return "—" if v is None else f"{100 * v:.0f} %"


def row(s: Setting, m: dict, ok: bool) -> str:
    a = m["all"]
    by = a["ended_by"]
    return (f"| {'✓' if ok else '✗'} {MODES[s.mode][:2]} {s.judge} | {s.cand_ms} | {s.limit_ms} | {s.rejudge_ms} | "
            f"{a['interrupted_paused']} из {a['paused_lines']}" + (f" (+{a['interrupted'] - a['interrupted_paused']})" if a['interrupted'] > a['interrupted_paused'] else "")
            + f" | {a['cancelled_turns']} ({a['cancels']}) | {f2(a['latency_med'])} | {f2(a['latency_p90'])} | "
            f"{a['long_end']} ({a['long_end_pct']:.0f} %) | {a['no_end']} | {a['false_speech']} из {a['silent_lines']} | "
            f"{pct_s(a['accuracy'])} | {a['extra_lines']} ({a['foreign_lines']}) | {by.get('judge', 0)} / {by.get('limit', 0)} |")


HEAD = ["| Вариант | кандидат, мс | предел, мс | шаг, мс | перебил | отменено (раз) | задержка мед, с | p90, с | долгий конец | нет конца | ложная речь | точность | реплик с лишними словами (чужой алфавит) | конец: судья / предел |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]


def cond_table(m: dict) -> list[str]:
    L = ["| Условие | перебил (с паузой) | отменено | задержка мед / p90, с | долгий конец | точность |", "|---|---|---|---|---|---|"]
    for c in CONDITIONS:
        b = m.get(c)
        if b:
            L.append(f"| {CONDITION_RU[c]} | {b['interrupted']} ({b['interrupted_paused']} из {b['paused_lines']}) | {b['cancelled_turns']} | "
                     f"{f2(b['latency_med'])} / {f2(b['latency_p90'])} | {b['long_end']} из {b['speech_lines']} | {pct_s(b['accuracy'])} |")
    return L


def report(trecs, raw, sources, results, j3, spend, est, elapsed, offline: bool = False) -> None:
    graded = []
    for s, per in results.items():
        m = metrics(trecs, per)
        if not m["all"]["speech_lines"]:
            continue  # способа нет в кэше (--offline)
        ok, why = passes(m)
        graded.append((rank(s, m, ok, why), s, m, ok, why))
    graded.sort(key=lambda x: x[0])
    good = [g for g in graded if g[3]]
    lag = delta_lag(trecs, sources)
    jt = judge_table(trecs, sources, j3)
    tc = text_check(j3)
    j3_lat = [c.latency_s for c in j3.mem.values()]
    live_secs = {m: sum(r.get("sent_s", 0) for r in raw[m].values()) for m in raw}
    ctt_b = [x for src in sources["b"].values() for x in src.ctts]
    data = {
        "date": time.strftime("%Y-%m-%d %H:%M"), "elapsed_s": round(elapsed), "offline": offline,
        "sessions": {m: len(raw[m]) for m in raw},
        "settings": {"candidate_ms": CANDIDATE_MS, "limit_ms": LIMIT_MS, "rejudge_ms": REJUDGE_MS, "teacher_audible_s": TEACHER_AUDIBLE_S,
                     "stt_s": STT_S, "commit_every_ms": COMMIT_EVERY_MS, "pad_after_last_word_s": PAD_AFTER_LAST_WORD_S,
                     "judge_model": M_JUDGE, "live_model": M_LIVE, "live_delay": LIVE_DELAY, "j2_words": sorted(J2_WORDS),
                     "j3_instructions": J3_INSTRUCTIONS},
        "best": [{"setting": g[1].__dict__, "ok": g[3], "why": g[4], "metrics": g[2]} for g in graded[:40]],
        "good_count": len(good),
        "delta_lag": lag, "judges_at_pauses_c1000": jt, "text_check": tc,
        "j3_latency": {"n": len(j3_lat), "med": endpoint.pct(j3_lat, 0.5), "p90": endpoint.pct(j3_lat, 0.9), "max": max(j3_lat) if j3_lat else None},
        "commit_to_text_b": {"n": len(ctt_b), "med": endpoint.pct(ctt_b, 0.5), "p90": endpoint.pct(ctt_b, 0.9)},
        "live_audio_min": {m: round(v / 60, 2) for m, v in live_secs.items()},
        "spend": {"spent_now": round(spend.spent, 4), "by_now": spend.by, "run_cost": round(getattr(spend, "cached", 0.0) + j3.cost_new, 4),
                  "run_cost_by": getattr(spend, "cached_by", {}), "judge_cost_all": round(sum(c.cost for c in j3.mem.values()), 5)},
        "recordings": [],
    }
    # Записи: выбранный (или лучший) вариант — подробно
    top = graded[0]
    top_res = results[top[1]]
    for t in trecs:
        r = top_res.get(t.base)
        if r is None:
            continue
        data["recordings"].append({
            "base": t.base, "condition": t.condition if hasattr(t, "condition") else t.rec.condition, "line": t.rec.line, "kind": t.rec.kind,
            "text": t.rec.text, "last_word_end_s": t.last_word_end, "mid_pauses_s": t.mid_pauses, "pad": t.pad_note,
            "end_s": r.end_s, "equiv_end_s": r.equiv_end_s, "ended_by": r.ended_by, "interrupted": r.interrupted,
            "cancels": r.cancels, "decisions": r.decisions, "final_text": r.text,
            "text_a": sources["a"][t.base].final if t.base in sources["a"] else None,
        })
    DOCS_JSON.write_text(json.dumps(data, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    DOCS_MD.write_text(render_md(trecs, graded, good, lag, jt, tc, data, results), encoding="utf-8")
    print(f"Готово: {DOCS_MD.relative_to(probe.REPO_ROOT)} (+ .json). Годных: {len(good)} из {len(graded)}.")
    for g in graded[:5]:
        print(("  ✓ " if g[3] else "  ✗ ") + g[1].name + ("" if g[3] else " — " + "; ".join(g[4])))


def render_md(trecs, graded, good, lag, jt, tc, data, results) -> str:
    sp = data["spend"]
    L = ["# Проба S04 — конец реплики: паузы обдумывания", "",
         f"_Прогон: {data['date']}; записей {len(trecs)} (S03b, последний дубль); сессий с речью: "
         + ", ".join(f"{MODES[m][:2]} {n} ({data['live_audio_min'].get(m, 0)} мин)" for m, n in data["sessions"].items())
         + f"; цена пробы ≈ ${sp['run_cost']:.3f} (в этом прогоне потрачено ${sp['spent_now']:.3f}, остальное — кэш)._", "",
         "## Как мерили", "",
         "- **Записи** — 48 записей S03b, разметка конца речи и пауз — из `03b-endpoint.json` (по энергии, сверена вручную). "
         "Кнопки нет: после неё к записи приклеен её же шум до 10,5 с после последнего слова (бриф: предел до 8 с, а кнопку "
         "в тишине нажимали через 1,8 с). Конец решает только детектор.",
         "- **Кандидат** — тишина по Silero, как `VadTurnDetector` приложения: речь — p ≥ 0,5, «речь была» — ≥ 200 мс, "
         "кадр 32 мс. До речи — молчание с начала 8000 мс (D70).",
         f"- **Текст** — настоящая сессия `{M_LIVE}` (`delay: {LIVE_DELAY}`, `prompt` = `TranscriptionPrompt.CONTEXT`), звук кусками 80 мс "
         "в темпе реального времени, время события = время прихода:",
         "  - **а)** дельты без `commit`; текст для судьи — дельты, пришедшие к моменту решения; итоговый текст — один `commit` в конце;",
         f"  - **б)** `commit` на каждой паузе ≥ {COMMIT_EVERY_MS} мс (речи с прошлого ≥ 200 мс), в `prompt` следующего куска — уже сказанное. "
         "Текст паузы готов через `commit → text` её куска после кандидата; итоговый текст — куски подряд.",
         "- **Судья** решает на кандидате; «не закончена» — снова через шаг (500 или 1000 мс) по свежему тексту; тишина «предел» — конец. "
         "Время судьи J3 — настоящее время запроса из облака (`reasoning: none`, без потока), входит в задержку.",
         f"- **Учитель** звучит через {TEACHER_AUDIBLE_S} с после конца (медиана `audible` S03d; в ней {STT_S} с — `commit → text`). "
         f"В б) текст к концу уже есть, поэтому учитель звучит на это время раньше. **Задержка** — от конца речи до "
         f"(голос учителя − {TEACHER_AUDIBLE_S} с), то есть сопоставима с концом записи сейчас (3,1 с).",
         "- **Отмена** — после конца ученик заговорил (речь ≥ 200 мс) раньше голоса учителя: ответ отменён, реплика продолжается. "
         f"**Перебил** — конец раньше конца речи больше чем на {FALSE_END_TOL_S} с, и до голоса учителя ученик не заговорил.",
         "- **Точность** — доля слов сценария в итоговом тексте (как в S03b и S03d, «um» не считается).",
         "- **Правило** (бриф) — по всем 48 записям вместе: перебил ≤ 2 из 24 реплик с паузой (на коротких — 0), задержка медиана ≤ 1,5 с, "
         "p90 ≤ 2,5 с, долгий конец (> 3 с или нет конца) ≤ 10 %, нет конца 0, ложная речь ≤ 1 из 8, точность ≥ 93 %. "
         "Среди годных — самый простой: судья J1/J2 лучше J3, способ а лучше б (одна сессия, без склейки), потом быстрее.", ""]
    L += ["## Итог", ""]
    if good:
        g = good[0]
        L += [f"**Годных вариантов: {len(good)} из {len(graded)}.** Самый простой — **{g[1].name}** ({JUDGE_RU[g[1].judge]}).", ""]
    else:
        L += [f"**Годных вариантов нет** ({len(graded)} проверено). Лучшие два (разные способ и судья) и что им мешает:", ""]
        two, seen2 = [], set()
        for g in graded:
            if (g[1].mode, g[1].judge) not in seen2:
                seen2.add((g[1].mode, g[1].judge))
                two.append(g)
            if len(two) == 2:
                break
        for g in two:
            L.append(f"- **{g[1].name}**: " + "; ".join(g[4]))
        L.append("")
    L += ["### Лучшие варианты каждого судьи и способа", ""] + HEAD
    seen = set()
    for g in graded:
        k = (g[1].mode, g[1].judge)
        if k in seen:
            continue
        seen.add(k)
        L.append(row(g[1], g[2], g[3]))
    L += ["", "«(+N)» в «перебил» — ещё на коротких репликах. «Отменено» — реплик с отменой (всего отмен).", ""]
    if good:
        L += ["### Годные варианты (до 15)", ""] + HEAD + [row(g[1], g[2], g[3]) for g in good[:15]] + [""]
    firsts, seen3 = [], set()
    for g in graded:
        if (g[1].mode, g[1].judge) not in seen3:
            seen3.add((g[1].mode, g[1].judge))
            firsts.append(g)
    for g in (good[:1] or firsts[:2]):
        L += [f"### По условиям: {g[1].name}", ""] + cond_table(g[2]) + [""]
    # Текст
    L += ["## Текст на момент кандидата", "",
          "**а) Придерживает ли модель хвост без `commit`.** Время от начала паузы (конец последнего кадра речи по Silero) до прихода "
          "последней дельты звука перед паузой:", "",
          "| Пауза | пауз | медиана, с | p90, с | максимум, с | " + " | ".join(f"готово к {c} мс" for c in CANDIDATE_MS) + " |",
          "|---|---|---|---|---|" + "---|" * len(CANDIDATE_MS)]
    for k, ru in (("mid", "посреди мысли"), ("final", "после последнего слова")):
        v = lag[k]
        L.append(f"| {ru} | {v['n']} | {f2(v['med'])} | {f2(v['p90'])} | {f2(v['max'])} | " + " | ".join(f"{v['ready_by'][c]} из {v['n']}" for c in CANDIDATE_MS) + " |")
    ctt = data["commit_to_text_b"]
    L += ["", f"**б) `commit` на каждой паузе:** кусков {ctt['n']}, `commit → text` медиана {f2(ctt['med'])} с, p90 {f2(ctt['p90'])} с.", "",
          "**Знаки препинания.** Поток пишет текст без точек и запятых (и в дельтах, и в `completed`): J1 «закончена» почти не говорит.", ""]
    # Судьи
    L += ["## Судьи на паузах (кандидат 1000 мс, первое решение паузы)", "",
          "Посреди мысли верно — «не закончена»; после последнего слова верно — «закончена».", "",
          "| Судья | " + " | ".join(f"{MODES[m][:2]} посреди мысли | {MODES[m][:2]} после конца" for m in MODES) + " | время J3 мед / p90, с |",
          "|---|" + "---|---|" * len(MODES) + "---|"]
    for j in JUDGES[1:]:
        a = jt[f"a|{j}"]
        lat = f"{f2(a['lat_med'])} / {f2(a['lat_p90'])}" if a["lat_med"] is not None else "—"
        cells = " | ".join(f"{jt[f'{m}|{j}']['mid_ok']} из {jt[f'{m}|{j}']['mid_n']} | {jt[f'{m}|{j}']['final_ok']} из {jt[f'{m}|{j}']['final_n']}" for m in MODES)
        L.append(f"| {j} — {JUDGE_RU[j]} | {cells} | {lat} |")
    jl = data["j3_latency"]
    L += ["", f"Время запроса J3 (`{M_JUDGE}`, все {jl['n']} запросов): медиана {f2(jl['med'])} с, p90 {f2(jl['p90'])} с, максимум {f2(jl['max'])} с. "
          f"Все запросы судьи ≈ ${sp['judge_cost_all']:.4f}.", "",
          "**Сверх брифа: судьи на фразах вне сценария.** В сценарии все паузы — после служебного слова («about», «I», «is», «that», "
          "«um»), это удобно J2. Проверка на 36 фразах без знаков (паузы после значимых слов, ответы одним словом):", ""]
    for j in ("J2", "J3"):
        v = tc[j]
        if not v["n"]:
            L.append(f"- **{j}**: нет ответов (прогон без сети)")
            continue
        L.append(f"- **{j}**: ошибок {len(v['wrong'])} из {v['n']}" + (": " + ", ".join(f"«{w}»" for w in v["wrong"]) if v["wrong"] else ""))
    # Записи лучшего
    top = graded[0][1]
    L += ["", f"## По записям: {top.name}", "",
          "| Запись | вид | конец речи, с | паузы, с | конец, с | задержка, с | чем | отмены (конец → заговорил) | итог |", "|---|---|---|---|---|---|---|---|---|"]
    per = results[top]
    for t in trecs:
        r = per.get(t.base)
        if r is None:
            continue
        name = f"{CONDITION_RU[t.rec.condition]} #{t.rec.line}"
        if not t.speech:
            L.append(f"| {name} | без речи | — | — | — | — | {'речь!' if r.heard else 'молчание'} | — | {'ложная речь' if r.heard else 'ok'} |")
            continue
        lat = (r.equiv_end_s - t.last_word_end) if r.end_s is not None and not r.interrupted else None
        verdict = "перебил" if r.interrupted else ("нет конца" if r.end_s is None else ("долгий" if lat is not None and lat > 3 else "ok"))
        canc = ", ".join(f"{a:.2f} → {b:.2f}" for a, b in r.cancels) or "—"
        pauses = ", ".join(f"{a:.1f}–{b:.1f}" for a, b in t.mid_pauses) or "—"
        L.append(f"| {name} | {t.rec.kind} | {t.last_word_end:.2f} | {pauses} | {f2(r.end_s)} | {f2(lat)} | {r.ended_by} | {canc} | {verdict} |")
    L += ["", "## Цена", "", f"- Проба целиком (новые вызовы и кэш): ≈ ${sp['run_cost']:.3f}: " +
          ", ".join(f"{k} ${v:.3f}" for k, v in sp["run_cost_by"].items()) + f", судья ${sp['judge_cost_all']:.4f}.",
          f"- В приложении поток стоит как сейчас (${PRICE_LIVE_MIN}/мин звука): способ б меняет только число `commit`, звук тот же. "
          "J3 — ≈ $0,00004 за вопрос.", ""]
    return "\n".join(L) + "\n"
