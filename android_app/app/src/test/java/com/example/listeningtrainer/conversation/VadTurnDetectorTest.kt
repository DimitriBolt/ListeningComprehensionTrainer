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
    fun `silence from the start - ends after 8000 ms, no speech (D70)`() {
        val d = VadTurnDetector(ScriptedVad(listOf(0.1f)))
        val (end, n) = runFrames(d)
        assertEquals(TurnEnd.SILENCE, end)
        assertEquals(250, n) // 250 · 32 = 8000 мс
        assertEquals(8_000L, d.audioMs)
        assertFalse(d.heardSpeech)
        assertEquals(0L, d.stats.speechMs)
    }

    @Test
    fun `a pause of 5 s before the answer does not end the recording - then 3000 ms after speech`() {
        // 5 с молчания (156 кадров), 20 кадров речи, тишина: конец через 3008 мс после речи, а не на 8 с
        val d = VadTurnDetector(ScriptedVad(List(156) { 0.1f } + List(20) { 0.9f } + listOf(0.1f)))
        val (end, n) = runFrames(d)
        assertEquals(TurnEnd.SILENCE, end)
        assertEquals(156 + 20 + 94, n)
        assertTrue(d.heardSpeech)
    }

    @Test
    fun `a blip under 200 ms is not speech - the 8000 ms rule counts from it`() {
        // 1 с тишины, 3 кадра «речи» (96 мс, шорох), тишина: речи нет, конец через 8000 мс после шороха
        val d = VadTurnDetector(ScriptedVad(List(31) { 0.1f } + List(3) { 0.9f } + listOf(0.1f)))
        val (end, n) = runFrames(d)
        assertEquals(TurnEnd.SILENCE, end)
        assertEquals(31 + 3 + 250, n)
        assertFalse(d.heardSpeech)
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
        // 10 кадров речи, потом тишина: конец на кадре 10 + 94 = 104 (3328 мс)
        val d = VadTurnDetector(ScriptedVad(List(10) { 0.9f } + listOf(0.1f)))
        d.reset()
        val piece = ShortArray(1280)
        var end: TurnEnd? = null
        var pieces = 0
        while (end == null) { end = d.onAudio(piece, 1280); pieces++ }
        assertEquals(TurnEnd.SILENCE, end)
        assertEquals(42, pieces) // 41 кусок = 102 кадра, в 42-м — кадры 103…105, конец на 104-м
        assertEquals(3_328L, d.endMs)
        assertEquals(105, d.stats.frames)
        d.onAudio(piece, 1280) // дальше тишина — «конец» снова, но первый момент конца прежний
        assertEquals(3_328L, d.endMs)
        assertEquals(107, d.stats.frames)
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

    // ---- S04: судья и запись-продолжение ----------------------------------------------------

    @Test
    fun `with the rule - a finished answer ends after 1000 ms, end_wait is the silence`() {
        val d = VadTurnDetector(ScriptedVad(List(20) { 0.9f } + listOf(0.1f)), rule = TurnEndRule())
        val decisions = mutableListOf<TurnDecision>()
        d.setContext(TurnContext(text = { "At noon" }, onDecision = { decisions += it }))
        val (end, n) = runFrames(d)
        assertEquals(TurnEnd.JUDGE, end)
        assertEquals(20 + 32, n) // 32 кадра = 1024 мс
        assertEquals(1024L, d.stats.endWaitMs)
        assertEquals(listOf(true), decisions.map { it.end })
    }

    @Test
    fun `with the rule - an unfinished thought waits for more speech, then ends`() {
        // «Yesterday I» — 4 с паузы — «went to the shop»
        val probs = List(20) { 0.9f } + List(125) { 0.1f } + List(20) { 0.9f } + listOf(0.1f)
        val d = VadTurnDetector(ScriptedVad(probs), rule = TurnEndRule())
        var frames = 0
        d.setContext(TurnContext(text = { if (frames < 150) "Yesterday I" else "Yesterday I went to the shop" }))
        d.reset()
        var end: TurnEnd? = null
        while (end == null && frames < 1000) { frames++; end = d.onAudio(frame, frame.size) }
        assertEquals(TurnEnd.JUDGE, end)
        assertEquals(20 + 125 + 20 + 32, frames)
    }

    @Test
    fun `with the rule - silence up to 8000 ms ends with LONG_SILENCE`() {
        val d = VadTurnDetector(ScriptedVad(List(20) { 0.9f } + listOf(0.1f)), rule = TurnEndRule())
        d.setContext(TurnContext(text = { "My favorite food is" }))
        val (end, n) = runFrames(d)
        assertEquals(TurnEnd.LONG_SILENCE, end)
        assertEquals(20 + 250, n)
    }

    @Test
    fun `continuation - no end on silence from the start, speech is reported once`() {
        val d = VadTurnDetector(ScriptedVad(List(400) { 0.1f } + List(20) { 0.9f } + listOf(0.1f)), rule = TurnEndRule())
        var heard = 0
        d.setContext(TurnContext(text = { "and then we watched a movie" }, continuation = true, onHeardSpeech = { heard++ }))
        val (end, n) = runFrames(d)
        assertEquals(TurnEnd.JUDGE, end) // 12,8 с молчания не кончили запись
        assertEquals(400 + 20 + 32, n)
        assertEquals(1, heard)
    }
}
