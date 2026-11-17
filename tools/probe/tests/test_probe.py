"""Модульные тесты частей probe.py без сети. Запуск: python -m pytest tools/probe/tests"""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import probe  # noqa: E402

FAKE_KEY = "sk-test-FAKEKEY1234567890abcdef"


# ---- ключ ----------------------------------------------------------------

def write_creds(tmp_path, text):
    p = tmp_path / "credentials.properties"
    p.write_text(text, encoding="utf-8")
    return p


def test_load_key_basic(tmp_path):
    p = write_creds(tmp_path, f"# comment\nother=1\nAPIkey={FAKE_KEY}\n")
    assert probe.load_api_key(p) == FAKE_KEY


def test_load_key_spaces_and_quotes(tmp_path):
    p = write_creds(tmp_path, f'APIkey = "{FAKE_KEY}"  \n')
    assert probe.load_api_key(p) == FAKE_KEY


@pytest.mark.parametrize("text", ["", "other=1\n", "APIkey=\n"])
def test_load_key_missing_line(tmp_path, text):
    p = write_creds(tmp_path, text)
    with pytest.raises(probe.ProbeError):
        probe.load_api_key(p)


def test_load_key_missing_file(tmp_path):
    with pytest.raises(probe.ProbeError) as e:
        probe.load_api_key(tmp_path / "nope.properties")
    assert "APIkey" in str(e.value)


def test_redact_hides_key(tmp_path):
    probe.load_api_key(write_creds(tmp_path, f"APIkey={FAKE_KEY}\n"))
    s = probe.redact(f"Authorization: Bearer {FAKE_KEY} failed")
    assert FAKE_KEY not in s and "***" in s
    assert "sk-abcdefghijkl" not in probe.redact("key sk-abcdefghijklmnop")
    assert "7890" not in probe.redact("Incorrect API key provided: sk-proj-******************7890.")


def test_auth_error_has_no_key():
    msg = probe.auth_error_text(401)
    assert "401" in msg and "sk-" not in msg


def test_main_missing_credentials_exit_code(tmp_path, capsys):
    rc = probe.main(["tutor", "--credentials", str(tmp_path / "x.properties"),
                     "--out", str(tmp_path / "out"), "--docs", str(tmp_path / "docs.md")])
    assert rc == 2
    assert "Нет файла с ключом" in capsys.readouterr().err


# ---- аргументы -----------------------------------------------------------

def test_help_is_russian(capsys):
    with pytest.raises(SystemExit) as e:
        probe.main(["--help"])
    assert e.value.code == 0
    out = capsys.readouterr().out
    assert "использование" in out and "подкоманды" in out and "verbatim" in out


def test_subcommand_help(capsys):
    with pytest.raises(SystemExit):
        probe.main(["verbatim", "-h"])
    out = capsys.readouterr().out
    assert "--limit" in out and "--wav" not in out


def test_parse_args_defaults_filled():
    a = probe.parse_args(["tutor"])
    assert a.cmd == "tutor" and a.voice == probe.DEFAULT_RT_VOICE and a.n == 10 and a.yes is False
    b = probe.parse_args(["all", "--yes", "--n", "4", "--limit", "3"])
    assert b.yes and b.n == 4 and b.limit == 3


def test_parse_args_rejects_unknown_voice():
    with pytest.raises(SystemExit):
        probe.parse_args(["verbatim", "--voice", "nova"])


def test_all_declined_makes_no_requests(tmp_path, capsys):
    args = probe.parse_args(["all", "--credentials", str(tmp_path / "none"), "--out", str(tmp_path / "o"),
                             "--docs", str(tmp_path / "d.md")])
    ctx = probe.Ctx(args)
    assert probe.probe_all(ctx, ask=lambda _: "n") is False
    out = capsys.readouterr().out
    assert "итого" in out and "Отменено" in out


def test_cost_estimate_within_budget():
    est = probe.estimate_cost()
    assert set(probe.PROBE_ORDER) <= set(est)
    assert 0 < est["total"] < 1.0


# ---- нормализация и сравнение -------------------------------------------

@pytest.mark.parametrize("ref,heard", [
    ("What do you think?", "what do you think"),
    ('I think you meant "Yesterday', "I think you meant yesterday."),
    ("November 16,", "November sixteenth"),
    ("2026.", "twenty twenty-six"),
    ("2026.", "two thousand twenty six"),
    ("2026.", "two thousand and twenty-six"),
    ("so we have 38 days.", "So we have thirty-eight days."),
    ("The holidays start on December 24th,", "The holidays start on December twenty-fourth."),
    ("today is about $3.29 per gallon.", "Today is about $3.29 per gallon."),
    ("Okay, tell me.", "OK. Tell me!"),
    ("“Make” — it’s easy", "\"make\", it's easy"),
])
def test_verbatim_matches(ref, heard):
    assert probe.compare_texts(ref, heard)["verdict"] == "verbatim"


def test_contractions_only():
    r = probe.compare_texts("That's a great question.", "That is a great question.")
    assert r["verdict"] == "contractions"


def test_mismatch_diff_and_answer_detection():
    r = probe.compare_texts("What do you think?",
                            "I think practicing every day with a friend is the best way to improve.")
    assert r["verdict"] == "mismatch" and r["suspected_answer"]
    r2 = probe.compare_texts("and", "and then")
    assert r2["verdict"] == "mismatch" and "добавлено «then»" in r2["diff"]
    assert not r2["suspected_answer"]
    r3 = probe.compare_texts("Tell me more.", "Tell me")
    assert "пропущено «more»" in r3["diff"]


def test_empty_transcript():
    assert probe.compare_texts("Hi", "")["verdict"] == "empty"


def test_number_words():
    assert probe.number_words(38) == "thirty eight"
    assert probe.number_words(2026) == "two thousand twenty six"
    assert probe.number_words(2026, use_and=True) == "two thousand and twenty six"
    assert probe.number_words(105, use_and=True) == "one hundred and five"
    assert probe.year_words(1990) == "nineteen ninety"
    assert probe.year_words(2005) is None
    assert probe.ordinal_words(24) == "twenty fourth"
    assert probe.ordinal_words(20) == "twentieth"
    assert probe.ordinal_words(12) == "twelfth"


# ---- разбиение на группы (перенос splitIntoChunks) -----------------------

def test_split_basic_pauses():
    # паузы как в Kotlin: max(10, p - 1000)
    assert probe.split_into_chunks("Hi! How are you?") == [("Hi!", 10), ("How are you?", 10)]
    assert probe.split_into_chunks("One, two.\nThree.") == [("One,", 10), ("two.", 600), ("Three.", 10)]


def test_split_long_chunk_halved():
    words = "one two three four five six seven eight"
    assert probe.split_into_chunks(words) == [("one two three four", 10), ("five six seven eight", 10)]


def test_split_before_conjunction_prefix_like_kotlin():
    # Kotlin-регулярка режет и перед словами, которые лишь начинаются на союз («ask» — «as»)
    assert [c for c, _ in probe.split_into_chunks("I ask you")] == ["I", "ask you"]
    assert [c for c, _ in probe.split_into_chunks("Say it slowly, and then say it")] == \
        ["Say it slowly,", "and", "then say it"]


def test_split_bold_heading():
    chunks = probe.split_into_chunks("Rules:\n**Make** We use it.")
    assert ("**Make**", 600) in chunks


def test_fixture_is_50_chunks_from_replies():
    chunks = probe.load_chunks()
    assert len(chunks) == 50
    assert chunks == probe.fixture_chunks()
    joined = "\n".join(chunks)
    assert any(c.endswith("?") for c in chunks)                     # вопросы
    assert "What do you think?" in chunks and "Tell me more." in chunks
    assert any(c.startswith(("Listen", "Repeat", "Say")) for c in chunks)  # повелительные
    assert any(len(c.split()) <= 2 for c in chunks)                  # обрывки
    assert "2026" in joined and "24th" in joined and "$3.29" in joined  # числа и даты
    assert all(len(c.split()) <= 6 for c in chunks)


def test_tts_text_strips_bold():
    assert probe.tts_text("**Make**") == "Make"


# ---- звук ----------------------------------------------------------------

def test_wav_roundtrip_and_resample():
    pcm = bytes(range(0, 200)) * 24  # 4800 байт = 2400 отсчётов
    wav = probe.pcm_to_wav(pcm, 24000)
    back, rate = probe.wav_to_pcm(wav)
    assert back == pcm and rate == 24000
    out = probe.resample(pcm, 24000, 16000)
    assert len(out) == 2 * 1600
    assert probe.pcm_seconds(probe.silence(1.5, 24000)) == pytest.approx(1.5)


# ---- tutor ---------------------------------------------------------------

def test_tool_schemas_copied_from_kotlin():
    kt = (probe.REPO_ROOT / "android_app/app/src/main/java/com/example/listeningtrainer/TrainerViewModel.kt").read_text()
    for t in probe.tutor_tools():
        fn = t["function"]
        assert f'name = "{fn["name"]}"' in kt
        assert fn["description"] in kt
        for prop in fn["parameters"]["properties"].values():
            assert prop["description"] in kt


def test_tool_call_format_check():
    good = {"id": "call_1", "type": "function",
            "function": {"name": "search_live_web", "arguments": '{"query": "gas price Tucson"}'}}
    assert probe.check_tool_call_format(good) == []
    bad = {"type": "custom", "function": {"name": "x", "arguments": "{oops"}}
    probs = probe.check_tool_call_format(bad)
    assert len(probs) == 3


def test_multipart_repeated_fields():
    d = probe._multipart_data([("model", "m"), ("languages[]", "en"), ("languages[]", "fr")])
    assert d == {"model": "m", "languages[]": ["en", "fr"]}


# ---- результаты ------------------------------------------------------------

def test_results_public_copy_and_redaction(tmp_path):
    probe.load_api_key(write_creds(tmp_path, f"APIkey={FAKE_KEY}\n"))
    docs = tmp_path / "docs" / "00b-results.md"
    r = probe.Results(tmp_path / "out", docs)
    r.put("voices", {"k": FAKE_KEY}, "## voices\n/path/out/voices/marin.wav", "## voices\nбез путей")
    r.put("verbatim", {}, "## verbatim\nok")
    md = (tmp_path / "out" / "results.md").read_text()
    pub = docs.read_text()
    assert md.index("## verbatim") < md.index("## voices")  # порядок как в брифе
    assert "marin.wav" in md and "marin.wav" not in pub and "без путей" in pub
    raw = (tmp_path / "out" / "results.json").read_text()
    assert FAKE_KEY not in raw and FAKE_KEY not in md
    assert json.loads(raw)["probes"]["voices"]["raw"]["k"] == "***"
    # повторный запуск дописывает, а не стирает
    r2 = probe.Results(tmp_path / "out", docs)
    r2.put("tutor", {}, "## tutor")
    assert "## verbatim" in docs.read_text() and "## tutor" in docs.read_text()


def test_failed_probe_does_not_crash_run(tmp_path, monkeypatch):
    args = probe.parse_args(["tutor", "--out", str(tmp_path / "o"), "--docs", str(tmp_path / "d.md")])
    ctx = probe.Ctx(args)

    def boom(_ctx):
        raise RuntimeError("network down")

    monkeypatch.setitem(probe.PROBES, "tutor", boom)
    assert probe.run_probe(ctx, "tutor") is False
    assert "network down" in (tmp_path / "d.md").read_text()


def test_next_steps_depend_on_done_probes(tmp_path):
    args = probe.parse_args(["verbatim", "--out", str(tmp_path / "o"), "--docs", str(tmp_path / "d.md")])
    ctx = probe.Ctx(args)
    ctx.results.put("verbatim", {}, "## v")
    steps = "\n".join(probe.next_steps(ctx))
    assert "voices" in steps and "probe.py all" in steps
    assert ".wav" not in steps and "git add" not in steps
    for n in probe.PROBE_ORDER:
        ctx.results.put(n, {}, f"## {n}")
    steps = "\n".join(probe.next_steps(ctx))
    assert "voices/*.wav" in steps and "Usage" in steps and "git add" in steps
    ctx.results.put("tutor", {"error": "boom"}, "## tutor")
    assert "tutor" in probe.next_steps(ctx)[0] and "git add" not in "\n".join(probe.next_steps(ctx))
