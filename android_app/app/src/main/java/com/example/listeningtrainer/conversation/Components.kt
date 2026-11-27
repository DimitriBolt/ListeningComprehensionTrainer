package com.example.listeningtrainer.conversation

import java.io.File

// Компоненты под ConversationController (docs/ARCHITECTURE.md, «Целевая структура»).
// Здесь только интерфейсы и данные, без Android-классов. Смена модели или поставщика —
// новая реализация интерфейса, а не правка контроллера.

/**
 * Записанная реплика ученика: [file] — WAV (кнопка «My Voice», копия в debug), [upload] — файл
 * для распознавания (сжатый `.m4a` или тот же WAV).
 * [heardSpeech] — набралось ли в записи речи по [TurnDetector]. Нет — это молчание,
 * распознаватель не вызывается (он «слышит» в тишине фразы вроде `Thank you.`).
 */
data class RecordedAudio(
    val file: File,
    val upload: File = file,
    val durationMs: Long,
    val heardSpeech: Boolean,
    /** Самый громкий кусок записи, дБ (для журнала). */
    val peakDb: Float,
    /** Чем кончилась запись (для журнала). */
    val endedBy: TurnEnd = TurnEnd.BUTTON,
    /** Речь и время вывода детектора за эту запись (для журнала). */
    val turn: TurnStats = TurnStats(),
    /** Сколько мс после конца записи ушло на подготовку [upload] (сжатие) — входит в замер `stt`. */
    val encodeMs: Long = 0,
)

/** Микрофон. */
interface AudioInput {
    /**
     * Записывает одну реплику, пока [turnDetector] не скажет «закончил» или не вызван [finish].
     * [onLevel] получает громкость в дБ (для экрана). Отмена корутины прерывает запись без результата.
     */
    suspend fun record(turnDetector: TurnDetector, onLevel: (Float) -> Unit): RecordedAudio

    /** Ученик сам закончил реплику (кнопка «Stop Recording» или гарнитура): запись завершается и отдаётся. */
    fun finish()
}

/** Чем кончилась запись: тишина после речи (или с начала), кнопка ученика, предохранитель 90 с (D31). */
enum class TurnEnd(val label: String) {
    SILENCE("silence"),
    BUTTON("button"),
    LIMIT("limit"),
}

/** Речь за запись и время вывода модели на кадр (журнал, S03c). */
data class TurnStats(
    val speechMs: Long = 0,
    val frames: Int = 0,
    val avgFrameMs: Double = 0.0,
    val maxFrameMs: Double = 0.0,
)

/** Решает, закончил ли ученик реплику, по звуку записи (S03c: Silero VAD, D64). */
interface TurnDetector {
    /** Начало новой реплики: счёт и состояние модели — с нуля. */
    fun reset()

    /**
     * Очередной кусок звука: [count] отсчётов PCM 16 кГц из [pcm], подряд с прошлым куском.
     * Не `null` — реплика кончилась ([TurnEnd.SILENCE] или [TurnEnd.LIMIT]).
     */
    fun onAudio(pcm: ShortArray, count: Int): TurnEnd?

    /** Набралось ли с начала реплики достаточно речи, чтобы это было не молчание. */
    val heardSpeech: Boolean

    /** Речь и время вывода за реплику. */
    val stats: TurnStats
}

/**
 * Вероятность речи по кадрам звука (Silero VAD). Кадр — [frameSamples] отсчётов 16 кГц; модель
 * помнит состояние между кадрами, [reset] — в начале каждой реплики. Реализация на onnxruntime —
 * [com.example.listeningtrainer.audio.SileroVad], в тестах — подделка.
 */
interface SpeechProbability {
    val frameSamples: Int
    fun reset()
    fun probability(frame: ShortArray): Float
}

/** Звук → текст. [prompt] — короткий контекст для модели ([TranscriptionPrompt]). */
interface Transcriber {
    suspend fun transcribe(audio: RecordedAudio, prompt: String): String
}

/** Источник ответа учителя (сноска поиска `url_citation`): показывается на экране ссылкой, вслух не читается. */
data class TutorSource(val title: String, val url: String)

/**
 * Итог ответа учителя для журнала: токены, поиски, типы источников (`url`, `oai-weather` …),
 * оценка цены в долларах. Без текстов и адресов.
 */
data class TutorStats(
    val inputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val outputTokens: Int = 0,
    val searches: Int = 0,
    val sourceTypes: Map<String, Int> = emptyMap(),
    val costUsd: Double = 0.0,
    /** Ответ обрезан пределом `max_output_tokens` (S02e): незаконченная фраза в конце не звучит. */
    val truncated: Boolean = false,
    /** Ответ оборван на первом вопросе (S02e): остаток потока не читался, токенов сервер не сообщил. */
    val endedAtQuestion: Boolean = false,
)

/** Событие потока ответа учителя (с S02d — Responses API, D36). */
sealed interface TutorEvent {
    /** Очередной кусок текста ответа (сноски поиска могут прийти прямо в тексте). */
    data class Text(val delta: String) : TutorEvent
    /** Учитель начал искать в интернете. */
    data object SearchStarted : TutorEvent
    /** Поиск закончился. */
    data object SearchFinished : TutorEvent
    /** Источник для экрана; один адрес приходит один раз. */
    data class Source(val source: TutorSource) : TutorEvent
    /** Ответ кончился. После него учитель запомнил реплику ученика и свой ответ. */
    data class Done(val stats: TutorStats) : TutorEvent
}

/**
 * Учитель: история диалога, системный промпт, поиск в интернете.
 *
 * Ответ — поток событий [TutorEvent]: [onEvent] вызывается по порядку, в контексте вызывающего.
 * Функция возвращается после [TutorEvent.Done]. Сбой — исключение (`ServiceException` с типом
 * сбоя, без повтора: повторять ли, решает контроллер — только пока ничего не прозвучало).
 * Реплика ученика и ответ попадают в историю только вместе с [TutorEvent.Done]: оборванный ответ
 * не запоминается, и повтор запроса не удваивает реплику.
 */
interface Tutor {
    /**
     * Ответ на реплику ученика. [levelNote] — служебная строка цели длины по уровню ученика (D56): идёт
     * только в этот запрос, в историю не попадает; `null` — без неё.
     */
    suspend fun reply(studentText: String, levelNote: String?, onEvent: suspend (TutorEvent) -> Unit)

    /** Короткое прощание после долгого молчания ученика. */
    suspend fun farewell(onEvent: suspend (TutorEvent) -> Unit)

    /** Последний ответ учителя без сносок (для подсказки распознаванию); `null` — разговор только начался. */
    val lastTeacherText: String?

    /** Забыть историю диалога: следующий ответ — как в начале разговора. */
    fun newConversation()

    /**
     * Последний ответ в истории — так, как он прозвучал (S02f): с поправкой поправщика в начале или без
     * собственной поправки учителя. Если последнего ответа в истории нет — ничего.
     */
    fun replaceLastReply(text: String) {}
}

/** Ответ поправщика: [text] — как пришёл (исправленная фраза или `OK`); [costUsd] — оценка цены. */
data class CorrectorAnswer(val text: String, val costUsd: Double)

/**
 * Поправщик (S02f): отдельный быстрый запрос с одной задачей — исправить грамматику реплики ученика.
 * Идёт одновременно с учителем. Сбой — исключение; без повтора: поправка либо успевает, либо её нет.
 */
interface Corrector {
    suspend fun correct(studentText: String): CorrectorAnswer
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
    /** Тихий «пульс» — идёт запись; каждые 2 с, только в наушниках (D28). */
    PULSE,
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
     * Можно ли сейчас играть «пульс» во время записи (D28): только в наушниках (проводные, USB,
     * Bluetooth). На динамике его услышит микрофон, и запись не закончится. Нельзя — пишет причину в журнал.
     */
    fun listenPulseAllowed(): Boolean

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
