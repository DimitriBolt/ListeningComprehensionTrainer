package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.eventlog.EventLog
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Пишет в журнал каждый сетевой вызов: метод, адрес, код ответа, длительность до заголовков ответа.
 * Путь пишется только для OpenAI; от других адресов — только хост (в них могут быть слова ученика).
 * Заголовки (в том числе ключ) и тела не пишутся.
 */
class NetworkLogInterceptor(private val log: EventLog) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val what = describe(request)
        val started = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            log.event(SOURCE, failureLine(what, elapsedMs(started), e, chain.call().isCanceled()))
            throw e
        }
        log.event(SOURCE, "$what -> ${response.code} in ${elapsedMs(started)} ms")
        return response
    }

    private fun elapsedMs(startedNs: Long) = (System.nanoTime() - startedNs) / 1_000_000

    companion object {
        private const val SOURCE = "Network"
        private val OPENAI_HOST = ModelConfig.OPENAI_BASE_URL.toHttpUrl().host

        /**
         * Строка о неудачном вызове. Отмена пользователем («Stop», «Back») — `cancelled`, а не
         * `failed … IOException`: так она не путается с настоящими сбоями.
         */
        fun failureLine(what: String, ms: Long, e: Exception, canceled: Boolean): String =
            if (canceled) "$what cancelled after $ms ms" else "$what failed after $ms ms: ${e.javaClass.simpleName}"

        /** «POST api.openai.com/v1/audio/transcriptions, up 96 KB» или «GET example.com». */
        fun describe(request: Request): String {
            val url = request.url
            val where = if (url.host == OPENAI_HOST) url.host + url.encodedPath else url.host
            val size = try { request.body?.contentLength() ?: -1L } catch (e: Exception) { -1L }
            val upload = if (size >= 0) ", up ${(size + 1023) / 1024} KB" else ""
            return "${request.method} $where$upload"
        }
    }
}
