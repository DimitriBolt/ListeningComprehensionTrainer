package com.example.listeningtrainer.endpoint

import com.example.listeningtrainer.conversation.SilenceTurnDetector
import com.example.listeningtrainer.conversation.TurnDetector
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
    /** Пульс, мс; маска D34 в обычном режиме, мс (здесь она **не** применялась — звук сырой). */
    val pulseDurationMs: Int,
    val pulseMaskMs: Long,
    val levelWindowMs: Int,
    /** Громкость окна в дБ по шкале нынешнего детектора (20·lg RMS), без маски. */
    val levelDb: List<Float>,
    val levelWallMs: List<Long>,
    /** Эмуляция нынешнего детектора на телефоне (без маски): где он кончил бы запись, мс звука, или `null`. */
    val silenceDetector: DetectorEmulation,
    val device: String = "",
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        const val SCHEMA = 1
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

/**
 * «Детектор» записи для пробы: запись **не** останавливает (её кончает кнопка или предохранитель),
 * а только запоминает громкость каждого окна и попутно гоняет нынешний [SilenceTurnDetector] — где бы
 * он кончил запись. Время окна — время звука по номеру окна, а не часы: так эмуляция не зависит
 * от того, что микрофон отдаёт первые окна пачкой.
 */
class LevelTrace(
    private val windowMs: Int,
    private val thresholdDb: Float = SilenceTurnDetector.DEFAULT_THRESHOLD_DB,
    private val silenceMs: Long = SilenceTurnDetector.DEFAULT_SILENCE_MS,
) : TurnDetector {
    private val emulated = SilenceTurnDetector(thresholdDb, silenceMs)
    var startedWallMs = 0L
        private set
    val levels = mutableListOf<Float>()
    val wallMs = mutableListOf<Long>()
    var emulatedEndMs: Long? = null
        private set

    override val heardSpeech: Boolean get() = emulated.heardSpeech

    override fun reset(nowMs: Long) {
        startedWallMs = nowMs
        levels.clear()
        wallMs.clear()
        emulatedEndMs = null
        emulated.reset(0)
    }

    override fun onLevel(db: Float, nowMs: Long): Boolean {
        levels += db
        wallMs += nowMs - startedWallMs
        val audioMs = levels.size.toLong() * windowMs
        if (emulatedEndMs == null && emulated.onLevel(db, audioMs)) emulatedEndMs = audioMs
        return false
    }

    fun emulation() = DetectorEmulation(thresholdDb, silenceMs, emulatedEndMs, heardSpeech)
}
