package com.example.listeningtrainer.endpoint

import com.example.listeningtrainer.conversation.AudioInput
import com.example.listeningtrainer.conversation.AudioSink
import com.example.listeningtrainer.conversation.AudioOutput
import com.example.listeningtrainer.conversation.Cue
import com.example.listeningtrainer.conversation.RecordedAudio
import com.example.listeningtrainer.conversation.SpeechAudio
import com.example.listeningtrainer.conversation.SpeechProbability
import com.example.listeningtrainer.conversation.TurnDetector
import com.example.listeningtrainer.conversation.TurnEnd
import com.example.listeningtrainer.conversation.VadTurnDetector
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EndpointScenarioTest {

    @Test
    fun `12 lines - 4 short, 4 with a pause, 2 long, 2 without speech - texts from the brief`() {
        val lines = EndpointScenario.lines
        assertEquals((1..12).toList(), lines.map { it.number })
        val kinds = lines.groupingBy { it.kind }.eachCount()
        assertEquals(4, kinds[EndpointScenario.Kind.SHORT])
        assertEquals(4, kinds[EndpointScenario.Kind.PAUSE])
        assertEquals(2, kinds[EndpointScenario.Kind.LONG])
        assertEquals(2, kinds[EndpointScenario.Kind.NO_SPEECH])
        assertEquals(listOf("At noon.", "Yes, I do.", "My dog is five.", "I like to read books in the evening."), lines.take(4).map { it.text })
        assertEquals("I wanted to tell you about … my trip to the mountains.", lines[4].text)
        assertTrue(lines.filter { it.kind == EndpointScenario.Kind.PAUSE }.all { "…" in it.text })
        assertTrue(lines.filter { it.kind == EndpointScenario.Kind.LONG }.all { "um…" in it.text.lowercase() })
        assertTrue(lines.filter { it.kind == EndpointScenario.Kind.NO_SPEECH }.all { it.text.isEmpty() && it.shownText == "(say nothing)" })
    }

    @Test
    fun `four conditions with file-safe ids`() {
        assertEquals(listOf("silence", "water", "street", "steady_noise"), EndpointScenario.Condition.values().map { it.id })
    }

    @Test
    fun `the mode exists only in a debug build`() {
        assertTrue(EndpointRecorder.available(isDebug = true))
        assertFalse(EndpointRecorder.available(isDebug = false))
    }
}

class RecordingStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun take(at: Long, condition: String = "water", line: Int = 5, takeNo: Int = 1) = EndpointTake(
        condition = condition, line = line, kind = "pause", text = "Yesterday I … went to the shop.", take = takeNo,
        startedAtMs = at, startedAtIso = "", sampleRate = 16_000, durationMs = 800, endedBy = "button",
        buttonReleaseMs = 750, buttonPressMs = 650, pulsesMs = listOf(2000), pulseDurationMs = 60,
        levelWindowMs = 80, levelDb = List(10) { 40f }, levelWallMs = List(10) { (it + 1) * 80L },
        silenceDetector = DetectorEmulation(59f, 3000, null, false),
    )

    private fun wav(): File = tmp.newFile().apply { writeBytes(ByteArray(44 + 1600)) }

    @Test
    fun `name - time with 13 digits, condition, line with 2 digits`() {
        val store = RecordingStore(tmp.root)
        assertEquals("ep_1764201600123_water_05", store.baseName(1_764_201_600_123L, "water", 5))
        assertEquals("ep_0000000000042_steady_noise_12", store.baseName(42L, "steady_noise", 12))
    }

    @Test
    fun `save puts wav and json side by side, takes and recorded lines are counted`() {
        val dir = File(tmp.root, "recordings")
        val store = RecordingStore(dir)
        val (base, deleted) = store.save(take(1_764_201_600_000L), wav())
        assertEquals(0, deleted)
        assertTrue(File(dir, "$base.wav").length() == 44L + 1600)
        val json = EndpointTake.fromJson(File(dir, "$base.json").readText())
        assertEquals("water", json.condition)
        assertEquals(5, json.line)
        store.save(take(1_764_201_700_000L, takeNo = 2), wav())
        store.save(take(1_764_201_800_000L, line = 6), wav())
        store.save(take(1_764_201_900_000L, condition = "street", line = 5), wav())
        assertEquals(2, store.takes("water", 5))
        assertEquals(1, store.takes("street", 5))
        assertEquals(0, store.takes("water", 7))
        assertEquals(setOf(5, 6), store.recordedLines("water"))
        assertEquals(setOf(5), store.recordedLines("street"))
    }

    @Test
    fun `only the newest 200 recordings are kept, wav and json are deleted together`() {
        val store = RecordingStore(tmp.root, keep = 200)
        val start = 1_764_201_600_000L
        for (i in 0 until 205) store.save(take(start + i * 1000L, line = i % 12 + 1), wav())
        val wavs = tmp.root.listFiles()!!.filter { it.name.endsWith(".wav") && it.name.startsWith("ep_") }
        val jsons = tmp.root.listFiles()!!.filter { it.name.endsWith(".json") }
        assertEquals(200, wavs.size)
        assertEquals(200, jsons.size)
        val oldest = wavs.minOf { it.name }
        assertEquals(store.baseName(start + 5_000L, "water", 6) + ".wav", oldest)
    }

    @Test
    fun `old recordings - everything but the newest`() {
        val names = (1L..5L).map { "ep_${1_764_201_600_000L + it}_water_01" }.shuffled()
        assertEquals(listOf("ep_1764201600002_water_01", "ep_1764201600001_water_01"), RecordingStore.oldRecordings(names, 3))
        assertEquals(emptyList<String>(), RecordingStore.oldRecordings(names, 5))
    }
}

class EndpointTakeTest {
    @Test
    fun `json keeps every field the probe needs`() {
        val take = EndpointTake(
            condition = "street", line = 9, kind = "long", text = "Yesterday I got up early.", take = 3,
            startedAtMs = 1_764_201_600_000L, startedAtIso = "2026-11-26T15:00:00.000-07:00", sampleRate = 16_000,
            durationMs = 9_040, endedBy = "button", buttonReleaseMs = 9_000, buttonPressMs = 8_880,
            pulsesMs = listOf(2_010, 4_005), pulseDurationMs = 60, levelWindowMs = 80,
            levelDb = listOf(41.5f, 72.25f), levelWallMs = listOf(300, 310),
            silenceDetector = DetectorEmulation(59f, 3000, 6_400, true),
            sileroDetector = SileroEmulation(0.5f, 3000, 200, 5_024, true, 1_536, 282, 0.84, 3.1), device = "samsung SM-X",
        )
        val text = take.toJson()
        for (key in listOf(
            "\"schema\"", "\"condition\"", "\"line\"", "\"kind\"", "\"text\"", "\"take\"", "\"startedAtMs\"", "\"sampleRate\"",
            "\"durationMs\"", "\"endedBy\"", "\"buttonReleaseMs\"", "\"buttonPressMs\"", "\"pulsesMs\"", "\"sileroDetector\"",
            "\"levelWindowMs\"", "\"levelDb\"", "\"levelWallMs\"", "\"silenceDetector\"",
        )) assertTrue(key, key in text)
        assertEquals(take, EndpointTake.fromJson(text))
    }

    @Test
    fun `no button - nulls in json`() {
        val take = EndpointTake(
            condition = "silence", line = 11, kind = "no_speech", text = "", take = 1, startedAtMs = 1, startedAtIso = "",
            sampleRate = 16_000, durationMs = 90_000, endedBy = "limit", buttonReleaseMs = null, buttonPressMs = null,
            pulsesMs = emptyList(), pulseDurationMs = 60, levelWindowMs = 80, levelDb = emptyList(),
            levelWallMs = emptyList(), silenceDetector = DetectorEmulation(59f, 3000, 3080, false),
        )
        val back = EndpointTake.fromJson(take.toJson())
        assertNull(back.buttonReleaseMs)
        assertEquals("limit", back.endedBy)
        assertNull(back.sileroDetector)
    }
}

/** «Silero» для тестов: речь — кадр, где первый отсчёт громче 1000. */
internal class LoudFrameVad : SpeechProbability {
    override val frameSamples = 512
    var resets = 0
    override fun reset() { resets++ }
    override fun probability(frame: ShortArray) = if (frame[0] > 1000) 0.9f else 0.1f
}

class LevelTraceTest {
    private val window = ShortArray(1280) // 80 мс при 16 кГц

    private fun feed(trace: LevelTrace, loud: Boolean): TurnEnd? {
        window.fill(if (loud) 3000 else 10)
        trace.onLevel(if (loud) 70f else 40f)
        return trace.onAudio(window, window.size)
    }

    @Test
    fun `never ends the recording, but remembers where the 59 dB and the Silero detectors would end it`() {
        var now = 10_000L
        val trace = LevelTrace(windowMs = 80, silero = VadTurnDetector(LoudFrameVad()), clock = { now })
        trace.reset()
        // 1 с тишины, 1 с речи, потом тишина 5 с
        val loud = List(12) { false } + List(13) { true } + List(63) { false }
        loud.forEachIndexed { i, l ->
            now = 10_000L + 500 + i * 80L
            assertNull(feed(trace, l))
        }
        assertTrue(trace.heardSpeech)
        // последнее громкое окно кончается на 25·80 = 2000 мс; конец — первое окно, где тишина > 3000 мс
        assertEquals(5_040L, trace.emulatedEndMs)
        assertEquals(loud.size, trace.levels.size)
        assertEquals(500L, trace.wallMs.first())
        assertEquals(DetectorEmulation(59f, 3000, 5_040L, true), trace.emulation())
        // Silero: речь — кадры 960…2000 мс (последний кончается на 2016), 3000 мс тишины — конец в 5024 мс
        val silero = trace.sileroEmulation()
        assertEquals(5_024L, silero.endMs)
        assertTrue(silero.heardSpeech)
        assertEquals(32L * 33, silero.speechMs)
        assertEquals(loud.size * 80 / 32, silero.frames)
    }

    @Test
    fun `silence - no speech, the old detector would end after 3 s, Silero after 8 s (D70)`() {
        val trace = LevelTrace(windowMs = 80, silero = VadTurnDetector(LoudFrameVad()))
        trace.reset()
        repeat(110) { feed(trace, false) }
        assertFalse(trace.heardSpeech)
        assertEquals(3_040L, trace.emulatedEndMs)
        assertEquals(8_000L, trace.sileroEmulation().endMs)
        assertEquals(8_000L, trace.sileroEmulation().startSilenceMs)
        assertFalse(trace.sileroEmulation().heardSpeech)
    }

    @Test
    fun `noise above the threshold - the old detector would never end`() {
        val trace = LevelTrace(windowMs = 80, silero = VadTurnDetector(LoudFrameVad()))
        trace.reset()
        repeat(500) {
            trace.onLevel(62f)
            trace.onAudio(ShortArray(1280), 1280)
        }
        assertNull(trace.emulatedEndMs)
    }

    @Test
    fun `the 90 s limit ends the recording`() {
        val trace = LevelTrace(windowMs = 80, silero = VadTurnDetector(LoudFrameVad()))
        trace.reset()
        var end: TurnEnd? = null
        var windows = 0
        while (end == null && windows < 2_000) {
            end = feed(trace, true)
            windows++
        }
        assertEquals(TurnEnd.LIMIT, end)
        // 1125 окон = 2812,5 кадра = 89 984 мс; предел — на кадре 2813 (90 016 мс), он во 1126-м окне
        assertEquals(1_126, windows)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EndpointRecorderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Микрофон: окно 80 мс на шаг виртуального времени, громкость по времени звука; ждёт finish() или 90 с. */
    private class FakeMic(private val clock: () -> Long, private val tmp: TemporaryFolder, private val level: (Long) -> Float) : AudioInput {
        @Volatile var finished = false
        var records = 0
        override suspend fun record(turnDetector: TurnDetector, sink: AudioSink?, onLevel: (Float) -> Unit): RecordedAudio {
            records++
            finished = false
            turnDetector.reset()
            var audioMs = 0L
            val window = ShortArray(1280)
            while (!finished) {
                delay(80)
                audioMs += 80
                val db = level(audioMs)
                window.fill(if (db > 60f) 3000 else 10)
                onLevel(db)
                if (turnDetector.onAudio(window, window.size) != null) break
            }
            val file = tmp.newFile().apply { writeBytes(ByteArray(44 + (audioMs * 32).toInt())) }
            return RecordedAudio(file, durationMs = audioMs, heardSpeech = turnDetector.heardSpeech, peakDb = 0f)
        }

        override fun finish() { finished = true }
    }

    private class FakeOut(private val clock: () -> Long) : AudioOutput {
        val cues = mutableListOf<Cue>()
        var pulseAllowed = true
        var onPulse: (Long) -> Unit = {}
        override fun prepareReply() = 0L
        override suspend fun play(audio: SpeechAudio, shieldMs: Long, onStarted: () -> Unit) {}
        override fun listenPulseAllowed() = pulseAllowed
        override fun playCue(cue: Cue) {
            cues += cue
            if (cue == Cue.PULSE) onPulse(clock() + 15) // звук реально пошёл чуть позже
        }
        override suspend fun awaitCues() {}
        override fun stop() {}
        override fun release() {}
    }

    private class Log : EventLog {
        val lines = mutableListOf<String>()
        override fun event(source: String, message: String) { lines += "[$source] $message" }
        override fun error(source: String, message: String, error: Throwable?) { lines += "[$source] ERROR $message" }
    }

    private class Setup(scope: TestScope, tmp: TemporaryFolder, level: (Long) -> Float) {
        val clock = { scope.testScheduler.currentTime + 1_764_201_600_000L }
        val mic = FakeMic(clock, tmp, level)
        val out = FakeOut(clock)
        val log = Log()
        val dir = File(tmp.root, "recordings")
        val store = RecordingStore(dir)
        val rec = EndpointRecorder(
            scope, mic, out, store, LoudFrameVad(), clock, log, io = StandardTestDispatcher(scope.testScheduler),
        ).also { r -> out.onPulse = r::onPulseStarted }

        fun takes(): List<EndpointTake> = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }
            .sortedBy { it.name }.map { EndpointTake.fromJson(it.readText()) }
    }

    private val speechUntil2s: (Long) -> Float = { ms -> if (ms in 500..2000) 72f else 45f }

    @Test
    fun `headset button starts and finishes a take - wav, json with pulses, button and levels, next line`() = runTest {
        val s = Setup(this, tmp, speechUntil2s)
        s.rec.open()
        s.rec.chooseCondition(EndpointScenario.Condition.WATER)
        assertEquals(0, s.rec.ui.value.lineIndex)
        assertTrue(s.rec.headsetButton(KEY_PLAY_PAUSE, 100)) // Talk
        runCurrent()
        assertTrue(s.rec.ui.value.recording)
        advanceTimeBy(5_900)
        assertTrue(s.rec.headsetButton(KEY_PLAY_PAUSE, 120)) // finish
        advanceUntilIdle()

        val take = s.takes().single()
        assertEquals("water", take.condition)
        assertEquals(1, take.line)
        assertEquals("short", take.kind)
        assertEquals("At noon.", take.text)
        assertEquals(1, take.take)
        assertEquals("button", take.endedBy)
        assertEquals(5_900L, take.buttonReleaseMs)
        assertEquals(5_780L, take.buttonPressMs)
        assertEquals(listOf(2_015L, 4_015L), take.pulsesMs) // от начала записи, как запустил проигрыватель
        assertEquals(80, take.levelWindowMs)
        assertEquals(take.durationMs / 80, take.levelDb.size.toLong())
        assertEquals(72f, take.levelDb[10])
        assertEquals(5_040L, take.silenceDetector.endMs) // эмуляция 59 дБ / 3 с — запись она не остановила
        assertEquals(5_024L, take.sileroDetector?.endMs) // и Silero с настройками приложения — тоже не остановил
        assertEquals(2, take.schema)
        assertTrue(take.durationMs >= 5_900)
        assertTrue(File(s.dir, s.store.baseName(take.startedAtMs, "water", 1) + ".wav").exists())

        assertEquals(listOf(Cue.LISTEN, Cue.PULSE, Cue.PULSE, Cue.PROCESSING), s.out.cues)
        val ui = s.rec.ui.value
        assertFalse(ui.recording)
        assertEquals(1, ui.lineIndex) // следующая реплика
        assertEquals(setOf(1), ui.recorded)
        assertTrue(s.log.lines.any { it.startsWith("[Debug] endpoint recording saved: ep_") && "ended by button" in it && "pulses 2" in it && "silero end 5024 ms" in it })
        assertFalse(s.log.lines.any { "At noon" in it }) // текст реплики в журнал не попадает
    }

    @Test
    fun `the detector never stops the recording - only the 90 s limit`() = runTest {
        val s = Setup(this, tmp) { 45f }
        s.rec.open()
        s.rec.chooseCondition(EndpointScenario.Condition.SILENCE)
        s.rec.start()
        advanceUntilIdle()
        val take = s.takes().single()
        assertEquals(90_080L, take.durationMs) // предел — в детекторе, на кадре 2813 (окно 1126)
        assertEquals("limit", take.endedBy)
        assertNull(take.buttonReleaseMs)
        assertTrue(take.pulsesMs.size in 44..45)
        assertEquals(3_040L, take.silenceDetector.endMs)
    }

    @Test
    fun `a second take of the same line is numbered, the screen can go back to a line`() = runTest {
        val s = Setup(this, tmp, speechUntil2s)
        s.rec.open()
        s.rec.chooseCondition(EndpointScenario.Condition.STREET)
        s.rec.start(); advanceTimeBy(3_000); s.rec.finish("screen"); advanceUntilIdle()
        s.rec.selectLine(0)
        assertEquals(1, s.rec.ui.value.takes)
        s.rec.start(); advanceTimeBy(3_000); s.rec.finish("screen"); advanceUntilIdle()
        val takes = s.takes()
        assertEquals(listOf(1, 2), takes.map { it.take })
        assertEquals(listOf("screen", "screen"), takes.map { it.endedBy })
        assertEquals(0L, takes[0].buttonReleaseMs!! - takes[0].buttonPressMs!!)
    }

    @Test
    fun `choosing a condition again opens the first line without a recording`() = runTest {
        val s = Setup(this, tmp, speechUntil2s)
        s.rec.open()
        s.rec.chooseCondition(EndpointScenario.Condition.WATER)
        s.rec.start(); advanceTimeBy(3_000); s.rec.finish(); advanceUntilIdle()
        s.rec.backToConditions()
        assertNull(s.rec.ui.value.condition)
        s.rec.chooseCondition(EndpointScenario.Condition.WATER)
        assertEquals(1, s.rec.ui.value.lineIndex)
        assertEquals(setOf(1), s.rec.ui.value.recorded)
    }

    @Test
    fun `close during a recording - nothing is saved, the mode is off`() = runTest {
        val s = Setup(this, tmp, speechUntil2s)
        s.rec.open()
        s.rec.chooseCondition(EndpointScenario.Condition.WATER)
        s.rec.start()
        advanceTimeBy(1_000)
        s.rec.close()
        advanceUntilIdle()
        assertEquals(emptyList<EndpointTake>(), s.takes())
        assertFalse(s.rec.active)
    }

    @Test
    fun `before a condition is chosen the headset button does nothing, on the speaker there are no pulses`() = runTest {
        val s = Setup(this, tmp, speechUntil2s)
        s.out.pulseAllowed = false
        s.rec.open()
        s.rec.headsetButton(KEY_PLAY_PAUSE, 100)
        runCurrent()
        assertEquals(0, s.mic.records)
        advanceTimeBy(600) // дребезг 500 мс
        s.rec.chooseCondition(EndpointScenario.Condition.SILENCE)
        s.rec.headsetButton(KEY_PLAY_PAUSE, 100)
        advanceTimeBy(4_000)
        s.rec.headsetButton(KEY_PLAY_PAUSE, 100)
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), s.takes().single().pulsesMs)
        assertFalse(Cue.PULSE in s.out.cues)
    }

    private companion object {
        const val KEY_PLAY_PAUSE = 85 // KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    }
}
