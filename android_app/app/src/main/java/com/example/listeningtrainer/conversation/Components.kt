package com.example.listeningtrainer.conversation

import java.io.File

// Компоненты под ConversationController (docs/ARCHITECTURE.md, «Целевая структура»).
// Здесь только интерфейсы и данные, без Android-классов. Смена модели или поставщика —
// новая реализация интерфейса, а не правка контроллера.

/**
 * Записанная реплика ученика. Сейчас — WAV-файл.
 * [heardSpeech] — был ли хоть один замер громче порога [TurnDetector]. Нет — это молчание,
 * распознаватель не вызывается (он «слышит» в тишине фразы вроде `Thank you.`).
 */
data class RecordedAudio(
    val file: File,
    val durationMs: Long,
    val heardSpeech: Boolean,
    /** Самый громкий замер записи, дБ (для журнала). */
    val peakDb: Float,
)

/** Микрофон. */
interface AudioInput {
    /**
     * Записывает одну реплику, пока [turnDetector] не скажет «закончил» или не вызван [finish].
     * [onLevel] получает громкость в дБ (для экрана). Отмена корутины прерывает запись без результата.
     */
    suspend fun record(turnDetector: TurnDetector, onLevel: (Float) -> Unit): RecordedAudio

    /** Ученик сам закончил реплику (кнопка «Stop Recording»): запись завершается и отдаётся. */
    fun finish()
}

/** Решает, закончил ли ученик реплику. */
interface TurnDetector {
    /** Начало новой реплики. */
    fun reset(nowMs: Long)

    /** Очередной замер громкости. `true` — реплика закончена. */
    fun onLevel(db: Float, nowMs: Long): Boolean

    /** Был ли с начала реплики хоть один замер, похожий на речь. */
    val heardSpeech: Boolean
}

/** Звук → текст. */
interface Transcriber {
    suspend fun transcribe(audio: RecordedAudio): String
}

/** Ответ учителя. [text] может начинаться со служебной метки — она только для экрана. */
data class TutorReply(val text: String)

/** Учитель: история диалога, системный промпт, инструменты. */
interface Tutor {
    /** Ответ на реплику ученика. [onStatus] — строки состояния для экрана («Accessing internet…»). */
    suspend fun reply(studentText: String, onStatus: (String) -> Unit): TutorReply

    /** Короткое прощание после долгого молчания ученика. */
    suspend fun farewell(onStatus: (String) -> Unit): TutorReply

    /** Забыть историю диалога: следующий ответ — как в начале разговора. */
    fun newConversation()
}

/**
 * Звук одной смысловой группы: PCM 16 бит, моно, [sampleRate] Гц (Realtime — 24 кГц, запасной
 * синтез — сколько даст движок телефона). Играет его [AudioOutput], контроллер внутрь не смотрит.
 */
class SpeechAudio(val pcm: ShortArray, val sampleRate: Int) {
    val durationMs: Long get() = pcm.size * 1000L / sampleRate
}

/**
 * Что вернул синтезатор. [transcript] — что модель, по её же расшифровке, произнесла
 * (у Realtime); `null` — сверить нельзя (Android `TextToSpeech` читает текст как есть).
 */
class SynthesizedSpeech(val audio: SpeechAudio, val transcript: String?)

/** Ошибка синтеза. [unavailable] — синтезатор недоступен целиком (нет сети, 401, 429, сессия). */
class SynthesisException(
    message: String,
    val unavailable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Смысловая группа → звук. */
interface Synthesizer {
    /** Короткое имя для журнала: `realtime`, `android`. */
    val name: String

    /** Подготовиться заранее (открыть сессию, поднять движок), не дожидаясь. */
    fun prepare() {}

    /**
     * Звук группы. Вызывается по одной группе за раз. Ошибка — [SynthesisException]
     * (или любое другое исключение — тогда это ошибка только этой группы).
     */
    suspend fun synthesize(text: String): SynthesizedSpeech

    /** Закрыть сессию и движок (сервис останавливается). */
    fun release() {}
}

/**
 * Звуковые сигналы: ученик занимается, не глядя на экран, поэтому каждое состояние слышно (D13).
 * Когда играть — решает контроллер, как звучит — `AudioOutput` (см. `audio/CueTones.kt`).
 */
enum class Cue {
    /** «Говори» — началась запись ученика (оба режима). */
    LISTEN,
    /** «Обрабатываю» — запись закончена, иду распознавать и думать. */
    PROCESSING,
    /** Тихий «тик» — ученик ждёт учителя дольше 5 с; повторяется каждые 3 с. */
    WAITING,
    /** «Не услышал» — в записи не было речи. */
    NOT_HEARD,
    /** «Ошибка» — сеть, распознавание, микрофон. Следом всегда идёт [END]. */
    ERROR,
    /** «Конец» — круг закончен, ждать нечего. */
    END,
}

/** Воспроизведение. */
interface AudioOutput {
    /**
     * Перед каждым ответом: куда сейчас идёт звук (пишется в журнал) и сколько миллисекунд
     * тишины ставить перед каждой группой (щит Bluetooth, D17). 0 — проводные и динамик, щита нет.
     */
    fun prepareReply(): Long

    /**
     * Играет звук до конца: сначала [shieldMs] тишины (нули), потом сама группа.
     * [onStarted] — в момент запуска воспроизведения (с началом тишины).
     */
    suspend fun play(audio: SpeechAudio, shieldMs: Long, onStarted: () -> Unit = {})

    /**
     * Ставит сигнал в очередь и сразу возвращается. Сигналы звучат по одному, в порядке вызова,
     * не накладываясь. [stop] и [release] их не обрывают: «конец» после «Stop» должен прозвучать.
     */
    fun playCue(cue: Cue)

    /** Ждёт, пока прозвучат все сигналы из очереди. */
    suspend fun awaitCues()

    /** Остановить текущую речь (play завершится). */
    fun stop()

    fun release()
}
