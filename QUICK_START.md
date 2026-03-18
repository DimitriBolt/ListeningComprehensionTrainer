# 🚀 QUICK START — Начните прямо сейчас!

**Время чтения:** 5 минут  
**Время на запуск:** 10-15 минут

---

## 📋 Пятиэтапный план старта

### Шаг 1️⃣ : Установка (5 минут)

#### На Linux/Mac:
```bash
# Перейти в папку проекта
cd ~/PycharmProjects/ListeningComprehensionTrainer

# Установить зависимости для микрофона
sudo apt-get install portaudio19-dev   # Ubuntu/Debian
# или
brew install portaudio                 # macOS

# Установить Python зависимости
pip install SpeechRecognition "pyaudio>=0.2.14" python-dotenv

# Создать файл .env
cp .env.example .env
```

#### На Windows:
```bash
# 1. Установите portaudio: https://www.portaudio.com/download.html
# 2. Затем:
cd %USERPROFILE%\PycharmProjects\ListeningComprehensionTrainer
pip install SpeechRecognition pyaudio python-dotenv
copy .env.example .env
```

### Шаг 2️⃣ : Редактирование конфига (2 минуты)

**Откройте `.env` файл в IDE:**

```bash
nano .env  # или используйте PyCharm/VSCode
```

**Сейчас вам нужны только PAUSE_THRESHOLD (остальное опционально):**

```env
# ===== Микрофон =====
PAUSE_THRESHOLD=2.5      # 2.5 сек паузы = конец речи
PHRASE_TIME_LIMIT=30     # максимум 30 сек на запись

# ===== Все остальное (опционально на этапе 1) =====
# Оставьте как есть или закомментируйте (#)
```

### Шаг 3️⃣ : Запуск первого теста (3 минуты)

```bash
cd ~/PycharmProjects/ListeningComprehensionTrainer
python src/stage1_pause_detection.py
```

**Что произойдет:**
```
================================================
ЭТАП 1: ТЕСТ ОБНАРУЖЕНИЯ ПАУЗЫ С МИКРОФОНА
================================================
✓ Микрофон доступен
⏳ Слушаю шум окружения в течение 1 второй...
✓ Адаптирована к шуму окружения
⏺️  Начало записи...

👉 Начните говорить! (программа завершит запись после 2.5s тишины)
```

### Шаг 4️⃣ : Тестирование (5 минут)

**Попробуйте разные варианты:**

```
Попытка 1: Скажите короткую фразу
  "Hello teacher"
  → Пауза 3 сек → Запись завершена ✓

Попытка 2: Говорите с паузой в середине
  "Hello... (2.5 сек паузы) ...teacher"
  → Запись завершена после паузы ✓

Попытка 3: Длинная речь без пауз
  "My name is John and I want to study English"
  → Запись продолжается до 30 сек ✓
```

**Ожидаемый результат:**
```
✓ Запись сохранена: audio_files/recording_20240301_120000.wav
  Длительность: 5.32 сек
```

### Шаг 5️⃣ : Проверка звука (2 минуты)

**Прослушайте сохраненный аудиофайл:**

```bash
# Linux/Mac
open audio_files/recording_20240301_120000.wav

# Или через Python
import pygame
pygame.mixer.init()
sound = pygame.mixer.Sound("audio_files/recording_20240301_120000.wav")
sound.play()
```

---

## ✅ Критерии успешного запуска

После запуска программы, вы должны увидеть:

- ✅ Микрофон обнаружен
- ✅ Программа слушает вас
- ✅ После паузы запись завершается
- ✅ Аудиофайл сохраняется в `audio_files/`
- ✅ Файл можно открыть и послушать

---

## 🎯 Если что-то не работает

### ❌ "No module named 'pyaudio'"

```bash
# На Ubuntu:
sudo apt-get install portaudio19-dev
pip install --upgrade pyaudio

# На macOS:
brew install portaudio
pip install --upgrade pyaudio

# На Windows - смотрите: https://stackoverflow.com/questions/33513522/python-unable-to-find-vcvarsall-bat
```

Если у вас `Python 3.13`, ставьте `PyAudio 0.2.14` или новее: `pip install "pyaudio>=0.2.14"`.

### ❌ "No microphone input detected"

```bash
# Проверьте микрофон:
python -m speech_recognition

# Если не работает - проверьте системные настройки!
```

### ❌ "Timeout: listening for phrase timed out"

**Решение:** Увеличьте паузу или говорите громче
```env
PAUSE_THRESHOLD=3.5    # Увеличьте если слишком мало времени
```

### ❌ "Audio is silent"

**Решение:** Говорите громче или приближайтесь к микрофону

### ❌ Другая ошибка?

1. Проверьте логи: `cat logs/stage1.log | tail -20`
2. Посмотрите на вывод консоли (есть подробные логи)
3. Убедитесь, что `.env` файл создан

---

## 🎓 Что дальше?

### 📚 После успешного запуска ЭТАПА 1:

1. **Прочитайте ROADMAP.md** для понимания общей архитектуры
   ```bash
   cat ROADMAP.md
   ```

2. **Посмотрите примеры кода** для других этапов
   ```bash
   python EXAMPLES.py
   ```

3. **Переходите на ЭТАП 2** (Speech-to-Text с Whisper)
   - Вам понадобятся API ключи (см. ниже)
   - Следуйте инструкциям в ROADMAP.md → Этап 2

---

## 🔑 Получение API ключей (для этапов 2+)

### OpenAI API ключ (для Whisper и GPT)

1. Откройте https://platform.openai.com/api-keys
2. Нажмите "Create new secret key"
3. Скопируйте ключ
4. Вставьте в `.env`:
   ```env
   OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxx
   ```

### Google Gemini API ключ (бесплатный)

1. Откройте https://ai.google.dev/tutorials/python_quickstart
2. Нажмите "Get API Key"
3. Создайте ключ
4. Вставьте в `.env`:
   ```env
   GEMINI_API_KEY=xxxxxxxxxxxxxxxxxxxxxxx
   ```

---

## 📂 Структура проекта после запуска

```
ListeningComprehensionTrainer/
├── src/
│   └── stage1_pause_detection.py    ← Этап 1 (запускаем)
├── audio_files/                     ← 📁 Сохраняются записи
│   └── recording_20240301_120000.wav
├── logs/                            ← 📁 Логи программы
│   └── stage1.log
├── config.py                        ← Конфигурация (PAUSE_THRESHOLD)
├── .env                             ← Ваши переменные окружения
├── requirements.txt                 ← Зависимости
├── README.md                        ← Полная документация
├── ROADMAP.md                       ← План разработки
└── EXAMPLES.py                      ← Примеры для других этапов
```

---

## 🎮 Как играться с параметрами?

**Отредактируйте `config.py` для экспериментов:**

```python
# Уменьшить время обнаружения паузы (программа завершит быстрее)
PAUSE_THRESHOLD = 1.5  # вместо 2.5

# Увеличить (полезно для медленной речи)
PAUSE_THRESHOLD = 4.0

# Максимум времени на запись
PHRASE_TIME_LIMIT = 20  # вместо 30

# И перезапустите:
python src/stage1_pause_detection.py
```

---

## 💡 Советы для лучшего опыта

1. **Используйте хороший микрофон** — улучшит качество записей
2. **Сидите в тихой комнате** — меньше фонового шума
3. **Говорите четко и не спешите** — программа поймет лучше
4. **Делайте паузы между фразами** — так программа поймет конец
5. **Сохраняйте логи** — для отладки, если что-то не так

---

## 🎯 Финальная чек-лист

Перед тем как считать Этап 1 завершенным:

- [ ] Python 3.8+ установлен
- [ ] SpeechRecognition и pyaudio установлены
- [ ] `.env` файл создан и настроен
- [ ] Микрофон работает и обнаружен программой
- [ ] Программа слушает и записывает звук
- [ ] Пауза обнаруживается правильно (2-3 сек)
- [ ] Аудиофайлы сохраняются в `audio_files/`
- [ ] Логи создаются в `logs/stage1.log`

---

## 🚀 Запуск (TL;DR)

```bash
# 1. Установка
cd ~/PycharmProjects/ListeningComprehensionTrainer
pip install SpeechRecognition pyaudio python-dotenv

# 2. Конфиг (скопировать)
cp .env.example .env

# 3. Запуск
python src/stage1_pause_detection.py

# 4. Говорить в микрофон + пауза 2.5 сек = готово!
```

---

**Готовы начать? Запустите сейчас! 🎙️**

```bash
python src/stage1_pause_detection.py
```

При возникновении проблем, смотрите раздел "Если что-то не работает" выше.

**Удачи! 🎉**
