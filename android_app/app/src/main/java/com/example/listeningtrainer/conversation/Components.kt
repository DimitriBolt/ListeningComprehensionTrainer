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
 * Звук одной смысловой группы. Синтезатор отдаёт его, [AudioOutput] умеет его играть;
 * контроллер внутрь не смотрит. Сейчас есть только [SpeechAudioFile] (MP3 от `tts-1`).
 * В S02 сюда добавится поток PCM 24 кГц от Realtime API — новый вариант этого интерфейса
 * и его проигрывание в `AudioOutput`, контроллер не меняется.
 */
sealed interface SpeechAudio

/** Готовый звуковой файл (сейчас MP3). */
data class SpeechAudioFile(val file: File) : SpeechAudio

/** Смысловая группа → звук. */
interface Synthesizer {
    /**
     * Сколько миллисекунд тишины в начале каждого звука (щит Bluetooth). На столько раньше
     * эталонной паузы контроллер запускает следующую группу. 0 — щита в звуке нет.
     */
    val leadInMs: Long

    /** Можно вызывать параллельно для всех групп ответа. */
    suspend fun synthesize(text: String): SpeechAudio
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
    /** Играет звук до конца. [onStarted] — в момент запуска воспроизведения. */
    suspend fun play(audio: SpeechAudio, onStarted: () -> Unit = {})

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
