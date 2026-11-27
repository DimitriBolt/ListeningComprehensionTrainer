package com.example.listeningtrainer.conversation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInTest {

    /** Вероятность речи по номеру кадра. */
    private class ScriptVad(private val p: (Int) -> Float) : SpeechProbability {
        override val frameSamples = 512
        var frame = 0
        var resets = 0
        override fun reset() { resets++; frame = 0 }
        override fun probability(frame: ShortArray): Float = p(this.frame++)
    }

    private fun feed(d: BargeInDetector, frames: Int, chunk: Int = 1280): Int? {
        val pcm = ShortArray(chunk) { (it % 100).toShort() }
        var sent = 0
        while (sent < frames * 512) {
            val n = minOf(chunk, frames * 512 - sent)
            if (d.onAudio(pcm, n)) return sent + n
            sent += n
        }
        return null
    }

    @Test
    fun `ring buffer keeps the newest samples in order`() {
        val r = RingBuffer(5)
        r.write(shortArrayOf(1, 2, 3), 3)
        assertArrayEquals(shortArrayOf(1, 2, 3), r.snapshot())
        r.write(shortArrayOf(4, 5, 6, 7), 4)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), r.snapshot())
        r.write(shortArrayOf(10, 11, 12, 13, 14, 15, 16), 7) // кусок длиннее буфера — его хвост
        assertArrayEquals(shortArrayOf(12, 13, 14, 15, 16), r.snapshot())
        r.write(shortArrayOf(9, 9, 9), 1) // только count отсчётов
        assertArrayEquals(shortArrayOf(13, 14, 15, 16, 9), r.snapshot())
        r.clear()
        assertEquals(0, r.snapshot().size)
    }

    @Test
    fun `300 ms of speech in a row at p of 0_8 or more - barge-in, with the preroll of 500 ms`() {
        // тишина 1 с (31 кадр), затем речь
        val d = BargeInDetector(ScriptVad { if (it >= 31) 0.9f else 0.1f })
        d.reset()
        assertNull(feed(d, 31))
        assertFalse(d.hit != null)
        assertTrue(feed(d, 20) != null)
        val hit = d.hit!!
        assertEquals(320, hit.speechMs) // 10 кадров по 32 мс — первые ≥ 300
        assertEquals(41L * 32, hit.atMs)
        assertEquals(0.9f, hit.meanP, 1e-6f)
        assertEquals(8_000, d.preroll().size) // 500 мс при 16 кГц
        assertFalse(d.onAudio(ShortArray(512), 512)) // после решения — ничего
    }

    @Test
    fun `a dip below the threshold starts the count again, p between 0_5 and 0_8 is only a leak`() {
        // 9 кадров речи, 1 кадр 0.6, 9 кадров речи, 30 кадров 0.6
        val d = BargeInDetector(ScriptVad { i ->
            when {
                i < 9 -> 0.9f
                i == 9 -> 0.6f
                i < 19 -> 0.9f
                else -> 0.6f
            }
        })
        d.reset()
        assertNull(feed(d, 49))
        val m = d.stats
        assertEquals(49, m.speechFrames)
        assertEquals(49L * 32, m.longestSpeechMs) // утечка p ≥ 0,5 подряд
        assertEquals(0.9f, m.maxP, 1e-6f)
        assertEquals(49L * 32, m.listenedMs)
    }

    @Test
    fun `frames while a cue sounds are not counted`() {
        var cue = true
        val d = BargeInDetector(ScriptVad { 0.95f }, muted = { cue })
        d.reset()
        assertNull(feed(d, 30))
        assertEquals(30L * 32, d.stats.mutedMs)
        assertEquals(0, d.stats.speechFrames)
        cue = false
        assertTrue(feed(d, 10) != null)
        assertEquals(320, d.hit!!.speechMs)
    }

    @Test
    fun `mark - spoken part with the mark, nothing spoken - empty, unmarked for the prompt`() {
        assertEquals("Many birds sing [interrupted by the student]", BargeIn.marked(" Many birds sing "))
        assertEquals("", BargeIn.marked("  "))
        assertEquals("Many birds sing", BargeIn.unmarked("Many birds sing [interrupted by the student]"))
        assertEquals("Hello.", BargeIn.unmarked("Hello."))
    }
}
