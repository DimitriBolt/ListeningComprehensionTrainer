package com.example.listeningtrainer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugExportTest {

    private fun name(ms: Long) = "debug_speech_$ms.wav"

    @Test
    fun `only the newest copies are kept`() {
        val names = (1L..25L).map { name(1_763_530_265_000L + it * 1000) }.shuffled()
        val old = oldCopies(names, keep = 20)
        assertEquals((1L..5L).map { name(1_763_530_265_000L + it * 1000) }.sortedDescending(), old)
    }

    @Test
    fun `nothing is deleted while there are 20 or fewer`() {
        val names = (1L..20L).map { name(1_763_530_265_000L + it) }
        assertEquals(emptyList<String>(), oldCopies(names, keep = 20))
        assertEquals(emptyList<String>(), oldCopies(emptyList(), keep = 20))
    }
}
