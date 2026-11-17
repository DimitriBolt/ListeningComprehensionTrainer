package com.example.listeningtrainer.config

/**
 * Все модели, голос, скорость, адреса API и таймауты — только здесь (инвариант 4).
 * Смена модели — правка этого файла или новая реализация интерфейса, а не поиск по коду.
 */
object ModelConfig {
    // --- OpenAI ---
    const val OPENAI_BASE_URL = "https://api.openai.com/"

    /** Распознавание речи ученика. */
    const val TRANSCRIBE_MODEL = "whisper-1"
    const val TRANSCRIBE_LANGUAGE = "en"

    /** Учитель (Chat Completions) и проверка ключа. */
    const val TUTOR_MODEL = "gpt-4o-mini"

    /** Синтез речи учителя. */
    const val TTS_MODEL = "tts-1"
    const val TTS_VOICE = "nova"
    const val TTS_FORMAT = "mp3"
    const val TTS_SPEED = 0.88f

    // --- Инструменты учителя ---
    /** Погода: `{location}` заменяется на город. */
    const val WEATHER_URL = "https://wttr.in/{location}?format=%l:+%C+%t"
    /** Поиск: к адресу добавляется запрос в URL-кодировке. */
    const val SEARCH_URL = "https://duckduckgo.com/lite/?q="
    const val SEARCH_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    // --- Сеть ---
    /** Таймауты OkHttp. Значения по умолчанию OkHttp (10 с), как было до S01. */
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val READ_TIMEOUT_MS = 10_000L
    const val WRITE_TIMEOUT_MS = 10_000L
    /** Параллельные запросы синтеза: все группы ответа запрашиваются сразу. */
    const val MAX_PARALLEL_REQUESTS = 50
}
