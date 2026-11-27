package com.example.listeningtrainer.audio

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Пересчёт PCM 16 кГц → 24 кГц на лету (S03d, D6): звук записи для потокового распознавания, которое
 * принимает только 24 кГц. Чистый Kotlin, без Android.
 *
 * Полифазный фильтр 3/2 — тот же, что `scipy.signal.resample_poly(x, 3, 2)` в пробе S03b: вставить 2 нуля
 * между отсчётами (48 кГц), убрать всё выше 8 кГц фильтром нижних частот (`firwin(61, 1/3, window=("kaiser", 5))`),
 * взять каждый второй отсчёт. Состояние держится между кусками: куски любой длины дают тот же звук, что весь
 * файл целиком. Фильтр центрирован, как у `resample_poly`: отсчёт выхода выдаётся, когда пришли входные на
 * (TAPS − 1) / 2 отсчётов 48 кГц вперёд (≈ 0,6 мс); последние ≈ 10 отсчётов 24 кГц записи не выдаются.
 */
class Resampler16To24 {

    /** Последние входные отсчёты: хвост для фильтра на стыке кусков. */
    private val history = FloatArray(HISTORY)
    /** Номер следующего выходного отсчёта 24 кГц от начала звука. */
    private var outIndex = 0L
    /** Сколько входных отсчётов 16 кГц пришло от начала звука. */
    private var inCount = 0L

    fun reset() {
        history.fill(0f)
        outIndex = 0
        inCount = 0
    }

    /** Кусок [count] отсчётов 16 кГц → столько отсчётов 24 кГц, сколько уже можно посчитать (≈ 1,5 × count). */
    fun process(pcm: ShortArray, count: Int): ShortArray {
        if (count <= 0) return ShortArray(0)
        // Вход за всё время: history (последние HISTORY отсчётов до этого куска) + кусок.
        val buf = FloatArray(HISTORY + count)
        System.arraycopy(history, 0, buf, 0, HISTORY)
        for (i in 0 until count) buf[HISTORY + i] = pcm[i].toFloat()
        val firstIn = inCount - HISTORY // номер отсчёта buf[0] от начала звука
        inCount += count

        // Выходной отсчёт n (24 кГц) = отсчёт 2n сетки 48 кГц; ему нужны входы до (2n + HALF) / 3.
        val out = ShortArray(count * 3 / 2 + 2)
        var produced = 0
        while (true) {
            val m = 2 * outIndex          // позиция на сетке 48 кГц
            val lastNeeded = Math.floorDiv(m + HALF, 3L)
            if (lastNeeded >= inCount) break
            // y[m] = 3 · Σ h[k] · x48[m − k + HALF], x48[j] ≠ 0 только при j кратном 3 (j = 3i → x[i])
            var acc = 0.0
            val top = m + HALF
            var k = (top % 3).toInt() // первый k, при котором (top − k) кратно 3
            while (k < TAPS) {
                val i = (top - k) / 3 - firstIn
                if (i >= 0) acc += H[k] * buf[i.toInt()]
                k += 3
            }
            out[produced++] = (acc * 3).roundToInt().coerceIn(-32768, 32767).toShort()
            outIndex++
        }
        // Хвост для следующего куска — последние HISTORY отсчётов.
        System.arraycopy(buf, buf.size - HISTORY, history, 0, HISTORY)
        return if (produced == out.size) out else out.copyOf(produced)
    }

    private companion object {
        /** Длина фильтра на сетке 48 кГц (нечётная, чтобы задержка была целой). */
        const val TAPS = 61
        const val HALF = (TAPS - 1) / 2L
        /** Входных отсчётов в памяти фильтра. */
        const val HISTORY = TAPS / 3 + 2

        /** Окно Кайзера, как у `resample_poly`. */
        const val KAISER_BETA = 5.0

        /** Фильтр нижних частот: срез 8 кГц на 48 кГц, окно Кайзера, сумма 1 (`firwin` с `scale=True`). */
        val H: DoubleArray = run {
            val fc = 8_000.0 / 48_000.0
            val h = DoubleArray(TAPS) { k ->
                val x = k - HALF.toDouble()
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val r = 2.0 * k / (TAPS - 1) - 1
                sinc * besselI0(KAISER_BETA * sqrt(1 - r * r)) / besselI0(KAISER_BETA)
            }
            val sum = h.sum()
            DoubleArray(TAPS) { h[it] / sum }
        }

        /** Модифицированная функция Бесселя I₀ рядом (для окна Кайзера). */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-12 * sum) {
                term *= (x / (2 * k)) * (x / (2 * k))
                sum += term
                k++
            }
            return sum
        }
    }
}
