package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.eventlog.EventLog
import com.example.listeningtrainer.eventlog.NoEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    SWIPED("app swiped from recents"),
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
 * Ответ учителя озвучивает [SpeechPipeline]: [synthesizer] (Realtime) по порядку групп со сверкой,
 * [fallbackSynthesizer] (Android TextToSpeech) — на отдельную группу или на остаток ответа.
 *
 * В [log] пишутся каждый переход состояния с причиной и причина каждого выхода из круга —
 * без ключа и без текстов, только их длины. На каждый круг одна строка замера с источником
 * [TIMING_SOURCE] (в Logcat — тег LCT-Timing): сколько мс от конца записи до текста ученика,
 * до ответа учителя, до запуска первого звука и до момента, когда голос **слышен** (после щита).
 */
class ConversationController(
    private val scope: CoroutineScope,
    private val input: AudioInput,
    private val turnDetector: TurnDetector,
    private val transcriber: Transcriber,
    private val tutor: Tutor,
    private val planner: SpeechPlanner,
    synthesizer: Synthesizer,
    fallbackSynthesizer: Synthesizer,
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
    /** Сколько групп держать готовыми вперёд. */
    speechLookahead: Int = 3,
) {
    private val speech = SpeechPipeline(synthesizer, fallbackSynthesizer, log, clock, speechLookahead)

    private val _ui = MutableStateFlow(ConversationUi())
    val ui: StateFlow<ConversationUi> = _ui.asStateFlow()

    private var job: Job? = null
    private val headset = HeadsetButton()
    private var silenceCount = 0
    /** Сбой, о котором после «ошибки» скажет голос телефона (тип — из [ServiceException]). */
    private var failure: FailureKind? = null
    /** Время сжатия последней записи — для строки `[Timing]`. */
    private var lastEncodeMs = 0L
    /** Круг уже записан в журнал как законченный, идёт голосовое сообщение об ошибке. */
    private var closing = false
    /** «Тики» ожидания; дочерняя корутина круга. */
    private var waitTicks: Job? = null

    /** Кнопка «Talk» (или средняя кнопка гарнитуры — [why]): начать круг. Если круг уже идёт — ничего не делает. */
    fun startTurn(why: String = "Talk button") {
        if (job?.isActive == true) {
            log.event(SOURCE, "Talk ignored: circle already running")
            return
        }
        silenceCount = 0
        failure = null
        closing = false
        job = scope.launch { runCircle(why) }
    }

    /** Кнопка «Stop Recording»: ученик закончил реплику, запись уходит на распознавание. */
    fun finishRecording() {
        if (_ui.value.state == ConversationState.Listening) {
            log.event(SOURCE, "Stop Recording button")
            input.finish()
        }
    }

    /**
     * Средняя кнопка гарнитуры отпущена (D29): в простое — как «Talk», во время записи — как
     * «Stop Recording», пока учитель думает или говорит — ничего. Каждое нажатие — строка журнала
     * с кодом клавиши. `true` — нажатие наше (система не передаёт его дальше).
     */
    fun headsetButton(keyCode: Int, pressMs: Long): Boolean {
        val decision = headset.onRelease(keyCode, pressMs, _ui.value.state, clock())
        log.event(SOURCE, decision.log)
        when (decision.action) {
            HeadsetButton.Action.TALK -> startTurn("headset button")
            HeadsetButton.Action.FINISH_RECORDING -> input.finish()
            HeadsetButton.Action.NONE -> Unit
        }
        return HeadsetButton.isMiddleButton(keyCode)
    }

    /**
     * Остановить всё: «Stop», «Back», остановка сервиса. Авто-режим выключается (D14).
     * Если шёл круг — его конец пишется в журнал с [reason] и звучит «конец». На «Back» и
     * смахивание из недавних «конец» звучит всегда: разговор закрыт, ждать нечего.
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
            wasActive && closing -> {
                // Конец круга уже в журнале — «Stop» оборвал голосовое сообщение об ошибке.
                log.event(SOURCE, "stop (${reason.description}) during the error message")
                output.playCue(Cue.END)
            }
            wasActive -> endCircle(reason)
            reason == EndReason.BACK || reason == EndReason.SWIPED -> {
                log.event(SOURCE, "${reason.description} while no circle was running: end cue")
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

    /** Кнопка «New conversation»: учитель забывает историю, экран очищается. Только между кругами. */
    fun newConversation() {
        if (job?.isActive == true) {
            log.event(SOURCE, "New conversation ignored: circle is running")
            return
        }
        tutor.newConversation()
        silenceCount = 0
        _ui.update { it.copy(studentText = "", teacherText = "", currentChunk = "", status = "New conversation.") }
        log.event(SOURCE, "New conversation: tutor history cleared")
    }

    /** Сервис стартовал: заранее открыть сессию синтеза, чтобы подключение не попало во время ответа. */
    fun prepare() = speech.prepare()

    /** Сервис закрывается. Если круг ещё идёт — он заканчивается с причиной «service destroyed». */
    fun release() {
        stop(EndReason.SERVICE_DESTROYED)
        output.release()
        speech.release()
    }

    // ---- круг --------------------------------------------------------------------------------

    private suspend fun runCircle(firstWhy: String) {
        var why = firstWhy
        while (true) {
            val end = try {
                runOneTurn(why)
            } finally {
                stopWaitTicks()
            }
            if (end != null) {
                val kind = failure
                if (end.isError && kind?.message != null) endWithMessage(end, kind) else endCircle(end)
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

    /**
     * Конец круга после сетевого сбоя: «ошибка», короткая фраза голосом телефона по типу сбоя
     * (D21), «конец». Голоса телефона нет — только сигналы. Строка `circle end` пишется сразу.
     */
    private suspend fun endWithMessage(reason: EndReason, kind: FailureKind) {
        closing = true
        log.event(SOURCE, "circle end: ${reason.name} (${reason.description}, ${kind.name.lowercase()})")
        output.playCue(Cue.ERROR)
        try {
            val audio = speech.speakFallback(kind.message!!)
            output.awaitCues()
            output.play(audio, output.prepareReply())
            log.event(SOURCE, "error message spoken: ${kind.name.lowercase()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.event(SOURCE, "error message not spoken (${e.message ?: e.javaClass.simpleName}): cues only")
        }
        output.playCue(Cue.END)
        closing = false
    }

    /** Один ход. `null` — продолжать (авто-режим), иначе — причина конца круга. */
    private suspend fun runOneTurn(why: String): EndReason? {
        // --- Listening ---
        setState(ConversationState.Listening, why) {
            it.copy(studentText = "", teacherText = "", currentChunk = "")
        }
        // Сессия синтеза — заранее, пока ученик говорит (если оборвалась — переподключится сейчас).
        speech.prepare()
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
        // Конец записи — до сжатия: время сжатия ученик тоже ждёт, оно входит в `stt` (поле `enc`).
        val recordedAt = clock() - audio.encodeMs
        lastEncodeMs = audio.encodeMs
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
            transcriber.transcribe(
                audio,
                TranscriptionPrompt.build(tutor.lastTeacherText, ModelConfig.TRANSCRIBE_PROMPT_TEACHER_CHARS),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(EndReason.TRANSCRIPTION_ERROR, "Transcription error: ${e.localizedMessage}", e)
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
     * Ответ учителя: текст → группы → синтез по порядку с опережением → воспроизведение по очереди.
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
            if (logTiming) logTimings(recordedAt, transcribedAt, repliedAt, reply, null, 0)
            setState(ConversationState.Idle, "empty reply") { it.copy(status = "Ready.") }
            return null
        }
        try {
            coroutineScope {
                // Щит Bluetooth — только если звук сейчас идёт в Bluetooth (выход проверяется перед ответом).
                val shieldMs = output.prepareReply()
                val groups = speech.start(this, chunks)
                var firstSoundAt: Long? = null
                for (i in chunks.indices) {
                    if (i > 0) startWaitTicks() // долгий синтез посреди ответа — тоже ожидание
                    val audio = groups.receive()
                    if (i > 0) stopWaitTicks()
                    if (audio.pcm.isEmpty()) {
                        // Произносить нечего (группа из одного знака или пустой звук) — только пауза.
                        if (i < chunks.size - 1) delay(chunks[i].pauseMs)
                        continue
                    }
                    setState(ConversationState.Speaking, "first group ready") {
                        it.copy(currentChunk = chunks[i].text, status = "Speaking ${i + 1}/${chunks.size} 🔊")
                    }
                    output.play(audio, shieldMs) {
                        if (firstSoundAt == null) {
                            firstSoundAt = clock()
                            stopWaitTicks()
                            if (logTiming) logTimings(recordedAt, transcribedAt, repliedAt, reply, firstSoundAt, shieldMs)
                        }
                    }
                    // Опережающий запуск: в начале звука shieldMs тишины, голос звучит через эталонную паузу.
                    if (i < chunks.size - 1) delay(maxOf(10L, chunks[i].pauseMs - shieldMs))
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
        failure = (error as? ServiceException)?.kind
        log.error(SOURCE, reason.description, error)
        setState(ConversationState.Error(message), reason.description) {
            it.copy(status = message, currentChunk = "", levelDb = 0f)
        }
        return reason
    }

    /**
     * Строка замера. Все времена — мс от конца записи, кроме `enc` — сжатия записи (входит в `stt`)
     * и `llm1` — длительности первого запроса к учителю. `search` — вызывал ли учитель инструмент, `reply_chars` — длина ответа.
     * `first_audio` — запуск звука первой группы; `audible` — голос слышен (после щита, если он есть).
     */
    private fun logTimings(
        recordedAt: Long, transcribedAt: Long, repliedAt: Long, reply: TutorReply, firstSoundAt: Long?, shieldMs: Long,
    ) {
        val first = firstSoundAt?.let { (it - recordedAt).toString() } ?: "-"
        val audible = firstSoundAt?.let { (it + shieldMs - recordedAt).toString() } ?: "-"
        log.event(
            TIMING_SOURCE,
            "enc=$lastEncodeMs stt=${transcribedAt - recordedAt} tutor=${repliedAt - recordedAt} llm1=${reply.firstRequestMs ?: "-"} " +
                "search=${yesNo(reply.searched)} reply_chars=${reply.text.length} first_audio=$first audible=$audible",
        )
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
