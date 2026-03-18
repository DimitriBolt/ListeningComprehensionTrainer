# 🎧 Listening Comprehension Trainer

Приложение для тренировки listening comprehension английского языка с использованием AI.

## 📋 Проект структура

```
ListeningComprehensionTrainer/
├── src/                          # Исходный код по этапам
│   ├── stage1_pause_detection.py # ✅ ЭТАП 1: Обнаружение паузы
│   ├── stage2_stt.py             # ЭТАП 2: Speech-to-Text (Whisper)
│   ├── stage3_llm.py             # ЭТАП 3: LLM интеграция
│   ├── stage4_tts.py             # ЭТАП 4: Text-to-Speech
│   ├── main_cli.py               # ЭТАП 5: Полный цикл
│   └── ...
├── api/                          # ЭТАП 7: REST API (FastAPI)
├── web/                          # ЭТАП 6: Web интерфейс (Streamlit)
├── android_app/                  # ЭТАП 8: Android приложение
├── audio_files/                  # 📁 Сохраненные аудиозаписи
├── logs/                         # 📁 Логи
├── config.py                     # Конфигурация приложения
├── requirements.txt              # Зависимости
├── .env.example                  # Пример переменных окружения
└── README.md                     # Этот файл
```

## 🚀 Быстрый старт

### Шаг 1: Клонирование и подготовка

```bash
cd ~/PycharmProjects/ListeningComprehensionTrainer
```

### Шаг 2: Установка зависимостей

#### Вариант A: Полная установка (все этапы)
```bash
pip install -r requirements.txt
```

`requirements.txt` использует `pyaudio==0.2.14`, потому что `0.2.13` падает на `Python 3.13`.

#### Вариант B: Минимальная установка (только Этап 1)
```bash
pip install SpeechRecognition "pyaudio>=0.2.14" python-dotenv
```

**⚠️ Примечание для macOS/Linux:**
```bash
# На macOS:
brew install portaudio
pip install "pyaudio>=0.2.14"

# На Ubuntu/Debian:
sudo apt-get install portaudio19-dev
pip install "pyaudio>=0.2.14"
```

Если планируете использовать локальный `pyttsx3` для озвучивания на Linux, дополнительно нужен пакет, который предоставляет `libespeak.so.1`.
Обычно это `libespeak1` или `espeak-ng`, в зависимости от дистрибутива.

### Шаг 3: Настройка окружения

```bash
# Копируем пример конфига
cp .env.example .env

# Редактируем .env с вашими API ключами
nano .env  # или используйте IDE
```

**Нужны API ключи для:**
- ✅ **OPENAI_API_KEY** — для Whisper и GPT (получить на https://platform.openai.com/api-keys)
- ✅ **GEMINI_API_KEY** — для Google Gemini (получить на https://ai.google.dev/tutorials/python_quickstart)

### Шаг 4: Тестирование Этапа 1 (Обнаружение паузы)

```bash
python src/stage1_pause_detection.py
```

Что произойдет:
1. Программа проверит микрофон
2. Адаптируется к шуму окружения (1 сек)
3. Начнет слушать и ждет вашей речи
4. **После 2.5 секунд тишины** автоматически завершит запись
5. Сохранит аудио в `audio_files/recording_*.wav`

**Тестируйте:**
```
Попробуйте 1: Скажите что-то без пауз ✓
Попробуйте 2: Скажите что-то... с паузой... в середине ✓
Попробуйте 3: Длинная речь без пауз (максимум 30 сек) ✓
```

---

## 📚 Этапы разработки

### ✅ ЭТАП 1: Обнаружение паузы (ТЕКУЩИЙ)
**Файл:** `src/stage1_pause_detection.py`

**Что делает:**
- Слушает микрофон с параметром `pause_threshold=2.5s`
- Автоматически завершает запись после обнаружения паузы
- Сохраняет аудио в WAV файл

**Критерий успеха:**
- Программа корректно реагирует на паузы
- Не режет слова в конце
- Аудио файл можно открыть и послушать

---

### 🔄 ЭТАП 2: Speech-to-Text (STT)
**Файл:** `src/stage2_stt.py` (СКОРО)

**Что будет:**
- Использовать OpenAI Whisper для распознавания речи
- Преобразовать аудиофайлы в текст
- Обработка ошибок и логирование

**Зависимости:**
```bash
pip install openai
```

---

### 💭 ЭТАП 3: LLM интеграция
**Файл:** `src/stage3_llm.py` (СКОРО)

**Что будет:**
- Система промптов для учителя
- Интеграция с OpenAI GPT или Google Gemini
- Генерация ответов на уровне A2-B1

---

### 🔊 ЭТАП 4: Text-to-Speech (TTS)
**Файл:** `src/stage4_tts.py` (СКОРО)

**Что будет:**
- Преобразование текста в речь
- Замедление скорости речи (0.8x)
- Высокое качество произношения

---

### 🎮 ЭТАП 5: Полный цикл (CLI)
**Файл:** `src/main_cli.py` (СКОРО)

**Что будет:**
- Полный pipeline: Микрофон → STT → LLM → TTS → Динамики
- Бесконечный цикл диалога

---

### 🌐 ЭТАП 6: Web интерфейс
**Файл:** `web/app.py` (СКОРО)

**Что будет:**
- Streamlit приложение с UI
- Запись аудио прямо в браузере
- История диалога

---

### 📡 ЭТАП 7: REST API
**Файл:** `api/main.py` (СКОРО)

**Что будет:**
- FastAPI сервер
- Endpoints для STT, LLM, TTS
- Развертывание в облаке

---

### 📱 ЭТАП 8: Android приложение
**Папка:** `android_app/` (СКОРО)

**Что будет:**
- Нативное Java приложение для Android
- Запись аудио и отправка на сервер
- Воспроизведение ответов

---

## 🛠️ Конфигурация (config.py)

Основные параметры, которые вы можете менять:

```python
PAUSE_THRESHOLD = 2.5      # Пауза обнаружения (сек). 
                           # ↑ Увеличьте, если режет слова
                           # ↓ Уменьшьте, если ждет после конца

PHRASE_TIME_LIMIT = 30     # Максимум времени на одну запись (сек)

TTS_SPEED = 0.8            # Скорость речи (0.5-1.0, где 0.5 - медленнее)

LANGUAGE_LEVEL = "B1"      # Уровень английского (A2 или B1)

LLM_MODEL = "gemini"       # Какой LLM использовать (gemini или openai)

TTS_SERVICE = "pyttsx3"    # TTS сервис (pyttsx3, openai, elevenlabs)
```

---

## 📝 Логирование

Логи сохраняются в папке `logs/`:
- `stage1.log` — логи Этапа 1
- `stage2.log` — логи Этапа 2
- И т.д...

Просмотр логов:
```bash
tail -f logs/stage1.log     # Real-time логи
cat logs/stage1.log | grep ERROR  # Только ошибки
```

---

## 🐛 Часто встречаемые проблемы

### ❌ "No module named 'pyaudio'"
```bash
# Решение:
pip install --upgrade pyaudio
# Или:
brew install portaudio && pip install pyaudio  # macOS
sudo apt-get install portaudio19-dev && pip install pyaudio  # Ubuntu
```

Если у вас `Python 3.13`, убедитесь, что ставится `PyAudio 0.2.14` или новее.

### ❌ "No microphone input detected"
- Проверьте, что микрофон подключен
- Проверьте в системных настройках, что микрофон разрешен
- Попробуйте: `python -m speech_recognition`

### ❌ "OSError: libespeak.so.1: cannot open shared object file"
- Это системная зависимость `pyttsx3`, а не Python-пакет из `requirements.txt`
- Установите пакет, который предоставляет `libespeak.so.1`
- На многих Debian/Ubuntu системах это `libespeak1` или `espeak-ng`
- После этого перезапустите PyCharm-конфигурацию

### ❌ "Timeout: listening for phrase timed out"
- Говорите громче или приближайтесь к микрофону
- Измените `PAUSE_THRESHOLD` в `config.py`

### ❌ "Audio is silent (possibly too quiet)"
- Увеличьте громкость микрофона
- Уменьшите фоновый шум

---

## 🎯 Рекомендуемый путь разработки

1. **Сейчас:** Запустите Этап 1 и убедитесь, что микрофон работает ✓
2. **Следующая неделя:** Добавьте Этап 2 (STT)
3. **Далее:** Этапы 3-5 (полный цикл)
4. **Затем:** Web интерфейс (Этап 6)
5. **Финал:** API + Android (Этапы 7-8)

---

## 📚 Полезные ресурсы

- **SpeechRecognition docs:** https://github.com/Uberi/speech_recognition
- **OpenAI API:** https://platform.openai.com/docs
- **Google Gemini:** https://ai.google.dev
- **Streamlit:** https://streamlit.io/
- **FastAPI:** https://fastapi.tiangolo.com/
- **Android Development:** https://developer.android.com/

---

## 📧 Поддержка

Если возникли проблемы:
1. Проверьте логи в папке `logs/`
2. Смотрите вывод консоли программы
3. Убедитесь, что `config.py` правильно настроен
4. Проверьте подключение интернета для API вызовов

---

## 📄 Лицензия

MIT License

---

**Статус проекта:** 🟢 Этап 1 готов к тестированию

Начните с `python src/stage1_pause_detection.py` — говорите в микрофон и смотрите, как программа автоматически завершает запись! 🎙️
