package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.ModelConfig

/**
 * Конец реплики по вероятности речи кадров (S03c, D64) — чистый Kotlin, без Android.
 *
 * Звук приходит кусками любой длины ([onAudio]) и режется на кадры модели ([SpeechProbability.frameSamples]).
 * Время — по звуку (номер кадра), а не по часам: так детектор одинаково ведёт себя в приложении, в тестах
 * и на записях S03b.
 * - кадр с `p ≥ speechProb` — речь;
 * - речь уже была ([heardSpeech]):
 *   - с [rule] (приложение, S04) — тишина-кандидат и судья по тексту, предел ([TurnEndRule]);
 *   - без него (режим записи S03b) — конец ([TurnEnd.SILENCE]) через [silenceMs] без речи после последнего кадра речи;
 * - речи ещё не было — через [startSilenceMs] без речи (с начала записи или после последнего кадра речи):
 *   ученик молчит, запись тоже кончается (D70). Кроме записи-продолжения ([TurnContext.continuation]): её кончает
 *   контроллер, когда звучит учитель;
 * - речи набралось на [heardSpeech] — [TurnContext.onHeardSpeech] (один раз за запись);
 * - [heardSpeech] — речи набралось не меньше [minSpeechMs];
 * - предохранитель: [maxMs] звука — [TurnEnd.LIMIT] (D31).
 * Время вывода модели на кадр копится в [stats] (журнал).
 */
class VadTurnDetector(
    private val vad: SpeechProbability,
    private val sampleRate: Int = ModelConfig.RECORD_SAMPLE_RATE,
    private val speechProb: Float = ModelConfig.VAD_SPEECH_PROB,
    private val silenceMs: Long = ModelConfig.TURN_END_SILENCE_MS,
    private val startSilenceMs: Long = ModelConfig.TURN_START_SILENCE_MS,
    private val minSpeechMs: Long = ModelConfig.TURN_MIN_SPEECH_MS,
    private val maxMs: Long = ModelConfig.RECORD_MAX_MS.toLong(),
    private val nanoTime: () -> Long = System::nanoTime,
    /** Конец реплики по тексту (S04); `null` — только тишина [silenceMs] (режим записи S03b). */
    private val rule: TurnEndRule? = null,
) : TurnDetector {

    private var context = TurnContext()
    private var heardReported = false

    private val frame = ShortArray(vad.frameSamples)
    private var filled = 0
    private var frames = 0
    private var speechFrames = 0
    private var framesSinceSpeech = 0
    private var inferNsTotal = 0L
    private var inferNsMax = 0L

    /** Кто ещё хочет знать вероятность каждого кадра (проверка на записях S03b). */
    var onFrame: ((index: Int, probability: Float) -> Unit)? = null

    /** Конец звука последнего полного кадра, мс. */
    val audioMs: Long get() = framesToMs(frames)

    /** Где (мс звука, конец кадра) реплика кончилась впервые после [reset]; `null` — ещё не кончилась. */
    var endMs: Long? = null
        private set

    override val heardSpeech: Boolean get() = framesToMs(speechFrames) >= minSpeechMs

    override val stats: TurnStats
        get() = TurnStats(
            speechMs = framesToMs(speechFrames),
            frames = frames,
            avgFrameMs = if (frames == 0) 0.0 else inferNsTotal / 1e6 / frames,
            maxFrameMs = inferNsMax / 1e6,
            endWaitMs = framesToMs(framesSinceSpeech),
        )

    override fun setContext(context: TurnContext) {
        this.context = context
    }

    override fun reset() {
        heardReported = false
        rule?.onSpeech()
        vad.reset()
        filled = 0
        frames = 0
        speechFrames = 0
        framesSinceSpeech = 0
        inferNsTotal = 0
        inferNsMax = 0
        endMs = null
    }

    /** Кусок обрабатывается целиком (время звука не теряется); ответ — первый «конец» внутри куска. */
    override fun onAudio(pcm: ShortArray, count: Int): TurnEnd? {
        var result: TurnEnd? = null
        var i = 0
        while (i < count) {
            val n = minOf(count - i, frame.size - filled)
            System.arraycopy(pcm, i, frame, filled, n)
            filled += n
            i += n
            if (filled == frame.size) {
                filled = 0
                val end = onFullFrame()
                if (end != null && result == null) result = end
            }
        }
        return result
    }

    private fun onFullFrame(): TurnEnd? {
        val started = nanoTime()
        val p = vad.probability(frame)
        val ns = nanoTime() - started
        inferNsTotal += ns
        if (ns > inferNsMax) inferNsMax = ns
        onFrame?.invoke(frames, p)
        frames++
        val speech = p >= speechProb
        if (speech) {
            speechFrames++
            framesSinceSpeech = 0
            rule?.onSpeech()
            if (heardSpeech && !heardReported) {
                heardReported = true
                context.onHeardSpeech()
            }
        } else {
            framesSinceSpeech++
        }
        val silence = framesToMs(framesSinceSpeech)
        val end = when {
            audioMs >= maxMs -> TurnEnd.LIMIT
            speech -> null
            !heardSpeech -> if (!context.continuation && silence >= startSilenceMs) TurnEnd.SILENCE else null
            rule != null -> rule.onSilence(silence, context.text, context.onDecision)
            silence >= silenceMs -> TurnEnd.SILENCE
            else -> null
        }
        if (end != null && endMs == null) endMs = audioMs
        return end
    }

    private fun framesToMs(n: Int): Long = n.toLong() * frame.size * 1000 / sampleRate
}
