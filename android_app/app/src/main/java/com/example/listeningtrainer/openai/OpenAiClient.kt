package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.eventlog.AppLog
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * HTTP-клиенты процесса. Общий [http] (пул соединений один) — для WebSocket Realtime и проверки
 * ключа; у распознавания и учителя — свой общий предел на весь вызов
 * (`callTimeout`, S02b): чтение и запись не дольше него же.
 */
object OpenAiClient {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ModelConfig.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ModelConfig.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(ModelConfig.WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .addInterceptor(NetworkLogInterceptor(AppLog))
        .build()

    /** Клиент с общим пределом [callTimeoutMs] на весь вызов. */
    fun withCallTimeout(callTimeoutMs: Long): OkHttpClient = http.newBuilder()
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    val transcribeHttp: OkHttpClient by lazy { withCallTimeout(ModelConfig.TRANSCRIBE_CALL_TIMEOUT_MS) }
    val tutorHttp: OkHttpClient by lazy { withCallTimeout(ModelConfig.TUTOR_CALL_TIMEOUT_MS) }
    val correctorHttp: OkHttpClient by lazy { withCallTimeout(ModelConfig.CORRECTOR_CALL_TIMEOUT_MS) }

    /** Проверка ключа. */
    val api: OpenAiService by lazy { retrofit(http) }
    val transcribeApi: OpenAiService by lazy { retrofit(transcribeHttp) }

    private fun retrofit(client: OkHttpClient): OpenAiService {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        return Retrofit.Builder()
            .baseUrl(ModelConfig.OPENAI_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiService::class.java)
    }

    fun bearer(apiKey: String) = "Bearer ${apiKey.trim()}"
}
