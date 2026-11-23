package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.SpeechPlanner
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTutorTest {

    /** Подставной API: запоминает запросы, отвечает по очереди из [answers]. */
    private class FakeApi(vararg answers: Message) : OpenAiService {
        val requests = mutableListOf<ChatRequest>()
        private val queue = ArrayDeque(answers.toList())
        override suspend fun chat(authHeader: String, request: ChatRequest): ChatResponse {
            requests += request
            return ChatResponse(listOf(Choice(queue.removeFirst())))
        }
        override suspend fun listModels(authHeader: String): ResponseBody = error("not used")
        override suspend fun transcribeAudio(
            authHeader: String, file: MultipartBody.Part, model: RequestBody, language: RequestBody, prompt: RequestBody,
        ): TranscriptionResponse = error("not used")
    }

    private fun answer(text: String) = Message("assistant", text)
    private fun toolCall(id: String = "call_1", name: String = "search_live_web") = Message(
        "assistant",
        tool_calls = listOf(ToolCall(id, "function", FunctionCall(name, """{"query":"microsoft outage"}"""))),
    )

    private fun tutor(api: FakeApi, tools: TutorTools = TutorTools { _, _ -> "fresh data from the web" }, limit: Int = 30) =
        ChatTutor(apiKey = { "sk-test" }, api = api, tools = tools, historyLimit = limit)

    @Test
    fun `request uses gpt-6-luna with reasoning none and tools`() = runTest {
        val api = FakeApi(answer("Hi!"))
        val reply = tutor(api).reply("Hello", {})
        val r = api.requests.single()
        assertEquals("gpt-6-luna", r.model)
        assertEquals(ModelConfig.TUTOR_MODEL, r.model)
        assertEquals("none", r.reasoning_effort)
        assertEquals(listOf("get_weather", "search_live_web"), r.tools!!.map { it.function.name })
        assertEquals(listOf("system", "user"), r.messages.map { it.role })
        assertEquals("Hi!", reply.text)
        assertFalse(reply.searched)
    }

    @Test
    fun `empty search - tool message says NO_DATA, system prompt forbids inventing, screen label is fallback`() = runTest {
        val api = FakeApi(toolCall(), answer("Sorry, I could not find fresh information."))
        val tools = TutorTools { _, _ -> ToolResults.searchSnippets("<html>no results here</html>") }
        val reply = tutor(api, tools).reply("Was there a Microsoft outage today?", {})

        val second = api.requests[1]
        assertEquals(listOf("system", "user", "assistant", "tool"), second.messages.map { it.role })
        val toolMessage = second.messages.last()
        assertTrue(toolMessage.content!!.startsWith("NO_DATA:"))
        assertEquals("call_1", toolMessage.tool_call_id)
        val system = second.messages.first().content!!
        assertTrue(system.contains("starts with NO_DATA"))
        assertTrue(system.contains("Do NOT guess"))
        assertTrue(system.contains("numbers, prices, dates, names, or events"))
        assertEquals("none", second.reasoning_effort)
        assertNull(second.tools) // второй запрос — без инструментов, как раньше
        assertTrue(reply.searched)
        assertTrue(reply.text.startsWith(SpeechPlanner.LABEL_SEARCH_FALLBACK))
    }

    @Test
    fun `search with data - live web label, no NO_DATA`() = runTest {
        val api = FakeApi(toolCall(), answer("Gas is 3 dollars."))
        val reply = tutor(api).reply("Gas price?", {})
        assertFalse(api.requests[1].messages.last().content!!.startsWith("NO_DATA"))
        assertTrue(reply.text.startsWith(SpeechPlanner.LABEL_LIVE_WEB))
    }

    @Test
    fun `search snippets are extracted, a page without results is NO_DATA`() {
        val html = """<tr><td class="result-snippet">Regular gas <b>costs</b> ${'$'}3.29</td></tr>
            <tr><td class="result-snippet">  </td></tr>"""
        assertEquals("Regular gas costs \$3.29", ToolResults.searchSnippets(html))
        assertEquals(ToolResults.SEARCH_EMPTY, ToolResults.searchSnippets("<html><body>DuckDuckGo lite page</body></html>"))
        assertEquals(ToolResults.WEATHER_EMPTY, ToolResults.weather("  "))
        // В журнал — только число результатов и вид страницы, без текста.
        assertEquals("search: 1 results (http 200, 1 KB)", ToolResults.searchLogLine(200, html))
        assertEquals(
            "search: no results (http 202, 1 KB, bot check page) -> NO_DATA",
            ToolResults.searchLogLine(202, "<html>anomaly-modal</html>"),
        )
        assertEquals("Tucson: Sunny +20C", ToolResults.weather("Tucson: Sunny +20C\n"))
    }

    @Test
    fun `history is the system prompt plus the last N messages`() = runTest {
        val api = FakeApi(*Array(10) { answer("Answer $it.") })
        val t = tutor(api, limit = 4)
        repeat(10) { t.reply("Question $it", {}) }
        val last = api.requests.last().messages
        assertEquals(5, last.size) // system + 4
        assertEquals("system", last.first().role)
        assertEquals(listOf("Question 8", "Answer 8.", "Question 9"), last.drop(2).map { it.content })
        assertEquals("Answer 9.", t.lastTeacherText)
    }

    @Test
    fun `trimming never starts with a tool answer`() {
        val history = listOf(
            Message("system", "S"),
            Message("user", "u1"), Message("assistant", "a1"),
            Message("user", "u2"),
            toolCall(),
            Message("tool", "r1", tool_call_id = "call_1"),
            Message("tool", "r2", tool_call_id = "call_2"),
            Message("assistant", "a2"),
            Message("user", "u3"),
        )
        // Последние 4 — [r1, r2, a2, u3]: ответы инструмента без своего вызова отбрасываются.
        val four = ChatTutor.trimHistory(history, 4)
        assertEquals(listOf("system", "assistant", "user"), four.map { it.role })
        assertEquals(listOf("S", "a2", "u3"), four.map { it.content })
        // Окно с вызовом инструмента целиком — пара не разорвана.
        val five = ChatTutor.trimHistory(history, 5)
        assertEquals(listOf("system", "assistant", "tool", "tool", "assistant", "user"), five.map { it.role })
        assertTrue(five[1].tool_calls != null)
        // Короткая история не трогается.
        assertEquals(history, ChatTutor.trimHistory(history, 30))
    }

    @Test
    fun `new conversation clears history and last teacher text`() = runTest {
        val api = FakeApi(answer("One."), answer("Two."))
        val t = tutor(api)
        t.reply("a", {})
        assertEquals("One.", t.lastTeacherText)
        t.newConversation()
        assertNull(t.lastTeacherText)
        t.reply("b", {})
        assertEquals(listOf("system", "user"), api.requests.last().messages.map { it.role })
    }
}
