package com.example.listeningtrainer.openai

import com.example.listeningtrainer.openai.LiveTranscriptionEvents.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTranscriptionEventsTest {
    @Test
    fun `session update - transcription session, 24 kHz, the model, languages, delay, prompt, no turn detection`() {
        val e = Json.parseToJsonElement(LiveTranscriptionEvents.sessionUpdate("Say \"hi\"")).jsonObject
        assertEquals("session.update", e["type"]!!.jsonPrimitive.content)
        val session = e["session"]!!.jsonObject
        assertEquals("transcription", session["type"]!!.jsonPrimitive.content)
        val input = session["audio"]!!.jsonObject["input"]!!.jsonObject
        assertEquals("audio/pcm", input["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(24_000, input["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content.toInt())
        val tr = input["transcription"]!!.jsonObject
        assertEquals("gpt-live-transcribe", tr["model"]!!.jsonPrimitive.content)
        assertEquals("en", tr["languages"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("low", tr["delay"]!!.jsonPrimitive.content)
        assertEquals("Say \"hi\"", tr["prompt"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, input["turn_detection"])
    }

    @Test
    fun `append - little-endian PCM in base64`() {
        assertEquals("{\"type\":\"input_audio_buffer.append\",\"audio\":\"AQD//w==\"}", LiveTranscriptionEvents.append(shortArrayOf(1, -1)))
    }

    @Test
    fun `server events as seen in the S03d probe`() {
        assertEquals(Event.Created, LiveTranscriptionEvents.parse("""{"type":"session.created","session":{}}"""))
        assertEquals(Event.Delta("item_1", " Yesterday"), LiveTranscriptionEvents.parse(
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"item_1","content_index":0,"delta":" Yesterday","obfuscation":"x"}""",
        ))
        assertEquals(Event.Committed("item_1"), LiveTranscriptionEvents.parse(
            """{"type":"input_audio_buffer.committed","previous_item_id":null,"item_id":"item_1"}""",
        ))
        assertEquals(Event.Completed("item_1", "Yesterday, I went to the shop", 9.0), LiveTranscriptionEvents.parse(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1","content_index":0,"transcript":"Yesterday, I went to the shop","usage":{"type":"duration","seconds":9}}""",
        ))
        assertEquals(Event.Error("error invalid_request_error/input_audio_buffer_commit_empty"), LiveTranscriptionEvents.parse(
            """{"type":"error","error":{"type":"invalid_request_error","code":"input_audio_buffer_commit_empty","message":"buffer too small"}}""",
        ))
        assertEquals(Event.Other, LiveTranscriptionEvents.parse("""{"type":"input_audio_buffer.cleared"}"""))
        assertEquals(Event.Other, LiveTranscriptionEvents.parse("not json"))
    }
}
