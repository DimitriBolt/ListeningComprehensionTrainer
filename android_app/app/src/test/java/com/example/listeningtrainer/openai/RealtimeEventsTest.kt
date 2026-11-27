package com.example.listeningtrainer.openai

import com.example.listeningtrainer.openai.RealtimeEvents.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeEventsTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `session - audio only, pcm 24 kHz, verse, 0_88, prompt 1, no VAD`() {
        val s = json(RealtimeEvents.sessionUpdate()).obj("session")
        assertEquals("realtime", s.text("type"))
        assertEquals(listOf("audio"), s.getValue("output_modalities").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(s.text("instructions").startsWith("You are a text-to-speech engine, not an assistant."))
        val out = s.obj("audio").obj("output")
        assertEquals("verse", out.text("voice"))
        assertEquals("0.88", out.text("speed"))
        assertEquals("audio/pcm", out.obj("format").text("type"))
        assertEquals("24000", out.obj("format").text("rate"))
        assertEquals("null", s.obj("audio").obj("input").getValue("turn_detection").toString())
    }

    @Test
    fun `session - Slower changes only the speed (S05)`() {
        val out = json(RealtimeEvents.sessionUpdate(0.7f)).obj("session").obj("audio").obj("output")
        assertEquals("0.7", out.text("speed"))
        assertEquals("verse", out.text("voice"))
    }

    @Test
    fun `group - out of band response, only this text, only audio`() {
        val r = json(RealtimeEvents.responseCreate("Tell me \"more\".", "g7")).obj("response")
        assertEquals("none", r.text("conversation"))
        val content = r.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray.single().jsonObject
        assertEquals("input_text", content.text("type"))
        assertEquals("Tell me \"more\".", content.text("text"))
        assertEquals("g7", r.obj("metadata").text("group"))
    }

    private fun delta(bytes: ByteArray) = json("""{"type":"response.output_audio.delta","delta":"${bytes.toByteString().base64()}"}""")

    private val done = json(
        """{"type":"response.done","response":{"status":"completed","metadata":{"group":"g1"},
           "usage":{"input_token_details":{"text_tokens":84,"audio_tokens":0,"cached_tokens":64},
                    "output_token_details":{"text_tokens":44,"audio_tokens":50}}}}""",
    )

    @Test
    fun `collects audio, transcript and usage`() {
        val c = RealtimeEvents.ResponseCollector("g1")
        assertNull(c.onEvent(json("""{"type":"response.created"}""")))
        assertNull(c.onEvent(delta(byteArrayOf(1, 0, -1, -1))))
        assertNull(c.onEvent(delta(byteArrayOf(0, 1))))
        assertNull(c.onEvent(json("""{"type":"response.output_audio_transcript.done","transcript":"Tell me more."}""")))
        val result = c.onEvent(done) as Outcome.Done
        assertArrayEquals(shortArrayOf(1, -1, 256), result.pcm)
        assertEquals("Tell me more.", result.transcript)
        assertEquals("tokens in text 84 (cached 64) audio 0, out text 44 audio 50", result.usage.toString())
    }

    @Test
    fun `end of an old response is ignored`() {
        val c = RealtimeEvents.ResponseCollector("g2")
        c.onEvent(delta(byteArrayOf(1, 0)))
        assertNull(c.onEvent(done)) // metadata.group = g1
    }

    @Test
    fun `rate limit or quota - unavailable, server error - only this group`() {
        val limited = RealtimeEvents.ResponseCollector("g1").onEvent(json(
            """{"type":"response.done","response":{"status":"failed","metadata":{"group":"g1"},
               "status_details":{"error":{"type":"rate_limit_error","code":"rate_limit_exceeded"}}}}""",
        )) as Outcome.Failed
        assertTrue(limited.unavailable)
        assertEquals("status failed (rate_limit_exceeded)", limited.reason)

        val server = RealtimeEvents.ResponseCollector("g1").onEvent(json(
            """{"type":"error","error":{"type":"server_error","code":null,"message":"text of the teacher"}}""",
        )) as Outcome.Failed
        assertFalse(server.unavailable)
        assertEquals("error server_error/-", server.reason) // сообщение сервера в журнал не идёт
    }

    @Test
    fun `completed without audio is a failure`() {
        val r = RealtimeEvents.ResponseCollector("g1").onEvent(done) as Outcome.Failed
        assertFalse(r.unavailable)
    }

    @Test
    fun `pcm is little-endian 16 bit`() {
        assertArrayEquals(shortArrayOf(0x0201, -2), RealtimeEvents.pcm16(byteArrayOf(1, 2, -2, -1)))
    }
}
