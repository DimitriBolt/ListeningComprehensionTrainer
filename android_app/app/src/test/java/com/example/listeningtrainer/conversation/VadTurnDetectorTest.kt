package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VadTurnDetectorTest {

    /** Вероятность кадра — по очереди из списка (дальше — последняя); кадры приходят целиком. */
    private class ScriptedVad(private val probs: List<Float>) : SpeechProbability {
        override val frameSamples = 512
        var resets = 0
        var calls = 0
        val sizes = mutableListOf<Int>()
        override fun reset() { resets++; calls = 0 }
        override fun probability(frame: ShortArray): Float {
            sizes += frame.size
            return probs[minOf(calls++, probs.lastIndex)]
        }
    }

    private val frame = ShortArray(512)

    /** Кадры по одному, пока детектор не скажет «конец»; ответ — (причина, номер кадра с 1). */
    private fun runFrames(d: VadTurnDetector, max: Int = 10_000): Pair<TurnEnd?, Int> {
        d.reset()
        for (i in 1..max) d.onAudio(frame, frame.size)?.let { return it to i }
        return null to max
    }

    @Test
    fun `silence from the start - ends after 3000 ms, no speech`() {
        val d = VadTurnDetector(ScriptedVad(listOf(0.1f)))
        val (end, n) = runFrames(d)
        assertEquals(TurnEnd.SILENCE, end)
        assertEquals(94, n) // 94 · 32 = 3008 мс ≥ 3000
        assertEquals(3_008L, d.audioMs)
        assertFalse(d.heardSpeech)
        assertEquals(0L, d.stats.speechMs)
    }

    @Test
    fun `speech then silence - ends 3000 ms after the last speech frame`() {
        // 10 кадров тишины, 20 речи (640 мс), потом тишина
        val d = VadTurnDetector(ScriptedVad(List(10) { 0.1f } + List(20) { 0.9f } + listOf(0.2f)))
        val (end, n) = runFrames(d)
        assertEquals(TurnEnd.SILENCE, end)
        assertEquals(30 + 94, n)
        assertTrue(d.heardSpeech)
        assertEquals(640L, d.stats.speechMs)
    }

    @Test
    fun `probability exactly at the threshold is speech, just below is not`() {
        val at = VadTurnDetector(ScriptedVad(List(7) { 0.5f } + listOf(0f)))
        runFrames(at)
        assertEquals(224L, at.stats.speechMs)
        val below = VadTurnDetector(ScriptedVad(List(7) { 0.4999f } + listOf(0f)))
        runFrames(below)
        assertEquals(0L, below.stats.speechMs)
    }

    @Test
    fun `heard speech needs at least 200 ms of speech frames`() {
        // 6 кадров = 192 мс — ещё молчание; 7 кадров = 224 мс — речь
        val six = VadTurnDetector(ScriptedVad(List(6) { 0.9f } + listOf(0f)))
        runFrames(six)
        assertFalse(six.heardSpeech)
        val seven = VadTurnDetector(ScriptedVad(List(7) { 0.9f } + listOf(0f)))
        runFrames(seven)
        assertTrue(seven.heardSpeech)
    }

    @Test
    fun `speech frames scattered in a pause reset the silence count`() {
        // речь, 2,5 с тишины, один кадр речи, снова тишина — конец через 3 с после этого кадра
        val probs = List(10) { 0.9f } + List(78) { 0.1f } + listOf(0.9f) + listOf(0.1f)
        val d = VadTurnDetector(ScriptedVad(probs))
        val (end, n) = runFrames(d)
        assertEquals(TurnEnd.SILENCE, end)
        assertEquals(10 + 78 + 1 + 94, n)
    }

    @Test
    fun `endless speech - the 90 s limit is in the detector`() {
        val d = VadTurnDetector(ScriptedVad(listOf(0.9f)))
        val (end, n) = runFrames(d, max = 5_000)
        assertEquals(TurnEnd.LIMIT, end)
        assertEquals(2_813, n) // 2813 · 32 = 90 016 мс ≥ 90 000
    }

    @Test
    fun `audio in arbitrary pieces is cut into 512-sample frames, the tail waits for the next piece`() {
        val vad = ScriptedVad(listOf(0.1f))
        val d = VadTurnDetector(vad)
        d.reset()
        val piece = ShortArray(1280) // 80 мс: 2,5 кадра
        assertNull(d.onAudio(piece, 1280))
        assertEquals(2, d.stats.frames)
        assertNull(d.onAudio(piece, 1280))
        assertEquals(5, d.stats.frames)
        assertNull(d.onAudio(piece, 100)) // count меньше размера буфера
        assertEquals(5, d.stats.frames)
        assertTrue(vad.sizes.all { it == 512 })
    }

    @Test
    fun `a piece is processed to its end even after the end of the turn - audio time is not lost`() {
        val d = VadTurnDetector(ScriptedVad(listOf(0.1f)))
        d.reset()
        val piece = ShortArray(1280)
        var end: TurnEnd? = null
        var pieces = 0
        while (end == null) { end = d.onAudio(piece, 1280); pieces++ }
        assertEquals(TurnEnd.SILENCE, end)
        assertEquals(38, pieces) // 37 кусков = 92 кадра, в 38-м — кадры 93…95, конец на 94-м
        assertEquals(3_008L, d.endMs)
        assertEquals(95, d.stats.frames)
        d.onAudio(piece, 1280) // дальше тишина — «конец» снова, но первый момент конца прежний
        assertEquals(3_008L, d.endMs)
        assertEquals(97, d.stats.frames)
    }

    @Test
    fun `reset starts a new turn - model state and counters from zero`() {
        val vad = ScriptedVad(List(20) { 0.9f } + listOf(0f))
        val d = VadTurnDetector(vad)
        runFrames(d)
        assertTrue(d.heardSpeech)
        d.reset()
        assertEquals(2, vad.resets)
        assertFalse(d.heardSpeech)
        assertEquals(TurnStats(), d.stats)
        d.onAudio(ShortArray(300), 300)
        d.reset()
        d.onAudio(ShortArray(300), 300)
        assertEquals(0, d.stats.frames) // хвост прошлой реплики не доклеился
    }

    @Test
    fun `inference time per frame - average and maximum`() {
        var now = 0L
        val steps = ArrayDeque(listOf(1_000_000L, 3_000_000L, 2_000_000L))
        val vad = object : SpeechProbability {
            override val frameSamples = 512
            override fun reset() {}
            override fun probability(frame: ShortArray): Float {
                now += steps.removeFirst()
                return 0f
            }
        }
        val d = VadTurnDetector(vad, nanoTime = { now })
        d.reset()
        repeat(3) { d.onAudio(frame, 512) }
        assertEquals(3, d.stats.frames)
        assertEquals(2.0, d.stats.avgFrameMs, 1e-9)
        assertEquals(3.0, d.stats.maxFrameMs, 1e-9)
    }
}
