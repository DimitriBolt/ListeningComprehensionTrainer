package com.example.listeningtrainer.conversation

/**
 * Потоковое распознавание во время записи (S03d, D67). Чистый Kotlin, без Android.
 *
 * Звук реплики идёт в распознавание с первого кадра записи; конец реплики решает детектор на устройстве
 * (Silero, D64): речь была — `commit` и ждать текст, не было — `clear`. Сбой в любой момент — текст
 * берётся из файла нынешним путём ([Transcriber]), поток не повторяется.
 */
interface LiveTranscriber {
    /** Открыть сессию заранее (старт сервиса, начало хода), не дожидаясь. Сессия живёт весь разговор. */
    fun prepare() {}

    /**
     * Начать реплику. [prompt] — контекст для модели ([TranscriptionPrompt]). [onPartial] получает
     * частичный текст реплики целиком при каждой новой дельте (для S04), из потока сети.
     * Не бросает: сбой сессии виден в итоге [LiveTurn.result].
     */
    fun begin(prompt: String, onPartial: (String) -> Unit = {}): LiveTurn

    /** Закрыть сессию (сервис останавливается). */
    fun release() {}
}

/** Звук записи, кусок за куском, и её конец ([AudioInput.record]). Вызывается из потока записи. */
interface AudioSink {
    /** Очередной кусок: [count] отсчётов PCM 16 кГц из [pcm]; массив после вызова переиспользуется. */
    fun onPcm(pcm: ShortArray, count: Int)

    /** Запись кончилась (тишина, кнопка, предел) — до сжатия файла. [heardSpeech] — была ли речь. */
    fun onEnd(heardSpeech: Boolean)
}

/** Одна реплика в потоковом распознавании. */
interface LiveTurn : AudioSink {
    /**
     * Текст реплики: после [AudioSink.onEnd] с речью — ждать `completed` не дольше предела
     * ([com.example.listeningtrainer.config.ModelConfig.LIVE_TEXT_TIMEOUT_MS] от `commit`).
     * Без речи или после сбоя — сразу [LiveResult.Failed].
     */
    suspend fun result(): LiveResult

    /** Реплика брошена (Stop, Back, ошибка записи): звук сессии очищается, текст не нужен. */
    fun cancel()
}

/** Итог потока для одной реплики. */
sealed interface LiveResult {
    val stats: LiveStats

    data class Text(val text: String, override val stats: LiveStats) : LiveResult

    /** [reason] — коротко для журнала, без текстов: `session not opened`, `closed: …`, `no text in 2500 ms`, `error …`. */
    data class Failed(val reason: String, override val stats: LiveStats) : LiveResult
}

/** Замер реплики для журнала (без текстов). */
data class LiveStats(
    /** Сколько звук реплики ждал открытия сессии, мс (0 — сессия уже была открыта). */
    val sessionWaitMs: Long = 0,
    /** Сессия уже служила прошлой реплике. */
    val reused: Boolean = false,
    /** Сколько звука ушло в сессию, мс. */
    val audioSentMs: Long = 0,
    /** От `commit` до `completed`, мс; `-1` — текста нет. */
    val commitToTextMs: Long = -1,
    val deltas: Int = 0,
    val chars: Int = 0,
    /** Оценка цены по `usage` (секунды звука), $; 0 — сервер не сообщил. */
    val costUsd: Double = 0.0,
)
