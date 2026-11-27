package com.example.listeningtrainer.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Громкость куска записи в «дБ отсчётов»: 20·lg(RMS), где RMS — в единицах 16-битного отсчёта.
 * Шкала та же, что у прежнего порога 59 дБ (до S03c; теперь он только в режиме записи для сравнения —
 * [com.example.listeningtrainer.endpoint.LevelTrace]). Расчёт не зависит от частоты записи: один и тот же синус даёт одно и то же число
 * при 16 и 44,1 кГц (тест `PcmLevelTest`). Чистый Kotlin.
 */
object PcmLevel {
    fun db(samples: ShortArray, count: Int = samples.size): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / count)
        return (if (rms > 0) 20 * log10(rms) else 0.0).toFloat()
    }
}
