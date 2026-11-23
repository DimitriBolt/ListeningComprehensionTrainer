package com.example.listeningtrainer.audio

import com.example.listeningtrainer.conversation.SilenceTurnDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenPulseTest {

    @Test
    fun `pulse only in headphones`() {
        assertTrue(ListenPulse.allowed(OutputKind.WIRED))
        assertTrue(ListenPulse.allowed(OutputKind.USB))
        assertTrue(ListenPulse.allowed(OutputKind.BLUETOOTH))
        assertFalse(ListenPulse.allowed(OutputKind.SPEAKER))
        assertFalse(ListenPulse.allowed(OutputKind.EARPIECE))
        assertFalse(ListenPulse.allowed(OutputKind.HDMI))
        assertFalse(ListenPulse.allowed(OutputKind.OTHER))
        assertFalse(ListenPulse.allowed(null))
    }

    @Test
    fun `gate masks windows that overlap the pulse`() {
        val gate = PulseEchoGate(maskMs = 350)
        assertFalse(gate.masked(1_000, 80)) // пульса ещё не было
        gate.pulseStarted(2_000)
        assertFalse(gate.masked(2_000, 80)) // кусок кончился до пульса
        assertTrue(gate.masked(2_010, 80))
        assertTrue(gate.masked(2_400, 80)) // начался в 2320 < 2350
        assertFalse(gate.masked(2_430, 80)) // начался в 2350
    }

    /**
     * Запись без речи: фон 40 дБ, а микрофон слышит каждый пульс на 70 дБ (выше порога 59)
     * в течение 300 мс. Пик остаётся фоном, речи нет, запись кончается по тишине через 3 с.
     */
    @Test
    fun `pulse heard by the microphone is not speech and does not stop the silence count`() {
        val gate = PulseEchoGate(maskMs = 350)
        val detector = SilenceTurnDetector()
        val meter = RecordingMeter(detector, gate, windowMs = 80)
        val start = 10_000L
        meter.start(start)
        val shown = mutableListOf<Float>()
        var endedAt: Long? = null
        var t = start
        while (endedAt == null && t < start + 10_000) {
            t += 80
            // пульс каждые 2 с от начала записи; поздний пульс (в 2,5 с) — как будто тишина ещё длится
            for (p in listOf(start + 2_000, start + 2_500)) if (t - 80 < p && t >= p) gate.pulseStarted(p)
            val echo = listOf(start + 2_000, start + 2_500).any { t > it && t - 80 < it + 300 }
            if (meter.onWindow(if (echo) 70f else 40f, t) { shown += it }) endedAt = t
        }
        assertFalse(meter.heardSpeech)
        assertEquals(40f, meter.peakDb)
        assertTrue(shown.all { it == 40f })
        assertTrue(meter.maskedWindows > 0)
        // тишина отсчитывается от начала записи, пульсы её не сбрасывают: конец через ≈ 3 с
        assertEquals(start + 3_040, endedAt)
    }

    @Test
    fun `speech is still heard between pulses`() {
        val gate = PulseEchoGate(maskMs = 350)
        val meter = RecordingMeter(SilenceTurnDetector(), gate, windowMs = 80)
        meter.start(0)
        gate.pulseStarted(2_000)
        meter.onWindow(70f, 2_080) { } // в пульсе — не речь
        assertFalse(meter.heardSpeech)
        meter.onWindow(72f, 2_800) { } // после пульса — речь
        assertTrue(meter.heardSpeech)
        assertEquals(72f, meter.peakDb)
    }

    @Test
    fun `without a gate every window counts`() {
        val meter = RecordingMeter(SilenceTurnDetector(), null, windowMs = 80)
        meter.start(0)
        meter.onWindow(70f, 80) { }
        assertTrue(meter.heardSpeech)
        assertEquals(0, meter.maskedWindows)
    }
}
