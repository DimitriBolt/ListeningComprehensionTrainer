package com.example.listeningtrainer.audio

import com.example.listeningtrainer.conversation.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CueTonesTest {

    private fun tones(cue: Cue) = CueTones.notes(cue).filter { it.hz > 0 }.map { it.hz }
    private fun peak(cue: Cue) = CueTones.render(cue).maxOf { abs(it.toInt()) }

    @Test
    fun `every cue is shorter than half a second`() {
        for (cue in Cue.values()) assertTrue("$cue", CueTones.durationMs(cue) <= 500)
    }

    @Test
    fun `rendered length matches duration`() {
        for (cue in Cue.values()) {
            assertEquals("$cue", CueTones.notes(cue).sumOf { it.ms * CueTones.SAMPLE_RATE / 1000 }, CueTones.render(cue).size)
        }
    }

    @Test
    fun `listen goes up, end goes down`() {
        val listen = tones(Cue.LISTEN)
        val end = tones(Cue.END)
        assertTrue(listen.zipWithNext().all { (a, b) -> b > a })
        assertTrue(end.size >= 3)
        assertTrue(end.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun `error is low and buzzy, unlike the others`() {
        val error = CueTones.notes(Cue.ERROR).filter { it.hz > 0 }
        assertTrue(error.all { it.buzz && it.hz < 250 })
        for (cue in Cue.values().toList() - Cue.ERROR) {
            assertTrue("$cue", CueTones.notes(cue).none { it.buzz })
            assertTrue("$cue", tones(cue).all { it > 400 })
        }
    }

    @Test
    fun `all cues sound different`() {
        val patterns = Cue.values().map { cue -> CueTones.notes(cue).map { it.hz to it.ms } }
        assertEquals(patterns.size, patterns.toSet().size)
    }

    @Test
    fun `tick is the quietest, nothing clips`() {
        val tick = peak(Cue.WAITING)
        for (cue in Cue.values().toList() - Cue.WAITING) assertTrue("$cue", peak(cue) > tick * 2)
        for (cue in Cue.values()) assertTrue("$cue", peak(cue) < Short.MAX_VALUE / 2)
    }

    @Test
    fun `sound starts and ends quietly - no clicks`() {
        for (cue in Cue.values()) {
            val pcm = CueTones.render(cue)
            assertTrue("$cue start", abs(pcm.first().toInt()) < 100)
            assertTrue("$cue end", abs(pcm.last().toInt()) < 100)
        }
    }
}
