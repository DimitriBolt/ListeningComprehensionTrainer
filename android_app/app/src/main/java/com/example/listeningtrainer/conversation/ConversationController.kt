package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.eventlog.EventLog
import com.example.listeningtrainer.eventlog.NoEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Текст учителя без сносок; растёт по мере ответа. */
    val teacherText: String = "",
    /** Источники ответа (поиск в интернете): на экране — ссылками, вслух не читаются (D37). */
    val sources: List<TutorSource> = emptyList(),
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
 * «говори» перед каждой записью, «пульс» каждые [listenPulseEveryMs] всё время записи (только в
 * наушниках, D28), «обрабатываю» после записи с речью, «тик» каждые
 * [waitTickEveryMs], если ученик ждёт учителя дольше [waitTickAfterMs], «не услышал» на молчании,
 * «ошибка» и «конец» на выходе из круга.
 *
 * Ответ учителя приходит потоком (S02d): [StreamingSpeechPlanner] отдаёт группы, как только их
 * текст уже не изменится, [SpeechPipeline] озвучивает их по порядку — [synthesizer] (Realtime) со
 * сверкой, [fallbackSynthesizer] (Android TextToSpeech) — на отдельную группу или на остаток ответа.
 * Пауза после группы берётся, когда группа прозвучала (D38). Пока учитель ищет в интернете и ещё
 * ничего не сказал — заготовленная фраза [searchFiller] голосом учителя (D39).
 * Сбой учителя, пока ничего не прозвучало, — один повтор; когда учитель уже говорит — готовые
 * группы дочитываются, затем «ошибка», фраза голосом телефона и «конец».
 *
 * В [log] пишутся каждый переход состояния с причиной и причина каждого выхода из круга —
 * без ключа и без текстов, только их длины. На каждый круг одна строка замера с источником
 * [TIMING_SOURCE] (в Logcat — тег LCT-Timing): сколько мс от конца записи до текста ученика,
 * до первого куска ответа, до первой группы в синтезе, до конца ответа, до запуска первого звука
 * и до момента, когда голос **слышен** (после щита).
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
    /** Как часто звучит «пульс», пока идёт запись (первый — через столько же после «говори»). */
    private val listenPulseEveryMs: Long = 2_000L,
    /** Сколько групп держать готовыми вперёд. */
    speechLookahead: Int = 3,
    /** Пауза перед единственным повтором запроса к учителю. */
    private val tutorRetryPauseMs: Long = ModelConfig.RETRY_PAUSE_MS,
    /** Фраза на время поиска (D39); `null` — без неё. */
    private val searchFiller: String? = SEARCH_FILLER,
    /** Поправщик (S02f): запрос одновременно с учителем, поправка звучит первой; `null` — без него. */
    private val corrector: Corrector? = null,
    /** Сколько ждать поправку после того, как учитель начал отвечать (первый текст, поиск или конец). */
    private val correctionWaitMs: Long = ModelConfig.CORRECTOR_WAIT_MS,
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
    /** Сколько «пульсов» прозвучало в текущей записи. */
    private var pulseCount = 0
    /** Звук фразы на время поиска: синтезируется один раз за жизнь сервиса (D39). */
    private var fillerAudio: SpeechAudio? = null
    private var fillerJob: Job? = null

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
        return decision.consumed
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
        prepareFiller()
        // Запись — после сигнала: микрофон его не слышит, и 3 с тишины отсчитываются от конца сигнала.
        output.playCue(Cue.LISTEN)
        output.awaitCues()
        val pulses = startListenPulses()
        val audio = try {
            input.record(turnDetector) { db -> _ui.update { it.copy(levelDb = db) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(EndReason.RECORDING_ERROR, "Recording error: ${e.localizedMessage}", e)
        } finally {
            pulses?.cancel() // конец записи любым путём: тишина, кнопка, «Stop Recording», предохранитель
        }
        // Конец записи — до сжатия: время сжатия ученик тоже ждёт, оно входит в `stt` (поле `enc`).
        val recordedAt = clock() - audio.encodeMs
        lastEncodeMs = audio.encodeMs
        _ui.update { it.copy(levelDb = 0f) }
        val skipped = if (audio.pulseMaskedWindows > 0) " (level skipped in ${audio.pulseMaskedWindows} windows)" else ""
        log.event(
            SOURCE,
            "recording: ${audio.durationMs} ms, above threshold: ${yesNo(audio.heardSpeech)}, peak ${audio.peakDb.toInt()} dB, " +
                "pulses: $pulseCount$skipped",
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

        // Поправщик — сразу, одновременно с учителем (S02f).
        val correction = corrector?.let { CorrectionTask(it, studentText, CoroutineScope(currentCoroutineContext())) }
        try {
            answer(recordedAt, transcribedAt, correction = correction) { onEvent -> tutor.reply(studentText, onEvent) }
                ?.let { return it }
        } finally {
            correction?.finish()
        }
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
        return answer(recordedAt = now, transcribedAt = now, logTiming = false) { onEvent -> tutor.farewell(onEvent) }
            ?: EndReason.FAREWELL
    }

    /** Замер одного ответа: моменты времени (часы [clock]) для строки `[Timing]`. */
    private class ReplyTiming {
        var firstToken: Long? = null
        var firstGroup: Long? = null
        var firstSound: Long? = null
        /** Когда зазвучала первая группа самого учителя (не поправки, S02f): после неё повтора нет. */
        var teacherSound: Long? = null
        var searchStart: Long? = null
        var searchEnd: Long? = null
        var repliedAt: Long? = null
        var filler = false
        /** Когда зазвучала фраза на время поиска (D44); `null` — не звучала. */
        var fillerSound: Long? = null
        var chars = 0

        /** Повтор запроса: всё, кроме фразы на время поиска (она не повторяется). */
        fun resetForRetry() {
            firstToken = null; firstGroup = null; searchStart = null; searchEnd = null; repliedAt = null; chars = 0
        }
    }

    /** Ничего не прозвучало, а поток учителя оборвался: ответ бросается целиком. */
    private class AbortReply(val error: Exception) : Exception(error)

    /**
     * Ответ учителя: поток текста → группы → синтез по порядку с опережением → воспроизведение.
     * `null` — прозвучал, иначе — причина ошибки.
     */
    private suspend fun answer(
        recordedAt: Long,
        transcribedAt: Long,
        logTiming: Boolean = true,
        correction: CorrectionTask? = null,
        ask: suspend (onEvent: suspend (TutorEvent) -> Unit) -> Unit,
    ): EndReason? {
        setState(ConversationState.Thinking, "asking the teacher") {
            it.copy(teacherText = "", sources = emptyList(), status = "Teacher is thinking... 🧠")
        }
        // Щит Bluetooth — только если звук сейчас идёт в Bluetooth (выход проверяется перед ответом).
        val shieldMs = output.prepareReply()
        val t = ReplyTiming()
        var attempt = 1
        while (true) {
            val error = try {
                speakReply(t, shieldMs, correction, ask)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return fail(EndReason.SPEECH_ERROR, "Error: ${e.localizedMessage}", e)
            } ?: break
            val kind = (error as? ServiceException)?.kind
            val detail = error.message ?: error.javaClass.simpleName
            if (t.teacherSound == null && attempt == 1 && kind?.retry == true) {
                log.event(SOURCE, "tutor failed ($detail), nothing spoken yet: retry in $tutorRetryPauseMs ms")
                attempt++
                t.resetForRetry()
                _ui.update { it.copy(teacherText = "", sources = emptyList(), status = "Teacher is thinking... 🧠") }
                delay(tutorRetryPauseMs)
                continue
            }
            when {
                t.teacherSound != null -> log.event(SOURCE, "tutor stream broke ($detail) while the teacher was speaking: ready groups spoken")
                attempt > 1 -> log.event(SOURCE, "tutor retry failed ($detail)")
                else -> log.event(SOURCE, "tutor failed ($detail), no retry")
            }
            if (logTiming) logTimings(recordedAt, transcribedAt, t, shieldMs)
            return fail(EndReason.TUTOR_ERROR, "Error: ${error.localizedMessage}", error)
        }
        if (attempt > 1) log.event(SOURCE, "tutor retry succeeded")
        if (logTiming) logTimings(recordedAt, transcribedAt, t, shieldMs)
        setState(ConversationState.Idle, "reply finished") { it.copy(currentChunk = "", status = "Ready.") }
        return null
    }

    /**
     * Один запрос к учителю и озвучка по мере ответа. `null` — ответ прозвучал целиком; иначе —
     * сбой потока учителя (если что-то уже прозвучало — после того, как дочитаны готовые группы).
     * Сбой синтеза — исключение.
     *
     * С поправщиком (S02f) текст учителя копится, пока не решено, будет ли поправка: она готова, или
     * прошло [correctionWaitMs] после того, как учитель начал отвечать. Поправка звучит первой, затем
     * («Let me check.», если учитель ищет и) ответ. Своя поправка учителя в начале ответа тогда не звучит.
     */
    private suspend fun speakReply(
        t: ReplyTiming,
        shieldMs: Long,
        correction: CorrectionTask?,
        ask: suspend (onEvent: suspend (TutorEvent) -> Unit) -> Unit,
    ): Exception? {
        val stream = StreamingSpeechPlanner(planner::plan)
        /** Группы, отданные в синтез, по порядку: i-й звук из конвейера — это sent[i]. */
        val sent = mutableListOf<SpeechChunk>()
        /** Группы поправки поправщика: они первые в [sent]. */
        var prefix: List<SpeechChunk> = emptyList()
        var prefixText = ""
        /** Сколько групп самого учителя отдано в синтез. */
        var teacherSent = 0
        var tutorError: Exception? = null
        var truncated = false
        /** Учитель начал отвечать: первый текст, поиск, конец или сбой — отсюда отсчёт ожидания поправки. */
        val teacherStarted = CompletableDeferred<Unit>()
        /** Решено, будет ли поправка; до этого текст учителя копится в [pending]. */
        val gateOpen = CompletableDeferred<Unit>()
        val pending = StringBuilder()
        /** Поправщик поправил: своя поправка учителя в начале ответа не звучит. Пока не ясно, есть ли она, — [held]. */
        var dropTeacherCorrection = false
        var teacherStartDecided = true
        var teacherCorrectionDropped = false
        val held = StringBuilder()
        /** Группы поправки прозвучали (или их нет): после них — фраза на время поиска. */
        val prefixPlayed = CompletableDeferred<Unit>()
        /** Поток учителя оборвался до его первого звука: доиграть поправку и бросить ответ. */
        var aborting = false
        try {
            coroutineScope {
                val chunks = Channel<SpeechChunk>(Channel.UNLIMITED)
                var filler: Job? = null
                fun send(new: List<SpeechChunk>) {
                    for (c in new) {
                        if (t.firstGroup == null) t.firstGroup = clock()
                        sent += c
                        chunks.trySend(c)
                    }
                }
                fun screen() = listOf(prefixText, stream.screenText()).filter { it.isNotEmpty() }.joinToString(" ")
                fun sendTeacher(new: List<SpeechChunk>) {
                    teacherSent += new.size
                    send(new)
                }
                /** Текст учителя → нарезка (после решения о поправке). */
                fun feedTeacher(delta: String) {
                    if (!teacherStartDecided) {
                        held.append(delta)
                        val end = ReplyText.correctionEnd(held.toString()) ?: return
                        teacherStartDecided = true
                        var rest = held.toString()
                        held.setLength(0)
                        if (end > 0 && dropTeacherCorrection) {
                            rest = rest.substring(end).trimStart()
                            teacherCorrectionDropped = true
                            log.event(SOURCE, "teacher's own correction not spoken: the corrector already corrected")
                        }
                        if (rest.isNotEmpty()) sendTeacher(stream.feed(rest))
                    } else {
                        sendTeacher(stream.feed(delta))
                    }
                    _ui.update { it.copy(teacherText = screen()) }
                }
                if (correction == null) {
                    gateOpen.complete(Unit)
                    prefixPlayed.complete(Unit)
                } else {
                    launch {
                        val fix = correction.decide(teacherStarted)
                        if (fix != null) {
                            prefixText = Correction.phrase(fix)
                            if (!correction.spoken) {
                                prefix = planner.plan(prefixText)
                                send(prefix)
                            }
                            dropTeacherCorrection = true
                            teacherStartDecided = false
                            _ui.update { it.copy(teacherText = screen()) }
                        }
                        if (prefix.isEmpty()) prefixPlayed.complete(Unit)
                        gateOpen.complete(Unit)
                        val p = pending.toString()
                        pending.setLength(0)
                        if (p.isNotEmpty()) feedTeacher(p)
                    }
                }
                launch {
                    try {
                        ask { event ->
                            when (event) {
                                is TutorEvent.Text -> {
                                    if (t.firstToken == null) t.firstToken = clock()
                                    teacherStarted.complete(Unit)
                                    if (gateOpen.isCompleted) feedTeacher(event.delta) else pending.append(event.delta)
                                }
                                TutorEvent.SearchStarted -> {
                                    if (t.searchStart == null) t.searchStart = clock()
                                    teacherStarted.complete(Unit)
                                    _ui.update { it.copy(status = "Searching the web... 🌐") }
                                    // Прозвучавшая поправка фразу не отменяет: важно, что сам учитель ещё молчит.
                                    if (filler == null && teacherSent == 0 && pending.isEmpty() && held.isEmpty() && !t.filler) {
                                        filler = startFiller(this@coroutineScope, shieldMs, t, after = prefixPlayed)
                                    }
                                }
                                TutorEvent.SearchFinished -> t.searchEnd = clock()
                                is TutorEvent.Source -> _ui.update { it.copy(sources = it.sources + event.source) }
                                is TutorEvent.Done -> truncated = event.stats.truncated
                            }
                        }
                        teacherStarted.complete(Unit)
                        gateOpen.await()
                        if (held.isNotEmpty()) {
                            sendTeacher(stream.feed(held.toString()))
                            held.setLength(0)
                        }
                        sendTeacher(stream.finish(dropUnfinished = truncated))
                        t.repliedAt = clock()
                        val teacherText = stream.screenText()
                        val text = screen()
                        t.chars = text.length
                        _ui.update { it.copy(teacherText = text) }
                        if (truncated) log.event(SOURCE, "reply cut by the length limit: unfinished sentence not spoken")
                        // История — как прозвучало (S02f): с поправкой поправщика, без своей поправки учителя.
                        if (prefixText.isNotEmpty() || teacherCorrectionDropped) tutor.replaceLastReply(text)
                        val source = when {
                            prefixText.isNotEmpty() -> "yes (corrector)"
                            (ReplyText.correctionEnd("$teacherText ") ?: 0) > 0 -> "yes (teacher)"
                            else -> "no"
                        }
                        // Мера реплики по «Методике» (S02e): слова, предложения, вопрос в конце, русское слово, поправка.
                        log.event(SOURCE, "reply: ${t.chars} chars, ${sent.size} groups; ${ReplyText.metrics(text)}, correction $source")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        teacherStarted.complete(Unit)
                        tutorError = e
                        t.chars = screen().length // сколько успело прийти
                        log.event(SOURCE, "reply broken off: ${sent.size} groups sent to synthesis")
                        if (t.teacherSound == null) {
                            // Сам учитель ещё не звучал: ответ бросается целиком (повтор решит answer).
                            // Поправка, которая уже звучит, сначала дочитывается — и при повторе не звучит снова.
                            if (gateOpen.isCompleted && prefix.isNotEmpty()) {
                                aborting = true
                                chunks.close()
                                prefixPlayed.await()
                            }
                            throw AbortReply(e)
                        }
                    } finally {
                        chunks.close()
                        if (stream.divergences.isNotEmpty()) {
                            log.event(SOURCE, "stream planning: text of sent groups ${stream.divergences.joinToString(", ")} changed later")
                        }
                    }
                }
                val groups = speech.start(this, chunks)
                var i = 0
                var lastEnd = 0L
                try {
                    while (true) {
                        if (i > 0) startWaitTicks() // долгий синтез посреди ответа — тоже ожидание
                        val next = groups.receiveCatching()
                        if (i > 0) stopWaitTicks()
                        if (next.isClosed) {
                            next.exceptionOrNull()?.let { throw it }
                            break
                        }
                        val audio = next.getOrThrow()
                        if (i > 0) {
                            // Пауза после прошлой группы — по самой свежей нарезке (D38). Опережающий
                            // запуск: в начале звука shieldMs тишины, голос звучит через эталонную паузу.
                            val before = i - 1
                            val pause = if (before < prefix.size) prefix[before].pauseMs else stream.pauseAfter(before - prefix.size)
                            val wait = maxOf(10L, pause - shieldMs) - (clock() - lastEnd)
                            if (wait > 0) delay(wait)
                        }
                        if (i >= prefix.size) {
                            prefixPlayed.complete(Unit)
                            if (aborting) break // учитель оборвался до своего первого звука: дальше не играть
                            filler?.join() // фраза на время поиска не обрывается, ответ — после неё
                        }
                        if (audio.pcm.isNotEmpty()) {
                            // Произносить нечего (группа из одного знака или пустой звук) — только пауза.
                            setState(ConversationState.Speaking, "first group ready") {
                                it.copy(currentChunk = sent[i].text, status = "Speaking ${i + 1} 🔊")
                            }
                            val teacherGroup = i >= prefix.size
                            output.play(audio, shieldMs) {
                                if (t.firstSound == null) {
                                    t.firstSound = clock()
                                    stopWaitTicks()
                                }
                                if (teacherGroup && t.teacherSound == null) t.teacherSound = clock()
                            }
                        }
                        lastEnd = clock()
                        i++
                        if (i == prefix.size) {
                            prefixPlayed.complete(Unit)
                            correction?.spoken = true // повтор запроса учителя не повторяет поправку
                        }
                    }
                } finally {
                    prefixPlayed.complete(Unit)
                }
            }
        } catch (e: AbortReply) {
            return e.error
        }
        return tutorError
    }

    // ---- фраза на время поиска (D39) -------------------------------------------------------

    /** Синтезировать фразу один раз за жизнь сервиса, пока ученик говорит. Не вышло — попробовать в следующий раз. */
    private fun prepareFiller() {
        val text = searchFiller ?: return
        if (fillerAudio != null || fillerJob?.isActive == true) return
        fillerJob = scope.launch {
            val audio = speech.synthesizePrimary(text)
            if (audio != null) {
                fillerAudio = audio
                log.event(SOURCE, "search filler ready: ${audio.durationMs} ms of audio")
            } else {
                log.event(SOURCE, "search filler not ready: will try again next turn")
            }
        }
    }

    /**
     * Учитель начал искать и сам ещё ничего не сказал: фраза голосом учителя — после [after] (поправка
     * прозвучала или её нет). Не готова — пропустить.
     */
    private fun startFiller(scope: CoroutineScope, shieldMs: Long, t: ReplyTiming, after: Deferred<Unit>): Job? {
        if (searchFiller == null) return null
        val audio = fillerAudio
        if (audio == null) {
            log.event(SOURCE, "search started: filler not ready, skipped")
            return null
        }
        t.filler = true
        log.event(SOURCE, "search started, nothing said yet: filler phrase")
        return scope.launch {
            after.await()
            output.play(audio, shieldMs) { if (t.fillerSound == null) t.fillerSound = clock() }
        }
    }

    // ---- поправщик (S02f) ----------------------------------------------------------------

    /** Итог запроса поправщика. */
    private sealed interface CorrectorOutcome {
        val ms: Long
        class Answer(val answer: CorrectorAnswer, override val ms: Long) : CorrectorOutcome
        class Failed(val detail: String, override val ms: Long) : CorrectorOutcome
    }

    /**
     * Поправщик на один ход: запрос уходит сразу, одновременно с учителем. [decide] решает один раз (повтор
     * учителя берёт то же решение). Строка журнала `[Network] corrector: …` — без текстов.
     */
    private inner class CorrectionTask(corrector: Corrector, private val student: String, private val scope: CoroutineScope) {
        private val startedAt = clock()
        private val result: Deferred<CorrectorOutcome> = scope.async {
            try {
                CorrectorOutcome.Answer(corrector.correct(student), clock() - startedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CorrectorOutcome.Failed(e.message ?: e.javaClass.simpleName, clock() - startedAt)
            }
        }
        private var decided = false
        private var fix: String? = null
        /** Поправка уже прозвучала (до сбоя учителя): при повторе запроса она не звучит снова. */
        var spoken = false
        private var lateLogger: Job? = null

        /** Поправка (правильная фраза ученика) или `null`. Ждёт не дольше [correctionWaitMs] после [teacherStarted]. */
        suspend fun decide(teacherStarted: Deferred<Unit>): String? {
            if (decided) return fix
            val outcome = if (result.isCompleted) {
                result.await()
            } else {
                select<CorrectorOutcome?> {
                    result.onAwait { it }
                    teacherStarted.onAwait { null }
                } ?: withTimeoutOrNull(correctionWaitMs) { result.await() }
            }
            decided = true
            if (outcome == null) {
                lateLogger = scope.launch {
                    try {
                        log.event(NETWORK, "corrector: late ${describe(result.await()).first}")
                    } catch (e: CancellationException) {
                        log.event(NETWORK, "corrector: late, no answer in ${clock() - startedAt} ms (turn over)")
                        throw e
                    }
                }
                return null
            }
            val (line, accepted) = describe(outcome)
            log.event(NETWORK, "corrector: $line")
            fix = accepted
            return accepted
        }

        /** Строка журнала (без «corrector: ») и принятая поправка. */
        private fun describe(o: CorrectorOutcome): Pair<String, String?> = when (o) {
            is CorrectorOutcome.Failed -> "failed (${o.detail}) in ${o.ms} ms; cost unknown" to null
            is CorrectorOutcome.Answer -> {
                val cost = "cost \$${"%.4f".format(java.util.Locale.US, o.answer.costUsd)}"
                when (val v = Correction.check(student, o.answer.text)) {
                    is Correction.Verdict.Fix -> "correction in ${o.ms} ms; $cost" to v.text
                    Correction.Verdict.None -> "ok in ${o.ms} ms; $cost" to null
                    is Correction.Verdict.Rejected ->
                        "rejected (similarity ${"%.2f".format(java.util.Locale.US, v.similarity)}) in ${o.ms} ms; $cost" to null
                }
            }
        }

        /** Ход окончен: запрос, который ещё идёт, не нужен. */
        fun finish() {
            if (!decided && !result.isCompleted) log.event(NETWORK, "corrector: not used (no teacher reply)")
            result.cancel()
            lateLogger?.cancel()
        }
    }

    // ---- «пульс» слушания (D28) ------------------------------------------------------------

    /**
     * Пока идёт запись: «пульс» каждые [listenPulseEveryMs], первый — через столько же после «говори».
     * Только в наушниках ([AudioOutput.listenPulseAllowed]); `null` — пульса нет. Строки на каждый
     * пульс нет: число — в строке `recording: …`.
     */
    private suspend fun startListenPulses(): Job? {
        pulseCount = 0
        if (!output.listenPulseAllowed()) return null
        return CoroutineScope(currentCoroutineContext()).launch {
            while (true) {
                delay(listenPulseEveryMs)
                output.playCue(Cue.PULSE)
                pulseCount++
            }
        }
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
     * и `search_ms` — от начала первого поиска до конца последнего. `first_token` — первый кусок
     * текста учителя, `first_group` — первая группа ушла в синтез, `tutor` — конец ответа.
     * `search` — искал ли учитель, `filler` — звучала ли фраза на время поиска, `reply_chars` —
     * длина ответа без сносок (при обрыве — сколько успело прийти). `first_audio` — запуск звука первой группы ответа (не фразы);
     * `audible` — голос ответа слышен (после щита, если он есть); `filler_audio` — слышна фраза на время поиска
     * (D44: цель — первый звук учителя, фраза или ответ). `-` — не было.
     */
    private fun logTimings(recordedAt: Long, transcribedAt: Long, t: ReplyTiming, shieldMs: Long) {
        fun rel(at: Long?) = at?.let { (it - recordedAt).toString() } ?: "-"
        val searchMs = t.searchStart?.let { start -> t.searchEnd?.let { (it - start).toString() } } ?: "-"
        log.event(
            TIMING_SOURCE,
            "enc=$lastEncodeMs stt=${transcribedAt - recordedAt} first_token=${rel(t.firstToken)} " +
                "first_group=${rel(t.firstGroup)} tutor=${rel(t.repliedAt)} search=${yesNo(t.searchStart != null)} " +
                "search_ms=$searchMs filler=${yesNo(t.filler)} reply_chars=${t.chars} " +
                "first_audio=${rel(t.firstSound)} audible=${rel(t.firstSound?.plus(shieldMs))} " +
                "filler_audio=${rel(t.fillerSound?.plus(shieldMs))}",
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
        private const val NETWORK = "Network"
        /** Источник строки замера скорости (в Logcat — тег LCT-Timing, как в S01). */
        const val TIMING_SOURCE = "Timing"
        /** Фраза учителя, пока идёт поиск и он ещё ничего не сказал (D39). */
        const val SEARCH_FILLER = "Let me check."
    }
}
