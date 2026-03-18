#!/usr/bin/env python3
"""
ЭТАП 4: Text-to-Speech (TTS) - УЛУЧШЕННАЯ ВЕРСИЯ
Озвучивание ответов учителя с использованием OpenAI TTS (премиум качество)

Поддерживает несколько движков TTS:
  1. OpenAI TTS (рекомендуется) - лучшее качество
  2. pyttsx3 (fallback) - быстро, офлайн
  3. Google TTS (опционально) - хорошее качество

Использование:
    python src/stage4_tts_premium.py                    # Озвучить последний ответ
    python src/stage4_tts_premium.py --batch            # Озвучить все ответы
    python src/stage4_tts_premium.py "Hello, world!"    # Озвучить текст напрямую
    python src/stage4_tts_premium.py --list-engines     # Показать доступные движки
"""

import os
import sys
import logging
from pathlib import Path
from datetime import datetime
import json
from dotenv import load_dotenv
import pyttsx3

try:
    from src.openai_compat import create_openai_client
except ImportError:
    from openai_compat import create_openai_client

# ===== КОНФИГУРАЦИЯ =====
load_dotenv()

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
TTS_ENGINE = os.getenv("TTS_ENGINE", "openai")  # openai, pyttsx3, google
TTS_SPEED = float(os.getenv("TTS_SPEED", "0.8"))
TTS_VOLUME = float(os.getenv("TTS_VOLUME", "0.9"))
TTS_VOICE_GENDER = os.getenv("TTS_VOICE_GENDER", "female")
OPENAI_VOICE = os.getenv("OPENAI_VOICE", "nova")  # alloy, echo, fable, nova, onyx, shimmer

# Инициализация
client = create_openai_client(OPENAI_API_KEY)

# Пути
PROJECT_ROOT = Path(__file__).parent.parent
RESPONSES_DIR = PROJECT_ROOT / "responses"
AUDIO_OUTPUT_DIR = PROJECT_ROOT / "audio_responses"
LOGS_DIR = PROJECT_ROOT / "logs"

RESPONSES_DIR.mkdir(exist_ok=True)
AUDIO_OUTPUT_DIR.mkdir(exist_ok=True)
LOGS_DIR.mkdir(exist_ok=True)

# ===== ЛОГИРОВАНИЕ =====
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)

log_file = LOGS_DIR / "stage4_tts_premium.log"
file_handler = logging.FileHandler(log_file, encoding='utf-8')
file_handler.setLevel(logging.DEBUG)

console_handler = logging.StreamHandler()
console_handler.setLevel(logging.INFO)

formatter = logging.Formatter(
    '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
file_handler.setFormatter(formatter)
console_handler.setFormatter(formatter)

logger.addHandler(file_handler)
logger.addHandler(console_handler)

# ===== OPENAI TTS =====

def speak_with_openai_tts(text: str, output_file: str = None) -> str:
    """
    Озвучить текст с использованием OpenAI TTS (ПРЕМИУМ качество)

    Args:
        text: Текст для озвучивания
        output_file: Путь для сохранения (если None, генерируется автоматически)

    Returns:
        Путь к сохранённому файлу
    """
    if not client:
        logger.error("❌ OpenAI клиент не инициализирован")
        return None

    logger.info("🎤 Озвучиваю текст через OpenAI TTS (премиум качество)...")
    logger.info(f"   Голос: {OPENAI_VOICE}")
    logger.info(f"   Текст: {text[:50]}...")

    try:
        # Генерировать речь
        response = client.audio.speech.create(
            model="gpt-4o-mini-tts",
            voice=OPENAI_VOICE,  # Выбор голоса: alloy, echo, fable, nova, onyx, shimmer
            input=text,
            response_format="mp3",
            extra_body={
                "instructions": "Speak slowly and clearly for an English learner."
            },
            speed=TTS_SPEED  # 0.25 - 4.0 (1.0 = нормальная скорость)
        )

        # Сохранить в файл
        if not output_file:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            output_file = str(AUDIO_OUTPUT_DIR / f"response_openai_{timestamp}.mp3")

        response.stream_to_file(output_file)
        logger.info(f"✅ Озвучено (OpenAI TTS): {Path(output_file).name}")
        logger.info(f"📦 Размер: {Path(output_file).stat().st_size / 1024:.1f} KB")

        return output_file

    except Exception as e:
        logger.error(f"❌ Ошибка OpenAI TTS: {e}")
        logger.warning("⚠️  Пытаюсь использовать pyttsx3 как fallback...")
        return speak_with_pyttsx3(text, output_file)


# ===== PYTTSX3 TTS (FALLBACK) =====

def initialize_pyttsx3_engine(speed: float = 0.8, volume: float = 0.9) -> pyttsx3.Engine:
    """Инициализировать pyttsx3 движок с оптимальными параметрами"""
    engine = pyttsx3.init()
    engine.setProperty('rate', int(120 * speed))
    engine.setProperty('volume', max(0.9, volume))

    voices = engine.getProperty('voices')
    if voices and len(voices) > 1:
        engine.setProperty('voice', voices[1].id)  # Женский голос

    return engine


def speak_with_pyttsx3(text: str, output_file: str = None) -> str:
    """
    Озвучить текст с использованием pyttsx3 (fallback)

    Args:
        text: Текст для озвучивания
        output_file: Путь для сохранения (если None, генерируется автоматически)

    Returns:
        Путь к сохранённому файлу
    """
    logger.info("🎤 Озвучиваю текст через pyttsx3 (fallback)...")

    try:
        engine = initialize_pyttsx3_engine(TTS_SPEED, TTS_VOLUME)

        if not output_file:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            output_file = str(AUDIO_OUTPUT_DIR / f"response_pyttsx3_{timestamp}.wav")

        logger.info(f"   Голос: pyttsx3")
        logger.info(f"   Текст: {text[:50]}...")

        engine.save_to_file(text, output_file)
        engine.runAndWait()

        logger.info(f"✅ Озвучено (pyttsx3): {Path(output_file).name}")
        return output_file

    except Exception as e:
        logger.error(f"❌ Ошибка pyttsx3: {e}")
        return None


# ===== ОСНОВНАЯ ФУНКЦИЯ =====

def speak_text(text: str, engine: str = None, output_file: str = None) -> str:
    """
    Озвучить текст выбранным движком

    Args:
        text: Текст для озвучивания
        engine: Движок (openai, pyttsx3)
        output_file: Путь для сохранения

    Returns:
        Путь к сохранённому файлу
    """
    engine = engine or TTS_ENGINE

    logger.info(f"🎙️  Озвучивание текста ({engine})...")

    if engine == "openai":
        return speak_with_openai_tts(text, output_file)
    elif engine == "pyttsx3":
        return speak_with_pyttsx3(text, output_file)
    else:
        logger.error(f"❌ Неизвестный движок: {engine}")
        return None


def process_response_file(response_file: str, engine: str = None) -> dict:
    """Загрузить ответ из JSON файла и озвучить его"""
    try:
        with open(response_file, "r", encoding="utf-8") as f:
            response_data = json.load(f)

        teacher_text = response_data.get("teacher_response", "")
        student_text = response_data.get("student_input", "")

        if not teacher_text:
            logger.error(f"❌ Не найден текст ответа в {response_file}")
            return None

        logger.info(f"📄 Загруженный ответ: {teacher_text[:50]}...")

        # Генерировать имя выходного файла
        input_name = Path(response_file).stem
        output_file = str(AUDIO_OUTPUT_DIR / f"{input_name}_audio.mp3")

        # Озвучить
        audio_file = speak_text(teacher_text, engine=engine, output_file=output_file)

        result = {
            "response_file": response_file,
            "student_input": student_text,
            "teacher_response": teacher_text,
            "audio_file": audio_file,
            "engine": engine or TTS_ENGINE,
            "timestamp": datetime.now().isoformat(),
        }

        return result

    except Exception as e:
        logger.error(f"❌ Ошибка при обработке {response_file}: {str(e)}")
        return None


def process_batch_responses(engine: str = None) -> list:
    """Озвучить все ответы из папки responses/"""
    if not RESPONSES_DIR.exists():
        logger.error(f"❌ Папка {RESPONSES_DIR} не найдена")
        return []

    response_files = sorted(
        RESPONSES_DIR.glob("response_*.json"),
        key=lambda p: p.stat().st_mtime
    )

    if not response_files:
        logger.error("❌ Файлов ответов не найдено")
        return []

    logger.info(f"📁 Найдено файлов: {len(response_files)}")
    logger.info("=" * 70)

    results = []
    for i, response_file in enumerate(response_files, 1):
        logger.info(f"\n[{i}/{len(response_files)}] Озвучивание ответа...")
        result = process_response_file(str(response_file), engine=engine)
        if result:
            results.append(result)
        logger.info("-" * 70)

    return results


def print_summary(results: list):
    """Вывести итоговый отчёт"""
    if not results:
        logger.warning("⚠️  Нет успешно обработанных ответов")
        return

    logger.info("=" * 70)
    logger.info("📊 ИТОГОВЫЙ ОТЧЁТ TTS PREMIUM")
    logger.info("=" * 70)
    logger.info(f"✅ Успешно озвучено: {len(results)} ответов\n")

    for i, result in enumerate(results, 1):
        logger.info(f"{i}. Студент: {result['student_input'][:40]}...")
        logger.info(f"   Учитель: {result['teacher_response'][:40]}...")
        logger.info(f"   Движок: {result['engine']}")
        logger.info(f"   Аудио: {Path(result['audio_file']).name}\n")

    logger.info("-" * 70)
    logger.info(f"📊 Всего аудиофайлов создано: {len(results)}")
    logger.info(f"📂 Папка с аудио: {AUDIO_OUTPUT_DIR}\n")


def list_available_engines():
    """Показать доступные движки"""
    logger.info("=" * 70)
    logger.info("📢 ДОСТУПНЫЕ TTS ДВИЖКИ:")
    logger.info("=" * 70)
    logger.info("\n🥇 OpenAI TTS (рекомендуется):")
    logger.info("   • Качество: 🌟🌟🌟🌟🌟 (звучит как человек!)")
    logger.info("   • Голоса: alloy, echo, fable, nova, onyx, shimmer")
    logger.info("   • Текущий голос: " + OPENAI_VOICE)
    logger.info("   • Стоимость: $0.015 за 1000 символов")
    logger.info("   • Скорость: 0.25-4.0 (текущая: " + str(TTS_SPEED) + ")")

    logger.info("\n🥈 pyttsx3 (fallback/офлайн):")
    logger.info("   • Качество: 🌟🌟🌟 (механический, но быстро)")
    logger.info("   • Голоса: системные")
    logger.info("   • Стоимость: Бесплатно")
    logger.info("   • Скорость: Мгновенно")
    logger.info("\n" + "=" * 70 + "\n")


# ===== ГЛАВНАЯ ПРОГРАММА =====

def main():
    """Главная функция"""
    print("\n" + "=" * 70)
    print("🎤 ЭТАП 4: TEXT-TO-SPEECH PREMIUM (OpenAI TTS)")
    print("=" * 70 + "\n")

    # Парсинг аргументов
    if len(sys.argv) > 1:
        if sys.argv[1] == "--batch":
            logger.info(f"📁 Режим пакетной обработки (движок: {TTS_ENGINE})\n")
            results = process_batch_responses(engine=TTS_ENGINE)
            print_summary(results)

        elif sys.argv[1] == "--list-engines":
            list_available_engines()

        elif sys.argv[1] == "--openai":
            logger.info("🎤 Использую OpenAI TTS для всех ответов\n")
            results = process_batch_responses(engine="openai")
            print_summary(results)

        elif sys.argv[1] == "--pyttsx3":
            logger.info("🎤 Использую pyttsx3 для всех ответов\n")
            results = process_batch_responses(engine="pyttsx3")
            print_summary(results)

        else:
            # Озвучить текст напрямую
            text = " ".join(sys.argv[1:])
            logger.info(f"💬 Озвучивание текста напрямую\n")
            speak_text(text, engine=TTS_ENGINE)

    else:
        # По умолчанию - озвучить последний ответ
        logger.info("📝 Режим обработки последнего ответа\n")
        if RESPONSES_DIR.exists():
            response_files = sorted(RESPONSES_DIR.glob("response_*.json"))
            if response_files:
                result = process_response_file(str(response_files[-1]), engine=TTS_ENGINE)
                if result:
                    print_summary([result])
            else:
                logger.error("❌ Файлов ответов не найдено. Выполните ЭТАП 3 сначала.")
        else:
            logger.error("❌ Папка responses/ не найдена")

    print("\n" + "=" * 70)
    print("✅ ЭТАП 4 ЗАВЕРШЁН")
    print("=" * 70 + "\n")


if __name__ == "__main__":
    main()
