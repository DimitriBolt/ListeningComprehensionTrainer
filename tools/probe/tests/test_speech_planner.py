"""Python-копия SpeechPlanner (случаи из SpeechPlannerTest.kt) и потоковая нарезка."""
import random
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import speech_planner as sp  # noqa: E402
from speech_planner import Chunk, plan  # noqa: E402
import tutor_dialog as td  # noqa: E402


def pairs(text):
    return [(c.text, c.pause_ms) for c in plan(text)]


def texts(text):
    return [c.text for c in plan(text)]


# ---- случаи из SpeechPlannerTest.kt ---------------------------------------

def test_single_word_and_empty():
    assert pairs("Hello") == [("Hello", 250)]
    assert plan("") == [] and plan("  \n\n ") == []


def test_sentences_and_commas():
    assert pairs("Listen carefully. Repeat after me. Say it slowly, and then say it fast.") == [
        ("Listen carefully.", 1000), ("Repeat", 250), ("after me.", 1000), ("Say it slowly,", 600),
        ("and", 250), ("then say it fast.", 1000)]


def test_paragraph_and_six_words():
    assert pairs("First line.\nSecond line.") == [("First line.", 1600), ("Second line.", 1000)]
    assert pairs("The price is about $3.29 today. Do you drive?") == [
        ("The price is about $3.29 today.", 1000), ("Do you drive?", 1000)]
    assert texts("Can you make a sentence with each word?") == ["Can you make a", "sentence with each word?"]


def test_list_number_and_heading():
    # D45: жирное слово в начале пункта — обычный текст; номер пункта — по-прежнему своя группа.
    assert pairs("Here are two simple rules:\n1. **Make** We use \"make\" when we create something, "
                 "like a cake or a plan.\nCan you make a sentence with each word?") == [
        ("Here are two simple rules:", 1600), ("1.", 400), ("**Make** We use \"make\"", 250),
        ("when we create something,", 600), ("like a cake", 250), ("or a plan.", 1600),
        ("Can you make a", 250), ("sentence with each word?", 1000)]
    assert pairs("Speak slowly. **Tip**") == [("Speak slowly.", 1000), ("**Tip**", 250)]
    assert pairs("Rules:\n1. **Make**") == [("Rules:", 1600), ("1.", 400), ("**Make**", 1100)]
    assert pairs("**Tips for you:**\nSpeak slowly.") == [("**Tips for you:**", 1600), ("Speak slowly.", 1000)]
    assert pairs("2. **Do**:\nWe do tasks.") == [("2.", 400), ("**Do**:", 1600), ("We do tasks.", 1000)]


def test_bold_inside_a_phrase_is_plain_text():
    # D45 (S02d, шаг 5): абзацная пауза перед каждым жирным словом терялась среди лишних длинных пауз.
    assert pairs("We **make** a cake, but we **do** homework. Can you try?") == [
        ("We **make** a cake,", 600), ("but we **do** homework.", 1000), ("Can you try?", 1000)]
    assert pairs("**Make** or **do**\nUse them.") == [("**Make**", 250), ("or **do**", 1600), ("Use them.", 1000)]
    assert all(c.pause_ms != sp.PAUSE_PARAGRAPH_MS for c in plan("I **make** tea and **do** sports every day."))
    assert pairs("1. Listen every day.\n2. Repeat out loud.") == [
        ("1.", 400), ("Listen every day.", 1600), ("2.", 400), ("Repeat out loud.", 1000)]


def test_conjunctions():
    for t in ["I ask you.", "I am sorry.", "I want some tea.", "Ask me again.", "In order to win."]:
        assert texts(t) == [t]
    assert texts("Yes, So we go.") == ["Yes,", "So we go."]
    assert texts("Wait here AND listen.") == ["Wait here", "AND listen."]
    assert texts("Tea or coffee?") == ["Tea", "or coffee?"]


def test_labels_and_numbers():
    assert texts(f"{sp.LABEL_LIVE_WEB} The price is about $3.29 today.") == ["The price is about $3.29 today."]
    assert texts("I said (Live Web Data): hi.") == ["I said (Live Web Data):", "hi."]
    for t in ["We won 2. Then we lost.", "In 2026, prices went up.", "Today is November 16."]:
        assert all(c.pause_ms != sp.PAUSE_LIST_NUMBER_MS for c in plan(t))


def test_wordless_join():
    assert pairs("1. **Make a plan**: write it down.") == [
        ("1.", 400), ("**Make a plan**:", 600), ("write it down.", 1000)]
    assert pairs("Today is 2026. **Note** this.") == [("Today is 2026.", 1000), ("**Note** this.", 1000)]
    for sign in [".", "!", "-", "—"]:
        assert all(sp._has_word(c.text) for c in plan(f"**Tip**{sign} Speak slowly."))


# ---- сноски ----------------------------------------------------------------

def test_clean_citations():
    raw = ("Gas in Tucson costs about $3.05 per gallon today "
           "([gasbuddy.com](https://www.gasbuddy.com/gasprices/arizona/tucson?utm_source=openai)). Do you drive?")
    assert sp.clean_for_speech(raw) == "Gas in Tucson costs about $3.05 per gallon today. Do you drive?"
    assert sp.clean_for_speech("According to [AAA](https://aaa.com/x), prices fell.") == "According to AAA, prices fell."
    assert sp.clean_for_speech("See https://example.com/page for more.") == "See for more."
    assert sp.speech_leftovers(raw) == ["адрес http", "www.", "ссылка markdown", "utm_source"]
    assert sp.speech_leftovers(sp.clean_for_speech(raw)) == []


# ---- потоковая нарезка ------------------------------------------------------

REPLIES = [
    "Listen carefully. Repeat after me. Say it slowly, and then say it fast.",
    "Here are two simple rules:\n1. **Make** We use \"make\" when we create something, like a cake or a plan.\n"
    "2. **Do** We use \"do\" for tasks.\nCan you make a sentence with each word?",
    "Today is 2026. **Note** this. We won 2. Then we lost, but that is fine!",
    "1. **Make a plan**: write it down.\n\n2. Repeat out loud.",
    "Gas in Tucson costs about $3.05 per gallon today "
    "([gasbuddy.com](https://www.gasbuddy.com/gasprices/arizona/tucson?utm_source=openai)). Do you drive to work, "
    "or do you take the bus? I think you meant \"I went\", right?",
    "The sunset in Tucson today is at 5:21 PM ([timeanddate.com](https://www.timeanddate.com/sun/usa/tucson)), "
    "([weather.gov](https://weather.gov/twc)).\n**Tip** - Say it slowly.",
]


def stream_groups(text, pieces):
    s = sp.StreamPlanner()
    out = []
    for p in pieces:
        out += s.feed(p)
    out += s.finish()
    return out, s


def split_random(text, rng):
    pieces, i = [], 0
    while i < len(text):
        n = rng.randint(1, 7)
        pieces.append(text[i:i + n])
        i += n
    return pieces


@pytest.mark.parametrize("text", REPLIES)
def test_stream_equals_whole_text(text):
    expected = plan(sp.clean_for_speech(text))
    rng = random.Random(7)
    for _ in range(40):
        got, s = stream_groups(text, split_random(text, rng))
        assert got == expected and s.divergences == []
    got, _ = stream_groups(text, list(text))  # по одному знаку
    assert got == expected


def test_stream_gives_first_group_early():
    s = sp.StreamPlanner()
    assert s.feed("Listen carefully.") == []
    assert s.feed(" ") == []              # пробелы могут продолжиться
    assert s.feed("R") == []              # первая группа — последняя в префиксе, её держим
    assert s.feed("epeat after me. ") == []  # после последней точки ещё нет знака
    assert s.feed("S") == [Chunk("Listen carefully.", 1000), Chunk("Repeat", 250)]


def test_stream_waits_for_whole_citation():
    s = sp.StreamPlanner()
    s.feed("It costs $3. Today ([gasbuddy.com](https://www.gas. buddy")
    assert sp.safe_cut(s.text) <= s.text.index("(")
    assert all("gasbuddy" not in c.text for c in s.emitted)


def test_safe_cut_rules():
    assert sp.safe_cut("We won 2. Then") == 0          # «2.» — не конец предложения
    assert sp.safe_cut("Hi. ") == 0                      # нет знака после пробелов
    assert sp.safe_cut("Hi. T") == 4
    assert sp.safe_cut("Rules:\n1") == 7
    assert sp.safe_cut("Hi. **Tip. More") == 4           # незакрытый жирный


# --- S02d: жирный не режется посередине, пауза — поздно; ответы из приложения 02c-results.md ---

PROBE_REPLIES = Path(__file__).resolve().parents[3] / "android_app/app/src/test/resources/02c-replies.txt"


def stream_with_late_pauses(text, pieces):
    """Группы и пауза каждой — в момент, когда пришла следующая (или конец), как в приложении."""
    s = sp.StreamPlanner()
    texts, pauses = [], []

    def take(new):
        for c in new:
            if texts:
                pauses.append(s.pause_after(len(texts) - 1))
            texts.append(c.text)

    for p in pieces:
        take(s.feed(p))
    take(s.finish())
    if texts:
        pauses.append(s.pause_after(len(texts) - 1))
    return [Chunk(t, p) for t, p in zip(texts, pauses)], s


def test_safe_cut_not_inside_bold_pair():
    assert sp.safe_cut("a, **b, c") == 3
    assert sp.safe_cut("a, **b, c**") == 3
    assert sp.safe_cut("a, **b, c**, d") == len("a, **b, c**, ")


def test_heading_piece_by_piece_keeps_paragraph_pause():
    # Расхождение пробы S02c (в5 B4 #1). С D45 жирное внутри фразы — обычный текст: пауза запятой.
    text = "Sunset in Tucson today, **Monday, November 23, 2026**, is at about **5:20 PM**.\n\nWill you watch it?"
    got, s = stream_with_late_pauses(text, list(text))
    assert got[0] == Chunk("Sunset in Tucson today,", 600)
    assert got == plan(text) and s.divergences == []
    # Строка-заголовок, пришедшая по одному знаку, — пауза абзаца перед ней.
    text = "Here is one tip.\n**Speak slowly, please**\nTry it now."
    got, s = stream_with_late_pauses(text, list(text))
    assert got[0] == Chunk("Here is one tip.", 1600) and got[2].pause_ms == 1600
    assert got == plan(text) and s.divergences == []


def test_all_probe_replies_stream_like_whole_text():
    replies = [t.strip() for t in PROBE_REPLIES.read_text(encoding="utf-8").split("\n=====\n") if t.strip()]
    assert len(replies) == 90
    for n, text in enumerate(replies):
        expected = plan(sp.clean_for_speech(text))
        rng = random.Random(n)
        ways = [list(text)] + [split_random(text, rng) for _ in range(5)]
        for way in ways:
            got, s = stream_with_late_pauses(text, way)
            assert got == expected and s.divergences == [], f"ответ {n + 1}"


# --- S05c: кавычки — пара (D63); те же случаи — в Kotlin (SpeechPlannerTest, SpeechPlannerCopyTest) ---

def groups(text):
    return [(c.text, c.pause_ms) for c in plan(text)]


def test_quotes_title_is_one_group():
    assert groups('His favorite book is "The Call of the Wild".') == [
        ("His favorite book is", 250), ('"The Call of the Wild".', 1000)]
    assert groups("His story “The Call of the Wild” is famous. Do you like it?")[:2] == [
        ("His story", 250), ("“The Call of the Wild” is famous.", 1000)]


def test_quotes_question_inside_is_not_the_end():
    t = 'Have you read "Who Moved My Cheese?" yet? I liked it.'
    assert t[:td.first_question_end(t + " ", final=True)] == 'Have you read "Who Moved My Cheese?" yet?'
    assert groups(t)[1] == ('"Who Moved My Cheese?" yet?', 1000)
    assert groups("The sign says “Stop!” and you stop.")[0] == ("The sign says “Stop!”", 250)  # «!» внутри — не конец фразы


def test_quotes_comma_inside_the_closing_quote_is_a_comma_pause():
    assert groups('I read "The Call of the Wild," a book about a big dog.')[:2] == [
        ("I read", 250), ('"The Call of the Wild,"', 600)]
    assert groups('My mom loves "Eat, Pray, Love" very much.') == [
        ("My mom loves", 250), ('"Eat, Pray, Love" very much.', 1000)]


def test_quotes_long_quote_keeps_the_usual_rules_but_not_one_word_from_a_quote():
    g = [t for t, _ in groups('He said, "I will go to the big lake with my son, and we will catch many fish there." Do you?')]
    assert g == ["He said,", '"I will go to the', "big lake with my son,", "and we will", 'catch many fish there."', "Do you?"]
    g = [t for t, _ in groups('“Yesterday, I went to the shop and bought some apples and pears.”')]
    assert g[0] != "“Yesterday,"  # одно слово у кавычки не отрезается


def test_quotes_without_a_pair_are_ordinary_text():
    t = 'She said "I am happy. Are you happy? I am.'
    assert groups(t) == [('She said "I am happy.', 1000), ("Are you happy?", 1000), ("I am.", 1000)]
    assert t[:td.first_question_end(t + " ", final=True)] == 'She said "I am happy. Are you happy?'
    assert td.first_question_end(t + " ") is None and td.question_pending(t)  # поток: пока не ясно — ждать
    # закрывающая дальше 30 слов или в другом абзаце — без пары
    far = '"' + " ".join(["word"] * 31) + '"'
    assert sp.quote_pairs(far)[0] == []
    assert sp.quote_pairs('"a\nb"')[0] == []


def test_quotes_stream_waits_for_the_closing_quote():
    assert sp.safe_cut('Hi. "The Call, of') == 4  # внутри открытой пары не режем
    assert sp.safe_cut('Hi. "The Call, of the Wild," a') == len('Hi. "The Call, of the Wild," ')
    for text in ['His favorite book is "The Call of the Wild". It is about a dog. Do you like dogs?',
                 'Have you read "Who Moved My Cheese?" yet? I liked it.',
                 'I read "The Call of the Wild," a book about a big dog. Do you like dogs?',
                 'He said, "I will go to the big lake with my son, and we will catch many fish there." Do you?',
                 'She said "I am happy. Are you happy? I am.',
                 'A "b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w, x, y, z, aa, bb, cc, dd, ee, ff, gg." Ok.']:
        for n in range(6):
            way = list(text) if n == 0 else split_random(text, random.Random(n))
            got, s = stream_with_late_pauses(text, way)
            assert got == plan(text) and s.divergences == [], text


def test_quotes_check_fixture_is_current():
    """fixtures/02c_plans.json — то, что сверяет Kotlin; он должен совпадать с нынешней копией."""
    import json
    import quotes_check as qc
    d = json.loads(qc.AFTER.read_text(encoding="utf-8"))
    assert d["replies"] == [qc.now(t) for t in qc.replies()]
    assert d["cases"] == [qc.now(t) for t in qc.CASES]
