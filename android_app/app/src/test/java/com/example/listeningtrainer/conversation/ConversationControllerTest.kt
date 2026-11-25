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

    /**
     * Подставной учитель: ответ [replyText] потоком через [replyDelayMs] после запроса, кусками по
     * [pieceSize] знаков с паузой [pieceDelayMs]. [searchMs] — перед текстом «поиск» этой длины.
     * Ошибка: [errors] — по одной на запрос (очередь), иначе [error] на каждый; [breakAfterChars] —
     * обрыв потока (одного запроса), когда отдано столько знаков.
     */
    private class FakeTutor(var replyText: String = "Hello there. How are you?") : Tutor {
        val replies = mutableListOf<String>()
        val levelNotes = mutableListOf<String?>()
        var farewells = 0
        var error: Exception? = null
        val errors = ArrayDeque<Exception?>()
        var farewellError: Exception? = null
        var replyDelayMs = 1000L
        var searchMs: Long? = null
        var pieceSize = Int.MAX_VALUE
        var pieceDelayMs = 0L
        var breakAfterChars: Int? = null
        var breakError: Exception = ServiceException(FailureKind.NO_NETWORK, "SocketException")
        var sources: List<TutorSource> = emptyList()
        var truncated = false
        override suspend fun reply(studentText: String, levelNote: String?, onEvent: suspend (TutorEvent) -> Unit) {
            levelNotes += levelNote
            replies += studentText
            stream(replyText, onEvent, errors.removeFirstOrNull() ?: error)
        }

        override suspend fun farewell(onEvent: suspend (TutorEvent) -> Unit) {
            farewells++
            stream("Let's take a break.", onEvent, farewellError)
        }

        private suspend fun stream(text: String, onEvent: suspend (TutorEvent) -> Unit, failure: Exception?) {
            searchMs?.let {
                onEvent(TutorEvent.SearchStarted)
                delay(it)
                onEvent(TutorEvent.SearchFinished)
            }
            delay(replyDelayMs)
            failure?.let { throw it }
            var sent = 0
            val breakAt = breakAfterChars.also { breakAfterChars = null } // обрыв — только в этом запросе
            for (piece in text.chunked(minOf(pieceSize, maxOf(1, text.length)))) {
                breakAt?.let { if (sent >= it) throw breakError }
                if (sent > 0) delay(pieceDelayMs)
                onEvent(TutorEvent.Text(piece))
                sent += piece.length
            }
            breakAt?.let { if (sent >= it) throw breakError }
            sources.forEach { onEvent(TutorEvent.Source(it)) }
            onEvent(TutorEvent.Done(TutorStats(truncated = truncated)))
        }

        override var lastTeacherText: String? = null
        var newConversations = 0
        override fun newConversation() { newConversations++ }
        val replaced = mutableListOf<String>()
        override fun replaceLastReply(text: String) { replaced += text }
    }

    /** Подставной поправщик: ответ [answer] через [delayMs]; [error] — сбой. */
    private class FakeCorrector(var answer: String = "OK", var delayMs: Long = 500L) : Corrector {
        val calls = mutableListOf<String>()
        var error: Exception? = null
        override suspend fun correct(studentText: String): CorrectorAnswer {
            calls += studentText
            delay(delayMs)
            error?.let { throw it }
            return CorrectorAnswer(answer, 0.0001)
        }
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

    private class Rig(
        scope: TestScope, vararg transcripts: Any, filler: String? = null, val corrector: FakeCorrector? = null,
        val level: StudentLevel? = null,
    ) {
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
                searchFiller = filler,
                corrector = corrector,
                studentLevel = level,
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
        assertTrue(rig.log.lines.contains("[Controller] reply: 22 chars, 1 groups; words 3, own_words 3, sentences 1, question no, russian no, correction no"))
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
    fun `footnote is shown as a source, neither spoken nor in the teacher text`() = runTest {
        val rig = Rig(this, "Gas price?")
        rig.tutor.replyText = "It is three dollars. ([site.com](https://site.com/gas?utm_source=openai))\n\nOk?"
        rig.tutor.sources = listOf(TutorSource("Gas prices", "https://site.com/gas?utm_source=openai"))
        rig.tutor.pieceSize = 3
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("It is three dollars.", "Ok?"), rig.synth.texts)
        assertEquals("It is three dollars.\n\nOk?", rig.controller.ui.value.teacherText)
        assertEquals(listOf(TutorSource("Gas prices", "https://site.com/gas?utm_source=openai")), rig.controller.ui.value.sources)
        assertFalse(rig.log.lines.any { "site.com" in it })
    }

    @Test
    fun `timing line is written once per turn without texts`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.controller.startTurn()
        advanceUntilIdle()

        // распознавание 300 мс, учитель +1000, синтез +200; щита нет — слышно сразу
        assertEquals(listOf("enc=0 stt=300 first_token=1300 first_group=1300 tutor=1300 search=no search_ms=- filler=no reply_chars=25 first_audio=1500 audible=1500 filler_audio=-"), rig.timing)
    }

    @Test
    fun `list number is its own group, 400 ms before the heading, colon joins the heading`() = runTest {
        val rig = Rig(this, "Hi")
        rig.tutor.replyText = "1. **Make a plan**:\nwrite it down."
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

        assertEquals(listOf("enc=0 stt=300 first_token=1300 first_group=1300 tutor=1300 search=no search_ms=- filler=no reply_chars=25 first_audio=1500 audible=2700 filler_audio=-"), rig.timing)
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

    // ---- реплика по «Методике» (S02e) -------------------------------------------------------

    @Test
    fun `reply cut by the length limit - the unfinished sentence is not spoken`() = runTest {
        val rig = Rig(this, "Weather?")
        rig.tutor.replyText = "It is warm today. Do you like the sun? And the wind is"
        rig.tutor.truncated = true
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("It is warm today.", "Do you like the sun?"), rig.output.played)
        assertEquals("It is warm today. Do you like the sun?", rig.controller.ui.value.teacherText)
        assertTrue(rig.log.lines.contains("[Controller] reply cut by the length limit: unfinished sentence not spoken"))
        assertTrue(rig.log.lines.contains("[Controller] reply: 38 chars, 2 groups; words 9, own_words 9, sentences 2, question yes, russian no, correction no"))
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
    }

    @Test
    fun `reply metrics in the log - question at the end and a russian word, no text`() = runTest {
        val rig = Rig(this, "What?")
        rig.tutor.replyText = "I think you meant \"What is noon?\", right? \"Noon\" is \"полдень\". Is it clear?"
        rig.controller.startTurn()
        advanceUntilIdle()

        val line = rig.log.lines.single { it.startsWith("[Controller] reply: ") }
        assertTrue(line, line.endsWith("; words 14, own_words 6, sentences 3, question yes, russian yes, correction yes (teacher)"))
        assertFalse(line, "Noon" in line || "полдень" in line)
    }

    // ---- поток ответа (S02d) ---------------------------------------------------------------

    @Test
    fun `streamed reply - the voice starts before the teacher has finished writing`() = runTest {
        val rig = Rig(this, "Count")
        rig.tutor.replyText = "One, two, three, four, five, six."
        rig.tutor.pieceSize = 5
        rig.tutor.pieceDelayMs = 500 // куски в 1300, 1800, … 4300
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("One,", "two,", "three,", "four,", "five,", "six."), rig.output.played)
        // «One,» не изменится, когда после «two, » пришёл знак (2300); синтез 200 мс
        assertEquals(
            listOf("enc=0 stt=300 first_token=1300 first_group=2300 tutor=4300 search=no search_ms=- filler=no reply_chars=33 first_audio=2500 audible=2500 filler_audio=-"),
            rig.timing,
        )
        assertTrue(rig.output.startTimes.first() < 4300)
        assertEquals("One, two, three, four, five, six.", rig.controller.ui.value.teacherText)
        assertTrue(rig.log.lines.contains("[Controller] reply: 33 chars, 6 groups; words 6, own_words 6, sentences 1, question no, russian no, correction no"))
    }

    @Test
    fun `teacher text grows on the screen while the reply streams`() = runTest {
        val rig = Rig(this, "Count")
        rig.tutor.replyText = "One, two, three."
        rig.tutor.pieceSize = 5
        rig.tutor.pieceDelayMs = 500
        rig.controller.startTurn()
        advanceTimeBy(1300 + 1)
        assertEquals("One,", rig.controller.ui.value.teacherText)
        advanceTimeBy(500)
        assertEquals("One, two,", rig.controller.ui.value.teacherText)
        advanceUntilIdle()
        assertEquals("One, two, three.", rig.controller.ui.value.teacherText)
    }

    @Test
    fun `pause after a group is decided when it has sounded - paragraph pause before a late heading`() = runTest {
        val rig = Rig(this, "Sunset?")
        rig.tutor.replyText = "Sunset today, at 5:20 PM\n**Monday, November 23**\nIs that late?"
        rig.tutor.pieceSize = 1
        rig.tutor.pieceDelayMs = 20
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(SpeechPlanner().plan(rig.tutor.replyText).map { it.text.replace("**", "") }, rig.output.played)
        val t = rig.output.startTimes
        assertTrue("${t[2] - t[1]}", t[2] - t[1] >= 1600) // абзац перед жирным заголовком, а не 250 мс внутри фразы
    }

    @Test
    fun `stream broke before anything was spoken - one retry, the whole reply is spoken`() = runTest {
        val rig = Rig(this, "Hello")
        rig.tutor.errors += ServiceException(FailureKind.NO_NETWORK, "SocketException")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("Hello", "Hello"), rig.tutor.replies)
        assertEquals(listOf("Hello there.", "How are you?"), rig.output.played)
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
        assertTrue(rig.log.lines.contains("[Controller] tutor failed (SocketException), nothing spoken yet: retry in 1000 ms"))
        assertTrue(rig.log.lines.contains("[Controller] tutor retry succeeded"))
        assertFalse(Cue.ERROR in rig.output.cues)
        // первая попытка: 300 + 1000, пауза 1000, вторая: +1000
        assertTrue(rig.timing.single(), rig.timing.single().contains("first_token=3300"))
    }

    @Test
    fun `stream broke after the first piece but before any sound - retry, screen starts again`() = runTest {
        val rig = Rig(this, "Hello")
        rig.tutor.replyText = "One, two, three."
        rig.tutor.pieceSize = 5
        rig.tutor.pieceDelayMs = 50
        rig.tutor.breakAfterChars = 15 // «One, two, three» пришло, «One,» ушла в синтез, звука ещё нет
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(2, rig.tutor.replies.size)
        assertEquals(listOf("One,", "two,", "three."), rig.output.played)
        assertEquals("One, two, three.", rig.controller.ui.value.teacherText)
        assertTrue(rig.log.lines.contains("[Controller] reply broken off: 1 groups sent to synthesis"))
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
    }

    @Test
    fun `second failure before any sound - error, phone voice, end`() = runTest {
        val rig = Rig(this, "Hello")
        rig.tutor.error = ServiceException(FailureKind.SERVER, "http 503")
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(2, rig.tutor.replies.size)
        assertEquals(listOf(FailureKind.Phrases.NO_ANSWER), rig.output.played)
        assertTrue(rig.log.lines.contains("[Controller] tutor retry failed (http 503)"))
        assertEquals(listOf("TUTOR_ERROR"), rig.circleEnds)
    }

    @Test
    fun `no retry for a failure that a retry cannot fix`() = runTest {
        val rig = Rig(this, "Hello")
        rig.tutor.error = ServiceException(FailureKind.QUOTA, "http 429 insufficient_quota")
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(1, rig.tutor.replies.size)
        assertTrue(rig.log.lines.contains("[Controller] tutor failed (http 429 insufficient_quota), no retry"))
        assertEquals(listOf(FailureKind.Phrases.QUOTA), rig.output.played)
    }

    @Test
    fun `stream broke while the teacher was speaking - ready groups are read, then error, phone voice, end`() = runTest {
        val rig = Rig(this, "Count")
        rig.tutor.replyText = "One, two, three, four, five, six."
        rig.tutor.pieceSize = 5
        rig.tutor.pieceDelayMs = 500
        rig.tutor.breakAfterChars = 20 // обрыв в 2800; «One,» звучит с 2500
        rig.output.playMs = 1000
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(1, rig.tutor.replies.size) // без повтора: учитель уже говорил
        assertEquals(listOf("One,", "two,", FailureKind.Phrases.NO_NETWORK), rig.output.played)
        assertEquals(listOf("TUTOR_ERROR"), rig.circleEnds)
        assertEquals(listOf(Cue.ERROR, Cue.END), rig.output.cues.takeLast(2))
        assertTrue(rig.log.lines.contains("[Controller] tutor stream broke (SocketException) while the teacher was speaking: ready groups spoken"))
        // «ошибка» — после последней готовой группы
        val ev = rig.timeline.events
        assertTrue(ev.lastIndexOf("cue:ERROR") > ev.indexOf("play") )
        assertEquals(1, rig.timing.size)
        assertTrue(rig.timing.single(), rig.timing.single().contains("tutor=- search=no search_ms=- filler=no reply_chars=20 "))
    }

    // ---- фраза на время поиска (D39) -------------------------------------------------------

    @Test
    fun `search - the teacher says the filler phrase, then the answer`() = runTest {
        val rig = Rig(this, "Gas price?", "And now?", filler = "Let me check.")
        rig.tutor.searchMs = 2000
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("Let me check.", "Hello there.", "How are you?"), rig.output.played)
        assertEquals("Let me check.", rig.synth.texts.first()) // синтез — пока ученик говорит
        assertTrue(rig.log.lines.any { it.startsWith("[Controller] search filler ready:") })
        assertTrue(rig.log.lines.contains("[Controller] search started, nothing said yet: filler phrase"))
        assertEquals(
            listOf("enc=0 stt=300 first_token=3300 first_group=3300 tutor=3300 search=yes search_ms=2000 filler=yes reply_chars=25 first_audio=3500 audible=3500 filler_audio=300"),
            rig.timing,
        )
        assertEquals(300L, rig.output.startTimes.first()) // фраза — сразу, как учитель начал искать

        // Второй ответ: фраза та же, синтез не повторяется (один раз за жизнь сервиса).
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(1, rig.synth.texts.count { it == "Let me check." })
        assertEquals(2, rig.output.played.count { it == "Let me check." })
    }

    @Test
    fun `filler is not cut when the search ends early, the answer follows it`() = runTest {
        val rig = Rig(this, "Gas price?", filler = "Let me check.")
        rig.tutor.searchMs = 100
        rig.output.playMs = 1500 // фраза 300…1800, первая группа готова в 1600
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(listOf("Let me check.", "Hello there.", "How are you?"), rig.output.played)
        assertTrue(rig.output.startTimes[1] >= rig.output.startTimes[0] + 1500)
    }

    @Test
    fun `no search - no filler phrase`() = runTest {
        val rig = Rig(this, "Hello", filler = "Let me check.")
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(listOf("Hello there.", "How are you?"), rig.output.played)
        assertTrue(rig.timing.single().contains("search=no search_ms=- filler=no"))
    }

    @Test
    fun `filler not ready yet - skipped, the answer is spoken as usual`() = runTest {
        val rig = Rig(this, "Gas price?", filler = "Let me check.")
        rig.synth.delayMs = 1000 // фраза готова только в 1000, поиск начался в 300
        rig.tutor.searchMs = 500
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(listOf("Hello there.", "How are you?"), rig.output.played)
        assertTrue(rig.log.lines.contains("[Controller] search started: filler not ready, skipped"))
        assertTrue(rig.timing.single().contains("filler=no"))
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
        assertEquals(listOf("enc=400 stt=700 first_token=1700 first_group=1700 tutor=1700 search=no search_ms=- filler=no reply_chars=25 first_audio=1900 audible=1900 filler_audio=-"), rig.timing)
    }

    @Test
    fun `timing line has search, its length and reply length`() = runTest {
        val rig = Rig(this, "Gas price?")
        rig.tutor.searchMs = 900
        rig.tutor.replyText = "It is three dollars."
        rig.controller.startTurn()
        advanceUntilIdle()
        // поиск 300…1200, текст через 1000 после него
        assertEquals(
            listOf("enc=0 stt=300 first_token=2200 first_group=2200 tutor=2200 search=yes search_ms=900 filler=no reply_chars=20 first_audio=2400 audible=2400 filler_audio=-"),
            rig.timing,
        )
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
        assertFalse(rig.controller.headsetButton(HeadsetButton.KEYCODE_HEADSETHOOK, 1_200)) // долгое — системе
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

    // ---- поправщик (S02f) ----------------------------------------------------------------

    private val fixPhrase = Correction.phrase("I have a dog.")
    private val fixGroups = SpeechPlanner().plan(fixPhrase).map { it.text }

    @Test
    fun `corrector - correction is spoken first, then the reply, history as spoken`() = runTest {
        val rig = Rig(this, "I have dog.", corrector = FakeCorrector("I have a dog."))
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals("I think you meant \"I have a dog\", right?", fixPhrase)
        assertEquals(fixGroups + listOf("Hello there.", "How are you?"), rig.output.played)
        assertEquals(listOf("I have dog."), rig.corrector!!.calls)
        assertEquals(listOf("$fixPhrase Hello there. How are you?"), rig.tutor.replaced)
        assertEquals("$fixPhrase Hello there. How are you?", rig.controller.ui.value.teacherText)
        assertTrue(rig.log.lines.contains("[Network] corrector: correction in 500 ms; cost \$0.00010"))
        val reply = rig.log.lines.single { it.startsWith("[Controller] reply: ") }
        assertTrue(reply, reply.endsWith(", correction yes (corrector)"))
        assertTrue(reply, ", own_words 5, " in reply) // свои слова учителя — без поправки (S02g)
        assertFalse(rig.log.lines.any { "dog" in it })
    }

    @Test
    fun `corrector - OK or the same sentence - no correction`() = runTest {
        for (answer in listOf("OK", "i have dog")) {
            val rig = Rig(this, "I have dog.", corrector = FakeCorrector(answer))
            rig.controller.startTurn()
            advanceUntilIdle()
            assertEquals(listOf("Hello there.", "How are you?"), rig.output.played)
            assertTrue(rig.log.lines.contains("[Network] corrector: ok in 500 ms; cost \$0.00010"))
            assertTrue(rig.tutor.replaced.isEmpty())
            assertTrue(rig.log.lines.single { it.startsWith("[Controller] reply: ") }.endsWith(", correction no"))
        }
    }

    @Test
    fun `corrector - an answer unlike the student's words is rejected`() = runTest {
        val rig = Rig(this, "Where you live?", corrector = FakeCorrector("I live in Tucson."))
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(listOf("Hello there.", "How are you?"), rig.output.played)
        assertTrue(rig.log.lines.contains("[Network] corrector: rejected (similarity 0.33) in 500 ms; cost \$0.00010"))
    }

    @Test
    fun `corrector - late answer is skipped, the reply waits no longer than the limit`() = runTest {
        // Учитель начал писать в 1300; поправка ждётся до 1300 + 800 = 2100, она придёт в 300 + 2000 = 2300.
        val rig = Rig(this, "I have dog.", corrector = FakeCorrector("I have a dog.", delayMs = 2000))
        rig.output.playMs = 500
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(listOf("Hello there.", "How are you?"), rig.output.played)
        assertEquals(2100L + 200, rig.output.startTimes.first()) // решение в 2100, синтез 200 мс
        assertTrue(rig.log.lines.contains("[Network] corrector: late correction in 2000 ms; cost \$0.00010"))
        assertTrue(rig.tutor.replaced.isEmpty())
    }

    @Test
    fun `corrector - no answer before the turn ends - logged as late`() = runTest {
        val rig = Rig(this, "I have dog.", corrector = FakeCorrector("I have a dog.", delayMs = 60_000))
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(listOf("Hello there.", "How are you?"), rig.output.played)
        assertTrue(rig.log.lines.any { it.startsWith("[Network] corrector: late, no answer in ") && it.endsWith(" ms (turn over)") })
    }

    @Test
    fun `corrector - failure never breaks the reply`() = runTest {
        val rig = Rig(this, "I have dog.", corrector = FakeCorrector().apply { error = ServiceException(FailureKind.TIMEOUT, "boom") })
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(listOf("Hello there.", "How are you?"), rig.output.played)
        assertTrue(rig.log.lines.contains("[Network] corrector: failed (boom) in 500 ms; cost unknown"))
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
    }

    @Test
    fun `corrector corrected - the teacher's own correction is not spoken`() = runTest {
        val rig = Rig(this, "I have dog.", corrector = FakeCorrector("I have a dog."))
        rig.tutor.replyText = "I think you meant \"I have a dog\", right? What is its name?"
        rig.tutor.pieceSize = 5
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(fixGroups + listOf("What is its name?"), rig.output.played)
        assertTrue(rig.log.lines.contains("[Controller] teacher's own correction not spoken: the corrector already corrected"))
        assertEquals(listOf("$fixPhrase What is its name?"), rig.tutor.replaced)
    }

    @Test
    fun `corrector says OK - the teacher's own correction is spoken and logged as the teacher's`() = runTest {
        val rig = Rig(this, "I have dog.", corrector = FakeCorrector("OK"))
        rig.tutor.replyText = "I think you meant \"I have a dog\", right? What is its name?"
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(fixGroups + listOf("What is its name?"), rig.output.played)
        assertTrue(rig.log.lines.single { it.startsWith("[Controller] reply: ") }.endsWith(", correction yes (teacher)"))
        assertTrue(rig.tutor.replaced.isEmpty())
    }

    @Test
    fun `corrector and search - correction, then the filler phrase, then the answer`() = runTest {
        val rig = Rig(this, "Gas price?", filler = "Let me check.", corrector = FakeCorrector("What is the gas price?"))
        rig.tutor.searchMs = 2000
        rig.output.playMs = 300
        rig.controller.startTurn()
        advanceUntilIdle()
        val fix = SpeechPlanner().plan(Correction.phrase("What is the gas price?")).map { it.text }
        assertEquals(fix + listOf("Let me check.", "Hello there.", "How are you?"), rig.output.played)
        // Ни один звук не накладывается на другой.
        val starts = rig.output.startTimes
        assertTrue(starts.zipWithNext().all { (a, b) -> b >= a + 300 })
        assertTrue(rig.timing.single(), rig.timing.single().contains("filler=yes"))
    }

    @Test
    fun `corrector - not asked on silence, asked once even when the teacher is retried`() = runTest {
        val rig = Rig(this, "I have dog.", corrector = FakeCorrector("I have a dog."))
        rig.tutor.errors += ServiceException(FailureKind.TIMEOUT, "timeout")
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(listOf("I have dog."), rig.corrector!!.calls)
        assertEquals(fixGroups + listOf("Hello there.", "How are you?"), rig.output.played)
        assertEquals(1, rig.log.lines.count { it.startsWith("[Network] corrector:") })

        val silent = Rig(this, corrector = FakeCorrector())
        silent.input.speech += false
        silent.controller.startTurn()
        advanceUntilIdle()
        assertTrue(silent.corrector!!.calls.isEmpty())
    }

    // ---- S02h: уровень ученика ---------------------------------------------------------------

    @Test
    fun `level - each student turn counted once, note of the level goes to the tutor, log line per turn`() = runTest {
        val level = StudentLevel(MemoryLevelStore(StudentLevel.State(level = 3)))
        val rig = Rig(this, "I am fine", level = level)
        rig.tutor.errors += ServiceException(FailureKind.NO_NETWORK, "SocketException") // повтор запроса
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(2, rig.tutor.replies.size) // запрос и повтор
        assertEquals(listOf(level.note(3), level.note(3)), rig.tutor.levelNotes)
        assertEquals(StudentLevel.State(3, 1, 0), level.state.value) // но в окне — одна реплика
        assertTrue(rig.log.lines.contains("[Controller] level 3 (window 1 of 20, not understood 0)"))
        assertEquals("(App note: target length: 5 to 6 sentences, 45 to 60 words.)", rig.tutor.levelNotes[0])
    }

    @Test
    fun `level - silence is not counted, level up logged, new conversation keeps the level`() = runTest {
        val level = StudentLevel(MemoryLevelStore(StudentLevel.State(level = 1, turns = 19, notUnderstood = 1)))
        val rig = Rig(this, "", "What?", level = level)
        rig.controller.startTurn() // пустая расшифровка — молчание
        advanceUntilIdle()
        assertEquals(StudentLevel.State(1, 19, 1), level.state.value)

        rig.controller.startTurn() // 20-я реплика: «не понял» 2 — уровень тот же
        advanceUntilIdle()
        assertEquals(StudentLevel.State(1, 0, 0), level.state.value)
        assertTrue(rig.log.lines.contains("[Controller] level 1 (window 0 of 20, not understood 0)"))
        assertFalse(rig.log.lines.any { "level up" in it })

        level.setLevel(2)
        rig.controller.newConversation()
        assertEquals(2, level.level)
    }

    @Test
    fun `level - the window full of understood turns moves the level up`() = runTest {
        val level = StudentLevel(MemoryLevelStore(StudentLevel.State(level = 4, turns = 19, notUnderstood = 0)))
        val rig = Rig(this, "I am fine", level = level)
        rig.controller.startTurn()
        advanceUntilIdle()
        assertTrue(rig.log.lines.contains("[Controller] level up 4 → 5"))
        assertTrue(rig.log.lines.contains("[Controller] level 5 (window 0 of 20, not understood 0)"))
        assertEquals(level.note(5), rig.tutor.levelNotes.single())
    }

    @Test
    fun `level - without a level there is no note`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), rig.tutor.levelNotes)
        assertFalse(rig.log.lines.any { "] level " in it })
    }
}
