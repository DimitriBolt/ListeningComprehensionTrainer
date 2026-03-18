#!/usr/bin/env python3
"""
ЭТАП 3: LLM интеграция (ChatGPT/Gemini)
Обработка распознанного текста через LLM для получения ответа учителя

Использование:
    python src/stage3_llm.py "Hello, teacher."                    # Обработать текст напрямую
    python src/stage3_llm.py --transcript transcripts/recording_*.json  # Обработать транскрипцию
    python src/stage3_llm.py --batch                               # Обработать все транскрипции
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

# Получить API ключи
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
LANGUAGE_LEVEL = os.getenv("LANGUAGE_LEVEL", "B1")  # A2, B1, B2, C1
LLM_MODEL = os.getenv("LLM_MODEL", "openai")  # openai или gemini

if not OPENAI_API_KEY and LLM_MODEL == "openai":
    print("❌ ОШИБКА: OPENAI_API_KEY не найден в .env файле")
    sys.exit(1)

# Инициализировать OpenAI клиент
client = create_openai_client(OPENAI_API_KEY)

# Пути
PROJECT_ROOT = Path(__file__).parent.parent
TRANSCRIPTS_DIR = PROJECT_ROOT / "transcripts"
RESPONSES_DIR = PROJECT_ROOT / "responses"
LOGS_DIR = PROJECT_ROOT / "logs"

# Создать папки если их нет
RESPONSES_DIR.mkdir(exist_ok=True)
LOGS_DIR.mkdir(exist_ok=True)

# ===== ЛОГИРОВАНИЕ =====
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)

# Обработчик файла логов
log_file = LOGS_DIR / "stage3_llm.log"
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

def get_system_prompt(level: str = "B1") -> str:
    """Получить системный промпт для указанного уровня"""
    return SYSTEM_PROMPTS.get(level, SYSTEM_PROMPTS["B1"])


def query_llm(student_input: str, language_level: str = "B1") -> dict:
    """
    Отправить вопрос студента в LLM и получить ответ учителя

    Args:
        student_input: Текст от студента (распознанная речь)
        language_level: Уровень языка (A2, B1, B2)

    Returns:
        dict с результатами
    """
    if not client:
        logger.error("❌ OpenAI клиент не инициализирован")
        return None

    system_prompt = get_system_prompt(language_level)

    logger.info(f"🎓 Отправляю запрос к LLM (уровень: {language_level})")
    logger.info(f"📝 Вопрос студента: {student_input}")

    try:
        # Отправить запрос к ChatGPT
        response = client.chat.completions.create(
            model="gpt-4o-mini",  # Экономный и быстрый вариант
            messages=[
                {
                    "role": "system",
                    "content": system_prompt
                },
                {
                    "role": "user",
                    "content": student_input
                }
            ],
            temperature=0.7,  # Баланс между творчеством и консистентностью
            max_tokens=500,
        )

        teacher_response = response.choices[0].message.content

        logger.info("✅ Ответ получен от LLM")

        # Результаты
        result = {
            "student_input": student_input,
            "teacher_response": teacher_response,
            "language_level": language_level,
            "model": "gpt-4o-mini",
            "timestamp": datetime.now().isoformat(),
            "tokens_used": {
                "prompt": response.usage.prompt_tokens,
                "completion": response.usage.completion_tokens,
                "total": response.usage.total_tokens,
            }
        }

        logger.info(f"🎓 Ответ учителя: {teacher_response}")
        logger.info(f"📊 Токенов использовано: {result['tokens_used']['total']}")

        return result

    except Exception as e:
        logger.error(f"❌ Ошибка при обращении к LLM: {str(e)}")
        return None


def save_response(result: dict, output_format: str = "json") -> str:
    """
    Сохранить ответ LLM в файл

    Args:
        result: Результат от query_llm()
        output_format: Формат сохранения (json, txt, html)

    Returns:
        Путь к сохранённому файлу
    """
    if not result:
        return None

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    if output_format == "json":
        output_file = RESPONSES_DIR / f"response_{timestamp}.json"
        with open(output_file, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        logger.info(f"💾 JSON сохранён: {output_file}")

    elif output_format == "txt":
        output_file = RESPONSES_DIR / f"response_{timestamp}.txt"
        with open(output_file, "w", encoding="utf-8") as f:
            f.write("=" * 70 + "\n")
            f.write("CONVERSATION TRANSCRIPT\n")
            f.write("=" * 70 + "\n\n")
            f.write(f"Student (Level {result['language_level']}): {result['student_input']}\n\n")
            f.write(f"Teacher: {result['teacher_response']}\n\n")
            f.write("=" * 70 + "\n")
            f.write(f"Timestamp: {result['timestamp']}\n")
            f.write(f"Tokens: {result['tokens_used']['total']}\n")
        logger.info(f"💾 TXT сохранён: {output_file}")

    elif output_format == "html":
        output_file = RESPONSES_DIR / f"response_{timestamp}.html"
        html_content = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>English Lesson</title>
    <style>
        body {{ font-family: Arial, sans-serif; max-width: 800px; margin: 40px auto; padding: 20px; }}
        .container {{ background: #f5f5f5; padding: 20px; border-radius: 8px; }}
        .student {{ background: #e3f2fd; padding: 15px; margin: 10px 0; border-left: 4px solid #2196F3; }}
        .teacher {{ background: #f3e5f5; padding: 15px; margin: 10px 0; border-left: 4px solid #9c27b0; }}
        .label {{ font-weight: bold; color: #666; }}
        .timestamp {{ color: #999; font-size: 0.9em; margin-top: 20px; }}
    </style>
</head>
<body>
    <div class="container">
        <h1>🎓 English Lesson</h1>
        <p><strong>Level:</strong> {result['language_level']}</p>
        
        <div class="student">
            <div class="label">👤 Student:</div>
            {result['student_input']}
        </div>
        
        <div class="teacher">
            <div class="label">👨‍🏫 Teacher:</div>
            {result['teacher_response']}
        </div>
        
        <div class="timestamp">
            <p>Timestamp: {result['timestamp']}</p>
            <p>Model: {result['model']}</p>
            <p>Tokens: {result['tokens_used']['total']}</p>
        </div>
    </div>
</body>
</html>"""
        with open(output_file, "w", encoding="utf-8") as f:
            f.write(html_content)
        logger.info(f"💾 HTML сохранён: {output_file}")

    return str(output_file)


def load_transcript(transcript_file: str) -> str:
    """Загрузить текст из файла транскрипции"""
    try:
        transcript_path = Path(transcript_file)

        if transcript_path.suffix == ".json":
            with open(transcript_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                return data.get("text", "")
        elif transcript_path.suffix == ".txt":
            with open(transcript_path, "r", encoding="utf-8") as f:
                return f.read().strip()
    except Exception as e:
        logger.error(f"❌ Ошибка при загрузке транскрипции: {str(e)}")

    return None


def process_transcript(transcript_file: str, language_level: str = "B1") -> dict:
    """
    Обработать файл транскрипции через LLM

    Args:
        transcript_file: Путь к файлу транскрипции
        language_level: Уровень языка студента

    Returns:
        Результат от query_llm()
    """
    student_text = load_transcript(transcript_file)

    if not student_text:
        logger.error(f"❌ Не удалось загрузить текст из {transcript_file}")
        return None

    logger.info(f"📄 Загруженный текст: {student_text}")
    result = query_llm(student_text, language_level)

    return result


def process_batch(language_level: str = "B1") -> list:
    """
    Обработать все файлы транскрипций в режиме пакетной обработки

    Args:
        language_level: Уровень языка студента

    Returns:
        Список с результатами обработки
    """
    if not TRANSCRIPTS_DIR.exists():
        logger.error(f"❌ Папка {TRANSCRIPTS_DIR} не найдена")
        return []

    transcript_files = sorted(
        TRANSCRIPTS_DIR.glob("*_transcript.json"),
        key=lambda p: p.stat().st_mtime
    )

    if not transcript_files:
        logger.error("❌ Файлов транскрипций не найдено")
        return []

    logger.info(f"📁 Найдено файлов: {len(transcript_files)}")
    logger.info("=" * 70)

    results = []
    for i, transcript_file in enumerate(transcript_files, 1):
        logger.info(f"\n[{i}/{len(transcript_files)}] Обработка файла...")
        result = process_transcript(str(transcript_file), language_level)
        if result:
            results.append(result)
            save_response(result, output_format="json")
            save_response(result, output_format="txt")
            save_response(result, output_format="html")
        logger.info("-" * 70)

    return results


def print_summary(results: list):
    """Вывести итоговый отчёт"""
    if not results:
        logger.warning("⚠️  Нет успешно обработанных запросов")
        return

    logger.info("=" * 70)
    logger.info("📊 ИТОГОВЫЙ ОТЧЁТ")
    logger.info("=" * 70)
    logger.info(f"✅ Успешно обработано: {len(results)} запросов\n")

    total_tokens = 0
    for i, result in enumerate(results, 1):
        tokens = result['tokens_used']['total']
        total_tokens += tokens
        logger.info(f"{i}. Student: {result['student_input'][:50]}...")
        logger.info(f"   Teacher: {result['teacher_response'][:50]}...")
        logger.info(f"   Tokens: {tokens}\n")

    logger.info("-" * 70)
    logger.info(f"📊 Всего токенов использовано: {total_tokens}")
    estimated_cost = total_tokens * 0.00015 / 1000  # $0.15 за 1M входных токенов
    logger.info(f"💰 Приблизительная стоимость: ${estimated_cost:.4f}\n")


# ===== ГЛАВНАЯ ПРОГРАММА =====

def main():
    """Главная функция"""
    print("\n" + "=" * 70)
    print("🎓 ЭТАП 3: LLM ИНТЕГРАЦИЯ (ChatGPT)")
    print("=" * 70 + "\n")

    # Парсинг аргументов командной строки
    if len(sys.argv) > 1:
        if sys.argv[1] == "--batch":
            # Обработать все транскрипции
            logger.info(f"📁 Режим пакетной обработки (уровень: {LANGUAGE_LEVEL})\n")
            results = process_batch(LANGUAGE_LEVEL)
            print_summary(results)

        elif sys.argv[1] == "--transcript":
            # Обработать конкретную транскрипцию
            if len(sys.argv) < 3:
                logger.error("❌ Укажите путь к файлу транскрипции")
                sys.exit(1)
            transcript_file = sys.argv[2]
            logger.info(f"🎯 Обработка транскрипции: {transcript_file}\n")
            result = process_transcript(transcript_file, LANGUAGE_LEVEL)
            if result:
                save_response(result, output_format="json")
                save_response(result, output_format="txt")
                save_response(result, output_format="html")
                print_summary([result])

        else:
            # Обработать текст напрямую
            student_input = " ".join(sys.argv[1:])
            logger.info(f"💬 Режим прямого ввода текста\n")
            result = query_llm(student_input, LANGUAGE_LEVEL)
            if result:
                save_response(result, output_format="json")
                save_response(result, output_format="txt")
                save_response(result, output_format="html")
                print_summary([result])

    else:
        # По умолчанию - обработать первую транскрипцию
        logger.info("📝 Режим обработки первой транскрипции\n")
        if TRANSCRIPTS_DIR.exists():
            transcript_files = sorted(TRANSCRIPTS_DIR.glob("*_transcript.json"))
            if transcript_files:
                result = process_transcript(str(transcript_files[0]), LANGUAGE_LEVEL)
                if result:
                    save_response(result, output_format="json")
                    save_response(result, output_format="txt")
                    save_response(result, output_format="html")
                    print_summary([result])
            else:
                logger.error("❌ Файлов транскрипций не найдено. Выполните ЭТАП 2 сначала.")
        else:
            logger.error("❌ Папка transcripts/ не найдена")

    print("\n" + "=" * 70)
    print("✅ ЭТАП 3 ЗАВЕРШЁН")
    print("=" * 70 + "\n")


if __name__ == "__main__":
    main()
