#!/usr/bin/env python3
"""
ЭТАП 5: Полный цикл (CLI приложение)
Интерактивное приложение для обучения listening comprehension
...
"""

import os
import sys
import logging
import re
import time
import tempfile
import subprocess
import threading
from collections import Counter
from pathlib import Path
from datetime import datetime, date, timedelta
import json
from typing import Any, Optional, Callable
from contextlib import contextmanager
from io import UnsupportedOperation
from dotenv import dotenv_values
import httpx
import speech_recognition as sr

from src.openai_compat import create_openai_client

# ===== ПОДАВЛЕНИЕ ALSA СООБЩЕНИЙ =====
# Отключить лишние сообщения об ошибках ALSA (от звукового драйвера)
os.environ['ALSA_CARD'] = 'default'
os.environ['ALSA_PCM_CARD'] = 'default'


PROJECT_ROOT = Path(__file__).parent
ENV_FILE = PROJECT_ROOT / ".env"


def get_required_env_value(config: dict[str, Optional[str]], name: str) -> str:
    """Получить обязательный параметр только из .env."""
    raw_value = config.get(name)
    if raw_value is None:
        raise SystemExit(f"В {ENV_FILE} отсутствует обязательный параметр {name}.")

    value = raw_value.strip()
    if not value:
        raise SystemExit(f"В {ENV_FILE} пустой обязательный параметр {name}.")

    return value


def get_required_float_env_value(config: dict[str, Optional[str]], name: str) -> float:
    """Прочитать числовой обязательный параметр только из .env."""
    value = get_required_env_value(config, name)
    try:
        return float(value)
    except ValueError as exc:
        raise SystemExit(f"Параметр {name} в {ENV_FILE} должен быть числом, сейчас: {value!r}.") from exc


def get_required_int_env_value(config: dict[str, Optional[str]], name: str) -> int:
    """Прочитать целочисленный обязательный параметр только из .env."""
    value = get_required_env_value(config, name)
    try:
        return int(value)
    except ValueError as exc:
        raise SystemExit(f"Параметр {name} в {ENV_FILE} должен быть целым числом, сейчас: {value!r}.") from exc


# ===== КОНФИГУРАЦИЯ =====
if not ENV_FILE.is_file():
    raise SystemExit(f"Не найден обязательный файл конфигурации {ENV_FILE}. Создайте его из .env.example.")

ENV_CONFIG = dotenv_values(ENV_FILE)

OPENAI_API_KEY = get_required_env_value(ENV_CONFIG, "OPENAI_API_KEY")
LANGUAGE_LEVEL = get_required_env_value(ENV_CONFIG, "LANGUAGE_LEVEL")
# Ключевые параметры темпа:
# - PAUSE_THRESHOLD управляет тем, сколько тишины считать концом реплики пользователя.
# - TTS_SPEED управляет скоростью произнесения слов.
# - TTS_MAX_CHUNK_WORDS управляет агрессивностью внутреннего разбиения на chunk-и.
# - Паузы ниже независимо управляют ритмом и "дыханием" речи учителя.
PAUSE_THRESHOLD = get_required_float_env_value(ENV_CONFIG, "PAUSE_THRESHOLD")  # Секунды тишины до автоостановки записи; увеличьте, если конец фразы обрезается.
PHRASE_TIME_LIMIT = get_required_float_env_value(ENV_CONFIG, "PHRASE_TIME_LIMIT")  # Жёсткий максимум длины одной реплики; <= 0 отключает hard cap.
SESSION_IDLE_TIMEOUT = get_required_float_env_value(ENV_CONFIG, "SESSION_IDLE_TIMEOUT")  # Сколько ждать начала новой реплики; если тишина длится дольше, сеанс завершается.
TTS_SPEED = get_required_float_env_value(ENV_CONFIG, "TTS_SPEED")  # Скорость произнесения слов TTS; меньше = медленнее/четче, больше = быстрее/естественнее.
TTS_VOLUME = get_required_float_env_value(ENV_CONFIG, "TTS_VOLUME")
TTS_MAX_CHUNK_WORDS = get_required_int_env_value(ENV_CONFIG, "TTS_MAX_CHUNK_WORDS")
SMALL_PAUSE_MS = get_required_int_env_value(ENV_CONFIG, "SMALL_PAUSE_MS")
CLAUSE_PAUSE_MS = get_required_int_env_value(ENV_CONFIG, "CLAUSE_PAUSE_MS")
SENTENCE_PAUSE_MS = get_required_int_env_value(ENV_CONFIG, "SENTENCE_PAUSE_MS")

if TTS_MAX_CHUNK_WORDS < 2:
    raise SystemExit(
        f"Параметр TTS_MAX_CHUNK_WORDS в {ENV_FILE} должен быть не меньше 2, "
        f"сейчас: {TTS_MAX_CHUNK_WORDS}."
    )

# Инициализация клиентов
client = create_openai_client(OPENAI_API_KEY)
recognizer = sr.Recognizer()

# Пути
SESSIONS_DIR = PROJECT_ROOT / "sessions"
LOGS_DIR = PROJECT_ROOT / "logs"
AUDIO_FILES_DIR = PROJECT_ROOT / "audio_files"

SESSIONS_DIR.mkdir(exist_ok=True)
LOGS_DIR.mkdir(exist_ok=True)
AUDIO_FILES_DIR.mkdir(exist_ok=True)

# ===== ЛОГИРОВАНИЕ =====
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)

log_file = LOGS_DIR / "main.log"
file_handler = logging.FileHandler(log_file, encoding='utf-8')
file_handler.setLevel(logging.DEBUG)

console_handler = logging.StreamHandler()
console_handler.setLevel(logging.WARNING)

formatter = logging.Formatter(
    '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
file_handler.setFormatter(formatter)
console_handler.setFormatter(logging.Formatter('%(message)s'))

logger.addHandler(file_handler)
logger.addHandler(console_handler)
logger.propagate = False

DIVIDER = "=" * 70
ROUND_DIVIDER = "─" * 70
CHAT_MODEL = "gpt-4o-mini"
TTS_MODEL = "gpt-4o-mini-tts"
DEFAULT_TTS_VOICE = "nova"
MIN_TTS_SPEED = 0.25
MAX_TTS_SPEED = 4.0
TEACHER_WAIT_SIGNAL_INTERVAL_SECONDS = 6.0
LISTENING_SIGNAL_INTERVAL_SECONDS = 1.8
TRANSCRIPTION_WAIT_SIGNAL_INTERVAL_SECONDS = 4.0
TTS_PREPARING_SIGNAL_INTERVAL_SECONDS = 4.0
ONLINE_SEARCH_TRIGGER_PHRASES = (
    "search the internet",
    "search internet",
    "search online",
    "search the web",
    "look up",
    "look it up",
    "find online",
    "find on the internet",
    "find in the internet",
    "check online",
    "on the internet",
    "in the internet",
    "from the internet",
)
REPEAT_SEARCH_PHRASES = (
    "try again",
    "search again",
    "look it up again",
    "check again",
    "find again",
)
WEATHER_KEYWORDS = {
    "weather", "forecast", "temperature", "temperatures", "rain", "snow",
    "wind", "winds", "sunny", "cloudy", "storm", "storms", "humidity",
}
LIVE_DATA_KEYWORDS = {
    "weather", "forecast", "news", "price", "prices", "cost", "rate", "rates",
    "schedule", "schedules", "hours", "opening hours", "open", "closed",
    "score", "scores", "result", "results", "traffic", "delay", "delays",
    "ceo", "president", "election",
}
TEMPORAL_HINT_WORDS = {
    "today", "tomorrow", "tonight", "morning", "afternoon", "evening",
    "night", "weekend", "this", "next", "current", "latest", "recent",
}
WEEKDAY_TO_INDEX = {
    "monday": 0,
    "tuesday": 1,
    "wednesday": 2,
    "thursday": 3,
    "friday": 4,
    "saturday": 5,
    "sunday": 6,
}
MONTH_NAME_TO_INDEX = {
    "january": 1,
    "february": 2,
    "march": 3,
    "april": 4,
    "may": 5,
    "june": 6,
    "july": 7,
    "august": 8,
    "september": 9,
    "october": 10,
    "november": 11,
    "december": 12,
}
TIME_OF_DAY_RANGES = {
    "morning": range(6, 12),
    "afternoon": range(12, 18),
    "evening": range(18, 22),
    "night": range(0, 6),
    "tonight": range(18, 24),
}
LOCATION_STOP_WORDS = (
    set(WEEKDAY_TO_INDEX)
    | set(MONTH_NAME_TO_INDEX)
    | WEATHER_KEYWORDS
    | {
        "hi", "hello", "hey", "today", "tomorrow", "this", "next", "morning", "afternoon", "evening", "night",
        "could", "would", "should", "can", "what", "when", "where", "who", "why", "how",
        "internet", "online", "web", "search", "find", "check",
    }
)
WEATHER_CODE_DESCRIPTIONS = {
    0: "clear",
    1: "mostly clear",
    2: "partly cloudy",
    3: "overcast",
    45: "foggy",
    48: "foggy",
    51: "light drizzle",
    53: "drizzle",
    55: "dense drizzle",
    61: "light rain",
    63: "rain",
    65: "heavy rain",
    66: "light freezing rain",
    67: "freezing rain",
    71: "light snow",
    73: "snow",
    75: "heavy snow",
    77: "snow grains",
    80: "rain showers",
    81: "rain showers",
    82: "heavy rain showers",
    85: "snow showers",
    86: "heavy snow showers",
    95: "a thunderstorm",
    96: "a thunderstorm",
    99: "a thunderstorm",
}
WORD_RE = re.compile(r"[A-Za-z0-9]+(?:'[A-Za-z]+)?")
WH_QUESTION_WORDS = {"what", "which", "who", "whom", "whose", "where", "when", "why", "how"}
AUXILIARY_BOUNDARY_WORDS = {
    "am", "is", "are", "was", "were",
    "do", "does", "did",
    "have", "has", "had",
    "can", "could", "will", "would", "shall", "should", "may", "might", "must",
}
CLAUSE_BOUNDARY_WORDS = {
    "because", "although", "though", "if", "when", "while", "that",
    "which", "who", "whom", "whose", "where", "since", "unless", "until",
    "after", "before", "whether",
}
COORDINATING_BOUNDARY_WORDS = {"and", "but", "or", "so", "yet", "nor"}
PREPOSITION_BOUNDARY_WORDS = {
    "about", "for", "with", "without", "on", "in", "at", "from", "into",
    "over", "under", "after", "before", "during", "through", "between",
    "among", "around", "against", "toward", "towards",
}
COPULAR_BOUNDARY_WORDS = {
    "am", "is", "are", "was", "were", "be", "been", "being",
    "feel", "feels", "felt",
    "seem", "seems", "seemed",
    "sound", "sounds", "sounded",
    "look", "looks", "looked",
}

# ===== СИСТЕМНЫЕ ПРОМПТЫ =====
SYSTEM_PROMPTS = {
    "A2": """You are a patient and encouraging English teacher for beginners (A2 level).
Your role is to:
1. Respond to the student's input as a teacher would
2. Use ONLY simple vocabulary and short sentences (maximum 3 sentences)
3. Be encouraging and positive
4. If the student makes a mistake, gently correct them with a simple explanation
5. Ask simple follow-up questions to keep the conversation going
6. Never use idioms, phrasal verbs, or complex grammar
7. Use present simple and present continuous mostly
8. Keep responses under 50 words

Remember: Be warm, patient, and supportive. The student is learning.""",

    "B1": """You are a patient and encouraging English teacher for intermediate students (B1 level).
Your role is to:
1. Respond to the student's input as a teacher would
2. Use intermediate vocabulary and clear sentence structures (maximum 4 sentences)
3. Be encouraging and provide constructive feedback
4. If the student makes a mistake, politely correct them with a brief explanation
5. Ask follow-up questions to encourage deeper thinking
6. Avoid idioms and complex phrasal verbs (unless explaining them)
7. Can use past and future tenses alongside present
8. Keep responses under 80 words

Remember: Balance challenge with encouragement. Help them grow!""",

    "B2": """You are a knowledgeable and supportive English teacher for upper-intermediate students (B2 level).
Your role is to:
1. Respond thoughtfully to the student's input
2. Use sophisticated vocabulary and varied sentence structures (maximum 5 sentences)
3. Provide meaningful feedback and corrections
4. If the student makes a mistake, explain it clearly with examples
5. Ask thoughtful follow-up questions to promote critical thinking
6. Can use advanced grammar and some idiomatic expressions
7. Encourage them to explore nuances of the language
8. Keep responses under 120 words

Remember: Challenge them appropriately while maintaining support.""",
}

# ===== ФУНКЦИИ =====

def ui_print(message: str = ""):
    """Печатать только понятные пользователю сообщения."""
    print(message, flush=True)


def ui_header(title: str):
    """Заголовок пользовательского раздела."""
    ui_print("\n" + DIVIDER)
    ui_print(title)
    ui_print(DIVIDER)


@contextmanager
def suppress_stderr():
    """Временно скрыть нативный шум из stderr, например ALSA/JACK."""
    try:
        stderr_fd = sys.stderr.fileno()
    except (AttributeError, UnsupportedOperation, OSError):
        yield
        return

    saved_stderr_fd = os.dup(stderr_fd)

    try:
        with open(os.devnull, "w", encoding="utf-8") as devnull:
            os.dup2(devnull.fileno(), stderr_fd)
            yield
    finally:
        os.dup2(saved_stderr_fd, stderr_fd)
        os.close(saved_stderr_fd)


def show_startup_hint(current_level: str):
    """Коротко объяснить сценарий работы программы."""
    effective_phrase_time_limit = get_effective_phrase_time_limit(PHRASE_TIME_LIMIT)
    ui_header("🎓 LISTENING COMPREHENSION TRAINER")
    ui_print(f"Текущий уровень: {current_level}")
    ui_print("\nЧто программа ждёт от вас:")
    ui_print("  • В меню введите цифру 1-5 и нажмите Enter.")
    ui_print("  • Для тренировки выберите пункт 1.")
    ui_print("  • После начала сеанса говорите в микрофон по-английски.")
    ui_print(f"  • Когда закончите, просто помолчите {PAUSE_THRESHOLD} сек.")
    if effective_phrase_time_limit is None:
        ui_print("  • Жёсткий лимит длины одной реплики отключён.")
    else:
        ui_print(f"  • Одна реплика может длиться максимум {effective_phrase_time_limit:g} сек., даже без паузы.")
    if SESSION_IDLE_TIMEOUT > 0:
        ui_print(f"  • Если слишком долго молчать перед новой репликой, сеанс завершится через {SESSION_IDLE_TIMEOUT:.0f} сек.")
    ui_print("  • Для принудительного выхода из сеанса используйте Ctrl+C.")
    ui_print("\nПодробные технические логи пишутся в logs/main.log.")


def clamp_tts_speed(speed: float) -> float:
    """Ограничить скорость TTS безопасным диапазоном API/движка."""
    return max(MIN_TTS_SPEED, min(MAX_TTS_SPEED, speed))


def get_effective_phrase_time_limit(seconds: float | None) -> float | None:
    """Подготовить phrase_time_limit для recognizer.listen(); <= 0 отключает hard cap."""
    try:
        if seconds is None:
            return None
        value = float(seconds)
    except (TypeError, ValueError):
        value = PHRASE_TIME_LIMIT

    return value if value > 0 else None


def get_effective_pause_ms(pause_ms: Any) -> int:
    """Вернуть паузу в мс как есть, не меньше нуля."""
    try:
        return max(0, int(pause_ms))
    except (TypeError, ValueError):
        return CLAUSE_PAUSE_MS


def get_effective_playback_pauses_ms(tts_chunks: list[dict[str, Any]]) -> list[int]:
    """Собрать реальные межchunk-паузы, которые будут использованы при воспроизведении."""
    if len(tts_chunks) <= 1:
        return []

    return [
        get_effective_pause_ms(chunk.get("pause_ms", CLAUSE_PAUSE_MS))
        for chunk in tts_chunks[:-1]
    ]


def format_pause_values(pause_values: list[int]) -> str:
    """Подготовить список пауз для UI и логов."""
    if not pause_values:
        return "нет дополнительных межchunk-пауз"

    return ", ".join(str(value) for value in pause_values)


def build_tts_chunk_instructions(speed: float) -> str:
    """Сформировать инструкцию для OpenAI TTS без жёсткого навязывания медленного темпа."""
    safe_speed = clamp_tts_speed(speed)

    if safe_speed <= 0.9:
        pace_instruction = "Speak slowly and clearly for an English learner."
    elif safe_speed <= 1.15:
        pace_instruction = "Speak clearly for an English learner with a calm, natural pace."
    elif safe_speed <= 1.45:
        pace_instruction = "Speak clearly with a natural, moderately brisk pace for an English learner."
    else:
        pace_instruction = "Speak clearly with a brisk but intelligible pace for an English learner."

    return (
        f"{pace_instruction} Separate words distinctly and keep a natural rhythm. "
        "Do not add extra words or sounds."
    )


def show_tts_playback_settings(service_label: str, tts_chunks: list[dict[str, Any]]) -> None:
    """Показать фактические параметры, которые будут использованы для озвучивания."""
    effective_speed = clamp_tts_speed(TTS_SPEED)
    pause_values = get_effective_playback_pauses_ms(tts_chunks)
    speed_details = f"{effective_speed:.2f}"

    if abs(effective_speed - TTS_SPEED) > 1e-9:
        speed_details += f" (из TTS_SPEED={TTS_SPEED:.2f}, ограничено допустимым диапазоном)"
    else:
        speed_details += f" (TTS_SPEED={TTS_SPEED:.2f})"

    logger.info(
        "🔧 Фактические параметры озвучивания: service=%s, speech_speed=%.2f, chunks=%d, pauses_ms=[%s]",
        service_label,
        effective_speed,
        len(tts_chunks),
        format_pause_values(pause_values),
    )

    logger.info(
        "🔧 OpenAI TTS: model=%s, voice=%s",
        TTS_MODEL,
        DEFAULT_TTS_VOICE,
    )

    ui_print("🔧 Фактические параметры озвучивания:")
    ui_print(f"   Сервис: {service_label}")
    ui_print(f"   Скорость слов: {speed_details}")
    ui_print(f"   OpenAI модель/голос: {TTS_MODEL} / {DEFAULT_TTS_VOICE}")
    ui_print(f"   Chunk-ов в ответе: {len(tts_chunks)}")
    ui_print(f"   Целевой максимум слов в chunk-е: {TTS_MAX_CHUNK_WORDS}")
    ui_print(f"   Паузы между chunk-ами, мс: {format_pause_values(pause_values)}")
    ui_print(f"   Суммарная межchunk-пауза: {sum(pause_values)} мс")
    ui_print(
        "   Базовые паузы, мс: "
        f"short={SMALL_PAUSE_MS}, clause={CLAUSE_PAUSE_MS}, sentence={SENTENCE_PAUSE_MS}"
    )


def show_teacher_chunk_sequence(tts_chunks: list[dict[str, Any]]) -> None:
    """Показать на экране последовательность chunk-ов и пауз между ними."""
    if not tts_chunks:
        ui_print("👨‍🏫 Ответ учителя пуст.")
        return

    ui_print("👨‍🏫 Ответ учителя по chunk-ам:")
    for index, chunk in enumerate(tts_chunks):
        ui_print(f"   {index + 1}. {chunk['text']}")
        if index < len(tts_chunks) - 1:
            pause_ms = get_effective_pause_ms(chunk.get("pause_ms", CLAUSE_PAUSE_MS))
            ui_print(f"   Пауза: {pause_ms} мс")


def normalize_text_spacing(text: str) -> str:
    """Нормализовать пробелы для текста и chunk-ов."""
    compact = " ".join(text.split())
    return re.sub(r"\s+([,.;:!?])", r"\1", compact).strip()


def get_max_chunk_words(_level: str) -> int:
    """Вернуть максимально допустимый размер грамматического chunk-а."""
    return TTS_MAX_CHUNK_WORDS


def get_chunk_terminal_pause_ms(text: str) -> int:
    """Определить тип паузы по знаку препинания в конце chunk-а."""
    if text.endswith((".", "!", "?")):
        return SENTENCE_PAUSE_MS
    if text.endswith((",", ";", ":")):
        return CLAUSE_PAUSE_MS
    return SMALL_PAUSE_MS


def get_chunk_pause_ms(text: str, requested_pause_ms: Any = None) -> int:
    """Вернуть финальную паузу chunk-а только из текущих настроек .env."""
    punctuation_pause_ms = get_chunk_terminal_pause_ms(text)
    if text.endswith((".", "!", "?", ",", ";", ":")):
        return punctuation_pause_ms
    if requested_pause_ms is None:
        return SMALL_PAUSE_MS
    return get_effective_pause_ms(requested_pause_ms)


def split_text_at_char_index(text: str, boundary_index: int) -> tuple[str, str] | None:
    """Разделить строку по позиции начала правой части."""
    left = text[:boundary_index].strip()
    right = text[boundary_index:].strip()
    if not left or not right:
        return None
    return left, right


def split_on_explicit_punctuation(text: str) -> tuple[str, str, int] | None:
    """Сначала резать по явной пунктуации: каждый chunk должен оканчиваться знаком препинания."""
    match = re.search(r"[,;:.!?]+(?=\s+\S)", text)
    if not match:
        return None

    split_result = split_text_at_char_index(text, match.end())
    if not split_result:
        return None

    left, right = split_result
    return left, right, get_chunk_terminal_pause_ms(left)


def should_split_before_infinitive(words: list[str], index: int) -> bool:
    """Разрывать перед to после краткой связки/оценки: I am happy | to help."""
    if words[index] != "to" or index < 2:
        return False

    for probe_index in range(max(1, index - 3), index):
        if words[probe_index] in COPULAR_BOUNDARY_WORDS:
            return True
    return False


def choose_grammar_split(text: str, level: str) -> tuple[str, str, int] | None:
    """Подобрать внутреннюю грамматическую границу, если пунктуации недостаточно."""
    word_matches = list(WORD_RE.finditer(text))
    if len(word_matches) <= 1:
        return None

    lower_words = [match.group(0).lower() for match in word_matches]
    max_words = get_max_chunk_words(level)
    candidates: list[tuple[int, int, int, int]] = []

    if text.rstrip().endswith("?") and lower_words[0] in WH_QUESTION_WORDS:
        for split_index, word in enumerate(lower_words[1:-1], start=1):
            if word in AUXILIARY_BOUNDARY_WORDS and split_index >= 2:
                candidates.append((split_index, SMALL_PAUSE_MS, 100, split_index))
                break

    for split_index, word in enumerate(lower_words[1:-1], start=1):
        if word == "to" and should_split_before_infinitive(lower_words, split_index):
            candidates.append((split_index, SMALL_PAUSE_MS, 95, split_index))
            continue

        if word in CLAUSE_BOUNDARY_WORDS:
            candidates.append((split_index, CLAUSE_PAUSE_MS, 90, split_index))
            continue

        if word in COORDINATING_BOUNDARY_WORDS:
            candidates.append((split_index, CLAUSE_PAUSE_MS, 80, split_index))
            continue

        if word in PREPOSITION_BOUNDARY_WORDS:
            candidates.append((split_index, SMALL_PAUSE_MS, 65, split_index))

    if candidates:
        ranked_candidates: list[tuple[int, int, int, int, int]] = []
        total_words = len(word_matches)

        for split_index, pause_ms, priority, order_index in candidates:
            left_words = split_index
            right_words = total_words - split_index
            if left_words < 2 or right_words < 2:
                continue

            overflow = max(0, left_words - max_words) + max(0, right_words - max_words)
            distance_from_limit = abs(max_words - left_words)
            ranked_candidates.append((overflow, -priority, distance_from_limit, order_index, pause_ms))

        if ranked_candidates:
            best_overflow, best_priority, _, best_index, best_pause = min(ranked_candidates)
            split_result = split_text_at_char_index(text, word_matches[best_index].start())
            if split_result:
                left, right = split_result
                if best_overflow == 0 or len(word_matches) > max_words:
                    return left, right, best_pause

    if len(word_matches) <= max_words:
        return None

    fallback_index = min(max_words, len(word_matches) - 2)
    split_result = split_text_at_char_index(text, word_matches[fallback_index].start())
    if not split_result:
        return None

    left, right = split_result
    return left, right, SMALL_PAUSE_MS


def split_text_recursively_for_tts(text: str, level: str) -> list[tuple[str, int]]:
    """Рекурсивно делить текст на короткие грамматические куски с паузами."""
    normalized_text = normalize_text_spacing(text)
    if not normalized_text:
        return []

    split_result = split_on_explicit_punctuation(normalized_text) or choose_grammar_split(normalized_text, level)
    if not split_result:
        return [(normalized_text, get_chunk_terminal_pause_ms(normalized_text))]

    left, right, boundary_pause_ms = split_result
    left_chunks = split_text_recursively_for_tts(left, level)
    right_chunks = split_text_recursively_for_tts(right, level)
    last_left_text, last_left_pause_ms = left_chunks[-1]
    left_chunks[-1] = (last_left_text, max(last_left_pause_ms, boundary_pause_ms))
    return left_chunks + right_chunks


def split_text_for_tts_fallback(text: str, level: str = LANGUAGE_LEVEL) -> list[dict[str, Any]]:
    """Запасная эвристика: короткие chunk-и по пунктуации и грамматическим границам."""
    normalized_text = normalize_text_spacing(text)
    if not normalized_text:
        return []

    return [
        {"text": chunk_text, "pause_ms": pause_ms}
        for chunk_text, pause_ms in split_text_recursively_for_tts(normalized_text, level)
    ]


def refine_tts_chunks(tts_chunks: list[dict[str, Any]], level: str = LANGUAGE_LEVEL) -> list[dict[str, Any]]:
    """Доразбить модельные chunk-и локальными правилами, если они получились слишком крупными."""
    refined_chunks: list[dict[str, Any]] = []

    for chunk in tts_chunks:
        chunk_text = normalize_text_spacing(str(chunk.get("text", "")).strip())
        if not chunk_text:
            continue

        subchunks = split_text_for_tts_fallback(chunk_text, level)
        if not subchunks:
            continue

        original_pause_ms = get_chunk_pause_ms(chunk_text, chunk.get("pause_ms", CLAUSE_PAUSE_MS))
        subchunks[-1]["pause_ms"] = original_pause_ms
        refined_chunks.extend(subchunks)

    return refined_chunks


def parse_json_object(raw_text: str) -> dict[str, Any] | None:
    """Извлечь JSON-объект из текста модели."""
    try:
        return json.loads(raw_text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", raw_text, flags=re.DOTALL)
        if not match:
            return None

        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            return None


def normalize_teacher_response_payload(payload: Any, level: str = LANGUAGE_LEVEL) -> dict[str, Any]:
    """Привести ответ учителя к стабильной структуре."""
    if not isinstance(payload, dict):
        display_text = normalize_text_spacing(str(payload or ""))
        return {
            "display_text": display_text,
            "tts_chunks": split_text_for_tts_fallback(display_text, level),
        }

    display_text = normalize_text_spacing(str(payload.get("display_text", "")).strip())
    raw_chunks = payload.get("tts_chunks", [])
    normalized_chunks: list[dict[str, Any]] = []

    if isinstance(raw_chunks, list):
        for item in raw_chunks:
            if not isinstance(item, dict):
                continue

            chunk_text = normalize_text_spacing(str(item.get("text", "")).strip())
            if not chunk_text:
                continue

            pause_ms_raw = item.get("pause_ms", CLAUSE_PAUSE_MS)
            normalized_chunks.append({
                "text": chunk_text,
                "pause_ms": get_chunk_pause_ms(chunk_text, pause_ms_raw),
            })

    if not display_text and normalized_chunks:
        display_text = normalize_text_spacing(" ".join(chunk["text"] for chunk in normalized_chunks))

    if not display_text:
        return {
            "display_text": "",
            "tts_chunks": [],
        }

    joined_chunks = normalize_text_spacing(" ".join(chunk["text"] for chunk in normalized_chunks))
    if not normalized_chunks or joined_chunks != display_text:
        normalized_chunks = [{"text": display_text, "pause_ms": SENTENCE_PAUSE_MS}]

    normalized_chunks = refine_tts_chunks(normalized_chunks, level)
    if normalize_text_spacing(" ".join(chunk["text"] for chunk in normalized_chunks)) != display_text:
        normalized_chunks = split_text_for_tts_fallback(display_text, level)

    return {
        "display_text": display_text,
        "tts_chunks": normalized_chunks,
    }


def build_structured_teacher_prompt(level: str) -> str:
    """Системный промпт для ответа учителя с разметкой пауз."""
    max_chunk_words = get_max_chunk_words(level)
    example_pause_ms = SMALL_PAUSE_MS
    return (
        SYSTEM_PROMPTS.get(level, SYSTEM_PROMPTS["B1"])
        + "\n\n"
        + """Return valid JSON only.
Use exactly this schema:
{
  "display_text": "Natural teacher reply for the student",
  "tts_chunks": [
    {"text": "First grammatical chunk", "pause_ms": """
        + str(example_pause_ms)
        + """}
  ]
}

Rules:
- display_text must be natural English for the student's level.
- tts_chunks must reconstruct display_text exactly when joined with spaces.
- Split by listening-friendly grammatical units: introductory phrases, main clauses,
  subordinate clauses, relative clauses, participial phrases, parenthetical insertions,
  coordinated clauses, and direct address.
- End a chunk at every comma, semicolon, colon, period, question mark, and exclamation mark.
- If one punctuation-based chunk is still long, split it further at natural intonation boundaries.
- Prefer very small chunks for listening practice: usually 2 to """
        + str(max_chunk_words)
        + """ words per chunk for this configuration.
- Keep punctuation inside chunk text.
- pause_ms:
  """
        + str(SMALL_PAUSE_MS)
        + """ = short phrase boundary
  """
        + str(CLAUSE_PAUSE_MS)
        + """ = clause boundary
  """
        + str(SENTENCE_PAUSE_MS)
        + """ = end of sentence or long grammatical boundary
- Prefer shorter, clearer sentences when possible.
- Comprehension check: if your previous turn ended with a question and the student's reply
  is vague, off-topic, or clearly does not address that question, do NOT move on.
  Instead, gently rephrase the same question using simpler words and a shorter sentence.
  You may start with "I meant..." or "Let me ask again:".
  Only after the student gives a relevant answer, continue normally.
"""
    )


def get_display_text_and_chunks(teacher_response: Any, level: str = LANGUAGE_LEVEL) -> tuple[str, list[dict[str, Any]]]:
    """Получить текст для экрана и chunks для TTS."""
    normalized = normalize_teacher_response_payload(teacher_response, level)
    return normalized["display_text"], normalized["tts_chunks"]




def ensure_openai_api_key() -> bool:
    """Проверить, что для практики доступен OpenAI API ключ."""
    if OPENAI_API_KEY and client is not None:
        return True

    logger.error("❌ ОШИБКА: OPENAI_API_KEY не найден в .env файле")
    ui_print("\n❌ Не найден OPENAI_API_KEY в файле .env.")
    ui_print("Добавьте ключ и повторите запуск практики.")
    return False


def ensure_microphone_available() -> bool:
    """Проверить микрофон только перед голосовой практикой."""
    try:
        with suppress_stderr():
            with sr.Microphone():
                pass
            logger.info("✓ Микрофон найден и работает")
        return True
    except Exception as e:
        logger.error(f"❌ Микрофон не найден: {e}")
        ui_print("\n❌ Микрофон не найден или недоступен.")
        ui_print("Проверьте подключение и системные разрешения, затем попробуйте снова.")
        return False


class SessionIdleTimeout(Exception):
    """Пользователь слишком долго молчал перед началом новой реплики."""

class ConversationSession:
    """Класс для управления сеансом беседы"""

    def __init__(self, level: str = "B1"):
        self.session_id = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.level = level
        self.conversation = []
        self.start_time = datetime.now()
        self.round_count = 0

        logger.info(f"📋 Создана новая сессия: {self.session_id} (уровень: {level})")

    def add_exchange(self, student_input: str, teacher_response: Any):
        """Добавить обмен в историю"""
        display_text, tts_chunks = get_display_text_and_chunks(teacher_response, self.level)
        self.conversation.append({
            "round": self.round_count,
            "student": student_input,
            "teacher": display_text,
            "teacher_tts_chunks": tts_chunks,
            "timestamp": datetime.now().isoformat()
        })
        self.round_count += 1

    def save(self):
        """Сохранить сеанс в JSON файл"""
        session_data = {
            "session_id": self.session_id,
            "level": self.level,
            "start_time": self.start_time.isoformat(),
            "duration_seconds": (datetime.now() - self.start_time).total_seconds(),
            "total_rounds": self.round_count,
            "conversation": self.conversation
        }

        output_file = SESSIONS_DIR / f"session_{self.session_id}.json"
        with open(output_file, "w", encoding="utf-8") as f:
            json.dump(session_data, f, ensure_ascii=False, indent=2)

        logger.info(f"💾 Сеанс сохранён: {output_file}")
        return str(output_file)

    def print_summary(self):
        """Вывести итоги сеанса"""
        duration = datetime.now() - self.start_time
        ui_header("📊 ИТОГИ СЕАНСА")
        ui_print(f"ID сеанса:        {self.session_id}")
        ui_print(f"Уровень:          {self.level}")
        ui_print(f"Всего раундов:    {self.round_count}")
        ui_print(f"Длительность:     {duration.total_seconds():.0f} сек ({duration.total_seconds()/60:.1f} мин)")
        ui_print(DIVIDER + "\n")
        logger.info("\n" + "=" * 70)
        logger.info("📊 ИТОГИ СЕАНСА")
        logger.info("=" * 70)
        logger.info(f"ID сеанса:        {self.session_id}")
        logger.info(f"Уровень:          {self.level}")
        logger.info(f"Всего раундов:    {self.round_count}")
        logger.info(f"Длительность:     {duration.total_seconds():.0f} сек ({duration.total_seconds()/60:.1f} мин)")
        logger.info("=" * 70 + "\n")

def record_audio(
    pause_threshold: float = 2.5,
    phrase_time_limit: float = 30,
    session_idle_timeout: float | None = SESSION_IDLE_TIMEOUT,
) -> str | None:
    """
    Записать аудио через микрофон

    Args:
        pause_threshold: Пауза для завершения записи (сек)
        phrase_time_limit: Максимум времени записи (сек); <= 0 отключает hard cap
        session_idle_timeout: Максимум ожидания начала речи (сек)

    Returns:
        Путь к сохранённому WAV файлу
    """
    effective_phrase_time_limit = get_effective_phrase_time_limit(phrase_time_limit)

    logger.info("🎙️  Слушаю микрофон...")
    logger.info(f"   Пауза обнаружения: {pause_threshold}s")
    if effective_phrase_time_limit is None:
        logger.info("   Максимум времени: без жёсткого лимита")
    else:
        logger.info(f"   Максимум времени: {effective_phrase_time_limit:g}s")
    if session_idle_timeout and session_idle_timeout > 0:
        logger.info(f"   Автовыход при молчании: {session_idle_timeout}s")
    logger.info("Начинайте говорить! (программа завершит запись после паузы)\n")
    ui_print("🎙️ Сейчас программа ждёт ваш голос в микрофон.")
    ui_print(f"   Скажите фразу на английском и затем помолчите {pause_threshold} сек.")
    if effective_phrase_time_limit is None:
        ui_print("   Жёсткий лимит длины реплики отключён.")
    else:
        ui_print(f"   Даже без паузы запись остановится через {effective_phrase_time_limit:g} сек.")
    if session_idle_timeout and session_idle_timeout > 0:
        ui_print(f"   Если не начать говорить в течение {session_idle_timeout:.0f} сек., сеанс завершится автоматически.")
    ui_print("   Ничего печатать в консоль сейчас не нужно.\n")

    try:
        with suppress_stderr():
            with sr.Microphone() as source:
            # Адаптация к шуму
                logger.info("⏳ Слушаю шум окружения в течение 1 второй...")
                ui_print("⏳ Настраиваю микрофон под шум окружения...")
                recognizer.adjust_for_ambient_noise(source, duration=1)
                logger.info("✓ Адаптирована к шуму окружения")
                ui_print("✅ Готово. Говорите...")

                # Параметры распознавания
                recognizer.pause_threshold = pause_threshold
                recognizer.non_speaking_duration = 0.3

                # Запись
                logger.info("⏺️  Начало записи...")
                with listening_wait_feedback():
                    audio = recognizer.listen(
                        source,
                        timeout=session_idle_timeout if session_idle_timeout and session_idle_timeout > 0 else None,
                        phrase_time_limit=effective_phrase_time_limit
                    )
                logger.info("✓ Запись завершена!")
                ui_print("✅ Запись завершена.")

                # Сохранить в файл
                timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
                output_file = AUDIO_FILES_DIR / f"recording_{timestamp}.wav"

                with open(output_file, "wb") as f:
                    f.write(audio.get_wav_data())

                logger.info(f"💾 Файл сохранён: {output_file}")
                return str(output_file)

    except sr.WaitTimeoutError:
        logger.info("⏹️  Сеанс завершён: пользователь слишком долго молчал перед новой репликой")
        raise SessionIdleTimeout
    except sr.RequestError as e:
        logger.error(f"❌ Ошибка микрофона: {e}")
        return None
    except Exception as e:
        logger.error(f"❌ Ошибка записи: {e}")
        return None


def transcribe_audio(audio_file: str) -> str:
    """Преобразовать аудио в текст (Whisper API)"""
    try:
        logger.info("🎙️  Отправляю на Whisper...")
        ui_print("⏳ Распознаю вашу речь...")

        with open(audio_file, "rb") as audio:
            with transcription_wait_feedback():
                transcript = client.audio.transcriptions.create(
                    model="whisper-1",
                    file=audio,
                    language="en"
                )

        text = transcript.text
        logger.info(f"✅ Распознано: {text}")
        return text

    except Exception as e:
        logger.error(f"❌ Ошибка распознавания: {e}")
        return None


def build_history_messages(conversation: list[dict[str, Any]]) -> list[dict[str, str]]:
    """Собрать историю диалога в формат messages для API."""
    messages = []
    for exchange in conversation:
        messages.append({"role": "user", "content": exchange["student"]})
        messages.append({"role": "assistant", "content": exchange["teacher"]})
    return messages


def normalize_search_router_text(text: str) -> str:
    """Нормализовать текст для простого роутинга интернет-запросов."""
    return re.sub(r"\s+", " ", str(text or "").casefold()).strip()


def contains_any_phrase(normalized_text: str, phrases: set[str] | tuple[str, ...]) -> bool:
    """Проверить, содержит ли нормализованный текст одну из фраз."""
    return any(phrase in normalized_text for phrase in phrases)


def is_repeat_search_request(text: str) -> bool:
    """Понять, просит ли пользователь повторить предыдущий интернет-поиск."""
    normalized_text = normalize_search_router_text(text)
    if normalized_text in REPEAT_SEARCH_PHRASES:
        return True
    return any(normalized_text.startswith(phrase) for phrase in REPEAT_SEARCH_PHRASES)


def should_search_online_text(text: str) -> bool:
    """Решить по самому тексту, нужен ли обязательный интернет-поиск."""
    normalized_text = normalize_search_router_text(text)
    if not normalized_text:
        return False

    if contains_any_phrase(normalized_text, ONLINE_SEARCH_TRIGGER_PHRASES):
        return True
    if any(keyword in normalized_text for keyword in WEATHER_KEYWORDS):
        return True
    if any(keyword in normalized_text for keyword in LIVE_DATA_KEYWORDS):
        temporal_hints = TEMPORAL_HINT_WORDS | set(WEEKDAY_TO_INDEX)
        if any(hint in normalized_text for hint in temporal_hints):
            return True

    return False


def find_latest_searchable_request(conversation: list[dict[str, Any]] | None) -> str | None:
    """Найти последнюю реплику пользователя, для которой уже имело смысл искать онлайн."""
    for exchange in reversed(conversation or []):
        student_text = str(exchange.get("student", "")).strip()
        if should_search_online_text(student_text):
            return student_text
    return None


def resolve_online_request_text(student_text: str, conversation: list[dict[str, Any]] | None = None) -> str:
    """Определить, какой именно запрос нужно искать онлайн прямо сейчас."""
    if is_repeat_search_request(student_text):
        previous_request = find_latest_searchable_request(conversation)
        if previous_request:
            return previous_request
    return student_text


def should_search_online(student_text: str, conversation: list[dict[str, Any]] | None = None) -> bool:
    """Решить, нужен ли интернет-поиск с учётом возможного 'try again'."""
    return should_search_online_text(resolve_online_request_text(student_text, conversation))


def is_weather_request(text: str) -> bool:
    """Понять, относится ли запрос к погоде."""
    normalized_text = normalize_search_router_text(text)
    return any(keyword in normalized_text for keyword in WEATHER_KEYWORDS)


def format_absolute_date(target_date: date) -> str:
    """Подготовить дату в понятном английском формате без ведущего нуля."""
    return target_date.strftime("%A, %B %d, %Y").replace(" 0", " ")


def extract_requested_date(request_text: str, reference_date: date) -> date | None:
    """Вытащить дату из пользовательского запроса простыми правилами."""
    normalized_text = normalize_search_router_text(request_text)

    if "day after tomorrow" in normalized_text:
        return reference_date + timedelta(days=2)
    if "tomorrow" in normalized_text:
        return reference_date + timedelta(days=1)
    if "today" in normalized_text or "tonight" in normalized_text:
        return reference_date

    month_pattern = (
        r"\b("
        + "|".join(MONTH_NAME_TO_INDEX)
        + r")\s+(\d{1,2})(?:st|nd|rd|th)?(?:,\s*(\d{4}))?\b"
    )
    month_match = re.search(month_pattern, normalized_text)
    if month_match:
        month_index = MONTH_NAME_TO_INDEX[month_match.group(1)]
        day_of_month = int(month_match.group(2))
        year = int(month_match.group(3)) if month_match.group(3) else reference_date.year
        try:
            return date(year, month_index, day_of_month)
        except ValueError:
            return None

    for weekday_name, weekday_index in WEEKDAY_TO_INDEX.items():
        if re.search(rf"\b(?:this|next)?\s*{weekday_name}\b", normalized_text):
            delta_days = (weekday_index - reference_date.weekday()) % 7
            if f"next {weekday_name}" in normalized_text and delta_days == 0:
                delta_days = 7
            if weekday_name in normalized_text and f"this {weekday_name}" not in normalized_text and delta_days == 0:
                delta_days = 7
            return reference_date + timedelta(days=delta_days)

    return None


def extract_part_of_day(request_text: str) -> str | None:
    """Определить, просит ли пользователь погоду на часть дня."""
    normalized_text = normalize_search_router_text(request_text)
    for part_of_day in ("morning", "afternoon", "evening", "tonight", "night"):
        if re.search(rf"\b{part_of_day}\b", normalized_text):
            return part_of_day
    return None


def clean_location_candidate(raw_candidate: str) -> str:
    """Очистить кандидат на название места от хвостов вроде 'this Saturday morning'."""
    candidate = normalize_text_spacing(str(raw_candidate or "")).strip(" ,.!?;:")
    if not candidate:
        return ""

    cleaned_tokens: list[str] = []
    for token in candidate.split():
        bare_token = token.strip(" ,.!?;:")
        if not bare_token:
            continue

        normalized_token = bare_token.casefold()
        if normalized_token in LOCATION_STOP_WORDS or normalized_token in TIME_OF_DAY_RANGES:
            break
        cleaned_tokens.append(bare_token)

    cleaned_candidate = " ".join(cleaned_tokens[:4]).strip()
    if len(cleaned_candidate) < 2:
        return ""

    return cleaned_candidate.title()


def extract_location_candidates(request_text: str) -> list[str]:
    """Собрать вероятные названия мест из вопроса пользователя."""
    candidates: list[str] = []
    seen: set[str] = set()

    def add_candidate(raw_candidate: str) -> None:
        candidate = clean_location_candidate(raw_candidate)
        if not candidate:
            return
        normalized_candidate = candidate.casefold()
        if normalized_candidate in seen:
            return
        seen.add(normalized_candidate)
        candidates.append(candidate)

    for match in re.finditer(
        r"\b(?:in|for|near|around|at)\s+([A-Za-z][A-Za-z.'-]*(?:\s+[A-Za-z][A-Za-z.'-]*){0,3})",
        request_text,
        flags=re.IGNORECASE,
    ):
        add_candidate(match.group(1))

    for match in re.finditer(r"\b[A-Z][A-Za-z.'-]*(?:\s+[A-Z][A-Za-z.'-]*){0,3}\b", request_text):
        add_candidate(match.group(0))

    return candidates


def build_web_search_query(request_text: str, reference_date: date) -> str:
    """Собрать понятный поисковый запрос для общего веб-поиска."""
    query = normalize_text_spacing(request_text)
    query = re.sub(r"\b(?:please|can you|could you|would you|do you know|tell me)\b", "", query, flags=re.IGNORECASE)
    query = re.sub(r"\b(?:find|search|look up|look it up|check)\b", "", query, flags=re.IGNORECASE)
    query = re.sub(r"\b(?:on|in|from)\s+the\s+internet\b", "", query, flags=re.IGNORECASE)
    query = re.sub(r"\bonline\b", "", query, flags=re.IGNORECASE)
    query = normalize_text_spacing(query).strip(" ?.")

    target_date = extract_requested_date(request_text, reference_date)
    if target_date and str(target_date.year) not in query:
        date_suffix = target_date.strftime("%B %d %Y")
        if target_date.strftime("%A").casefold() not in query.casefold():
            date_suffix = target_date.strftime("%A ") + date_suffix
        query = f"{query} {date_suffix}".strip()

    return query or normalize_text_spacing(request_text)


def perform_web_search(query: str) -> dict[str, Any]:
    """Выполнить общий веб-поиск через DuckDuckGo."""
    normalized_query = str(query or "").strip()
    search_payload: dict[str, Any] = {
        "type": "web_search",
        "source": "duckduckgo",
        "status": "error",
        "query": normalized_query,
        "results": [],
    }

    try:
        from ddgs import DDGS
        with DDGS() as ddgs:
            results = list(ddgs.text(normalized_query, max_results=4))

        normalized_results: list[dict[str, str]] = []
        for result in results:
            title = normalize_text_spacing(str(result.get("title", "")).strip())
            body = normalize_text_spacing(str(result.get("body", "")).strip())
            href = str(result.get("href", "")).strip()
            if not any((title, body, href)):
                continue
            normalized_results.append({
                "title": title,
                "snippet": body,
                "url": href,
            })

        if normalized_results:
            logger.info("✅ Результаты веб-поиска получены")
            search_payload["status"] = "ok"
            search_payload["results"] = normalized_results
        else:
            logger.info("ℹ️  Веб-поиск не дал результатов")
            search_payload["status"] = "no_results"
            search_payload["message"] = "No search results found."
    except Exception as e:
        logger.warning(f"⚠️  Веб-поиск не удался: {e}")
        search_payload["message"] = "Web search failed inside the application."
        search_payload["error"] = str(e)

    return search_payload


def describe_weather_code(weather_code: int | None) -> str:
    """Перевести код Open-Meteo в короткое английское описание."""
    if weather_code is None:
        return "mixed weather"
    return WEATHER_CODE_DESCRIPTIONS.get(int(weather_code), "mixed weather")


def pick_dominant_weather_code(codes: list[int]) -> int | None:
    """Выбрать самый частый weather_code из набора часов."""
    if not codes:
        return None
    return Counter(codes).most_common(1)[0][0]


def summarize_hourly_weather(hourly_payload: dict[str, list[Any]], part_of_day: str | None) -> dict[str, Any] | None:
    """Свести почасовой прогноз к короткому summary по нужной части дня."""
    selected_points: list[dict[str, Any]] = []
    allowed_hours = TIME_OF_DAY_RANGES.get(part_of_day)

    for time_str, temperature, precipitation_probability, weather_code, wind_speed in zip(
        hourly_payload.get("time", []),
        hourly_payload.get("temperature_2m", []),
        hourly_payload.get("precipitation_probability", []),
        hourly_payload.get("weather_code", []),
        hourly_payload.get("wind_speed_10m", []),
    ):
        try:
            hour = int(str(time_str)[11:13])
        except (TypeError, ValueError):
            continue

        if allowed_hours is not None and hour not in allowed_hours:
            continue

        selected_points.append({
            "time": str(time_str),
            "temperature_2m": float(temperature),
            "precipitation_probability": int(precipitation_probability),
            "weather_code": int(weather_code),
            "wind_speed_10m": float(wind_speed),
        })

    if not selected_points:
        return None

    return {
        "weather_code": pick_dominant_weather_code([point["weather_code"] for point in selected_points]),
        "min_temp_c": min(point["temperature_2m"] for point in selected_points),
        "max_temp_c": max(point["temperature_2m"] for point in selected_points),
        "max_precipitation_probability": max(point["precipitation_probability"] for point in selected_points),
        "max_wind_kmh": max(point["wind_speed_10m"] for point in selected_points),
    }


def perform_weather_lookup(request_text: str, reference_date: date) -> dict[str, Any]:
    """Получить точный прогноз погоды через Open-Meteo."""
    location_candidates = extract_location_candidates(request_text)
    requested_date = extract_requested_date(request_text, reference_date) or reference_date
    part_of_day = extract_part_of_day(request_text)
    weather_payload: dict[str, Any] = {
        "type": "weather",
        "source": "open-meteo",
        "status": "error",
        "request_text": request_text,
        "location_candidates": location_candidates,
        "requested_date": requested_date.isoformat(),
        "requested_date_label": format_absolute_date(requested_date),
        "part_of_day": part_of_day,
    }

    if not location_candidates:
        weather_payload["message"] = "Could not identify the location in the weather request."
        return weather_payload

    location_record: dict[str, Any] | None = None
    resolved_location = ""
    for candidate in location_candidates:
        try:
            geocoding_response = httpx.get(
                "https://geocoding-api.open-meteo.com/v1/search",
                params={
                    "name": candidate,
                    "count": 1,
                    "language": "en",
                    "format": "json",
                },
                timeout=20,
            )
            geocoding_response.raise_for_status()
            geocoding_payload = geocoding_response.json()
            results = geocoding_payload.get("results") or []
            if not results:
                continue
            location_record = results[0]
            resolved_location = str(location_record.get("name") or candidate)
            break
        except Exception as e:
            logger.warning("⚠️  Геокодирование Open-Meteo не удалось для %s: %s", candidate, e)

    if not location_record:
        weather_payload["message"] = "Could not geocode the location for the weather request."
        return weather_payload

    try:
        forecast_response = httpx.get(
            "https://api.open-meteo.com/v1/forecast",
            params={
                "latitude": location_record["latitude"],
                "longitude": location_record["longitude"],
                "timezone": "auto",
                "hourly": "temperature_2m,precipitation_probability,weather_code,wind_speed_10m",
                "daily": "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code",
                "start_date": requested_date.isoformat(),
                "end_date": requested_date.isoformat(),
            },
            timeout=20,
        )
        forecast_response.raise_for_status()
        forecast_payload = forecast_response.json()
    except Exception as e:
        weather_payload["message"] = "Open-Meteo forecast request failed."
        weather_payload["error"] = str(e)
        return weather_payload

    daily_payload = forecast_payload.get("daily", {})
    hourly_payload = forecast_payload.get("hourly", {})
    period_summary = summarize_hourly_weather(hourly_payload, part_of_day)

    weather_payload.update({
        "status": "ok",
        "resolved_location": resolved_location,
        "country_code": location_record.get("country_code"),
        "timezone": forecast_payload.get("timezone"),
        "daily_summary": {
            "weather_code": (daily_payload.get("weather_code") or [None])[0],
            "min_temp_c": (daily_payload.get("temperature_2m_min") or [None])[0],
            "max_temp_c": (daily_payload.get("temperature_2m_max") or [None])[0],
            "max_precipitation_probability": (daily_payload.get("precipitation_probability_max") or [None])[0],
        },
        "period_summary": period_summary,
    })
    logger.info("✅ Прогноз погоды получен через Open-Meteo: %s, %s", resolved_location, weather_payload["requested_date"])
    return weather_payload


def build_weather_teacher_text(weather_payload: dict[str, Any]) -> str:
    """Построить короткий ответ учителя по прямому weather API without LLM."""
    location_name = str(weather_payload.get("resolved_location", "the city"))
    requested_date_label = str(weather_payload.get("requested_date_label", "that day"))
    part_of_day = weather_payload.get("part_of_day")
    period_summary = weather_payload.get("period_summary") or {}
    daily_summary = weather_payload.get("daily_summary") or {}

    if period_summary:
        weather_code = period_summary.get("weather_code")
        condition = describe_weather_code(weather_code)
        max_precipitation_probability = int(period_summary.get("max_precipitation_probability") or 0)
        if max_precipitation_probability <= 10 and condition in {"clear", "mostly clear", "partly cloudy"}:
            condition = f"{condition} and dry"

        sentences = [
            f"In {location_name} on {requested_date_label}, the {part_of_day} looks {condition}.",
            (
                f"Temperatures should stay around {round(period_summary['min_temp_c'])} to "
                f"{round(period_summary['max_temp_c'])} degrees Celsius, with up to "
                f"{max_precipitation_probability}% chance of precipitation."
            ),
        ]
        if float(period_summary.get("max_wind_kmh") or 0) >= 20:
            sentences.append(f"Wind may reach about {round(period_summary['max_wind_kmh'])} kilometers per hour.")
        return " ".join(sentences)

    daily_weather_code = daily_summary.get("weather_code")
    daily_condition = describe_weather_code(daily_weather_code)
    max_precipitation_probability = int(daily_summary.get("max_precipitation_probability") or 0)
    if max_precipitation_probability <= 10 and daily_condition in {"clear", "mostly clear", "partly cloudy"}:
        daily_condition = f"{daily_condition} and dry"

    return (
        f"In {location_name} on {requested_date_label}, the forecast looks {daily_condition}. "
        f"Temperatures should range from {round(daily_summary['min_temp_c'])} to "
        f"{round(daily_summary['max_temp_c'])} degrees Celsius, with up to "
        f"{max_precipitation_probability}% chance of precipitation."
    )


def build_search_failure_text(lookup_payload: dict[str, Any]) -> str:
    """Сформировать понятный ответ, если интернет-поиск не дал usable data."""
    if lookup_payload.get("type") == "weather":
        return (
            "I tried to check the weather online, but I could not get a reliable forecast just now. "
            "Please try again, or say the city and date more clearly."
        )
    return (
        "I searched the internet, but I could not get useful results just now. "
        "Please try again or ask the question in a more specific way."
    )


def perform_online_lookup(request_text: str, reference_date: date) -> dict[str, Any]:
    """Выполнить обязательный интернет-поиск по детерминированным правилам."""
    if is_weather_request(request_text):
        logger.info("🌤️  Проверяю прогноз погоды через Open-Meteo")
        ui_print("🌤️ Проверяю прогноз погоды онлайн...")
        weather_payload = perform_weather_lookup(request_text, reference_date)
        if weather_payload.get("status") == "ok":
            return weather_payload
        logger.info("ℹ️  Weather lookup не дал надёжного результата, переключаюсь на веб-поиск")

    search_query = build_web_search_query(request_text, reference_date)
    logger.info("🔍 Веб-поиск: %s", search_query)
    ui_print("🌐 Ищу актуальную информацию в интернете...")
    ui_print(f"🔍 Ищу: {search_query}")
    return perform_web_search(search_query)


def get_teacher_response(
    student_text: str,
    level: str = "B1",
    conversation: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Получить структурированный ответ от учителя (текст + chunks для TTS)."""
    history_messages = build_history_messages(conversation or [])
    today_date = datetime.now().date()
    today_label = format_absolute_date(today_date)
    response_messages: list[dict[str, Any]] = []

    try:
        logger.info("🤖 Генерирую ответ учителя...")
        ui_print("⏳ Учитель готовит ответ...")

        effective_request_text = resolve_online_request_text(student_text, conversation)
        online_lookup: dict[str, Any] | None = None

        if should_search_online(student_text, conversation):
            online_lookup = perform_online_lookup(effective_request_text, today_date)
            if online_lookup.get("type") == "weather" and online_lookup.get("status") == "ok":
                teacher_response = normalize_teacher_response_payload(
                    {"display_text": build_weather_teacher_text(online_lookup)},
                    level,
                )
                logger.info(f"✅ Ответ учителя: {teacher_response['display_text']}")
                logger.info(f"🧩 TTS chunks: {len(teacher_response['tts_chunks'])}")
                return teacher_response

            if online_lookup.get("status") != "ok":
                teacher_response = normalize_teacher_response_payload(
                    {"display_text": build_search_failure_text(online_lookup)},
                    level,
                )
                logger.info(f"✅ Ответ учителя: {teacher_response['display_text']}")
                logger.info(f"🧩 TTS chunks: {len(teacher_response['tts_chunks'])}")
                return teacher_response

        system_messages = [
            {"role": "system", "content": f"Today's date: {today_label}.\n\n" + build_structured_teacher_prompt(level)},
        ]

        if online_lookup:
            system_messages.extend([
                {
                    "role": "system",
                    "content": (
                        "The application has already searched the internet because the student asked for current or "
                        "time-sensitive information. Use the online lookup context below directly.\n"
                        "- Answer the factual question directly and concretely.\n"
                        "- If the lookup is partial or uncertain, say exactly that it is partial or uncertain.\n"
                        "- Do not say that you cannot search the internet.\n"
                        "- Do not redirect the student to generic websites unless the lookup failed.\n"
                        "- When the original question used relative dates like today, tomorrow, or this Saturday, "
                        "prefer exact dates in your answer.\n"
                        "Return valid JSON only."
                    ),
                },
                {
                    "role": "system",
                    "content": "Online lookup context retrieved just now:\n" + json.dumps(online_lookup, ensure_ascii=False, indent=2),
                },
            ])

        response_messages = [
            *system_messages,
            *history_messages,
            {"role": "user", "content": student_text},
        ]

        response = client.chat.completions.create(
            model=CHAT_MODEL,
            messages=response_messages,
            response_format={"type": "json_object"},
            temperature=0.4,
            max_tokens=700,
        )

        raw_payload = response.choices[0].message.content or ""
        parsed_payload = parse_json_object(raw_payload)
        teacher_response = normalize_teacher_response_payload(parsed_payload or raw_payload, level)

        logger.info(f"✅ Ответ учителя: {teacher_response['display_text']}")
        logger.info(f"🧩 TTS chunks: {len(teacher_response['tts_chunks'])}")
        return teacher_response

    except Exception as e:
        logger.warning(f"⚠️  Не удалось получить структурированный ответ: {e}")

        try:
            response = client.chat.completions.create(
                model=CHAT_MODEL,
                messages=response_messages or [
                    {"role": "system", "content": f"Today's date: {today_label}.\n\n" + SYSTEM_PROMPTS.get(level, SYSTEM_PROMPTS["B1"])},
                    *history_messages,
                    {"role": "user", "content": student_text},
                ],
                temperature=0.4,
                max_tokens=500,
            )

            fallback_text = response.choices[0].message.content or ""
            teacher_response = normalize_teacher_response_payload({"display_text": fallback_text}, level)
            logger.info(f"✅ Ответ учителя (fallback): {teacher_response['display_text']}")
            logger.info(f"🧩 TTS chunks (fallback): {len(teacher_response['tts_chunks'])}")
            return teacher_response

        except Exception as fallback_error:
            logger.error(f"❌ Ошибка при получении ответа: {fallback_error}")
            return None


def _fetch_tts_chunk(chunk: dict[str, Any], path: Path, safe_speed: float, instructions: str, errors: list) -> None:
    """Сгенерировать один TTS chunk и сохранить в файл (вызывается из фонового потока)."""
    try:
        response = client.audio.speech.create(
            model=TTS_MODEL,
            voice=DEFAULT_TTS_VOICE,
            input=chunk["text"],
            response_format="wav",
            speed=safe_speed,
            extra_body={"instructions": instructions},
        )
        response.stream_to_file(str(path))
    except Exception as e:
        errors.append(e)


def speak_with_openai_chunks(tts_chunks: list[dict[str, Any]]) -> bool:
    """Озвучить ответ через OpenAI TTS с double-buffering: пока играет chunk N, генерируется chunk N+1."""
    if not client:
        logger.warning("⚠️  OpenAI TTS недоступен без API-клиента.")
        return False

    try:
        logger.info("🎤 Озвучиваю ответ через OpenAI TTS с грамматическими паузами...")
        logger.info(f"🧩 Количество chunk-ов: {len(tts_chunks)}")
        ui_print("⏳ Готовлю озвучивание ответа...")
        show_tts_playback_settings("OpenAI TTS", tts_chunks)

        safe_speed = clamp_tts_speed(TTS_SPEED)
        chunk_instructions = build_tts_chunk_instructions(safe_speed)
        errors: list = []

        with tempfile.TemporaryDirectory() as temp_dir_name:
            temp_dir = Path(temp_dir_name)

            # Генерируем первый chunk синхронно — без него нечего воспроизводить.
            current_path = temp_dir / "chunk_00.wav"
            with tts_preparing_wait_feedback():
                _fetch_tts_chunk(tts_chunks[0], current_path, safe_speed, chunk_instructions, errors)
            if errors:
                raise errors[0]

            ui_print("🔊 Озвучиваю ответ...")
            for index in range(len(tts_chunks)):
                # Запускаем генерацию следующего chunk в фоне пока воспроизводится текущий.
                next_thread: threading.Thread | None = None
                next_path: Path | None = None
                if index + 1 < len(tts_chunks):
                    next_path = temp_dir / f"chunk_{index + 1:02d}.wav"
                    next_thread = threading.Thread(
                        target=_fetch_tts_chunk,
                        args=(tts_chunks[index + 1], next_path, safe_speed, chunk_instructions, errors),
                        daemon=True,
                    )
                    next_thread.start()

                if not play_audio_file(current_path):
                    if next_thread:
                        next_thread.join()
                    return False

                if index < len(tts_chunks) - 1:
                    pause_ms = get_effective_pause_ms(tts_chunks[index].get("pause_ms", CLAUSE_PAUSE_MS))
                    time.sleep(pause_ms / 1000)

                if next_thread:
                    next_thread.join()
                    if errors:
                        raise errors[0]
                    current_path = next_path

        logger.info("✅ Озвучено (OpenAI TTS с паузами)")
        ui_print("✅ Ответ озвучен.")
        return True

    except Exception as e:
        logger.warning(f"⚠️  OpenAI TTS ошибка: {e}")

    return False


def play_input_beep() -> None:
    """Сыграть короткий звуковой сигнал, что программа ждёт голосового ввода."""
    play_synthetic_status_tone(
        frequency=880,
        duration=0.25,
        audio_filter="volume=0.06,afade=t=out:st=0.15:d=0.1",
    )


def play_stop_listening_beep() -> None:
    """Сыграть короткий нисходящий сигнал, что программа перестала слушать."""
    play_synthetic_status_tone(
        frequency=520,
        duration=0.2,
        audio_filter="volume=0.06,afade=t=in:st=0:d=0.05,afade=t=out:st=0.12:d=0.08",
    )


def play_listening_active_beep() -> None:
    """Сыграть тихий повторяющийся сигнал, пока программа слушает микрофон."""
    play_synthetic_status_tone(
        frequency=930,
        duration=0.09,
        audio_filter="volume=0.025,afade=t=in:st=0:d=0.015,afade=t=out:st=0.05:d=0.04",
    )


def play_transcribing_beep() -> None:
    """Сыграть мягкий сигнал, пока Whisper ещё распознаёт речь."""
    play_synthetic_status_tone(
        frequency=610,
        duration=0.11,
        audio_filter="volume=0.035,afade=t=in:st=0:d=0.02,afade=t=out:st=0.06:d=0.05",
        fallback_bell=True,
    )


def play_teacher_waiting_beep() -> None:
    """Сыграть мягкий короткий сигнал, что учитель всё ещё готовит ответ."""
    play_synthetic_status_tone(
        frequency=660,
        duration=0.12,
        audio_filter="volume=0.04,afade=t=in:st=0:d=0.02,afade=t=out:st=0.07:d=0.05",
        fallback_bell=True,
    )


def play_tts_preparing_beep() -> None:
    """Сыграть мягкий сигнал, пока готовится первый TTS chunk."""
    play_synthetic_status_tone(
        frequency=760,
        duration=0.11,
        audio_filter="volume=0.035,afade=t=in:st=0:d=0.02,afade=t=out:st=0.06:d=0.05",
        fallback_bell=True,
    )


def play_synthetic_status_tone(
    frequency: int,
    duration: float,
    audio_filter: str,
    *,
    timeout_seconds: float = 5,
    fallback_bell: bool = False,
) -> None:
    """Сыграть короткий синтетический тон через ffplay."""
    try:
        subprocess.run(
            [
                "ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet",
                "-f", "lavfi",
                "-i", f"sine=frequency={frequency}:duration={duration}",
                "-af", audio_filter,
            ],
            capture_output=True,
            timeout=timeout_seconds,
            check=False,
        )
    except Exception:
        if not fallback_bell:
            return
        try:
            sys.stdout.write("\a")
            sys.stdout.flush()
        except Exception:
            pass


@contextmanager
def periodic_stage_feedback(
    interval_seconds: float,
    beep_callback: Callable[[], None],
    *,
    logger_message: str | None = None,
    ui_message: str | None = None,
    play_immediately: bool = False,
):
    """Периодически напоминать пользователю, что текущий этап всё ещё идёт."""
    stop_event = threading.Event()
    started_at = time.monotonic()

    def notify() -> None:
        if play_immediately:
            beep_callback()

        while not stop_event.wait(interval_seconds):
            elapsed_seconds = int(time.monotonic() - started_at)
            if logger_message:
                logger.info(logger_message, elapsed_seconds)
            if ui_message:
                ui_print(ui_message.format(seconds=elapsed_seconds))
            beep_callback()

    notifier_thread = threading.Thread(target=notify, daemon=True)
    notifier_thread.start()
    try:
        yield
    finally:
        stop_event.set()
        notifier_thread.join(timeout=1.0)


@contextmanager
def listening_wait_feedback(interval_seconds: float = LISTENING_SIGNAL_INTERVAL_SECONDS):
    """Тихо напоминать, что запись голоса всё ещё продолжается."""
    with periodic_stage_feedback(interval_seconds, play_listening_active_beep):
        yield


@contextmanager
def transcription_wait_feedback(interval_seconds: float = TRANSCRIPTION_WAIT_SIGNAL_INTERVAL_SECONDS):
    """Периодически сообщать, что Whisper всё ещё распознаёт речь."""
    with periodic_stage_feedback(
        interval_seconds,
        play_transcribing_beep,
        logger_message="⏳ Whisper всё ещё распознаёт речь... %s сек",
        ui_message="⏳ Всё ещё распознаю вашу речь... {seconds} сек.",
    ):
        yield


@contextmanager
def teacher_response_wait_feedback(interval_seconds: float = TEACHER_WAIT_SIGNAL_INTERVAL_SECONDS):
    """Периодически напоминать, что длинная генерация ответа всё ещё идёт."""
    with periodic_stage_feedback(
        interval_seconds,
        play_teacher_waiting_beep,
        logger_message="⏳ Учитель всё ещё готовит ответ... %s сек",
        ui_message="⏳ Учитель всё ещё готовит ответ... {seconds} сек.",
    ):
        yield


@contextmanager
def tts_preparing_wait_feedback(interval_seconds: float = TTS_PREPARING_SIGNAL_INTERVAL_SECONDS):
    """Периодически сообщать, что первый TTS chunk всё ещё готовится."""
    with periodic_stage_feedback(
        interval_seconds,
        play_tts_preparing_beep,
        logger_message="⏳ OpenAI TTS всё ещё готовит первый audio chunk... %s сек",
        ui_message="⏳ Всё ещё готовлю озвучивание ответа... {seconds} сек.",
    ):
        yield



def play_audio_file(audio_file: Path) -> bool:
    """Воспроизвести подготовленный аудиофайл через ffplay или системный WAV-плеер."""
    playback_attempts = [
        ("ffplay", ["ffplay", "-nodisp", "-autoexit", str(audio_file)], 60),
    ]
    if audio_file.suffix.lower() == ".wav":
        playback_attempts.append(("aplay", ["aplay", "-q", str(audio_file)], 60))

    for player_name, command, timeout_seconds in playback_attempts:
        try:
            result = subprocess.run(
                command,
                capture_output=True,
                timeout=timeout_seconds,
                check=False,
            )
            if result.returncode == 0:
                return True

            stderr_text = result.stderr.decode(errors="ignore").strip()
            if stderr_text:
                logger.warning("⚠️  %s завершился с ошибкой: %s", player_name, stderr_text)
            else:
                logger.warning("⚠️  %s завершился с кодом %s", player_name, result.returncode)
        except FileNotFoundError:
            logger.info("ℹ️ %s не найден, пробую следующий способ воспроизведения.", player_name)
        except Exception as e:
            logger.warning("⚠️  Не удалось воспроизвести аудиофайл через %s: %s", player_name, e)

    logger.warning("⚠️  Не удалось воспроизвести аудиофайл локально.")
    return False


def speak_response(
    teacher_response: Any,
    enable_audio: bool = True,
    level: str = LANGUAGE_LEVEL,
):
    """
    Озвучить ответ учителя с паузами между грамматическими chunk-ами.

    Args:
        teacher_response: dict с display_text и tts_chunks или обычная строка
        enable_audio: Озвучивать ли (True/False)
    """
    display_text, tts_chunks = get_display_text_and_chunks(teacher_response, level)

    if not display_text:
        logger.warning("⚠️  Нет текста для озвучивания.")
        return

    show_teacher_chunk_sequence(tts_chunks)

    if not enable_audio:
        logger.info(f"🔇 TTS отключен. Ответ: {display_text}")
        return

    logger.info("🔊 Конфиг TTS: service=openai, requested_speech_speed=%.2f", TTS_SPEED)

    if speak_with_openai_chunks(tts_chunks):
        return

    ui_print("⚠️ Озвучка недоступна. Ответ показан только текстом.")


def display_menu(current_level: str):
    """Показать главное меню"""
    ui_header("📋 МЕНЮ")
    ui_print(f"Текущий уровень: {current_level}")
    ui_print("  1. 🎤 Начать сеанс обучения")
    ui_print("  2. 🔧 Выбрать уровень (A2/B1/B2)")
    ui_print("  3. 📊 Показать историю беседы")
    ui_print("  4. ❓ Справка")
    ui_print("  5. 🚪 Выход")
    ui_print("\nВведите только цифру 1-5 и нажмите Enter.")


def practice_session(level: str = "B1", enable_audio: bool = True):
    """Главный цикл обучения"""
    if not ensure_openai_api_key() or not ensure_microphone_available():
        return

    session = ConversationSession(level)

    logger.info(f"\n{'='*70}")
    logger.info(f"🎓 СЕАНС ОБУЧЕНИЯ ЗАПУЩЕН (уровень: {level})")
    logger.info(f"{'='*70}\n")

    ui_header(f"🎓 СЕАНС ОБУЧЕНИЯ (уровень: {level})")
    ui_print("Что делать:")
    ui_print("  • Говорите по-английски в микрофон, когда увидите подсказку 'Говорите...'.")
    ui_print(f"  • Когда закончите фразу, просто помолчите {PAUSE_THRESHOLD} сек.")
    ui_print(
        "  • Озвучивание: OpenAI TTS, "
        f"скорость слов {TTS_SPEED:.2f}; паузы между chunk-ами настраиваются отдельно."
    )
    ui_print("  • Если учитель думает дольше обычного, программа подаст короткий сигнал ожидания.")
    ui_print("  • Для выхода из сеанса можно нажать Ctrl+C.\n")
    if SESSION_IDLE_TIMEOUT > 0:
        ui_print(f"  • Если не говорить слишком долго, сеанс завершится сам через {SESSION_IDLE_TIMEOUT:.0f} сек.\n")

    round_num = 0
    while True:
        try:
            round_num += 1
            ui_print(f"\n{ROUND_DIVIDER}")
            ui_print(f"🔄 РАУНД {round_num}")
            ui_print(f"{ROUND_DIVIDER}\n")

            # ЭТАП 1: Запись
            ui_print("📝 Шаг 1/4: Запись вашей речи")
            play_input_beep()
            try:
                audio_file = record_audio(PAUSE_THRESHOLD, PHRASE_TIME_LIMIT, SESSION_IDLE_TIMEOUT)
            finally:
                play_stop_listening_beep()

            if not audio_file:
                logger.warning("⚠️  Не удалось записать аудио. Попробуйте снова.")
                continue

            # ЭТАП 2: Распознавание
            ui_print("\n📝 Шаг 2/4: Распознавание речи")
            student_text = transcribe_audio(audio_file)

            if not student_text:
                logger.warning("⚠️  Не удалось распознать речь. Попробуйте снова.")
                continue

            # ЭТАП 3: Ответ учителя
            ui_print("\n📝 Шаг 3/4: Генерация ответа учителя")
            with teacher_response_wait_feedback():
                teacher_response = get_teacher_response(student_text, level, session.conversation)

            if not teacher_response:
                logger.warning("⚠️  Не удалось получить ответ. Попробуйте снова.")
                continue

            teacher_text, tts_chunks = get_display_text_and_chunks(teacher_response, level)

            # Добавить в историю
            session.add_exchange(student_text, teacher_response)

            # ЭТАП 4: Озвучивание
            ui_print("\n📝 Шаг 4/4: Озвучивание ответа")
            speak_response(teacher_response, enable_audio, level=level)

            # Вывести результаты раунда
            ui_print(f"\n{ROUND_DIVIDER}")
            ui_print(f"👤 ВЫ:     {student_text}")
            ui_print(f"👨‍🏫 УЧИТЕЛЬ: {teacher_text}")
            ui_print(f"🧩 Грамматических блоков для TTS: {len(tts_chunks)}")
            ui_print(f"{ROUND_DIVIDER}")
        except SessionIdleTimeout:
            ui_print("\n⏹️  Сеанс завершён: вы слишком долго ничего не говорили.")
            break
        except KeyboardInterrupt:
            ui_print("\n\n⏹️  Сеанс прерван пользователем")
            break
        except Exception as e:
            logger.error(f"❌ Ошибка в раунде {round_num}: {e}")
            continue

    if session.round_count == 0:
        ui_print("\nℹ️  Сеанс завершён без сохранения: не было ни одного раунда.")
        logger.info("ℹ️  Пустой сеанс не сохраняю")
        return

    # Сохранить сеанс
    saved_session = session.save()
    ui_print(f"\n💾 Сеанс сохранён: {saved_session}")
    session.print_summary()


def show_history(session_id: str = None):
    """Показать историю беседы"""
    if session_id:
        session_file = SESSIONS_DIR / f"session_{session_id}.json"
        if not session_file.exists():
            logger.error(f"❌ Сеанс {session_id} не найден")
            return

        with open(session_file, "r", encoding="utf-8") as f:
            session_data = json.load(f)
    else:
        # Показать последний сеанс
        sessions = sorted(SESSIONS_DIR.glob("session_*.json"))
        if not sessions:
            logger.warning("⚠️  Нет сохранённых сеансов")
            return

        with open(sessions[-1], "r", encoding="utf-8") as f:
            session_data = json.load(f)

    ui_header("📖 ИСТОРИЯ БЕСЕДЫ")
    ui_print(f"Сеанс ID: {session_data['session_id']}")
    ui_print(f"Уровень: {session_data['level']}")
    ui_print(f"Раундов: {session_data['total_rounds']}")
    ui_print(DIVIDER + "\n")

    for exchange in session_data['conversation']:
        ui_print(f"[Раунд {exchange['round']+1}]")
        ui_print(f"  👤 Студент:  {exchange['student']}")
        ui_print(f"  👨‍🏫 Учитель:  {exchange['teacher']}\n")

    ui_print(DIVIDER + "\n")


def show_help():
    """Показать справку"""
    ui_header("❓ СПРАВКА")
    ui_print("Что программа ждёт от вас:")
    ui_print("  • В меню: введите цифру 1-5 и нажмите Enter.")
    ui_print("  • В сеансе: говорите в микрофон по-английски.")
    ui_print(f"  • После фразы: помолчите {PAUSE_THRESHOLD} сек, чтобы запись завершилась.")
    if SESSION_IDLE_TIMEOUT > 0:
        ui_print(f"  • Если не начать новую реплику за {SESSION_IDLE_TIMEOUT:.0f} сек., сеанс завершится автоматически.")
    ui_print("  • Для ручного выхода из сеанса нажмите Ctrl+C.")

    ui_print("\nЧто НЕ нужно делать:")
    ui_print("  • Не нужно печатать английский текст во время голосового сеанса.")
    ui_print("  • Не нужно вручную останавливать запись, если вы просто закончили говорить.")

    ui_print("\nЭтапы работы:")
    ui_print("  1. Запись вашей речи")
    ui_print("  2. Распознавание через Whisper")
    ui_print("  3. Ответ учителя через ChatGPT")
    ui_print("  4. Озвучивание ответа с паузами между грамматическими частями")

    ui_print("\nУровни языка:")
    ui_print("  A2: Простой словарь, короткие предложения")
    ui_print("  B1: Средний словарь, естественные предложения")
    ui_print("  B2: Продвинутый словарь, сложные структуры")
    ui_print("\n" + DIVIDER + "\n")


# ===== ГЛАВНАЯ ПРОГРАММА =====

def main():
    """Главная функция"""

    # Парсинг аргументов
    if len(sys.argv) > 1:
        if sys.argv[1] == "--practice":
            practice_session(LANGUAGE_LEVEL, enable_audio=True)
        elif sys.argv[1] == "--history":
            show_history()
        else:
            logger.error(f"❌ Неизвестный аргумент: {sys.argv[1]}")
            sys.exit(1)
    else:
        # Интерактивный режим
        current_level = LANGUAGE_LEVEL
        show_startup_hint(current_level)

        while True:
            display_menu(current_level)
            choice = input("🎯 Выберите пункт (1-5): ").strip()

            if choice == "1":
                practice_session(current_level, enable_audio=True)
            elif choice == "2":
                ui_print("\nВыберите уровень:")
                ui_print("  1. A2 (Начинающий)")
                ui_print("  2. B1 (Средний)")
                ui_print("  3. B2 (Продвинутый)")
                level_choice = input("Выбор (1-3): ").strip()

                if level_choice == "1":
                    current_level = "A2"
                elif level_choice == "2":
                    current_level = "B1"
                elif level_choice == "3":
                    current_level = "B2"
                else:
                    ui_print("❌ Неверный выбор")
                    continue

                ui_print(f"✅ Уровень установлен: {current_level}")

            elif choice == "3":
                show_history()
            elif choice == "4":
                show_help()
            elif choice == "5":
                ui_print("\n👋 До встречи! Успехов в изучении английского! 🎉\n")
                sys.exit(0)
            else:
                ui_print("❌ Неверный выбор. Попробуйте снова.")


if __name__ == "__main__":
    main()
