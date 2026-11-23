package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.SpeechPlanner
import com.example.listeningtrainer.conversation.Tutor
import com.example.listeningtrainer.conversation.TutorReply
import com.example.listeningtrainer.eventlog.EventLog
import com.example.listeningtrainer.eventlog.NoEventLog
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Учитель на Chat Completions (модель — в ModelConfig, `reasoning_effort: none`): история диалога,
 * системный промпт, инструменты `get_weather` и `search_live_web` ([tools]).
 *
 * История ограничена (D27): системный промпт + последние [historyLimit] сообщений ([trimHistory]).
 * По каждому запросу в журнал — номер запроса и размер истории в сообщениях (без текстов).
 *
 * Один экземпляр на процесс (TrainerApplication.tutor): история переживает «Back» и
 * перезапуск сервиса, пока приложение не закрыто совсем (D15). Сброс — [newConversation].
 */
class ChatTutor(
    private val apiKey: () -> String,
    private val api: OpenAiService = OpenAiClient.tutorApi,
    private val tools: TutorTools = WebTools(OpenAiClient.toolsHttp),
    private val log: EventLog = NoEventLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyLimit: Int = ModelConfig.TUTOR_HISTORY_MESSAGES,
    private val today: () -> Date = ::Date,
) : Tutor {

    private val chatHistory = mutableListOf<Message>()

    override suspend fun reply(studentText: String, onStatus: (String) -> Unit): TutorReply =
        ask(studentText, onStatus)

    override suspend fun farewell(onStatus: (String) -> Unit): TutorReply =
        ask(INACTIVITY_PROMPT, onStatus)

    override fun newConversation() = chatHistory.clear()

    override val lastTeacherText: String?
        get() = chatHistory.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content

    /** Сколько сообщений в истории (для журнала; тексты туда не пишутся). */
    val historySize: Int get() = chatHistory.size

    private suspend fun ask(userText: String, onStatus: (String) -> Unit): TutorReply {
        val auth = OpenAiClient.bearer(apiKey())
        if (chatHistory.isEmpty()) chatHistory.add(Message("system", systemPrompt()))
        chatHistory.add(Message("user", userText))
        trim()

        val started = clock()
        var response = request(1, auth, ChatRequest(model = ModelConfig.TUTOR_MODEL, messages = chatHistory.toList(), tools = TOOLS, reasoning_effort = ModelConfig.TUTOR_REASONING_EFFORT))
        val firstRequestMs = clock() - started
        var currentMessage = response.choices.first().message
        val toolCalls = currentMessage.tool_calls
        var toolDataFound = false

        if (!toolCalls.isNullOrEmpty()) {
            chatHistory.add(currentMessage)
            for (toolCall in toolCalls) {
                onStatus("Accessing internet... 🌐")
                val result = tools.call(toolCall.function.name, toolCall.function.arguments)
                if (ToolResults.hasData(result)) toolDataFound = true
                onStatus(if (ToolResults.hasData(result)) "Received ${result.length} chars from web." else "No fresh data found.")
                chatHistory.add(Message(role = "tool", tool_call_id = toolCall.id, content = result, name = toolCall.function.name))
            }
            response = request(2, auth, ChatRequest(model = ModelConfig.TUTOR_MODEL, messages = chatHistory.toList(), reasoning_effort = ModelConfig.TUTOR_REASONING_EFFORT))
            currentMessage = response.choices.first().message
        }

        var finalContent = currentMessage.content ?: ""
        val searched = !toolCalls.isNullOrEmpty()
        if (searched) {
            // Метка только для экрана: SpeechPlanner её не озвучивает.
            val label = if (toolDataFound) SpeechPlanner.LABEL_LIVE_WEB else SpeechPlanner.LABEL_SEARCH_FALLBACK
            finalContent = "$label $finalContent"
        }
        chatHistory.add(currentMessage)
        trim()
        return TutorReply(finalContent, searched = searched, firstRequestMs = firstRequestMs)
    }

    private suspend fun request(n: Int, auth: String, body: ChatRequest): ChatResponse {
        log.event(SOURCE, "tutor request $n: history ${body.messages.size} messages")
        return api.chat(auth, body)
    }

    private fun trim() {
        val trimmed = trimHistory(chatHistory, historyLimit)
        if (trimmed.size != chatHistory.size) {
            chatHistory.clear()
            chatHistory.addAll(trimmed)
        }
    }

    private fun systemPrompt(): String {
        val currentDate = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.US).format(today())
        return """
            You are a patient English teacher. Today is $currentDate.
            Your memory is limited to 2023.

            CRITICAL INSTRUCTIONS:
            1. For ANY info about prices, leaders, or news in $currentDate, YOU MUST CALL 'search_live_web'.
            2. Read the search results carefully. If they contain data (like Donald Trump or a price), USE IT.
            3. If search_live_web returns data, NEVER say "I only know up to 2023".
            4. $NO_DATA_RULE
            5. Use short sentences, check understanding, and end with a question.
            6. If the student makes a clear grammatical error or if the transcription suggests a phonetic mistake, briefly and kindly correct it first (e.g., "I think you meant '...', right?"). Then continue the conversation.
        """.trimIndent()
    }

    companion object {
        private const val SOURCE = "Network"

        /** Правило системного промпта для пустого поиска (S02b): без выдумок. */
        val NO_DATA_RULE =
            "If a tool result starts with ${ToolResults.NO_DATA}, the tool found nothing. Tell the student honestly, " +
                "in simple words, that you could not find fresh information about it. Do NOT guess. " +
                "Do NOT give any numbers, prices, dates, names, or events about it from memory."

        private const val INACTIVITY_PROMPT =
            "The student has been silent for several minutes. Gently say that you'll take a break and they can click the button to continue later. Keep it very short."

        /**
         * Окно истории: системный промпт (если он первый) + последние [limit] сообщений.
         * Окно не начинается с ответа инструмента (`role: tool`): пары «`tool_calls` — ответы `tool`»
         * не разрываются, иначе API отклоняет запрос.
         */
        fun trimHistory(history: List<Message>, limit: Int): List<Message> {
            val head = history.firstOrNull()?.takeIf { it.role == "system" }
            val rest = if (head != null) history.drop(1) else history
            if (rest.size <= limit) return history
            val window = rest.takeLast(limit).dropWhile { it.role == "tool" }
            return listOfNotNull(head) + window
        }

        val TOOLS = listOf(
            Tool(function = FunctionDef(
                name = "get_weather",
                description = "Get REAL-TIME weather for a city.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("location", buildJsonObject { put("type", "string"); put("description", "City (e.g. Tucson)") })
                    })
                    put("required", buildJsonArray { add("location") })
                }
            )),
            Tool(function = FunctionDef(
                name = "search_live_web",
                description = "Search internet for CURRENT 2026 data, prices, or news. Provide keywords ONLY.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject { put("type", "string"); put("description", "Keywords (e.g. 'gas price Tucson', 'US President')") })
                    })
                    put("required", buildJsonArray { add("query") })
                }
            ))
        )
    }
}
