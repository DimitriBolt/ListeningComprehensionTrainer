package com.example.listeningtrainer.conversation

/**
 * Нынешнее правило конца реплики: громкость выше [thresholdDb] — ученик говорит;
 * тишина дольше [silenceMs] с последнего громкого замера — реплика закончена.
 * Отсчёт идёт и от начала записи: если ученик молчит с самого начала, запись тоже кончится.
 * Ни одного замера выше порога за всю запись — [heardSpeech] = false, это молчание.
 */
class SilenceTurnDetector(
    val thresholdDb: Float = DEFAULT_THRESHOLD_DB,
    private val silenceMs: Long = DEFAULT_SILENCE_MS,
) : TurnDetector {

    private var lastSpeechMs = 0L

    override var heardSpeech = false
        private set

    override fun reset(nowMs: Long) {
        lastSpeechMs = nowMs
        heardSpeech = false
    }

    override fun onLevel(db: Float, nowMs: Long): Boolean {
        if (db > thresholdDb) {
            lastSpeechMs = nowMs
            heardSpeech = true
            return false
        }
        return nowMs - lastSpeechMs > silenceMs
    }

    companion object {
        /** Порог шума в дБ: ниже — считается, что ученик молчит. */
        const val DEFAULT_THRESHOLD_DB = 59f
        /** Сколько тишины ждать перед остановкой записи. */
        const val DEFAULT_SILENCE_MS = 3000L
    }
}
