package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.eventlog.AppLog
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/** Один HTTP-клиент и один Retrofit на процесс: их делят распознавание, учитель, синтез и проверка ключа. */
object OpenAiClient {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ModelConfig.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ModelConfig.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(ModelConfig.WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = ModelConfig.MAX_PARALLEL_REQUESTS
            maxRequestsPerHost = ModelConfig.MAX_PARALLEL_REQUESTS
        })
        .addInterceptor(NetworkLogInterceptor(AppLog))
        .build()

    val api: OpenAiService by lazy {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        Retrofit.Builder()
            .baseUrl(ModelConfig.OPENAI_BASE_URL)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiService::class.java)
    }

    fun bearer(apiKey: String) = "Bearer ${apiKey.trim()}"
}
