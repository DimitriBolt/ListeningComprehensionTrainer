package com.example.listeningtrainer.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Громкость голоса учителя. Чистый Kotlin.
 *
 * RMS считается только по звучащим кускам (окна 20 мс громче [ACTIVE_FLOOR_DBFS]): тишина в начале
 * и в конце группы и паузы между словами не занижают громкость. Нормализация — усиление до целевого
 * RMS (но не больше [maxGainDb]), затем ограничитель пика без перегрузки: огибающая усиления
 * плавно опускается перед пиком ([ATTACK_MS]) и плавно возвращается после ([RELEASE_MS]),
 * так что щелчков от резких скачков усиления нет. Края группы — короткое затухание ([EDGE_FADE_MS]).
 */
object Loudness {

    /** RMS и пик, дБFS (0 — максимум 16-битного звука). Тишина — [SILENCE_DBFS]. */
    data class Level(val rmsDbfs: Double, val peakDbfs: Double) {
        override fun toString() = "rms ${fmt(rmsDbfs)} dBFS, peak ${fmt(peakDbfs)} dBFS"
    }

    class Result(val pcm: ShortArray, val before: Level, val after: Level, val gainDb: Double)

    const val SILENCE_DBFS = -96.0
    private const val FULL_SCALE = 32768.0
    private const val WINDOW_MS = 20
    private const val ACTIVE_FLOOR_DBFS = -50.0
    private const val ATTACK_MS = 5.0
    private const val RELEASE_MS = 80.0
    private const val EDGE_FADE_MS = 3

    fun measure(pcm: ShortArray, sampleRate: Int): Level {
        if (pcm.isEmpty()) return Level(SILENCE_DBFS, SILENCE_DBFS)
        var peak = 0
        for (s in pcm) peak = max(peak, abs(s.toInt()))
        val window = max(1, sampleRate * WINDOW_MS / 1000)
        var activeSum = 0.0
        var activeCount = 0L
        var allSum = 0.0
        var i = 0
        while (i < pcm.size) {
            val end = min(pcm.size, i + window)
            var sum = 0.0
            for (j in i until end) { val v = pcm[j].toDouble(); sum += v * v }
            allSum += sum
            if (db(sqrt(sum / (end - i))) > ACTIVE_FLOOR_DBFS) { activeSum += sum; activeCount += end - i }
            i = end
        }
        val rms = if (activeCount > 0) sqrt(activeSum / activeCount) else sqrt(allSum / pcm.size)
        return Level(db(rms), db(peak.toDouble()))
    }

    fun normalize(
        pcm: ShortArray,
        sampleRate: Int,
        targetRmsDbfs: Double,
        peakCeilingDbfs: Double,
        maxGainDb: Double,
    ): Result {
        val before = measure(pcm, sampleRate)
        if (pcm.isEmpty() || before.peakDbfs <= SILENCE_DBFS) return Result(pcm, before, before, 0.0)

        val gainDb = min(maxGainDb, targetRmsDbfs - before.rmsDbfs)
        val gain = 10.0.pow(gainDb / 20)
        val ceiling = 10.0.pow(peakCeilingDbfs / 20) * FULL_SCALE
        val x = DoubleArray(pcm.size) { pcm[it] * gain }

        // Огибающая ограничителя: нужное усиление в каждой точке, затем плавный возврат вперёд
        // (release) и плавный спуск назад, к пику (attack). Оба прохода только уменьшают значения,
        // поэтому после умножения ни одна точка не выше потолка.
        val g = DoubleArray(x.size) { val a = abs(x[it]); if (a > ceiling) ceiling / a else 1.0 }
        val release = 1 - exp(-1.0 / (RELEASE_MS * sampleRate / 1000))
        for (i in 1 until g.size) g[i] = min(g[i], g[i - 1] + (1 - g[i - 1]) * release)
        val attackStep = 1.0 / max(1.0, ATTACK_MS * sampleRate / 1000)
        for (i in g.size - 2 downTo 0) g[i] = min(g[i], g[i + 1] + attackStep)

        val fade = min(EDGE_FADE_MS * sampleRate / 1000, x.size / 2)
        val out = ShortArray(x.size) { i ->
            val edge = when {
                fade == 0 -> 1.0
                i < fade -> i.toDouble() / fade
                i >= x.size - fade -> (x.size - 1 - i).toDouble() / fade
                else -> 1.0
            }
            (x[i] * g[i] * edge).roundToInt().coerceIn(-32767, 32767).toShort()
        }
        return Result(out, before, measure(out, sampleRate), gainDb)
    }

    private fun db(amplitude: Double): Double =
        if (amplitude <= 0.0) SILENCE_DBFS else max(SILENCE_DBFS, 20 * log10(amplitude / FULL_SCALE))

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}
