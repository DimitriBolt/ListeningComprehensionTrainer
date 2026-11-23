package com.example.listeningtrainer.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class PcmLevelTest {

    /** Синус [freq] Гц амплитудой [amplitude] отсчётов, кусок [ms] мс при частоте записи [rate]. */
    private fun sine(rate: Int, freq: Double, amplitude: Double, ms: Int): ShortArray =
        ShortArray(rate * ms / 1000) { (amplitude * sin(2 * PI * freq * it / rate)).roundToInt().toShort() }

    @Test
    fun `the same sine gives the same level at 16 and 44_1 kHz`() {
        for (amplitude in listOf(300.0, 1200.0, 8000.0)) {
            // Куски той же длительности, что и при записи (80 мс).
            val at16 = PcmLevel.db(sine(16_000, 440.0, amplitude, 80))
            val at44 = PcmLevel.db(sine(44_100, 440.0, amplitude, 80))
            val expected = 20 * log10(amplitude / sqrt(2.0))
            assertEquals(expected, at16.toDouble(), 0.05)
            assertEquals(expected, at44.toDouble(), 0.05)
        }
    }

    @Test
    fun `threshold 59 dB sits between quiet room and speech as before`() {
        // 59 дБ ≈ амплитуда синуса 1260 отсчётов: тише — тишина, громче — речь.
        assertEquals(true, PcmLevel.db(sine(16_000, 300.0, 1100.0, 80)) < 59f)
        assertEquals(true, PcmLevel.db(sine(16_000, 300.0, 1400.0, 80)) > 59f)
    }

    @Test
    fun `only the first count samples are measured, silence is 0`() {
        val buf = sine(16_000, 440.0, 1000.0, 80)
        val half = buf.size / 2
        buf.fill(0, half)
        assertEquals(PcmLevel.db(buf.copyOf(half)), PcmLevel.db(buf, half), 0.001f)
        assertEquals(0f, PcmLevel.db(ShortArray(100)), 0f)
        assertEquals(0f, PcmLevel.db(ShortArray(0)), 0f)
    }
}
