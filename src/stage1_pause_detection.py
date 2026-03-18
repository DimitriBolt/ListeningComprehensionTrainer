"""
Этап 1: Прототип - Тест обнаружения паузы с микрофона
=====================================================

Этот скрипт демонстрирует, как корректно обнаруживать конец речи
пользователя используя параметр pause_threshold.

Как использовать:
1. Запустите: python src/stage1_pause_detection.py
2. Говорите в микрофон
3. После 3 секунд тишины программа завершит запись
4. Аудио сохранится в audio_files/recording_*.wav

Что происходит за сценой:
- Слушаем микрофон с pause_threshold=2.5 сек
- Как только обнаружена пауза длительностью 2.5+ сек, запись завершается
- Сохраняем запись в файл
"""

import speech_recognition as sr
import os
from pathlib import Path
from datetime import datetime
import logging

# Импортируем конфиг
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from config import PAUSE_THRESHOLD, PHRASE_TIME_LIMIT, AUDIO_DIR, LOGS_DIR

# Настройка логирования
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler(os.path.join(LOGS_DIR, 'stage1.log')),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)


def listen_to_microphone():
    """
    Слушает микрофон и записывает аудио до момента обнаружения паузы.

    Возвращает:
        tuple: (audio_data, duration_seconds) или (None, 0) если ошибка
    """
    recognizer = sr.Recognizer()

    # Устанавливаем параметры обнаружения паузы
    recognizer.pause_threshold = PAUSE_THRESHOLD  # 2.5 секунды паузы = конец речи
    recognizer.phrase_time_limit = PHRASE_TIME_LIMIT  # максимум 30 секунд на запись

    logger.info(f"🎙️  Слушаем микрофон...")
    logger.info(f"   Пауза обнаружения: {PAUSE_THRESHOLD}s")
    logger.info(f"   Максимум времени: {PHRASE_TIME_LIMIT}s")
    logger.info("Начинайте говорить! (программа завершит запись после {0}s тишины)".format(PAUSE_THRESHOLD))

    try:
        with sr.Microphone() as source:
            # Адаптируемся к шуму окружения (очень важно!)
            logger.info("⏳ Слушаю шум окружения в течение 1 второй...")
            recognizer.adjust_for_ambient_noise(source, duration=1)
            logger.info("✓ Адаптирована к шуму окружения")

            # Записываем аудио с микрофона
            logger.info("⏺️  Начало записи...")
            audio_data = recognizer.listen(
                source,
                timeout=None,  # Нет абсолютного таймаута
                phrase_time_limit=PHRASE_TIME_LIMIT  # Максимум секунд на одну фразу
            )
            logger.info("✓ Запись завершена")

            # Подсчитываем длительность
            duration = len(audio_data.frame_data) / (audio_data.sample_rate * 2)

            return audio_data, duration

    except sr.RequestError as e:
        logger.error(f"❌ Ошибка запроса микрофона: {e}")
        return None, 0
    except sr.UnknownValueError:
        logger.error("❌ Не удалось распознать речь (звук слишком тихий?)")
        return None, 0
    except Exception as e:
        logger.error(f"❌ Неожиданная ошибка: {e}")
        return None, 0


def save_audio_to_file(audio_data, filename=None):
    """
    Сохраняет аудиоданные в WAV файл.

    Аргументы:
        audio_data: AudioData объект из speech_recognition
        filename: имя файла (если None, генерируется автоматически)

    Возвращает:
        str: путь к сохраненному файлу
    """
    if filename is None:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"recording_{timestamp}.wav"

    filepath = os.path.join(AUDIO_DIR, filename)

    try:
        with open(filepath, "wb") as f:
            f.write(audio_data.get_wav_data())
        logger.info(f"✓ Аудио сохранено: {filepath}")
        return filepath
    except Exception as e:
        logger.error(f"❌ Ошибка при сохранении файла: {e}")
        return None


def check_microphone_available():
    """Проверяет, доступен ли микрофон."""
    try:
        with sr.Microphone() as source:
            logger.info("✓ Микрофон доступен")
            return True
    except Exception as e:
        logger.error(f"❌ Микрофон не доступен: {e}")
        return False


def main():
    """Основной цикл демонстрации."""
    logger.info("="*60)
    logger.info("ЭТАП 1: ТЕСТ ОБНАРУЖЕНИЯ ПАУЗЫ С МИКРОФОНА")
    logger.info("="*60)

    # Проверяем микрофон
    if not check_microphone_available():
        logger.error("Не удалось открыть микрофон. Проверьте подключение.")
        return

    print("\n" + "="*60)
    print("УПРАВЛЕНИЕ:")
    print("  - Нажмите CTRL+C для выхода")
    print("  - Говорите в микрофон")
    print("  - Программа автоматически завершит запись после паузы")
    print("="*60 + "\n")

    try:
        while True:
            print("\n" + "-"*60)

            # Слушаем микрофон
            audio_data, duration = listen_to_microphone()

            if audio_data is None:
                logger.warning("Не удалось записать звук, попробуйте еще раз")
                continue

            # Сохраняем в файл
            filepath = save_audio_to_file(audio_data)

            if filepath:
                logger.info(f"📊 Длительность: {duration:.2f} секунд")
                logger.info(f"📊 Размер файла: {os.path.getsize(filepath) / 1024:.1f} KB")
                print(f"\n✓ Запись сохранена: {filepath}")
                print(f"  Длительность: {duration:.2f} сек")

            # Предлагаем продолжить
            print("\n👉 Начните новую запись или нажмите CTRL+C для выхода")

    except KeyboardInterrupt:
        logger.info("\n\n👋 Завершение программы...")
        print("\n👋 До свидания!")


if __name__ == "__main__":
    main()

