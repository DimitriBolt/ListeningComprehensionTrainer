package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.FailureKind
import com.example.listeningtrainer.conversation.ServiceException
import com.example.listeningtrainer.conversation.TutorEvent
import com.example.listeningtrainer.conversation.TutorSource
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Разбор потока Responses (формат событий — документация OpenAI, `streaming-events`; примеры
 * сносок — проба S02c). Подставной транспорт отдаёт строки SSE; это проверяет наш разбор, а не
 * то, что настоящий API отвечает так же.
 */
class ResponsesTutorTest {

    private class Log : EventLog {
        val lines = mutableListOf<String>()
        override fun event(source: String, message: String) { lines += "[$source] $message" }
        override fun error(source: String, message: String, error: Throwable?) { lines += "[$source] ERROR $message" }
    }

    /** Транспорт: запоминает тела запросов и отдаёт заготовленные строки (или бросает ошибку). */
    private class FakeTransport : ResponsesTransport {
        val bodies = mutableListOf<JsonObject>()
        val streams = ArrayDeque<List<String>>()
        var error: Exception? = null
        override suspend fun stream(auth: String, body: String, onLine: suspend (String) -> Unit) {
            bodies += Json.parseToJsonElement(body).jsonObject
            error?.let { throw it }
            for (line in streams.removeFirst()) onLine(line)
        }
    }

    private fun sse(vararg events: String): List<String> = events.flatMap { listOf("event: x", "data: $it", "") }

    private fun delta(text: String) =
        """{"type":"response.output_text.delta","item_id":"msg_1","output_index":1,"content_index":0,"delta":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))},"sequence_number":5}"""

    private val searchAdded = """{"type":"response.output_item.added","output_index":0,"item":{"id":"ws_1","type":"web_search_call","status":"in_progress"}}"""
    private val searchDone =
        """{"type":"response.output_item.done","output_index":0,"item":{"id":"ws_1","type":"web_search_call","status":"completed",""" +
            """"action":{"type":"search","queries":["gas price Tucson"],"sources":[{"type":"url","url":"https://a.com"},{"type":"url","url":"https://b.com"},{"type":"oai-finance"}]}}}"""
    private val citation =
        """{"type":"response.output_text.annotation.added","item_id":"msg_1","output_index":1,"content_index":0,"annotation_index":0,""" +
            """"annotation":{"type":"url_citation","start_index":106,"end_index":208,"title":"Tucson, AZ Gas Prices Today","url":"https://whatsthepriceofgas.com/tucson?utm_source=openai"}}"""
    private val messageDone =
        """{"type":"response.output_item.done","output_index":1,"item":{"id":"msg_1","type":"message","content":[{"type":"output_text","text":"…",""" +
            """"annotations":[{"type":"url_citation","title":"Tucson, AZ Gas Prices Today","url":"https://whatsthepriceofgas.com/tucson?utm_source=openai"}]}]}}"""
    private val completed =
        """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":812,"input_tokens_details":{"cached_tokens":512},"output_tokens":64,"total_tokens":876}}}"""

    private val footnoteReply = listOf(
        "Regular gas in Tucson is about **\$3.88 per gallon** today. Prices can vary by station. ",
        "([whatsthepriceofgas.com](https://whatsthepriceofgas.com/tucson?utm_source=openai))",
        "\n\nDoes that help?",
    )

    private fun tutor(transport: FakeTransport, log: Log = Log(), limit: Int = 30) = ResponsesTutor(
        apiKey = { "sk-test" }, transport = transport, log = log, historyLimit = limit,
        today = { GregorianCalendar(2026, Calendar.NOVEMBER, 23).time },
    )

    @Test
    fun `request - model, effort none, stream, store false, web_search low, sources included`() = runTest {
        val transport = FakeTransport().apply { streams += sse(delta("Hi!"), completed) }
        tutor(transport).reply("Hello") {}
        val body = transport.bodies.single()
        assertEquals("gpt-6-luna", body["model"]!!.jsonPrimitive.content)
        assertEquals("none", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("web_search", tool["type"]!!.jsonPrimitive.content)
        assertEquals("low", tool["search_context_size"]!!.jsonPrimitive.content)
        assertEquals("web_search_call.action.sources", body["include"]!!.jsonArray.single().jsonPrimitive.content)
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user"), input.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("Hello", input.single()["content"]!!.jsonPrimitive.content)
        val prompt = body["instructions"]!!.jsonPrimitive.content
        assertTrue(prompt.contains("Today is Monday, November 23, 2026."))
        assertTrue(prompt.contains("Use web_search ONLY for fresh facts"))
        assertTrue(prompt.contains("If web_search finds nothing useful, tell the student honestly"))
        assertFalse(prompt.contains("2023"))
    }

    @Test
    fun `system prompt - one resource file by the method (D41-D43, D46, D47), date filled in`() {
        val prompt = ResponsesTutor.systemPrompt(java.util.GregorianCalendar(2026, 10, 23).time)
        // Тот же файл читает tools/probe/probe.py: ни одной копии в коде.
        val file = java.io.File("src/main/resources/tutor_prompt.txt").readText().trim()
        assertEquals(file.replace(ResponsesTutor.DATE_MARK, "Monday, November 23, 2026"), prompt)
        assertFalse(prompt.contains(ResponsesTutor.DATE_MARK))
        for (rule in listOf(
            "Each sentence has 6 to 8 words at most", "30 words at most", "End almost every reply with exactly one simple question", "Never ask the same thing twice",
            "A missing or wrong small word",
            "first ask a short question with two choices", "Give one main detail only",
            "The third time: say one key word in Russian", "Start at level A1",
            "Never search in a reply where you ask a clarifying question", "Your question is the last thing in your reply",
            "I think you meant \"...\", right?", "The words inside the quotes do not count",
            "Use web_search ONLY for fresh facts", "Do NOT guess",
        )) assertTrue(rule, prompt.contains(rule))
        // Прежние уровни A2/B1/B2 отменены.
        for (old in listOf("A2", "B1", "B2", "2023")) assertFalse(old, prompt.contains(old))
    }

    @Test
    fun `stream - text pieces, search start and end, one source per address, done with stats`() = runTest {
        val transport = FakeTransport().apply {
            streams += sse(searchAdded, searchDone, *footnoteReply.map { delta(it) }.toTypedArray(), citation, messageDone, completed)
        }
        val log = Log()
        val events = mutableListOf<TutorEvent>()
        tutor(transport, log).reply("How much is gas in Tucson today?") { events += it }

        assertEquals(TutorEvent.SearchStarted, events[0])
        assertEquals(TutorEvent.SearchFinished, events[1])
        assertEquals(footnoteReply, events.filterIsInstance<TutorEvent.Text>().map { it.delta })
        assertEquals(
            listOf(TutorSource("Tucson, AZ Gas Prices Today", "https://whatsthepriceofgas.com/tucson?utm_source=openai")),
            events.filterIsInstance<TutorEvent.Source>().map { it.source },
        )
        val stats = (events.last() as TutorEvent.Done).stats
        assertEquals(812, stats.inputTokens)
        assertEquals(512, stats.cachedTokens)
        assertEquals(64, stats.outputTokens)
        assertEquals(1, stats.searches)
        assertEquals(mapOf("oai-finance" to 1, "url" to 2), stats.sourceTypes)
        // (300 × 0,10 + 512 × 0,01 + 64 × 0,50) / 1M + 1 поиск × $0,01
        assertEquals(0.01 + (300 * 0.10 + 512 * 0.01 + 64 * 0.50) / 1e6, stats.costUsd, 1e-9)
        val line = log.lines.single { "tutor reply:" in it }
        assertEquals(
            "[Network] tutor reply: tokens in 812 (cached 512), out 64; searches 1; sources oai-finance×1, url×2; cost \$0.0101",
            line,
        )
        assertFalse(log.lines.any { "Tucson" in it || "whatsthepriceofgas" in it || "gas price" in it })
    }

    @Test
    fun `history keeps the student and the clean answer, sent with the next request`() = runTest {
        val transport = FakeTransport().apply {
            streams += sse(*footnoteReply.map { delta(it) }.toTypedArray(), completed)
            streams += sse(delta("Good."), completed)
        }
        val t = tutor(transport)
        t.reply("Gas?") {}
        val clean = "Regular gas in Tucson is about **\$3.88 per gallon** today. Prices can vary by station.\n\nDoes that help?"
        assertEquals(clean, t.lastTeacherText)
        t.reply("Yes") {}
        val input = transport.bodies[1]["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), input.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(clean, input[1]["content"]!!.jsonPrimitive.content)
        assertEquals(4, t.historySize)
    }

    @Test
    fun `broken stream - nothing remembered, retry sends the student once`() = runTest {
        val transport = FakeTransport().apply {
            streams += sse(delta("Half an ans")) // без response.completed
            streams += sse(delta("Whole answer."), completed)
        }
        val t = tutor(transport)
        try {
            t.reply("Hello") {}
            fail("expected an error")
        } catch (e: ServiceException) {
            assertEquals(FailureKind.SERVER, e.kind)
            assertTrue(e.kind.retry)
        }
        assertNull(t.lastTeacherText)
        assertEquals(0, t.historySize)
        t.reply("Hello") {}
        assertEquals(1, transport.bodies[1]["input"]!!.jsonArray.size)
        assertEquals("Whole answer.", t.lastTeacherText)
    }

    @Test
    fun `errors - http 401 is the key, failed response is the server, quota is not retried`() = runTest {
        val unauthorized = FakeTransport().apply {
            error = HttpException(retrofit2.Response.error<Any>(401, "{}".toResponseBody("application/json".toMediaType())))
        }
        val log = Log()
        assertEquals(FailureKind.KEY, kindOf { tutor(unauthorized, log).reply("Hi") {} })
        assertTrue(log.lines.contains("[Network] tutor stream: failed (http 401)"))

        val failed = FakeTransport().apply {
            streams += sse(delta("Hi"), """{"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"x"}}}""")
        }
        assertEquals(FailureKind.SERVER, kindOf { tutor(failed).reply("Hi") {} })

        val quota = FakeTransport().apply { streams += sse("""{"type":"error","code":"insufficient_quota","message":"x","param":null}""") }
        assertEquals(FailureKind.QUOTA, kindOf { tutor(quota).reply("Hi") {} })
        assertFalse(FailureKind.QUOTA.retry)

        val noNetwork = FakeTransport().apply { error = java.net.UnknownHostException("api.openai.com") }
        assertEquals(FailureKind.NO_NETWORK, kindOf { tutor(noNetwork).reply("Hi") {} })
    }

    @Test
    fun `incomplete response still ends the reply`() = runTest {
        val log = Log()
        val transport = FakeTransport().apply {
            streams += sse(delta("Short."), """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":10,"output_tokens":5}}}""")
        }
        val events = mutableListOf<TutorEvent>()
        tutor(transport, log).reply("Hi") { events += it }
        assertTrue(events.last() is TutorEvent.Done)
        assertTrue(log.lines.contains("[Network] tutor reply incomplete (max_output_tokens)"))
    }

    @Test
    fun `text before and after a search - two messages joined with a space`() = runTest {
        val message = """{"type":"response.output_item.added","output_index":1,"item":{"id":"msg_2","type":"message","role":"assistant","content":[]}}"""
        val transport = FakeTransport().apply {
            streams += sse(message, delta("Let me see."), searchAdded, searchDone, message, delta("It is 64 degrees."), completed)
        }
        val t = tutor(transport)
        val texts = mutableListOf<String>()
        t.reply("Temperature?") { if (it is TutorEvent.Text) texts += it.delta }
        assertEquals("Let me see. It is 64 degrees.", texts.joinToString(""))
        assertEquals("Let me see. It is 64 degrees.", t.lastTeacherText)
    }

    private val message2 = """{"type":"response.output_item.added","output_index":1,"item":{"id":"msg_2","type":"message","role":"assistant","content":[]}}"""

    @Test
    fun `reply ends at the first question - asked and then searched - no search, no answer`() = runTest {
        val log = Log()
        val transport = FakeTransport().apply {
            streams += sse(message2, delta("I think you meant \"What is the temperature in Tucson?\", right? Now or "), delta("tonight?"),
                searchAdded, searchDone, message2, delta("It is 64 degrees."), completed)
        }
        val t = tutor(transport, log)
        val events = mutableListOf<TutorEvent>()
        t.reply("What is temperature in Tucson?") { events += it }
        val said = events.filterIsInstance<TutorEvent.Text>().joinToString("") { it.delta }
        assertEquals("I think you meant \"What is the temperature in Tucson?\", right? Now or tonight?", said)
        assertFalse(events.any { it is TutorEvent.SearchStarted }) // без «Let me check.»
        assertTrue((events.last() as TutorEvent.Done).stats.endedAtQuestion)
        assertEquals(said, t.lastTeacherText)
        assertTrue(log.lines.contains("[Network] tutor reply ended at the first question: the rest is not read"))
    }

    @Test
    fun `reply ends at the first question - the same question twice in one piece`() = runTest {
        val transport = FakeTransport().apply {
            streams += sse(delta("Do you mean now or today? Do you mean the temperature now?"), completed)
            streams += sse(delta("It is 15 degrees. Is that warm or cold? "), completed)
        }
        val t = tutor(transport)
        val texts = mutableListOf<String>()
        t.reply("Temperature?") { if (it is TutorEvent.Text) texts += it.delta }
        assertEquals("Do you mean now or today?", texts.joinToString(""))
        assertEquals("Do you mean now or today?", t.lastTeacherText)
        // Вопрос в конце — обычный ответ.
        val events = mutableListOf<TutorEvent>()
        t.reply("Now.") { events += it }
        assertFalse((events.last() as TutorEvent.Done).stats.endedAtQuestion)
        assertEquals("It is 15 degrees. Is that warm or cold?", t.lastTeacherText)
    }

    @Test
    fun `reply ends at the first question - its footnote is still read for the screen`() = runTest {
        val transport = FakeTransport().apply {
            streams += sse(delta("Sunset is at 5:20 PM. Is that early? "), delta("([site.com](https://site.com/a?utm_source=openai))"),
                citation, delta(" It is late in summer."), completed)
        }
        val t = tutor(transport)
        val events = mutableListOf<TutorEvent>()
        t.reply("Sunset?") { events += it }
        assertEquals("Sunset is at 5:20 PM. Is that early?", events.filterIsInstance<TutorEvent.Text>().joinToString("") { it.delta })
        assertEquals(1, events.count { it is TutorEvent.Source })
        assertTrue((events.last() as TutorEvent.Done).stats.endedAtQuestion)
    }

    @Test
    fun `length limit - max_output_tokens in the request, cut reply remembered without the unfinished sentence`() = runTest {
        val transport = FakeTransport().apply {
            streams += sse(delta("It is warm. It is nice. And the"), """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":10,"output_tokens":120}}}""")
            streams += sse(delta("Fine."), """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"content_filter"}}}""")
        }
        val t = tutor(transport)
        val events = mutableListOf<TutorEvent>()
        t.reply("Weather?") { events += it }
        assertEquals(ModelConfig.TUTOR_MAX_OUTPUT_TOKENS, transport.bodies.single()["max_output_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue((events.last() as TutorEvent.Done).stats.truncated)
        assertEquals("It is warm. It is nice.", t.lastTeacherText)
        // Другая причина неполного ответа — не обрезка по длине.
        events.clear()
        t.reply("Ok") { events += it }
        assertFalse((events.last() as TutorEvent.Done).stats.truncated)
    }

    @Test
    fun `history window keeps the last messages only`() = runTest {
        val transport = FakeTransport().apply { repeat(4) { streams += sse(delta("Answer $it."), completed) } }
        val t = tutor(transport, limit = 4)
        repeat(4) { t.reply("Question $it") {} }
        assertEquals(4, t.historySize)
        assertEquals("Question 2", t.turns.first().text)
        // В запросе — окно и новая реплика, не больше limit сообщений.
        assertEquals(4, transport.bodies.last()["input"]!!.jsonArray.size)
        assertEquals(
            listOf(ResponsesTutor.Turn("user", "b"), ResponsesTutor.Turn("assistant", "c")),
            ResponsesTutor.trimHistory(listOf(ResponsesTutor.Turn("user", "a"), ResponsesTutor.Turn("user", "b"), ResponsesTutor.Turn("assistant", "c")), 2),
        )
    }

    @Test
    fun `farewell is asked like a reply and new conversation forgets`() = runTest {
        val transport = FakeTransport().apply { streams += sse(delta("Bye."), completed) }
        val t = tutor(transport)
        t.farewell {}
        assertTrue(transport.bodies.single()["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content.contains("silent"))
        t.newConversation()
        assertNull(t.lastTeacherText)
    }

    private suspend fun kindOf(block: suspend () -> Unit): FailureKind? = try {
        block()
        null
    } catch (e: ServiceException) {
        e.kind
    }
}
