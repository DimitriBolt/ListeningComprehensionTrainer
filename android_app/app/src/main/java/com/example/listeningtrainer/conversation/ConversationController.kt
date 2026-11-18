package com.example.listeningtrainer.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Состояние разговора. */
sealed interface ConversationState {
    data object Idle : ConversationState
    data object Listening : ConversationState
    data object Thinking : ConversationState
    data object Speaking : ConversationState
    data class Error(val message: String) : ConversationState
}

/** Всё, что показывает экран практики. Экран только показывает это и шлёт команды. */
data class ConversationUi(
    val state: ConversationState = ConversationState.Idle,
    val status: String = "Ready.",
    val studentText: String = "",
    val teacherText: String = "",
    /** Группа, которая звучит сейчас (с `**` для экрана). */
    val currentChunk: String = "",
    val autoMode: Boolean = false,
    /** Громкость микрофона, дБ — пока идёт запись. */
    val levelDb: Float = 0f,
)

/**
 * Машина состояний разговора: Idle → Listening → Thinking → Speaking → (авто-режим: Listening) / Idle.
 * Чистый Kotlin (инвариант 3): компоненты приходят через интерфейсы, время — через [clock].
 *
 * В [timingLog] на каждый круг пишется одна строка (тег LCT-Timing на Android): сколько мс
 * от конца записи до текста ученика, до ответа учителя и до запуска первого звука.
 * Ни ключа, ни текстов в ней нет.
 */
class ConversationController(
    private val scope: CoroutineScope,
    private val input: AudioInput,
    private val turnDetector: TurnDetector,
    private val transcriber: Transcriber,
    private val tutor: Tutor,
    private val planner: SpeechPlanner,
    private val synthesizer: Synthesizer,
    private val output: AudioOutput,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timingLog: (String) -> Unit = {},
    /** Пауза между концом речи учителя и сигналом «твоя очередь» в авто-режиме. */
    private val autoListenDelayMs: Long = 700L,
    /** Сколько молчаний подряд в авто-режиме — и учитель прощается. */
    private val maxSilences: Int = 3,
) {
    private val _ui = MutableStateFlow(ConversationUi())
    val ui: StateFlow<ConversationUi> = _ui.asStateFlow()

    private var job: Job? = null
    private var silenceCount = 0

    /** Кнопка «Talk»: начать круг. Если круг уже идёт — ничего не делает. */
    fun startTurn() {
        if (job?.isActive == true) return
        silenceCount = 0
        job = scope.launch { runTurns() }
    }

    /** Кнопка «Stop Recording»: ученик закончил реплику, запись уходит на распознавание. */
    fun finishRecording() {
        if (_ui.value.state == ConversationState.Listening) input.finish()
    }

    /** Кнопка «Stop» и выход с экрана: остановить всё, авто-режим выключается. */
    fun stop() {
        job?.cancel()
        job = null
        output.stop()
        _ui.update {
            it.copy(state = ConversationState.Idle, status = "Stopped.", currentChunk = "", autoMode = false, levelDb = 0f)
        }
    }

    /** Переключатель авто-режима. Запись сам не запускает: круг начинается с «Talk». */
    fun setAutoMode(enabled: Boolean) {
        if (enabled && !_ui.value.autoMode) silenceCount = 0
        _ui.update { it.copy(autoMode = enabled) }
    }

    fun release() {
        stop()
        output.release()
    }

    private suspend fun runTurns() {
        while (runOneTurn()) {
            // Авто-режим: пауза, сигнал «твоя очередь», снова запись.
            delay(autoListenDelayMs)
            output.playCue(Cue.YOUR_TURN)
        }
    }

    /** Один круг. `true` — продолжать запись (авто-режим). */
    private suspend fun runOneTurn(): Boolean {
        // --- Listening ---
        _ui.update { it.copy(state = ConversationState.Listening, studentText = "", teacherText = "", currentChunk = "") }
        val audio = try {
            input.record(turnDetector) { db -> _ui.update { it.copy(levelDb = db) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail("Recording error: ${e.localizedMessage}")
        }
        val recordedAt = clock()
        _ui.update { it.copy(levelDb = 0f) }

        // --- Thinking: распознавание ---
        _ui.update { it.copy(state = ConversationState.Thinking, status = "Transcribing...") }
        val studentText = try {
            transcriber.transcribe(audio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail("Whisper error: ${e.localizedMessage}")
        }
        val transcribedAt = clock()
        _ui.update { it.copy(studentText = studentText) }

        if (studentText.trim().length < 2) return onSilence()
        silenceCount = 0

        val ok = answer(recordedAt, transcribedAt) { onStatus -> tutor.reply(studentText, onStatus) }
        return ok && _ui.value.autoMode
    }

    /** Ученик промолчал. */
    private suspend fun onSilence(): Boolean {
        silenceCount++
        if (!_ui.value.autoMode) {
            idle("Nothing heard.")
            return false
        }
        if (silenceCount < maxSilences) {
            _ui.update { it.copy(status = "Nothing heard.") }
            return true // авто-режим: сигнал и снова запись
        }

        // Третье молчание подряд: прощание, авто-режим выключается.
        _ui.update { it.copy(autoMode = false, status = "Auto-mode: Inactivity timeout.") }
        val now = clock()
        answer(recordedAt = now, transcribedAt = now, logTiming = false) { onStatus -> tutor.farewell(onStatus) }
        return false
    }

    /** Ответ учителя: текст → группы → параллельный синтез → воспроизведение по очереди. `false` — ошибка. */
    private suspend fun answer(
        recordedAt: Long,
        transcribedAt: Long,
        logTiming: Boolean = true,
        ask: suspend (onStatus: (String) -> Unit) -> TutorReply,
    ): Boolean {
        _ui.update { it.copy(state = ConversationState.Thinking, teacherText = "", status = "Teacher is thinking... 🧠") }
        output.playCue(Cue.PROCESSING)
        try {
            val reply = ask { status -> _ui.update { it.copy(status = status) } }
            val repliedAt = clock()
            _ui.update { it.copy(teacherText = reply.text) }

            val chunks = planner.plan(reply.text)
            if (chunks.isEmpty()) {
                if (logTiming) logTimings(recordedAt, transcribedAt, repliedAt, null)
                idle("Ready.")
                return true
            }
            coroutineScope {
                val audios = chunks.map { chunk -> async { synthesizer.synthesize(chunk.text) } }
                var firstSoundAt: Long? = null
                for (i in chunks.indices) {
                    val audio = audios[i].await()
                    _ui.update {
                        it.copy(
                            state = ConversationState.Speaking,
                            currentChunk = chunks[i].text,
                            status = "Speaking ${i + 1}/${chunks.size} 🔊",
                        )
                    }
                    output.play(audio) {
                        if (firstSoundAt == null) {
                            firstSoundAt = clock()
                            if (logTiming) logTimings(recordedAt, transcribedAt, repliedAt, firstSoundAt)
                        }
                    }
                    // Опережающий запуск: в начале звука leadInMs тишины (щит Bluetooth).
                    if (i < chunks.size - 1) delay(maxOf(10L, chunks[i].pauseMs - synthesizer.leadInMs))
                }
            }
            _ui.update { it.copy(currentChunk = "") }
            idle("Ready.")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Error: ${e.localizedMessage}")
            return false
        }
    }

    private fun logTimings(recordedAt: Long, transcribedAt: Long, repliedAt: Long, firstSoundAt: Long?) {
        val first = firstSoundAt?.let { (it - recordedAt).toString() } ?: "-"
        timingLog("stt=${transcribedAt - recordedAt} tutor=${repliedAt - recordedAt} first_audio=$first")
    }

    private fun idle(status: String) {
        _ui.update { it.copy(state = ConversationState.Idle, status = status, levelDb = 0f) }
    }

    /** Ошибка: круг заканчивается, авто-режим остаётся как был (как раньше). `false` для удобства return. */
    private fun fail(message: String): Boolean {
        _ui.update { it.copy(state = ConversationState.Error(message), status = message, currentChunk = "", levelDb = 0f) }
        return false
    }
}
