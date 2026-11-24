@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.example.listeningtrainer.openai

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

@Serializable
data class TranscriptionResponse(
    val text: String
)

interface OpenAiService {
    /** Проверка ключа: список моделей — бесплатно, без запроса к модели. */
    @GET("v1/models")
    suspend fun listModels(@Header("Authorization") authHeader: String): ResponseBody

    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribeAudio(
        @Header("Authorization") authHeader: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("languages[]") language: RequestBody,
        @Part("prompt") prompt: RequestBody,
    ): TranscriptionResponse
}
