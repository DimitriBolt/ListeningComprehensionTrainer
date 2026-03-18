"""
Конфигурация приложения Listening Comprehension Trainer
"""
import os
from dotenv import load_dotenv

# Загружаем переменные окружения
load_dotenv()

# ===== API Keys =====
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY", "")

# ===== Микрофон =====
PAUSE_THRESHOLD = float(os.getenv("PAUSE_THRESHOLD", 2.5))  # секунды
PHRASE_TIME_LIMIT = int(os.getenv("PHRASE_TIME_LIMIT", 30))  # максимум секунд на фразу
SAMPLE_RATE = 16000  # Hz

# ===== TTS (Text-to-Speech) =====
TTS_SPEED = float(os.getenv("TTS_SPEED", 0.8))  # 0.5-1.0
TTS_SERVICE = os.getenv("TTS_SERVICE", "pyttsx3")  # pyttsx3, openai, elevenlabs

# ===== LLM (Language Model) =====
LLM_MODEL = os.getenv("LLM_MODEL", "gemini")  # gemini или openai
LANGUAGE_LEVEL = os.getenv("LANGUAGE_LEVEL", "B1")  # A2 или B1

# ===== Системный промпт учителя =====
TEACHER_PROMPT = """Ты — терпеливый преподаватель английского языка. 
Твоя цель — тренировать listening comprehension пользователя с уровнем {level}.
Используй только простую лексику и короткие предложения.
Избегай сложных идиом и фразовых глаголов.
Отвечай не более чем 2-3 предложениями.
Если пользователь делает ошибку, мягко исправь его с объяснением.
Поддерживай разговор, задавай вопросы для практики."""

# ===== API Сервер =====
API_HOST = os.getenv("API_HOST", "127.0.0.1")
API_PORT = int(os.getenv("API_PORT", 8000))
API_DEBUG = os.getenv("API_DEBUG", "true").lower() == "true"

# ===== Пути файлов =====
PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))
SRC_DIR = os.path.join(PROJECT_ROOT, "src")
AUDIO_DIR = os.path.join(PROJECT_ROOT, "audio_files")
LOGS_DIR = os.path.join(PROJECT_ROOT, "logs")

# Создаем директории если их нет
os.makedirs(AUDIO_DIR, exist_ok=True)
os.makedirs(LOGS_DIR, exist_ok=True)

if __name__ == "__main__":
    print(f"Config loaded successfully!")
    print(f"PAUSE_THRESHOLD: {PAUSE_THRESHOLD}s")
    print(f"TTS_SERVICE: {TTS_SERVICE}")
    print(f"LLM_MODEL: {LLM_MODEL}")

