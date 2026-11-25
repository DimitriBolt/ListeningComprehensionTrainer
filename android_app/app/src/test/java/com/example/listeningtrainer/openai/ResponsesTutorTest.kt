package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.FailureKind
import com.example.listeningtrainer.conversation.ServiceException
import com.example.listeningtrainer.conversation.NotUnderstood
import com.example.listeningtrainer.conversation.StudentLevel
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
        tutor(transport).reply("Hello", null) {}
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
    fun `system prompt - one resource file by the method (D41-D43, D46, D47, S02f-S02i), date filled in`() {
        val prompt = ResponsesTutor.systemPrompt(java.util.GregorianCalendar(2026, 10, 23).time)
        // Тот же файл читает tools/probe/probe.py: ни одной копии в коде.
        val file = java.io.File("src/main/resources/tutor_prompt.txt").readText().trim()
        assertEquals(file.replace(ResponsesTutor.DATE_MARK, "Monday, November 23, 2026"), prompt)
        assertFalse(prompt.contains(ResponsesTutor.DATE_MARK))
        for (rule in listOf(
            // S02g: реплика содержательна (D51), один вопрос в конце из сказанного (D52), уточнение — фраза о теме и выбор.
            "25 to 40 words", "8 to 10 words, never more than 10", "Build the reply in this order", "something real and new about the topic",
            "Your question, about something from step 2. It is the very last sentence", "No questions in the middle of your reply",
            "Never ask the same thing twice", "It grows from what you just said", "Take turns between two kinds of questions",
            "About half of your questions are checks", "Your replies go in turn",
            "first ask a question with two choices",
            "one short sentence about the topic, then the question with two choices", "no numbers, no dates and no names",
            "Start at level A1", "Never search in this reply",
            "Use web_search ONLY for fresh facts", "Do NOT search for general knowledge", "Do NOT guess",
            // S02h: длина — из служебной строки уровня (D56); после выбора — рассказ; выбор простыми словами; без курсива (D57).
            StudentLevel.note(ModelConfig.LEVEL_TARGETS[2]), "Your reply has exactly that many sentences and words",
            "never longer sentences", "The length comes only from the app note",
            "whatever the target length", "NEVER offer a new choice", "simple, very common words", "no italics, no markdown",
            // S02f: поправляет поправщик, русское слово — по счёту приложения.
            "Never correct the student's grammar", "The app does it before your reply",
            // S02i: строка «не понял» вместо строки длины и сама говорит, что делать (D58); названия в кавычках (D60);
            // не больше двух чисел (D61).
            "the app sends a different note instead", "do what that note says", "Do exactly what the note says",
            "only when the app note tells you to say one key word in Russian", "Without that note, not one Russian letter",
            NotUnderstood.note(2)!!.substringBefore(", simpler"), "Titles of books, films and songs go in plain double quotes",
            "His favorite book is \"The Call of the Wild\". Never italics.", "At most two numbers in one reply",
            "the one main number, not a list",
        )) assertTrue(rule, prompt.contains(rule))
        // Прежние уровни A2/B1/B2 отменены.
        for (old in listOf("A2", "B1", "B2", "2023", "6 to 8 words", "30 words at most", "up to 5 or 6 sentences", "LEVEL AND LENGTH",
            "forget the target length", "The note says 3 or more", "Write book and film titles as plain words")) assertFalse(old, prompt.contains(old))
    }

    @Test
    fun `stream - text pieces, search start and end, one source per address, done with stats`() = runTest {
        val transport = FakeTransport().apply {
            streams += sse(searchAdded, searchDone, *footnoteReply.map { delta(it) }.toTypedArray(), citation, messageDone, completed)
        }
        val log = Log()
        val events = mutableListOf<TutorEvent>()
        tutor(transport, log).reply("How much is gas in Tucson today?", null) { events += it }

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
        t.reply("Gas?", null) {}
        val clean = "Regular gas in Tucson is about **\$3.88 per gallon** today. Prices can vary by station.\n\nDoes that help?"
        assertEquals(clean, t.lastTeacherText)
        t.reply("Yes", null) {}
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
            t.reply("Hello", null) {}
            fail("expected an error")
        } catch (e: ServiceException) {
            assertEquals(FailureKind.SERVER, e.kind)
            assertTrue(e.kind.retry)
        }
        assertNull(t.lastTeacherText)
        assertEquals(0, t.historySize)
        t.reply("Hello", null) {}
        assertEquals(1, transport.bodies[1]["input"]!!.jsonArray.size)
        assertEquals("Whole answer.", t.lastTeacherText)
    }

    @Test
    fun `errors - http 401 is the key, failed response is the server, quota is not retried`() = runTest {
        val unauthorized = FakeTransport().apply {
            error = HttpException(retrofit2.Response.error<Any>(401, "{}".toResponseBody("application/json".toMediaType())))
        }
        val log = Log()
        assertEquals(FailureKind.KEY, kindOf { tutor(unauthorized, log).reply("Hi", null) {} })
        assertTrue(log.lines.contains("[Network] tutor stream: failed (http 401)"))

        val failed = FakeTransport().apply {
            streams += sse(delta("Hi"), """{"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"x"}}}""")
        }
        assertEquals(FailureKind.SERVER, kindOf { tutor(failed).reply("Hi", null) {} })

        val quota = FakeTransport().apply { streams += sse("""{"type":"error","code":"insufficient_quota","message":"x","param":null}""") }
        assertEquals(FailureKind.QUOTA, kindOf { tutor(quota).reply("Hi", null) {} })
        assertFalse(FailureKind.QUOTA.retry)

        val noNetwork = FakeTransport().apply { error = java.net.UnknownHostException("api.openai.com") }
        assertEquals(FailureKind.NO_NETWORK, kindOf { tutor(noNetwork).reply("Hi", null) {} })
    }

    @Test
    fun `incomplete response still ends the reply`() = runTest {
        val log = Log()
        val transport = FakeTransport().apply {
            streams += sse(delta("Short."), """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":10,"output_tokens":5}}}""")
        }
        val events = mutableListOf<TutorEvent>()
        tutor(transport, log).reply("Hi", null) { events += it }
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
        t.reply("Temperature?", null) { if (it is TutorEvent.Text) texts += it.delta }
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
        t.reply("What is temperature in Tucson?", null) { events += it }
        val said = events.filterIsInstance<TutorEvent.Text>().joinToString("") { it.delta }
        assertEquals("I think you meant \"What is the temperature in Tucson?\", right? Now or tonight?", said)
        assertFalse(events.any { it is TutorEvent.SearchStarted }) // без «Let me check.»
        assertTrue((events.last() as TutorEvent.Done).stats.endedAtQuestion)
        assertEquals(said, t.lastTeacherText)
        assertTrue(log.lines.contains("[Network] tutor reply ended at the first question: the rest is not read"))
        // Итога с токенами сервер не прислал: цена неизвестна, а не $0.0000 (S02f).
        assertTrue(log.lines.contains("[Network] tutor reply: tokens unknown (stream cut); searches 0; sources -; cost unknown (stream cut)"))
    }

    @Test
    fun `reply ends at the first question - the same question twice in one piece`() = runTest {
        val transport = FakeTransport().apply {
            streams += sse(delta("Do you mean now or today? Do you mean the temperature now?"), completed)
            streams += sse(delta("It is 15 degrees. Is that warm or cold? "), completed)
        }
        val t = tutor(transport)
        val texts = mutableListOf<String>()
        t.reply("Temperature?", null) { if (it is TutorEvent.Text) texts += it.delta }
        assertEquals("Do you mean now or today?", texts.joinToString(""))
        assertEquals("Do you mean now or today?", t.lastTeacherText)
        // Вопрос в конце — обычный ответ.
        val events = mutableListOf<TutorEvent>()
        t.reply("Now.", null) { events += it }
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
        t.reply("Sunset?", null) { events += it }
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
        t.reply("Weather?", null) { events += it }
        assertEquals(ModelConfig.TUTOR_MAX_OUTPUT_TOKENS, transport.bodies.single()["max_output_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue((events.last() as TutorEvent.Done).stats.truncated)
        assertEquals("It is warm. It is nice.", t.lastTeacherText)
        // Другая причина неполного ответа — не обрезка по длине.
        events.clear()
        t.reply("Ok", null) { events += it }
        assertFalse((events.last() as TutorEvent.Done).stats.truncated)
    }

    @Test
    fun `history window keeps the last messages only`() = runTest {
        val transport = FakeTransport().apply { repeat(4) { streams += sse(delta("Answer $it."), completed) } }
        val t = tutor(transport, limit = 4)
        repeat(4) { t.reply("Question $it", null) {} }
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

    // ---- S02f ---------------------------------------------------------------------------

    @Test
    fun `not understood in a row - the count goes to this request only, history keeps the student's words`() = runTest {
        val transport = FakeTransport().apply {
            repeat(4) { streams += sse(delta("What do you cook?"), completed) }
        }
        val log = Log()
        val t = tutor(transport, log)
        for (said in listOf("I like to cook.", "What?", "I don't understand.", "Sorry, I don't understand.")) t.reply(said, null) {}

        fun lastUser(i: Int) = transport.bodies[i]["input"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("I like to cook.", lastUser(0))
        assertEquals("What?\n${NotUnderstood.note(1)}", lastUser(1))
        assertEquals("I don't understand.\n${NotUnderstood.note(2)}", lastUser(2))
        assertEquals("Sorry, I don't understand.\n${NotUnderstood.note(3)}", lastUser(3))
        // В истории — реплики как есть, без служебной строки.
        assertEquals(listOf("I like to cook.", "What?", "I don't understand.", "Sorry, I don't understand."),
            t.turns.filter { it.role == "user" }.map { it.text })
        assertTrue(log.lines.contains("[Network] student did not understand 3 in a row: note added to the request"))
        assertFalse(log.lines.any { "cook" in it })
    }

    // ---- S02h ---------------------------------------------------------------------------

    @Test
    fun `level note - in this request only, with not understood only that note goes (D58)`() = runTest {
        val transport = FakeTransport().apply {
            repeat(4) { streams += sse(delta("What do you cook?"), completed) }
        }
        val t = tutor(transport)
        val level = "(App note: target length: 7 to 8 sentences, 65 to 80 words.)"
        for (said in listOf("I like to cook.", "What?", "Sorry, I don't understand.", "Pasta.")) t.reply(said, level) {}

        fun lastUser(i: Int) = transport.bodies[i]["input"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("I like to cook.\n$level", lastUser(0))
        // Строки не спорят: при «не понял» строки длины нет, только строка «не понял» с указанием.
        assertEquals("What?\n${NotUnderstood.note(1)}", lastUser(1))
        assertEquals("Sorry, I don't understand.\n${NotUnderstood.note(2)}", lastUser(2))
        // Понял — снова цель длины.
        assertEquals("Pasta.\n$level", lastUser(3))
        assertEquals(listOf("I like to cook.", "What?", "Sorry, I don't understand.", "Pasta."),
            t.turns.filter { it.role == "user" }.map { it.text })
        // В истории второго запроса прошлая реплика — без строки.
        assertEquals("I like to cook.", transport.bodies[1]["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `replace last reply - the history keeps what was spoken`() = runTest {
        val transport = FakeTransport().apply { streams += sse(delta("Hello there."), completed) }
        val t = tutor(transport)
        t.replaceLastReply("nothing yet") // истории ещё нет — ничего
        assertTrue(t.turns.isEmpty())
        t.reply("I have dog.", null) {}
        t.replaceLastReply("I think you meant \"I have a dog\", right? Hello there.")
        assertEquals("I think you meant \"I have a dog\", right? Hello there.", t.lastTeacherText)
        assertEquals(2, t.turns.size)
    }
}
