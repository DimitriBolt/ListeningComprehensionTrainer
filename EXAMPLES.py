"""
ПРИМЕРЫ ИСПОЛЬЗОВАНИЯ LISTENING COMPREHENSION TRAINER

Этот файл содержит примеры кода для каждого этапа разработки.
Скопируйте примеры и адаптируйте под свои нужды.
"""

# =====================================
# ЭТАП 1: ОБНАРУЖЕНИЕ ПАУЗЫ
# =====================================

"""
Пример 1.1: Простая запись аудио с микрофона
"""
import speech_recognition as sr

recognizer = sr.Recognizer()
recognizer.pause_threshold = 2.5  # 2.5 секунды паузы = конец речи

with sr.Microphone() as source:
    print("Говорите...")
    audio = recognizer.listen(source)

# Сохранить в файл
with open("recording.wav", "wb") as f:
    f.write(audio.get_wav_data())

print("Запись завершена и сохранена в recording.wav")


"""
Пример 1.2: Настройка параметров микрофона
"""
import speech_recognition as sr

recognizer = sr.Recognizer()

# Параметры обнаружения паузы
recognizer.pause_threshold = 2.5    # Пауза обнаружения (сек)
recognizer.non_speaking_duration = 0.4  # Минимальная длительность звука
recognizer.phrase_time_limit = 30   # Максимум на одну запись (сек)
recognizer.energy_threshold = 4000  # Порог громкости (адаптируется)

with sr.Microphone() as source:
    recognizer.adjust_for_ambient_noise(source, duration=1)  # Адаптация к шуму
    audio = recognizer.listen(source)

print(f"Записано {len(audio.frame_data)} байт аудио")


# =====================================
# ЭТАП 2: SPEECH-TO-TEXT (WHISPER)
# =====================================

"""
Пример 2.1: Распознавание с OpenAI Whisper API
"""
from openai import OpenAI

client = OpenAI(api_key="your_openai_api_key")

# Способ 1: Из файла
with open("recording.wav", "rb") as audio_file:
    transcript = client.audio.transcriptions.create(
        model="whisper-1",
        file=audio_file,
        language="en"  # Явно указываем язык
    )
    print(f"Распознанный текст: {transcript.text}")

# Способ 2: Из bytes
import speech_recognition as sr
recognizer = sr.Recognizer()
with sr.Microphone() as source:
    audio = recognizer.listen(source)

transcript = client.audio.transcriptions.create(
    model="whisper-1",
    file=("audio.wav", audio.get_wav_data()),
    language="en"
)
print(f"Распознанный текст: {transcript.text}")


"""
Пример 2.2: Распознавание с локальной моделью Whisper
"""
import whisper

# Загружаем модель (первый раз ~1.5ГБ)
model = whisper.load_model("base")  # или "tiny", "small", "medium", "large"

# Распознаем аудиофайл
result = model.transcribe("recording.wav", language="en")
print(f"Распознанный текст: {result['text']}")


"""
Пример 2.3: Обработка ошибок при STT
"""
from openai import OpenAI
from openai import APIError

client = OpenAI(api_key="your_openai_api_key")

try:
    with open("recording.wav", "rb") as audio_file:
        transcript = client.audio.transcriptions.create(
            model="whisper-1",
            file=audio_file,
            language="en"
        )
        print(f"Успешно: {transcript.text}")
except FileNotFoundError:
    print("Ошибка: Аудиофайл не найден")
except APIError as e:
    print(f"Ошибка API: {e}")
except Exception as e:
    print(f"Неожиданная ошибка: {e}")


# =====================================
# ЭТАП 3: LLM ИНТЕГРАЦИЯ
# =====================================

"""
Пример 3.1: OpenAI GPT с system prompt (учитель)
"""
from openai import OpenAI

client = OpenAI(api_key="your_openai_api_key")

system_prompt = """Ты — терпеливый преподаватель английского языка.
Твоя цель — тренировать listening comprehension пользователя с уровнем B1.
Используй только простую лексику и короткие предложения (MAX 3 предложения).
Избегай сложных идиом и фразовых глаголов.
Если пользователь делает ошибку, мягко исправь его с объяснением."""

user_message = "I go to school yesterday"

response = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_message}
    ],
    temperature=0.7,  # Креативность (0-1)
    max_tokens=150    # Максимум токенов в ответе
)

print(f"Ответ учителя: {response.choices[0].message.content}")


"""
Пример 3.2: Google Gemini с system prompt
"""
import google.generativeai as genai

genai.configure(api_key="your_gemini_api_key")

system_prompt = """Ты — терпеливый преподаватель английского языка.
Твоя цель — тренировать listening comprehension пользователя с уровнем B1.
Используй только простую лексику и короткие предложения."""

model = genai.GenerativeModel(
    model_name="gemini-pro",
    system_instruction=system_prompt
)

user_message = "I go to school yesterday"
response = model.generate_content(user_message)

print(f"Ответ учителя: {response.text}")


"""
Пример 3.3: Класс для управления LLM
"""
from openai import OpenAI
import os

class TeacherBot:
    def __init__(self, level="B1", provider="openai"):
        self.level = level
        self.provider = provider

        if provider == "openai":
            self.client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))
            self.model = "gpt-4o-mini"
        elif provider == "gemini":
            import google.generativeai as genai
            genai.configure(api_key=os.getenv("GEMINI_API_KEY"))
            self.client = genai.GenerativeModel("gemini-pro")

    def get_system_prompt(self):
        return f"""Ты — терпеливый преподаватель английского языка.
Твоя цель — тренировать listening comprehension пользователя с уровнем {self.level}.
Используй только простую лексику и короткие предложения (MAX 3).
Избегай сложных идиом."""

    def generate_response(self, user_text):
        if self.provider == "openai":
            response = self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": self.get_system_prompt()},
                    {"role": "user", "content": user_text}
                ],
                temperature=0.7,
                max_tokens=150
            )
            return response.choices[0].message.content
        elif self.provider == "gemini":
            response = self.client.generate_content(user_text)
            return response.text

# Использование
teacher = TeacherBot(level="B1", provider="openai")
response = teacher.generate_response("I go to school yesterday")
print(response)


# =====================================
# ЭТАП 4: TEXT-TO-SPEECH
# =====================================

"""
Пример 4.1: OpenAI TTS с замедленной скоростью
"""
from openai import OpenAI
import os

client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

text = "Hi! How are you today?"

response = client.audio.speech.create(
    model="tts-1",  # или tts-1-hd для большего качества
    voice="nova",   # alloy, echo, fable, onyx, nova, shimmer
    speed=0.8,      # 0.5-1.0, где 0.5 - медленнее
    input=text
)

# Сохранить в файл
response.stream_to_file("response.mp3")
print("Аудио сохранено в response.mp3")

# Или получить raw bytes
audio_bytes = response.content
print(f"Размер аудио: {len(audio_bytes)} байт")


"""
Пример 4.2: pyttsx3 (локально, бесплатно)
"""
import pyttsx3

engine = pyttsx3.init()

# Настройки
engine.setProperty('rate', 120)      # Скорость (слов в минуту)
engine.setProperty('volume', 0.9)    # Громкость (0-1)
engine.setProperty('voice', 0)       # Голос (зависит от ОС)

# Озвучить текст
text = "Hi! How are you today?"
engine.say(text)
engine.runAndWait()

# Сохранить в файл
engine.save_to_file(text, "response.mp3")
engine.runAndWait()


"""
Пример 4.3: Класс для управления TTS
"""
from openai import OpenAI
import pyttsx3
import os

class SpeechSynthesizer:
    def __init__(self, service="pyttsx3", speed=0.8):
        self.service = service
        self.speed = speed

        if service == "openai":
            self.client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))
        elif service == "pyttsx3":
            self.engine = pyttsx3.init()
            # Преобразуем speed (0-1) в rate (слов в минуту)
            self.engine.setProperty('rate', 150 * speed)

    def speak(self, text, filename="output.mp3"):
        if self.service == "openai":
            response = self.client.audio.speech.create(
                model="tts-1",
                voice="nova",
                speed=self.speed,
                input=text
            )
            response.stream_to_file(filename)
        elif self.service == "pyttsx3":
            self.engine.save_to_file(text, filename)
            self.engine.runAndWait()

        return filename

# Использование
synthesizer = SpeechSynthesizer(service="pyttsx3", speed=0.8)
synthesizer.speak("Hi! How are you?", "response.mp3")


"""
Пример 4.4: Воспроизведение аудио через динамики
"""
import pygame
import time

# Инициализируем pygame mixer
pygame.mixer.init()

# Загружаем и проигрываем аудиофайл
sound = pygame.mixer.Sound("response.mp3")
sound.play()

# Ждем завершения воспроизведения
time.sleep(sound.get_length())


# =====================================
# ЭТАП 5: ПОЛНЫЙ ЦИКЛ
# =====================================

"""
Пример 5.1: Полный цикл - микрофон → STT → LLM → TTS
"""
import speech_recognition as sr
from openai import OpenAI
import os
import pygame

def listening_comprehension_cycle():
    # Инициализация
    recognizer = sr.Recognizer()
    recognizer.pause_threshold = 2.5

    client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))
    pygame.mixer.init()

    print("Начинается цикл тренировки. Говорите в микрофон.")

    while True:
        try:
            # Шаг 1: Слушаем микрофон
            print("\n🎙️  Слушаю...")
            with sr.Microphone() as source:
                recognizer.adjust_for_ambient_noise(source, duration=0.5)
                audio = recognizer.listen(source)
            print("✓ Запись завершена")

            # Шаг 2: Распознаем текст (STT)
            print("📝 Распознаю текст...")
            transcript = client.audio.transcriptions.create(
                model="whisper-1",
                file=("audio.wav", audio.get_wav_data()),
                language="en"
            )
            user_text = transcript.text
            print(f"Вы сказали: {user_text}")

            # Шаг 3: Генерируем ответ (LLM)
            print("💭 Генерирую ответ...")
            system_prompt = """Ты преподаватель английского. Уровень B1. 
            Короткие предложения. Простая лексика. Максимум 3 предложения."""

            response = client.chat.completions.create(
                model="gpt-4o-mini",
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_text}
                ],
                max_tokens=150
            )
            teacher_text = response.choices[0].message.content
            print(f"Учитель: {teacher_text}")

            # Шаг 4: Озвучиваем ответ (TTS)
            print("🔊 Озвучиваю ответ...")
            speech_response = client.audio.speech.create(
                model="tts-1",
                voice="nova",
                speed=0.8,
                input=teacher_text
            )
            speech_response.stream_to_file("response.mp3")

            # Шаг 5: Проигрываем звук
            sound = pygame.mixer.Sound("response.mp3")
            sound.play()
            import time
            time.sleep(sound.get_length())

            # Шаг 6: Цикл повторяется
            print("\n" + "="*50)

        except KeyboardInterrupt:
            print("\n👋 Тренировка завершена")
            break
        except Exception as e:
            print(f"❌ Ошибка: {e}")

# Запуск
# listening_comprehension_cycle()


"""
Пример 5.2: Класс Trainer для управления полным циклом
"""
import logging
from datetime import datetime

class Trainer:
    def __init__(self, config):
        self.config = config
        self.history = []
        self.logger = logging.getLogger(__name__)

    def record_audio(self):
        """Записать аудио с микрофона"""
        pass

    def transcribe(self, audio):
        """Распознать текст из аудио"""
        pass

    def generate_response(self, user_text):
        """Сгенерировать ответ учителя"""
        pass

    def synthesize_speech(self, text):
        """Озвучить текст"""
        pass

    def play_audio(self, filename):
        """Проиграть аудиофайл"""
        pass

    def save_to_history(self, user_text, teacher_response):
        """Сохранить в историю"""
        entry = {
            "timestamp": datetime.now().isoformat(),
            "user": user_text,
            "teacher": teacher_response
        }
        self.history.append(entry)
        self.logger.info(f"Saved to history: {entry}")

    def run_cycle(self):
        """Запустить один цикл тренировки"""
        audio = self.record_audio()
        user_text = self.transcribe(audio)
        teacher_response = self.generate_response(user_text)
        audio_file = self.synthesize_speech(teacher_response)
        self.play_audio(audio_file)
        self.save_to_history(user_text, teacher_response)

    def run_forever(self):
        """Запустить бесконечный цикл"""
        while True:
            try:
                self.run_cycle()
            except KeyboardInterrupt:
                self.logger.info("Training stopped")
                break
            except Exception as e:
                self.logger.error(f"Error: {e}")


# =====================================
# ЭТАП 6: WEB ИНТЕРФЕЙС (STREAMLIT)
# =====================================

"""
Пример 6.1: Простое Streamlit приложение
"""
import streamlit as st
from streamlit_webrtc import webrtc_streamer, RTCConfiguration
import os
from openai import OpenAI

st.set_page_config(page_title="Listening Trainer", layout="wide")
st.title("🎧 Listening Comprehension Trainer")

# Боковая панель с настройками
st.sidebar.header("⚙️ Settings")
level = st.sidebar.select_slider("Level", ["A2", "B1"], value="B1")
speed = st.sidebar.slider("Speech Speed", 0.5, 1.0, 0.8, 0.1)

# Главная область
col1, col2 = st.columns([1, 1])

with col1:
    st.subheader("🎙️ Record Your Voice")

    rtc_configuration = RTCConfiguration(
        {"iceServers": [{"urls": ["stun:stun.l.google.com:19302"]}]}
    )

    webrtc_ctx = webrtc_streamer(
        key="listening-trainer",
        rtc_configuration=rtc_configuration,
        media_stream_constraints={"audio": True},
        async_processing=True,
    )

with col2:
    st.subheader("💬 Teacher Response")

    if webrtc_ctx.state.playing:
        st.info("Recording...")
    else:
        st.success("Ready to record!")

# История
st.subheader("📚 Conversation History")
if "history" not in st.session_state:
    st.session_state.history = []

for i, entry in enumerate(st.session_state.history):
    st.write(f"**You:** {entry['user']}")
    st.write(f"**Teacher:** {entry['teacher']}")
    st.divider()
"""


# =====================================
# ЭТАП 7: REST API (FASTAPI)
# =====================================

"""
Пример 7.1: FastAPI endpoints
"""
from fastapi import FastAPI, File, UploadFile, HTTPException
from pydantic import BaseModel
from openai import OpenAI
import os

app = FastAPI(title="Listening Trainer API")

# Модели
class TextRequest(BaseModel):
    text: str

class ResponseModel(BaseModel):
    user_text: str
    teacher_response: str

# Инициализация
client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

@app.get("/health")
async def health():
    return {"status": "ok"}

@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...)):
    """Распознать текст из аудиофайла"""
    try:
        content = await file.read()
        transcript = client.audio.transcriptions.create(
            model="whisper-1",
            file=("audio.wav", content),
            language="en"
        )
        return {"text": transcript.text}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

@app.post("/generate_response")
async def generate_response(request: TextRequest):
    """Сгенерировать ответ учителя"""
    try:
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": "Ты преподаватель английского уровня B1"},
                {"role": "user", "content": request.text}
            ],
            max_tokens=150
        )
        return {"response": response.choices[0].message.content}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

@app.post("/full_cycle")
async def full_cycle(file: UploadFile = File(...)):
    """Полный цикл: STT → LLM → TTS"""
    try:
        # STT
        content = await file.read()
        transcript = client.audio.transcriptions.create(
            model="whisper-1",
            file=("audio.wav", content),
            language="en"
        )
        user_text = transcript.text
        
        # LLM
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": "Ты преподаватель английского"},
                {"role": "user", "content": user_text}
            ],
            max_tokens=150
        )
        teacher_response = response.choices[0].message.content
        
        # TTS
        speech = client.audio.speech.create(
            model="tts-1",
            voice="nova",
            speed=0.8,
            input=teacher_response
        )
        
        return {
            "user_text": user_text,
            "teacher_response": teacher_response,
            "audio": "URL к аудиофайлу"
        }
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

# Запуск: uvicorn app:app --reload
"""


# =====================================
# ЭТАП 8: ANDROID (Java примеры)
# =====================================

"""
Пример 8.1: Java - AudioRecorder.java
"""
JAVA_CODE = """
import android.media.MediaRecorder;
import java.io.IOException;

public class AudioRecorder {
    private MediaRecorder mediaRecorder;
    private String filePath;
    
    public AudioRecorder(String filePath) {
        this.filePath = filePath;
    }
    
    public void startRecording() throws IOException {
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setOutputFile(filePath);
        mediaRecorder.prepare();
        mediaRecorder.start();
    }
    
    public void stopRecording() {
        if (mediaRecorder != null) {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }
}
"""

"""
Пример 8.2: Java - APIClient.java
"""
JAVA_CODE_2 = """
import okhttp3.*;
import java.io.File;
import java.io.IOException;

public class APIClient {
    private OkHttpClient client = new OkHttpClient();
    private static final String BASE_URL = "https://your-api.com";
    
    public String fullCycle(File audioFile) throws IOException {
        RequestBody requestBody = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.getName(),
                RequestBody.create(MediaType.parse("audio/wav"), audioFile))
            .build();
        
        Request request = new Request.Builder()
            .url(BASE_URL + "/full_cycle")
            .post(requestBody)
            .build();
        
        try (Response response = client.newCall(request).execute()) {
            return response.body().string();
        }
    }
}
"""

print("✅ Примеры кода готовы для всех 8 этапов!")

