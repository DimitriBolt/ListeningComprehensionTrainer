#!/usr/bin/env python3
"""
ЭТАП 4: Text-to-Speech (TTS)
Озвучивание ответов учителя с использованием pyttsx3

Использование:
    python src/stage4_tts.py                    # Озвучить последний ответ
    python src/stage4_tts.py --batch            # Озвучить все ответы
    python src/stage4_tts.py "Hello, world!"    # Озвучить текст напрямую
    python src/stage4_tts.py --speed 0.8        # Установить скорость (0.5-1.0)
"""

import os
import sys
import logging
from pathlib import Path
from datetime import datetime
import json
import pyttsx3
from dotenv import load_dotenv

# ===== КОНФИГУРАЦИЯ =====
# Загрузить переменные окружения из .env
load_dotenv()

# Параметры TTS
TTS_SPEED = float(os.getenv("TTS_SPEED", "0.8"))  # Скорость речи (0.5-1.0)
TTS_VOLUME = float(os.getenv("TTS_VOLUME", "0.9"))  # Громкость (0.0-1.0)
TTS_VOICE_GENDER = os.getenv("TTS_VOICE_GENDER", "female")  # male или female
TTS_LANGUAGE = os.getenv("TTS_LANGUAGE", "english")  # Язык

# Пути
PROJECT_ROOT = Path(__file__).parent.parent
RESPONSES_DIR = PROJECT_ROOT / "responses"
AUDIO_OUTPUT_DIR = PROJECT_ROOT / "audio_responses"
LOGS_DIR = PROJECT_ROOT / "logs"

# Создать папки если их нет
AUDIO_OUTPUT_DIR.mkdir(exist_ok=True)
LOGS_DIR.mkdir(exist_ok=True)

# ===== ЛОГИРОВАНИЕ =====
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)

# Обработчик файла логов
log_file = LOGS_DIR / "stage4_tts.log"
file_handler = logging.FileHandler(log_file, encoding='utf-8')
file_handler.setLevel(logging.DEBUG)

# Обработчик консоли
console_handler = logging.StreamHandler()
console_handler.setLevel(logging.INFO)

# Формат логов
formatter = logging.Formatter(
    '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
file_handler.setFormatter(formatter)
console_handler.setFormatter(formatter)

logger.addHandler(file_handler)
logger.addHandler(console_handler)

# ===== ФУНКЦИИ =====

def initialize_engine(speed: float = 0.8, volume: float = 0.9) -> pyttsx3.Engine:
    """
    Инициализировать pyttsx3 движок с параметрами

    Args:
        speed: Скорость речи (0.5-1.0, где 0.8 = медленная и четкая)
        volume: Громкость (0.0-1.0)

    Returns:
        pyttsx3.Engine объект
    """
    logger.info("🔧 Инициализирую TTS движок...")

    engine = pyttsx3.init()

    # Установить скорость (чем ниже, тем медленнее)
    # Оптимальная скорость: 100-150 слов в минуту для понятности
    engine.setProperty('rate', int(120 * speed))  # Уменьшена базовая скорость для лучшей четкости

    # Установить громкость (максимум для лучшей слышимости)
    engine.setProperty('volume', max(0.9, volume))

    # Установить голос (если доступно)
    voices = engine.getProperty('voices')
    logger.info(f"📢 Доступные голоса: {len(voices)}")

    # Выбрать голос
    if voices:
        # По умолчанию первый голос (обычно мужской)
        if TTS_VOICE_GENDER.lower() == "female" and len(voices) > 1:
            # Выбрать женский голос (обычно второй) - обычно звучит лучше
            engine.setProperty('voice', voices[1].id)
            logger.info("👩 Выбран женский голос (обычно лучше качество)")
        else:
            engine.setProperty('voice', voices[0].id)
            logger.info("👨 Выбран мужской голос")

    logger.info(f"⚙️  Скорость: {speed} (базовая 120 WPM)")
    logger.info(f"🔊 Громкость: {volume} (максимум для лучшей слышимости)")

    return engine


def speak_text(engine: pyttsx3.Engine, text: str, save_audio: bool = True, output_file: str = None) -> str:
    """
    Озвучить текст и сохранить в файл

    Args:
        engine: pyttsx3.Engine объект
        text: Текст для озвучивания
        save_audio: Сохранить ли в WAV файл
        output_file: Путь для сохранения (если None, генерируется автоматически)

    Returns:
        Путь к сохранённому файлу (если save_audio=True)
    """
    logger.info(f"🎙️  Озвучиваю текст: {text[:50]}...")

    if not output_file and save_audio:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        output_file = str(AUDIO_OUTPUT_DIR / f"response_{timestamp}.wav")

    if save_audio and output_file:
        # Сохранить в файл
        engine.save_to_file(text, output_file)
        engine.runAndWait()
        logger.info(f"💾 Аудио сохранено: {output_file}")
        return output_file
    else:
        # Просто воспроизвести
        engine.say(text)
        engine.runAndWait()
        logger.info("✅ Текст озвучен")
        return None


def process_response_file(response_file: str, engine: pyttsx3.Engine) -> dict:
    """
    Загрузить ответ из JSON файла и озвучить его

    Args:
        response_file: Путь к JSON файлу с ответом
        engine: pyttsx3.Engine объект

    Returns:
        dict с результатами
    """
    try:
        with open(response_file, "r", encoding="utf-8") as f:
            response_data = json.load(f)

        teacher_text = response_data.get("teacher_response", "")
        student_text = response_data.get("student_input", "")

        if not teacher_text:
            logger.error(f"❌ Не найден текст ответа в {response_file}")
            return None

        logger.info(f"📄 Загруженный ответ: {teacher_text[:50]}...")

        # Генерировать имя выходного файла на основе исходного
        input_name = Path(response_file).stem
        output_file = str(AUDIO_OUTPUT_DIR / f"{input_name}_audio.wav")

        # Озвучить
        audio_file = speak_text(engine, teacher_text, save_audio=True, output_file=output_file)

        result = {
            "response_file": response_file,
            "student_input": student_text,
            "teacher_response": teacher_text,
            "audio_file": audio_file,
            "timestamp": datetime.now().isoformat(),
        }

        return result

    except Exception as e:
        logger.error(f"❌ Ошибка при обработке {response_file}: {str(e)}")
        return None


def process_batch_responses(engine: pyttsx3.Engine) -> list:
    """
    Озвучить все ответы из папки responses/

    Args:
        engine: pyttsx3.Engine объект

    Returns:
        Список с результатами
    """
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
        result = process_response_file(str(response_file), engine)
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
    logger.info("📊 ИТОГОВЫЙ ОТЧЁТ TTS")
    logger.info("=" * 70)
    logger.info(f"✅ Успешно озвучено: {len(results)} ответов\n")

    for i, result in enumerate(results, 1):
        logger.info(f"{i}. Студент: {result['student_input'][:40]}...")
        logger.info(f"   Учитель: {result['teacher_response'][:40]}...")
        logger.info(f"   Аудио: {Path(result['audio_file']).name}\n")

    logger.info("-" * 70)
    logger.info(f"📊 Всего аудиофайлов создано: {len(results)}")
    logger.info(f"📂 Папка с аудио: {AUDIO_OUTPUT_DIR}\n")


def list_voices(engine: pyttsx3.Engine):
    """Вывести список доступных голосов"""
    voices = engine.getProperty('voices')
    logger.info("=" * 70)
    logger.info("📢 ДОСТУПНЫЕ ГОЛОСА:")
    logger.info("=" * 70)
    for i, voice in enumerate(voices):
        logger.info(f"{i+1}. {voice.name}")
        logger.info(f"   ID: {voice.id}")
        logger.info(f"   Пол: {voice.gender if hasattr(voice, 'gender') else 'unknown'}\n")


# ===== ГЛАВНАЯ ПРОГРАММА =====

def main():
    """Главная функция"""
    print("\n" + "=" * 70)
    print("🎙️  ЭТАП 4: TEXT-TO-SPEECH (TTS)")
    print("=" * 70 + "\n")

    # Инициализировать движок
    engine = initialize_engine(speed=TTS_SPEED, volume=TTS_VOLUME)

    # Парсинг аргументов командной строки
    if len(sys.argv) > 1:
        if sys.argv[1] == "--batch":
            # Озвучить все ответы
            logger.info("📁 Режим пакетной обработки\n")
            results = process_batch_responses(engine)
            print_summary(results)

        elif sys.argv[1] == "--list-voices":
            # Показать доступные голоса
            list_voices(engine)

        elif sys.argv[1] == "--speed" and len(sys.argv) > 2:
            # Озвучить с пользовательской скоростью
            try:
                speed = float(sys.argv[2])
                if not 0.5 <= speed <= 1.0:
                    logger.error("❌ Скорость должна быть между 0.5 и 1.0")
                    sys.exit(1)
                engine = initialize_engine(speed=speed, volume=TTS_VOLUME)
                text = " ".join(sys.argv[3:]) if len(sys.argv) > 3 else "Hello, how are you today?"
                speak_text(engine, text, save_audio=False)
            except ValueError:
                logger.error("❌ Скорость должна быть числом")
                sys.exit(1)

        else:
            # Озвучить текст напрямую
            text = " ".join(sys.argv[1:])
            logger.info(f"💬 Режим прямого ввода текста\n")
            speak_text(engine, text, save_audio=True)

    else:
        # По умолчанию - озвучить последний ответ
        logger.info("📝 Режим обработки последнего ответа\n")
        if RESPONSES_DIR.exists():
            response_files = sorted(RESPONSES_DIR.glob("response_*.json"))
            if response_files:
                result = process_response_file(str(response_files[-1]), engine)
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

