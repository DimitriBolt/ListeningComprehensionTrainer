package com.example.listeningtrainer.openai

import com.example.listeningtrainer.conversation.LiveResult
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.ByteString.Companion.decodeBase64

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeTranscriberTest {

    /** Подделка сокета: что отправлено, и события сервера по команде теста. */
    private class FakeSocket(val listener: LiveSocketListener) : LiveSocket {
        val sent = mutableListOf<String>()
        var open = true
        var cancelled = false
        override fun send(text: String): Boolean {
            if (!open) return false
            sent += text
            return true
        }
        override fun cancel() {
            cancelled = true
            open = false
        }
        fun server(json: String) = listener.onMessage(json)
        fun drop(reason: String) {
            open = false
            listener.onClosed(reason)
        }
        val types get() = sent.map { Regex("\"type\":\"([^\"]+)\"").find(it)!!.groupValues[1] }
    }

    private class Rig(scope: TestScope) {
        val sockets = mutableListOf<FakeSocket>()
        val log = mutableListOf<String>()
        val partials = mutableListOf<String>()
        val t = RealtimeTranscriber(
            sockets = { l -> FakeSocket(l).also { sockets += it } },
            log = object : EventLog {
                override fun event(source: String, message: String) { log += message }
                override fun error(source: String, message: String, error: Throwable?) { log += message }
            },
            clock = { scope.testScheduler.currentTime },
            textTimeoutMs = 2_500,
            renewMs = 50 * 60_000L,
        )
        val s get() = sockets.last()
        fun created() = s.server("""{"type":"session.created","session":{}}""")
        fun delta(item: String, text: String) =
            s.server("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"$item","delta":"$text"}""")
        fun committed(item: String) = s.server("""{"type":"input_audio_buffer.committed","item_id":"$item"}""")
        fun completed(item: String, text: String, seconds: Int = 6) = s.server(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"$item","transcript":"$text","usage":{"type":"duration","seconds":$seconds}}""",
        )
    }

    private val piece = ShortArray(1280) // 80 мс записи

    @Test
    fun `one turn - prompt first, audio while connecting, commit, text with stats`() = runTest {
        val r = Rig(this)
        r.t.prepare()
        val turn = r.t.begin("CONTEXT") { r.partials += it }
        turn.onPcm(piece, piece.size) // сессия ещё открывается: звук ждёт в очереди сокета
        advanceTimeBy(300)
        r.created()
        repeat(9) { turn.onPcm(piece, piece.size) }
        r.delta("i1", " I")
        r.delta("i1", " like tea.")
        turn.onEnd(heardSpeech = true)
        val result = async { turn.result() }
        advanceTimeBy(450)
        r.committed("i1")
        r.completed("i1", "I like tea.")
        runCurrent()

        assertEquals(listOf("session.update") + List(10) { "input_audio_buffer.append" } + "input_audio_buffer.commit", r.s.types)
        assertTrue("\"prompt\":\"CONTEXT\"" in r.s.sent[0])
        assertTrue("\"model\":\"gpt-live-transcribe\"" in r.s.sent[0])
        assertTrue("\"delay\":\"low\"" in r.s.sent[0])
        assertTrue("\"turn_detection\":null" in r.s.sent[0])
        assertTrue("\"rate\":24000" in r.s.sent[0])
        assertEquals(listOf("I", "I like tea."), r.partials)
        val text = result.await() as LiveResult.Text
        assertEquals("I like tea.", text.text)
        val st = text.stats
        assertEquals(300, st.sessionWaitMs)
        assertFalse(st.reused)
        assertEquals(799, st.audioSentMs) // 10 × 80 мс, 24 кГц, без хвоста фильтра (≈ 10 отсчётов)
        assertEquals(450, st.commitToTextMs)
        assertEquals(2, st.deltas)
        assertEquals(11, st.chars)
        assertEquals(6 * 0.017 / 60, st.costUsd, 1e-9)
    }

    @Test
    fun `audio is 24 kHz - 80 ms of recording is about 1920 samples`() = runTest {
        val r = Rig(this)
        val turn = r.t.begin("p")
        r.created()
        repeat(25) { turn.onPcm(piece, piece.size) }
        val bytes = r.s.sent.drop(1).sumOf { it.substringAfter("\"audio\":\"").substringBefore('"').decodeBase64()!!.size }
        // 2 с × 24 000 отсчётов × 2 байта; без хвоста фильтра (≈ 20 отсчётов)
        assertTrue("got $bytes", bytes in 96_000 - 80..96_000)
    }

    @Test
    fun `second turn - the same session, new prompt, late events of the old turn are ignored`() = runTest {
        val r = Rig(this)
        val first = r.t.begin("p1")
        r.created()
        first.onPcm(piece, piece.size)
        r.delta("i1", " Yes")
        first.onEnd(true)
        r.committed("i1")
        r.completed("i1", "Yes.")
        assertEquals("Yes.", (first.result() as LiveResult.Text).text)

        val second = r.t.begin("p2") { r.partials += it }
        r.delta("i1", " late") // старый item — не наш
        second.onPcm(piece, piece.size)
        r.delta("i2", " No")
        second.onEnd(true)
        r.completed("i1", "Yes.") // повтор старого
        r.committed("i2")
        r.completed("i2", "No.")
        val text = second.result() as LiveResult.Text
        assertEquals("No.", text.text)
        assertEquals(1, r.sockets.size)
        assertTrue(text.stats.reused)
        assertEquals(0, text.stats.sessionWaitMs)
        assertEquals(listOf("No"), r.partials)
        assertTrue("\"prompt\":\"p2\"" in r.s.sent.last { "session.update" in it })
    }

    @Test
    fun `continuation - a new turn starts right after commit, the old one still gets its text (S04)`() = runTest {
        val r = Rig(this)
        val p1 = mutableListOf<String>()
        val p2 = mutableListOf<String>()
        val first = r.t.begin("p1") { p1 += it }
        r.created()
        first.onPcm(piece, piece.size)
        r.delta("i1", " I like")
        first.onEnd(true)
        // конец объявлен — продолжение сразу пишет звук в ту же сессию
        val second = r.t.begin("p2") { p2 += it }
        second.onPcm(piece, piece.size)
        r.delta("i1", " coffee") // хвост дельт первого хода
        r.committed("i1")
        r.delta("i2", " and tea")
        r.completed("i1", "I like coffee.")
        assertEquals("I like coffee.", (first.result() as LiveResult.Text).text)
        assertEquals(listOf("I like", "I like coffee"), p1)
        assertEquals(listOf("and tea"), p2)

        second.onEnd(true)
        r.committed("i2")
        r.completed("i2", "And tea.")
        assertEquals("And tea.", (second.result() as LiveResult.Text).text)
        assertTrue(r.log.none { "replaced" in it })
    }

    @Test
    fun `a turn still recording is replaced by a new one`() = runTest {
        val r = Rig(this)
        val first = r.t.begin("p1")
        r.created()
        first.onPcm(piece, piece.size)
        r.t.begin("p2")
        assertEquals("replaced by a new turn", (first.result() as LiveResult.Failed).reason)
    }

    @Test
    fun `completed before committed is kept until committed names the item`() = runTest {
        val r = Rig(this)
        val turn = r.t.begin("p")
        r.created()
        turn.onPcm(piece, piece.size)
        turn.onEnd(true)
        r.completed("i9", "Hi.")
        r.committed("i9")
        assertEquals("Hi.", (turn.result() as LiveResult.Text).text)
    }

    @Test
    fun `no speech - clear, no commit, nothing to wait for`() = runTest {
        val r = Rig(this)
        val turn = r.t.begin("p")
        r.created()
        turn.onPcm(piece, piece.size)
        turn.onEnd(heardSpeech = false)
        assertEquals("input_audio_buffer.clear", r.s.types.last())
        assertFalse("input_audio_buffer.commit" in r.s.types)
        assertTrue(turn.result() is LiveResult.Failed)
        // сессия остаётся для следующей реплики
        r.t.begin("p2")
        assertEquals(1, r.sockets.size)
    }

    @Test
    fun `no text in time - failure, the session is closed, the next turn opens a new one`() = runTest {
        val r = Rig(this)
        val turn = r.t.begin("p")
        r.created()
        turn.onPcm(piece, piece.size)
        turn.onEnd(true)
        val result = async { turn.result() }
        advanceTimeBy(2_499)
        assertFalse(result.isCompleted)
        advanceTimeBy(2)
        assertEquals("no text in 2500 ms", (result.await() as LiveResult.Failed).reason)
        assertTrue(r.s.cancelled)
        r.t.begin("p2")
        assertEquals(2, r.sockets.size)
    }

    @Test
    fun `session never opened - failure names it`() = runTest {
        val r = Rig(this)
        val turn = r.t.begin("p")
        turn.onPcm(piece, piece.size)
        turn.onEnd(true)
        val result = async { turn.result() }
        advanceTimeBy(2_600)
        assertEquals("session not opened in 2500 ms", (result.await() as LiveResult.Failed).reason)
    }

    @Test
    fun `connection lost during the recording - failure at once, no more audio is sent`() = runTest {
        val r = Rig(this)
        val turn = r.t.begin("p")
        r.created()
        turn.onPcm(piece, piece.size)
        r.s.drop("network: SocketException")
        val before = r.s.sent.size
        turn.onPcm(piece, piece.size)
        turn.onEnd(true)
        assertEquals(before, r.s.sent.size)
        assertEquals("closed: network: SocketException", (turn.result() as LiveResult.Failed).reason)
        assertTrue(r.log.any { "live transcription session closed: network: SocketException" in it })
    }

    @Test
    fun `server error during the turn - failure, the session is closed`() = runTest {
        val r = Rig(this)
        val turn = r.t.begin("p")
        r.created()
        turn.onPcm(piece, piece.size)
        r.s.server("""{"type":"error","error":{"type":"invalid_request_error","code":"input_audio_buffer_commit_empty","message":"x"}}""")
        turn.onEnd(true)
        assertEquals("error invalid_request_error/input_audio_buffer_commit_empty", (turn.result() as LiveResult.Failed).reason)
        assertTrue(r.s.cancelled)
    }

    @Test
    fun `transcription failed event for our item - failure`() = runTest {
        val r = Rig(this)
        val turn = r.t.begin("p")
        r.created()
        turn.onPcm(piece, piece.size)
        turn.onEnd(true)
        r.committed("i1")
        r.s.server("""{"type":"conversation.item.input_audio_transcription.failed","item_id":"i1","error":{"type":"server_error","code":null}}""")
        assertEquals("transcription failed (server_error/-)", (turn.result() as LiveResult.Failed).reason)
    }

    @Test
    fun `cancel - the buffer is cleared, the session stays`() = runTest {
        val r = Rig(this)
        val turn = r.t.begin("p")
        r.created()
        turn.onPcm(piece, piece.size)
        turn.cancel()
        assertEquals("input_audio_buffer.clear", r.s.types.last())
        assertFalse(r.s.cancelled)
    }

    @Test
    fun `session renewal - after 50 min a new session, but never in the middle of a turn`() = runTest {
        val r = Rig(this)
        r.t.prepare()
        r.created()
        val turn = r.t.begin("p")
        advanceTimeBy(51 * 60_000L)
        r.t.prepare()
        assertEquals(1, r.sockets.size) // идёт реплика — не трогаем
        turn.onEnd(false)
        r.t.prepare()
        assertEquals(2, r.sockets.size)
        assertTrue(r.sockets[0].cancelled)
        assertTrue(r.log.any { "closed: session renewal" in it })
    }

    @Test
    fun `release closes the session`() = runTest {
        val r = Rig(this)
        r.t.prepare()
        r.t.release()
        assertTrue(r.s.cancelled)
    }
}
