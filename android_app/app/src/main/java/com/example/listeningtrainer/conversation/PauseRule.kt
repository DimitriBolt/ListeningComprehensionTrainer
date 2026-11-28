package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.ModelConfig
import kotlin.math.roundToLong

/**
 * Пауза после группы по потребности (S07, D97). Чистый Kotlin (инвариант 3). Копия — `speech_ms`, `pause_ms` в
 * `tools/probe/s07.py` (сверяет `tests/test_s07.py`).
 *
 * `пауза = множитель × (пауза по знаку + доля × звук группы)`, не больше [ModelConfig.PAUSE_MAX_MS]; «Slower» умножает
 * паузу на [ModelConfig.SLOWER_PAUSE_FACTOR] уже после предела (медленный повтор ученик просит сам и один раз).
 * Пауза по знаку — эталон озвучки ([SpeechPlanner]). Номер пункта и заголовок — по тому же правилу.
 */
object PauseRule {

    fun pauseMs(
        markMs: Long,
        audioMs: Long,
        scale: Double,
        factor: Double = 1.0,
        share: Double = ModelConfig.PAUSE_GROUP_SHARE,
        maxMs: Long = ModelConfig.PAUSE_MAX_MS,
    ): Long = (minOf(maxMs.toDouble(), scale * (markMs + share * audioMs)) * factor).roundToLong()

    /**
     * Звук группы без тишины по краям, мс: от первого до последнего окна [ModelConfig.PAUSE_WINDOW_MS], которое не тише
     * самого громкого окна больше чем на [ModelConfig.PAUSE_EDGE_DB] дБ. Щит Bluetooth сюда не входит (его добавляет
     * вывод звука). Пустой или беззвучный — 0.
     */
    fun speechMs(
        pcm: ShortArray,
        sampleRate: Int,
        windowMs: Int = ModelConfig.PAUSE_WINDOW_MS,
        edgeDb: Double = ModelConfig.PAUSE_EDGE_DB,
    ): Long {
        val n = sampleRate * windowMs / 1000
        if (n <= 0) return 0
        val k = pcm.size / n
        if (k == 0) return 0
        val energy = DoubleArray(k)
        for (w in 0 until k) {
            var e = 0.0
            for (i in w * n until (w + 1) * n) {
                val v = pcm[i].toDouble()
                e += v * v
            }
            energy[w] = e
        }
        val top = energy.max()
        if (top <= 0.0) return 0
        val floor = top * Math.pow(10.0, -edgeDb / 10)
        val first = energy.indexOfFirst { it >= floor }
        val last = energy.indexOfLast { it >= floor }
        return (last - first + 1).toLong() * windowMs
    }

    fun speechMs(audio: SpeechAudio): Long = speechMs(audio.pcm, audio.sampleRate)
}
