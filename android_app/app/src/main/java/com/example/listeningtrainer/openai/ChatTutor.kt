package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.SpeechPlanner
import com.example.listeningtrainer.conversation.Tutor
import com.example.listeningtrainer.conversation.TutorReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Учитель на Chat Completions (модель — в ModelConfig): история диалога, системный промпт,
 * инструменты `get_weather` и `search_live_web`. Перенесено из TrainerViewModel без изменений.
 *
 * Один экземпляр на процесс (TrainerApplication.tutor): история переживает «Back» и
 * перезапуск сервиса, пока приложение не закрыто совсем (D15). Сброс — [newConversation].
 */
class ChatTutor(
    private val apiKey: () -> String,
    private val api: OpenAiService = OpenAiClient.api,
    private val http: OkHttpClient = OpenAiClient.http,
) : Tutor {

    private val chatHistory = mutableListOf<Message>()

    override suspend fun reply(studentText: String, onStatus: (String) -> Unit): TutorReply =
        ask(studentText, onStatus)

    override suspend fun farewell(onStatus: (String) -> Unit): TutorReply =
        ask(INACTIVITY_PROMPT, onStatus)

    override fun newConversation() = chatHistory.clear()

    /** Сколько сообщений в истории (для журнала; тексты туда не пишутся). */
    val historySize: Int get() = chatHistory.size

    private suspend fun ask(userText: String, onStatus: (String) -> Unit): TutorReply {
        val auth = OpenAiClient.bearer(apiKey())
        if (chatHistory.isEmpty()) chatHistory.add(Message("system", systemPrompt()))
        chatHistory.add(Message("user", userText))

        var response = api.checkKey(auth, ChatRequest(model = ModelConfig.TUTOR_MODEL, messages = chatHistory, tools = TOOLS))
        var currentMessage = response.choices.first().message
        val toolCalls = currentMessage.tool_calls
        var toolDataFound = false

        if (toolCalls != null) {
            chatHistory.add(currentMessage)
            for (toolCall in toolCalls) {
                onStatus("Accessing internet... 🌐")
                val result = callTool(toolCall.function.name, toolCall.function.arguments)
                if (result.length > 50 && !result.contains("failed", true)) toolDataFound = true

                onStatus("Received ${result.length} chars from web.")
                // Пауза, чтобы строка состояния успела прочитаться. Сохранена с прежнего кода (S01).
                delay(800)
                chatHistory.add(Message(role = "tool", tool_call_id = toolCall.id, content = result, name = toolCall.function.name))
            }
            response = api.checkKey(auth, ChatRequest(model = ModelConfig.TUTOR_MODEL, messages = chatHistory))
            currentMessage = response.choices.first().message
        }

        var finalContent = currentMessage.content ?: ""
        if (toolCalls != null) {
            // Метка только для экрана: SpeechPlanner её не озвучивает.
            val label = if (toolDataFound) SpeechPlanner.LABEL_LIVE_WEB else SpeechPlanner.LABEL_SEARCH_FALLBACK
            finalContent = "$label $finalContent"
        }
        chatHistory.add(currentMessage)
        return TutorReply(finalContent)
    }

    private fun systemPrompt(): String {
        val currentDate = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.US).format(Date())
        return """
            You are a patient English teacher. Today is $currentDate.
            Your memory is limited to 2023.

            CRITICAL INSTRUCTIONS:
            1. For ANY info about prices, leaders, or news in $currentDate, YOU MUST CALL 'search_live_web'.
            2. Read the search results carefully. If they contain data (like Donald Trump or a price), USE IT.
            3. If search_live_web returns data, NEVER say "I only know up to 2023".
            4. If results are truly empty, ADMIT IT and ask for a simpler query.
            5. Use short sentences, check understanding, and end with a question.
            6. If the student makes a clear grammatical error or if the transcription suggests a phonetic mistake, briefly and kindly correct it first (e.g., "I think you meant '...', right?"). Then continue the conversation.
        """.trimIndent()
    }

    private suspend fun callTool(name: String, args: String): String = withContext(Dispatchers.IO) {
        val jsonArgs = Json.parseToJsonElement(args).jsonObject
        when (name) {
            "get_weather" -> {
                val loc = jsonArgs["location"]?.jsonPrimitive?.content ?: "London"
                try {
                    val response = http.newCall(Request.Builder().url(ModelConfig.WEATHER_URL.replace("{location}", loc)).build()).execute()
                    response.body?.string() ?: "Weather unavailable"
                } catch (e: Exception) { "Weather failed" }
            }
            "search_live_web" -> {
                val query = jsonArgs["query"]?.jsonPrimitive?.content ?: ""
                try {
                    val url = ModelConfig.SEARCH_URL + URLEncoder.encode(query, "UTF-8")
                    val request = Request.Builder().url(url).header("User-Agent", ModelConfig.SEARCH_USER_AGENT).build()
                    val html = http.newCall(request).execute().body?.string() ?: ""

                    // Собираем все текстовые блоки из результатов для модели
                    val snippets = mutableListOf<String>()
                    val matcher = Pattern.compile("<td class=\"result-snippet\">(.*?)</td>", Pattern.DOTALL).matcher(html)
                    while (matcher.find()) {
                        snippets.add(matcher.group(1)?.replace(Regex("<.*?>"), "")?.trim() ?: "")
                    }

                    if (snippets.isNotEmpty()) {
                        snippets.take(6).joinToString("\n---\n")
                    } else {
                        // Фолбек: если сниппеты не найдены, отдаём первые 2000 знаков текста страницы
                        val cleanText = html.replace(Regex("<.*?>"), " ").replace(Regex("\\s+"), " ").take(2000)
                        "Raw results for '$query': $cleanText"
                    }
                } catch (e: Exception) { "Search failed: ${e.localizedMessage}" }
            }
            else -> "Unknown tool"
        }
    }

    private companion object {
        const val INACTIVITY_PROMPT =
            "The student has been silent for several minutes. Gently say that you'll take a break and they can click the button to continue later. Keep it very short."

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
