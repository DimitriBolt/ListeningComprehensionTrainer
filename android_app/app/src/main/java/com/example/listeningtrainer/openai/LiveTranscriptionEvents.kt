package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.ByteString.Companion.toByteString

/**
 * События сессии транскрипции Realtime для `gpt-live-transcribe` (S03d). Протокол — документация OpenAI
 * «Realtime transcription» и проба S03d (`session.update` с `prompt` перед репликой, `input_audio_buffer.clear`
 * → `cleared`, дельты с `item_id` во время записи, `committed`, `completed` с `usage.seconds`). Чистый Kotlin.
 */
object LiveTranscriptionEvents {

    /** Настройки сессии с контекстом реплики: шлётся перед каждой репликой. */
    fun sessionUpdate(prompt: String): String = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "transcription")
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", ModelConfig.LIVE_TRANSCRIBE_SAMPLE_RATE)
                    }
                    putJsonObject("transcription") {
                        put("model", ModelConfig.LIVE_TRANSCRIBE_MODEL)
                        putJsonArray("languages") { add(ModelConfig.TRANSCRIBE_LANGUAGE) }
                        put("delay", ModelConfig.LIVE_TRANSCRIBE_DELAY)
                        put("prompt", prompt)
                    }
                    put("turn_detection", JsonNull)
                }
            }
        }
    }.toString()

    /** Кусок звука PCM 16 бит little-endian 24 кГц (base64 — okio: `java.util.Base64` нет на Android 7). */
    fun append(pcm24: ShortArray): String {
        val bytes = ByteArray(pcm24.size * 2)
        for (i in pcm24.indices) {
            val v = pcm24[i].toInt()
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return "{\"type\":\"input_audio_buffer.append\",\"audio\":\"${bytes.toByteString().base64()}\"}"
    }

    const val COMMIT = "{\"type\":\"input_audio_buffer.commit\"}"
    const val CLEAR = "{\"type\":\"input_audio_buffer.clear\"}"

    /** Разобранное событие сервера (только нужное). */
    sealed interface Event {
        data object Created : Event
        data object Updated : Event
        data class Delta(val itemId: String?, val delta: String) : Event
        data class Committed(val itemId: String?) : Event
        data class Completed(val itemId: String?, val transcript: String, val seconds: Double?) : Event
        data class Failed(val itemId: String?, val reason: String) : Event
        data class Error(val reason: String) : Event
        data object Other : Event
    }

    fun parse(text: String): Event {
        val e = RealtimeEvents.parse(text) ?: return Event.Other
        val item = e.str("item_id")
        return when (e.str("type")) {
            "session.created" -> Event.Created
            "session.updated" -> Event.Updated
            "conversation.item.input_audio_transcription.delta" -> Event.Delta(item, e.str("delta").orEmpty())
            "input_audio_buffer.committed" -> Event.Committed(item)
            "conversation.item.input_audio_transcription.completed" -> Event.Completed(
                item, e.str("transcript").orEmpty(),
                ((e["usage"] as? JsonObject)?.get("seconds") as? JsonPrimitive)?.doubleOrNull,
            )
            "conversation.item.input_audio_transcription.failed" -> Event.Failed(item, "transcription failed (${errorText(e)})")
            "error" -> Event.Error("error ${errorText(e)}")
            else -> Event.Other
        }
    }

    /** `тип/код` ошибки без сообщения (в сообщении может оказаться что угодно). */
    private fun errorText(e: JsonObject): String {
        val err = e["error"] as? JsonObject
        return "${err?.str("type") ?: "-"}/${err?.str("code") ?: "-"}"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
