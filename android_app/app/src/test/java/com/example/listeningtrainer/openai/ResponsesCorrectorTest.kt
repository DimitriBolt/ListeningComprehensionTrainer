package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.FailureKind
import com.example.listeningtrainer.conversation.ServiceException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ResponsesCorrectorTest {

    private val answer = """
        {"id":"resp_1","object":"response","status":"completed","output":[{"type":"message","id":"msg_1","role":"assistant",
        "content":[{"type":"output_text","text":"I have a dog.","annotations":[]}]}],
        "usage":{"input_tokens":303,"input_tokens_details":{"cached_tokens":0},"output_tokens":9,"total_tokens":312}}
    """.trimIndent()

    private class FakeTransport(private val lines: List<String>, private val error: Exception? = null) : ResponsesTransport {
        var body = ""
        var auth = ""
        override suspend fun stream(auth: String, body: String, onLine: suspend (String) -> Unit) {
            this.auth = auth
            this.body = body
            error?.let { throw it }
            lines.forEach { onLine(it) }
        }
    }

    @Test
    fun `request - one student line, no tools, no history, not stored, prompt from the resource`() = runTest {
        val transport = FakeTransport(answer.lines())
        val a = ResponsesCorrector({ "sk-test" }, transport).correct("I have dog.")
        assertEquals("I have a dog.", a.text)
        assertEquals(ResponsesTutor.cost(303, 0, 9, 0), a.costUsd, 1e-12)
        assertEquals("Bearer sk-test", transport.auth)
        val body = Json.parseToJsonElement(transport.body).jsonObject
        assertEquals(ModelConfig.CORRECTOR_MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals("none", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(ModelConfig.CORRECTOR_MAX_OUTPUT_TOKENS, body["max_output_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("stream"))
        val input = body["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("I have dog.", input[0].jsonObject["content"]!!.jsonPrimitive.content)
        // Тот же файл читает tools/probe/corrector.py.
        val file = java.io.File("src/main/resources/corrector_prompt.txt").readText().trim()
        assertEquals(file, body["instructions"]!!.jsonPrimitive.content)
        assertTrue(file.contains("reply exactly: OK"))
    }

    @Test
    fun `failures - network, empty answer, unfinished answer`() = runTest {
        suspend fun kind(t: FakeTransport): FailureKind = try {
            ResponsesCorrector({ "k" }, t).correct("x"); fail("no error"); FailureKind.OTHER
        } catch (e: ServiceException) {
            e.kind
        }
        assertEquals(FailureKind.SERVER, kind(FakeTransport(emptyList(), IOException("reset"))))
        assertEquals(FailureKind.SERVER, kind(FakeTransport(listOf("""{"status":"completed","output":[]}"""))))
        assertEquals(FailureKind.SERVER, kind(FakeTransport(listOf("""{"status":"incomplete","output":[]}"""))))
        assertEquals(FailureKind.SERVER, kind(FakeTransport(listOf("not json"))))
    }

    @Test
    fun `long utterance - the limit has room, a long completed answer is taken (S05, D80)`() = runTest {
        assertTrue(ModelConfig.CORRECTOR_MAX_OUTPUT_TOKENS >= 200)
        val long = List(90) { "word" }.joinToString(" ") + "."
        val t = FakeTransport(listOf(
            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"$long"}]}],""" +
                """"usage":{"input_tokens":400,"output_tokens":120}}""",
        ))
        assertEquals(long, ResponsesCorrector({ "k" }, t).correct(long).text)
    }
}
