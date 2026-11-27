package com.example.listeningtrainer.endpoint

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.SpeechProbability
import com.example.listeningtrainer.conversation.VadTurnDetector
import com.example.listeningtrainer.conversation.AudioInput
import com.example.listeningtrainer.conversation.AudioOutput
import com.example.listeningtrainer.conversation.ConversationState
import com.example.listeningtrainer.conversation.Cue
import com.example.listeningtrainer.conversation.HeadsetButton
import com.example.listeningtrainer.eventlog.EventLog
import com.example.listeningtrainer.eventlog.NoEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Что показывает экран режима записи. */
data class EndpointUi(
    val active: Boolean = false,
    /** `null` — условие ещё не выбрано (экран показывает список условий). */
    val condition: EndpointScenario.Condition? = null,
    val lineIndex: Int = 0,
    val recording: Boolean = false,
    /** Запись закончена, файл ещё сохраняется. */
    val saving: Boolean = false,
    /** Номера реплик этого условия, у которых уже есть запись. */
    val recorded: Set<Int> = emptySet(),
    /** Сколько дублей текущей реплики уже есть. */
    val takes: Int = 0,
    val message: String = "",
    val levelDb: Float = 0f,
) {
    val line: EndpointScenario.Line get() = EndpointScenario.lines[lineIndex]
}

/**
 * Отладочный режим записи для пробы конца реплики (S03b, только debug — [available]). Без учителя.
 *
 * Пользователь выбирает условие, экран показывает реплики сценария по одной. Запись начинается кнопкой
 * гарнитуры (или на экране): «говори», потом «пульс» каждые [pulseEveryMs], как в разговоре (D28).
 * Детектор её **не** останавливает ([LevelTrace]): кончают кнопка или предохранитель 90 с. В JSON — где кончили
 * бы запись прежний порог 59 дБ / 3 с и детектор приложения (Silero, S03c). Звук сырой, без сжатия.
 * Моменты пульсов приходят в [onPulseStarted] от
 * проигрывателя (когда звук реально запущен). После записи — WAV + JSON в [store], «обрабатываю»,
 * экран переходит к следующей реплике.
 *
 * В журнал — номер условия и реплики, длительность, чем кончилась; без текста реплики (инвариант 8).
 */
class EndpointRecorder(
    private val scope: CoroutineScope,
    private val input: AudioInput,
    private val output: AudioOutput,
    private val store: RecordingStore,
    /** Вероятность речи для эмуляции детектора приложения (Silero, S03c). */
    private val vad: SpeechProbability,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: EventLog = NoEventLog,
    private val pulseEveryMs: Long = 2_000L,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val device: String = "",
) {
    private val _ui = MutableStateFlow(EndpointUi())
    val ui: StateFlow<EndpointUi> = _ui.asStateFlow()

    private val headset = HeadsetButton()
    private var job: Job? = null
    private val pulses = mutableListOf<Long>()
    @Volatile private var recordingActive = false
    private var release: Long? = null
    private var pressMs: Long? = null
    private var endedBy: String? = null

    val active: Boolean get() = _ui.value.active

    /** Войти в режим: экран выбора условия. */
    fun open() {
        if (active) return
        log.event(SOURCE, "endpoint recording mode opened")
        _ui.value = EndpointUi(active = true, message = "Choose the condition.")
    }

    fun chooseCondition(condition: EndpointScenario.Condition) {
        if (!active || busy) return
        val recorded = store.recordedLines(condition.id)
        val first = EndpointScenario.lines.indexOfFirst { it.number !in recorded }.let { if (it < 0) 0 else it }
        _ui.update { it.copy(condition = condition, recorded = recorded, message = "Press the headset button to start.") }
        showLine(first)
        log.event(SOURCE, "endpoint condition ${condition.id}: ${recorded.size} of ${EndpointScenario.lines.size} lines recorded")
    }

    /** Перейти к реплике [index] (0…11). */
    fun selectLine(index: Int) {
        if (!busy) showLine(index)
    }

    private fun showLine(index: Int) {
        val condition = _ui.value.condition ?: return
        if (index !in EndpointScenario.lines.indices) return
        val number = EndpointScenario.lines[index].number
        _ui.update { it.copy(lineIndex = index, takes = store.takes(condition.id, number)) }
    }

    /** Назад к выбору условия. */
    fun backToConditions() {
        if (busy) return
        _ui.update { EndpointUi(active = true, message = "Choose the condition.") }
    }

    /** Выйти из режима. Идущая запись отменяется и не сохраняется. */
    fun close() {
        if (!active) return
        job?.cancel()
        job = null
        recordingActive = false
        _ui.value = EndpointUi()
        log.event(SOURCE, "endpoint recording mode closed")
    }

    private val busy: Boolean get() = job?.isActive == true

    /** Начать запись текущей реплики ([why] — для журнала). */
    fun start(why: String = "screen") {
        val condition = _ui.value.condition ?: return
        if (busy) return
        val line = _ui.value.line
        release = null
        pressMs = null
        endedBy = null
        synchronized(pulses) { pulses.clear() }
        log.event(SOURCE, "endpoint recording start (${condition.id} #${line.number}, $why)")
        job = scope.launch { recordOne(condition, line) }
    }

    /** Закончить запись: [by] — `button` (гарнитура) или `screen`; [heldMs] — длительность нажатия. */
    fun finish(by: String = "screen", heldMs: Long = 0L) {
        if (!recordingActive || endedBy != null) return
        endedBy = by
        release = clock()
        pressMs = heldMs
        input.finish()
    }

    /** Проигрыватель запустил звук «пульса» в [wallMs] (часы [clock]). */
    fun onPulseStarted(wallMs: Long) {
        if (recordingActive) synchronized(pulses) { pulses += wallMs }
    }

    /** Средняя кнопка гарнитуры, пока режим открыт: начать / закончить запись. `true` — нажатие наше. */
    fun headsetButton(keyCode: Int, heldMs: Long): Boolean {
        val state = when {
            recordingActive -> ConversationState.Listening
            busy || _ui.value.condition == null -> ConversationState.Thinking
            else -> ConversationState.Idle
        }
        val decision = headset.onRelease(keyCode, heldMs, state, clock())
        log.event(SOURCE, "endpoint ${decision.log}")
        when (decision.action) {
            HeadsetButton.Action.TALK -> start("headset button")
            HeadsetButton.Action.FINISH_RECORDING -> finish("button", heldMs)
            HeadsetButton.Action.NONE -> Unit
        }
        return decision.consumed
    }

    private suspend fun recordOne(condition: EndpointScenario.Condition, line: EndpointScenario.Line) {
        _ui.update { it.copy(recording = true, message = "Recording… ${line.instruction}") }
        output.playCue(Cue.LISTEN)
        output.awaitCues()
        val trace = LevelTrace(ModelConfig.RECORD_LEVEL_WINDOW_MS, VadTurnDetector(vad), clock = clock)
        val pulseJob = if (output.listenPulseAllowed()) scope.launch {
            while (true) {
                delay(pulseEveryMs)
                output.playCue(Cue.PULSE)
            }
        } else null
        val audio = try {
            recordingActive = true
            input.record(trace) { db ->
                trace.onLevel(db)
                _ui.update { it.copy(levelDb = db) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(SOURCE, "endpoint recording failed", e)
            _ui.update { it.copy(recording = false, levelDb = 0f, message = "Recording error. Try again.") }
            return
        } finally {
            recordingActive = false
            pulseJob?.cancel()
        }
        val startedAt = trace.startedWallMs
        _ui.update { it.copy(recording = false, saving = true, levelDb = 0f) }
        val takeNumber = store.takes(condition.id, line.number) + 1
        val pulsesRel = synchronized(pulses) { pulses.map { it - startedAt } }
        val releaseRel = release?.let { it - startedAt }
        val take = EndpointTake(
            condition = condition.id,
            line = line.number,
            kind = line.kind.id,
            text = line.text,
            take = takeNumber,
            startedAtMs = startedAt,
            startedAtIso = iso(startedAt),
            sampleRate = ModelConfig.RECORD_SAMPLE_RATE,
            durationMs = audio.durationMs,
            endedBy = endedBy ?: "limit",
            buttonReleaseMs = releaseRel,
            buttonPressMs = releaseRel?.let { r -> r - (pressMs ?: 0L) },
            pulsesMs = pulsesRel,
            pulseDurationMs = PULSE_MS,
            levelWindowMs = ModelConfig.RECORD_LEVEL_WINDOW_MS,
            levelDb = trace.levels.map { Math.round(it * 10) / 10f },
            levelWallMs = trace.wallMs.toList(),
            silenceDetector = trace.emulation(),
            sileroDetector = trace.sileroEmulation(),
            device = device,
        )
        val (base, deleted) = try {
            withContext(io) { store.save(take, audio.file) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(SOURCE, "endpoint recording not saved", e)
            _ui.update { it.copy(saving = false, message = "Not saved. Try again.") }
            return
        }
        output.playCue(Cue.PROCESSING)
        log.event(
            SOURCE,
            "endpoint recording saved: $base, take $takeNumber, ${audio.durationMs} ms, ended by ${take.endedBy}, " +
                "pulses ${pulsesRel.size}, silero end ${take.sileroDetector?.endMs?.let { "$it ms" } ?: "none"}, old recordings deleted $deleted",
        )
        val recorded = _ui.value.recorded + line.number
        val nextIndex = (_ui.value.lineIndex + 1).coerceAtMost(EndpointScenario.lines.lastIndex)
        val last = _ui.value.lineIndex == EndpointScenario.lines.lastIndex
        _ui.update {
            it.copy(
                saving = false,
                recorded = recorded,
                message = if (last) "Saved #${line.number}. All lines done for this condition." else "Saved #${line.number}. Next line.",
            )
        }
        showLine(if (last) _ui.value.lineIndex else nextIndex)
    }

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(ms))

    companion object {
        private const val SOURCE = "Debug"
        /** Длина звука «пульса» ([com.example.listeningtrainer.audio.CueTones]). */
        const val PULSE_MS = 60

        /** Режим есть только в debug-сборке. */
        fun available(isDebug: Boolean): Boolean = isDebug
    }
}
