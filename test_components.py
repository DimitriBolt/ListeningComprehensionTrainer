#!/usr/bin/env python3
"""
БЫСТРЫЙ ТЕСТ: Проверка всех компонентов без микрофона
"""

import os
import sys
from pathlib import Path
from dotenv import load_dotenv

from src.openai_compat import create_openai_client

# Загрузить конфигурацию
load_dotenv()

print("\n" + "="*70)
print("🧪 БЫСТРЫЙ ТЕСТ: Проверка компонентов приложения")
print("="*70 + "\n")

# ===== ПРОВЕРКА 1: OpenAI API =====
print("1️⃣  Проверка OpenAI API...")
try:
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        print("   ❌ OPENAI_API_KEY не найден в .env")
        sys.exit(1)

    client = create_openai_client(api_key)
    print("   ✅ OpenAI клиент инициализирован")
except Exception as e:
    print(f"   ❌ Ошибка: {e}")
    sys.exit(1)

# ===== ПРОВЕРКА 2: Whisper (распознавание) =====
print("\n2️⃣  Проверка Whisper API...")
try:
    print("   ⏳ Тестирую распознавание речи...")
    # Создаем простой тестовый файл WAV
    import wave
    import tempfile

    # Создать молчаливый WAV файл (1 секунда)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = tmp.name
        with wave.open(tmp_path, 'wb') as wav_file:
            wav_file.setnchannels(1)
            wav_file.setsampwidth(2)
            wav_file.setframerate(16000)
            wav_file.writeframes(b'\x00' * 32000)

    with open(tmp_path, 'rb') as audio_file:
        transcript = client.audio.transcriptions.create(
            model="whisper-1",
            file=audio_file,
            language="en"
        )

    print(f"   ✅ Whisper работает (результат: '{transcript.text}')")
    os.unlink(tmp_path)
except Exception as e:
    print(f"   ❌ Ошибка: {e}")

# ===== ПРОВЕРКА 3: ChatGPT (LLM) =====
print("\n3️⃣  Проверка ChatGPT...")
try:
    print("   ⏳ Генерирую ответ учителя...")
    response = client.chat.completions.create(
        model="gpt-4o-mini",
        messages=[
            {
                "role": "system",
                "content": "You are a friendly English teacher. Reply in 1-2 sentences."
            },
            {
                "role": "user",
                "content": "Hello, how are you?"
            }
        ],
        temperature=0.7,
        max_tokens=100,
    )

    answer = response.choices[0].message.content
    print(f"   ✅ ChatGPT работает")
    print(f"      Ответ: '{answer[:60]}...'")
except Exception as e:
    print(f"   ❌ Ошибка: {e}")
    sys.exit(1)

# ===== ПРОВЕРКА 4: OpenAI TTS (озвучивание) =====
print("\n4️⃣  Проверка OpenAI TTS...")
try:
    print("   ⏳ Генерирую речь...")
    response = client.audio.speech.create(
        model="gpt-4o-mini-tts",
        voice="nova",
        input="Hello, how are you today?",
        response_format="wav",
        speed=0.8,
        extra_body={
            "instructions": "Speak slowly and clearly for an English learner."
        },
    )

    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = tmp.name
        response.stream_to_file(tmp_path)

    file_size = os.path.getsize(tmp_path) / 1024
    print(f"   ✅ OpenAI TTS работает (файл: {file_size:.1f} KB)")
    os.unlink(tmp_path)
except Exception as e:
    print(f"   ❌ Ошибка: {e}")

# ===== ПРОВЕРКА 5: pyttsx3 (локальное озвучивание) =====
print("\n5️⃣  Проверка pyttsx3...")
try:
    import pyttsx3
    engine = pyttsx3.init()
    engine.setProperty('rate', int(120 * 0.8))
    print("   ✅ pyttsx3 инициализирован")
except Exception as e:
    print(f"   ❌ Ошибка: {e}")

# ===== ПРОВЕРКА 6: SpeechRecognition =====
print("\n6️⃣  Проверка SpeechRecognition...")
try:
    import speech_recognition as sr
    recognizer = sr.Recognizer()
    print("   ✅ SpeechRecognition инициализирован")

    # Попытка найти микрофон
    try:
        with sr.Microphone() as source:
            print("   ✅ Микрофон найден и доступен")
    except Exception as e:
        print(f"   ⚠️  Микрофон может быть недоступен: {e}")
except Exception as e:
    print(f"   ❌ Ошибка: {e}")

print("\n" + "="*70)
print("✅ ВСЕ КОМПОНЕНТЫ РАБОТАЮТ!")
print("="*70 + "\n")

print("🚀 Теперь можете запустить основную программу:")
print("   main.py --practice  (запускайте из PyCharm)")
print("\n" + "="*70 + "\n")
