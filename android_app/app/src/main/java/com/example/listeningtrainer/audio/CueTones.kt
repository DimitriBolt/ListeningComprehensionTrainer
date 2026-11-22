package com.example.listeningtrainer.audio

import com.example.listeningtrainer.conversation.Cue
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Как звучат сигналы. Каждый короче 0,5 с, без речи, на слух отличается от остальных.
 * Звук генерируется здесь же (PCM 16 бит, моно), без файлов и без сети. Чистый Kotlin.
 *
 * | Сигнал | Звук |
 * |---|---|
 * | LISTEN «говори» | две ноты вверх, 880 → 1320 Гц |
 * | PROCESSING «обрабатываю» | одна нота 660 Гц |
 * | WAITING «тик» | очень короткий тихий щелчок 1200 Гц |
 * | NOT_HEARD «не услышал» | две одинаковые ноты 494 Гц |
 * | ERROR «ошибка» | две низкие «жужжащие» ноты (с обертонами) 196 → 185 Гц |
 * | END «конец» | три ноты вниз, 784 → 659 → 523 Гц, последняя длиннее |
 */
object CueTones {

    const val SAMPLE_RATE = 44_100

    /** Нота: [hz] = 0 — пауза. [buzz] — с нечётными обертонами (резкий звук ошибки). */
    data class Note(val hz: Double, val ms: Int, val amplitude: Double = 0.0, val buzz: Boolean = false)

    /**
     * Громкость сигналов — чуть тише голоса учителя (его RMS приводится к −16 дБFS):
     * синус 0,14 — RMS ≈ −20 дБFS, пик ≈ −17 дБFS. «Тик» — на 10 дБ тише.
     */
    const val LOUD = 0.14
    const val QUIET = 0.044
    private const val FADE_MS = 5

    fun notes(cue: Cue): List<Note> = when (cue) {
        Cue.LISTEN -> listOf(Note(880.0, 70, LOUD), Note(0.0, 30), Note(1320.0, 90, LOUD))
        Cue.PROCESSING -> listOf(Note(660.0, 120, LOUD))
        Cue.WAITING -> listOf(Note(1200.0, 25, QUIET))
        Cue.NOT_HEARD -> listOf(Note(494.0, 110, LOUD), Note(0.0, 70), Note(494.0, 110, LOUD))
        Cue.ERROR -> listOf(Note(196.0, 150, LOUD, buzz = true), Note(0.0, 60), Note(185.0, 190, LOUD, buzz = true))
        Cue.END -> listOf(Note(784.0, 110, LOUD), Note(659.0, 110, LOUD), Note(523.0, 200, LOUD))
    }

    fun durationMs(cue: Cue): Int = notes(cue).sumOf { it.ms }

    /** Звук сигнала: PCM 16 бит, моно, [sampleRate]. */
    fun render(cue: Cue, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val notes = notes(cue)
        val out = ShortArray(notes.sumOf { it.ms * sampleRate / 1000 })
        var pos = 0
        for (note in notes) {
            val n = note.ms * sampleRate / 1000
            val fade = min(FADE_MS * sampleRate / 1000, n / 2)
            if (note.hz == 0.0) { pos += n; continue } // пауза — нули
            for (i in 0 until n) {
                val t = i.toDouble() / sampleRate
                val w = 2 * PI * note.hz * t
                var v = sin(w)
                if (note.buzz) v = (v + sin(3 * w) / 3 + sin(5 * w) / 5 + sin(7 * w) / 7) / 1.3
                // Плавные края, чтобы не было щелчков.
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i >= n - fade -> (n - 1 - i).toDouble() / fade
                    else -> 1.0
                }
                out[pos + i] = (v * env * note.amplitude * Short.MAX_VALUE).roundToInt().coerceIn(-32767, 32767).toShort()
            }
            pos += n
        }
        return out
    }
}
