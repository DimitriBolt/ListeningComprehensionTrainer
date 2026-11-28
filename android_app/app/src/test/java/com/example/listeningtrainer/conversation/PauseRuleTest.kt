package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** Пауза по потребности (S07, D97): знак + доля звука группы, × множитель, предел, «Slower» поверх. */
class PauseRuleTest {

    @Test
    fun `numbers come from ModelConfig - share 0_25, limit 3000 ms, windows 10 ms, edges 35 dB`() {
        assertEquals(0.25, ModelConfig.PAUSE_GROUP_SHARE, 0.0)
        assertEquals(3000L, ModelConfig.PAUSE_MAX_MS)
        assertEquals(10, ModelConfig.PAUSE_WINDOW_MS)
        assertEquals(35.0, ModelConfig.PAUSE_EDGE_DB, 0.0)
        assertEquals(1.5, ModelConfig.SLOWER_PAUSE_FACTOR, 0.0)
    }

    @Test
    fun `no sound - the pause by the mark, as the reference`() {
        for (mark in listOf(250L, 400L, 600L, 1000L, 1100L, 1600L)) assertEquals(mark, PauseRule.pauseMs(mark, 0, 1.0))
    }

    @Test
    fun `a share of the group sound is added - short group a little, long group more`() {
        assertEquals(1000L + 175L, PauseRule.pauseMs(1000, 700, 1.0))   // группа 0,7 с — +0,18 с
        assertEquals(1000L + 625L, PauseRule.pauseMs(1000, 2500, 1.0))  // группа 2,5 с — +0,63 с
        assertEquals(250L + 500L, PauseRule.pauseMs(250, 2000, 1.0))
    }

    @Test
    fun `the scale multiplies the whole pause`() {
        assertEquals(1725L, PauseRule.pauseMs(1000, 2000, 1.15))  // 1,15 × 1500
        assertEquals(1050L, PauseRule.pauseMs(1000, 2000, 0.7))
    }

    @Test
    fun `one pause is never longer than the limit`() {
        assertEquals(3000L, PauseRule.pauseMs(1600, 4000, 2.5))
        assertEquals(2999L, PauseRule.pauseMs(2999, 0, 1.0))
    }

    @Test
    fun `slower multiplies over the formula and over the limit`() {
        assertEquals(1515L, PauseRule.pauseMs(1000, 40, 1.0, ModelConfig.SLOWER_PAUSE_FACTOR))
        assertEquals(4500L, PauseRule.pauseMs(1600, 4000, 2.5, ModelConfig.SLOWER_PAUSE_FACTOR))
    }

    private fun tone(ms: Int, amp: Int, rate: Int = 24_000) = ShortArray(rate * ms / 1000) { i -> if (i % 2 == 0) amp.toShort() else (-amp).toShort() }

    @Test
    fun `group sound without silence at the edges`() {
        val pcm = ShortArray(24_000 * 300 / 1000) + tone(1200, 8000) + ShortArray(24_000 * 200 / 1000)
        assertEquals(1200L, PauseRule.speechMs(pcm, 24_000))
    }

    @Test
    fun `quiet breath at the edges is silence, quiet speech inside is speech`() {
        // −46 дБ относительно голоса по краям — тишина; −20 дБ внутри — речь
        val pcm = tone(100, 40) + tone(500, 8000) + tone(300, 800) + tone(500, 8000) + tone(100, 40)
        assertEquals(1300L, PauseRule.speechMs(pcm, 24_000))
    }

    @Test
    fun `empty or silent sound - 0`() {
        assertEquals(0L, PauseRule.speechMs(ShortArray(0), 24_000))
        assertEquals(0L, PauseRule.speechMs(ShortArray(24_000), 24_000))
        assertEquals(0L, PauseRule.speechMs(ShortArray(100) { 5000 }, 24_000)) // короче одного окна
    }
}
