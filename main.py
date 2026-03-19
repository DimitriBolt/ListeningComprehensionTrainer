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
import wave
import time
import tempfile
import subprocess
from pathlib import Path
from datetime import datetime
import json
from typing import Any, Optional
from contextlib import contextmanager
from io import UnsupportedOperation
from dotenv import dotenv_values
import speech_recognition as sr
import pyttsx3

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
# - Паузы ниже независимо управляют ритмом и "дыханием" речи учителя.
# - MIN/MAX задают safety bounds для любых pause_ms в chunk-ах.
PAUSE_THRESHOLD = get_required_float_env_value(ENV_CONFIG, "PAUSE_THRESHOLD")  # Секунды тишины до автоостановки записи; увеличьте, если конец фразы обрезается.
PHRASE_TIME_LIMIT = get_required_float_env_value(ENV_CONFIG, "PHRASE_TIME_LIMIT")
SESSION_IDLE_TIMEOUT = get_required_float_env_value(ENV_CONFIG, "SESSION_IDLE_TIMEOUT")  # Сколько ждать начала новой реплики; если тишина длится дольше, сеанс завершается.
TTS_SPEED = get_required_float_env_value(ENV_CONFIG, "TTS_SPEED")  # Скорость произнесения слов TTS; меньше = медленнее/четче, больше = быстрее/естественнее.
TTS_VOLUME = get_required_float_env_value(ENV_CONFIG, "TTS_VOLUME")
TTS_SERVICE = get_required_env_value(ENV_CONFIG, "TTS_SERVICE").lower()
MIN_PAUSE_MS = get_required_int_env_value(ENV_CONFIG, "MIN_PAUSE_MS")
MAX_PAUSE_MS = get_required_int_env_value(ENV_CONFIG, "MAX_PAUSE_MS")
SMALL_PAUSE_MS = get_required_int_env_value(ENV_CONFIG, "SMALL_PAUSE_MS")
CLAUSE_PAUSE_MS = get_required_int_env_value(ENV_CONFIG, "CLAUSE_PAUSE_MS")
SENTENCE_PAUSE_MS = get_required_int_env_value(ENV_CONFIG, "SENTENCE_PAUSE_MS")

if MIN_PAUSE_MS > MAX_PAUSE_MS:
    raise SystemExit(
        f"В {ENV_FILE} MIN_PAUSE_MS={MIN_PAUSE_MS} не может быть больше MAX_PAUSE_MS={MAX_PAUSE_MS}."
    )

# Инициализация клиентов
client = create_openai_client(OPENAI_API_KEY)
recognizer = sr.Recognizer()
tts_engine: Optional[pyttsx3.Engine] = None
tts_init_attempted = False

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
LOCAL_TTS_BASE_RATE = 150
GRAMMAR_CHUNK_MAX_WORDS = {
    "A2": 4,
    "B1": 5,
    "B2": 6,
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
    ui_header("🎓 LISTENING COMPREHENSION TRAINER")
    ui_print(f"Текущий уровень: {current_level}")
    ui_print("\nЧто программа ждёт от вас:")
    ui_print("  • В меню введите цифру 1-5 и нажмите Enter.")
    ui_print("  • Для тренировки выберите пункт 1.")
    ui_print("  • После начала сеанса говорите в микрофон по-английски.")
    ui_print(f"  • Когда закончите, просто помолчите {PAUSE_THRESHOLD} сек.")
    if SESSION_IDLE_TIMEOUT > 0:
        ui_print(f"  • Если слишком долго молчать перед новой репликой, сеанс завершится через {SESSION_IDLE_TIMEOUT:.0f} сек.")
    ui_print("  • Для принудительного выхода из сеанса используйте Ctrl+C.")
    ui_print("\nПодробные технические логи пишутся в logs/main.log.")


def clamp_tts_speed(speed: float) -> float:
    """Ограничить скорость TTS безопасным диапазоном API/движка."""
    return max(MIN_TTS_SPEED, min(MAX_TTS_SPEED, speed))


def get_local_tts_rate(speed: float) -> int:
    """Рассчитать фактический rate для локального pyttsx3."""
    return max(90, min(320, int(LOCAL_TTS_BASE_RATE * clamp_tts_speed(speed))))


def get_effective_pause_ms(pause_ms: Any) -> int:
    """Свести любую паузу к одному из явно заданных пользователем значений."""
    try:
        pause_value = int(pause_ms)
    except (TypeError, ValueError):
        pause_value = CLAUSE_PAUSE_MS

    pause_value = max(MIN_PAUSE_MS, min(MAX_PAUSE_MS, pause_value))
    configured_pauses = [SMALL_PAUSE_MS, CLAUSE_PAUSE_MS, SENTENCE_PAUSE_MS]
    return min(configured_pauses, key=lambda configured: (abs(configured - pause_value), configured))


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

    if service_label == "OpenAI TTS":
        logger.info(
            "🔧 OpenAI TTS: model=%s, voice=%s",
            TTS_MODEL,
            DEFAULT_TTS_VOICE,
        )
    else:
        logger.info("🔧 pyttsx3: rate=%d WPM", get_local_tts_rate(effective_speed))

    ui_print("🔧 Фактические параметры озвучивания:")
    ui_print(f"   Сервис: {service_label}")
    ui_print(f"   Скорость слов: {speed_details}")
    if service_label == "OpenAI TTS":
        ui_print(f"   OpenAI модель/голос: {TTS_MODEL} / {DEFAULT_TTS_VOICE}")
    else:
        ui_print(f"   Локальный rate движка: {get_local_tts_rate(effective_speed)} WPM")
    ui_print(f"   Chunk-ов в ответе: {len(tts_chunks)}")
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


def get_max_chunk_words(level: str) -> int:
    """Вернуть максимально допустимый размер грамматического chunk-а для уровня."""
    return GRAMMAR_CHUNK_MAX_WORDS.get(str(level or "").upper(), GRAMMAR_CHUNK_MAX_WORDS["B1"])


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
        + """ words per chunk for this level.
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
"""
    )


def get_display_text_and_chunks(teacher_response: Any, level: str = LANGUAGE_LEVEL) -> tuple[str, list[dict[str, Any]]]:
    """Получить текст для экрана и chunks для TTS."""
    normalized = normalize_teacher_response_payload(teacher_response, level)
    return normalized["display_text"], normalized["tts_chunks"]


def generate_silence_frames(pause_ms: int, frame_rate: int, channels: int, sample_width: int) -> bytes:
    """Сгенерировать WAV-тишину нужной длины."""
    frame_count = max(0, round(frame_rate * (pause_ms / 1000)))
    return b"\x00" * frame_count * channels * sample_width


def trim_wav_silence(audio_frames: bytes, sample_width: int, channels: int, silence_threshold: int = 150) -> bytes:
    """Обрезать тишину с начала и конца WAV-фреймов."""
    frame_size = sample_width * channels
    if frame_size == 0 or len(audio_frames) < frame_size:
        return audio_frames

    def is_silent_frame(frame_bytes: bytes) -> bool:
        if sample_width == 2:
            import struct
            samples = struct.unpack_from(f"<{len(frame_bytes) // 2}h", frame_bytes)
            return all(abs(s) < silence_threshold for s in samples)
        return all(b < silence_threshold for b in frame_bytes)

    frames = [audio_frames[i:i + frame_size] for i in range(0, len(audio_frames) - frame_size + 1, frame_size)]

    start = 0
    while start < len(frames) and is_silent_frame(frames[start]):
        start += 1

    end = len(frames)
    while end > start and is_silent_frame(frames[end - 1]):
        end -= 1

    return b"".join(frames[start:end])


def stitch_wav_chunks(chunk_files: list[tuple[Path, int]], output_file: Path) -> Path:
    """Склеить WAV-куски и добавить тишину между ними."""
    if not chunk_files:
        raise ValueError("Нет WAV-кусков для склейки")

    base_params = None

    with wave.open(str(output_file), "wb") as output_wav:
        for index, (chunk_file, pause_ms) in enumerate(chunk_files):
            with wave.open(str(chunk_file), "rb") as input_wav:
                params = input_wav.getparams()
                audio_frames = input_wav.readframes(input_wav.getnframes())

            current_params = (params.nchannels, params.sampwidth, params.framerate)
            if base_params is None:
                base_params = current_params
                output_wav.setnchannels(params.nchannels)
                output_wav.setsampwidth(params.sampwidth)
                output_wav.setframerate(params.framerate)
            elif current_params != base_params:
                raise ValueError("OpenAI TTS вернул WAV-куски с разными параметрами")

            audio_frames = trim_wav_silence(audio_frames, params.sampwidth, params.nchannels)
            output_wav.writeframes(audio_frames)

            if index < len(chunk_files) - 1 and pause_ms > 0:
                output_wav.writeframes(
                    generate_silence_frames(pause_ms, params.framerate, params.nchannels, params.sampwidth)
                )

    return output_file


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


def initialize_tts_engine(speed: float = 0.8, volume: float = 0.9) -> Optional[pyttsx3.Engine]:
    """Инициализировать pyttsx3 движок, если локальный TTS доступен."""
    try:
        with suppress_stderr():
            engine = pyttsx3.init()

        # Базовая локальная скорость ближе к естественной речи; TTS_SPEED масштабирует её.
        engine.setProperty('rate', get_local_tts_rate(speed))

        # Максимальная громкость для лучшей слышимости
        engine.setProperty('volume', max(0.9, volume))

        voices = engine.getProperty('voices')
        if voices and len(voices) > 1:
            # Женский голос обычно звучит лучше и понятнее
            engine.setProperty('voice', voices[1].id)

        return engine
    except Exception as e:
        logger.warning(
            "⚠️  Локальный TTS через pyttsx3 недоступен: %s. "
            "Сессия продолжится без локальной озвучки.",
            e
        )
        return None


def get_tts_engine(speed: float = 0.8, volume: float = 0.9) -> Optional[pyttsx3.Engine]:
    """Ленивая инициализация локального TTS с кешированием результата."""
    global tts_engine, tts_init_attempted

    if tts_engine is not None:
        return tts_engine
    if tts_init_attempted:
        return None

    tts_init_attempted = True
    tts_engine = initialize_tts_engine(speed, volume)
    return tts_engine


def get_configured_tts_service() -> str:
    """Вернуть корректно нормализованное имя TTS-сервиса."""
    if TTS_SERVICE in {"openai", "pyttsx3"}:
        return TTS_SERVICE

    logger.warning("⚠️  Неизвестный TTS_SERVICE=%s, использую openai", TTS_SERVICE)
    return "openai"


def get_tts_service_label(tts_service: str) -> str:
    """Понятная подпись активного TTS-сервиса для UI и логов."""
    if tts_service == "pyttsx3":
        return "pyttsx3"
    return "OpenAI TTS"


def record_audio(
    pause_threshold: float = 2.5,
    phrase_time_limit: float = 30,
    session_idle_timeout: float | None = SESSION_IDLE_TIMEOUT,
) -> str | None:
    """
    Записать аудио через микрофон

    Args:
        pause_threshold: Пауза для завершения записи (сек)
        phrase_time_limit: Максимум времени записи (сек)
        session_idle_timeout: Максимум ожидания начала речи (сек)

    Returns:
        Путь к сохранённому WAV файлу
    """
    logger.info("🎙️  Слушаю микрофон...")
    logger.info(f"   Пауза обнаружения: {pause_threshold}s")
    logger.info(f"   Максимум времени: {phrase_time_limit}s")
    if session_idle_timeout and session_idle_timeout > 0:
        logger.info(f"   Автовыход при молчании: {session_idle_timeout}s")
    logger.info("Начинайте говорить! (программа завершит запись после паузы)\n")
    ui_print("🎙️ Сейчас программа ждёт ваш голос в микрофон.")
    ui_print(f"   Скажите фразу на английском и затем помолчите {pause_threshold} сек.")
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
                audio = recognizer.listen(
                    source,
                    timeout=session_idle_timeout if session_idle_timeout and session_idle_timeout > 0 else None,
                    phrase_time_limit=phrase_time_limit
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


def get_teacher_response(
    student_text: str,
    level: str = "B1",
    conversation: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Получить структурированный ответ от учителя (текст + chunks для TTS)."""
    history_messages = build_history_messages(conversation or [])
    try:
        logger.info("🤖 Генерирую ответ учителя...")
        ui_print("⏳ Учитель готовит ответ...")

        response = client.chat.completions.create(
            model=CHAT_MODEL,
            messages=[
                {
                    "role": "system",
                    "content": build_structured_teacher_prompt(level)
                },
                *history_messages,
                {
                    "role": "user",
                    "content": student_text
                }
            ],
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
                messages=[
                    {
                        "role": "system",
                        "content": SYSTEM_PROMPTS.get(level, SYSTEM_PROMPTS["B1"])
                    },
                    *history_messages,
                    {
                        "role": "user",
                        "content": student_text
                    }
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


def render_chunked_openai_tts(tts_chunks: list[dict[str, Any]], output_file: Path) -> Path:
    """Сгенерировать OpenAI TTS по chunk-ам и склеить результат в один WAV."""
    chunk_files: list[tuple[Path, int]] = []
    safe_speed = clamp_tts_speed(TTS_SPEED)
    chunk_instructions = build_tts_chunk_instructions(safe_speed)

    with tempfile.TemporaryDirectory() as temp_dir_name:
        temp_dir = Path(temp_dir_name)

        for index, chunk in enumerate(tts_chunks):
            chunk_path = temp_dir / f"chunk_{index:02d}.wav"
            response = client.audio.speech.create(
                model=TTS_MODEL,
                voice=DEFAULT_TTS_VOICE,
                input=chunk["text"],
                response_format="wav",
                speed=safe_speed,
                extra_body={"instructions": chunk_instructions},
            )
            response.stream_to_file(str(chunk_path))
            chunk_files.append((chunk_path, get_effective_pause_ms(chunk.get("pause_ms", CLAUSE_PAUSE_MS))))

        return stitch_wav_chunks(chunk_files, output_file)


def speak_with_openai_chunks(tts_chunks: list[dict[str, Any]]) -> bool:
    """Озвучить ответ через OpenAI TTS и воспроизвести результат."""
    if not client:
        logger.warning("⚠️  OpenAI TTS недоступен без API-клиента.")
        return False

    try:
        logger.info("🎤 Озвучиваю ответ через OpenAI TTS с грамматическими паузами...")
        logger.info(f"🧩 Количество chunk-ов: {len(tts_chunks)}")
        ui_print("🔊 Озвучиваю ответ...")
        show_tts_playback_settings("OpenAI TTS", tts_chunks)

        with tempfile.TemporaryDirectory() as temp_dir_name:
            final_audio_file = Path(temp_dir_name) / "teacher_response.wav"
            render_chunked_openai_tts(tts_chunks, final_audio_file)
            logger.info("✅ Озвучено (OpenAI TTS с паузами)")

            if play_audio_file(final_audio_file):
                ui_print("✅ Ответ озвучен.")
                return True

    except Exception as e:
        logger.warning(f"⚠️  OpenAI TTS ошибка: {e}")

    return False


def play_input_beep() -> None:
    """Сыграть короткий звуковой сигнал, что программа ждёт голосового ввода."""
    try:
        subprocess.run(
            [
                "ffplay", "-nodisp", "-autoexit",
                "-f", "lavfi",
                "-i", "sine=frequency=880:duration=0.25",
                "-af", "afade=t=out:st=0.15:d=0.1",
            ],
            capture_output=True,
            timeout=5,
            check=False,
        )
    except Exception:
        pass


def play_stop_listening_beep() -> None:
    """Сыграть короткий нисходящий сигнал, что программа перестала слушать."""
    try:
        subprocess.run(
            [
                "ffplay", "-nodisp", "-autoexit",
                "-f", "lavfi",
                "-i", "sine=frequency=520:duration=0.2",
                "-af", "afade=t=in:st=0:d=0.05,afade=t=out:st=0.12:d=0.08",
            ],
            capture_output=True,
            timeout=5,
            check=False,
        )
    except Exception:
        pass


def play_audio_file(audio_file: Path) -> bool:
    """Воспроизвести подготовленный аудиофайл через ffplay."""
    try:
        result = subprocess.run(
            ["ffplay", "-nodisp", "-autoexit", str(audio_file)],
            capture_output=True,
            timeout=60,
            check=False,
        )
        if result.returncode == 0:
            return True

        logger.warning("⚠️  ffplay завершился с ошибкой, перехожу на локальный TTS fallback...")
        return False
    except FileNotFoundError:
        logger.warning("⚠️  ffplay не найден, перехожу на локальный TTS fallback...")
        return False
    except Exception as e:
        logger.warning(f"⚠️  Не удалось воспроизвести аудиофайл через ffplay: {e}")
        return False


def speak_with_local_chunks(fallback_engine: Optional[pyttsx3.Engine], tts_chunks: list[dict[str, Any]]) -> bool:
    """Локальный fallback: озвучить chunk-и по очереди и выдержать паузы."""
    if fallback_engine is None:
        fallback_engine = get_tts_engine(TTS_SPEED, TTS_VOLUME)
    if fallback_engine is None:
        logger.warning("⚠️  Локальный TTS недоступен.")
        return False

    logger.info("🎤 Озвучиваю ответ через pyttsx3...")
    ui_print("🔊 Озвучиваю ответ...")
    show_tts_playback_settings("pyttsx3", tts_chunks)

    with suppress_stderr():
        for index, chunk in enumerate(tts_chunks):
            fallback_engine.say(chunk["text"])
            fallback_engine.runAndWait()

            if index < len(tts_chunks) - 1:
                pause_ms = get_effective_pause_ms(chunk.get("pause_ms", CLAUSE_PAUSE_MS))
                time.sleep(pause_ms / 1000)

    logger.info("✅ Озвучено")
    ui_print("✅ Ответ озвучен.")
    return True


def speak_response(
    engine: Optional[pyttsx3.Engine],
    teacher_response: Any,
    enable_audio: bool = True,
    use_openai_tts: bool = True,
    level: str = LANGUAGE_LEVEL,
):
    """
    Озвучить ответ учителя с паузами между грамматическими chunk-ами.

    Args:
        engine: pyttsx3.Engine объект (используется если OpenAI недоступен)
        teacher_response: dict с display_text и tts_chunks или обычная строка
        enable_audio: Озвучивать ли (True/False)
        use_openai_tts: Использовать ли OpenAI TTS (если доступен)
    """
    display_text, tts_chunks = get_display_text_and_chunks(teacher_response, level)

    if not display_text:
        logger.warning("⚠️  Нет текста для озвучивания.")
        return

    show_teacher_chunk_sequence(tts_chunks)

    if not enable_audio:
        logger.info(f"🔇 TTS отключен. Ответ: {display_text}")
        return

    fallback_engine = engine
    tts_service = get_configured_tts_service()
    logger.info("🔊 Конфиг TTS: service=%s, requested_speech_speed=%.2f", tts_service, TTS_SPEED)

    if tts_service == "openai":
        if use_openai_tts and speak_with_openai_chunks(tts_chunks):
            return

        logger.warning("⚠️  OpenAI TTS недоступен, использую pyttsx3...")
        ui_print("⚠️ OpenAI TTS недоступен. Пробую локальную озвучку...")
        if speak_with_local_chunks(fallback_engine, tts_chunks):
            return
        ui_print("⚠️ Озвучка недоступна. Ответ показан только текстом.")
        return

    if speak_with_local_chunks(fallback_engine, tts_chunks):
        return

    if use_openai_tts and client:
        logger.warning("⚠️  pyttsx3 недоступен, переключаюсь на OpenAI TTS...")
        ui_print("⚠️ Локальная озвучка недоступна. Переключаюсь на OpenAI TTS...")
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
    engine = None
    tts_service = get_configured_tts_service()

    logger.info(f"\n{'='*70}")
    logger.info(f"🎓 СЕАНС ОБУЧЕНИЯ ЗАПУЩЕН (уровень: {level})")
    logger.info(f"{'='*70}\n")

    ui_header(f"🎓 СЕАНС ОБУЧЕНИЯ (уровень: {level})")
    ui_print("Что делать:")
    ui_print("  • Говорите по-английски в микрофон, когда увидите подсказку 'Говорите...'.")
    ui_print(f"  • Когда закончите фразу, просто помолчите {PAUSE_THRESHOLD} сек.")
    ui_print(
        f"  • Озвучивание: {get_tts_service_label(tts_service)}, "
        f"скорость слов {TTS_SPEED:.2f}; паузы между chunk-ами настраиваются отдельно."
    )
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
            audio_file = record_audio(PAUSE_THRESHOLD, PHRASE_TIME_LIMIT, SESSION_IDLE_TIMEOUT)
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
            teacher_response = get_teacher_response(student_text, level, session.conversation)

            if not teacher_response:
                logger.warning("⚠️  Не удалось получить ответ. Попробуйте снова.")
                continue

            teacher_text, tts_chunks = get_display_text_and_chunks(teacher_response, level)

            # Добавить в историю
            session.add_exchange(student_text, teacher_response)

            # ЭТАП 4: Озвучивание
            ui_print("\n📝 Шаг 4/4: Озвучивание ответа")
            speak_response(engine, teacher_response, enable_audio, level=level)

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
