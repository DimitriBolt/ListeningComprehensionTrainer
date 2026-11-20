package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationControllerTest {

    // ---- подставные компоненты -----------------------------------------------------------

    private class FakeInput(private val ui: () -> ConversationUi) : AudioInput {
        var records = 0
        var finishes = 0
        val statesAtRecord = mutableListOf<ConversationState>()
        /** true — запись ждёт finish() (или отмены). */
        var hold = false
        /** Была ли речь в очередной записи; очередь пуста — была. */
        val speech = ArrayDeque<Boolean>()
        /** Ошибка микрофона в очередной записи. */
        var error: Exception? = null
        private var pending: CompletableDeferred<Unit>? = null

        override suspend fun record(turnDetector: TurnDetector, onLevel: (Float) -> Unit): RecordedAudio {
            records++
            statesAtRecord += ui().state
            error?.let { throw it }
            val heard = speech.removeFirstOrNull() ?: true
            onLevel(if (heard) 65f else 40f)
            if (hold) CompletableDeferred<Unit>().also { pending = it }.await()
            return RecordedAudio(File("rec$records.wav"), durationMs = 3000, heardSpeech = heard, peakDb = if (heard) 65f else 40f)
        }

        override fun finish() {
            finishes++
            pending?.complete(Unit)
        }
    }

    private class FakeTranscriber(private val ui: () -> ConversationUi, vararg answers: Any) : Transcriber {
        private val queue = ArrayDeque(answers.toList())
        val statesAtCall = mutableListOf<ConversationState>()
        var calls = 0
        override suspend fun transcribe(audio: RecordedAudio): String {
            calls++
            statesAtCall += ui().state
            delay(300)
            return when (val a = queue.removeFirstOrNull() ?: "") {
                is Throwable -> throw a
                else -> a as String
            }
        }
    }

    private class FakeTutor(var replyText: String = "Hello there. How are you?") : Tutor {
        val replies = mutableListOf<String>()
        var farewells = 0
        var error: Exception? = null
        override suspend fun reply(studentText: String, onStatus: (String) -> Unit): TutorReply {
            replies += studentText
            delay(1000)
            error?.let { throw it }
            return TutorReply(replyText)
        }

        override suspend fun farewell(onStatus: (String) -> Unit): TutorReply {
            farewells++
            delay(1000)
            return TutorReply("Let's take a break.")
        }
    }

    private class FakeSynth : Synthesizer {
        override val leadInMs = 1000L
        val texts = mutableListOf<String>()
        override suspend fun synthesize(text: String): SpeechAudio {
            texts += text
            delay(200)
            return SpeechAudioFile(File(text))
        }
    }

    private class FakeOutput(private val ui: () -> ConversationUi, private val clock: () -> Long) : AudioOutput {
        val played = mutableListOf<String>()
        val startTimes = mutableListOf<Long>()
        val cues = mutableListOf<Cue>()
        val statesAtPlay = mutableListOf<ConversationState>()
        var playMs = 0L
        var stops = 0
        override suspend fun play(audio: SpeechAudio, onStarted: () -> Unit) {
            statesAtPlay += ui().state
            played += (audio as SpeechAudioFile).file.path
            startTimes += clock()
            onStarted()
            delay(playMs)
        }
        override fun playCue(cue: Cue) { cues += cue }
        override fun stop() { stops++ }
        override fun release() {}
    }

    private class FakeLog : EventLog {
        val lines = mutableListOf<String>()
        override fun event(source: String, message: String) { lines += "[$source] $message" }
        override fun error(source: String, message: String, error: Throwable?) {
            lines += "[$source] ERROR $message: ${error?.javaClass?.simpleName}"
        }
    }

    private class Rig(scope: TestScope, vararg transcripts: Any) {
        lateinit var controller: ConversationController
        private val ui = { controller.ui.value }
        private val clock = { scope.testScheduler.currentTime }
        val input = FakeInput(ui)
        val transcriber = FakeTranscriber(ui, *transcripts)
        val tutor = FakeTutor()
        val synth = FakeSynth()
        val output = FakeOutput(ui, clock)
        val log = FakeLog()
        val timing get() = log.lines.filter { it.startsWith("[Timing] ") }.map { it.removePrefix("[Timing] ") }

        init {
            controller = ConversationController(
                scope = scope,
                input = input,
                turnDetector = SilenceTurnDetector(),
                transcriber = transcriber,
                tutor = tutor,
                planner = SpeechPlanner(),
                synthesizer = synth,
                output = output,
                clock = clock,
                log = log,
            )
        }

        val state get() = controller.ui.value.state
    }

    // ---- обычный круг --------------------------------------------------------------------

    @Test
    fun `manual turn goes Listening - Thinking - Speaking - Idle`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf<ConversationState>(ConversationState.Listening), rig.input.statesAtRecord)
        assertEquals(listOf<ConversationState>(ConversationState.Thinking), rig.transcriber.statesAtCall)
        assertEquals(listOf("I am fine"), rig.tutor.replies)
        assertEquals(listOf("Hello there.", "How are you?"), rig.output.played)
        assertTrue(rig.output.statesAtPlay.all { it == ConversationState.Speaking })
        assertEquals(listOf(Cue.PROCESSING), rig.output.cues)

        val ui = rig.controller.ui.value
        assertEquals(ConversationState.Idle, ui.state)
        assertEquals("Ready.", ui.status)
        assertEquals("I am fine", ui.studentText)
        assertEquals("Hello there. How are you?", ui.teacherText)
        assertEquals("", ui.currentChunk)
        assertEquals(1, rig.input.records) // ручной режим: один круг
    }

    @Test
    fun `next group starts at reference pause minus Bluetooth lead-in`() = runTest {
        val rig = Rig(this, "Hi")
        rig.tutor.replyText = "One, two.\nThree."
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("One,", "two.", "Three."), rig.output.played)
        val t = rig.output.startTimes
        assertEquals(10L, t[1] - t[0])   // 600 - 1000 → не меньше 10
        assertEquals(600L, t[2] - t[1])  // абзац 1600 - 1000
    }

    @Test
    fun `service label is shown but not synthesized`() = runTest {
        val rig = Rig(this, "Gas price?")
        rig.tutor.replyText = "${SpeechPlanner.LABEL_LIVE_WEB} It is three dollars."
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("It is three dollars."), rig.synth.texts)
        assertTrue(rig.controller.ui.value.teacherText.startsWith(SpeechPlanner.LABEL_LIVE_WEB))
    }

    @Test
    fun `timing line is written once per turn without texts`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.controller.startTurn()
        advanceUntilIdle()

        // распознавание 300 мс, учитель +1000, синтез +200
        assertEquals(listOf("stt=300 tutor=1300 first_audio=1500"), rig.timing)
    }

    // ---- авто-режим и молчание -----------------------------------------------------------

    @Test
    fun `auto mode listens again after the reply`() = runTest {
        val rig = Rig(this, "one", "two")
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(rig.input.records >= 3)
        assertEquals(listOf("one", "two"), rig.tutor.replies.take(2))
        assertTrue(Cue.YOUR_TURN in rig.output.cues)
        rig.controller.stop()
    }

    @Test
    fun `three silences in a row in auto mode - farewell and auto mode off`() = runTest {
        val rig = Rig(this, "", "", "")
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(3, rig.input.records)
        assertEquals(2, rig.output.cues.count { it == Cue.YOUR_TURN })
        assertEquals(1, rig.tutor.farewells)
        assertEquals(emptyList<String>(), rig.tutor.replies)
        assertEquals(listOf("Let's take a break."), rig.output.played)
        assertFalse(rig.controller.ui.value.autoMode)
        assertEquals(ConversationState.Idle, rig.state)
        assertTrue(rig.timing.isEmpty()) // прощание — не круг ученика
    }

    @Test
    fun `recording without speech is silence even if the recognizer would hear Thank you`() = runTest {
        // Дефект S01: whisper-1 на тишине «слышит» `Thank you.`, молчание засчитывалось как реплика.
        val rig = Rig(this, "Thank you.", "Thank you.", "Thank you.")
        repeat(3) { rig.input.speech.addLast(false) }
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(0, rig.transcriber.calls) // на записи без речи распознаватель не вызывается
        assertEquals(3, rig.input.records)
        assertTrue(rig.tutor.replies.isEmpty())
        assertEquals(1, rig.tutor.farewells)
        assertFalse(rig.controller.ui.value.autoMode)
        assertEquals(ConversationState.Idle, rig.state)
        assertEquals(3, rig.log.lines.count { "counted as silence: yes (nothing above threshold" in it })
        assertTrue(rig.log.lines.any { "recording: 3000 ms, above threshold: no, peak 40 dB" in it })
    }

    @Test
    fun `transcript length and silence decision are logged`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertTrue(rig.log.lines.contains("[Controller] recording: 3000 ms, above threshold: yes, peak 65 dB"))
        assertTrue(rig.log.lines.contains("[Controller] transcript: 9 chars, counted as silence: no"))
    }

    @Test
    fun `speech resets the silence counter`() = runTest {
        val rig = Rig(this, "", "Hi there", "", "", "")
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(5, rig.input.records)
        assertEquals(listOf("Hi there"), rig.tutor.replies)
        assertEquals(1, rig.tutor.farewells)
    }

    @Test
    fun `silence in manual mode - Nothing heard and stop`() = runTest {
        val rig = Rig(this, " ")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(1, rig.input.records)
        assertEquals(ConversationState.Idle, rig.state)
        assertEquals("Nothing heard.", rig.controller.ui.value.status)
        assertEquals(0, rig.tutor.farewells)
        assertTrue(rig.tutor.replies.isEmpty())
    }

    // ---- ошибки --------------------------------------------------------------------------

    @Test
    fun `network error in transcription - Error state, next Talk works`() = runTest {
        val rig = Rig(this, java.io.IOException("timeout"), "Hello")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(ConversationState.Error("Whisper error: timeout"), rig.state)
        assertTrue(rig.tutor.replies.isEmpty())
        assertTrue(rig.output.played.isEmpty())

        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(ConversationState.Idle, rig.state)
        assertEquals(listOf("Hello"), rig.tutor.replies)
    }

    @Test
    fun `network error in tutor - Error state, nothing played, auto loop ends`() = runTest {
        val rig = Rig(this, "Hello", "again")
        rig.tutor.error = java.io.IOException("HTTP 500")
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(ConversationState.Error("Error: HTTP 500"), rig.state)
        assertTrue(rig.output.played.isEmpty())
        assertEquals(1, rig.input.records)
    }

    // ---- остановка пользователем ---------------------------------------------------------

    @Test
    fun `Stop in the middle of speech - Idle, auto mode off, rest not played`() = runTest {
        val rig = Rig(this, "Hello")
        rig.tutor.replyText = "One. Two. Three. Four."
        rig.output.playMs = 2000
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceTimeBy(1600) // распознавание + учитель + синтез; первая группа звучит
        runCurrent()
        assertEquals(ConversationState.Speaking, rig.state)

        rig.controller.stop()
        advanceUntilIdle()

        val ui = rig.controller.ui.value
        assertEquals(ConversationState.Idle, ui.state)
        assertEquals("Stopped.", ui.status)
        assertFalse(ui.autoMode)
        assertEquals("", ui.currentChunk)
        assertEquals(listOf("One."), rig.output.played)
        assertTrue(rig.output.stops >= 1)
        assertEquals(1, rig.input.records) // авто-режим не продолжился
    }

    @Test
    fun `Stop while listening - nothing is transcribed`() = runTest {
        val rig = Rig(this, "Hello")
        rig.input.hold = true
        rig.controller.startTurn()
        runCurrent()
        assertEquals(ConversationState.Listening, rig.state)

        rig.controller.stop()
        advanceUntilIdle()
        assertEquals(ConversationState.Idle, rig.state)
        assertEquals(0, rig.transcriber.calls)
    }

    @Test
    fun `Stop Recording finishes the turn and sends it`() = runTest {
        val rig = Rig(this, "Hello")
        rig.input.hold = true
        rig.controller.startTurn()
        runCurrent()

        rig.controller.finishRecording()
        advanceUntilIdle()
        assertEquals(1, rig.input.finishes)
        assertEquals(listOf("Hello"), rig.tutor.replies)
        assertEquals(ConversationState.Idle, rig.state)
    }

    @Test
    fun `turning auto mode off mid-turn stops after the reply`() = runTest {
        val rig = Rig(this, "Hello", "more")
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceTimeBy(500)
        rig.controller.setAutoMode(false)
        advanceUntilIdle()

        assertEquals(1, rig.input.records)
        assertEquals(listOf("Hello"), rig.tutor.replies)
        assertEquals(ConversationState.Idle, rig.state)
    }

    @Test
    fun `Talk while a turn is running does not start a second one`() = runTest {
        val rig = Rig(this, "Hello")
        rig.controller.startTurn()
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(1, rig.input.records)
    }
}
