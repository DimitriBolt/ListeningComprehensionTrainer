package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.FailureKind
import com.example.listeningtrainer.conversation.ServiceException
import com.example.listeningtrainer.conversation.SpeechText
import com.example.listeningtrainer.conversation.Tutor
import com.example.listeningtrainer.conversation.TutorEvent
import com.example.listeningtrainer.conversation.TutorSource
import com.example.listeningtrainer.conversation.TutorStats
import com.example.listeningtrainer.eventlog.EventLog
import com.example.listeningtrainer.eventlog.NoEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Учитель на Responses API (D36): `POST /v1/responses`, поток (SSE), встроенный `web_search`
 * (`search_context_size: low`), `reasoning.effort: none`, `store: false`. Параметры — в [ModelConfig].
 * Образец — `tools/probe/probe.py responses`, вариант 3 (проба S02c).
 *
 * Документация OpenAI (прочитана 2026-11-24): developers.openai.com/api/reference/resources/responses
 * (`methods/create`: `input` — сообщения `role`/`content`, `store` по умолчанию `true`, `include`
 * `web_search_call.action.sources`; `streaming-events`: `response.output_text.delta {delta}`,
 * `response.output_text.annotation.added {annotation}`, `response.output_item.added/done {item}`,
 * `response.completed {response.usage}`, `response.failed {response.error}`, `response.incomplete`,
 * `error {code, message}`), developers.openai.com/api/docs/guides/tools-web-search (`url_citation`:
 * `url`, `title`; сноски на экране — кликабельными ссылками).
 *
 * История — только реплики ученика и ответы учителя (без сносок); служебные элементы поиска в
 * неё не идут. Окно — последние [historyLimit] сообщений (D27). Пара «реплика — ответ» попадает
 * в историю только после `response.completed`.
 *
 * В журнал — номер запроса, размер истории, итог ответа (токены, поиски, типы источников, цена);
 * тексты, запросы поиска и адреса — никогда.
 *
 * Один экземпляр на процесс (TrainerApplication.tutor): история переживает «Back» (D15).
 */
class ResponsesTutor(
    private val apiKey: () -> String,
    private val transport: ResponsesTransport = OkHttpResponsesTransport(),
    private val log: EventLog = NoEventLog,
    private val historyLimit: Int = ModelConfig.TUTOR_HISTORY_MESSAGES,
    private val today: () -> Date = ::Date,
) : Tutor {

    /** Сообщение истории: `user` или `assistant`. */
    data class Turn(val role: String, val text: String)

    private val history = mutableListOf<Turn>()
    private var requests = 0

    override suspend fun reply(studentText: String, onEvent: suspend (TutorEvent) -> Unit) = ask(studentText, onEvent)

    override suspend fun farewell(onEvent: suspend (TutorEvent) -> Unit) = ask(INACTIVITY_PROMPT, onEvent)

    override fun newConversation() = history.clear()

    override val lastTeacherText: String?
        get() = history.lastOrNull { it.role == ROLE_ASSISTANT && it.text.isNotBlank() }?.text

    /** История (для тестов). */
    val turns: List<Turn> get() = history.toList()

    /** Сколько сообщений в истории (для журнала; тексты туда не пишутся). */
    val historySize: Int get() = history.size

    private suspend fun ask(userText: String, onEvent: suspend (TutorEvent) -> Unit) {
        val input = trimHistory(history + Turn(ROLE_USER, userText), historyLimit)
        requests++
        log.event(SOURCE, "tutor request $requests: history ${input.size} messages")
        val stream = Stream(userText, onEvent)
        try {
            transport.stream(OpenAiClient.bearer(apiKey()), requestBody(input).toString(), stream::line)
            stream.end()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServiceException) {
            log.event(SOURCE, "tutor stream: failed (${e.message})")
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // OkHttp при отмене бросает IOException «Canceled»
            val c = classify(e)
            log.event(SOURCE, "tutor stream: failed (${c.detail})")
            throw ServiceException(c.kind, c.detail, e)
        }
    }

    /** Тело запроса. */
    fun requestBody(input: List<Turn>): JsonObject = buildJsonObject {
        put("model", ModelConfig.TUTOR_MODEL)
        put("instructions", systemPrompt(today()))
        putJsonArray("input") {
            for (t in input) addJsonObject { put("role", t.role); put("content", t.text) }
        }
        putJsonObject("reasoning") { put("effort", ModelConfig.TUTOR_REASONING_EFFORT) }
        putJsonArray("tools") {
            addJsonObject {
                put("type", "web_search")
                put("search_context_size", ModelConfig.TUTOR_WEB_SEARCH_CONTEXT_SIZE)
            }
        }
        putJsonArray("include") { add("web_search_call.action.sources") }
        put("stream", true)
        put("store", false)
    }

    /** Разбор одного потока ответа (SSE: строки `event:`, `data:`, пустая строка — конец события). */
    private inner class Stream(private val userText: String, private val onEvent: suspend (TutorEvent) -> Unit) {
        private val data = StringBuilder()
        private val text = StringBuilder()
        private val searchesStarted = mutableSetOf<String>()
        private var searchActions = 0
        private val sourceTypes = sortedMapOf<String, Int>()
        private val urls = mutableSetOf<String>()
        private var done = false

        suspend fun line(line: String) {
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
                else -> Unit // `event:`, `id:`, комментарии — тип есть и в самом JSON
            }
        }

        /** Поток кончился: последнее событие без пустой строки и проверка, что ответ дошёл до конца. */
        suspend fun end() {
            dispatch()
            if (!done) throw ServiceException(FailureKind.SERVER, "stream ended before response.completed")
        }

        private suspend fun dispatch() {
            if (data.isEmpty()) return
            val raw = data.toString()
            data.setLength(0)
            if (raw == "[DONE]" || done) return
            val event = try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return
            handle(event)
        }

        private suspend fun handle(e: JsonObject) {
            when (e.str("type")) {
                "response.output_text.delta" -> {
                    val delta = e.str("delta").orEmpty()
                    if (delta.isNotEmpty()) {
                        text.append(delta)
                        onEvent(TutorEvent.Text(delta))
                    }
                }
                "response.output_item.added" -> {
                    val item = e.obj("item") ?: return
                    if (item.str("type") == "web_search_call" && searchesStarted.add(item.str("id") ?: "#${searchesStarted.size}")) {
                        onEvent(TutorEvent.SearchStarted)
                    }
                }
                "response.output_item.done" -> {
                    val item = e.obj("item") ?: return
                    when (item.str("type")) {
                        "web_search_call" -> {
                            val action = item.obj("action")
                            val kind = action?.str("type")
                            if (kind == null || kind == "search") searchActions++
                            (action?.get("sources") as? JsonArray)?.forEach { s ->
                                val type = (s as? JsonObject)?.str("type") ?: "?"
                                sourceTypes[type] = (sourceTypes[type] ?: 0) + 1
                            }
                            onEvent(TutorEvent.SearchFinished)
                        }
                        "message" -> (item["content"] as? JsonArray)?.forEach { part ->
                            ((part as? JsonObject)?.get("annotations") as? JsonArray)?.forEach { citation(it) }
                        }
                    }
                }
                "response.output_text.annotation.added" -> e["annotation"]?.let { citation(it) }
                "response.completed" -> complete(e.obj("response"), incomplete = null)
                "response.incomplete" -> {
                    val reason = e.obj("response")?.obj("incomplete_details")?.str("reason") ?: "?"
                    complete(e.obj("response"), incomplete = reason)
                }
                "response.failed" -> {
                    val code = e.obj("response")?.obj("error")?.str("code")
                    throw ServiceException(kindOf(code), "response.failed (${code ?: "?"})")
                }
                "error" -> {
                    val code = e.str("code")
                    throw ServiceException(kindOf(code), "stream error (${code ?: "?"})")
                }
            }
        }

        private suspend fun citation(a: JsonElement) {
            val o = a as? JsonObject ?: return
            if (o.str("type") != "url_citation") return
            val url = o.str("url") ?: return
            if (!urls.add(url)) return
            onEvent(TutorEvent.Source(TutorSource(o.str("title")?.takeIf { it.isNotBlank() } ?: url, url)))
        }

        private suspend fun complete(response: JsonObject?, incomplete: String?) {
            if (done) return
            done = true
            val usage = response?.obj("usage")
            val input = usage?.int("input_tokens") ?: 0
            val cached = usage?.obj("input_tokens_details")?.int("cached_tokens") ?: 0
            val output = usage?.int("output_tokens") ?: 0
            val stats = TutorStats(input, cached, output, searchActions, sourceTypes.toMap(), cost(input, cached, output, searchActions))
            history += Turn(ROLE_USER, userText)
            history += Turn(ROLE_ASSISTANT, SpeechText.clean(text.toString()).trim())
            trim()
            if (incomplete != null) log.event(SOURCE, "tutor reply incomplete ($incomplete)")
            log.event(SOURCE, statsLine(stats))
            onEvent(TutorEvent.Done(stats))
        }
    }

    private fun trim() {
        val window = trimHistory(history, historyLimit)
        if (window.size != history.size) {
            history.clear()
            history.addAll(window)
        }
    }

    companion object {
        private const val SOURCE = "Network"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"

        /** Правило пустого поиска (S02b, для встроенного поиска — проба S02c): без выдумок. */
        const val NO_DATA_RULE =
            "If web_search finds nothing useful, tell the student honestly, in simple words, that you could " +
                "not find fresh information about it. Do NOT guess. Do NOT give any numbers, prices, dates, " +
                "names, or events about it from memory."

        private const val INACTIVITY_PROMPT =
            "The student has been silent for several minutes. Gently say that you'll take a break and they can click the button to continue later. Keep it very short."

        /** Системный промпт: искать только ради свежих фактов (S02d), дата — в промпте. */
        fun systemPrompt(date: Date): String {
            val currentDate = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.US).format(date)
            return """
                You are a patient English teacher. Today is $currentDate.

                CRITICAL INSTRUCTIONS:
                1. Use web_search ONLY for fresh facts that change over time: prices, news, weather, sports results, schedules, opening hours, times of events (like sunset), and who holds a position now. Do NOT search for normal conversation, grammar, word meanings, or general knowledge.
                2. When you search, use the results and give the concrete facts (numbers, names, dates). Never say that your knowledge stops at some year.
                3. $NO_DATA_RULE
                4. Use short sentences, check understanding, and end with a question.
                5. If the student makes a clear grammatical error or if the transcription suggests a phonetic mistake, briefly and kindly correct it first (e.g., "I think you meant '...', right?"). Then continue the conversation.
            """.trimIndent()
        }

        /** Окно истории: последние [limit] сообщений. Пар «вызов инструмента — ответ» в истории больше нет. */
        fun trimHistory(history: List<Turn>, limit: Int): List<Turn> =
            if (history.size <= limit) history else history.takeLast(limit)

        /** Оценка цены ответа, $ (D40). */
        fun cost(input: Int, cached: Int, output: Int, searches: Int): Double =
            ((input - cached).coerceAtLeast(0) * ModelConfig.TUTOR_PRICE_INPUT_PER_M +
                cached * ModelConfig.TUTOR_PRICE_CACHED_PER_M +
                output * ModelConfig.TUTOR_PRICE_OUTPUT_PER_M) / 1_000_000 +
                searches * ModelConfig.TUTOR_PRICE_PER_SEARCH

        /** Строка журнала об ответе — без текстов и адресов. */
        fun statsLine(s: TutorStats): String {
            val sources = if (s.sourceTypes.isEmpty()) "-" else s.sourceTypes.entries.joinToString(", ") { "${it.key}×${it.value}" }
            return "tutor reply: tokens in ${s.inputTokens} (cached ${s.cachedTokens}), out ${s.outputTokens}; " +
                "searches ${s.searches}; sources $sources; cost \$${"%.4f".format(Locale.US, s.costUsd)}"
        }

        /** Код ошибки из потока → тип сбоя (повторять ли и какой фразой сказать). */
        fun kindOf(code: String?): FailureKind = when {
            code == null -> FailureKind.SERVER
            "insufficient_quota" in code -> FailureKind.QUOTA
            "rate_limit" in code -> FailureKind.RATE_LIMIT
            code.startsWith("invalid") -> FailureKind.BAD_REQUEST
            else -> FailureKind.SERVER
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
