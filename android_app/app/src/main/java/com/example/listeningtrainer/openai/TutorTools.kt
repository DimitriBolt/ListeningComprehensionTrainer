package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.regex.Pattern

/** Инструменты учителя: имя функции и JSON аргументов → текст для модели. */
fun interface TutorTools {
    suspend fun call(name: String, arguments: String): String
}

/**
 * Ответы инструментов, когда данных нет: начинаются с [NO_DATA]. Системный промпт учителя
 * требует в этом случае честно сказать ученику, что свежих данных нет, и ничего не придумывать.
 */
object ToolResults {
    const val NO_DATA = "NO_DATA:"
    val SEARCH_EMPTY = "$NO_DATA the web search returned no results. There is no fresh information."
    val WEATHER_EMPTY = "$NO_DATA the weather service did not answer. There is no weather information."
    val UNKNOWN_TOOL = "$NO_DATA unknown tool."

    fun hasData(result: String) = !result.startsWith(NO_DATA)

    /**
     * Страница поиска DuckDuckGo Lite → до 6 фрагментов результатов. Нет ни одного фрагмента —
     * [SEARCH_EMPTY]: «сырой» текст страницы без результатов раньше уходил модели и вёл к выдумкам.
     */
    fun searchSnippets(html: String): String {
        val snippets = mutableListOf<String>()
        val matcher = Pattern.compile("<td class=\"result-snippet\">(.*?)</td>", Pattern.DOTALL).matcher(html)
        while (matcher.find()) {
            val text = matcher.group(1)?.replace(Regex("<.*?>"), "")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (text.isNotEmpty()) snippets.add(text)
        }
        return if (snippets.isEmpty()) SEARCH_EMPTY else snippets.take(6).joinToString("\n---\n")
    }

    /** Ответ wttr.in: пустой — [WEATHER_EMPTY]. */
    fun weather(body: String?): String = body?.trim()?.takeIf { it.isNotEmpty() } ?: WEATHER_EMPTY
}

/** Погода (wttr.in) и поиск (DuckDuckGo Lite). Ошибка сети — ответ [ToolResults.NO_DATA], не исключение. */
class WebTools(private val http: OkHttpClient) : TutorTools {

    override suspend fun call(name: String, arguments: String): String = withContext(Dispatchers.IO) {
        val args = try { Json.parseToJsonElement(arguments).jsonObject } catch (e: Exception) { null }
        when (name) {
            "get_weather" -> {
                val loc = args?.get("location")?.jsonPrimitive?.content ?: "London"
                try {
                    val request = Request.Builder().url(ModelConfig.WEATHER_URL.replace("{location}", loc)).build()
                    http.newCall(request).execute().use { r ->
                        if (r.isSuccessful) ToolResults.weather(r.body?.string()) else ToolResults.WEATHER_EMPTY
                    }
                } catch (e: Exception) { ToolResults.WEATHER_EMPTY }
            }
            "search_live_web" -> {
                val query = args?.get("query")?.jsonPrimitive?.content.orEmpty()
                try {
                    val url = ModelConfig.SEARCH_URL + URLEncoder.encode(query, "UTF-8")
                    val request = Request.Builder().url(url).header("User-Agent", ModelConfig.SEARCH_USER_AGENT).build()
                    http.newCall(request).execute().use { r -> ToolResults.searchSnippets(r.body?.string().orEmpty()) }
                } catch (e: Exception) { ToolResults.SEARCH_EMPTY }
            }
            else -> ToolResults.UNKNOWN_TOOL
        }
    }
}
