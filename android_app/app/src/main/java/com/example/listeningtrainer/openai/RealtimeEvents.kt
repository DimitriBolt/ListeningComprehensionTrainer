package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.ByteString.Companion.decodeBase64
import java.io.ByteArrayOutputStream

/**
 * События Realtime API для синтеза (протокол — `tools/probe/probe.py`, `verbatim`; документация
 * OpenAI «Realtime conversations»: out-of-band ответы с `conversation: "none"`). Чистый Kotlin.
 */
object RealtimeEvents {

    /** Сессия читающего движка: только звук PCM 24 кГц, голос, скорость, «Промпт 1», без VAD. */
    fun sessionUpdate(): String = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "realtime")
            put("instructions", ModelConfig.SPEECH_INSTRUCTIONS)
            putJsonArray("output_modalities") { add("audio") }
            putJsonObject("audio") {
                putJsonObject("input") { put("turn_detection", JsonNull) }
                putJsonObject("output") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", ModelConfig.SPEECH_SAMPLE_RATE)
                    }
                    put("voice", ModelConfig.SPEECH_VOICE)
                    put("speed", ModelConfig.SPEECH_SPEED)
                }
            }
        }
    }.toString()

    /** Одна группа — отдельный ответ вне разговора: вход — только текст группы, выход — только звук. */
    fun responseCreate(text: String, tag: String): String = buildJsonObject {
        put("type", "response.create")
        putJsonObject("response") {
            put("conversation", "none")
            putJsonArray("input") {
                add(buildJsonObject {
                    put("type", "message")
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", text)
                        })
                    }
                })
            }
            putJsonArray("output_modalities") { add("audio") }
            putJsonObject("metadata") { put("group", tag) }
        }
    }.toString()

    fun type(event: JsonObject): String? = event.str("type")

    /** Токены из `response.done` — для оценки стоимости. */
    data class Usage(
        val inputText: Int, val inputAudio: Int, val inputCached: Int,
        val outputText: Int, val outputAudio: Int,
    ) {
        override fun toString() =
            "tokens in text $inputText (cached $inputCached) audio $inputAudio, out text $outputText audio $outputAudio"
    }

    sealed interface Outcome {
        class Done(val pcm: ShortArray, val transcript: String?, val usage: Usage?) : Outcome
        /** [unavailable] — дальше этот синтезатор в ответе не использовать (квота, ключ, лимит). */
        class Failed(val reason: String, val unavailable: Boolean) : Outcome
    }

    /** Коды ошибок, после которых Realtime в этом ответе больше не пробовать. */
    private val UNAVAILABLE_CODES = setOf(
        "rate_limit_exceeded", "insufficient_quota", "invalid_api_key", "session_expired",
        "model_not_found", "billing_hard_limit_reached",
    )

    /**
     * Собирает один ответ на группу из событий сервера. [onEvent] возвращает итог, когда ответ
     * закончен (`response.done` или `error`), иначе `null`.
     */
    class ResponseCollector(private val tag: String) {
        private val audio = ByteArrayOutputStream()
        private var transcript: String? = null

        fun onEvent(event: JsonObject): Outcome? = when (type(event)) {
            "response.output_audio.delta" -> {
                event.str("delta")?.decodeBase64()?.let { audio.write(it.toByteArray()) }
                null
            }
            "response.output_audio_transcript.done" -> {
                transcript = event.str("transcript")
                null
            }
            "response.done" -> done(event["response"] as? JsonObject ?: JsonObject(emptyMap()))
            "error" -> {
                val error = event["error"] as? JsonObject
                val code = error?.str("code")
                val kind = error?.str("type")
                Outcome.Failed("error ${kind ?: "-"}/${code ?: "-"}", unavailable = code in UNAVAILABLE_CODES)
            }
            else -> null
        }

        private fun done(response: JsonObject): Outcome? {
            val metaTag = (response["metadata"] as? JsonObject)?.str("group")
            if (metaTag != null && metaTag != tag) return null // конец чужого (старого) ответа
            val status = response.str("status")
            val usage = (response["usage"] as? JsonObject)?.let(::usage)
            if (status != "completed") {
                val err = ((response["status_details"] as? JsonObject)?.get("error") as? JsonObject)
                val code = err?.str("code")
                return Outcome.Failed("status ${status ?: "-"} (${code ?: err?.str("type") ?: "-"})", unavailable = code in UNAVAILABLE_CODES)
            }
            val pcm = pcm16(audio.toByteArray())
            if (pcm.isEmpty()) return Outcome.Failed("completed without audio", unavailable = false)
            return Outcome.Done(pcm, transcript, usage)
        }
    }

    private fun usage(u: JsonObject): Usage {
        val i = u["input_token_details"] as? JsonObject
        val o = u["output_token_details"] as? JsonObject
        return Usage(
            inputText = i.int("text_tokens"), inputAudio = i.int("audio_tokens"), inputCached = i.int("cached_tokens"),
            outputText = o.int("text_tokens"), outputAudio = o.int("audio_tokens"),
        )
    }

    /** PCM 16 бит little-endian → отсчёты. */
    fun pcm16(bytes: ByteArray): ShortArray = ShortArray(bytes.size / 2) { i ->
        ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject?.int(key: String): Int = (this?.get(key) as? JsonPrimitive)?.intOrNull ?: 0

    /** Разбор строки события; не JSON — `null`. */
    fun parse(text: String): JsonObject? = try {
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        null
    }
}
