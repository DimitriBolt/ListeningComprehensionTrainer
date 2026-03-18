# Listening Comprehension Trainer

CLI-прототип для тренировки английского через короткий голосовой диалог:
микрофон -> Whisper -> ChatGPT -> TTS.

## Что делает сейчас

- записывает реплику пользователя до момента тишины;
- распознает речь через OpenAI Whisper;
- генерирует ответ преподавателя под уровень `A2`, `B1` или `B2`;
- озвучивает ответ через OpenAI TTS с управляемыми паузами между chunk-ами;
- сохраняет историю диалога в `sessions/`.

## Структура проекта

```text
ListeningComprehensionTrainer/
├── main.py
├── src/
│   ├── __init__.py
│   └── openai_compat.py
├── .env.example
├── requirements.txt
└── README.md
```

Runtime-артефакты создаются автоматически и в git не попадают:
`audio_files/`, `logs/`, `responses/`, `sessions/`, `transcripts/`.

## Быстрый старт

### 1. Установить зависимости

```bash
pip install -r requirements.txt
```

Для `pyaudio` на Linux/macOS обычно нужен `portaudio`.

### 2. Настроить окружение

```bash
cp .env.example .env
```

Минимально обязателен только `OPENAI_API_KEY`.

### 3. Запустить приложение

Интерактивное меню:

```bash
python3 main.py
```

Сразу начать голосовую практику:

```bash
python3 main.py --practice
```

Показать последний сохраненный сеанс:

```bash
python3 main.py --history
```

## Параметры для тюнинга

В `.env` можно менять:

```bash
OPENAI_API_KEY=...
LANGUAGE_LEVEL=B1
PAUSE_THRESHOLD=2.5
PHRASE_TIME_LIMIT=30
TTS_SPEED=0.8
TTS_VOLUME=0.9
```

Пояснения к ключевым параметрам продублированы прямо в `main.py`.

## Внешние зависимости

- `ffplay` используется для воспроизведения OpenAI TTS WAV-файлов;
- если `ffplay` недоступен, приложение переключается на локальный `pyttsx3`;
- для `pyttsx3` на Linux может понадобиться `espeak` или `espeak-ng`.

## Ограничения прототипа

- текущий интерфейс только CLI;
- нужен рабочий микрофон;
- нужен OpenAI API key;
- вся логика пока собрана в `main.py`, без разбиения на отдельные сервисы.
