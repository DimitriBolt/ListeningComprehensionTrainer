# 🗺️ Дорожная карта проекта

## Общий план разработки

Этот документ описывает стратегию разработки Listening Comprehension Trainer с указанием этапов, сроков и критериев успеха.

---

## 📊 Временная шкала

```
Этап 1: Обнаружение паузы    |███████░░░░░░░░░| 1-2 дня    ✅ ТЕКУЩИЙ
Этап 2: STT (Whisper)        |░░░░░░░░░░░░░░░░| 2-3 дня    🔄 СЛЕДУЮЩИЙ
Этап 3: LLM интеграция       |░░░░░░░░░░░░░░░░| 2-3 дня
Этап 4: TTS интеграция       |░░░░░░░░░░░░░░░░| 1-2 дня
Этап 5: Полный цикл (CLI)    |░░░░░░░░░░░░░░░░| 2-3 дня
Этап 6: Web интерфейс        |░░░░░░░░░░░░░░░░| 2-3 дня
Этап 7: REST API сервер      |░░░░░░░░░░░░░░░░| 2-3 дня
Этап 8: Android приложение   |░░░░░░░░░░░░░░░░| 3-5 дней
─────────────────────────────────────────────────────
Всего: ~8-10 недель при 10-15 часах/неделю
```

---

## 🎯 Этапы разработки

### ✅ ЭТАП 1: Обнаружение паузы (ТЕКУЩИЙ)

**Описание:** Решение проблемы «перебивания» - программа должна понять, когда пользователь закончил говорить.

**Файлы:**
- `src/stage1_pause_detection.py` — основной скрипт
- `config.py` — конфигурация (PAUSE_THRESHOLD=2.5)

**Технология:**
- `SpeechRecognition` библиотека для захвата аудио
- `pause_threshold` параметр для обнаружения паузы

**Входные параметры:**
- `pause_threshold` — 2.5 сек (длительность тишины = конец речи)
- `phrase_time_limit` — 30 сек (максимум на одну фразу)

**Выходные данные:**
- WAV файл с записанной речью в `audio_files/recording_*.wav`

**Критерии успеха:**
- ✅ Программа корректно реагирует на 2-3 секундные паузы
- ✅ Не режет слова в конце фразы
- ✅ Максимальное время на запись соблюдается
- ✅ Логирование работает (файлы в `logs/stage1.log`)

**Команда для запуска:**
```bash
python src/stage1_pause_detection.py
```

---

### 🔄 ЭТАП 2: Speech-to-Text (Whisper)

**Описание:** Распознавание речи - преобразование аудиофайла в текст.

**Файлы (TODO):**
- `src/stage2_stt.py` — основной скрипт
- `src/audio_handler.py` — работа с аудиофайлами

**Технология:**
- OpenAI Whisper API или локальная модель `openai-whisper`

**Входные данные:**
- WAV файл из Этапа 1

**Выходные данные:**
- Распознанный текст

**Выбор: локально vs облако?**
- **Опция A:** `openai-whisper` (локально, ~1.5ГБ, медленнее на CPU)
- **Опция B:** OpenAI Whisper API (облако, быстро, ~$0.02/мин)
- **Рекомендация:** Начать с API для скорости разработки

**Критерии успеха:**
- ✅ Распознает русский и английский текст
- ✅ Точность распознавания >90%
- ✅ Обработка ошибок (no audio, API error)
- ✅ Время ответа <5 сек

**Примерный код:**
```python
from openai import OpenAI
audio_file = open("audio_files/recording_20240301_120000.wav", "rb")
transcript = client.audio.transcriptions.create(
    model="whisper-1",
    file=audio_file,
    language="en"
)
print(transcript.text)
```

---

### 💭 ЭТАП 3: LLM интеграция

**Описание:** Решение проблемы «сложной лексики» - AI должен отвечать как терпеливый учитель.

**Файлы (TODO):**
- `src/stage3_llm.py` — основной скрипт
- `src/teacher_prompt.py` — система промптов

**Технология:**
- OpenAI GPT или Google Gemini

**System Prompt:**
```
Ты — терпеливый преподаватель английского языка.
Твоя цель — тренировать listening comprehension пользователя с уровнем {level}.
Используй только простую лексику и короткие предложения (MAX 3 предложения).
Избегай сложных идиом и фразовых глаголов.
Если пользователь делает ошибку, мягко исправь его с объяснением.
Поддерживай разговор, задавай вопросы для практики.
```

**Входные данные:**
- Распознанный текст из Этапа 2

**Выходные данные:**
- Ответ учителя (текст)

**Выбор: OpenAI vs Gemini?**
- **OpenAI GPT-4o mini:** дешево (~$0.00015 за 1K input), самый надежный
- **Google Gemini:** бесплатный уровень ~60 запросов/мин
- **Рекомендация:** Начать с Gemini (бесплатно), потом на OpenAI

**Критерии успеха:**
- ✅ Ответы в роли учителя
- ✅ Простая лексика (уровень A2-B1)
- ✅ 2-3 предложения максимум
- ✅ Обработка ошибок пользователя

---

### 🔊 ЭТАП 4: Text-to-Speech

**Описание:** Решение проблемы «быстрой речи» - программа говорит медленно и четко.

**Файлы (TODO):**
- `src/stage4_tts.py` — основной скрипт
- `src/audio_player.py` — воспроизведение звука

**Технология:**
- OpenAI TTS API, ElevenLabs или pyttsx3 (локально)

**Входные данные:**
- Текст ответа учителя

**Выходные данные:**
- MP3 / WAV файл или прямое воспроизведение

**Параметры:**
- `speed = 0.8` — замедление речи на 20%
- `voice = "nova"` или другой голос

**Выбор: OpenAI vs pyttsx3 vs ElevenLabs?**
- **pyttsx3:** локально, быстро, низкое качество звука
- **OpenAI TTS:** высокое качество, платно (~$0.015/1K символов)
- **ElevenLabs:** естественный голос, платно
- **Рекомендация:** pyttsx3 для прототипа, потом на OpenAI

**Критерии успеха:**
- ✅ Четкая, понятная речь
- ✅ Медленная скорость (0.8x)
- ✅ Воспроизведение через динамики
- ✅ Время генерации <3 сек

---

### 🎮 ЭТАП 5: Полный цикл (CLI приложение)

**Описание:** Объединение всех компонентов в работающий бесконечный цикл диалога.

**Архитектура:**
```
┌──────────────────────────────────────────────┐
│         LISTENING COMPREHENSION TRAINER      │
├──────────────────────────────────────────────┤
│                                              │
│  1. 🎙️  Слушаем микрофон                   │
│         (STT1: Обнаружение паузы)           │
│         ↓                                    │
│  2. 📝 Распознаем речь                      │
│         (STT2: Whisper)                     │
│         ↓                                    │
│  3. 💭 Генерируем ответ учителя             │
│         (LLM: OpenAI/Gemini)                │
│         ↓                                    │
│  4. 🔊 Озвучиваем ответ                     │
│         (TTS: OpenAI/pyttsx3)               │
│         ↓                                    │
│  5. 🔁 Циклимся - слушаем новую запись     │
│         (повтор шаги 1-4)                   │
│                                              │
└──────────────────────────────────────────────┘
```

**Файлы (TODO):**
- `src/main_cli.py` — основной цикл
- `src/trainer.py` — класс Trainer

**Входные данные:**
- Микрофон (реальное время)

**Выходные данные:**
- Диалог с пользователем в консоль
- Логи всех операций

**Функциональность:**
- Бесконечный цикл диалога
- Команда выхода ("bye", "exit", "quit")
- История разговора (сохранение в файл)
- Обработка ошибок и восстановление

**Критерии успеха:**
- ✅ Полный цикл работает без сбоев 10+ минут
- ✅ Естественный диалог с пользователем
- ✅ Логирование всех этапов
- ✅ Возможность выхода из программы

---

### 🌐 ЭТАП 6: Web интерфейс (Streamlit)

**Описание:** Переход от CLI к web интерфейсу для удобства пользователя.

**Технология:**
- Streamlit (быстрое создание web UI без HTML/CSS/JS)
- streamlit-webrtc (запись аудио в браузере)

**Файлы (TODO):**
- `web/app.py` — Streamlit приложение

**Функциональность:**
- Кнопка "Start Recording" → запись аудио
- Отображение распознанного текста
- Отображение ответа учителя
- История диалога (chat history)
- Настройки: pause_threshold, speed, level (A2/B1)

**Интерфейс:**
```
┌─────────────────────────────────────────┐
│   🎧 Listening Comprehension Trainer    │
├─────────────────────────────────────────┤
│                                         │
│  Settings:                              │
│  [Level: B1  ▼] [Speed: 0.8  ▼]        │
│                                         │
│  ┌───────────────────────────────────┐ │
│  │ [🎙️  Click to record] (20s)       │ │
│  └───────────────────────────────────┘ │
│                                         │
│  📝 You said:                           │
│  "Can you help me with English?"       │
│                                         │
│  🧑‍🏫 Teacher says:                     │
│  "Yes, of course! I can help you      │
│   with English. What do you want     │
│   to learn today?"                    │
│  [▶️ 5.2s audio]                       │
│                                         │
│  📚 History:                            │
│  User: "Hello"                          │
│  Teacher: "Hi! How are you?"           │
│  User: "I'm fine"                      │
│  Teacher: "Great!"                     │
│                                         │
└─────────────────────────────────────────┘
```

**Развертывание:**
- Streamlit Cloud (бесплатно)
- Hugging Face Spaces
- Heroku

**Команда запуска:**
```bash
streamlit run web/app.py
```

---

### 📡 ЭТАП 7: REST API сервер (FastAPI)

**Описание:** Backend для мобильного приложения. Преобразуем CLI в REST API.

**Технология:**
- FastAPI (быстрый, современный)
- Uvicorn (ASGI сервер)

**Файлы (TODO):**
- `api/main.py` — FastAPI приложение
- `api/models.py` — Pydantic модели
- `api/trainer_service.py` — бизнес логика

**API Endpoints:**

```
POST /transcribe
├─ Request: audio WAV file
└─ Response: { "text": "Hello teacher" }

POST /generate_response
├─ Request: { "text": "Hello" }
└─ Response: { "response": "Hi! How are you?" }

POST /synthesize
├─ Request: { "text": "Hi! How are you?" }
└─ Response: audio MP3 file

POST /full_cycle
├─ Request: audio WAV file
└─ Response: { 
│   "user_text": "Hello",
│   "teacher_response": "Hi!",
│   "audio": audio MP3 file
│ }

GET /health
└─ Response: { "status": "ok" }

GET /config
└─ Response: { "pause_threshold": 2.5, "speed": 0.8, ... }
```

**Пример использования:**
```bash
# Распознать речь
curl -X POST -F "audio=@recording.wav" http://localhost:8000/transcribe

# Получить ответ
curl -X POST -H "Content-Type: application/json" \
  -d '{"text":"Hello teacher"}' \
  http://localhost:8000/generate_response

# Синтезировать речь
curl -X POST -H "Content-Type: application/json" \
  -d '{"text":"Hi! How are you?"}' \
  --output answer.mp3 \
  http://localhost:8000/synthesize

# Полный цикл
curl -X POST -F "audio=@recording.wav" \
  http://localhost:8000/full_cycle
```

**Развертывание:**
- Railway (быстро, ~$7/месяц)
- AWS EC2 (t2.micro бесплатный год)
- Google Cloud
- DigitalOcean

**Команда запуска:**
```bash
uvicorn api.main:app --host 0.0.0.0 --port 8000
```

---

### 📱 ЭТАП 8: Android приложение (Java Native)

**Описание:** Мобильное приложение для Android, которое отправляет аудио на сервер и воспроизводит ответ.

**Структура проекта:**
```
android_app/
├── app/src/main/
│   ├── java/com/example/listeningtrainer/
│   │   ├── MainActivity.java         — главный экран
│   │   ├── ChatActivity.java         — история диалога
│   │   ├── AudioRecorder.java        — захват аудио
│   │   ├── APIClient.java            — REST клиент
│   │   └── AudioPlayer.java          — воспроизведение
│   └── AndroidManifest.xml
├── build.gradle
└── settings.gradle
```

**Зависимости:**
- okhttp3 (HTTP клиент)
- ExoPlayer (аудиоплеер)
- ConstraintLayout (UI)

**Permissions:**
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

**Функциональность:**
1. **Главный экран (MainActivity):**
   - Кнопка "🎙️ Start Recording"
   - Таймер записи
   - Отображение статуса

2. **Запись аудио (AudioRecorder):**
   - MediaRecorder API
   - Сохранение WAV файла
   - Отправка на сервер

3. **REST клиент (APIClient):**
   - okhttp3 для HTTP запросов
   - загрузка аудиофайла
   - получение ответа (текст + аудио)

4. **Воспроизведение (AudioPlayer):**
   - ExoPlayer для качественного воспроизведения
   - Контроль громкости

5. **История (ChatActivity):**
   - RecyclerView со списком сообщений
   - Сохранение истории в LocalDatabase

**Примерная логика:**
```java
// Нажата кнопка "Record"
- Запросить permissions (RECORD_AUDIO, INTERNET)
- Начать запись в recorder.wav
- При нажатии "Stop" отправить на сервер

// Ответ с сервера
- Получить JSON: { user_text, teacher_response, audio_url }
- Загрузить audio_url
- Воспроизвести через ExoPlayer
- Добавить в historу (ChatActivity)

// Цикл повторяется
```

**UI Mock:**
```
┌──────────────────────────────────────────┐
│    Listening Comprehension Trainer       │
├──────────────────────────────────────────┤
│                                          │
│  ┌────────────────────────────────────┐ │
│  │    History                         │ │
│  │ ┌──────────────────────────────┐  │ │
│  │ │ You: "Hello teacher"         │  │ │
│  │ └──────────────────────────────┘  │ │
│  │ ┌──────────────────────────────┐  │ │
│  │ │ Teacher: "Hi! How are you?"  │  │ │
│  │ │ [▶️ 3.2s]                    │  │ │
│  │ └──────────────────────────────┘  │ │
│  └────────────────────────────────────┘ │
│                                          │
│  ┌────────────────────────────────────┐ │
│  │  [🎙️ 00:12]  [⏹️ Stop]           │ │
│  │  Recording...                      │ │
│  └────────────────────────────────────┘ │
│                                          │
│  ⚙️ Settings | 📞 Support              │
│                                          │
└──────────────────────────────────────────┘
```

---

## 🔧 Выбор технологий

### STT (Speech-to-Text)

| Вариант | Плюсы | Минусы | Стоимость |
|---------|-------|--------|-----------|
| **OpenAI Whisper API** | ✅ Высокая точность, Облако | ❌ Платно, зависит от интернета | ~$0.02/мин |
| **openai-whisper (локально)** | ✅ Бесплатно, Оффлайн | ❌ Медленно на CPU, 1.5ГБ | Бесплатно |
| **Google Cloud Speech** | ✅ Высокая точность | ❌ Платно | ~$0.006/15s |

**Рекомендация:** `OpenAI Whisper API` для разработки (быстро), позже можно на локальную модель.

---

### LLM (Language Model)

| Вариант | Плюсы | Минусы | Стоимость |
|---------|-------|--------|-----------|
| **OpenAI GPT-4o mini** | ✅ Мощный, Надежный | ❌ Платно | $0.00015/1K tokens |
| **Google Gemini** | ✅ Бесплатный уровень | ❌ Лимиты (60 запросов/мин) | Бесплатно (с лимитами) |
| **Ollama (локально)** | ✅ Бесплатно, Оффлайн | ❌ Медленно, ~7ГБ RAM | Бесплатно |

**Рекомендация:** Начните с `Google Gemini` (бесплатно), потом на `OpenAI` (более надежный).

---

### TTS (Text-to-Speech)

| Вариант | Плюсы | Минусы | Стоимость |
|---------|-------|--------|-----------|
| **OpenAI TTS** | ✅ Естественный голос, Облако | ❌ Платно | ~$0.015/1K символов |
| **pyttsx3 (локально)** | ✅ Бесплатно, Быстро | ❌ Низкое качество звука | Бесплатно |
| **ElevenLabs** | ✅ Очень естественный | ❌ Платно | Платно |

**Рекомендация:** Начните с `pyttsx3` (быстро прототипировать), потом на `OpenAI TTS`.

---

## 📋 Чек-лист разработки

### ✅ Этап 0: Подготовка
- [ ] Структура папок создана
- [ ] `requirements.txt` готов
- [ ] `config.py` готов с параметрами
- [ ] `.env.example` готов

### ✅ Этап 1: Обнаружение паузы (ТЕКУЩИЙ)
- [ ] `stage1_pause_detection.py` работает
- [ ] Микрофон обнаруживает паузы корректно
- [ ] Аудиофайлы сохраняются в WAV
- [ ] Логирование работает
- [ ] README написан

### 🔄 Этап 2: STT
- [ ] `stage2_stt.py` создан
- [ ] Whisper API интегрирован
- [ ] Распознавание текста работает
- [ ] Обработка ошибок реализована
- [ ] Логирование работает

### ⏳ Этап 3: LLM
- [ ] `stage3_llm.py` создан
- [ ] System prompt создан и протестирован
- [ ] LLM отвечает как учитель
- [ ] Простая лексика используется

### ⏳ Этап 4: TTS
- [ ] `stage4_tts.py` создан
- [ ] TTS работает на замедленной скорости
- [ ] Аудио воспроизводится через динамики

### ⏳ Этап 5: Полный цикл
- [ ] `main_cli.py` создан
- [ ] Все компоненты объединены
- [ ] Бесконечный цикл диалога работает
- [ ] История диалога сохраняется

### ⏳ Этап 6: Web интерфейс
- [ ] `web/app.py` создан на Streamlit
- [ ] UI красивый и удобный
- [ ] Запись аудио работает в браузере

### ⏳ Этап 7: REST API
- [ ] `api/main.py` создан на FastAPI
- [ ] Все endpoints работают
- [ ] API развернут на сервере

### ⏳ Этап 8: Android
- [ ] Android Studio проект создан
- [ ] AudioRecorder работает
- [ ] APIClient отправляет запросы
- [ ] AudioPlayer воспроизводит звук
- [ ] App опубликовано в Play Store (опционально)

---

## 🎓 Обучающие материалы

По мере разработки каждого этапа:

1. **Этап 1:** Документация SpeechRecognition
   - https://github.com/Uberi/speech_recognition
   - https://realpython.com/python-speech-recognition/

2. **Этап 2:** OpenAI Whisper
   - https://platform.openai.com/docs/guides/speech-to-text
   - https://github.com/openai/whisper

3. **Этап 3:** OpenAI API
   - https://platform.openai.com/docs/guides/gpt
   - https://developers.google.com/generative-ai

4. **Этап 4:** TTS
   - https://platform.openai.com/docs/guides/text-to-speech
   - https://pyttsx3.readthedocs.io/

5. **Этап 5:** Python Best Practices
   - https://pep8.org/
   - https://docs.python-guide.org/

6. **Этап 6:** Streamlit
   - https://docs.streamlit.io/
   - https://streamlit.io/gallery

7. **Этап 7:** FastAPI
   - https://fastapi.tiangolo.com/
   - https://testdriven.io/blog/fastapi-crud/

8. **Этап 8:** Android Development
   - https://developer.android.com/
   - https://www.udacity.com/course/developing-android-apps-with-kotlin

---

## 🚀 Начало работы

**Сейчас:** Запустите Этап 1 и убедитесь, что микрофон работает
```bash
python src/stage1_pause_detection.py
```

**На следующей неделе:** Перейдите на Этап 2 (STT)

**Далее:** Продолжайте этапы в порядке

---

**Удачи в разработке! 🎉**

