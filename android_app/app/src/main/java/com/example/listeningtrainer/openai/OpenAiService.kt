@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Streaming

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    val tool_choice: String? = null
)

@Serializable
data class Message(
    val role: String,
    val content: String? = null,
    val tool_calls: List<ToolCall>? = null,
    val tool_call_id: String? = null,
    val name: String? = null
)

@Serializable
data class Tool(
    val type: String = "function",
    val function: FunctionDef
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonElement // Схема параметров в формате JSON Schema
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String,
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String // JSON-строка с аргументами
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>
)

@Serializable
data class Choice(
    val message: Message
)

@Serializable
data class TtsRequest(
    val model: String = ModelConfig.TTS_MODEL,
    val input: String,
    val voice: String = ModelConfig.TTS_VOICE,
    val response_format: String = ModelConfig.TTS_FORMAT,
    val speed: Float = ModelConfig.TTS_SPEED
)

@Serializable
data class TranscriptionResponse(
    val text: String
)

interface OpenAiService {
    @POST("v1/chat/completions")
    suspend fun checkKey(
        @Header("Authorization") authHeader: String,
        @Body request: ChatRequest
    ): ChatResponse

    @Streaming
    @POST("v1/audio/speech")
    suspend fun generateSpeech(
        @Header("Authorization") authHeader: String,
        @Body request: TtsRequest
    ): ResponseBody

    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribeAudio(
        @Header("Authorization") authHeader: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody
    ): TranscriptionResponse
}
