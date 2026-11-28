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
import kotlinx.coroutines.awaitCancellation
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
    /** Уровень ученика (S02h, D56): цель длины — служебной строкой в запрос; `null` — без неё. */
    private val studentLevel: StudentLevel? = null,
    /**
     * Потоковое распознавание во время записи (S03d, D67); `null` — только файл. Сбой потока — текст из файла
     * ([transcriber]) нынешним путём, без повтора потока.
     */
    private val liveTranscriber: LiveTranscriber? = null,
    /**
     * Сверка (только debug, S03d п. 3): для каждой реплики параллельно идёт и файл; в журнал — похожесть,
     * тексты — в [onLiveVsFile] (файл на телефоне, не журнал).
     */
    private val compareLiveWithFile: Boolean = false,
    private val onLiveVsFile: ((live: String, file: String, similarity: Double) -> Unit)? = null,
    /**
     * Перебивание (S05, D81): Silero со своим состоянием для микрофона, пока звучит учитель; `null` — микрофон во время
     * речи учителя закрыт, как до S05.
     */
    private val bargeInVad: SpeechProbability? = null,
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
    /** Откуда текст последней реплики — для строки `[Timing]`: `live` (поток) или `file`. */
    private var lastSttPath = STT_FILE
    /** Чем кончилась последняя запись и тишина от речи до конца (S04: `ended_by`, `end_wait` в `[Timing]`). */
    private var lastEndedBy = TurnEnd.BUTTON
    private var lastEndWaitMs = 0L

    private val _partialText = MutableStateFlow("")
    /**
     * Частичный текст текущей реплики из потока распознавания (S03d; понадобится S04, чтобы понять, закончена
     * ли мысль). С начала каждой записи — пустой. Не показывается и в журнал не пишется.
     */
    val partialText: StateFlow<String> = _partialText.asStateFlow()
    /** Круг уже записан в журнал как законченный, идёт голосовое сообщение об ошибке. */
    private var closing = false
    /** «Тики» ожидания; дочерняя корутина круга. */
    private var waitTicks: Job? = null
    /** Сколько «пульсов» прозвучало в текущей записи. */
    private var pulseCount = 0
    /** Звук фразы на время поиска: синтезируется один раз за жизнь сервиса (D39). */
    private var fillerAudio: SpeechAudio? = null
    private var fillerJob: Job? = null
    /** Последняя реплика учителя — для «Again» и «Slower» (S05, D82): группы, паузы и звук, что успел синтезироваться. */
    private var lastReply: ReplyCache? = null
    /** Слушание микрофона, пока звучит учитель (S05); ставится на время ответа или повтора. */
    @Volatile
    private var currentListen: TeacherListen? = null

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
        lastReply = null
        silenceCount = 0
        _ui.update { it.copy(studentText = "", teacherText = "", currentChunk = "", status = "New conversation.") }
        log.event(SOURCE, "New conversation: tutor history cleared")
    }

    /** Сервис стартовал: заранее открыть сессии синтеза и распознавания, чтобы подключение не попало во время ответа. */
    fun prepare() {
        speech.prepare()
        liveTranscriber?.prepare()
    }

    /** Сервис закрывается. Если круг ещё идёт — он заканчивается с причиной «service destroyed». */
    fun release() {
        stop(EndReason.SERVICE_DESTROYED)
        output.release()
        speech.release()
        liveTranscriber?.release()
    }

    // ---- круг --------------------------------------------------------------------------------

    private suspend fun runCircle(firstWhy: String) {
        var why = firstWhy
        var barged: BargeStart? = null
        while (true) {
            val next = try {
                runOneTurn(why, barged)
            } finally {
                stopWaitTicks()
            }
            barged = null
            when (next) {
                // Ученик перебил учителя (S05): запись уже идёт — сразу следующий ход, без паузы и без «говори».
                is Next.Barged -> {
                    barged = next.start
                    why = "student barged in"
                    continue
                }
                // Команда «Stop» или повторять нечего (S05): «говори» и слушание в любом режиме.
                Next.Listen -> why = "voice command: listening"
                is Next.End -> {
                    val end = next.reason
                    if (end != null) {
                        val kind = failure
                        if (end.isError && kind?.message != null) endWithMessage(end, kind) else endCircle(end)
                        return
                    }
                    why = "auto-mode: next turn"
                }
            }
            // Пауза, затем «говори» и снова запись.
            delay(autoListenDelayMs)
        }
    }

    /** Что после хода (S05): конец круга или авто-режим ([End]), слушать снова ([Listen]), ученик перебил ([Barged]). */
    private sealed interface Next {
        /** `null` — авто-режим, следующий ход; иначе — конец круга. */
        data class End(val reason: EndReason?) : Next
        data object Listen : Next
        class Barged(val start: BargeStart) : Next
    }

    /** Запись, начатая перебиванием (S05): её поток распознавания, `prompt` и реплика (новый ход). */
    private class BargeStart(
        val recording: Deferred<RecordedAudio>,
        val live: LiveTurn?,
        val prompt: String,
        val utterance: Utterance,
    )

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

    /**
     * Один ход. [barged] — ученик перебил учителя (S05): запись уже идёт, без «говори». Итог — [Next].
     */
    private suspend fun runOneTurn(why: String, barged: BargeStart? = null): Next {
        // --- Listening ---
        setState(ConversationState.Listening, why) {
            it.copy(studentText = "", teacherText = "", currentChunk = "")
        }
        // Сессии синтеза и распознавания — заранее, пока звучит «говори» и ученик говорит (если оборвались —
        // переподключатся сейчас).
        speech.prepare()
        liveTranscriber?.prepare()
        prepareFiller()
        val prompt: String
        val utterance: Utterance
        val live: LiveTurn?
        val pulses: Job?
        val audio = if (barged != null) {
            prompt = barged.prompt
            utterance = barged.utterance
            live = barged.live
            pulses = startListenPulses()
            try {
                barged.recording.await()
            } catch (e: CancellationException) {
                live?.cancel()
                throw e
            } catch (e: Exception) {
                live?.cancel()
                return Next.End(fail(EndReason.RECORDING_ERROR, "Recording error: ${e.localizedMessage}", e))
            } finally {
                pulses?.cancel()
            }
        } else {
            // Контекст распознавания — последнее предложение учителя; один и тот же для потока и файла.
            prompt = TranscriptionPrompt.build(tutor.lastTeacherText, ModelConfig.TRANSCRIBE_PROMPT_TEACHER_CHARS)
            _partialText.value = ""
            utterance = Utterance()
            // Запись — после сигнала: микрофон его не слышит, и тишина отсчитывается от конца сигнала.
            output.playCue(Cue.LISTEN)
            output.awaitCues()
            // Поток распознавания — с первого кадра записи (S03d).
            live = liveTranscriber?.begin(prompt) { partial -> _partialText.value = partial }
            turnDetector.setContext(turnContext(utterance, continuation = false))
            pulses = startListenPulses()
            try {
                input.record(turnDetector, live) { db -> _ui.update { it.copy(levelDb = db) } }
            } catch (e: CancellationException) {
                live?.cancel()
                throw e
            } catch (e: Exception) {
                live?.cancel()
                return Next.End(fail(EndReason.RECORDING_ERROR, "Recording error: ${e.localizedMessage}", e))
            } finally {
                pulses?.cancel() // конец записи любым путём: тишина, судья, кнопка, «Stop Recording», предохранитель
            }
        }
        logRecording(audio)

        // Молчание определяется по звуку: в записи без речи распознаватель «слышит» `Thank you.`
        // и т. п., поэтому его не вызываем (и не платим за тишину).
        if (!audio.heardSpeech) {
            // Поток уже очищен (`clear` в конце записи): распознавать нечего.
            log.event(SOURCE, "counted as silence: yes (speech under ${ModelConfig.TURN_MIN_SPEECH_MS} ms, transcription skipped)")
            return Next.End(onSilence())
        }

        var segment = Segment(audio, live, prompt)
        while (true) {
            // --- Thinking: распознавание и ответ; ученик может продолжить до первого звука учителя (S04) ---
            output.playCue(Cue.PROCESSING)
            startWaitTicks()
            setState(ConversationState.Thinking, "recording finished") { it.copy(status = "Transcribing...") }
            utterance.partials += _partialText.value
            _partialText.value = ""
            val resumable = segment.audio.endedBy == TurnEnd.JUDGE || segment.audio.endedBy == TurnEnd.LONG_SILENCE
            val watch = if (resumable) ResumeWatch(utterance, prompt, CoroutineScope(currentCoroutineContext())) else null
            currentWatch = watch
            val outcome = try {
                respond(segment, utterance, watch)
            } finally {
                currentWatch = null
            }
            if (outcome !is TurnOutcome.Resumed && outcome !is TurnOutcome.Continued) {
                watch?.close()
                return when (outcome) {
                    is TurnOutcome.End -> Next.End(outcome.reason)
                    is TurnOutcome.Barged -> Next.Barged(outcome.start)
                    is TurnOutcome.Command -> runCommand(outcome.command)
                    is TurnOutcome.Resumed, is TurnOutcome.Continued -> error("unreachable")
                }
            }
            // Ученик продолжил: та же реплика, снова слушание с пульсом, без нового «говори». До голоса учителя (S04) —
            // запись-продолжение; в первые 3 с голоса учителя после конца по судье (S05b, D86) — запись перебивания.
            stopWaitTicks()
            val continued = outcome as? TurnOutcome.Continued
            if (continued != null) watch?.close()
            setState(ConversationState.Listening, if (continued != null) "student went on over the teacher" else "student went on after the end") {
                it.copy(status = "Listening...")
            }
            val more = startListenPulses()
            val next = try {
                (continued?.start?.recording ?: watch!!.recording).await()
            } catch (e: CancellationException) {
                continued?.start?.live?.cancel()
                throw e
            } catch (e: Exception) {
                continued?.start?.live?.cancel()
                return Next.End(fail(EndReason.RECORDING_ERROR, "Recording error: ${e.localizedMessage}", e))
            } finally {
                more?.cancel()
            }
            logRecording(next)
            segment = if (continued != null) {
                Segment(next, continued.start.live, continued.start.prompt)
            } else {
                Segment(next, watch!!.live, watch.prompt)
            }
        }
    }

    /** Строка журнала о записи (без текстов). */
    private fun logRecording(audio: RecordedAudio) {
        lastEndedBy = audio.endedBy
        lastEndWaitMs = audio.turn.endWaitMs
        val t = audio.turn
        log.event(
            SOURCE,
            "recording: ${audio.durationMs} ms, speech ${t.speechMs} ms (silero), ended by ${audio.endedBy.label}, " +
                "peak ${audio.peakDb.toInt()} dB, vad frames ${t.frames}, avg ${ms1(t.avgFrameMs)} ms, max ${ms1(t.maxFrameMs)} ms, " +
                "pulses: $pulseCount",
        )
        if (audio.endedBy == TurnEnd.LONG_SILENCE) log.event(TURN, "pause ${t.endWaitMs} ms: limit → end")
    }

    /** Контекст детектора на одну запись: текст для судьи — всё, что уже сказано в реплике, и частичный текст куска. */
    private fun turnContext(utterance: Utterance, continuation: Boolean, onHeardSpeech: () -> Unit = {}) = TurnContext(
        text = { (utterance.partials + _partialText.value).filter { it.isNotBlank() }.joinToString(" ") },
        continuation = continuation,
        onHeardSpeech = onHeardSpeech,
        onDecision = { d ->
            log.event(
                TURN,
                "pause ${d.pauseMs} ms: judge ${d.judge} ${if (d.complete) "complete" else "incomplete"} (${d.judgeMs} ms) → " +
                    if (d.end) "end" else "wait",
            )
        },
    )

    /** Кусок реплики: запись, её поток и `prompt` (S04: после отмены реплика идёт дальше новым куском). */
    private class Segment(val audio: RecordedAudio, val live: LiveTurn?, val prompt: String)

    /** Что уже сказано в реплике (S04): тексты кусков (для учителя) и их частичный текст (для судьи). */
    private class Utterance {
        val texts = mutableListOf<String>()
        val partials = mutableListOf<String>()
        val text: String get() = texts.filter { it.isNotBlank() }.joinToString(" ")
    }

    private sealed interface TurnOutcome {
        class End(val reason: EndReason?) : TurnOutcome
        class Resumed(val afterEndMs: Long) : TurnOutcome
        /** Ученик перебил учителя (S05): следующий ход — эта запись. */
        class Barged(val start: BargeStart) : TurnOutcome
        /** Реплика — команда голосом (S05, D82): учителю не уходит. */
        class Command(val command: VoiceCommand) : TurnOutcome
        /** Ученик перебил начало ответа, продолжая мысль (S05b, D86): эта запись — следующий кусок той же реплики. */
        class Continued(val start: BargeStart) : TurnOutcome
    }

    /** Наблюдение за продолжением, пока идёт ответ (S04); ставится на время ответа, звук учителя его закрывает. */
    @Volatile
    private var currentWatch: ResumeWatch? = null

    /**
     * После объявленного конца микрофон и Silero слушают дальше — до первого звука учителя (поправка, «Let me check.»
     * или ответ). Запись-продолжение — в свои файлы, со своим потоком распознавания. Ученик заговорил (речь ≥ 200 мс)
     * раньше учителя — [resumed]: ответ отменяется, а эта запись становится следующим куском реплики. Учитель успел —
     * [claimSound] её бросает.
     */
    private inner class ResumeWatch(utterance: Utterance, basePrompt: String, scope: CoroutineScope) {
        private val lock = Any()
        private var closed = false
        private val endedAt = clock()
        val resumed = CompletableDeferred<Long>()
        val prompt = TranscriptionPrompt.continued(basePrompt, utterance.partials.filter { it.isNotBlank() }.joinToString(" "))
        val live: LiveTurn? = liveTranscriber?.begin(prompt) { partial -> _partialText.value = partial }
        val recording: Deferred<RecordedAudio> = scope.async {
            turnDetector.setContext(turnContext(utterance, continuation = true, onHeardSpeech = ::onSpeech))
            input.recordContinuation(turnDetector, live) { db -> _ui.update { it.copy(levelDb = db) } }
        }

        private fun onSpeech() = synchronized(lock) {
            if (!closed) resumed.complete(clock() - endedAt)
        }

        /** Перед первым звуком учителя: `true` — ученик молчит, запись-продолжение брошена, звук можно. */
        fun claimSound(): Boolean = synchronized(lock) {
            if (resumed.isCompleted) return false
            if (!closed) {
                closed = true
                recording.cancel()
                live?.cancel()
            }
            true
        }

        /** Ход кончился без продолжения (ответ, ошибка, молчание): запись-продолжение не нужна. */
        suspend fun close() {
            synchronized(lock) {
                closed = true
                if (!resumed.isCompleted) {
                    recording.cancel()
                    live?.cancel()
                }
            }
            recording.join()
        }
    }

    /**
     * Звучит учитель (поправка, фраза поиска, ответ, повтор): если ученик уже продолжил (S04) или перебил (S05) — не
     * звучать (ответ отменяется). Первый звук открывает микрофон для перебивания (S05).
     */
    private suspend fun beforeTeacherSound() {
        val listen = currentListen
        if (listen?.halted == true) awaitCancellation()
        val watch = currentWatch
        if (watch != null && !watch.claimSound()) awaitCancellation()
        listen?.start(after = watch?.recording)
    }

    /** Текст куска, ответ на всю реплику; ученик продолжил раньше голоса учителя — [TurnOutcome.Resumed]. */
    private suspend fun respond(segment: Segment, utterance: Utterance, watch: ResumeWatch?): TurnOutcome {
        val audio = segment.audio
        // Конец записи — до сжатия: время сжатия ученик тоже ждёт, оно входит в `stt` (поле `enc`).
        val recordedAt = clock() - audio.encodeMs
        lastEncodeMs = audio.encodeMs
        _ui.update { it.copy(levelDb = 0f) }
        // Сверка в debug: файл — параллельно с потоком, в области сервиса (круг не ждёт его конца).
        val fileJob = if (segment.live != null && compareLiveWithFile) {
            scope.async { runCatching { transcriber.transcribe(audio, segment.prompt) } }
        } else {
            null
        }
        val segmentText = try {
            if (segment.live == null) {
                lastSttPath = STT_FILE
                transcriber.transcribe(audio, segment.prompt)
            } else {
                studentTextLive(segment.live, audio, segment.prompt, fileJob)
            }
        } catch (e: CancellationException) {
            fileJob?.cancel()
            throw e
        } catch (e: Exception) {
            return TurnOutcome.End(fail(EndReason.TRANSCRIPTION_ERROR, "Transcription error: ${e.localizedMessage}", e))
        }
        val transcribedAt = clock()
        utterance.texts += segmentText.trim()
        val studentText = utterance.text
        _ui.update { it.copy(studentText = studentText) }

        // Запасной путь: речь была, а текста нет.
        val silent = studentText.trim().length < 2
        log.event(SOURCE, "transcript: ${studentText.trim().length} chars, counted as silence: ${yesNo(silent)}")
        if (silent) {
            stopWaitTicks()
            return TurnOutcome.End(onSilence())
        }
        watch?.resumed?.takeIf { it.isCompleted }?.let { return resumedOutcome(it.getCompleted(), correction = false) }
        silenceCount = 0
        // Команда голосом (S05, D82): учителю, поправщику и в окно уровня не идёт.
        VoiceCommand.of(studentText)?.let { return TurnOutcome.Command(it) }

        // Реплика дошла до учителя: она идёт в окно уровня (D56) один раз — когда ответ не отменён (S04);
        // повтор запроса её не считает.
        val levelStep = studentLevel?.preview(studentText)
        val levelNote = levelStep?.let { studentLevel?.note(it.state.level) }
        if (watch == null) levelStep?.let { countTurn(it) }

        val progress = ReplyProgress()
        // Кэш повтора до этого хода: ход отменён (S04 или D86) — «Again» повторяет прошлую реплику, а не отменённый ответ.
        val replyBefore = lastReply
        // Конец по судье (или по пределу тишины): перебивание в первые 3 с голоса учителя — продолжение мысли (D86).
        val goOn = if (watch != null) {
            GoOn(utterance) { TranscriptionPrompt.continued(segment.prompt, utterance.partials.filter { it.isNotBlank() }.joinToString(" ")) }
        } else {
            null
        }
        val listen = newListen(goOn) { TranscriptionPrompt.build(progress.spokenText().ifBlank { null } ?: tutor.lastTeacherText, ModelConfig.TRANSCRIBE_PROMPT_TEACHER_CHARS) }
        currentListen = listen
        try {
            return coroutineScope {
                var correction: CorrectionTask? = null
                val reply = async {
                    // Поправщик — сразу, одновременно с учителем (S02f).
                    correction = corrector?.let { CorrectionTask(it, studentText, this) }
                    try {
                        answer(recordedAt, transcribedAt, correction = correction, progress = progress) { onEvent ->
                            tutor.reply(studentText, levelNote, onEvent)
                        }?.let { return@async it }
                    } finally {
                        correction?.finish()
                    }
                    if (_ui.value.autoMode) null else EndReason.REPLY_FINISHED
                }
                val first = select<Any?> {
                    reply.onAwait { it }
                    watch?.resumed?.onAwait { ResumedAt(it) }
                    listen?.bargedIn?.onAwait { it }
                    listen?.commandHeard?.onAwait { it }
                }
                when {
                    first is ResumedAt -> {
                        correction?.cancelled()
                        reply.cancel()
                        reply.join()
                        // Ответ мог уже дойти целиком (`completed`, синтез первой группы ещё шёл): пара «первая часть —
                        // ответ» убирается из истории, иначе первая часть уйдёт учителю дважды (S05c).
                        tutor.withdrawn(studentText)
                        lastReply = replyBefore
                        resumedOutcome(first.afterMs, correction = correction != null)
                    }
                    first is VoiceCommand -> {
                        // Короткий звук посреди ответа — команда (S05b, D85): учитель уже замолк, как при перебивании.
                        reply.cancel()
                        reply.join()
                        listen!!.stop()
                        if (watch != null) levelStep?.let { countTurn(it) }
                        tutor.interrupted(studentText, BargeIn.marked(progress.spokenText()))
                        log.event(TURN, "short sound → command ${first.label}: stopped, spoken ${progress.started} of ${progress.total()} groups")
                        TurnOutcome.Command(first)
                    }
                    first is BargeInHit || listen?.hit == true -> {
                        // Ученик перебил (S05): учитель уже замолк, остальное ответа отменяется.
                        val hit = first as? BargeInHit ?: listen!!.bargedIn.await()
                        reply.cancel()
                        reply.join()
                        if (listen!!.continuation) {
                            // Продолжение мысли (D86): ход отменён целиком, как отмена S04; реплика посчитается в окне
                            // уровня один раз — склеенной.
                            correction?.cancelled()
                            tutor.withdrawn(studentText)
                            lastReply = replyBefore
                            TurnOutcome.Continued(bargedIn(listen, hit, progress.started, progress.total()))
                        } else {
                            if (watch != null) levelStep?.let { countTurn(it) }
                            // История: прозвучавшая часть с пометкой; ничего не прозвучало — ответа нет (D81).
                            tutor.interrupted(studentText, BargeIn.marked(progress.spokenText()))
                            TurnOutcome.Barged(bargedIn(listen, hit, progress.started, progress.total()))
                        }
                    }
                    else -> {
                        listen?.stop()
                        if (watch != null) levelStep?.let { countTurn(it) }
                        @Suppress("UNCHECKED_CAST")
                        TurnOutcome.End(first as EndReason?)
                    }
                }
            }
        } finally {
            currentListen = null
            listen?.release()
        }
    }

    private class ResumedAt(val afterMs: Long)

    /** Что из ответа успело прозвучать (S05): для истории с пометкой и строки журнала перебивания. */
    private class ReplyProgress {
        /** Группы, отданные в синтез, по порядку (как `sent` в [speakReply]). */
        var groups: List<SpeechChunk> = emptyList()
        /** Сколько групп начало звучать. */
        var started = 0

        fun total() = groups.size

        /** Текст групп, которые начали звучать, без разметки экрана. */
        fun spokenText(): String =
            groups.take(started).joinToString(" ") { it.text.replace("**", "").trim() }.replace(SPACES, " ").trim()
    }

    // ---- перебивание (S05, D81) ----------------------------------------------------------

    /** Слушание на время ответа или повтора; `null` — без перебивания (нет [bargeInVad]). */
    private suspend fun newListen(goOn: GoOn? = null, prompt: () -> String): TeacherListen? =
        bargeInVad?.let { TeacherListen(it, CoroutineScope(currentCoroutineContext()), prompt, goOn) }

    /**
     * Реплика, которую ученик может продолжить поверх начала ответа (S05b, D86): конец был по судье или по пределу
     * тишины. [prompt] — подсказка распознаванию куска-продолжения (уже сказанное, как у отмены S04).
     */
    private class GoOn(val utterance: Utterance, val prompt: () -> String)

    /**
     * Микрофон, пока звучит учитель (S05). Открывается первым звуком учителя ([start]); каждый кусок — в
     * [BargeInDetector]. Ученик перебил — [onHit] (из потока записи): учитель замолкает сразу, поток распознавания
     * новой реплики начинается со звука до решения, [bargedIn] — контроллеру. Учитель договорил — [stop].
     */
    private inner class TeacherListen(
        vad: SpeechProbability,
        private val scope: CoroutineScope,
        private val prompt: () -> String,
        private val goOn: GoOn? = null,
    ) {
        private val detector = BargeInDetector(vad, muted = output::cueSounding).also { it.onCandidate = ::onCandidate }
        val bargedIn = CompletableDeferred<BargeInHit>()
        /** Команда из короткого звука (S05b, D85): учитель уже замолк. */
        val commandHeard = CompletableDeferred<VoiceCommand>()
        private val lock = Any()
        /** Перебил: ставится до того, как учитель замолк, — ни одна группа после этого не зазвучит. */
        @Volatile
        var hit = false
            private set
        /** Учитель замолк: перебил ([hit]) или команда коротким звуком (D85). Ни одна группа больше не зазвучит. */
        @Volatile
        var halted = false
            private set
        /** Ход ещё идёт: итог распознавания короткого звука после конца хода ничего не делает. */
        @Volatile
        private var active = true
        private val candidateBusy = java.util.concurrent.atomic.AtomicBoolean(false)
        private val candidates = java.util.concurrent.atomic.AtomicInteger(0)
        private val skipped = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile
        private var commands = 0
        @Volatile
        private var candidateCostUsd = 0.0
        private var recording: Deferred<RecordedAudio>? = null
        var utterance = Utterance()
            private set
        /** Перебивание — продолжение мысли (D86): в первые [ModelConfig.BARGE_IN_J2_MISS_MS] голоса учителя после конца по судье. */
        @Volatile
        var continuation = false
            private set
        var live: LiveTurn? = null
            private set
        var livePrompt = ""
            private set
        private var logged = false

        /** Первый звук учителя: открыть микрофон. [after] — запись-продолжение S04 (её микрофон освобождается). */
        fun start(after: Job?) {
            if (recording != null) return
            recording = scope.async {
                after?.join()
                detector.reset()
                input.recordBargeIn(detector, turnDetector, ::onHit) { db -> _ui.update { it.copy(levelDb = db) } }
            }
        }

        /** Из потока записи. */
        private fun onHit(): AudioSink? {
            synchronized(lock) {
                if (halted) return null // учитель уже замолк по команде: запись сейчас бросят
                hit = true
                halted = true
            }
            output.stop() // затухание, ≤ 50 мс
            logMic()
            val at = detector.hit?.atMs ?: detector.stats.listenedMs
            if (goOn != null && at <= ModelConfig.BARGE_IN_J2_MISS_MS) {
                continuation = true
                utterance = goOn.utterance
                livePrompt = goOn.prompt()
            } else {
                livePrompt = prompt()
            }
            _partialText.value = ""
            turnDetector.setContext(turnContext(utterance, continuation = false))
            live = liveTranscriber?.begin(livePrompt) { partial -> _partialText.value = partial }
            bargedIn.complete(detector.hit ?: BargeInHit(detector.stats.listenedMs, 0f, 0))
            return live
        }

        /**
         * Короткий звук (S05b, D85), из потока записи: кусок — в распознавание файлом, учитель говорит дальше. Не больше
         * одного за раз: новый, пока старый распознаётся, пропускается.
         */
        private fun onCandidate(c: SoundCandidate) {
            if (halted || !active) return
            val head = "short sound during teacher (${c.speechMs} ms, p ${p2(c.maxP)})"
            if (!candidateBusy.compareAndSet(false, true)) {
                skipped.incrementAndGet()
                log.event(TURN, "$head → skipped (another one is being recognized)")
                return
            }
            candidates.incrementAndGet()
            scope.launch {
                try {
                    recognize(c, head)
                } finally {
                    candidateBusy.set(false)
                }
            }
        }

        private suspend fun recognize(c: SoundCandidate, head: String) {
            val started = clock()
            val audio = input.soundClip(c.pcm) ?: return
            val text = try {
                transcriber.transcribe(audio, prompt())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.event(TURN, "$head → stt failed (${e.message ?: e.javaClass.simpleName}) in ${clock() - started} ms")
                return
            }
            candidateCostUsd += audio.durationMs / 60_000.0 * ModelConfig.TRANSCRIBE_PRICE_PER_MIN
            val stt = "$head → stt ${clock() - started} ms: "
            val command = VoiceCommand.of(text)
            when {
                text.isBlank() -> log.event(TURN, "${stt}empty")
                command == null -> log.event(TURN, "${stt}not a command (${TranscriptMatch.words(text).size} words)")
                else -> {
                    val taken = synchronized(lock) {
                        if (halted || !active) false else { halted = true; true }
                    }
                    val why = when {
                        taken -> ""
                        hit -> ", dropped: barge-in"
                        else -> ", dropped: the teacher has finished"
                    }
                    log.event(TURN, "${stt}command ${command.label}$why")
                    if (taken) {
                        commands++
                        output.stop() // затухание, как при перебивании
                        commandHeard.complete(command)
                    }
                }
            }
        }

        /** Запись реплики после перебивания (её ждёт следующий ход). */
        fun takeRecording(): Deferred<RecordedAudio> = recording!!

        /** Учитель договорил, ученик не перебил: микрофон закрыть, замер утечки — в журнал. */
        suspend fun stop() {
            if (hit) return
            recording?.cancel()
            recording?.join()
            logMic()
        }

        /** Ход кончился любым путём: без перебивания запись не нужна. Короткие звуки за ответ — строкой в журнал. */
        fun release() {
            active = false
            if (!hit) recording?.cancel()
            val n = candidates.get()
            if (n > 0 || skipped.get() > 0) {
                log.event(
                    TURN,
                    "short sounds during teacher: $n recognized (commands $commands, skipped ${skipped.get()}); " +
                        "stt cost \$${"%.5f".format(java.util.Locale.US, candidateCostUsd)}",
                )
            }
        }

        private fun logMic() {
            if (logged || recording == null) return
            logged = true
            val m = detector.stats
            log.event(
                AUDIO,
                "mic during teacher: ${m.listenedMs} ms, max p ${p2(m.maxP)}, speech frames ${m.speechFrames} (p ≥ 0.5), " +
                    "longest speech ${m.longestSpeechMs} ms" + if (m.mutedMs > 0) ", cues ${m.mutedMs} ms not counted" else "",
            )
        }
    }

    /** Строка журнала о перебивании и запись следующего хода (S05). */
    private fun bargedIn(listen: TeacherListen, hit: BargeInHit, spoken: Int, total: Int): BargeStart {
        val miss = if (listen.continuation) {
            "; within ${ModelConfig.BARGE_IN_J2_MISS_MS / 1000} s of teacher audio after the end by ${lastEndedBy.label} " +
                "→ continuation of the student turn"
        } else {
            ""
        }
        log.event(
            TURN,
            "barge-in at +${hit.atMs} ms of teacher audio (p ${p2(hit.meanP)}, speech ${hit.speechMs} ms) → stopped, " +
                "spoken $spoken of $total groups$miss",
        )
        return BargeStart(listen.takeRecording(), listen.live, listen.livePrompt, listen.utterance)
    }

    /** Ученик продолжил после объявленного конца, до голоса учителя: ответ отменён (строка журнала без текстов). */
    private fun resumedOutcome(afterEndMs: Long, correction: Boolean): TurnOutcome {
        val what = if (correction) "tutor, corrector" else "tutor"
        log.event(TURN, "resumed after end (+$afterEndMs ms) → cancelled $what; tutor cost unknown (stream cut)")
        return TurnOutcome.Resumed(afterEndMs)
    }

    /**
     * Текст реплики из потока со страховкой файлом (D74). Поток дал текст за [ModelConfig.LIVE_FILE_BACKUP_AFTER_MS]
     * после `commit` — он и берётся. Поток дал сбой раньше — файл, как в S03d. Текста нет к этому сроку — файл
     * уходит параллельно (в debug он уже идёт для сверки), берётся первый готовый текст; поток по-прежнему ждёт
     * до своего предела ([ModelConfig.LIVE_TEXT_TIMEOUT_MS]), его итог пишется в журнал и тогда, когда победил файл.
     */
    private suspend fun studentTextLive(
        live: LiveTurn,
        audio: RecordedAudio,
        prompt: String,
        debugFile: Deferred<Result<String>>?,
    ): String {
        val speechMs = audio.turn.speechMs
        if (speechMs < ModelConfig.SHORT_TURN_SPEECH_MS) return studentTextShort(live, audio, prompt, debugFile, speechMs)
        val liveResult = scope.async { live.result() }
        // `commit` ушёл в конце записи, до сжатия: время сжатия уже прошло.
        val backupAfter = if (ModelConfig.LIVE_FIRST_TURN_FILE_AT_ONCE && live.firstInSession) {
            0L
        } else {
            (ModelConfig.LIVE_FILE_BACKUP_AFTER_MS - audio.encodeMs).coerceAtLeast(0)
        }
        val early = withTimeoutOrNull(backupAfter) { liveResult.await() }
        if (early != null) {
            liveText(early)?.let { text ->
                lastSttPath = STT_LIVE
                debugFile?.let { compareWithFile(text, it) }
                return text
            }
            // Запасной путь — файл, как до S03d (повтор по D30). В debug файл уже идёт — ждём его.
            lastSttPath = STT_FILE
            return debugFile?.await()?.getOrThrow() ?: transcriber.transcribe(audio, prompt)
        }

        log.event(NETWORK, "live transcription: no text in ${ModelConfig.LIVE_FILE_BACKUP_AFTER_MS} ms after commit → file in parallel")
        val file = debugFile ?: scope.async { runCatching { transcriber.transcribe(audio, prompt) } }
        try {
            when (val first = select<Any> {
                liveResult.onAwait { it }
                file.onAwait { it }
            }) {
                is LiveResult -> {
                    liveText(first)?.let { text ->
                        lastSttPath = STT_LIVE
                        if (debugFile != null) compareWithFile(text, debugFile) else file.cancel()
                        return text
                    }
                    lastSttPath = STT_FILE
                    return file.await().getOrThrow()
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val fromFile = first as Result<String>
                    fromFile.onSuccess { text ->
                        lastSttPath = STT_FILE
                        log.event(NETWORK, "transcription: file first (live still waiting)")
                        scope.launch { liveText(liveResult.await(), fileUsed = true) }
                        return text
                    }
                    // Файл не дал текста — остаётся поток.
                    liveText(liveResult.await())?.let { text ->
                        lastSttPath = STT_LIVE
                        return text
                    }
                    lastSttPath = STT_FILE
                    return fromFile.getOrThrow()
                }
            }
        } catch (e: CancellationException) {
            if (debugFile == null) file.cancel()
            liveResult.cancel()
            throw e
        }
    }

    /**
     * Короткая реплика (S05b, D83): файл уходит сразу (в debug он уже идёт для сверки), и его текст главный. Его ждут до
     * предела потока ([ModelConfig.LIVE_TEXT_TIMEOUT_MS] от `commit`). Файл не дал текста (не успел, сбой, пусто) — текст
     * потока. Поток тоже без текста — как раньше: ждать файл до конца (его сбой — ошибка распознавания); оба пусто — молчание.
     */
    private suspend fun studentTextShort(
        live: LiveTurn,
        audio: RecordedAudio,
        prompt: String,
        debugFile: Deferred<Result<String>>?,
        speechMs: Long,
    ): String {
        log.event(NETWORK, "transcription: short turn ($speechMs ms of speech) → file preferred")
        val liveResult = scope.async { live.result() }
        val file = debugFile ?: scope.async { runCatching { transcriber.transcribe(audio, prompt) } }
        try {
            val deadline = (ModelConfig.LIVE_TEXT_TIMEOUT_MS - audio.encodeMs).coerceAtLeast(0)
            val fromFile = withTimeoutOrNull(deadline) { file.await() }
            fromFile?.getOrNull()?.takeIf { it.isNotBlank() }?.let { text ->
                lastSttPath = STT_FILE_SHORT
                // Итог потока — в журнал, когда придёт; в debug — сверка, как раньше.
                scope.launch {
                    val liveTextLate = liveText(liveResult.await(), fileUsed = true)
                    if (debugFile != null && liveTextLate != null) compareWithFile(liveTextLate, debugFile)
                }
                return text
            }
            val why = when {
                fromFile == null -> "not ready in $deadline ms"
                fromFile.isFailure -> "failed (${fromFile.exceptionOrNull()?.message ?: "error"})"
                else -> "empty"
            }
            log.event(NETWORK, "transcription: short turn: file $why → live")
            liveText(liveResult.await())?.let { text ->
                if (text.isNotBlank() || fromFile != null) {
                    lastSttPath = STT_LIVE
                    if (fromFile == null) {
                        if (debugFile != null) compareWithFile(text, debugFile) else file.cancel()
                    }
                    return text
                }
            }
            lastSttPath = STT_FILE
            return file.await().getOrThrow()
        } catch (e: CancellationException) {
            if (debugFile == null) file.cancel()
            liveResult.cancel()
            throw e
        }
    }

    /**
     * Итог потока в журнал (без текстов); текст или `null` — сбой, нужен файл. [fileUsed] — итог пришёл после того,
     * как текст уже взят из файла (D74). Пустой текст — «молчание», как у файла (D76).
     */
    private fun liveText(r: LiveResult, fileUsed: Boolean = false): String? {
        val s = r.stats
        val tail = if (fileUsed) " (late: file used)" else ""
        when (r) {
            is LiveResult.Text -> {
                log.event(
                    NETWORK,
                    "live transcription: session open in ${s.sessionWaitMs} ms (${if (s.reused) "reused" else "new"}), " +
                        "audio ${sec1(s.audioSentMs)} s sent, commit → text ${s.commitToTextMs} ms, ${s.deltas} deltas, " +
                        "${s.chars} chars; cost \$${"%.5f".format(java.util.Locale.US, s.costUsd)}$tail",
                )
                if (!fileUsed && r.text.isBlank()) {
                    log.event(NETWORK, "live transcription: empty text (${s.deltas} deltas) → counted as silence, no file")
                }
                return r.text
            }
            is LiveResult.Failed -> {
                log.event(
                    NETWORK,
                    "live transcription failed (${r.reason}) → file (deltas ${s.deltas}, audio ${sec1(s.audioSentMs)} s sent)$tail",
                )
                return null
            }
        }
    }

    /** Debug: похожесть потока и файла — в журнал, тексты — в [onLiveVsFile]. Ответ учителя не ждёт. */
    private fun compareWithFile(liveText: String, fileJob: Deferred<Result<String>>) {
        scope.launch {
            val file = fileJob.await().getOrElse { e ->
                log.event(NETWORK, "live vs file: file failed (${e.message ?: e.javaClass.simpleName})")
                return@launch
            }
            val sim = TranscriptMatch.similarity(liveText, file)
            log.event(
                NETWORK,
                "live vs file: similarity ${"%.2f".format(java.util.Locale.US, sim)} " +
                    "(${TranscriptMatch.words(liveText).size} vs ${TranscriptMatch.words(file).size} words)",
            )
            onLiveVsFile?.invoke(liveText, file, sim)
        }
    }

    /** Учесть реплику в уровне ученика (шаг [StudentLevel.preview]), записать в журнал. */
    private fun countTurn(step: StudentLevel.Step) {
        val level = studentLevel ?: return
        level.apply(step)
        val s = step.state
        step.from?.let { from ->
            log.event(SOURCE, "level ${if (s.level > from) "up" else "down"} $from → ${s.level}")
        }
        log.event(SOURCE, "level ${s.level} (window ${s.turns} of ${level.windowSize}, not understood ${s.notUnderstood})")
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
        progress: ReplyProgress? = null,
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
                speakReply(t, shieldMs, correction, progress, ask)
            } catch (e: CancellationException) {
                // Ученик перебил (S05): замер ответа всё равно в журнал — голос учителя уже звучал.
                if (logTiming && currentListen?.halted == true) logTimings(recordedAt, transcribedAt, t, shieldMs)
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
        progress: ReplyProgress?,
        ask: suspend (onEvent: suspend (TutorEvent) -> Unit) -> Unit,
    ): Exception? {
        val stream = StreamingSpeechPlanner(planner::plan)
        /** Группы, отданные в синтез, по порядку: i-й звук из конвейера — это sent[i]. */
        val sent = mutableListOf<SpeechChunk>()
        progress?.groups = sent
        /** Звук групп по номеру — для «Again» (S05). */
        val played = HashMap<Int, SpeechAudio>()
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
                        played[i] = audio
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
                            beforeTeacherSound()
                            val index = i
                            output.play(audio, shieldMs) {
                                if (t.firstSound == null) {
                                    t.firstSound = clock()
                                    stopWaitTicks()
                                }
                                if (teacherGroup && t.teacherSound == null) t.teacherSound = clock()
                                progress?.started = index + 1
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
        } finally {
            // «Again» (S05): реплика, которая начала звучать, — вся, что успела прийти, с паузами и готовым звуком.
            if (t.firstSound != null) {
                val more = if (stream.finished) emptyList() else stream.finish()
                val groups = sent.mapIndexed { i, c ->
                    SpeechChunk(c.text, if (i < prefix.size) prefix[i].pauseMs else stream.pauseAfter(i - prefix.size))
                } + more
                lastReply = ReplyCache(groups, played.toMap())
            }
        }
        return tutorError
    }

    // ---- команды голосом (S05, D82) --------------------------------------------------------

    /** Последняя реплика учителя: группы с паузами (как прозвучали бы) и звук тех, что синтезированы, по номеру. */
    private class ReplyCache(val groups: List<SpeechChunk>, val audio: Map<Int, SpeechAudio>)

    /**
     * Команда вместо реплики. «Stop» — учитель уже молчит: «говори» и слушание. «Again» / «Slower» — последняя реплика
     * учителя заново (из кэша / медленнее), пока звучит — микрофон слушает перебивание; дальше — как после ответа.
     * Повторять нечего — «говори» и слушание. В историю учителя ничего не пишется.
     */
    private suspend fun runCommand(command: VoiceCommand): Next {
        stopWaitTicks()
        val cache = lastReply
        val groups = if (command == VoiceCommand.STOP) 0 else cache?.groups?.size ?: 0
        log.event(TURN, "command ${command.label} ($groups groups)")
        if (command == VoiceCommand.STOP) {
            setState(ConversationState.Idle, "voice command stop") { it.copy(status = "Listening again.") }
            return Next.Listen
        }
        if (cache == null || cache.groups.isEmpty()) {
            log.event(TURN, "command ${command.label}: nothing to repeat yet → listening")
            setState(ConversationState.Idle, "nothing to repeat") { it.copy(status = "Nothing to repeat.") }
            return Next.Listen
        }
        val slower = command == VoiceCommand.SLOWER
        _ui.update { it.copy(teacherText = cache.groups.joinToString(" ") { g -> g.text }.replace(SPACES, " ").trim()) }
        val progress = ReplyProgress().apply { this.groups = cache.groups }
        val listen = newListen { TranscriptionPrompt.build(tutor.lastTeacherText, ModelConfig.TRANSCRIBE_PROMPT_TEACHER_CHARS) }
        currentListen = listen
        try {
            val barged = coroutineScope {
                val job = async { playRepeat(cache, slower, progress) }
                val first = select<Any?> {
                    job.onAwait { null }
                    listen?.bargedIn?.onAwait { it }
                    listen?.commandHeard?.onAwait { it }
                }
                if (first is VoiceCommand) {
                    // Команда коротким звуком посреди повтора (D85).
                    job.cancel()
                    job.join()
                    listen!!.stop()
                    log.event(TURN, "short sound → command ${first.label}: stopped, spoken ${progress.started} of ${progress.total()} groups")
                    first
                } else if (first is BargeInHit || listen?.hit == true) {
                    val hit = first as? BargeInHit ?: listen!!.bargedIn.await()
                    job.cancel()
                    job.join()
                    bargedIn(listen!!, hit, progress.started, progress.total())
                } else {
                    listen?.stop()
                    null
                }
            }
            if (barged is VoiceCommand) return runCommand(barged)
            if (barged is BargeStart) return Next.Barged(barged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Next.End(fail(EndReason.SPEECH_ERROR, "Error: ${e.localizedMessage}", e))
        } finally {
            currentListen = null
            listen?.release()
        }
        setState(ConversationState.Idle, "repeat finished") { it.copy(currentChunk = "", status = "Ready.") }
        return Next.End(if (_ui.value.autoMode) null else EndReason.REPLY_FINISHED)
    }

    /**
     * Повтор реплики: «Again» — тот же звук (кэш), группы без звука синтезируются; «Slower» — всё заново со скоростью
     * [ModelConfig.SLOWER_SPEECH_SPEED] и паузами × [ModelConfig.SLOWER_PAUSE_FACTOR]. Сверка и запасной синтез — как у
     * ответа ([SpeechPipeline]).
     */
    private suspend fun playRepeat(cache: ReplyCache, slower: Boolean, progress: ReplyProgress) {
        val shieldMs = output.prepareReply()
        val speed = if (slower) ModelConfig.SLOWER_SPEECH_SPEED else ModelConfig.SPEECH_SPEED
        val factor = if (slower) ModelConfig.SLOWER_PAUSE_FACTOR else 1.0
        startWaitTicks()
        coroutineScope {
            val chunks = Channel<SpeechChunk>(Channel.UNLIMITED)
            cache.groups.forEach { chunks.trySend(it) }
            chunks.close()
            val audios = speech.start(this, chunks, speed, ready = { i -> if (slower) null else cache.audio[i] })
            var i = 0
            var lastEnd = 0L
            for (audio in audios) {
                if (i > 0) {
                    val pause = (cache.groups[i - 1].pauseMs * factor).toLong()
                    val wait = maxOf(10L, pause - shieldMs) - (clock() - lastEnd)
                    if (wait > 0) delay(wait)
                }
                if (audio.pcm.isNotEmpty()) {
                    setState(ConversationState.Speaking, if (slower) "repeat slower" else "repeat") {
                        it.copy(currentChunk = cache.groups[i].text, status = "Speaking ${i + 1} 🔊")
                    }
                    beforeTeacherSound()
                    val index = i
                    output.play(audio, shieldMs) {
                        stopWaitTicks()
                        progress.started = index + 1
                    }
                }
                lastEnd = clock()
                i++
            }
        }
        stopWaitTicks()
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
            beforeTeacherSound()
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
                // Поправщик стоит ≈ $0.00003: с 4 знаками в журнале было бы $0.0000.
                val cost = "cost \$${"%.5f".format(java.util.Locale.US, o.answer.costUsd)}"
                when (val v = Correction.check(student, o.answer.text)) {
                    is Correction.Verdict.Fix -> "correction in ${o.ms} ms; $cost" to v.text
                    Correction.Verdict.None -> "ok in ${o.ms} ms; $cost" to null
                    is Correction.Verdict.Rejected ->
                        "rejected (similarity ${"%.2f".format(java.util.Locale.US, v.similarity)}) in ${o.ms} ms; $cost" to null
                    is Correction.Verdict.SpanTooLong -> "rejected (span ${v.words} words) in ${o.ms} ms; $cost" to null
                }
            }
        }

        private var wasCancelled = false

        /** Ученик продолжил (S04): поправка не нужна; цена — если ответ уже пришёл. */
        fun cancelled() {
            wasCancelled = true
            val cost = (result.takeIf { it.isCompleted }?.getCompleted() as? CorrectorOutcome.Answer)?.answer?.costUsd
            log.event(NETWORK, "corrector: cancelled (student went on); " +
                (cost?.let { "cost \$${"%.5f".format(java.util.Locale.US, it)}" } ?: "cost unknown"))
        }

        /** Ход окончен: запрос, который ещё идёт, не нужен. */
        fun finish() {
            if (!wasCancelled && !decided && !result.isCompleted) log.event(NETWORK, "corrector: not used (no teacher reply)")
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
     * (D44: цель — первый звук учителя, фраза или ответ). `-` — не было. `stt_path` (S03d) — откуда текст
     * ученика: `live` — поток, `file` — файл (потока нет или он дал сбой), `file-short` — короткая реплика, текст файла
     * главный (S05b, D83).
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
                "filler_audio=${rel(t.fillerSound?.plus(shieldMs))} stt_path=$lastSttPath " +
                "end_wait=$lastEndWaitMs ended_by=${lastEndedBy.label}",
        )
    }

    /** Имя состояния для журнала. Текст ошибки сюда не идёт — он пишется отдельно, через log.error. */
    private fun stateName(state: ConversationState) = when (state) {
        is ConversationState.Error -> "Error"
        else -> state.toString()
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    private fun p2(value: Float) = String.format(java.util.Locale.US, "%.2f", value)

    private fun ms1(value: Double) = String.format(java.util.Locale.US, "%.1f", value)

    private fun sec1(ms: Long) = String.format(java.util.Locale.US, "%.1f", ms / 1000.0)

    companion object {
        private const val SOURCE = "Controller"
        private const val NETWORK = "Network"
        private const val TURN = "Turn"
        private const val AUDIO = "Audio"
        private val SPACES = Regex("""\s+""")
        /** Источник строки замера скорости (в Logcat — тег LCT-Timing, как в S01). */
        const val TIMING_SOURCE = "Timing"
        /** Фраза учителя, пока идёт поиск и он ещё ничего не сказал (D39). */
        const val SEARCH_FILLER = "Let me check."
        const val STT_LIVE = "live"
        const val STT_FILE = "file"
        /** Короткая реплика: победил файл по правилу D83 (S05b). */
        const val STT_FILE_SHORT = "file-short"
    }
}
