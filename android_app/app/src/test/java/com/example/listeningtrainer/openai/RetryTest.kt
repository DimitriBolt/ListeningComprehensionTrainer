package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.FailureKind
import com.example.listeningtrainer.conversation.ServiceException
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest {

    private class Log : EventLog {
        val lines = mutableListOf<String>()
        override fun event(source: String, message: String) { lines += "[$source] $message" }
        override fun error(source: String, message: String, error: Throwable?) { lines += "[$source] ERROR $message" }
    }

    private fun http(code: Int, body: String = "{}") =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    private suspend fun expectFailure(block: suspend () -> Unit): ServiceException {
        try {
            block()
        } catch (e: ServiceException) {
            return e
        }
        fail("ServiceException expected")
        error("unreachable")
    }

    @Test
    fun `classification of errors`() {
        assertEquals(FailureKind.KEY, classify(http(401)).kind)
        assertEquals(FailureKind.KEY, classify(http(403)).kind)
        assertEquals(FailureKind.QUOTA, classify(http(429, """{"error":{"code":"insufficient_quota"}}""")).kind)
        assertEquals(FailureKind.RATE_LIMIT, classify(http(429, """{"error":{"code":"rate_limit_exceeded"}}""")).kind)
        assertEquals(FailureKind.SERVER, classify(http(500)).kind)
        assertEquals(FailureKind.SERVER, classify(http(503)).kind)
        assertEquals(FailureKind.BAD_REQUEST, classify(http(400)).kind)
        assertEquals(FailureKind.NO_NETWORK, classify(UnknownHostException("api.openai.com")).kind)
        assertEquals(FailureKind.TIMEOUT, classify(SocketTimeoutException()).kind)
        assertEquals(FailureKind.TIMEOUT, classify(InterruptedIOException("timeout")).kind)
        assertEquals(FailureKind.SERVER, classify(IOException("unexpected end of stream")).kind)
        assertEquals(FailureKind.OTHER, classify(IllegalStateException()).kind)
    }

    @Test
    fun `retry happens for network, timeout, 5xx and 429, not for 400, 401 and quota`() {
        val retried = FailureKind.values().filter { it.retry }.toSet()
        assertEquals(setOf(FailureKind.NO_NETWORK, FailureKind.TIMEOUT, FailureKind.SERVER, FailureKind.RATE_LIMIT), retried)
    }

    @Test
    fun `success first time - one call, nothing logged`() = runTest {
        val log = Log()
        var calls = 0
        assertEquals("ok", withRetry("transcription", log) { calls++; "ok" })
        assertEquals(1, calls)
        assertTrue(log.lines.isEmpty())
    }

    @Test
    fun `timeout then success - one retry after a 1 s pause, logged`() = runTest {
        val log = Log()
        var calls = 0
        val result = withRetry("tutor request 1", log) {
            calls++
            if (calls == 1) throw SocketTimeoutException("timeout")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals(ModelConfig.RETRY_PAUSE_MS, currentTime)
        assertEquals(
            listOf(
                "[Network] tutor request 1: failed (SocketTimeoutException), retry in 1000 ms",
                "[Network] tutor request 1: retry succeeded",
            ),
            log.lines,
        )
    }

    @Test
    fun `two failures - ServiceException with the kind of the last one`() = runTest {
        val log = Log()
        var calls = 0
        val e = expectFailure {
            withRetry("transcription", log) {
                calls++
                if (calls == 1) throw UnknownHostException() else throw http(503)
            }
        }
        assertEquals(2, calls)
        assertEquals(FailureKind.SERVER, e.kind)
        assertEquals("[Network] transcription: retry failed (http 503)", log.lines.last())
    }

    @Test
    fun `401 and 400 - no retry`() = runTest {
        for ((error, kind) in listOf(http(401) to FailureKind.KEY, http(400) to FailureKind.BAD_REQUEST)) {
            val log = Log()
            var calls = 0
            val e = expectFailure { withRetry("transcription", log) { calls++; throw error } }
            assertEquals(1, calls)
            assertEquals(kind, e.kind)
            assertTrue(log.lines.single().endsWith("no retry"))
        }
    }

    @Test
    fun `cancellation is not a failure and is not retried`() = runTest {
        val log = Log()
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val job = launch {
            withRetry("transcription", log) {
                calls++
                started.complete(Unit)
                CompletableDeferred<Unit>().await() // ждёт, пока не отменят
            }
        }
        started.await()
        job.cancel()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(log.lines.isEmpty())
    }

    @Test
    fun `OkHttp Canceled IOException after cancel is not retried`() = runTest {
        val log = Log()
        var calls = 0
        val job = launch {
            withRetry("transcription", log) {
                calls++
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                throw IOException("Canceled")
            }
        }
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
        assertTrue(log.lines.isEmpty())
    }

    @Test
    fun `call timeouts are set per kind of call`() {
        assertEquals(25_000, OpenAiClient.transcribeHttp.callTimeoutMillis)
        assertEquals(20_000, OpenAiClient.tutorHttp.callTimeoutMillis)
        assertEquals(0, OpenAiClient.http.callTimeoutMillis) // WebSocket Realtime — без общего предела
    }

    @Test
    fun `cancelled call is logged as cancelled, not failed`() {
        val e = IOException("Canceled")
        assertEquals("POST x cancelled after 120 ms", NetworkLogInterceptor.failureLine("POST x", 120, e, canceled = true))
        assertEquals("POST x failed after 120 ms: IOException", NetworkLogInterceptor.failureLine("POST x", 120, e, canceled = false))
    }
}
