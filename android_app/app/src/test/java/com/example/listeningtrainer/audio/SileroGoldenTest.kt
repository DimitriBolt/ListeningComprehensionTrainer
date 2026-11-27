package com.example.listeningtrainer.audio

import com.example.listeningtrainer.conversation.TurnEnd
import com.example.listeningtrainer.conversation.VadTurnDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Модель из assets приложения — та же, что поедет на телефон. Тесты идут из каталога модуля `app`. */
internal object TestSilero {
    val model by lazy { SileroModel { File("src/main/assets/silero_vad.onnx").readBytes() } }

    fun resource(name: String): ByteArray =
        requireNotNull(TestSilero::class.java.getResourceAsStream(name)) { "no test resource $name" }.use { it.readBytes() }
}

/**
 * Golden-тест обёртки onnxruntime (S03c): синтезированный звук (`tools/probe/silero_golden.py` — фраза пробы
 * голосом espeak-ng, тишина, шум) даёт те же вероятности кадров, что обёртка пробы на Python. Голоса
 * пользователя здесь нет (D69).
 */
class SileroGoldenTest {
    private val audio = WavPcm.read(TestSilero.resource("/vad/golden.wav"))
    private val expected = String(TestSilero.resource("/vad/golden_probs.txt")).lines().filter { it.isNotBlank() }.map { it.toFloat() }

    @Test
    fun `frame probabilities match the Python probe`() {
        assertEquals(16_000, audio.sampleRate)
        val vad = SileroVad(TestSilero.model)
        vad.reset()
        val frame = ShortArray(vad.frameSamples)
        val got = (0 until audio.pcm.size / frame.size).map { i ->
            System.arraycopy(audio.pcm, i * frame.size, frame, 0, frame.size)
            vad.probability(frame)
        }
        assertEquals(expected.size, got.size)
        val worst = got.indices.maxOf { kotlin.math.abs(got[it] - expected[it]) }
        assertTrue("max |Δp| = $worst", worst < 1e-3)
        // одинаково решают «речь / не речь» по порогу приложения
        assertEquals(expected.map { it >= 0.5f }, got.map { it >= 0.5f })
    }

    @Test
    fun `reset clears the model state - the same audio gives the same probabilities again`() {
        val vad = SileroVad(TestSilero.model)
        val frame = audio.pcm.copyOfRange(16_000, 16_000 + 512) // кадр в середине фразы
        vad.reset()
        val first = (1..5).map { vad.probability(frame) }
        vad.reset()
        val again = (1..5).map { vad.probability(frame) }
        assertEquals(first, again)
    }

    @Test
    fun `detector on the golden sound - speech heard, ends 3 s after the phrase, noise is not speech`() {
        val d = VadTurnDetector(SileroVad(TestSilero.model))
        d.reset()
        var end: TurnEnd? = null
        var pos = 0
        while (end == null && pos < audio.pcm.size) {
            val n = minOf(1280, audio.pcm.size - pos)
            end = d.onAudio(audio.pcm.copyOfRange(pos, pos + n), n)
            pos += n
        }
        assertEquals(TurnEnd.SILENCE, end)
        assertTrue(d.heardSpeech)
        val lastSpeech = expected.indexOfLast { it >= 0.5f }
        assertEquals((lastSpeech + 1 + 94) * 32L, d.endMs)
        assertTrue(d.stats.frames > 0 && d.stats.maxFrameMs > 0.0)
    }
}
