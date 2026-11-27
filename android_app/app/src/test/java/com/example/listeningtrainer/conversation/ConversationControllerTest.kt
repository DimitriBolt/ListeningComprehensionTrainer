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
        /** Чем кончилась очередная запись с речью (S04); пусто — тишиной без судьи (как до S04). */
        val endings = ArrayDeque<TurnEnd>()
        var endWaitMs = 0L
        /** Речь в записи по Silero (S05b, D83: короче 1500 мс — короткая реплика); пусто — 2400 мс. */
        val speechMs = ArrayDeque<Long>()
        /** Запись-продолжение (S04): через сколько мс ученик заговорил снова; `null` или пусто — молчит. */
        val resumes = ArrayDeque<Long?>()
        var continuations = 0
        var continuationsCancelled = 0
        private var pending: CompletableDeferred<Unit>? = null

        override suspend fun record(turnDetector: TurnDetector, sink: AudioSink?, onLevel: (Float) -> Unit): RecordedAudio {
            records++
            timeline.events += "record"
            statesAtRecord += ui().state
            error?.let { throw it }
            val heard = speech.removeFirstOrNull() ?: true
            onLevel(if (heard) 65f else 40f)
            sink?.onPcm(ShortArray(1280), 1280)
            if (hold) CompletableDeferred<Unit>().also { pending = it }.await()
            sink?.onEnd(heard)
            return audio(heard)
        }

        private fun audio(heard: Boolean) = RecordedAudio(
            File("rec$records.wav"), durationMs = 3000, heardSpeech = heard, peakDb = if (heard) 65f else 40f,
            endedBy = if (heard) endings.removeFirstOrNull() ?: TurnEnd.SILENCE else TurnEnd.BUTTON,
            turn = if (heard) TurnStats(speechMs = speechMs.removeFirstOrNull() ?: 2_400, frames = 94, avgFrameMs = 0.84, maxFrameMs = 2.5, endWaitMs = endWaitMs) else TurnStats(),
            encodeMs = encodeMs,
        )

        override suspend fun recordContinuation(turnDetector: TurnDetector, sink: AudioSink?, onLevel: (Float) -> Unit): RecordedAudio {
            continuations++
            timeline.events += "continuation"
            val after = resumes.removeFirstOrNull()
            try {
                if (after == null) CompletableDeferred<Unit>().await() // молчит: запись бросят, когда звучит учитель
                delay(after!!)
            } catch (e: kotlinx.coroutines.CancellationException) {
                continuationsCancelled++
                throw e
            }
            (turnDetector as CtxDetector).ctx.onHeardSpeech()
            records++
            delay(1_500) // договорил и замолчал
            sink?.onPcm(ShortArray(1280), 1280)
            sink?.onEnd(true)
            return audio(true)
        }

        override fun finish() {
            finishes++
            pending?.complete(Unit)
        }

        /** Слушание, пока звучит учитель (S05): сколько раз открыто и сколько раз брошено без перебивания. */
        var listens = 0
        var listensCancelled = 0
        /** Сколько «говорит» ученик после перебивания, пока не замолчит. */
        var bargeSpeechMs = 1_500L

        override suspend fun recordBargeIn(
            watch: MicWatch,
            turnDetector: TurnDetector,
            onBargeIn: () -> AudioSink?,
            onLevel: (Float) -> Unit,
        ): RecordedAudio {
            listens++
            timeline.events += "listen"
            val frame = ShortArray(512) // 32 мс при 16 кГц
            try {
                while (true) {
                    delay(32)
                    if (watch.onAudio(frame, frame.size)) break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                listensCancelled++
                throw e
            }
            val sink = onBargeIn()
            timeline.events += "barge-in"
            records++
            sink?.onPcm(watch.preroll(), watch.preroll().size)
            delay(bargeSpeechMs)
            sink?.onEnd(true)
            return audio(true)
        }
    }

    /** Silero для перебивания (S05): вероятность речи по времени теста. */
    private class FakeVad(private val clock: () -> Long) : SpeechProbability {
        override val frameSamples = 512
        /** С какого момента (часы теста) ученик говорит и до какого; `null` — молчит. */
        var speakFrom: Long? = null
        var speakUntil: Long = Long.MAX_VALUE
        var speechP = 0.95f
        var silenceP = 0.05f
        override fun reset() {}
        override fun probability(frame: ShortArray): Float {
            val t = clock()
            val from = speakFrom
            return if (from != null && t >= from && t < speakUntil) speechP else silenceP
        }
    }

    private class FakeTranscriber(private val ui: () -> ConversationUi, vararg answers: Any) : Transcriber {
        private val queue = ArrayDeque(answers.toList())
        val statesAtCall = mutableListOf<ConversationState>()
        var calls = 0
        val prompts = mutableListOf<String>()
        /** Сколько «идёт» распознавание файлом. */
        var delayMs = 300L
        override suspend fun transcribe(audio: RecordedAudio, prompt: String): String {
            calls++
            prompts += prompt
            statesAtCall += ui().state
            delay(delayMs)
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
        /** Перебитые ответы (S05): реплика ученика и что прозвучало с пометкой. */
        val interrupted = mutableListOf<Pair<String, String>>()
        override fun interrupted(studentText: String, spoken: String) {
            interrupted += studentText to spoken
            lastTeacherText = BargeIn.unmarked(spoken).ifBlank { lastTeacherText }
        }
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
        val speeds = mutableListOf<Float>()
        val texts = mutableListOf<String>()
        val textTimes = mutableListOf<Long>()
        var error: Exception? = null
        var delayMs = 200L
        var prepares = 0
        var releases = 0
        var clock: () -> Long = { 0L }
        override fun prepare() { prepares++ }
        override fun release() { releases++ }
        override suspend fun synthesize(text: String, speed: Float): SynthesizedSpeech {
            speeds += speed
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

    /**
     * Поток распознавания: итог реплики — по очереди из [results] (строка — текст, `Fail(…)` — сбой); пусто —
     * «live text». Текст приходит через [delayMs] после конца записи.
     */
    private class FakeLive : LiveTranscriber {
        class Fail(val reason: String)
        val results = ArrayDeque<Any>()
        var delayMs = 200L
        /** Своя задержка для очередных реплик (D74: медленный поток); пусто — [delayMs]. */
        val delays = ArrayDeque<Long>()
        var prepares = 0
        var released = false
        val prompts = mutableListOf<String>()
        val turns = mutableListOf<Turn>()

        inner class Turn(private val onPartial: (String) -> Unit) : LiveTurn {
            var samples = 0
            var ended: Boolean? = null
            var cancelled = false
            override fun onPcm(pcm: ShortArray, count: Int) {
                samples += count
                onPartial("I like")
            }
            override fun onEnd(heardSpeech: Boolean) { ended = heardSpeech }
            override suspend fun result(): LiveResult {
                val wait = delays.removeFirstOrNull() ?: delayMs
                delay(wait)
                return when (val r = results.removeFirstOrNull() ?: "live text") {
                    is Fail -> LiveResult.Failed(r.reason, LiveStats(audioSentMs = 80, deltas = 2))
                    else -> LiveResult.Text(
                        r as String,
                        LiveStats(sessionWaitMs = 0, reused = turns.size > 1, audioSentMs = 2_500, commitToTextMs = wait, deltas = 3, chars = r.length, costUsd = 0.00071),
                    )
                }
            }
            override fun cancel() { cancelled = true }
        }

        override fun prepare() { prepares++ }
        override fun begin(prompt: String, onPartial: (String) -> Unit): LiveTurn {
            prompts += prompt
            return Turn(onPartial).also { turns += it }
        }
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
        val level: StudentLevel? = null, val live: FakeLive? = null, compare: Boolean = false,
        bargeIn: Boolean = false,
    ) {
        val compared = mutableListOf<Triple<String, String, Double>>()
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
        /** Перебивание (S05): своя подделка Silero; `null` — микрофон во время учителя закрыт. */
        val vad: FakeVad? = if (bargeIn) FakeVad(clock) else null
        val timing get() = log.lines.filter { it.startsWith("[Timing] ") }.map { it.removePrefix("[Timing] ") }
        val circleEnds get() = log.lines.filter { "circle end: " in it }.map { it.substringAfter("circle end: ").substringBefore(' ') }

        init {
            controller = ConversationController(
                scope = scope,
                input = input,
                turnDetector = CtxDetector(),
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
                liveTranscriber = live,
                compareLiveWithFile = compare,
                onLiveVsFile = { a, b, sim -> compared += Triple(a, b, sim) },
                bargeInVad = vad,
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
            listOf("${TranscriptionPrompt.CONTEXT} ${TranscriptionPrompt.COMMANDS_HINT} The teacher just said: \"How are you today?\""),
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
        assertEquals(listOf("enc=0 stt=300 first_token=1300 first_group=1300 tutor=1300 search=no search_ms=- filler=no reply_chars=25 first_audio=1500 audible=1500 filler_audio=- stt_path=file end_wait=0 ended_by=silence"), rig.timing)
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

        assertEquals(listOf("enc=0 stt=300 first_token=1300 first_group=1300 tutor=1300 search=no search_ms=- filler=no reply_chars=25 first_audio=1500 audible=2700 filler_audio=- stt_path=file end_wait=0 ended_by=silence"), rig.timing)
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
            listOf("enc=0 stt=300 first_token=1300 first_group=2300 tutor=4300 search=no search_ms=- filler=no reply_chars=33 first_audio=2500 audible=2500 filler_audio=- stt_path=file end_wait=0 ended_by=silence"),
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
            listOf("enc=0 stt=300 first_token=3300 first_group=3300 tutor=3300 search=yes search_ms=2000 filler=yes reply_chars=25 first_audio=3500 audible=3500 filler_audio=300 stt_path=file end_wait=0 ended_by=silence"),
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
        assertEquals(3, rig.log.lines.count { "counted as silence: yes (speech under 200 ms, transcription skipped)" in it })
        assertTrue(rig.log.lines.any { "recording: 3000 ms, speech 0 ms (silero), ended by button, peak 40 dB, vad frames 0, avg 0.0 ms, max 0.0 ms" in it })
        assertEquals(listOf("FAREWELL"), rig.circleEnds)
    }

    @Test
    fun `transcript length and silence decision are logged`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.controller.startTurn()
        advanceUntilIdle()

        assertTrue(rig.log.lines.contains("[Controller] recording: 3000 ms, speech 2400 ms (silero), ended by silence, peak 65 dB, vad frames 94, avg 0.8 ms, max 2.5 ms, pulses: 0"))
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
        assertEquals(listOf("enc=400 stt=700 first_token=1700 first_group=1700 tutor=1700 search=no search_ms=- filler=no reply_chars=25 first_audio=1900 audible=1900 filler_audio=- stt_path=file end_wait=0 ended_by=silence"), rig.timing)
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
            listOf("enc=0 stt=300 first_token=2200 first_group=2200 tutor=2200 search=yes search_ms=900 filler=no reply_chars=20 first_audio=2400 audible=2400 filler_audio=- stt_path=file end_wait=0 ended_by=silence"),
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
        assertTrue(rig.log.lines.any { it.endsWith("max 2.5 ms, pulses: 3") })
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

    // ---- S02i: досрочное понижение (D59) -------------------------------------------------------

    @Test
    fun `level - the fourth not understood on the 8th turn moves the level down at once`() = runTest {
        val level = StudentLevel(MemoryLevelStore(StudentLevel.State(level = 3, turns = 7, notUnderstood = 3)))
        val rig = Rig(this, "Sorry, I don't understand.", level = level)
        rig.controller.startTurn()
        advanceUntilIdle()
        assertTrue(rig.log.lines.contains("[Controller] level down 3 → 2"))
        assertTrue(rig.log.lines.contains("[Controller] level 2 (window 0 of 20, not understood 0)"))
        assertEquals(StudentLevel.State(2, 0, 0), level.state.value)
    }

    @Test
    fun `level - without a level there is no note`() = runTest {
        val rig = Rig(this, "I am fine")
        rig.controller.startTurn()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), rig.tutor.levelNotes)
        assertFalse(rig.log.lines.any { "] level " in it })
    }

    // ---- потоковое распознавание (S03d) ---------------------------------------------------

    @Test
    fun `live - text from the stream, the file is not sent, stt_path live`() = runTest {
        val live = FakeLive().apply { results += "I like coffee" }
        val rig = Rig(this, "file text", live = live)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(0, rig.transcriber.calls)
        assertEquals("I like coffee", rig.controller.ui.value.studentText)
        assertEquals("I like coffee", rig.tutor.replies.single())
        val turn = live.turns.single()
        assertEquals(1280, turn.samples)
        assertEquals(true, turn.ended)
        assertTrue(rig.log.lines.contains(
            "[Network] live transcription: session open in 0 ms (new), audio 2.5 s sent, commit → text 200 ms, 3 deltas, 13 chars; cost \$0.00071",
        ))
        assertEquals(listOf("enc=0 stt=200 first_token=1200 first_group=1200 tutor=1200 search=no search_ms=- filler=no reply_chars=25 first_audio=1400 audible=1400 filler_audio=- stt_path=live end_wait=0 ended_by=silence"), rig.timing)
        assertFalse(rig.log.lines.any { "I like coffee" in it })
    }

    @Test
    fun `live - the prompt is the same as for the file, session prepared at start and before every recording`() = runTest {
        val live = FakeLive()
        val rig = Rig(this, live = live)
        rig.controller.prepare()
        rig.controller.startTurn()
        advanceUntilIdle()
        rig.tutor.lastTeacherText = "Good. Do you like tea?"
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(3, live.prepares)
        assertEquals(TranscriptionPrompt.build(null, 200), live.prompts[0])
        assertEquals(TranscriptionPrompt.build("Good. Do you like tea?", 200), live.prompts[1])
        assertTrue(rig.log.lines.any { "live transcription: session open in 0 ms (reused)" in it })
    }

    @Test
    fun `live failure - text from the file, one line in the log, stt_path file`() = runTest {
        val live = FakeLive().apply { results += FakeLive.Fail("no text in 2500 ms") }
        val rig = Rig(this, "I like tea", live = live)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(1, rig.transcriber.calls)
        assertEquals("I like tea", rig.tutor.replies.single())
        assertTrue(rig.log.lines.contains("[Network] live transcription failed (no text in 2500 ms) → file (deltas 2, audio 0.1 s sent)"))
        // поток 200 мс до сбоя, файл ещё 300 мс
        assertEquals("stt=500", rig.timing.single().split(' ')[1])
        assertTrue(rig.timing.single().endsWith("stt_path=file end_wait=0 ended_by=silence"))
    }

    @Test
    fun `live failure and the file fails too - transcription error as before`() = runTest {
        val live = FakeLive().apply { results += FakeLive.Fail("closed: network: SocketException") }
        val rig = Rig(this, ServiceException(FailureKind.NO_NETWORK, "network"), live = live)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("TRANSCRIPTION_ERROR"), rig.circleEnds)
    }

    @Test
    fun `live - silence ends the stream without a commit, nothing is transcribed`() = runTest {
        val live = FakeLive()
        val rig = Rig(this, live = live)
        rig.input.speech += false
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(false, live.turns.single().ended)
        assertEquals(0, rig.transcriber.calls)
        assertTrue(rig.log.lines.none { "live transcription" in it })
        assertEquals(listOf("SILENCE_MANUAL"), rig.circleEnds)
    }

    @Test
    fun `live - Stop while listening cancels the stream`() = runTest {
        val live = FakeLive()
        val rig = Rig(this, live = live)
        rig.input.hold = true
        rig.controller.startTurn()
        runCurrent()
        rig.controller.stop(EndReason.STOP_BUTTON)
        advanceUntilIdle()

        assertTrue(live.turns.single().cancelled)
        assertEquals(null, live.turns.single().ended)
    }

    @Test
    fun `live - partial text of the current turn is available, empty at the start of the next one`() = runTest {
        val live = FakeLive()
        val rig = Rig(this, live = live)
        rig.input.hold = true
        rig.controller.startTurn()
        runCurrent()
        assertEquals("I like", rig.controller.partialText.value)
        assertFalse(rig.log.lines.any { "I like" in it })
        rig.controller.finishRecording()
        advanceUntilIdle()
    }

    @Test
    fun `debug comparison - the file runs too, the log has only the similarity`() = runTest {
        val live = FakeLive().apply { results += "I like to read books" }
        val rig = Rig(this, "I like read books in evening", live = live, compare = true)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(1, rig.transcriber.calls)
        assertEquals("I like to read books", rig.tutor.replies.single())
        // общих слов 4 (i like read books), длиннее — 6 слов
        assertTrue(rig.log.lines.contains("[Network] live vs file: similarity 0.67 (5 vs 6 words)"))
        assertEquals(Triple("I like to read books", "I like read books in evening", 4.0 / 6), rig.compared.single())
        assertFalse(rig.log.lines.any { "read books" in it })
        // ответ учителя не ждал файл: поток 200 мс
        assertTrue(rig.timing.single().startsWith("enc=0 stt=200 "))
    }

    @Test
    fun `debug comparison - when the stream fails the file already running gives the text`() = runTest {
        val live = FakeLive().apply { results += FakeLive.Fail("error invalid_request_error/-") }
        val rig = Rig(this, "I like tea", live = live, compare = true)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(1, rig.transcriber.calls)
        assertEquals("I like tea", rig.tutor.replies.single())
        assertTrue(rig.log.lines.none { "live vs file" in it })
        assertEquals("stt=300", rig.timing.single().split(' ')[1])
    }

    // ---- страховка файлом (D74) и пустой текст (D76) -----------------------------------------

    @Test
    fun `slow stream - after 1000 ms the file goes in parallel and the first text wins`() = runTest {
        val live = FakeLive().apply { results += "live late"; delays += 1_500L }
        val rig = Rig(this, "I like tea", live = live)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(1, rig.transcriber.calls)
        assertEquals("I like tea", rig.tutor.replies.single())
        // 1000 мс ожидания потока + 300 мс файл
        assertEquals("stt=1300", rig.timing.single().split(' ')[1])
        assertTrue(rig.timing.single().endsWith("stt_path=file end_wait=0 ended_by=silence"))
        val lines = rig.log.lines
        assertTrue(lines.contains("[Network] live transcription: no text in 1000 ms after commit → file in parallel"))
        assertTrue(lines.contains("[Network] transcription: file first (live still waiting)"))
        // Итог потока всё равно в журнале, когда он пришёл.
        assertTrue(lines.any { it.startsWith("[Network] live transcription: session open") && it.endsWith("(late: file used)") })
    }

    // ---- короткая реплика: текст файла главный (S05b, D83) --------------------------------------------------

    @Test
    fun `short turn - the file goes at once, its text wins even when the stream is earlier`() = runTest {
        val live = FakeLive().apply { results += "Slowware."; delayMs = 200L }
        val rig = Rig(this, "Slower.", live = live)
        rig.input.speechMs += 600L
        rig.input.speech.addAll(listOf(true, false)) // после команды — снова слушание, ученик молчит
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(1, rig.transcriber.calls)
        assertTrue(rig.log.lines.contains("[Network] transcription: short turn (600 ms of speech) → file preferred"))
        // «Slower.» из файла — команда (без ответа есть нечего повторять), поток «Slowware.» не ушёл учителю.
        assertTrue(rig.tutor.replies.isEmpty())
        assertTrue(rig.log.lines.contains("[Turn] command slower: nothing to repeat yet → listening"))
        assertTrue(rig.log.lines.any { it.startsWith("[Network] live transcription: session open") && it.endsWith("(late: file used)") })
    }

    @Test
    fun `short turn - file later than the stream, still within the stream limit - file text, stt_path file-short`() = runTest {
        val live = FakeLive().apply { results += "Add noon."; delayMs = 200L }
        val rig = Rig(this, "At noon.", live = live)
        rig.input.speechMs += 700L
        rig.transcriber.delayMs = 1_800L
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals("At noon.", rig.tutor.replies.single())
        assertEquals("stt=1800", rig.timing.single().split(' ')[1])
        assertTrue(rig.timing.single().contains("stt_path=file-short"))
    }

    @Test
    fun `short turn - file earlier than the stream`() = runTest {
        val live = FakeLive().apply { results += "Add noon."; delayMs = 900L }
        val rig = Rig(this, "At noon.", live = live)
        rig.input.speechMs += 700L
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals("At noon.", rig.tutor.replies.single())
        assertEquals("stt=300", rig.timing.single().split(' ')[1])
        assertTrue(rig.timing.single().contains("stt_path=file-short"))
    }

    @Test
    fun `short turn - the file fails, the stream text is used`() = runTest {
        val live = FakeLive().apply { results += "At noon."; delayMs = 200L }
        val rig = Rig(this, ServiceException(FailureKind.NO_NETWORK, "network"), live = live)
        rig.input.speechMs += 700L
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals("At noon.", rig.tutor.replies.single())
        assertTrue(rig.timing.single().contains("stt_path=live"))
        assertTrue(rig.log.lines.contains("[Network] transcription: short turn: file failed (network) → live"))
    }

    @Test
    fun `short turn - the file is slower than the stream limit, the stream text is used`() = runTest {
        val live = FakeLive().apply { results += "At noon."; delayMs = 200L }
        val rig = Rig(this, "file text", live = live)
        rig.input.speechMs += 700L
        rig.transcriber.delayMs = 4_000L
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals("At noon.", rig.tutor.replies.single())
        assertEquals("stt=2500", rig.timing.single().split(' ')[1])
        assertTrue(rig.timing.single().contains("stt_path=live"))
        assertTrue(rig.log.lines.contains("[Network] transcription: short turn: file not ready in 2500 ms → live"))
    }

    @Test
    fun `short turn in debug - both texts still go to the comparison`() = runTest {
        val live = FakeLive().apply { results += "Add noon."; delayMs = 200L }
        val rig = Rig(this, "At noon.", live = live, compare = true)
        rig.input.speechMs += 700L
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals("At noon.", rig.tutor.replies.single())
        assertEquals(1, rig.transcriber.calls)
        assertEquals("Add noon." to "At noon.", rig.compared.single().let { it.first to it.second })
    }

    @Test
    fun `long turn - unchanged, the stream text and no file`() = runTest {
        val live = FakeLive().apply { results += "I like coffee very much"; delayMs = 200L }
        val rig = Rig(this, "file text", live = live)
        rig.input.speechMs += 1_500L
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals("I like coffee very much", rig.tutor.replies.single())
        assertEquals(0, rig.transcriber.calls)
        assertTrue(rig.timing.single().contains("stt_path=live"))
        assertTrue(rig.log.lines.none { "short turn" in it })
    }

    @Test
    fun `slow stream that still beats the file - text from the stream, stt_path live`() = runTest {
        val live = FakeLive().apply { results += "I like coffee"; delays += 1_200L }
        val rig = Rig(this, "file text", live = live)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals("I like coffee", rig.tutor.replies.single())
        assertEquals("stt=1200", rig.timing.single().split(' ')[1])
        assertTrue(rig.timing.single().endsWith("stt_path=live end_wait=0 ended_by=silence"))
        assertTrue(rig.log.lines.contains("[Network] live transcription: no text in 1000 ms after commit → file in parallel"))
        assertTrue(rig.log.lines.none { "file first" in it })
    }

    @Test
    fun `slow stream and the file fails - the stream text is used`() = runTest {
        val live = FakeLive().apply { results += "I like coffee"; delays += 1_800L }
        val rig = Rig(this, ServiceException(FailureKind.NO_NETWORK, "network"), live = live)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals("I like coffee", rig.tutor.replies.single())
        assertTrue(rig.timing.single().endsWith("stt_path=live end_wait=0 ended_by=silence"))
    }

    @Test
    fun `the backup timer counts from the commit - encoding time is already spent`() = runTest {
        val live = FakeLive().apply { results += "live late"; delays += 1_500L }
        val rig = Rig(this, "I like tea", live = live)
        rig.input.encodeMs = 400
        rig.controller.startTurn()
        advanceUntilIdle()

        // 600 мс после возврата записи (+ 400 мс сжатия = 1000 от commit) + 300 мс файл; stt считает и enc
        assertEquals("stt=1300", rig.timing.single().split(' ')[1])
    }

    @Test
    fun `empty text from the stream - silence, one line in the log, no file`() = runTest {
        val live = FakeLive().apply { results += "" }
        val rig = Rig(this, "file text", live = live)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(0, rig.transcriber.calls)
        assertTrue(rig.log.lines.contains("[Network] live transcription: empty text (3 deltas) → counted as silence, no file"))
        assertEquals(listOf("SILENCE_MANUAL"), rig.circleEnds)
    }

    // ---- конец реплики: судья и отмена (S04) -------------------------------------------------

    @Test
    fun `judge end and the student stays silent - the teacher answers, the continuation is dropped`() = runTest {
        val rig = Rig(this, "At noon")
        rig.input.endings += TurnEnd.JUDGE
        rig.input.endWaitMs = 1024
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("At noon"), rig.tutor.replies)
        assertEquals(1, rig.input.continuations)
        assertEquals(1, rig.input.continuationsCancelled)
        assertTrue(rig.timing.single().endsWith("end_wait=1024 ended_by=judge"))
        assertTrue(rig.log.lines.none { "resumed after end" in it })
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
    }

    @Test
    fun `the student goes on before the teacher - reply cancelled, one turn with both texts`() = runTest {
        val level = StudentLevel(MemoryLevelStore())
        val rig = Rig(this, "I like", "to read books", corrector = FakeCorrector(), level = level)
        rig.input.endings += TurnEnd.JUDGE
        rig.input.resumes += 600L
        rig.controller.startTurn()
        advanceUntilIdle()

        // первый запрос к учителю отменён (ничего не прозвучало), второй — на всю реплику
        assertEquals(listOf("I like", "I like to read books"), rig.tutor.replies)
        assertEquals(listOf("Hello there.", "How are you?"), rig.output.played)
        assertEquals(2, rig.output.cues.count { it == Cue.PROCESSING })
        assertEquals(1, rig.output.cues.count { it == Cue.LISTEN })
        assertTrue(rig.log.lines.contains("[Turn] resumed after end (+600 ms) → cancelled tutor, corrector; tutor cost unknown (stream cut)"))
        assertTrue(rig.log.lines.any { it.startsWith("[Network] corrector: cancelled (student went on)") })
        assertEquals(1, rig.timing.size)
        assertEquals(1, level.state.value.turns) // одна реплика в окне уровня (D56)
        assertFalse(rig.log.lines.any { "read books" in it })
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
    }

    @Test
    fun `button end - no continuation, no cancel`() = runTest {
        val rig = Rig(this, "I like tea")
        rig.input.endings += TurnEnd.BUTTON
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(0, rig.input.continuations)
        assertTrue(rig.timing.single().endsWith("ended_by=button"))
    }

    @Test
    fun `long silence end can be cancelled too and the log shows the limit`() = runTest {
        val rig = Rig(this, "My favorite food is", "pizza")
        rig.input.endings += TurnEnd.LONG_SILENCE
        rig.input.endWaitMs = 8000
        rig.input.resumes += 300L
        rig.controller.startTurn()
        advanceUntilIdle()

        assertTrue(rig.log.lines.contains("[Turn] pause 8000 ms: limit → end"))
        assertEquals("My favorite food is pizza", rig.tutor.replies.last())
    }

    @Test
    fun `live session is released with the service`() = runTest {
        val live = FakeLive()
        val rig = Rig(this, live = live)
        rig.controller.release()
        assertTrue(live.released)
    }
    // ---- перебивание и команды голосом (S05) -------------------------------------------------

    private val longReply = "Yesterday I went to the market. I bought apples and bread. Then I walked home. Do you like apples?"

    @Test
    fun `silent listening - the mic hears the teacher, no barge-in, the leak goes to the log`() = runTest {
        val rig = Rig(this, "I am fine", bargeIn = true)
        rig.output.playMs = 1_000
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("Hello there.", "How are you?"), rig.output.played)
        assertEquals(1, rig.input.listens)
        assertEquals(1, rig.input.listensCancelled) // учитель договорил — микрофон закрыт
        val mic = rig.log.lines.single { it.startsWith("[Audio] mic during teacher: ") }
        assertTrue(mic, Regex("""mic during teacher: \d+ ms, max p 0.05, speech frames 0 \(p ≥ 0.5\), longest speech 0 ms""").matches(mic.removePrefix("[Audio] ")))
        assertTrue(rig.log.lines.none { "barge-in" in it })
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
    }

    @Test
    fun `barge-in - the teacher stops, the rest is cancelled, history keeps the spoken part, the student's turn is new`() = runTest {
        val level = StudentLevel(MemoryLevelStore())
        val rig = Rig(this, "I went shopping", "What is a market", bargeIn = true, level = level)
        rig.tutor.replyText = longReply
        rig.output.playMs = 1_000
        rig.controller.startTurn()
        runCurrent()
        // первый звук ≈ 1500 мс; ученик заговорил во второй группе
        rig.vad!!.speakFrom = 2_700
        rig.vad.speakUntil = 3_200
        advanceUntilIdle()

        val firstReplyGroups = SpeechPlanner().plan(longReply).map { it.text }
        // прозвучали первые группы, остальные нет; второй ответ — целиком
        val info = "played ${rig.output.played}, groups $firstReplyGroups\n" + rig.log.lines.joinToString("\n")
        // ученик заговорил в паузе после первой группы: из первого ответа прозвучала одна группа, второй — целиком
        val spoken = firstReplyGroups.take(1)
        assertEquals(info, spoken + firstReplyGroups, rig.output.played)
        assertTrue(info, rig.output.stops >= 1)
        assertEquals(listOf("I went shopping", "What is a market"), rig.tutor.replies)
        val (student, history) = rig.tutor.interrupted.single()
        assertEquals("I went shopping", student)
        assertTrue(history, history.endsWith(" ${BargeIn.INTERRUPTED_MARK}"))
        assertEquals(spoken.joinToString(" "), BargeIn.unmarked(history))
        // без «говори» перед второй репликой; «обрабатываю» — на каждую
        assertEquals(1, rig.output.cues.count { it == Cue.LISTEN })
        assertEquals(2, rig.output.cues.count { it == Cue.PROCESSING })
        val line = rig.log.lines.single { it.startsWith("[Turn] barge-in at +") }
        assertTrue(info, Regex("""\[Turn] barge-in at \+\d+ ms of teacher audio \(p 0.95, speech 320 ms\) → stopped, spoken ${spoken.size} of \d+ groups""").matches(line))
        assertTrue(info, rig.log.lines.any { it.startsWith("[Audio] mic during teacher: ") && "speech frames 10" in it })
        assertEquals(info, 2, rig.timing.size) // замер прерванного ответа тоже в журнале
        assertEquals(info, 2, level.state.value.turns) // ход ученика — новый, окно уровня как обычно
        assertEquals(listOf("REPLY_FINISHED"), rig.circleEnds)
    }

    @Test
    fun `short noise does not barge in - the rule needs 300 ms in a row`() = runTest {
        val rig = Rig(this, "I am fine", bargeIn = true)
        rig.tutor.replyText = longReply
        rig.output.playMs = 1_000
        rig.controller.startTurn()
        runCurrent()
        rig.vad!!.speakFrom = 2_700
        rig.vad.speakUntil = 2_700 + 250 // 8 кадров — мало
        advanceUntilIdle()

        assertEquals(SpeechPlanner().plan(longReply).map { it.text }, rig.output.played)
        assertTrue(rig.log.lines.none { "barge-in" in it })
        assertTrue(rig.log.lines.any { "mic during teacher" in it && "longest speech 256 ms" in it })
    }

    @Test
    fun `barge-in during the correction - only the correction was heard`() = runTest {
        val rig = Rig(this, "I go shopping yesterday", "Yes", corrector = FakeCorrector("I went shopping yesterday.", 300), bargeIn = true)
        rig.output.playMs = 2_000
        rig.controller.startTurn()
        runCurrent()
        rig.vad!!.speakFrom = 1_600
        rig.vad.speakUntil = 2_100
        advanceUntilIdle()

        val heard = BargeIn.unmarked(rig.tutor.interrupted.single().second)
        assertTrue(heard, heard.startsWith("I think you meant") && "Hello" !in heard)
        assertTrue(rig.output.played.first().startsWith("I think you meant"))
        assertEquals(listOf("I go shopping yesterday", "Yes"), rig.tutor.replies)
    }

    @Test
    fun `barge-in during the search filler - nothing of the reply was heard, no reply in history`() = runTest {
        val rig = Rig(this, "Weather in Tucson", "Never mind", filler = "Let me check.", bargeIn = true)
        rig.tutor.searchMs = 3_000
        rig.output.playMs = 1_000
        rig.controller.startTurn()
        advanceTimeBy(1_000) // фраза готова заранее
        rig.vad!!.speakFrom = 1_700
        rig.vad.speakUntil = 2_200
        advanceUntilIdle()

        assertEquals("Let me check.", rig.output.played.first())
        assertEquals("Weather in Tucson" to "", rig.tutor.interrupted.single())
        assertTrue(rig.log.lines.any { it.startsWith("[Turn] barge-in at +") && "spoken 0 of 0 groups" in it })
        assertEquals(listOf("Weather in Tucson", "Never mind"), rig.tutor.replies)
    }

    @Test
    fun `command again - the last reply, the same sound, nothing goes to the teacher or the corrector`() = runTest {
        val corrector = FakeCorrector()
        val level = StudentLevel(MemoryLevelStore())
        val rig = Rig(this, "I am fine", "Again!", corrector = corrector, level = level, bargeIn = true)
        rig.controller.startTurn()
        advanceUntilIdle()
        val synthesized = rig.synth.texts.size
        rig.controller.startTurn()
        advanceUntilIdle()

        assertEquals(listOf("Hello there.", "How are you?", "Hello there.", "How are you?"), rig.output.played)
        assertEquals(synthesized, rig.synth.texts.size) // тот же звук, без нового синтеза
        assertEquals(listOf("I am fine"), rig.tutor.replies)
        assertEquals(listOf("I am fine"), corrector.calls)
        assertEquals(1, level.state.value.turns)
        assertTrue(rig.log.lines.contains("[Turn] command again (2 groups)"))
        assertEquals(listOf("REPLY_FINISHED", "REPLY_FINISHED"), rig.circleEnds) // ручной режим: как после ответа
        assertEquals(2, rig.input.listens) // и во время повтора микрофон слушает
    }

    @Test
    fun `command slower - synthesized again at the slower speed with longer pauses, once`() = runTest {
        val rig = Rig(this, "I am fine", "Slower, please.", "Thanks")
        rig.controller.setAutoMode(true)
        rig.controller.startTurn()
        advanceTimeBy(20_000)
        rig.controller.stop(EndReason.STOP_BUTTON)
        advanceUntilIdle()

        assertEquals(listOf("I am fine", "Thanks"), rig.tutor.replies)
        assertEquals(listOf(0.88f, 0.88f, 0.7f, 0.7f, 0.88f, 0.88f), rig.synth.speeds.take(6))
        assertEquals(2, rig.synth.speeds.count { it == 0.7f })
        val t = rig.output.startTimes
        assertEquals(1_000L, t[1] - t[0]) // пауза после «Hello there.» — 1000 мс
        assertEquals(1_500L, t[3] - t[2]) // медленнее: × 1,5
        assertEquals(1_000L, t[5] - t[4]) // дальше — обычная скорость
        assertTrue(rig.log.lines.contains("[Turn] command slower (2 groups)"))
    }

    @Test
    fun `command stop - nothing to the teacher, listening again even in manual mode`() = runTest {
        val rig = Rig(this, "Stop.")
        rig.input.speech += listOf(true, false)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertTrue(rig.tutor.replies.isEmpty())
        assertEquals(2, rig.output.cues.count { it == Cue.LISTEN })
        assertTrue(rig.log.lines.contains("[Turn] command stop (0 groups)"))
        assertEquals(listOf("SILENCE_MANUAL"), rig.circleEnds)
    }

    @Test
    fun `command again before any reply - nothing to repeat, listening`() = runTest {
        val rig = Rig(this, "Repeat")
        rig.input.speech += listOf(true, false)
        rig.controller.startTurn()
        advanceUntilIdle()

        assertTrue(rig.tutor.replies.isEmpty())
        assertTrue(rig.log.lines.contains("[Turn] command again: nothing to repeat yet → listening"))
        assertEquals(2, rig.output.cues.count { it == Cue.LISTEN })
    }

    @Test
    fun `barge-in with again - the whole interrupted reply is repeated, unheard groups are synthesized`() = runTest {
        val rig = Rig(this, "I went shopping", "Again", bargeIn = true)
        rig.tutor.replyText = longReply
        rig.output.playMs = 1_000
        rig.controller.startTurn()
        runCurrent()
        rig.vad!!.speakFrom = 1_600
        rig.vad.speakUntil = 2_100
        advanceUntilIdle()

        val groups = SpeechPlanner().plan(longReply).map { it.text }
        assertEquals(groups, rig.output.played.drop(rig.output.played.size - groups.size))
        assertEquals(listOf("I went shopping"), rig.tutor.replies)
        assertTrue(rig.log.lines.contains("[Turn] command again (${groups.size} groups)"))
    }

    @Test
    fun `stop button during listening for barge-in ends everything`() = runTest {
        val rig = Rig(this, "I am fine", bargeIn = true)
        rig.output.playMs = 5_000
        rig.controller.startTurn()
        advanceTimeBy(2_000)
        rig.controller.stop(EndReason.STOP_BUTTON)
        advanceUntilIdle()

        assertEquals(1, rig.input.listensCancelled)
        assertEquals(listOf("STOP_BUTTON"), rig.circleEnds)
    }
}

/** Детектор не нужен: поддельный микрофон сам решает, была ли речь. Контекст (S04) запоминается для подделки. */
private class CtxDetector : TurnDetector {
    var ctx = TurnContext()
    override fun setContext(context: TurnContext) { ctx = context }
    override fun reset() {}
    override fun onAudio(pcm: ShortArray, count: Int): TurnEnd? = null
    override val heardSpeech = false
    override val stats = TurnStats()
}
