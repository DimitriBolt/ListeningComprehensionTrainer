package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.eventlog.EventLog
import com.example.listeningtrainer.eventlog.NoEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
 * Почему закончился круг диалога. Пишется в журнал на каждом выходе (инвариант 8).
 * После ошибки ([isError]) звучат «ошибка» и «конец», в остальных случаях — только «конец».
 */
enum class EndReason(val description: String, val isError: Boolean = false) {
    REPLY_FINISHED("reply finished, auto-mode off"),
    SILENCE_MANUAL("nothing heard, manual mode"),
    FAREWELL("farewell after silences, auto-mode switched off"),
    RECORDING_ERROR("microphone error", isError = true),
    TRANSCRIPTION_ERROR("transcription error", isError = true),
    TUTOR_ERROR("tutor error", isError = true),
    SPEECH_ERROR("speech synthesis or playback error", isError = true),
    // Команды извне — передаются в stop():
    STOP_BUTTON("Stop button"),
    BACK("Back button"),
    SERVICE_DESTROYED("service destroyed"),
}

/**
 * Машина состояний разговора: Idle → Listening → Thinking → Speaking → (авто-режим: Listening) / Idle.
 * Чистый Kotlin (инвариант 3): компоненты приходят через интерфейсы, время — через [clock].
 *
 * Звуковые сигналы ([Cue]) выбирает контроллер, играет [AudioOutput]:
 * «говори» перед каждой записью, «обрабатываю» после записи с речью, «тик» каждые
 * [waitTickEveryMs], если ученик ждёт учителя дольше [waitTickAfterMs], «не услышал» на молчании,
 * «ошибка» и «конец» на выходе из круга.
 *
 * В [log] пишутся каждый переход состояния с причиной и причина каждого выхода из круга —
 * без ключа и без текстов, только их длины. На каждый круг одна строка замера с источником
 * [TIMING_SOURCE] (в Logcat — тег LCT-Timing): сколько мс от конца записи до текста ученика,
 * до ответа учителя и до запуска первого звука.
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
    private val log: EventLog = NoEventLog,
    /** Пауза между концом речи учителя и сигналом «говори» в авто-режиме. */
    private val autoListenDelayMs: Long = 700L,
    /** Сколько молчаний подряд в авто-режиме — и учитель прощается. */
    private val maxSilences: Int = 3,
    /** Через сколько мс ожидания учителя начинает звучать «тик». */
    private val waitTickAfterMs: Long = 5_000L,
    /** Как часто звучит «тик», пока ученик ждёт. */
    private val waitTickEveryMs: Long = 3_000L,
) {
    private val _ui = MutableStateFlow(ConversationUi())
    val ui: StateFlow<ConversationUi> = _ui.asStateFlow()

    private var job: Job? = null
    private var silenceCount = 0
    /** «Тики» ожидания; дочерняя корутина круга. */
    private var waitTicks: Job? = null

    /** Кнопка «Talk»: начать круг. Если круг уже идёт — ничего не делает. */
    fun startTurn() {
        if (job?.isActive == true) {
            log.event(SOURCE, "Talk ignored: circle already running")
            return
        }
        silenceCount = 0
        job = scope.launch { runCircle() }
    }

    /** Кнопка «Stop Recording»: ученик закончил реплику, запись уходит на распознавание. */
    fun finishRecording() {
        if (_ui.value.state == ConversationState.Listening) {
            log.event(SOURCE, "Stop Recording button")
            input.finish()
        }
    }

    /**
     * Остановить всё: «Stop», «Back», остановка сервиса. Авто-режим выключается (D14).
     * Если шёл круг — его конец пишется в журнал с [reason] и звучит «конец». На «Back» «конец»
     * звучит всегда: разговор закрыт, ждать нечего.
     */
    fun stop(reason: EndReason) {
        val wasActive = job?.isActive == true
        job?.cancel()
        job = null
        waitTicks = null
        output.stop()
        setState(ConversationState.Idle, reason.description) {
            it.copy(status = "Stopped.", currentChunk = "", autoMode = false, levelDb = 0f)
        }
        when {
            wasActive -> endCircle(reason)
            reason == EndReason.BACK -> {
                log.event(SOURCE, "Back while no circle was running: end cue")
                output.playCue(Cue.END)
            }
            else -> log.event(SOURCE, "stop (${reason.description}): no circle was running")
        }
    }

    /** Переключатель авто-режима. Запись сам не запускает: круг начинается с «Talk». */
    fun setAutoMode(enabled: Boolean) {
        if (enabled && !_ui.value.autoMode) silenceCount = 0
        if (enabled != _ui.value.autoMode) log.event(SOURCE, "auto-mode ${if (enabled) "on" else "off"} (switch)")
        _ui.update { it.copy(autoMode = enabled) }
    }

    /** Сервис закрывается. Если круг ещё идёт — он заканчивается с причиной «service destroyed». */
    fun release() {
        stop(EndReason.SERVICE_DESTROYED)
        output.release()
    }

    // ---- круг --------------------------------------------------------------------------------

    private suspend fun runCircle() {
        var why = "Talk button"
        while (true) {
            val end = try {
                runOneTurn(why)
            } finally {
                stopWaitTicks()
            }
            if (end != null) {
                endCircle(end)
                return
            }
            // Авто-режим: пауза, затем «говори» и снова запись.
            delay(autoListenDelayMs)
            why = "auto-mode: next turn"
        }
    }

    /** Конец круга: сигналы и строка в журнале с причиной. */
    private fun endCircle(reason: EndReason) {
        if (reason.isError) output.playCue(Cue.ERROR)
        output.playCue(Cue.END)
        log.event(SOURCE, "circle end: ${reason.name} (${reason.description})")
    }

    /** Один ход. `null` — продолжать (авто-режим), иначе — причина конца круга. */
    private suspend fun runOneTurn(why: String): EndReason? {
        // --- Listening ---
        setState(ConversationState.Listening, why) {
            it.copy(studentText = "", teacherText = "", currentChunk = "")
        }
        // Запись — после сигнала: микрофон его не слышит, и 3 с тишины отсчитываются от конца сигнала.
        output.playCue(Cue.LISTEN)
        output.awaitCues()
        val audio = try {
            input.record(turnDetector) { db -> _ui.update { it.copy(levelDb = db) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(EndReason.RECORDING_ERROR, "Recording error: ${e.localizedMessage}", e)
        }
        val recordedAt = clock()
        _ui.update { it.copy(levelDb = 0f) }
        log.event(
            SOURCE,
            "recording: ${audio.durationMs} ms, above threshold: ${yesNo(audio.heardSpeech)}, peak ${audio.peakDb.toInt()} dB",
        )

        // Молчание определяется по звуку: в записи без речи распознаватель «слышит» `Thank you.`
        // и т. п., поэтому его не вызываем (и не платим за тишину).
        if (!audio.heardSpeech) {
            log.event(SOURCE, "counted as silence: yes (nothing above threshold, transcription skipped)")
            return onSilence()
        }

        // --- Thinking: распознавание ---
        output.playCue(Cue.PROCESSING)
        startWaitTicks()
        setState(ConversationState.Thinking, "recording finished") { it.copy(status = "Transcribing...") }
        val studentText = try {
            transcriber.transcribe(audio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(EndReason.TRANSCRIPTION_ERROR, "Whisper error: ${e.localizedMessage}", e)
        }
        val transcribedAt = clock()
        _ui.update { it.copy(studentText = studentText) }

        // Запасной путь: речь была, а текста нет.
        val silent = studentText.trim().length < 2
        log.event(SOURCE, "transcript: ${studentText.trim().length} chars, counted as silence: ${yesNo(silent)}")
        if (silent) {
            stopWaitTicks()
            return onSilence()
        }
        silenceCount = 0

        answer(recordedAt, transcribedAt) { onStatus -> tutor.reply(studentText, onStatus) }?.let { return it }
        return if (_ui.value.autoMode) null else EndReason.REPLY_FINISHED
    }

    /** Ученик промолчал. `null` — авто-режим, слушать снова. */
    private suspend fun onSilence(): EndReason? {
        silenceCount++
        output.playCue(Cue.NOT_HEARD)
        log.event(SOURCE, "silence $silenceCount in a row (auto-mode: ${yesNo(_ui.value.autoMode)})")
        if (!_ui.value.autoMode) {
            setState(ConversationState.Idle, "nothing heard") { it.copy(status = "Nothing heard.", levelDb = 0f) }
            return EndReason.SILENCE_MANUAL
        }
        if (silenceCount < maxSilences) {
            _ui.update { it.copy(status = "Nothing heard.") }
            return null // авто-режим: «говори» и снова запись
        }

        // Третье молчание подряд: прощание, авто-режим выключается.
        log.event(SOURCE, "auto-mode off: $maxSilences silences in a row")
        _ui.update { it.copy(autoMode = false, status = "Auto-mode: Inactivity timeout.") }
        startWaitTicks()
        val now = clock()
        return answer(recordedAt = now, transcribedAt = now, logTiming = false) { onStatus -> tutor.farewell(onStatus) }
            ?: EndReason.FAREWELL
    }

    /**
     * Ответ учителя: текст → группы → параллельный синтез → воспроизведение по очереди.
     * `null` — прозвучал, иначе — причина ошибки.
     */
    private suspend fun answer(
        recordedAt: Long,
        transcribedAt: Long,
        logTiming: Boolean = true,
        ask: suspend (onStatus: (String) -> Unit) -> TutorReply,
    ): EndReason? {
        setState(ConversationState.Thinking, "asking the teacher") {
            it.copy(teacherText = "", status = "Teacher is thinking... 🧠")
        }
        val reply = try {
            ask { status -> _ui.update { it.copy(status = status) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(EndReason.TUTOR_ERROR, "Error: ${e.localizedMessage}", e)
        }
        val repliedAt = clock()
        _ui.update { it.copy(teacherText = reply.text) }

        val chunks = planner.plan(reply.text)
        log.event(SOURCE, "reply: ${reply.text.length} chars, ${chunks.size} groups")
        if (chunks.isEmpty()) {
            if (logTiming) logTimings(recordedAt, transcribedAt, repliedAt, null)
            setState(ConversationState.Idle, "empty reply") { it.copy(status = "Ready.") }
            return null
        }
        try {
            coroutineScope {
                val audios = chunks.map { chunk -> async { synthesizer.synthesize(chunk.text) } }
                var firstSoundAt: Long? = null
                for (i in chunks.indices) {
                    if (i > 0) startWaitTicks() // долгий синтез посреди ответа — тоже ожидание
                    val audio = audios[i].await()
                    if (i > 0) stopWaitTicks()
                    setState(ConversationState.Speaking, "first group ready") {
                        it.copy(currentChunk = chunks[i].text, status = "Speaking ${i + 1}/${chunks.size} 🔊")
                    }
                    output.play(audio) {
                        if (firstSoundAt == null) {
                            firstSoundAt = clock()
                            stopWaitTicks()
                            if (logTiming) logTimings(recordedAt, transcribedAt, repliedAt, firstSoundAt)
                        }
                    }
                    // Опережающий запуск: в начале звука leadInMs тишины (щит Bluetooth).
                    if (i < chunks.size - 1) delay(maxOf(10L, chunks[i].pauseMs - synthesizer.leadInMs))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(EndReason.SPEECH_ERROR, "Error: ${e.localizedMessage}", e)
        }
        setState(ConversationState.Idle, "reply finished") { it.copy(currentChunk = "", status = "Ready.") }
        return null
    }

    // ---- «тики» ожидания -------------------------------------------------------------------

    /** Пока ученик ждёт учителя: через [waitTickAfterMs] — «тик» каждые [waitTickEveryMs]. */
    private suspend fun startWaitTicks() {
        if (waitTicks?.isActive == true) return
        waitTicks = CoroutineScope(currentCoroutineContext()).launch {
            delay(waitTickAfterMs)
            log.event(SOURCE, "waiting for the teacher longer than $waitTickAfterMs ms: ticks")
            while (true) {
                output.playCue(Cue.WAITING)
                delay(waitTickEveryMs)
            }
        }
    }

    private fun stopWaitTicks() {
        waitTicks?.cancel()
        waitTicks = null
    }

    // ---- состояние и журнал --------------------------------------------------------------

    /** Смена состояния; переход пишется в журнал с причиной. */
    private fun setState(state: ConversationState, reason: String, change: (ConversationUi) -> ConversationUi = { it }) {
        val from = _ui.value.state
        _ui.update { change(it).copy(state = state) }
        if (from != state) log.event(SOURCE, "${stateName(from)} -> ${stateName(state)} ($reason)")
    }

    /** Ошибка: круг заканчивается, авто-режим остаётся как был (как раньше). */
    private fun fail(reason: EndReason, message: String, error: Exception): EndReason {
        stopWaitTicks()
        log.error(SOURCE, reason.description, error)
        setState(ConversationState.Error(message), reason.description) {
            it.copy(status = message, currentChunk = "", levelDb = 0f)
        }
        return reason
    }

    private fun logTimings(recordedAt: Long, transcribedAt: Long, repliedAt: Long, firstSoundAt: Long?) {
        val first = firstSoundAt?.let { (it - recordedAt).toString() } ?: "-"
        log.event(TIMING_SOURCE, "stt=${transcribedAt - recordedAt} tutor=${repliedAt - recordedAt} first_audio=$first")
    }

    /** Имя состояния для журнала. Текст ошибки сюда не идёт — он пишется отдельно, через log.error. */
    private fun stateName(state: ConversationState) = when (state) {
        is ConversationState.Error -> "Error"
        else -> state.toString()
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    companion object {
        private const val SOURCE = "Controller"
        /** Источник строки замера скорости (в Logcat — тег LCT-Timing, как в S01). */
        const val TIMING_SOURCE = "Timing"
    }
}
