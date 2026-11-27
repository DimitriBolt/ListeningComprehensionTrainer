package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.ModelConfig

/**
 * Перебивание (S05, D81): ученик заговорил, пока звучит учитель, — учитель замолкает и слушает. Чистый Kotlin (инвариант 3).
 */
object BargeIn {
    /**
     * Пометка в истории учителя после прозвучавшей части прерванного ответа (D81). Её видит только модель учителя:
     * вслух и на экране её нет. Проба `s05 --part interrupted` читает её отсюда.
     */
    const val INTERRUPTED_MARK = "[interrupted by the student]"

    /** Прозвучавшая часть ответа с пометкой; ничего не прозвучало — пустая строка (ответа в истории нет). */
    fun marked(spoken: String): String = spoken.trim().let { if (it.isEmpty()) "" else "$it $INTERRUPTED_MARK" }

    /** Текст ответа без пометки (для подсказки распознаванию — последней фразы учителя). */
    fun unmarked(text: String): String = text.trim().removeSuffix(INTERRUPTED_MARK).trim()
}

/** Последние [capacity] отсчётов звука (S05: начало реплики ученика при перебивании). */
class RingBuffer(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val data = ShortArray(capacity)
    private var start = 0
    /** Сколько отсчётов сейчас в буфере (не больше [capacity]). */
    var size = 0
        private set

    fun write(pcm: ShortArray, count: Int) {
        var from = 0
        var n = count
        if (n >= capacity) { // кусок длиннее буфера — остаётся его хвост
            from = count - capacity
            n = capacity
            start = 0
            size = 0
        }
        for (i in 0 until n) {
            val at = (start + size) % capacity
            data[at] = pcm[from + i]
            if (size < capacity) size++ else start = (start + 1) % capacity
        }
    }

    /** Содержимое от старого к новому. */
    fun snapshot(): ShortArray = ShortArray(size) { data[(start + it) % capacity] }

    fun clear() {
        start = 0
        size = 0
    }
}

/** Что слышал микрофон, пока звучал учитель (журнал `mic during teacher`, замер утечки голоса учителя). */
data class MicDuringTeacher(
    /** Сколько мс звука прошло через детектор (кадры во время сигналов — тоже). */
    val listenedMs: Long = 0,
    /** Сколько мс пропущено: звучал сигнал. */
    val mutedMs: Long = 0,
    val maxP: Float = 0f,
    /** Кадров с `p ≥ 0,5` (как речь у детектора конца реплики). */
    val speechFrames: Int = 0,
    /** Самая длинная речь подряд при `p ≥ 0,5`, мс. */
    val longestSpeechMs: Long = 0,
)

/**
 * Короткий звук посреди ответа (S05b, D85), не дотянувший до правила перебивания: [pcm] — кусок для распознавания
 * (буфер до звука, звук и хвост тишины), [speechMs] — речи в нём (`p ≥ 0,5`), [maxP] — наибольшая `p`.
 */
class SoundCandidate(val pcm: ShortArray, val speechMs: Long, val maxP: Float)

/** Решение «перебил»: где (мс звука от начала слушания), средняя `p` и длина речи подряд, по которой решено. */
data class BargeInHit(val atMs: Long, val meanP: Float, val speechMs: Long)

/** Слушатель микрофона, пока звучит учитель (S05). Вызывается из потока записи. */
interface MicWatch {
    /** Очередной кусок звука; `true` — ученик перебил (один раз, дальше кусков не будет). */
    fun onAudio(pcm: ShortArray, count: Int): Boolean

    /** Звук до решения (кольцевой буфер) — начало записи реплики. */
    fun preroll(): ShortArray
}

/**
 * Перебивание по правилу (S05, бриф п. 1): кадры Silero ([SpeechProbability]) подряд с `p ≥` [minProb] набрали
 * [minSpeechMs] — ученик перебил. Кадры, пока звучит сигнал ([muted]), не считаются: счёт подряд обнуляется. Время — по
 * звуку (номер кадра), как у [VadTurnDetector]. Кольцевой буфер ([prerollMs]) пишется всегда.
 *
 * Вторая ступень (S05b, D85): звук — от первого кадра с `p ≥` [statProb] до [candidateTailMs] подряд без него (или
 * [candidateMaxMs] звука). Если в нём кадры речи набрали [candidateMinMs] в каком-нибудь окне [candidateWindowMs], а
 * перебивания не было, — [onCandidate] с куском: [prerollMs] до звука, звук и хвост. Звук при сигнале бросается.
 */
class BargeInDetector(
    private val vad: SpeechProbability,
    private val muted: () -> Boolean = { false },
    private val sampleRate: Int = ModelConfig.RECORD_SAMPLE_RATE,
    private val minProb: Float = ModelConfig.BARGE_IN_PROB,
    private val minSpeechMs: Long = ModelConfig.BARGE_IN_SPEECH_MS,
    prerollMs: Long = ModelConfig.BARGE_IN_PREROLL_MS,
    private val statProb: Float = ModelConfig.VAD_SPEECH_PROB,
    private val candidateMinMs: Long = ModelConfig.BARGE_IN_CANDIDATE_SPEECH_MS,
    private val candidateWindowMs: Long = ModelConfig.BARGE_IN_CANDIDATE_WINDOW_MS,
    private val candidateTailMs: Long = ModelConfig.BARGE_IN_CANDIDATE_TAIL_MS,
    private val candidateMaxMs: Long = ModelConfig.BARGE_IN_CANDIDATE_MAX_MS,
    /** Короткий звук (вызывается из потока записи); `null` — без второй ступени. */
    var onCandidate: ((SoundCandidate) -> Unit)? = null,
) : MicWatch {

    private val ring = RingBuffer((prerollMs * sampleRate / 1000).toInt().coerceAtLeast(1))
    private val frame = ShortArray(vad.frameSamples)
    private var filled = 0
    private var frames = 0
    private var mutedFrames = 0
    private var maxP = 0f
    private var speechFrames = 0
    private var run = 0
    private var longestRun = 0
    private var hitRun = 0
    private var hitSum = 0f
    /** Кадры до звука (буфер куска кандидата), от старого к новому. */
    private val recent = ArrayDeque<ShortArray>()
    private val prerollFrames = ((prerollMs * sampleRate / 1000 + vad.frameSamples - 1) / vad.frameSamples).toInt()
    /** Идущий звук: кадры (с буфером), речь ли каждый кадр после начала, тишина подряд, наибольшая `p`. */
    private var sound: MutableList<ShortArray>? = null
    private val soundSpeech = mutableListOf<Boolean>()
    private var soundSilence = 0
    private var soundMaxP = 0f

    /** Решение «перебил»; после него кадры не считаются. */
    var hit: BargeInHit? = null
        private set

    val stats: MicDuringTeacher
        get() = MicDuringTeacher(ms(frames), ms(mutedFrames), maxP, speechFrames, ms(longestRun))

    fun reset() {
        vad.reset()
        ring.clear()
        filled = 0; frames = 0; mutedFrames = 0; maxP = 0f; speechFrames = 0; run = 0; longestRun = 0
        hitRun = 0; hitSum = 0f; hit = null
        recent.clear(); dropSound()
    }

    private fun dropSound() {
        sound = null
        soundSpeech.clear()
        soundSilence = 0
        soundMaxP = 0f
    }

    override fun preroll(): ShortArray = ring.snapshot()

    override fun onAudio(pcm: ShortArray, count: Int): Boolean {
        if (hit != null) return false
        ring.write(pcm, count)
        var i = 0
        while (i < count) {
            val n = minOf(count - i, frame.size - filled)
            System.arraycopy(pcm, i, frame, filled, n)
            filled += n
            i += n
            if (filled == frame.size) {
                filled = 0
                if (onFrame()) return true
            }
        }
        return false
    }

    private fun onFrame(): Boolean {
        val p = vad.probability(frame) // модель видит каждый кадр: её состояние идёт подряд
        frames++
        if (muted()) {
            mutedFrames++
            run = 0; hitRun = 0; hitSum = 0f
            recent.clear(); dropSound() // звук сигнала — не кандидат
            return false
        }
        trackSound(p)
        if (p > maxP) maxP = p
        if (p >= statProb) {
            speechFrames++
            run++
            if (run > longestRun) longestRun = run
        } else {
            run = 0
        }
        if (p >= minProb) {
            hitRun++
            hitSum += p
            if (ms(hitRun) >= minSpeechMs) {
                hit = BargeInHit(ms(frames), hitSum / hitRun, ms(hitRun))
                return true
            }
        } else {
            hitRun = 0
            hitSum = 0f
        }
        return false
    }

    /** Вторая ступень (D85): звук кадр за кадром; кончился — оценить и, может быть, отдать кандидата. */
    private fun trackSound(p: Float) {
        val speech = p >= statProb
        val current = sound
        if (current == null) {
            if (speech && onCandidate != null) {
                sound = recent.toMutableList().also { it += frame.copyOf() }
                soundSpeech += true
                soundMaxP = p
            }
        } else {
            current += frame.copyOf()
            soundSpeech += speech
            if (p > soundMaxP) soundMaxP = p
            soundSilence = if (speech) 0 else soundSilence + 1
            if (ms(soundSilence) >= candidateTailMs || ms(soundSpeech.size) >= candidateMaxMs) finishSound()
        }
        recent.addLast(frame.copyOf())
        while (recent.size > prerollFrames) recent.removeFirst()
    }

    private fun finishSound() {
        val frames = sound ?: return
        val window = maxOf(1, (candidateWindowMs * sampleRate / 1000 / this.frame.size).toInt())
        var inWindow = 0
        var best = 0
        for (i in soundSpeech.indices) {
            if (soundSpeech[i]) inWindow++
            if (i >= window && soundSpeech[i - window]) inWindow--
            if (inWindow > best) best = inWindow
        }
        val speechMs = ms(soundSpeech.count { it })
        val maxP = soundMaxP
        dropSound()
        if (ms(best) < candidateMinMs) return
        val pcm = ShortArray(frames.sumOf { it.size })
        var at = 0
        for (f in frames) {
            System.arraycopy(f, 0, pcm, at, f.size)
            at += f.size
        }
        onCandidate?.invoke(SoundCandidate(pcm, speechMs, maxP))
    }

    private fun ms(n: Int): Long = n.toLong() * frame.size * 1000 / sampleRate
}
