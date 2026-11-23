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

    /** Общая лента событий подставных компонентов: в каком порядке что случилось. */
    private class Timeline {
        val events = mutableListOf<String>()
    }

    private class FakeInput(private val ui: () -> ConversationUi, private val timeline: Timeline) : AudioInput {
        var records = 0
        var finishes = 0
        val statesAtRecord = mutableListOf<ConversationState>()
        /** true — запись ждёт finish() (или отмены). */
        var hold = false
        /** Была ли речь в очередной записи; очередь пуста — была. */
        val speech = ArrayDeque<Boolean>()
        /** Ошибка микрофона. */
        var error: Exception? = null
        /** Сколько «длилось» сжатие (оно уже прошло к возврату записи). */
        var encodeMs = 0L
        private var pending: CompletableDeferred<Unit>? = null

        override suspend fun record(turnDetector: TurnDetector, onLevel: (Float) -> Unit): RecordedAudio {
            records++
            timeline.events += "record"
            statesAtRecord += ui().state
            error?.let { throw it }
            val heard = speech.removeFirstOrNull() ?: true
            onLevel(if (heard) 65f else 40f)
            if (hold) CompletableDeferred<Unit>().also { pending = it }.await()
            return RecordedAudio(File("rec$records.wav"), durationMs = 3000, heardSpeech = heard, peakDb = if (heard) 65f else 40f, encodeMs = encodeMs)
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
        val prompts = mutableListOf<String>()
        override suspend fun transcribe(audio: RecordedAudio, prompt: String): String {
            calls++
            prompts += prompt
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
        var farewellError: Exception? = null
        var replyDelayMs = 1000L
        var searched = false
        var firstRequestMs: Long? = null
        override suspend fun reply(studentText: String, onStatus: (String) -> Unit): TutorReply {
            replies += studentText
            delay(replyDelayMs)
            error?.let { throw it }
            return TutorReply(replyText, searched = searched, firstRequestMs = firstRequestMs)
        }

        override suspend fun farewell(onStatus: (String) -> Unit): TutorReply {
            farewells++
            delay(1000)
            farewellError?.let { throw it }
            return TutorReply("Let's take a break.")
        }

        override var lastTeacherText: String? = null
        var newConversations = 0
        override fun newConversation() { newConversations++ }
    }

    /**
     * Подставной синтез. Звук каждой группы — тишина своей длины: по длине [FakeOutput] узнаёт,
     * какой текст играет (нормализация громкости длину не меняет).
     */
    private class FakeSynth(override val name: String, private val sounds: MutableMap<Int, String>) : Synthesizer {
        val texts = mutableListOf<String>()
        val textTimes = mutableListOf<Long>()
        var error: Exception? = null
        var delayMs = 200L
        var prepares = 0
        var releases = 0
        var clock: () -> Long = { 0L }
        override fun prepare() { prepares++ }
        override fun release() { releases++ }
        override suspend fun synthesize(text: String): SynthesizedSpeech {
            texts += text
            textTimes += clock()
            delay(delayMs)
            error?.let { throw it }
            val size = 1000 + sounds.size
            sounds[size] = text
            return SynthesizedSpeech(SpeechAudio(ShortArray(size), 24_000), transcript = text)
        }
    }

    private class FakeOutput(
        private val ui: () -> ConversationUi,
        private val clock: () -> Long,
        private val timeline: Timeline,
    ) : AudioOutput {
        val played = mutableListOf<String>()
        val shields = mutableListOf<Long>()
        val startTimes = mutableListOf<Long>()
        /** Тишина щита, которую вернёт prepareReply: 0 — проводные наушники, 1000 — Bluetooth. */
        var shieldMs = 0L
        /** Наушники (пульс можно) или динамик. */
        var headphones = true
        var prepareReplies = 0
        var sounds: Map<Int, String> = emptyMap()
        val cues = mutableListOf<Cue>()
        val cueTimes = mutableListOf<Long>()
        val statesAtPlay = mutableListOf<ConversationState>()
        var playMs = 0L
        var stops = 0
        var released = false
        override fun prepareReply(): Long {
            prepareReplies++
            return shieldMs
        }
        override fun listenPulseAllowed() = headphones
        override suspend fun play(audio: SpeechAudio, shieldMs: Long, onStarted: () -> Unit) {
            statesAtPlay += ui().state
            played += sounds.getValue(audio.pcm.size)
            shields += shieldMs
            timeline.events += "play"
            startTimes += clock()
            onStarted()
            delay(playMs)
        }
        override fun playCue(cue: Cue) {
            cues += cue
            cueTimes += clock()
            timeline.events += "cue:${cue.name}"
        }
        override suspend fun awaitCues() { timeline.events += "awaitCues" }
        override fun stop() { stops++ }
        override fun release() { released = true }
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
        val timeline = Timeline()
        val input = FakeInput(ui, timeline)
        val transcriber = FakeTranscriber(ui, *transcripts)
        val tutor = FakeTutor()
        private val sounds = mutableMapOf<Int, String>()
        val synth = FakeSynth("realtime", sounds).also { it.clock = clock }
        val fallback = FakeSynth("android", sounds).also { it.clock = clock }
        val output = FakeOutput(ui, clock, timeline).also { it.sounds = sounds }
        val log = FakeLog()
        val timing get() = log.lines.filter { it.startsWith("[Timing] ") }.map { it.removePrefix("[Timing] ") }
        val circleEnds get() = log.lines.filter { "circle end: " in it }.map { it.substringAfter("circle end: ").substringBefore(' ') }

        init {
            controller = ConversationController(
                scope = scope,
                input = input,
                turnDetector = SilenceTurnDetector(),
                transcriber = transcriber,
                tutor = tutor,
                planner = SpeechPlanner(),
                synthesizer = synth,
                fallbackSynthesizer = fallback,
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

        val ui = rig.controller.ui.value
        assertEquals(ConversationState.Idle, ui.state)
        assertEquals("Ready.", ui.status)
        assertEquals("I am fine", ui.studentText)
        assertEquals("Hello there. How are you?", ui.teacherText)
        assertEquals("", ui.currentChunk)
        assertEquals(1, rig.input.records) // ручной режим: один круг
    }

    @Test
    fun `manual turn cues - listen before recording, processing, end after the reply`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.END), rig.output.cues)
        // «говори» дозвучал до начала записи, «конец» — после речи учителя
        assertEquals(
            listOf("cue:LISTEN", "awaitCues", "record", "cue:PROCESSING", "play", "play", "cue:END"),
            rig.timeline.events,
        )
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
    }

    @Test
    fun `state transitions are logged with reasons`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.controller.startTurn()
        advanceUntilIdle()

        val transitions = rig.log.lines.filter { it.startsWith("[Controller]") && " -> " in it }
        assertEquals(
            listOf(
                "[Controller] Idle -> Listening (Talk button)",
                "[Controller] Listening -> Thinking (recording finished)",
                "[Controller] Thinking -> Speaking (first group ready)",
                "[Controller] Speaking -> Idle (reply finished)",
            ),
            transitions,
        )
        assertTrue(rig.log.lines.contains("[Controller] circle end: REPLY_FINISHED (reply finished, auto-mode off)"))
    }

    @Test
    fun `transcription prompt carries the last teacher sentence`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.tutor.lastTeacherText = "Nice to meet you. How are you today?"
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(
            listOf("${TranscriptionPrompt.CONTEXT} The teacher just said: \"How are you today?\""),
            rig.transcriber.prompts,
        )
    }

    @Test
    fun `log has lengths but never the texts`() = runTest {
        val rig = Rig(this, "my secret words")
        rig.tutor.replyText = "Hidden teacher answer."
        rig.controller.startTurn()
        advanceUntilIdle()

        assertTrue(rig.log.lines.contains("[Controller] transcript: 15 chars, counted as silence: no"))
        assertTrue(rig.log.lines.contains("[Controller] reply: 22 chars, 1 groups"))
        assertFalse(rig.log.lines.any { "secret" in it || "Hidden" in it || "teacher answer" in it })
    }

    @Test
    fun `wired headphones - no shield, pauses exactly as the reference`() = runTest {
        val rig = Rig(this, "Hi")
        rig.tutor.replyText = "One, two.\nThree."
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("One,", "two.", "Three."), rig.output.played)
        assertEquals(listOf(0L, 0L, 0L), rig.output.shields)
        val t = rig.output.startTimes // звук групп в подставном выходе — 0 мс
        assertEquals(600L, t[1] - t[0])   // запятая
        assertEquals(1600L, t[2] - t[1])  // абзац
    }

    @Test
    fun `Bluetooth - shield before every group, next group starts earlier by the shield`() = runTest {
        val rig = Rig(this, "Hi")
        rig.output.shieldMs = 1000
        rig.output.playMs = 1000 // группа звучит дольше, чем синтезируется следующая
        rig.tutor.replyText = "One, two.\nThree."
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf(1000L, 1000L, 1000L), rig.output.shields)
        val t = rig.output.startTimes
        assertEquals(1000L + 10L, t[1] - t[0])   // звук 1000, пауза 600 - 1000 → не меньше 10
        assertEquals(1000L + 600L, t[2] - t[1])  // звук 1000, абзац 1600 - 1000
        assertEquals(1, rig.output.prepareReplies) // выход проверяется один раз на ответ
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

        // распознавание 300 мс, учитель +1000, синтез +200; щита нет — слышно сразу
        assertEquals(listOf("enc=0 stt=300 tutor=1300 llm1=- search=no reply_chars=25 first_audio=1500 audible=1500"), rig.timing)
    }

    @Test
    fun `list number is its own group, 400 ms before the heading, colon joins the heading`() = runTest {
        val rig = Rig(this, "Hi")
        rig.tutor.replyText = "1. **Make a plan**: write it down."
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("1.", "Make a plan:", "write it down."), rig.synth.texts)
        assertEquals(listOf("1.", "Make a plan:", "write it down."), rig.output.played)
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
        val t = rig.output.startTimes // звук групп в подставном выходе — 0 мс
        assertEquals(400L, t[1] - t[0])
        assertEquals(1600L, t[2] - t[1]) // пауза заголовка-абзаца
    }

    @Test
    fun `timing - with Bluetooth the voice is audible after the shield`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.output.shieldMs = 1200
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("enc=0 stt=300 tutor=1300 llm1=- search=no reply_chars=25 first_audio=1500 audible=2700"), rig.timing)
    }

    @Test
    fun `speech session is prepared at service start and before every recording`() = runTest {
        val rig = Rig(this, "Hi")
        rig.controller.prepare()
        assertEquals(1, rig.synth.prepares)
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(2, rig.synth.prepares)
        assertEquals(2, rig.fallback.prepares)
        rig.controller.release()
        assertEquals(1, rig.synth.releases)
    }

    @Test
    fun `groups are synthesized in order, the first one first`() = runTest {
        val rig = Rig(this, "Hi")
        rig.tutor.replyText = "One, two, three, four, five, six."
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("One,", "two,", "three,", "four,", "five,", "six."), rig.synth.texts)
        assertEquals(rig.synth.texts, rig.output.played)
        assertEquals(1300L, rig.synth.textTimes.first()) // сразу после ответа учителя, без очереди
        assertTrue(rig.fallback.texts.isEmpty())
    }

    @Test
    fun `Realtime unavailable - the reply is read by the fallback`() = runTest {
        val rig = Rig(this, "Hi")
        rig.tutor.replyText = "One, two."
        rig.synth.error = SynthesisException("network: UnknownHostException", unavailable = true)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("One,"), rig.synth.texts) // после недоступности Realtime больше не пробуется
        assertEquals(listOf("One,", "two."), rig.fallback.texts)
        assertEquals(listOf("One,", "two."), rig.output.played)
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
        assertTrue(rig.log.lines.any { "realtime unavailable (network: UnknownHostException)" in it })
    }

    @Test
    fun `Stop during speech stops playback and pending synthesis`() = runTest {
        val rig = Rig(this, "Hi")
        rig.tutor.replyText = "One, two, three, four, five, six, seven, eight."
        rig.output.playMs = 2000
        rig.controller.startTurn()
        advanceTimeBy(1300 + 200 + 500) // идёт первая группа
        val requested = rig.synth.texts.size
        rig.controller.stop(EndReason.STOP_BUTTON)
        advanceUntilIdle()

        assertEquals(listOf("One,"), rig.output.played)
        assertEquals(requested, rig.synth.texts.size) // новых запросов синтеза после Stop нет
        assertTrue(requested <= 1 + 3) // первая + не больше 3 вперёд
        assertEquals(listOf("STOP_BUTTON"), rig.circleEnds)
    }

    // ---- ожидание учителя ----------------------------------------------------------------

    @Test
    fun `waiting longer than 5 s ticks every 3 s until the teacher speaks`() = runTest {
        val rig = Rig(this, "Hello")
        rig.tutor.replyDelayMs = 12_000 // до первого звука: 300 + 12000 + 200 = 12,5 с
        rig.controller.startTurn()
        advanceUntilIdle()

        val ticks = rig.output.cueTimes.filterIndexed { i, _ -> rig.output.cues[i] == Cue.WAITING }
        assertEquals(listOf(5_000L, 8_000L, 11_000L), ticks) // от конца записи (t = 0)
        assertTrue(ticks.all { it < rig.output.startTimes.first() })
        assertEquals(Cue.END, rig.output.cues.last())
    }

    @Test
    fun `no ticks on a normal wait`() = runTest {
        val rig = Rig(this, "Hello")
        rig.controller.startTurn()
        advanceUntilIdle()
        assertFalse(Cue.WAITING in rig.output.cues)
    }

    @Test
    fun `ticks stop on error`() = runTest {
        val rig = Rig(this, "Hello")
        rig.tutor.replyDelayMs = 7_000
        rig.tutor.error = java.io.IOException("timeout")
        rig.controller.startTurn()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, rig.output.cues.count { it == Cue.WAITING }) // только на 5 с
        assertEquals(listOf(Cue.WAITING, Cue.ERROR, Cue.END), rig.output.cues.takeLast(3))
    }

    // ---- авто-режим и молчание -----------------------------------------------------------

    @Test
    fun `auto mode listens again after the reply, listen cue before every recording`() = runTest {
        val rig = Rig(this, "one", "two")
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceTimeBy(7_000) // два ответа (по 3,2 с) и третья запись; дальше пустые расшифровки привели бы к прощанию
        runCurrent()

        assertTrue(rig.input.records >= 3)
        assertEquals(listOf("one", "two"), rig.tutor.replies.take(2))
        val events = rig.timeline.events
        events.forEachIndexed { i, e -> if (e == "record") assertEquals(listOf("cue:LISTEN", "awaitCues"), events.subList(i - 2, i)) }
        assertFalse(Cue.END in rig.output.cues) // круг продолжается
        rig.controller.stop(EndReason.STOP_BUTTON)
    }

    @Test
    fun `three silences in a row in auto mode - farewell and auto mode off`() = runTest {
        val rig = Rig(this, "", "", "") // запасной путь: речь была, а текста нет
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(3, rig.input.records)
        assertEquals(1, rig.tutor.farewells)
        assertEquals(emptyList<String>(), rig.tutor.replies)
        assertEquals(listOf("Let's take a break."), rig.output.played)
        assertFalse(rig.controller.ui.value.autoMode)
        assertEquals(ConversationState.Idle, rig.state)
        assertTrue(rig.timing.isEmpty()) // прощание — не круг ученика
        assertEquals(
            listOf(
                Cue.LISTEN, Cue.PROCESSING, Cue.NOT_HEARD,
                Cue.LISTEN, Cue.PROCESSING, Cue.NOT_HEARD,
                Cue.LISTEN, Cue.PROCESSING, Cue.NOT_HEARD,
                Cue.END,
            ),
            rig.output.cues,
        )
        assertEquals(listOf("FAREWELL"), rig.circleEnds)
    }

    @Test
    fun `recording without speech is silence even if the recognizer would hear Thank you`() = runTest {
        // Дефект S01: распознаватель на тишине «слышит» `Thank you.`, молчание засчитывалось как реплика.
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
        assertEquals(
            listOf(Cue.LISTEN, Cue.NOT_HEARD, Cue.LISTEN, Cue.NOT_HEARD, Cue.LISTEN, Cue.NOT_HEARD, Cue.END),
            rig.output.cues,
        )
        assertEquals(3, rig.log.lines.count { "counted as silence: yes (nothing above threshold" in it })
        assertTrue(rig.log.lines.any { "recording: 3000 ms, above threshold: no, peak 40 dB" in it })
        assertEquals(listOf("FAREWELL"), rig.circleEnds)
    }

    @Test
    fun `transcript length and silence decision are logged`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertTrue(rig.log.lines.contains("[Controller] recording: 3000 ms, above threshold: yes, peak 65 dB, pulses: 0"))
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
    fun `no speech in manual mode - not heard, end, recognizer not called`() = runTest {
        val rig = Rig(this, "Thank you.")
        rig.input.speech.addLast(false)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(0, rig.transcriber.calls)
        assertEquals(ConversationState.Idle, rig.state)
        assertEquals("Nothing heard.", rig.controller.ui.value.status)
        assertEquals(listOf(Cue.LISTEN, Cue.NOT_HEARD, Cue.END), rig.output.cues)
        assertEquals(listOf("SILENCE_MANUAL"), rig.circleEnds)
    }

    @Test
    fun `empty transcript in manual mode - Nothing heard and stop`() = runTest {
        val rig = Rig(this, " ")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(1, rig.input.records)
        assertEquals(ConversationState.Idle, rig.state)
        assertEquals("Nothing heard.", rig.controller.ui.value.status)
        assertEquals(0, rig.tutor.farewells)
        assertTrue(rig.tutor.replies.isEmpty())
        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.NOT_HEARD, Cue.END), rig.output.cues)
    }

    @Test
    fun `turning auto mode off mid-turn stops after the reply with end cue`() = runTest {
        val rig = Rig(this, "Hello", "more")
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceTimeBy(500)
        rig.controller.setAutoMode(false)
        advanceUntilIdle()

        assertEquals(1, rig.input.records)
        assertEquals(listOf("Hello"), rig.tutor.replies)
        assertEquals(ConversationState.Idle, rig.state)
        assertEquals(Cue.END, rig.output.cues.last())
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
    }

    // ---- ошибки: «ошибка», затем «конец» -------------------------------------------------

    @Test
    fun `network error in transcription - error and end cues, next Talk works`() = runTest {
        val rig = Rig(this, java.io.IOException("timeout"), "Hello")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(ConversationState.Error("Transcription error: timeout"), rig.state)
        assertTrue(rig.tutor.replies.isEmpty())
        assertTrue(rig.output.played.isEmpty())
        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.ERROR, Cue.END), rig.output.cues)
        assertEquals(listOf("TRANSCRIPTION_ERROR"), rig.circleEnds)
        assertTrue(rig.log.lines.contains("[Controller] ERROR transcription error: IOException"))
        assertTrue(rig.log.lines.contains("[Controller] Thinking -> Error (transcription error)"))

        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(ConversationState.Idle, rig.state)
        assertEquals(listOf("Hello"), rig.tutor.replies)
    }

    @Test
    fun `network error in tutor - error and end, nothing played, auto loop ends`() = runTest {
        val rig = Rig(this, "Hello", "again")
        rig.tutor.error = java.io.IOException("HTTP 500")
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(ConversationState.Error("Error: HTTP 500"), rig.state)
        assertTrue(rig.output.played.isEmpty())
        assertEquals(1, rig.input.records)
        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.ERROR, Cue.END), rig.output.cues)
        assertEquals(listOf("TUTOR_ERROR"), rig.circleEnds)
    }

    @Test
    fun `no network after retry - error cue, phone voice says so, then end`() = runTest {
        val rig = Rig(this, "Hello")
        rig.tutor.error = ServiceException(FailureKind.NO_NETWORK, "UnknownHostException")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf(FailureKind.Phrases.NO_NETWORK), rig.fallback.texts)
        assertTrue(rig.synth.texts.isEmpty()) // Realtime для сообщения об ошибке не нужен
        assertEquals(listOf(FailureKind.Phrases.NO_NETWORK), rig.output.played)
        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.ERROR, Cue.END), rig.output.cues)
        // «ошибка» — до фразы, «конец» — после
        val tail = rig.timeline.events.takeLast(4)
        assertEquals(listOf("cue:ERROR", "awaitCues", "play", "cue:END"), tail)
        assertEquals(listOf("TUTOR_ERROR"), rig.circleEnds)
        assertTrue(rig.log.lines.contains("[Controller] circle end: TUTOR_ERROR (tutor error, no_network)"))
        assertTrue(rig.log.lines.contains("[Controller] error message spoken: no_network"))
    }

    @Test
    fun `each kind of failure has its own phrase, unknown errors - cues only`() = runTest {
        val cases = mapOf(
            FailureKind.TIMEOUT to FailureKind.Phrases.NO_ANSWER,
            FailureKind.SERVER to FailureKind.Phrases.NO_ANSWER,
            FailureKind.KEY to FailureKind.Phrases.KEY,
            FailureKind.QUOTA to FailureKind.Phrases.QUOTA,
        )
        for ((kind, phrase) in cases) {
            val rig = Rig(this, ServiceException(kind, "x"))
            rig.controller.startTurn()
            advanceUntilIdle()
            assertEquals(listOf(phrase), rig.fallback.texts)
            assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.ERROR, Cue.END), rig.output.cues)
            assertEquals(listOf("TRANSCRIPTION_ERROR"), rig.circleEnds)
        }
        val rig = Rig(this, ServiceException(FailureKind.BAD_REQUEST, "http 400"))
        rig.controller.startTurn()
        advanceUntilIdle()
        assertTrue(rig.fallback.texts.isEmpty())
        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.ERROR, Cue.END), rig.output.cues)
    }

    @Test
    fun `phone voice unavailable - error message is skipped, cues only`() = runTest {
        val rig = Rig(this, ServiceException(FailureKind.NO_NETWORK, "x"))
        rig.fallback.error = SynthesisException("no English voice data", unavailable = true)
        rig.controller.startTurn()
        advanceUntilIdle()
        assertTrue(rig.output.played.isEmpty())
        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.ERROR, Cue.END), rig.output.cues)
        assertEquals(listOf("TRANSCRIPTION_ERROR"), rig.circleEnds)
        assertTrue(rig.log.lines.contains("[Controller] error message not spoken (no English voice data): cues only"))
    }

    @Test
    fun `Stop during the error message - one end cue, one circle end`() = runTest {
        val rig = Rig(this, ServiceException(FailureKind.NO_NETWORK, "x"))
        rig.output.playMs = 3000
        rig.controller.startTurn()
        advanceTimeBy(300 + 200 + 500) // распознавание, синтез фразы, фраза звучит
        rig.controller.stop(EndReason.STOP_BUTTON)
        advanceUntilIdle()
        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.ERROR, Cue.END), rig.output.cues)
        assertEquals(listOf("TRANSCRIPTION_ERROR"), rig.circleEnds)
        assertTrue(rig.log.lines.contains("[Controller] stop (Stop button) during the error message"))
    }

    @Test
    fun `encoding time counts in stt - the student waited for it`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.input.encodeMs = 400
        rig.controller.startTurn()
        advanceUntilIdle()
        // сжатие 400 (уже прошло) + распознавание 300; дальше всё на те же 400 больше
        assertEquals(listOf("enc=400 stt=700 tutor=1700 llm1=- search=no reply_chars=25 first_audio=1900 audible=1900"), rig.timing)
    }

    @Test
    fun `timing line has search, reply length and first tutor request`() = runTest {
        val rig = Rig(this, "Gas price?")
        rig.tutor.searched = true
        rig.tutor.firstRequestMs = 640
        rig.tutor.replyText = "It is three dollars."
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(listOf("enc=0 stt=300 tutor=1300 llm1=640 search=yes reply_chars=20 first_audio=1500 audible=1500"), rig.timing)
    }

    @Test
    fun `microphone error - error and end`() = runTest {
        val rig = Rig(this, "Hello")
        rig.input.error = IllegalStateException("startRecording() called on an uninitialized AudioRecord.")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertTrue(rig.state is ConversationState.Error)
        assertEquals(0, rig.transcriber.calls)
        assertEquals(listOf(Cue.LISTEN, Cue.ERROR, Cue.END), rig.output.cues)
        assertEquals(listOf("RECORDING_ERROR"), rig.circleEnds)
    }

    @Test
    fun `synthesis error - error and end`() = runTest {
        val rig = Rig(this, "Hello")
        rig.synth.error = SynthesisException("http 429", unavailable = true)
        rig.fallback.error = SynthesisException("android tts: no English voice data", unavailable = true)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(ConversationState.Error("Error: android tts: no English voice data"), rig.state)
        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.ERROR, Cue.END), rig.output.cues)
        assertEquals(listOf("SPEECH_ERROR"), rig.circleEnds)
    }

    @Test
    fun `farewell error - error and end, auto mode stays off`() = runTest {
        val rig = Rig(this)
        repeat(3) { rig.input.speech.addLast(false) }
        rig.tutor.farewellError = java.io.IOException("timeout")
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertFalse(rig.controller.ui.value.autoMode)
        assertEquals(listOf(Cue.NOT_HEARD, Cue.ERROR, Cue.END), rig.output.cues.takeLast(3))
        assertEquals(listOf("TUTOR_ERROR"), rig.circleEnds)
    }

    // ---- остановка извне -----------------------------------------------------------------

    @Test
    fun `Stop in the middle of speech - Idle, auto mode off, rest not played, end cue`() = runTest {
        val rig = Rig(this, "Hello")
        rig.tutor.replyText = "One. Two. Three. Four."
        rig.output.playMs = 2000
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceTimeBy(1600) // распознавание + учитель + синтез; первая группа звучит
        runCurrent()
        assertEquals(ConversationState.Speaking, rig.state)

        rig.controller.stop(EndReason.STOP_BUTTON)
        advanceUntilIdle()

        val ui = rig.controller.ui.value
        assertEquals(ConversationState.Idle, ui.state)
        assertEquals("Stopped.", ui.status)
        assertFalse(ui.autoMode)
        assertEquals("", ui.currentChunk)
        assertEquals(listOf("One."), rig.output.played)
        assertTrue(rig.output.stops >= 1)
        assertEquals(1, rig.input.records) // авто-режим не продолжился
        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.END), rig.output.cues)
        assertEquals(listOf("STOP_BUTTON"), rig.circleEnds)
        assertTrue(rig.log.lines.contains("[Controller] Speaking -> Idle (Stop button)"))
    }

    @Test
    fun `Stop while listening - nothing is transcribed, end cue`() = runTest {
        val rig = Rig(this, "Hello")
        rig.input.hold = true
        rig.controller.startTurn()
        runCurrent()
        assertEquals(ConversationState.Listening, rig.state)

        rig.controller.stop(EndReason.STOP_BUTTON)
        advanceUntilIdle()
        assertEquals(ConversationState.Idle, rig.state)
        assertEquals(0, rig.transcriber.calls)
        assertEquals(listOf(Cue.LISTEN, Cue.END), rig.output.cues)
    }

    @Test
    fun `Stop button with no circle running - no end cue`() = runTest {
        val rig = Rig(this)
        rig.controller.stop(EndReason.STOP_BUTTON)
        advanceUntilIdle()
        assertTrue(rig.output.cues.isEmpty())
        assertTrue(rig.circleEnds.isEmpty())
    }

    @Test
    fun `Back during a circle - end cue once, reason Back`() = runTest {
        val rig = Rig(this, "Hello")
        rig.input.hold = true
        rig.controller.startTurn()
        runCurrent()

        rig.controller.stop(EndReason.BACK)
        rig.controller.release() // затем сервис закрывается
        advanceUntilIdle()

        assertEquals(listOf(Cue.LISTEN, Cue.END), rig.output.cues)
        assertEquals(listOf("BACK"), rig.circleEnds)
        assertTrue(rig.output.released)
    }

    @Test
    fun `Back with no circle running still plays end - conversation closed`() = runTest {
        val rig = Rig(this, "Hello")
        rig.controller.startTurn()
        advanceUntilIdle() // ответ прозвучал, «конец» уже был
        rig.output.cues.clear()

        rig.controller.stop(EndReason.BACK)
        advanceUntilIdle()
        assertEquals(listOf(Cue.END), rig.output.cues)
    }

    @Test
    fun `swiped from recents is Back - circle stops with end cue, also with no circle`() = runTest {
        val rig = Rig(this, "Hello")
        rig.tutor.replyDelayMs = 5_000
        rig.controller.startTurn()
        advanceTimeBy(1000)
        rig.controller.stop(EndReason.SWIPED)
        advanceUntilIdle()
        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.END), rig.output.cues)
        assertEquals(listOf("SWIPED"), rig.circleEnds)
        assertTrue(rig.output.played.isEmpty())

        rig.output.cues.clear()
        rig.controller.stop(EndReason.SWIPED)
        advanceUntilIdle()
        assertEquals(listOf(Cue.END), rig.output.cues)
    }

    @Test
    fun `service destroyed during a circle - end cue, reason logged`() = runTest {
        val rig = Rig(this, "Hello")
        rig.tutor.replyDelayMs = 5_000
        rig.controller.startTurn()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(ConversationState.Thinking, rig.state)

        rig.controller.release()
        advanceUntilIdle()
        assertEquals(listOf(Cue.LISTEN, Cue.PROCESSING, Cue.END), rig.output.cues)
        assertEquals(listOf("SERVICE_DESTROYED"), rig.circleEnds)
        assertTrue(rig.output.played.isEmpty())
    }

    @Test
    fun `service destroyed with no circle running - no cue`() = runTest {
        val rig = Rig(this)
        rig.controller.release()
        advanceUntilIdle()
        assertTrue(rig.output.cues.isEmpty())
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

    // ---- история разговора ---------------------------------------------------------------

    @Test
    fun `Back keeps the tutor history - only New conversation clears it`() = runTest {
        val rig = Rig(this, "Hello")
        rig.controller.startTurn()
        advanceUntilIdle()
        rig.controller.stop(EndReason.BACK)
        rig.controller.release()
        assertEquals(0, rig.tutor.newConversations)
    }

    @Test
    fun `New conversation clears history and screen between circles`() = runTest {
        val rig = Rig(this, "Hello")
        rig.controller.startTurn()
        advanceUntilIdle()

        rig.controller.newConversation()
        assertEquals(1, rig.tutor.newConversations)
        val ui = rig.controller.ui.value
        assertEquals("", ui.studentText)
        assertEquals("", ui.teacherText)
        assertEquals("New conversation.", ui.status)
        assertTrue(rig.log.lines.contains("[Controller] New conversation: tutor history cleared"))
    }

    @Test
    fun `New conversation is ignored while a circle is running`() = runTest {
        val rig = Rig(this, "Hello")
        rig.input.hold = true
        rig.controller.startTurn()
        runCurrent()

        rig.controller.newConversation()
        assertEquals(0, rig.tutor.newConversations)
        rig.controller.stop(EndReason.STOP_BUTTON)
    }

    @Test
    fun `Talk while a turn is running does not start a second one`() = runTest {
        val rig = Rig(this, "Hello")
        rig.controller.startTurn()
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(1, rig.input.records)
        assertEquals(1, rig.output.cues.count { it == Cue.END })
    }

    // ---- средняя кнопка гарнитуры (S03) ------------------------------------------------------

    @Test
    fun `headset button - Talk in idle, Stop Recording while listening, nothing while the teacher speaks`() = runTest {
        val rig = Rig(this, "Hello")
        rig.input.hold = true
        assertTrue(rig.controller.headsetButton(HeadsetButton.KEYCODE_HEADSETHOOK, 120))
        runCurrent()
        assertEquals(ConversationState.Listening, rig.state)

        advanceTimeBy(3_000)
        rig.controller.headsetButton(HeadsetButton.KEYCODE_HEADSETHOOK, 120)
        assertEquals(1, rig.input.finishes)
        advanceTimeBy(600)
        assertEquals(ConversationState.Thinking, rig.state)

        rig.controller.headsetButton(HeadsetButton.KEYCODE_HEADSETHOOK, 120)
        advanceUntilIdle()
        assertEquals(listOf("Hello"), rig.tutor.replies)
        assertEquals(1, rig.input.records)
        assertEquals(listOf("Hello there.", "How are you?"), rig.output.played) // ответ не оборван
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)

        val lines = rig.log.lines.filter { "headset key" in it }
        assertEquals(
            listOf(
                "[Controller] headset key KEYCODE_HEADSETHOOK in Idle: Talk",
                "[Controller] headset key KEYCODE_HEADSETHOOK in Listening: Stop Recording",
                "[Controller] headset key KEYCODE_HEADSETHOOK in Thinking: ignored (teacher is thinking or speaking)",
            ),
            lines,
        )
        assertTrue(rig.log.lines.contains("[Controller] Idle -> Listening (headset button)"))
    }

    @Test
    fun `headset button in auto mode ends the turn, the circle goes on`() = runTest {
        val rig = Rig(this, "Hello", "Again")
        rig.controller.setAutoMode(true)
        rig.input.hold = true
        rig.controller.headsetButton(HeadsetButton.KEYCODE_MEDIA_PLAY_PAUSE, 100)
        advanceTimeBy(2_000)
        rig.controller.headsetButton(HeadsetButton.KEYCODE_MEDIA_PLAY_PAUSE, 100)
        advanceTimeBy(10_000)
        assertEquals(listOf("Hello"), rig.tutor.replies)
        assertEquals(2, rig.input.records) // авто-режим: снова слушает
        assertEquals(ConversationState.Listening, rig.state)
        rig.controller.stop(EndReason.STOP_BUTTON)
    }

    @Test
    fun `other keys are not consumed`() = runTest {
        val rig = Rig(this, "Hello")
        assertFalse(rig.controller.headsetButton(HeadsetButton.KEYCODE_MEDIA_NEXT, 100))
        runCurrent()
        assertEquals(ConversationState.Idle, rig.state)
        assertEquals(0, rig.input.records)
    }

    // ---- «пульс» слушания (S03, D28) ---------------------------------------------------------

    @Test
    fun `listen pulse every 2 s while recording, first 2 s after the listen cue, none after`() = runTest {
        val rig = Rig(this, "Hello")
        rig.input.hold = true
        rig.controller.startTurn()
        runCurrent()
        val recordAt = testScheduler.currentTime
        advanceTimeBy(7_000)
        rig.controller.finishRecording()
        advanceUntilIdle()

        val pulseTimes = rig.output.cues.indices.filter { rig.output.cues[it] == Cue.PULSE }.map { rig.output.cueTimes[it] - recordAt }
        assertEquals(listOf(2_000L, 4_000L, 6_000L), pulseTimes)
        // после записи пульса нет: «обрабатываю» идёт после последнего пульса, дальше пульсов нет
        val afterRecord = rig.output.cues.dropWhile { it != Cue.PROCESSING }
        assertFalse(Cue.PULSE in afterRecord)
        assertTrue(rig.log.lines.any { it.endsWith("peak 65 dB, pulses: 3") })
        // строки на каждый пульс нет
        assertEquals(1, rig.log.lines.count { "pulse" in it })
    }

    @Test
    fun `listen pulse stops on the headset button and on Stop`() = runTest {
        val rig = Rig(this, "Hello")
        rig.input.hold = true
        rig.controller.headsetButton(HeadsetButton.KEYCODE_HEADSETHOOK, 100)
        advanceTimeBy(4_500)
        rig.controller.headsetButton(HeadsetButton.KEYCODE_HEADSETHOOK, 100)
        advanceUntilIdle()
        assertEquals(2, rig.output.cues.count { it == Cue.PULSE })

        rig.input.hold = true
        rig.controller.startTurn()
        advanceTimeBy(2_500)
        rig.controller.stop(EndReason.STOP_BUTTON)
        advanceTimeBy(10_000)
        assertEquals(3, rig.output.cues.count { it == Cue.PULSE })
    }

    @Test
    fun `no listen pulse on the speaker`() = runTest {
        val rig = Rig(this, "Hello")
        rig.output.headphones = false
        rig.input.hold = true
        rig.controller.startTurn()
        advanceTimeBy(7_000)
        rig.controller.finishRecording()
        advanceUntilIdle()
        assertFalse(Cue.PULSE in rig.output.cues)
        assertTrue(rig.log.lines.any { it.endsWith("pulses: 0") })
    }

    @Test
    fun `listen pulse in auto mode - every recording`() = runTest {
        val rig = Rig(this, "Hello", "Again")
        rig.controller.setAutoMode(true)
        rig.input.hold = true
        rig.controller.startTurn()
        advanceTimeBy(2_500)
        rig.controller.finishRecording()
        advanceTimeBy(10_000) // ответ, пауза, снова запись
        assertEquals(2, rig.input.records)
        assertTrue(rig.log.lines.any { it.endsWith("pulses: 1") }) // первая запись — 2,5 с
        // вторая запись тоже с пульсом: он звучит после второго «говори»
        val secondListen = rig.output.cues.lastIndexOf(Cue.LISTEN)
        assertTrue(Cue.PULSE in rig.output.cues.drop(secondListen))
        rig.controller.stop(EndReason.STOP_BUTTON)
    }
}
