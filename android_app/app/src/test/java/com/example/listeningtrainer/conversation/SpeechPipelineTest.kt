package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.audio.Loudness
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechPipelineTest {

    /** Подставной синтез: [transcripts] — что «прочитано» на каждый вызов (по умолчанию — дословно). */
    private class Fake(override val name: String, private val withTranscript: Boolean = true) : Synthesizer {
        val texts = mutableListOf<String>()
        val transcripts = ArrayDeque<String?>()
        val errors = ArrayDeque<Exception?>()
        var delayMs = 100L
        override suspend fun synthesize(text: String): SynthesizedSpeech {
            texts += text
            delay(delayMs)
            errors.removeFirstOrNull()?.let { throw it }
            val heard = if (transcripts.isEmpty()) text else transcripts.removeFirst()
            return SynthesizedSpeech(SpeechAudio(tone(0.02, 2400), 24_000), if (withTranscript) heard else null)
        }
    }

    private class Log : EventLog {
        val lines = mutableListOf<String>()
        override fun event(source: String, message: String) { lines += "[$source] $message" }
        override fun error(source: String, message: String, error: Throwable?) { lines += "[$source] ERROR $message" }
    }

    private fun chunks(vararg texts: String) = texts.map { SpeechChunk(it, 250) }

    @Test
    fun `groups come in order, first requested first, at most lookahead ready ahead`() = runTest {
        val primary = Fake("realtime")
        val pipeline = SpeechPipeline(primary, Fake("android"), Log(), { testScheduler.currentTime }, lookahead = 3)
        coroutineScope {
            val groups = pipeline.start(this, chunks("a", "b", "c", "d", "e", "f", "g", "h"))
            groups.receive() // играет первая
            advanceUntilIdle()
            assertEquals(listOf("a", "b", "c", "d"), primary.texts) // первая + 3 вперёд
            groups.receive()
            advanceUntilIdle()
            assertEquals(listOf("a", "b", "c", "d", "e"), primary.texts)
            val rest = mutableListOf<SpeechAudio>()
            repeat(6) { rest += groups.receive() }
            assertEquals(8, primary.texts.size)
        }
    }

    @Test
    fun `first group is requested immediately and nothing else before it`() = runTest {
        val primary = Fake("realtime")
        val pipeline = SpeechPipeline(primary, Fake("android"), Log(), { testScheduler.currentTime }, lookahead = 2)
        coroutineScope {
            val groups = pipeline.start(this, chunks("first", "second", "third"))
            runCurrent()
            assertEquals(listOf("first"), primary.texts) // одна группа в работе, а не все сразу
            groups.receive()
            assertEquals(100L, testScheduler.currentTime)
            groups.cancel()
        }
    }

    @Test
    fun `mismatch - one retry - then the right reading`() = runTest {
        val primary = Fake("realtime").apply { transcripts += "Sure! What do you think?"; transcripts += "What do you think?" }
        val fallback = Fake("android")
        val log = Log()
        coroutineScope {
            SpeechPipeline(primary, fallback, log, { testScheduler.currentTime }).start(this, chunks("What do you think?")).receive()
        }
        assertEquals(2, primary.texts.size)
        assertTrue(fallback.texts.isEmpty())
        assertTrue(log.lines.any { it.startsWith("[Audio] group 1/1: realtime mismatch (mismatch: expected 4 words, heard 5)") })
        assertTrue(log.lines.any { it.startsWith("[Audio] group 1/1: realtime (attempt 2), ready in") })
    }

    @Test
    fun `mismatch twice - this group via fallback, the next one via Realtime again`() = runTest {
        val primary = Fake("realtime").apply { transcripts += "wrong"; transcripts += "still wrong" }
        val fallback = Fake("android", withTranscript = false)
        val log = Log()
        coroutineScope {
            val groups = SpeechPipeline(primary, fallback, log, { testScheduler.currentTime }).start(this, chunks("Tell me more.", "Good job!"))
            groups.receive(); groups.receive()
        }
        assertEquals(listOf("Tell me more.", "Tell me more.", "Good job!"), primary.texts)
        assertEquals(listOf("Tell me more."), fallback.texts)
        assertTrue(log.lines.contains("[Audio] group 1/2: fallback to android after 2 attempts"))
        assertTrue(log.lines.any { it.startsWith("[Audio] group 1/2: android, ready in") })
        assertTrue(log.lines.any { it.startsWith("[Audio] group 2/2: realtime, ready in") })
    }

    @Test
    fun `group error twice - fallback for this group`() = runTest {
        val primary = Fake("realtime").apply {
            errors += SynthesisException("status failed (server_error)", unavailable = false)
            errors += IllegalStateException("boom")
        }
        val fallback = Fake("android", withTranscript = false)
        val log = Log()
        coroutineScope {
            SpeechPipeline(primary, fallback, log, { testScheduler.currentTime }).start(this, chunks("Hello.")).receive()
        }
        assertEquals(1, fallback.texts.size)
        assertTrue(log.lines.contains("[Audio] group 1/1: realtime error (status failed (server_error)), attempt 1 of 2"))
        assertTrue(log.lines.contains("[Audio] group 1/1: realtime error (IllegalStateException), attempt 2 of 2"))
    }

    @Test
    fun `Realtime unavailable - the rest of the reply via fallback, next reply tries Realtime again`() = runTest {
        val primary = Fake("realtime").apply { errors += SynthesisException("http 401", unavailable = true) }
        val fallback = Fake("android", withTranscript = false)
        val log = Log()
        val pipeline = SpeechPipeline(primary, fallback, log, { testScheduler.currentTime })
        coroutineScope {
            val groups = pipeline.start(this, chunks("One,", "two,", "three."))
            repeat(3) { groups.receive() }
        }
        assertEquals(listOf("One,"), primary.texts)
        assertEquals(listOf("One,", "two,", "three."), fallback.texts)
        assertTrue(log.lines.contains("[Audio] realtime unavailable (http 401): group 1/3 and the rest of the reply via android"))

        coroutineScope { pipeline.start(this, chunks("Next.")).receive() }
        assertEquals(listOf("One,", "Next."), primary.texts)
    }

    @Test
    fun `fallback failure fails the reply`() = runTest {
        val primary = Fake("realtime").apply { errors += SynthesisException("network: UnknownHostException", unavailable = true) }
        val fallback = Fake("android").apply { errors += SynthesisException("android tts: no English voice data", unavailable = true) }
        val log = Log()
        try {
            coroutineScope { SpeechPipeline(primary, fallback, log, { testScheduler.currentTime }).start(this, chunks("Hi.")).receive() }
            fail("expected an error")
        } catch (e: SynthesisException) {
            assertEquals("android tts: no English voice data", e.message)
        }
        assertTrue(log.lines.contains("[Audio] group 1/1: android failed (android tts: no English voice data)"))
    }

    @Test
    fun `contractions and numbers count as verbatim`() = runTest {
        val primary = Fake("realtime").apply { transcripts += "I am here on November sixteenth" }
        val fallback = Fake("android")
        coroutineScope {
            SpeechPipeline(primary, fallback, Log(), { testScheduler.currentTime }).start(this, chunks("I'm here on November 16,")).receive()
        }
        assertEquals(1, primary.texts.size)
        assertTrue(fallback.texts.isEmpty())
    }

    @Test
    fun `bold markers are not sent to the synthesizer`() = runTest {
        val primary = Fake("realtime")
        coroutineScope {
            SpeechPipeline(primary, Fake("android"), Log(), { testScheduler.currentTime }).start(this, chunks("1. **Make**")).receive()
        }
        assertEquals(listOf("1. Make"), primary.texts)
    }

    @Test
    fun `loudness is normalized and logged, texts never are`() = runTest {
        val log = Log()
        val audio = coroutineScope {
            SpeechPipeline(Fake("realtime"), Fake("android"), log, { testScheduler.currentTime }).start(this, chunks("Secret words here.")).receive()
        }
        val level = Loudness.measure(audio.pcm, audio.sampleRate)
        // Цель −13 дБFS (D25); звук на −37 дБFS упирается в предел усиления +24 дБ.
        assertEquals(-13.0, level.rmsDbfs, 1.0)
        val line = log.lines.single { it.startsWith("[Audio] group 1/1:") }
        assertTrue(line, "ready in 100 ms, 100 ms of audio, rms -37.0 dBFS, peak -34.0 dBFS -> rms -13." in line)
        assertFalse(log.lines.any { "Secret" in it })
    }

    @Test
    fun `group without words is not synthesized - no dot`() = runTest {
        val primary = Fake("realtime")
        val log = Log()
        val audios = coroutineScope {
            val groups = SpeechPipeline(primary, Fake("android"), log, { testScheduler.currentTime })
                .start(this, chunks("1. **Make a plan**", ":", "write it down.", "-", "!"))
            List(5) { groups.receive() }
        }
        assertEquals(listOf("1. Make a plan", "write it down."), primary.texts)
        assertEquals(listOf(false, true, false, true, true), audios.map { it.pcm.isEmpty() })
        assertTrue(log.lines.contains("[Audio] group 2/5: no words, not synthesized"))
    }

    @Test
    fun `cancel stops synthesis`() = runTest {
        val primary = Fake("realtime").apply { delayMs = 1000 }
        val pipeline = SpeechPipeline(primary, Fake("android"), Log(), { testScheduler.currentTime }, lookahead = 3)
        val job = launch { pipeline.start(this, chunks("a", "b", "c", "d", "e")).receive() }
        runCurrent()
        job.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(listOf("a"), primary.texts)
    }

    private companion object {
        /** Синус 440 Гц с амплитудой [amplitude] (доля от максимума). */
        fun tone(amplitude: Double, samples: Int) =
            ShortArray(samples) { (sin(2 * PI * 440 * it / 24_000) * amplitude * 32767).toInt().toShort() }
    }
}
