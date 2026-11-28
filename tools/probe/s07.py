"""Проба S07 — паузы по потребности (`docs/stages/07-brief.md`, п. 4; D97, D98).

Две части (`--part`, по умолчанию обе):

- `durations` — длительность звука каждой группы 90 ответов пробы S02c (`02c-replies.txt`; реплика — до первого вопроса, D48,
  как звучит в приложении). Звук — синтез голосом приложения (`SPEECH_VOICE`, `SPEECH_SPEED` из `ModelConfig.kt`), кэш —
  `out/07/synth/` (вне Git), повторный прогон бесплатен. Длительность — по звуку без тишины по краям (`speech_ms`, копия
  `PauseRule.speechMs`). Для нескольких долей: паузы по типам знаков (медиана, p90), прирост длины реплики, самые длинные
  паузы. Сверх брифа — слоги и плотность (слоги в секунду): объясняют ли они паузу лучше секунд.
- `faster` — команда «Faster» (S07) и похожие короткие ответы («Fast food.», «Breakfast.», «First.») на синтезе тремя голосами,
  файлом (короткая реплика — текст файла главный, D83) и потоком, с подсказкой команд приложения. Узнавание — копией
  `VoiceCommand.of` (`s05.command_of`). Плюс тексты 30 коротких ответов из `05b-probes.json` заново по новому правилу (без
  сети).

Итог — `docs/research/07-pauses.md` (+ `.json`).
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import math
import re
import statistics
import time
from datetime import datetime

import probe
import s05
import s05b
import speech_planner as sp
import tutor_dialog as td
from probe import ProbeError, log, redact

REPLIES = probe.REPO_ROOT / "android_app/app/src/test/resources/02c-replies.txt"
DOCS_MD = probe.REPO_ROOT / "docs" / "research" / "07-pauses.md"
DOCS_JSON = probe.REPO_ROOT / "docs" / "research" / "07-pauses.json"
PAUSE_RULE_KT = s05.KT_ROOT / "conversation" / "PauseRule.kt"
PARTS = ("durations", "faster")
SHARES = [0.0, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35]
PARALLEL = 1  # сессий синтеза сразу (при 4 — предел TPM Realtime)

# ---- копия PauseRule (сверяет tests/test_s07.py) ------------------------------------------------------------------

WINDOW_MS = 10       # окно уровня звука
EDGE_DB = 35.0       # окно тише самого громкого на столько дБ — тишина (края звука)


def speech_ms(pcm: bytes, rate: int = probe.RT_RATE) -> int:
    """Копия `PauseRule.speechMs`: от первого до последнего окна [WINDOW_MS] не тише самого громкого окна на [EDGE_DB] дБ."""
    import numpy as np
    x = np.frombuffer(pcm, dtype=np.int16).astype(np.float64)
    n = rate * WINDOW_MS // 1000
    k = len(x) // n
    if k == 0:
        return 0
    e = (x[: k * n].reshape(k, n) ** 2).sum(axis=1)  # энергия окна; сравнение по энергии = по RMS
    top = e.max()
    if top <= 0:
        return 0
    loud = np.nonzero(e >= top * 10 ** (-EDGE_DB / 10))[0]
    return int((loud[-1] - loud[0] + 1) * WINDOW_MS)


def pause_ms(mark_ms: int, audio_ms: int, share: float, scale: float = 1.0, cap: int = 3000, factor: float = 1.0) -> int:
    """Копия `PauseRule.pauseMs`: множитель × (пауза по знаку + доля × звук группы), предел; «Slower» (factor) — поверх
    предела (решение пользователя в S07)."""
    return round(min(cap, scale * (mark_ms + share * audio_ms)) * factor)


# ---- слоги (оценка, сверх брифа) ------------------------------------------------------------------------------------

def syllables(text: str) -> int:
    """Грубая оценка слогов: группы гласных в слове, немое конечное «e»; число — по слогам его слов."""
    total = 0
    for w in re.findall(r"[A-Za-z']+|\d+", text):
        if w.isdigit():
            total += max(1, len(probe.number_words(int(w)).replace("-", " ").split()) * 2 - 1) if len(w) < 5 else 4
            continue
        w = w.lower().replace("'", "")
        n = len(re.findall(r"[aeiouy]+", w))
        if w.endswith("e") and not w.endswith(("le", "ee", "ye")) and n > 1:
            n -= 1
        total += max(1, n)
    return total


# ---- 1. длительности групп ------------------------------------------------------------------------------------------

KINDS = {1600: "абзац", 1100: "заголовок", 1000: "предложение", 600: ", : ;", 400: "номер пункта", 250: "внутри"}


def replies() -> list[str]:
    return [t.strip() for t in REPLIES.read_text(encoding="utf-8").split("\n=====\n") if t.strip()]


def reply_groups(text: str) -> list[tuple[str, int]]:
    """Группы реплики так, как она звучит: до первого вопроса (D48), нарезка копией `SpeechPlanner`."""
    end = td.first_question_end(text + " ", final=True)
    spoken = text[:end] if end else text
    return [(c.text, c.pause_ms) for c in sp.plan(sp.clean_for_speech(spoken))]


def synth_text(group: str) -> str:
    return group.replace("**", "")


def _cache_path(ctx, voice: str, speed: float, text: str):
    h = hashlib.sha1(f"{voice}|{speed}|{text}".encode()).hexdigest()[:16]
    return ctx.out / "07" / "synth" / f"{h}.wav"


async def _synth_parallel(key: str, timeout: float, voice: str, speed: float, texts: list[str]) -> list[dict]:
    parts = [texts[i::PARALLEL] for i in range(PARALLEL)]

    async def one(part):
        out = []
        if not part:
            return out
        async with probe.Realtime(key, timeout, probe.REALTIME_URL.format(model=probe.M_REALTIME)) as rt:
            await rt.update_session(probe.synth_session(voice, speed, 1))
            for t in part:
                r = await probe.rt_say(rt, t)
                for _ in range(3):
                    if not (r["error"] and "rate_limit" in str(r["error"])):
                        break
                    await asyncio.sleep(5)
                    r = await probe.rt_say(rt, t)
                out.append(r)
        return out
    got = await asyncio.gather(*[one(p) for p in parts])
    res: dict[str, dict] = {}
    for part, rows in zip(parts, got):
        for t, r in zip(part, rows):
            res[t] = r
    return [res[t] for t in texts]


def synth_cached(ctx, texts: list[str], spend: dict, budget: float) -> dict[str, bytes]:
    voice = s05.kotlin_const("SPEECH_VOICE")
    speed = float(s05.kotlin_const("SPEECH_SPEED"))
    out: dict[str, bytes] = {}
    for t in texts:
        p = _cache_path(ctx, voice, speed, t)
        if p.exists():
            pcm = probe.wav_to_pcm(p.read_bytes())[0]
            if s05b.peak(pcm) >= s05b.SILENT_PEAK:
                out[t] = pcm
    for attempt in range(3):
        missing = [t for t in texts if t not in out]
        if not missing:
            break
        log(f"  синтез {len(missing)} групп ({voice}, {speed}), попытка {attempt + 1}")
        for i in range(0, len(missing), 40):
            if sum(spend.values()) > budget:
                raise ProbeError(f"бюджет ${budget} исчерпан (потрачено ${sum(spend.values()):.3f})")
            batch = missing[i:i + 40]
            got = asyncio.run(_synth_parallel(ctx.key, ctx.timeout, voice, speed, batch))
            for t, r in zip(batch, got):
                spend["synth"] += probe.rt_cost(r["usage"])
                if r["error"] or not r["pcm"]:
                    log(f"  синтез «{t}»: {r['error'] or 'нет звука'}")
                    continue
                if s05b.peak(r["pcm"]) < s05b.SILENT_PEAK:
                    log(f"  синтез «{t}» — тишина, ещё раз")
                    continue
                if probe.compare_texts(t, r["transcript"])["verdict"] in ("mismatch", "empty"):
                    log(f"  синтез «{t}» — расшифровка «{r['transcript']}» не та, ещё раз")
                    continue
                p = _cache_path(ctx, voice, speed, t)
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(probe.pcm_to_wav(r["pcm"], probe.RT_RATE))
                out[t] = r["pcm"]
            log(f"    готово {len(out)} из {len(texts)}, ≈ ${sum(spend.values()):.3f}")
    return out


def _q(xs: list[float], q: float) -> float | None:
    if not xs:
        return None
    s = sorted(xs)
    return s[min(len(s) - 1, int(round(q * (len(s) - 1))))]


def _corr(a: list[float], b: list[float]) -> float:
    return statistics.correlation(a, b) if len(a) > 2 else float("nan")


def part_durations(ctx, spend: dict, budget: float) -> dict:
    texts = replies()
    plans = [reply_groups(t) for t in texts]
    unique = sorted({synth_text(g) for plan in plans for g, _ in plan if any(ch.isalnum() for ch in g)})
    log(f"  ответов {len(texts)}, групп {sum(len(p) for p in plans)}, разных {len(unique)}")
    pcm = synth_cached(ctx, unique, spend, budget)
    groups: dict[str, dict] = {}
    for t in unique:
        if t not in pcm:
            continue
        x = pcm[t]
        total = round(len(x) / 2 / probe.RT_RATE * 1000)
        sm = speech_ms(x)
        groups[t] = {"total_ms": total, "speech_ms": sm, "words": len(t.split()), "syllables": syllables(t)}
    rows = []  # каждая звучащая группа каждой реплики: пауза после неё (не после последней)
    replies_out = []
    for i, plan in enumerate(plans):
        rep = {"index": i + 1, "groups": []}
        for j, (g, mark) in enumerate(plan):
            d = groups.get(synth_text(g))
            ms = d["speech_ms"] if d else 0
            rep["groups"].append({"text": g, "mark": mark, "speech_ms": ms, "last": j == len(plan) - 1})
            if j < len(plan) - 1:
                rows.append({"reply": i + 1, "text": g, "mark": mark, "speech_ms": ms,
                             "syllables": groups[synth_text(g)]["syllables"] if d else 0})
        replies_out.append(rep)

    edge = [(g["total_ms"] - g["speech_ms"]) for g in groups.values()]
    sm_all = [g["speech_ms"] for g in groups.values()]
    syl = [g["syllables"] for g in groups.values()]
    wrd = [g["words"] for g in groups.values()]
    dens = [g["syllables"] / (g["speech_ms"] / 1000) for g in groups.values() if g["speech_ms"] > 0]
    by_share = []
    for share in SHARES:
        by_kind = {}
        for mark, name in KINDS.items():
            ps = [pause_ms(r["mark"], r["speech_ms"], share) for r in rows if r["mark"] == mark]
            if ps:
                by_kind[name] = {"n": len(ps), "median": _q(ps, 0.5), "p90": _q(ps, 0.9), "max": max(ps)}
        growth = []
        for rep in replies_out:
            gs = rep["groups"]
            sound = sum(g["speech_ms"] for g in gs)
            old = sound + sum(g["mark"] for g in gs if not g["last"])
            new = sound + sum(pause_ms(g["mark"], g["speech_ms"], share) for g in gs if not g["last"])
            if old:
                growth.append((new - old) / old * 100)
        short = [share * r["speech_ms"] for r in rows if 500 <= r["speech_ms"] <= 900]
        long_ = [share * r["speech_ms"] for r in rows if 2200 <= r["speech_ms"] <= 2800]
        allp = sorted(((pause_ms(r["mark"], r["speech_ms"], share), r) for r in rows), key=lambda x: -x[0])
        by_share.append({
            "share": share, "kinds": by_kind,
            "growth_median": _q(growth, 0.5), "growth_p10": _q(growth, 0.1), "growth_p90": _q(growth, 0.9),
            "add_short_median": _q(short, 0.5), "add_long_median": _q(long_, 0.5),
            "n_short": len(short), "n_long": len(long_),
            "capped": sum(1 for p, _ in allp if p >= 3000),
            "longest": [{"pause": p, "mark": r["mark"], "speech_ms": r["speech_ms"], "text": r["text"], "reply": r["reply"]}
                        for p, r in allp[:8]],
        })
    return {
        "replies": len(texts), "groups_total": sum(len(p) for p in plans), "groups_unique": len(unique),
        "synthesized": len(groups), "pauses": len(rows),
        "speech_ms": {"median": _q(sm_all, 0.5), "p10": _q(sm_all, 0.1), "p90": _q(sm_all, 0.9), "max": max(sm_all)},
        "edge_ms": {"median": _q(edge, 0.5), "p90": _q(edge, 0.9), "max": max(edge)},
        "corr": {"syllables": round(_corr(syl, sm_all), 3), "words": round(_corr(wrd, sm_all), 3)},
        "density": {"median": _q(dens, 0.5), "p10": _q(dens, 0.1), "p90": _q(dens, 0.9)},
        "by_share": by_share, "groups": groups,
    }


# ---- 2. команда «Faster» --------------------------------------------------------------------------------------------

FASTER_ITEMS = [
    ("Faster.", "faster"), ("Faster, please.", "faster"), ("Speed up.", "faster"), ("Quicker.", "faster"),
    ("More quickly.", "faster"), ("Speak faster.", "faster"), ("A bit faster.", "faster"),
    # не команды
    ("Fast food.", None), ("Breakfast.", None), ("First.", None), ("Fasting.", None), ("Fast car.", None),
    ("Last.", None), ("Past.", None), ("Fact.", None), ("Vast.", None), ("Forest.", None), ("Fashion.", None),
    ("Quick lunch.", None), ("Faster than me.", None), ("Festival.", None), ("Feast.", None),
]
FASTER_VARIANTS = [("file", True), ("live", True)]
FASTER_VOICES = ["alloy", "coral"]  # два голоса из трёх S05b: бюджет $0,50 почти ушёл на длительности


def part_faster(ctx, spend: dict, budget: float) -> dict:
    phrases, maxw = s05.command_phrases(), s05.command_max_words()
    rows = []
    for voice in FASTER_VOICES:
        if sum(spend.values()) > budget:
            raise ProbeError(f"бюджет ${budget} исчерпан")
        pcms, _ = s05b._synth(ctx, voice, [u for u, _ in FASTER_ITEMS], spend)
        for path, hint in FASTER_VARIANTS:
            p = s05b.prompt(hint)
            if path == "file":
                res = s05b._file_all(ctx, [s05b._clip16(x, s05b.FILE_PAD_BEFORE_S, s05b.FILE_PAD_AFTER_S) for x in pcms], p, spend)
                heard = [r["text"] for r in res]
            else:
                sessions = asyncio.run(s05._transcribe_all(ctx.key, ctx.timeout, [s05._pcm16_from24(x) for x in pcms], p))
                heard = [s05._final_text(s) for s in sessions]
                spend["live"] += sum(s05._live_seconds(s) for s in sessions) * s05.probe_live_price() / 60
            for (said, want), h in zip(FASTER_ITEMS, heard):
                got = s05.command_of(h or "", phrases, maxw)
                rows.append({"voice": voice, "path": path, "said": said, "want": want, "heard": h, "got": got})
            ok = sum(1 for r in rows[-len(FASTER_ITEMS):] if r["got"] == r["want"])
            log(f"  {voice:6} {path:4}: верно {ok} из {len(FASTER_ITEMS)}")
    # Тексты коротких ответов S05b — заново по новому правилу (без сети).
    old = (s05b.load().get("commands") or {}).get("rows", [])
    redo = [{"said": r["said"], "heard": r["heard"], "path": r["path"], "hint": r["hint"], "voice": r["voice"],
             "want": r["want"], "got": s05.command_of(r["heard"] or "", phrases, maxw)} for r in old]
    cmd = [r for r in rows if r["want"]]
    ans = [r for r in rows if not r["want"]]
    return {
        "rows": rows,
        "commands_ok": sum(1 for r in cmd if r["got"] == r["want"]), "commands_total": len(cmd),
        "false": sum(1 for r in ans if r["got"]), "answers_total": len(ans),
        "s05b_total": len(redo), "s05b_wrong": [r for r in redo if r["got"] != r["want"]],
        "s05b_false_faster": sum(1 for r in redo if r["got"] == "faster"),
    }


# ---- запуск и отчёт ---------------------------------------------------------------------------------------------------

def estimate(parts: list[str]) -> float:
    e = 0.0
    if "durations" in parts:
        e += 437 * 0.0009
    if "faster" in parts:
        n = len(FASTER_VOICES) * len(FASTER_ITEMS)
        e += n * 0.0009 + n * (3.0 * 0.017 / 60) + n * (1.5 * 0.0045 / 60)
    return e


def probe_s07(ctx, ask=input) -> bool:
    chosen = getattr(ctx.args, "part_s07", None)
    parts = list(PARTS) if not chosen else [p for p in chosen if p in PARTS]
    budget = getattr(ctx.args, "s07_budget", 0.5)
    est = estimate(parts)
    print(f"Проба S07: {', '.join(parts)}. Оценка (сверху, без кэша): ≈ ${est:.2f}; бюджет ${budget}.")
    if not ctx.args.yes and ask("Запустить? [y/N] ").strip().lower() not in ("y", "yes", "д", "да"):
        print("Отменено.")
        return False
    ctx.key
    data = load()
    spend = {"synth": 0.0, "live": 0.0, "file": 0.0}
    t0 = time.perf_counter()
    fns = {"durations": part_durations, "faster": part_faster}
    for p in parts:
        log(f"\n== {p} ==")
        data[p] = {**fns[p](ctx, spend, budget), "date": datetime.now().isoformat(timespec="seconds")}
    data.setdefault("spend_runs", []).append({"date": datetime.now().isoformat(timespec="seconds"), "parts": parts,
                                              "spend": {k: round(v, 5) for k, v in spend.items()},
                                              "elapsed_s": round(time.perf_counter() - t0, 1)})
    save(data)
    log(f"\nПотрачено ≈ ${sum(spend.values()):.3f}. Итог: {DOCS_MD}")
    return True


def save(data: dict) -> None:
    DOCS_JSON.parent.mkdir(parents=True, exist_ok=True)
    DOCS_JSON.write_text(redact(json.dumps(data, ensure_ascii=False, indent=1)), encoding="utf-8")
    DOCS_MD.write_text(redact(render(data)), encoding="utf-8")


def load() -> dict:
    try:
        return json.loads(DOCS_JSON.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _c(s) -> str:
    return "" if s is None else str(s).replace("|", "\\|").replace("\n", " ")


def _n(x, d=0) -> str:
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return "—"
    return f"{x:.{d}f}" if d else f"{round(x)}"


def render(d: dict) -> str:
    total = sum(sum(r["spend"].values()) for r in d.get("spend_runs", []))
    L = ["# Проба S07 — паузы по потребности", "",
         "Код — `tools/probe/s07.py`, запуск — `python tools/probe/probe.py s07` (`--part durations|faster`).", "",
         f"Всего потрачено на пробу S07: ≈ ${total:.3f}.", ""]
    du = d.get("durations")
    if du:
        L += ["## Длительности групп", "",
              f"- ответов {du['replies']} (пробы S02c, до первого вопроса — D48), групп {du['groups_total']}, разных "
              f"{du['groups_unique']}, синтезировано {du['synthesized']}; пауз между группами {du['pauses']}",
              "- синтез голосом приложения (`verse`, 0,88), длительность — без тишины по краям (окна 10 мс, края тише самого "
              "громкого окна на 35 дБ)",
              f"- звук группы: медиана {_n(du['speech_ms']['median'])} мс, p10 {_n(du['speech_ms']['p10'])}, p90 "
              f"{_n(du['speech_ms']['p90'])}, максимум {_n(du['speech_ms']['max'])}",
              f"- тишина по краям в звуке синтеза: медиана {_n(du['edge_ms']['median'])} мс, p90 {_n(du['edge_ms']['p90'])}, "
              f"максимум {_n(du['edge_ms']['max'])}",
              f"- связь длительности со слогами r = {du['corr']['syllables']}, со словами r = {du['corr']['words']}; плотность "
              f"— медиана {_n(du['density']['median'], 2)} слога/с (p10 {_n(du['density']['p10'], 2)}, p90 "
              f"{_n(du['density']['p90'], 2)})", "",
              "### Доля длительности: что даёт", "",
              "`пауза = пауза_по_знаку + доля × звук группы` (множитель 1,0, предел 3000 мс). Прирост реплики — звук + паузы "
              "между группами, против нынешних пауз.", "",
              "| доля | группа 0,5–0,9 с: + мс | группа 2,2–2,8 с: + мс | реплика длиннее, медиана (p10 … p90) | "
              "предложение: медиана / p90 | `, : ;`: медиана / p90 | внутри: медиана / p90 | абзац: медиана / p90 | у предела |",
              "|---|---|---|---|---|---|---|---|---|"]
        for s in du["by_share"]:
            k = s["kinds"]

            def mp(name):
                v = k.get(name)
                return f"{_n(v['median'])} / {_n(v['p90'])}" if v else "—"
            L.append(f"| {s['share']} | {_n(s['add_short_median'])} ({s['n_short']}) | {_n(s['add_long_median'])} "
                     f"({s['n_long']}) | {_n(s['growth_median'], 1)} % ({_n(s['growth_p10'], 1)} … "
                     f"{_n(s['growth_p90'], 1)}) | {mp('предложение')} | {mp(', : ;')} | {mp('внутри')} | {mp('абзац')} | "
                     f"{s['capped']} |")
        pick = next((s for s in du["by_share"] if s["share"] == 0.25), None)
        if pick:
            L += ["", "### Выбор: доля 0,25", "",
                  "Ориентир брифа: группа ≈ 0,7 с — +0,1–0,2 с; ≈ 2,5 с — +0,6–0,8 с; реплика при множителе 1,0 длиннее на "
                  "15–30 %. При 0,25 — "
                  f"+{_n(pick['add_short_median'])} мс, +{_n(pick['add_long_median'])} мс, реплика длиннее на "
                  f"{_n(pick['growth_median'], 1)} % (p10 … p90 {_n(pick['growth_p10'], 1)} … {_n(pick['growth_p90'], 1)} %). "
                  "0,2 не дотягивает до 15 % и до +0,6 с у длинной группы; 0,3 даёт короткой группе больше +0,2 с. "
                  "Пауза ни разу не дошла до предела 3000 мс.",
                  "",
                  "Слоги и секунды почти одно и то же (r ≈ 0,87), а секунды уже есть в готовом звуке и точнее оценки "
                  "слогов. Плотность разная (p10 … p90 — 2,3 … 4,2 слога/с): плотная группа по секундам лишней паузы не "
                  "получает. Довода считать её пока нет — нужна оценка пользователя: если «не успеваю» окажется после "
                  "быстрых групп, добавить плотность. Остаёмся на секундах."]
        L += ["", "Число групп по типам знаков: " + ", ".join(
            f"{name} — {v['n']}" for name, v in du["by_share"][0]["kinds"].items()), ""]
        for s in du["by_share"]:
            if s["share"] in (0.2, 0.25, 0.3):
                L += [f"### Самые длинные паузы при доле {s['share']}", "", "| пауза | знак | звук группы | группа |",
                      "|---|---|---|---|"]
                L += [f"| {x['pause']} | {x['mark']} | {x['speech_ms']} | {_c(x['text'])} |" for x in s["longest"]]
                L.append("")
    fa = d.get("faster")
    if fa:
        L += ["## Команда «Faster»", "",
              f"- синтез двумя голосами ({', '.join(FASTER_VOICES)}), файл и поток с подсказкой команд приложения",
              f"- команды узнаны: **{fa['commands_ok']} из {fa['commands_total']}**",
              f"- ложных команд на похожих ответах: **{fa['false']} из {fa['answers_total']}**",
              f"- ответы и команды S05b заново по новому правилу ({fa['s05b_total']} строк): «faster» ложно — "
              f"{fa['s05b_false_faster']}, всего расхождений с ожиданием — {len(fa['s05b_wrong'])} (те же, что в S05b, если "
              "не 0 — см. `05b-probes.md`)", "",
              "| голос | путь | сказано | ожидание | распознано | команда |", "|---|---|---|---|---|---|"]
        for r in fa["rows"]:
            mark = "" if r["got"] == r["want"] else " ✗"
            L.append(f"| {r['voice']} | {r['path']} | {_c(r['said'])} | {r['want'] or '—'} | {_c(r['heard'])} | "
                     f"{r['got'] or '—'}{mark} |")
        L.append("")
    return "\n".join(L) + "\n"
