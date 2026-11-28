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
    fun `600 ms of speech in a row at p of 0_8 or more - barge-in, with the preroll of 500 ms`() {
        // тишина 1 с (31 кадр), затем речь
        val d = BargeInDetector(ScriptVad { if (it >= 31) 0.9f else 0.1f })
        d.reset()
        assertNull(feed(d, 31))
        assertFalse(d.hit != null)
        assertTrue(feed(d, 30) != null)
        val hit = d.hit!!
        assertEquals(608, hit.speechMs) // 19 кадров по 32 мс — первые ≥ 600 (D90)
        assertEquals(50L * 32, hit.atMs)
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
        assertTrue(feed(d, 20) != null)
        assertEquals(608, d.hit!!.speechMs)
    }

    @Test
    fun `mark - spoken part with the mark, nothing spoken - empty, unmarked for the prompt`() {
        assertEquals("Many birds sing [interrupted by the student]", BargeIn.marked(" Many birds sing "))
        assertEquals("", BargeIn.marked("  "))
        assertEquals("Many birds sing", BargeIn.unmarked("Many birds sing [interrupted by the student]"))
        assertEquals("Hello.", BargeIn.unmarked("Hello."))
    }

    // ---- вторая ступень: короткий звук (S05b, D85) ---------------------------------------------------------------

    /** Речь [frames] кадров с p 0.9 после 1 с тишины: (кандидат, перебивание). */
    private fun speech(frames: Int, minSpeechMs: Long = com.example.listeningtrainer.config.ModelConfig.BARGE_IN_SPEECH_MS): Pair<SoundCandidate?, BargeInHit?> {
        val d = BargeInDetector(ScriptVad { i -> if (i in 31 until 31 + frames) 0.9f else 0.1f }, minSpeechMs = minSpeechMs)
        val got = candidates(d)
        d.reset()
        feed(d, 31 + frames + 20)
        return got.singleOrNull() to d.hit
    }

    @Test
    fun `500 ms in a row - not a full barge-in, a candidate (D90)`() {
        val (c, hit) = speech(16) // 512 мс — «угу» пользователя
        assertNull(hit)
        assertEquals(512, c!!.speechMs)
    }

    @Test
    fun `650 ms in a row - a full barge-in, no candidate (D90)`() {
        val (c, hit) = speech(21) // 672 мс
        assertNull(c)
        assertEquals(608, hit!!.speechMs)
    }

    @Test
    fun `Stop and Again go to the second stage, a long phrase barges in 0_3 s later than in S05`() {
        val (stop, stopHit) = speech(7) // «Stop» ≈ 200 мс
        assertNull(stopHit)
        assertEquals(224, stop!!.speechMs)
        val (again, againHit) = speech(15) // «Again» ≈ 400–500 мс
        assertNull(againHit)
        assertEquals(480, again!!.speechMs)
        // «Wait, what is a lake?» ≈ 1,2 с: перебивание на 19-м кадре речи (608 мс), в S05 (300 мс) — на 10-м
        val (_, lake) = speech(38)
        val (_, lakeS05) = speech(38, minSpeechMs = 300)
        assertEquals((31L + 19) * 32, lake!!.atMs)
        assertEquals(288L, lake.atMs - lakeS05!!.atMs) // ≈ 0,3 с позже
    }

    private fun candidates(d: BargeInDetector): MutableList<SoundCandidate> =
        mutableListOf<SoundCandidate>().also { list -> d.onCandidate = { list += it } }

    @Test
    fun `short word below the barge-in rule - a candidate with 500 ms before and 300 ms of tail`() {
        // тишина 1 с, «Stop» 5 кадров (160 мс) p 0.85, тишина
        val d = BargeInDetector(ScriptVad { i -> if (i in 31..35) 0.85f else 0.1f })
        val got = candidates(d)
        d.reset()
        assertNull(feed(d, 31 + 5 + 9))
        assertTrue(got.isEmpty()) // хвост 300 мс ещё не набран (9 кадров = 288 мс)
        assertNull(feed(d, 1))
        val c = got.single()
        assertEquals(160, c.speechMs)
        assertEquals(0.85f, c.maxP, 1e-6f)
        // 16 кадров буфера (≥ 500 мс), 5 кадров звука, 10 кадров хвоста
        assertEquals((16 + 5 + 10) * 512, c.pcm.size)
        assertNull(d.hit) // учитель не перебит
    }

    @Test
    fun `two frames are not a candidate - less than 96 ms in the window`() {
        val d = BargeInDetector(ScriptVad { i -> if (i in 31..32) 0.9f else 0.1f })
        val got = candidates(d)
        d.reset()
        assertNull(feed(d, 60))
        assertTrue(got.isEmpty())
    }

    @Test
    fun `speech scattered over the window counts as one candidate`() {
        // 0.6 через кадр: 4 кадра речи в 8 кадрах — 128 мс в окне 600 мс
        val d = BargeInDetector(ScriptVad { i -> if (i in 31..38 && i % 2 == 1) 0.6f else 0.1f })
        val got = candidates(d)
        d.reset()
        assertNull(feed(d, 60))
        assertEquals(128, got.single().speechMs)
    }

    @Test
    fun `long murmur is cut at the limit and still goes as a candidate`() {
        val d = BargeInDetector(ScriptVad { i -> if (i >= 31) 0.6f else 0.1f })
        val got = candidates(d)
        d.reset()
        assertNull(feed(d, 31 + 47))
        assertEquals(1, got.size) // 47 кадров = 1504 мс ≥ 1500
    }

    @Test
    fun `barge-in - no candidate`() {
        val d = BargeInDetector(ScriptVad { i -> if (i >= 31) 0.9f else 0.1f })
        val got = candidates(d)
        d.reset()
        assertTrue(feed(d, 60) != null)
        assertTrue(got.isEmpty())
    }

    @Test
    fun `frames while a cue sounds do not make a candidate, a cue in the middle drops the sound`() {
        var cue = true
        val d = BargeInDetector(ScriptVad { i -> if (i in 31..35 || i in 61..65) 0.85f else 0.1f }, muted = { cue })
        val got = candidates(d)
        d.reset()
        assertNull(feed(d, 50)) // «Stop» во время сигнала
        assertTrue(got.isEmpty())
        cue = false
        assertNull(feed(d, 13)) // кадры 50..62: начало второго звука без сигнала
        cue = true
        assertNull(feed(d, 30)) // сигнал посреди звука — звук брошен
        assertTrue(got.isEmpty())
    }
}
