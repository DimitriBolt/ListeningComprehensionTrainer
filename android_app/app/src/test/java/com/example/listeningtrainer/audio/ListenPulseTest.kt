package com.example.listeningtrainer.audio

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
    fun `pulse marks reach the listener - nothing is masked any more`() {
        val marks = PulseMarks()
        marks.pulseStarted(1_000) // слушателя нет — ничего
        val got = mutableListOf<Long>()
        marks.onPulse = { got += it }
        marks.pulseStarted(2_000)
        assertEquals(listOf(2_000L), got)
    }
}
