#!/usr/bin/env python3
"""
ПРОСТОЙ ТЕСТ: Диалог с учителем без микрофона
"""

import os
import sys
from pathlib import Path
from dotenv import load_dotenv
import pyttsx3

from src.openai_compat import create_openai_client

load_dotenv()

# Инициализация
client = create_openai_client(os.getenv("OPENAI_API_KEY"))
engine = pyttsx3.init()
engine.setProperty('rate', int(120 * 0.8))

print("\n" + "="*70)
print("🎓 ПРОСТОЙ ТЕСТ: Диалог с AI учителем")
print("="*70 + "\n")

print("Вы можете общаться с AI учителем без микрофона!")
print("Просто вводите текст. Введите 'exit' для выхода.\n")

system_prompt = """You are a patient and encouraging English teacher for intermediate students (B1 level).
Keep responses short (2-4 sentences). Be friendly and supportive."""

while True:
    try:
        # Ввод от пользователя
        user_input = input("👤 ВЫ: ").strip()

        if user_input.lower() == 'exit':
            print("\n👋 До встречи!\n")
            break

        if not user_input:
            continue

        # Получить ответ от ChatGPT
        print("   ⏳ Учитель думает...")
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_input}
            ],
            temperature=0.7,
            max_tokens=200,
        )

        teacher_text = response.choices[0].message.content
        print(f"\n👨‍🏫 УЧИТЕЛЬ: {teacher_text}\n")

        # Озвучить ответ
        try:
            print("   🔊 Озвучиваю ответ...")
            response = client.audio.speech.create(
                model="gpt-4o-mini-tts",
                voice="nova",
                input=teacher_text,
                response_format="wav",
                speed=0.8,
                extra_body={
                    "instructions": "Speak slowly and clearly for an English learner."
                },
            )
            import tempfile
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
                response.stream_to_file(tmp.name)
                Path(tmp.name).unlink(missing_ok=True)
            print("   ✅ Озвучено!\n")
        except Exception as e:
            print(f"   ⚠️  Ошибка TTS: {str(e)[:50]}\n")

    except KeyboardInterrupt:
        print("\n\n👋 До встречи!\n")
        break
    except Exception as e:
        print(f"❌ Ошибка: {e}\n")

print("="*70 + "\n")
