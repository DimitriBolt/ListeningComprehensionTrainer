package com.example.listeningtrainer.endpoint

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.TurnDetector
import com.example.listeningtrainer.conversation.TurnEnd
import com.example.listeningtrainer.conversation.TurnStats
import com.example.listeningtrainer.conversation.VadTurnDetector
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Что лежит в JSON рядом с WAV записи для пробы (S03b). Времена — мс от начала записи
 * ([startedAtMs] — часы телефона, когда запись запущена), если не сказано иначе.
 *
 * Звук записи начинается в 0 мс файла. Окна громкости: окно `i` — звук `[i·windowMs, (i+1)·windowMs)`,
 * [levelWallMs] — когда это окно прочитано (часы телефона, от [startedAtMs]). По концу записи
 * (последнее окно прочитано в `levelWallMs.last()`, это звук до `durationMs`) проба переводит моменты
 * пульсов и кнопки в время звука файла: первые окна микрофон отдаёт пачкой, поэтому равнять по концу.
 */
@Serializable
data class EndpointTake(
    val schema: Int = SCHEMA,
    val condition: String,
    val line: Int,
    val kind: String,
    val text: String,
    /** Номер дубля этой реплики в этом условии (с 1); проба берёт последний. */
    val take: Int,
    val startedAtMs: Long,
    val startedAtIso: String,
    val sampleRate: Int,
    val durationMs: Long,
    /** Чем кончилась запись: `button` (гарнитура), `screen` (кнопка на экране), `limit` (90 с). */
    val endedBy: String,
    /** Отпускание кнопки (`button`, `screen`), иначе `null`. */
    val buttonReleaseMs: Long?,
    /** Нажатие кнопки гарнитуры: отпускание минус длительность нажатия. */
    val buttonPressMs: Long?,
    /** Начало каждого пульса (D28): когда проигрыватель запустил звук. */
    val pulsesMs: List<Long>,
    /** Длина пульса, мс. Маски после пульса нет (D68): звук сырой. */
    val pulseDurationMs: Int,
    val levelWindowMs: Int,
    /** Громкость окна в дБ по шкале нынешнего детектора (20·lg RMS), без маски. */
    val levelDb: List<Float>,
    val levelWallMs: List<Long>,
    /** Эмуляция прежнего порога 59 дБ / 3 с (до S03c, без маски): где он кончил бы запись, мс звука, или `null`. */
    val silenceDetector: DetectorEmulation,
    /** Детектор приложения (Silero VAD, S03c) с его настройками: где он кончил бы запись, мс звука, или `null`. */
    val sileroDetector: SileroEmulation? = null,
    val device: String = "",
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        const val SCHEMA = 2
        private val json = Json { prettyPrint = true; encodeDefaults = true }
        fun fromJson(text: String): EndpointTake = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class DetectorEmulation(
    val thresholdDb: Float,
    val silenceMs: Long,
    val endMs: Long?,
    val heardSpeech: Boolean,
)

/** Silero VAD с настройками приложения на этой записи (S03c). */
@Serializable
data class SileroEmulation(
    val speechProb: Float,
    val silenceMs: Long,
    val minSpeechMs: Long,
    /** Где кончил бы запись по тишине, мс звука, или `null` — не кончил до кнопки. */
    val endMs: Long?,
    /** Услышал ли речь к своему концу (или к кнопке). */
    val heardSpeech: Boolean,
    val speechMs: Long,
    val frames: Int,
    val avgFrameMs: Double,
    val maxFrameMs: Double,
    /** Тишина до конца, пока речи не было (D70, S03d); в записях S03c поля нет — там было как [silenceMs]. */
    val startSilenceMs: Long = silenceMs,
)

/**
 * «Детектор» записи для пробы: запись **не** останавливает (её кончает кнопка или предохранитель 90 с),
 * а только запоминает громкость каждого окна ([onLevel]) и попутно гоняет:
 * - прежний порог [thresholdDb] / [silenceMs] (до S03c) — где бы он кончил запись;
 * - детектор приложения [silero] (S03c) — где бы кончил он.
 * Время — время звука (номер окна или кадра), а не часы: эмуляция не зависит от того, что микрофон отдаёт
 * первые окна пачкой. [clock] — только для `levelWallMs` и начала записи.
 */
class LevelTrace(
    private val windowMs: Int,
    private val silero: VadTurnDetector,
    private val thresholdDb: Float = THRESHOLD_DB,
    private val silenceMs: Long = THRESHOLD_SILENCE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : TurnDetector {
    var startedWallMs = 0L
        private set
    val levels = mutableListOf<Float>()
    val wallMs = mutableListOf<Long>()
    var emulatedEndMs: Long? = null
        private set
    var sileroEndMs: Long? = null
        private set
    private var lastLoudMs = 0L
    private var thresholdHeard = false
    private var sileroHeardAtEnd: Boolean? = null
    private var sileroStatsAtEnd: TurnStats? = null

    /** Слышал ли речь прежний порог (для [RecordedAudio][com.example.listeningtrainer.conversation.RecordedAudio]). */
    override val heardSpeech: Boolean get() = thresholdHeard

    override val stats: TurnStats get() = silero.stats

    override fun reset() {
        startedWallMs = clock()
        levels.clear()
        wallMs.clear()
        emulatedEndMs = null
        sileroEndMs = null
        lastLoudMs = 0
        thresholdHeard = false
        sileroHeardAtEnd = null
        sileroStatsAtEnd = null
        silero.reset()
    }

    /** Громкость очередного окна (обратный вызов записи). */
    fun onLevel(db: Float) {
        levels += db
        wallMs += clock() - startedWallMs
        val audioMs = levels.size.toLong() * windowMs
        if (db > thresholdDb) {
            lastLoudMs = audioMs
            thresholdHeard = true
        } else if (emulatedEndMs == null && audioMs - lastLoudMs > silenceMs) {
            emulatedEndMs = audioMs
        }
    }

    override fun onAudio(pcm: ShortArray, count: Int): TurnEnd? {
        return when (silero.onAudio(pcm, count)) {
            TurnEnd.LIMIT -> TurnEnd.LIMIT // предохранитель 90 с — запись кончается и здесь
            TurnEnd.SILENCE -> {
                if (sileroEndMs == null) {
                    sileroEndMs = silero.endMs
                    sileroHeardAtEnd = silero.heardSpeech
                    sileroStatsAtEnd = silero.stats
                }
                null
            }
            else -> null
        }
    }

    fun emulation() = DetectorEmulation(thresholdDb, silenceMs, emulatedEndMs, thresholdHeard)

    fun sileroEmulation(): SileroEmulation {
        val st = sileroStatsAtEnd ?: silero.stats
        val all = silero.stats // время вывода — за всю запись
        return SileroEmulation(
            speechProb = ModelConfig.VAD_SPEECH_PROB,
            silenceMs = ModelConfig.TURN_END_SILENCE_MS,
            minSpeechMs = ModelConfig.TURN_MIN_SPEECH_MS,
            endMs = sileroEndMs,
            heardSpeech = sileroHeardAtEnd ?: silero.heardSpeech,
            speechMs = st.speechMs,
            frames = all.frames,
            avgFrameMs = Math.round(all.avgFrameMs * 100) / 100.0,
            maxFrameMs = Math.round(all.maxFrameMs * 100) / 100.0,
            startSilenceMs = ModelConfig.TURN_START_SILENCE_MS,
        )
    }

    companion object {
        /** Прежний порог громкости (до S03c) — только для сравнения в режиме записи. */
        const val THRESHOLD_DB = 59f
        const val THRESHOLD_SILENCE_MS = 3000L
    }
}
