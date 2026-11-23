package com.example.listeningtrainer.audio

import com.example.listeningtrainer.conversation.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

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
    fun `tick and pulse are the quiet ones, nothing clips`() {
        // «Пульс» — короткий удар: пик выше, чем у ровного тона, поэтому сравниваем среднюю громкость.
        val quiet = listOf(Cue.WAITING, Cue.PULSE)
        val loudest = quiet.maxOf { rmsDb(it) }
        for (cue in Cue.values().toList() - quiet) assertTrue("$cue", rmsDb(cue) > loudest + 6)
        for (cue in Cue.values()) assertTrue("$cue", peak(cue) < Short.MAX_VALUE / 2)
    }

    private fun rmsDb(cue: Cue): Double {
        val pcm = CueTones.render(cue)
        return 20 * log10(sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size) / Short.MAX_VALUE)
    }

    @Test
    fun `pulse is short and as quiet as the tick or a bit quieter`() {
        assertTrue(CueTones.durationMs(Cue.PULSE) <= 80)
        val diff = rmsDb(Cue.PULSE) - rmsDb(Cue.WAITING)
        assertTrue("pulse vs tick $diff dB", diff <= 0.0 && diff >= -6.0)
    }

    @Test
    fun `pulse differs from the tick - lower, gliding down, fading`() {
        val pulse = CueTones.notes(Cue.PULSE).single()
        val tick = CueTones.notes(Cue.WAITING).single()
        assertTrue(pulse.hz < tick.hz / 2)
        assertTrue(pulse.glideToHz in 1.0..pulse.hz)
        assertTrue(pulse.decayMs > 0 && tick.decayMs == 0.0)
        // «тук» в конце почти затих: последние 10 мс тише первых 10 мс хотя бы в 5 раз
        val pcm = CueTones.render(Cue.PULSE)
        val n = CueTones.SAMPLE_RATE / 100
        fun peakOf(r: IntRange) = r.maxOf { abs(pcm[it].toInt()) }
        assertTrue(peakOf(0 until n) > 5 * peakOf(pcm.size - n until pcm.size))
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
