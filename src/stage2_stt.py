#!/usr/bin/env python3
"""
ЭТАП 2: Speech-to-Text (Whisper)
Преобразование аудиофайлов в текст с использованием OpenAI Whisper API

Использование:
    python src/stage2_stt.py                    # Обработать последний аудиофайл
    python src/stage2_stt.py audio_files/recording_*.wav  # Обработать конкретный файл
    python src/stage2_stt.py --batch            # Обработать все файлы в папке
"""

import os
import sys
import logging
from pathlib import Path
from datetime import datetime
import json
from dotenv import load_dotenv

try:
    from src.openai_compat import create_openai_client
except ImportError:
    from openai_compat import create_openai_client

# ===== КОНФИГУРАЦИЯ =====
# Загрузить переменные окружения из .env
load_dotenv()

# Получить API ключ
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
if not OPENAI_API_KEY:
    print("❌ ОШИБКА: OPENAI_API_KEY не найден в .env файле")
    print("Добавьте следующую строку в .env:")
    print("OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxx")
    sys.exit(1)

# Инициализировать OpenAI клиент
client = create_openai_client(OPENAI_API_KEY)

# Пути
PROJECT_ROOT = Path(__file__).parent.parent
AUDIO_DIR = PROJECT_ROOT / "audio_files"
TRANSCRIPTS_DIR = PROJECT_ROOT / "transcripts"
LOGS_DIR = PROJECT_ROOT / "logs"

# Создать папки если их нет
TRANSCRIPTS_DIR.mkdir(exist_ok=True)
LOGS_DIR.mkdir(exist_ok=True)

# ===== ЛОГИРОВАНИЕ =====
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)

# Обработчик файла логов
log_file = LOGS_DIR / "stage2_stt.log"
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

def transcribe_audio(audio_file_path: str, language: str = "en") -> dict:
    """
    Преобразовать аудиофайл в текст с использованием OpenAI Whisper API

    Args:
        audio_file_path: Путь к аудиофайлу
        language: Язык (en, ru, auto и т.д.)

    Returns:
        dict с результатами транскрипции
    """
    audio_path = Path(audio_file_path)

    if not audio_path.exists():
        logger.error(f"❌ Файл не найден: {audio_path}")
        return None

    logger.info(f"🎙️  Обработка: {audio_path.name}")
    logger.info(f"   Размер: {audio_path.stat().st_size / 1024:.1f} KB")

    try:
        # Открыть аудиофайл
        with open(audio_path, "rb") as audio_file:
            logger.info("⏳ Отправляю файл на сервер OpenAI...")

            # Вызвать Whisper API
            transcript = client.audio.transcriptions.create(
                model="whisper-1",
                file=audio_file,
                language=language if language != "auto" else None,
                response_format="verbose_json"
            )

        logger.info("✅ Транскрипция завершена!")

        # Результаты
        result = {
            "input_file": str(audio_path),
            "filename": audio_path.name,
            "text": transcript.text,
            "duration": getattr(transcript, 'duration', None),
            "language": language,
            "timestamp": datetime.now().isoformat(),
        }

        logger.info(f"📝 Текст: {transcript.text}")
        logger.info(f"⏱️  Длительность: {result['duration']:.2f}s" if result['duration'] else "")

        return result

    except Exception as e:
        logger.error(f"❌ Ошибка при обработке {audio_path.name}: {str(e)}")
        return None


def save_transcript(result: dict, output_format: str = "json") -> str:
    """
    Сохранить результат транскрипции в файл

    Args:
        result: Результат от transcribe_audio()
        output_format: Формат сохранения (json, txt)

    Returns:
        Путь к сохранённому файлу
    """
    if not result:
        return None

    # Имя файла на основе исходного аудиофайла
    input_filename = Path(result['filename']).stem
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    if output_format == "json":
        output_file = TRANSCRIPTS_DIR / f"{input_filename}_transcript.json"
        with open(output_file, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        logger.info(f"💾 JSON сохранён: {output_file}")

    elif output_format == "txt":
        output_file = TRANSCRIPTS_DIR / f"{input_filename}_transcript.txt"
        with open(output_file, "w", encoding="utf-8") as f:
            f.write(result['text'])
        logger.info(f"💾 TXT сохранён: {output_file}")

    return str(output_file)


def get_latest_audio_file() -> str:
    """Получить последний созданный аудиофайл из audio_files/"""
    if not AUDIO_DIR.exists():
        logger.error(f"❌ Папка {AUDIO_DIR} не найдена")
        return None

    audio_files = sorted(
        AUDIO_DIR.glob("recording_*.wav"),
        key=lambda p: p.stat().st_mtime,
        reverse=True
    )

    if not audio_files:
        logger.error("❌ Нет аудиофайлов в папке audio_files/")
        return None

    return str(audio_files[0])


def process_batch(pattern: str = "recording_*.wav") -> list:
    """
    Обработать все файлы, соответствующие паттерну

    Args:
        pattern: Паттерн поиска файлов

    Returns:
        Список с результатами обработки
    """
    if not AUDIO_DIR.exists():
        logger.error(f"❌ Папка {AUDIO_DIR} не найдена")
        return []

    audio_files = sorted(AUDIO_DIR.glob(pattern))

    if not audio_files:
        logger.error(f"❌ Файлов, соответствующих '{pattern}', не найдено")
        return []

    logger.info(f"📁 Найдено файлов: {len(audio_files)}")
    logger.info("=" * 70)

    results = []
    for i, audio_file in enumerate(audio_files, 1):
        logger.info(f"\n[{i}/{len(audio_files)}] Обработка файла...")
        result = transcribe_audio(str(audio_file))
        if result:
            results.append(result)
            save_transcript(result, output_format="json")
            save_transcript(result, output_format="txt")
        logger.info("-" * 70)

    return results


def print_summary(results: list):
    """Вывести итоговый отчёт"""
    if not results:
        logger.warning("⚠️  Нет успешно обработанных файлов")
        return

    logger.info("=" * 70)
    logger.info("📊 ИТОГОВЫЙ ОТЧЁТ")
    logger.info("=" * 70)
    logger.info(f"✅ Успешно обработано: {len(results)} файлов\n")

    for i, result in enumerate(results, 1):
        logger.info(f"{i}. {result['filename']}")
        logger.info(f"   📝 {result['text'][:80]}..." if len(result['text']) > 80 else f"   📝 {result['text']}")
        logger.info("")


# ===== ГЛАВНАЯ ПРОГРАММА =====

def main():
    """Главная функция"""
    print("\n" + "=" * 70)
    print("🎙️  ЭТАП 2: SPEECH-TO-TEXT (WHISPER)")
    print("=" * 70 + "\n")

    # Парсинг аргументов командной строки
    if len(sys.argv) > 1:
        if sys.argv[1] == "--batch":
            # Обработать все файлы
            logger.info("📁 Режим пакетной обработки\n")
            results = process_batch()
            print_summary(results)
        else:
            # Обработать конкретный файл
            audio_file = sys.argv[1]
            logger.info(f"🎯 Обработка конкретного файла: {audio_file}\n")
            result = transcribe_audio(audio_file)
            if result:
                save_transcript(result, output_format="json")
                save_transcript(result, output_format="txt")
                print_summary([result])
    else:
        # Обработать последний аудиофайл
        logger.info("📝 Режим обработки последнего файла\n")
        latest_file = get_latest_audio_file()

        if latest_file:
            result = transcribe_audio(latest_file)
            if result:
                save_transcript(result, output_format="json")
                save_transcript(result, output_format="txt")
                print_summary([result])
        else:
            logger.error("❌ Не удалось найти аудиофайлы для обработки")

    print("\n" + "=" * 70)
    print("✅ ЭТАП 2 ЗАВЕРШЁН")
    print("=" * 70 + "\n")


if __name__ == "__main__":
    main()
