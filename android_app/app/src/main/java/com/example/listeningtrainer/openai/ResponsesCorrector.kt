package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.Corrector
import com.example.listeningtrainer.conversation.CorrectorAnswer
import com.example.listeningtrainer.conversation.FailureKind
import com.example.listeningtrainer.conversation.ServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Поправщик на Responses API (S02f; образец — `tools/probe/corrector.py`, вариант B): `POST /v1/responses`
 * без потока, без инструментов, `reasoning.effort: none`, `store: false`, одна реплика ученика без истории.
 * Промпт — файл ресурса `corrector_prompt.txt` (как промпт учителя, D46). Ответ — исправленная фраза или `OK`.
 *
 * Документация OpenAI (прочитана 2026-11-24): developers.openai.com/api/reference/resources/responses —
 * без `stream` приходит объект ответа: `output[]`, у сообщения (`type: message`) — части `output_text` с `text`;
 * `usage` — `input_tokens`, `input_tokens_details.cached_tokens`, `output_tokens`; `status`.
 *
 * Журнал пишет контроллер (итог поправки известен только ему); здесь — ничего, тексты — никогда.
 */
class ResponsesCorrector(
    private val apiKey: () -> String,
    private val transport: ResponsesTransport = OkHttpResponsesTransport(
        http = OpenAiClient.correctorHttp,
        accept = "application/json",
    ),
) : Corrector {

    override suspend fun correct(studentText: String): CorrectorAnswer {
        val body = StringBuilder()
        try {
            transport.stream(OpenAiClient.bearer(apiKey()), requestBody(studentText).toString()) { line ->
                body.append(line).append('\n')
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val c = classify(e)
            throw ServiceException(c.kind, c.detail, e)
        }
        return parse(body.toString())
    }

    /** Тело запроса. */
    fun requestBody(studentText: String): JsonObject = buildJsonObject {
        put("model", ModelConfig.CORRECTOR_MODEL)
        put("instructions", prompt)
        putJsonArray("input") { addJsonObject { put("role", "user"); put("content", studentText) } }
        putJsonObject("reasoning") { put("effort", ModelConfig.CORRECTOR_REASONING_EFFORT) }
        put("max_output_tokens", ModelConfig.CORRECTOR_MAX_OUTPUT_TOKENS)
        put("store", false)
    }

    companion object {
        const val PROMPT_RESOURCE = "/corrector_prompt.txt"

        val prompt: String by lazy {
            val stream = ResponsesCorrector::class.java.getResourceAsStream(PROMPT_RESOURCE)
                ?: error("corrector prompt resource $PROMPT_RESOURCE is missing")
            stream.use { it.readBytes().toString(Charsets.UTF_8) }.trim()
        }

        /** Ответ сервера → текст и цена. Нет текста или ответ не `completed` — сбой. */
        fun parse(raw: String): CorrectorAnswer {
            val o = try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: throw ServiceException(FailureKind.SERVER, "corrector: not a JSON object")
            val status = o.str("status")
            if (status != null && status != "completed") throw ServiceException(FailureKind.SERVER, "corrector: status $status")
            val text = buildString {
                (o["output"] as? JsonArray)?.forEach { item ->
                    val it = item as? JsonObject ?: return@forEach
                    if (it.str("type") != "message") return@forEach
                    (it["content"] as? JsonArray)?.forEach { part ->
                        val p = part as? JsonObject
                        if (p?.str("type") == "output_text") append(p.str("text").orEmpty())
                    }
                }
            }
            if (text.isBlank()) throw ServiceException(FailureKind.SERVER, "corrector: empty answer")
            val usage = o["usage"] as? JsonObject
            val input = usage?.int("input_tokens") ?: 0
            val cached = (usage?.get("input_tokens_details") as? JsonObject)?.int("cached_tokens") ?: 0
            val output = usage?.int("output_tokens") ?: 0
            return CorrectorAnswer(text.trim(), ResponsesTutor.cost(input, cached, output, searches = 0))
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
