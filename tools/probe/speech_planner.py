"""Нарезка речи учителя для пробы S02c: Python-копия SpeechPlanner и потоковая нарезка.

`plan()` — перенос `android_app/.../conversation/SpeechPlanner.kt` (состояние после S05c: D22, D23, D45, кавычки D63)
с Kotlin на Python, строка в строку, вместе с особенностями. Нужен, чтобы на настоящих потоках
ответа учителя проверить: потоковая нарезка даёт **те же** группы, что нарезка готового текста
(инвариант 1). Эталонные случаи — из `SpeechPlannerTest.kt` (перенесены в tests/test_speech_planner.py).
Если SpeechPlanner.kt меняется, эту копию надо поменять так же.

`StreamPlanner` — копия `StreamingSpeechPlanner.kt` (S02d): текст приходит кусками (`response.output_text.delta`),
группы отдаются в синтез, как только их уже не может изменить продолжение текста.

Правило (почему группы совпадают с нарезкой готового текста):
1. Текст режется только в «безопасной точке»: после знака `, ; : . ! ?` и пробелов за ним, или после
   перевода строки, — и только когда после неё пришёл хотя бы один непробельный знак. Это ровно те места,
   где режет и SpeechPlanner (кроме союзов), и всё, что решает нарезку слева от точки, уже известно:
   пробелы целиком, есть ли в них `\n` (пауза абзаца), начинается ли следующий абзац.
   Точки после одиночной цифры (`2. `, номер пункта) безопасными не считаются — SpeechPlanner там не режет.
2. Незакрытая ссылка или сноска (`[`, `(` без пары) останавливает безопасную точку перед собой:
   сноску нельзя вычистить, пока она не пришла целиком. Жирный `**…**` не режется посередине
   (поправка S02d): точка внутри пары — не точка, незакрытая `**` на последней строке — граница.
   Пары ищутся так же, как в SpeechPlanner: в пределах строки, слева направо.
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
# Заголовок — только строка, целиком жирная (D45, S02e).
_HEADING_LINE_RE = re.compile(r"[ \t]*(?:\d{1,2}\.[ \t]+)?\*\*(?:(?!\*\*)[^\n])+\*\*[ \t]*[:.!?]?[ \t]*")
_LIST_NUMBER_RE = re.compile(r"^\s*(\d{1,2}\.)\s+(\S.*)$", re.S)
_SPLIT_RE = re.compile(
    r"(?<=[,!?;:])\s+|(?<=[.])(?<!\b\d\.)\s+|(?<=[,.;:][\"”])(\s+)"
    r"|\s+(?=(?i:and|but|because|if|when|then|after|before|that|which|who|so|or|although|while|since|as)\b)")
_PARAGRAPH_RE = re.compile(r"\n+")


# --------------------------------------------------------------------------
# Кавычки — пара (S05c, D63): копия Quotes.kt
# --------------------------------------------------------------------------

QUOTE_SHORT_MAX_WORDS = 8
QUOTE_PAIR_MAX_WORDS = 30
_WORD_RE = re.compile(r"[^\W_]+(?:['’][^\W\d_]+)?")


@dataclass(frozen=True)
class QuotePair:
    open: int
    close: int
    words: int

    @property
    def short(self) -> bool:
        return self.words <= QUOTE_SHORT_MAX_WORDS

    def inside(self, at: int) -> bool:
        return self.open < at <= self.close


def _words(text: str, a: int, b: int) -> int:
    return 0 if b <= a else len(_WORD_RE.findall(text[a:b]))


def _letter_or_digit(ch: str) -> bool:
    return ch.isalnum()


def quote_pairs(text: str, final: bool = True) -> tuple[list[QuotePair], int]:
    """Копия Quotes.scan: пары `"…"` / `“…”` в одном абзаце, не дальше 30 слов; (пары, первая нерешённая открывающая или -1)."""
    pairs: list[QuotePair] = []
    i = 0
    n = len(text)
    while i < n:
        if text[i] not in '"“':
            i += 1
            continue
        o = i
        j = o + 1
        close = -1
        limit = False
        words = 0
        in_word = False
        while j < n:
            ch = text[j]
            if ch == "\n":
                limit = True
                break
            if ch in '"”':
                close = j
                break
            letter = _letter_or_digit(ch)
            if letter and not in_word:
                words += 1
                if words > QUOTE_PAIR_MAX_WORDS:
                    limit = True
                    break
            in_word = letter or (in_word and ch in "'’")
            j += 1
        if close >= 0:
            pairs.append(QuotePair(o, close, _words(text, o + 1, close)))
            i = close + 1
        elif limit or final:
            i = o + 1
        else:
            return pairs, o
    return pairs, -1


def quote_cut_allowed(text: str, pairs: list[QuotePair], at: int) -> bool:
    """Копия Quotes.cutAllowed: не внутри короткой пары и не в одном слове от кавычки внутри длинной."""
    return not any(p.inside(at) and (p.short or _words(text, p.open + 1, at) <= 1 or _words(text, at, p.close) <= 1)
                   for p in pairs)


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


def _half_cut(words: list[str], start: int, p_text: str, pairs: list[QuotePair]) -> int | None:
    mid = len(words) // 2
    space_at = [0] * len(words)
    off = start
    for k, w in enumerate(words):
        if k > 0:
            space_at[k] = off - 1
        off += len(w) + 1
    for k in sorted(range(1, len(words)), key=lambda k: (abs(k - mid), k)):
        if quote_cut_allowed(p_text, pairs, space_at[k]):
            return k
    return None


def _end_mark(text: str, start: int, p_text: str, pairs: list[QuotePair]) -> str | None:
    t = text.rstrip()
    if not t:
        return None
    last = start + len(t) - 1
    pair = next((p for p in pairs if p.close == last), None)
    if pair is None:
        return t[-1]
    if last <= pair.open + 1:
        return None
    before = p_text[last - 1]
    return before if before in ",.;:" else None


def plan(text: str) -> list[Chunk]:
    """Копия SpeechPlanner.plan()."""
    spoken = _strip_service_label(text)
    paragraphs = [p for p in _PARAGRAPH_RE.split(spoken) if p.strip()]
    all_chunks: list[Chunk] = []
    for p_index, paragraph in enumerate(paragraphs):
        heading = _HEADING_LINE_RE.fullmatch(paragraph) is not None
        p_text = paragraph
        m = _LIST_NUMBER_RE.fullmatch(paragraph)
        if m:
            all_chunks.append(Chunk(m.group(1), PAUSE_LIST_NUMBER_MS))
            p_text = m.group(2)
        pairs, _ = quote_pairs(p_text)
        raw: list[tuple[str, int]] = []
        frm = 0
        for sm in _SPLIT_RE.finditer(p_text):
            at = sm.start()
            if sm.group(1) is not None and not any(p.close == at - 1 for p in pairs):
                continue
            if not quote_cut_allowed(p_text, pairs, at):
                continue
            raw.append((p_text[frm:at], frm))
            frm = sm.end()
        raw.append((p_text[frm:], frm))
        raw = [r for r in raw if r[0].strip()]
        ped: list[tuple[str, int]] = []
        for r, start in raw:
            words = r.split(" ")
            if len(words) > 6:
                k = _half_cut(words, start, p_text, pairs)
                if k is None:
                    ped.append((r, start))
                else:
                    head = " ".join(words[:k])
                    ped.append((head, start))
                    ped.append((" ".join(words[k:]), start + len(head) + 1))
            else:
                ped.append((r, start))
        for c_index, (t, start) in enumerate(ped):
            s = t.strip()
            end = _end_mark(t, start, p_text, pairs)
            if c_index == len(ped) - 1 and p_index < len(paragraphs) - 1:
                pause = PAUSE_PARAGRAPH_MS
            elif heading and c_index == len(ped) - 1:
                pause = PAUSE_HEADING_MS
            elif end in (".", "!", "?"):
                pause = PAUSE_SENTENCE_MS
            elif end in (",", ":", ";"):
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


# Курсив (D57, копия SpeechText.kt): одиночная «*», не часть «**», — и непарная; «_» по краю слова.
_ITALIC_STAR_RE = re.compile(r"(?<!\*)\*(?!\*)")
_EDGE_UNDERSCORE_RE = re.compile(r"(?<![^\W_]|_)_+|_+(?![^\W_]|_)")


def clean_for_speech(text: str) -> str:
    """Убирает сноски, ссылки и адреса, которые пришли в тексте ответа, и разметку курсива (текст остаётся)."""
    s = _PAREN_LINK_RE.sub("", text)
    s = _INLINE_LINK_RE.sub(lambda m: m.group(1), s)
    s = _BARE_URL_RE.sub("", s)
    s = _ITALIC_STAR_RE.sub("", s)
    s = _EDGE_UNDERSCORE_RE.sub("", s)
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

_SAFE_RE = re.compile(r"(?<=[,;:!?])\s+|(?<=[.])(?<!\b\d\.)\s+|\n\s*|(?<=[,.;:][\"”])(\s+)")
_BOLD_PAIR_RE = re.compile(r"\*\*.*?\*\*")
_REGEX_SPACE = " \t\n\x0b\x0c\r"  # \s регулярных выражений Java (как в Kotlin)


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
    # Жирный «**…**»: пары — как у SpeechPlanner (в пределах строки, слева направо).
    # Незакрытая «**» на последней строке — граница; точка внутри пары — не точка (поправка S02d).
    pairs = [(m.start(), m.end()) for m in _BOLD_PAIR_RE.finditer(text)]
    last_line = text.rfind("\n") + 1
    for m in re.finditer(r"\*\*", text):
        if not any(a <= m.start() < b for a, b in pairs) and m.start() >= last_line:
            limit = min(limit, m.start())
    # Кавычки (S05c, D63): точка внутри пары — не точка; нерешённая открывающая — граница.
    qpairs, undecided = quote_pairs(text, final=False)
    if undecided >= 0:
        limit = min(limit, undecided)
    cut = 0
    for m in _SAFE_RE.finditer(text, 0, limit):
        end = m.end()
        # после точки должен прийти непробельный знак, иначе пробелы могут продолжиться
        if end >= len(text) or text[end] in _REGEX_SPACE:
            continue
        if any(a < end < b for a, b in pairs):
            continue
        if any(q.open < end <= q.close for q in qpairs):
            continue
        if m.group(1) is not None and not any(q.close == m.start() - 1 for q in qpairs):
            continue  # `,"` — точка, только если кавычка закрывает пару (там режет и plan)
        cut = end
    return cut


@dataclass
class StreamPlanner:
    """Текст кусками → группы, как только их уже не изменит продолжение.
    Копия StreamingSpeechPlanner.kt: пауза после группы берётся позже (`pause_after`), из самой
    свежей нарезки; разошедшийся текст отданной группы записывается, нарезка идёт дальше."""
    clean: bool = True
    text: str = ""
    emitted: list[Chunk] = field(default_factory=list)
    divergences: list[str] = field(default_factory=list)
    latest: list[Chunk] = field(default_factory=list)
    last_cut: int = 0

    def _plan(self, s: str) -> list[Chunk]:
        return plan(clean_for_speech(s) if self.clean else s)

    def _emit_from(self, chunks: list[Chunk]) -> list[Chunk]:
        n = min(len(self.emitted), len(chunks))
        for i in range(n):
            if chunks[i].text != self.emitted[i].text:
                self.divergences.append(f"группа {i + 1}: уже отдано {self.emitted[i]}, новая нарезка {chunks[i]}")
        new = chunks[len(self.emitted):]
        self.emitted.extend(new)
        return new

    def feed(self, delta: str) -> list[Chunk]:
        """Добавить кусок текста; вернуть новые группы, готовые к синтезу."""
        self.text += delta
        cut = safe_cut(self.text)
        if cut <= self.last_cut:
            return []
        self.last_cut = cut
        chunks = self._plan(self.text[:cut])
        self.latest = chunks
        return self._emit_from(chunks[:-1])

    def finish(self) -> list[Chunk]:
        """Поток кончился: отдать остаток."""
        chunks = self._plan(self.text)
        self.latest = chunks
        return self._emit_from(chunks)

    def pause_after(self, index: int) -> int:
        """Пауза после отданной группы — по самой свежей нарезке (когда пришла следующая или конец)."""
        if index < len(self.latest) and self.latest[index].text == self.emitted[index].text:
            return self.latest[index].pause_ms
        return self.emitted[index].pause_ms
