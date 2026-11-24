"""Нарезка речи учителя для пробы S02c: Python-копия SpeechPlanner и потоковая нарезка.

`plan()` — перенос `android_app/.../conversation/SpeechPlanner.kt` (состояние после S02b: D22, D23)
с Kotlin на Python, строка в строку, вместе с особенностями. Нужен, чтобы на настоящих потоках
ответа учителя проверить: потоковая нарезка даёт **те же** группы, что нарезка готового текста
(инвариант 1). Эталонные случаи — из `SpeechPlannerTest.kt` (перенесены в tests/test_speech_planner.py).
Если SpeechPlanner.kt меняется, эту копию надо поменять так же.

`StreamPlanner` — кандидат для S02d: текст приходит кусками (`response.output_text.delta`),
группы отдаются в синтез, как только их уже не может изменить продолжение текста.

Правило (почему группы совпадают с нарезкой готового текста):
1. Текст режется только в «безопасной точке»: после знака `, ; : . ! ?` и пробелов за ним, или после
   перевода строки, — и только когда после неё пришёл хотя бы один непробельный знак. Это ровно те места,
   где режет и SpeechPlanner (кроме союзов), и всё, что решает нарезку слева от точки, уже известно:
   пробелы целиком, есть ли в них `\n` (пауза абзаца), начинается ли следующий абзац.
   Точки после одиночной цифры (`2. `, номер пункта) безопасными не считаются — SpeechPlanner там не режет.
2. Незакрытая ссылка или сноска (`[`, `(` без пары) останавливает безопасную точку перед собой:
   сноску нельзя вычистить, пока она не пришла целиком.
3. Префикс до безопасной точки чистится от сносок (`clean_for_speech`) и нарезается `plan()`;
   отдаются все группы, кроме **последней**: её ещё может изменить продолжение (знак без букв
   присоединится к ней по D23, жирный заголовок сразу за ней сделает паузу абзацной).
4. Когда поток кончился — нарезается весь текст и отдаётся остаток.
Если новая нарезка префикса когда-нибудь разойдётся с уже отданными группами, это записывается
(`divergences`) — проба проверяет правило на настоящих ответах.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

LABEL_LIVE_WEB = "(Live Web Data):"
LABEL_SEARCH_FALLBACK = "(Search attempted, fallback):"
SERVICE_LABELS = [LABEL_LIVE_WEB, LABEL_SEARCH_FALLBACK]

PAUSE_PARAGRAPH_MS = 1600
PAUSE_HEADING_MS = 1100
PAUSE_SENTENCE_MS = 1000
PAUSE_COMMA_MS = 600
PAUSE_INSIDE_MS = 250
PAUSE_LIST_NUMBER_MS = 400

# Регулярные выражения — как в SpeechPlanner.kt.
_HEADING_RE = re.compile(r"(?m)\s*((?:^[ \t]*\d{1,2}\.[ \t]+)?\*\*.*?\*\*)")
_HEADING_CHUNK_RE = re.compile(r"(?:\d{1,2}\.\s+)?\*\*.*\*\*")
_LIST_NUMBER_RE = re.compile(r"^\s*(\d{1,2}\.)\s+(\S.*)$", re.S)
_SPLIT_RE = re.compile(
    r"(?<=[,!?;:])\s+|(?<=[.])(?<!\b\d\.)\s+"
    r"|\s+(?=(?i:and|but|because|if|when|then|after|before|that|which|who|so|or|although|while|since|as)\b)")
_PARAGRAPH_RE = re.compile(r"\n+")


@dataclass(frozen=True)
class Chunk:
    text: str
    pause_ms: int


def _strip_service_label(text: str) -> str:
    trimmed = text.lstrip()
    for label in SERVICE_LABELS:
        if trimmed.startswith(label):
            return trimmed[len(label):].lstrip()
    return text


def _has_word(s: str) -> bool:
    return any(ch.isalnum() for ch in s)


def _join_wordless(chunks: list[Chunk]) -> list[Chunk]:
    out: list[Chunk] = []
    pending = ""
    for c in chunks:
        if _has_word(c.text):
            out.append(c if not pending else Chunk(f"{pending} {c.text}", c.pause_ms))
            pending = ""
            continue
        if not out:
            pending = (pending + " " + c.text).strip()
            continue
        prev = out[-1]
        gap = "" if c.text[0] in ".,:;!?)…" else " "
        out[-1] = Chunk(prev.text + gap + c.text, max(prev.pause_ms, c.pause_ms))
    return out


def plan(text: str) -> list[Chunk]:
    """Копия SpeechPlanner.plan()."""
    spoken = _strip_service_label(text)
    prepared = _HEADING_RE.sub(lambda m: "\n" + m.group(1) + "\n", spoken)
    paragraphs = [p for p in _PARAGRAPH_RE.split(prepared) if p.strip()]
    all_chunks: list[Chunk] = []
    for p_index, paragraph in enumerate(paragraphs):
        p_text = paragraph
        m = _LIST_NUMBER_RE.fullmatch(paragraph)
        if m:
            all_chunks.append(Chunk(m.group(1), PAUSE_LIST_NUMBER_MS))
            p_text = m.group(2)
        raw = [c for c in _SPLIT_RE.split(p_text) if c.strip()]
        ped: list[str] = []
        for r in raw:
            words = r.split(" ")
            if len(words) > 6:
                mid = len(words) // 2
                ped.append(" ".join(words[:mid]))
                ped.append(" ".join(words[mid:]))
            else:
                ped.append(r)
        for c_index, t in enumerate(ped):
            s = t.strip()
            if c_index == len(ped) - 1 and p_index < len(paragraphs) - 1:
                pause = PAUSE_PARAGRAPH_MS
            elif _HEADING_CHUNK_RE.fullmatch(s):
                pause = PAUSE_HEADING_MS
            elif s.endswith((".", "!", "?")):
                pause = PAUSE_SENTENCE_MS
            elif s.endswith((",", ":", ";")):
                pause = PAUSE_COMMA_MS
            else:
                pause = PAUSE_INSIDE_MS
            all_chunks.append(Chunk(s, pause))
    return _join_wordless(all_chunks)


# --------------------------------------------------------------------------
# Сноски и ссылки поиска: вслух не читаются
# --------------------------------------------------------------------------

# «([example.com](https://…))» — так Responses вставляет сноску url_citation прямо в текст.
_PAREN_LINK_RE = re.compile(r"[ \t]*\(\s*\[[^\]\n]*\]\([^)\s]*\)(?:\s*,?\s*\[[^\]\n]*\]\([^)\s]*\))*\s*\)")
# «[текст](https://…)» внутри фразы: текст оставляем, адрес убираем.
_INLINE_LINK_RE = re.compile(r"\[([^\]\n]*)\]\([^)\s]*\)")
_BARE_URL_RE = re.compile(r"[ \t]*\(?\s*(?:https?://|www\.)\S+?\s*\)?(?=[\s.,;:!?]*(?:\s|$))")
_SPACE_BEFORE_PUNCT_RE = re.compile(r"[ \t]+([.,;:!?])")
_MULTI_SPACE_RE = re.compile(r"[ \t]{2,}")


def clean_for_speech(text: str) -> str:
    """Убирает сноски, ссылки и адреса, которые пришли в тексте ответа."""
    s = _PAREN_LINK_RE.sub("", text)
    s = _INLINE_LINK_RE.sub(lambda m: m.group(1), s)
    s = _BARE_URL_RE.sub("", s)
    s = _SPACE_BEFORE_PUNCT_RE.sub(r"\1", s)
    s = _MULTI_SPACE_RE.sub(" ", s)
    return s


def speech_leftovers(text: str) -> list[str]:
    """Что в тексте всё ещё помешало бы озвучке: адреса, ссылки, разметка сносок."""
    found = []
    for pat, name in ((r"https?://", "адрес http"), (r"www\.", "www."), (r"\]\(", "ссылка markdown"),
                      (r"utm_source", "utm_source"), (r"【|†", "сноска 【†】"), (r"\[\d+\]", "сноска [1]")):
        if re.search(pat, text):
            found.append(name)
    return found


# --------------------------------------------------------------------------
# Потоковая нарезка
# --------------------------------------------------------------------------

_SAFE_RE = re.compile(r"(?<=[,;:!?])\s+|(?<=[.])(?<!\b\d\.)\s+|\n\s*")


def safe_cut(text: str) -> int:
    """Позиция последней безопасной точки (см. правило в начале файла); 0 — её нет."""
    limit = len(text)
    # Незакрытая ссылка или сноска: безопасная точка — только до неё.
    depth_sq = depth_par = 0
    first_open = None
    for i, ch in enumerate(text):
        if ch == "[":
            if depth_sq == 0 and depth_par == 0:
                first_open = i
            depth_sq += 1
        elif ch == "]" and depth_sq:
            depth_sq -= 1
        elif ch == "(":
            if depth_sq == 0 and depth_par == 0:
                first_open = i
            depth_par += 1
        elif ch == ")" and depth_par:
            depth_par -= 1
        if depth_sq == 0 and depth_par == 0:
            first_open = None
    if first_open is not None:
        limit = first_open
    # Незакрытый жирный заголовок «**…» — тоже только до него.
    bold = [m.start() for m in re.finditer(r"\*\*", text)]
    if len(bold) % 2:
        limit = min(limit, bold[-1])
    cut = 0
    for m in _SAFE_RE.finditer(text, 0, limit):
        # после точки должен прийти непробельный знак, иначе пробелы могут продолжиться
        if m.end() < len(text) and not text[m.end()].isspace():
            cut = m.end()
    return cut


@dataclass
class StreamPlanner:
    """Текст кусками → группы, как только их уже не изменит продолжение."""
    clean: bool = True
    text: str = ""
    emitted: list[Chunk] = field(default_factory=list)
    divergences: list[str] = field(default_factory=list)

    def _plan(self, s: str) -> list[Chunk]:
        return plan(clean_for_speech(s) if self.clean else s)

    def _emit_from(self, chunks: list[Chunk]) -> list[Chunk]:
        n = len(self.emitted)
        if chunks[:n] != self.emitted:
            self.divergences.append(f"уже отдано {self.emitted[:n]}, новая нарезка {chunks[:n]}")
            return []
        new = chunks[n:]
        self.emitted.extend(new)
        return new

    def feed(self, delta: str) -> list[Chunk]:
        """Добавить кусок текста; вернуть новые группы, готовые к синтезу."""
        self.text += delta
        cut = safe_cut(self.text)
        if cut == 0:
            return []
        chunks = self._plan(self.text[:cut])
        return self._emit_from(chunks[:-1]) if len(chunks) > 1 else []

    def finish(self) -> list[Chunk]:
        """Поток кончился: отдать остаток."""
        return self._emit_from(self._plan(self.text))
