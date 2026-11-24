package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import java.io.IOException

/** Поток ответа учителя: тело запроса → строки ответа (SSE). Подменяется в тестах. */
fun interface ResponsesTransport {
    /**
     * POST [body] на `/v1/responses`; каждую строку ответа — в [onLine], в контексте вызывающего.
     * Ответ не 2xx — [HttpException] (тип сбоя определит [classify]). Отмена корутины обрывает вызов.
     */
    suspend fun stream(auth: String, body: String, onLine: suspend (String) -> Unit)
}

/** [ResponsesTransport] на OkHttp: общий предел на весь поток — `OpenAiClient.tutorHttp`. */
class OkHttpResponsesTransport(private val http: OkHttpClient = OpenAiClient.tutorHttp) : ResponsesTransport {

    override suspend fun stream(auth: String, body: String, onLine: suspend (String) -> Unit) = coroutineScope {
        val request = Request.Builder()
            .url(ModelConfig.TUTOR_URL)
            .header("Authorization", auth)
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON))
            .build()
        val call = http.newCall(request)
        // Чтение блокирующее (на IO): отмена корутины («Stop») обрывает сам вызов.
        val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            val response = withContext(Dispatchers.IO) { call.execute() }
            response.use { r ->
                if (r.code >= 400) {
                    val error = withContext(Dispatchers.IO) { r.body?.string().orEmpty() }
                    throw HttpException(retrofit2.Response.error<Any>(r.code, error.toResponseBody(JSON)))
                }
                if (!r.isSuccessful) throw IOException("http ${r.code}")
                val source = r.body?.source() ?: throw IOException("empty body")
                while (true) {
                    val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                    onLine(line)
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
