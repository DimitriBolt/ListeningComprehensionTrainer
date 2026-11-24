package com.example.listeningtrainer.openai

import com.example.listeningtrainer.conversation.FailureKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Настоящий OkHttp против локального сервера: строки потока по мере прихода, ошибка HTTP, отмена. */
class OkHttpResponsesTransportTest {

    /** Один запрос: заголовки и тело запроса → [respond] пишет ответ целиком (с заголовками). */
    private class OneShotServer(respond: (headers: List<String>, body: String, out: OutputStream) -> Unit) {
        private val socket = ServerSocket(0)
        val url = "http://127.0.0.1:${socket.localPort}/v1/responses"
        init {
            thread(isDaemon = true) {
                runCatching {
                    socket.accept().use { s ->
                        val input = s.getInputStream().bufferedReader()
                        val headers = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val body = CharArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }
                        respond(headers, String(body), s.getOutputStream())
                    }
                }
            }
        }
        fun close() = socket.close()
    }

    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private var server: OneShotServer? = null

    @After
    fun stop() { server?.close() }

    private fun OutputStream.line(s: String) { write((s + "\r\n").toByteArray()); flush() }

    @Test
    fun `lines arrive one by one, body and auth are sent`() = runBlocking(Dispatchers.Default) {
        var headers = emptyList<String>()
        var body = ""
        server = OneShotServer { h, b, out ->
            headers = h; body = b
            out.line("HTTP/1.1 200 OK"); out.line("Content-Type: text/event-stream"); out.line("Connection: close"); out.line("")
            for (l in listOf("event: a", "data: {\"x\":1}", "", "data: {\"x\":2}", "")) out.write((l + "\n").toByteArray()).also { out.flush() }
        }
        val lines = mutableListOf<String>()
        OkHttpResponsesTransport(http, server!!.url).stream("Bearer sk-test", "{\"a\":1}") { lines += it }
        assertEquals(listOf("event: a", "data: {\"x\":1}", "", "data: {\"x\":2}", ""), lines)
        assertTrue(headers.contains("Authorization: Bearer sk-test"))
        assertEquals("{\"a\":1}", body)
    }

    @Test
    fun `http error becomes HttpException with its body for classify`() = runBlocking(Dispatchers.Default) {
        server = OneShotServer { _, _, out ->
            val b = "{\"error\":{\"code\":\"insufficient_quota\"}}"
            out.line("HTTP/1.1 429 Too Many Requests"); out.line("Content-Length: ${b.length}"); out.line("Connection: close"); out.line("")
            out.write(b.toByteArray()); out.flush()
        }
        try {
            OkHttpResponsesTransport(http, server!!.url).stream("Bearer x", "{}") {}
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(FailureKind.QUOTA, classify(e).kind)
        }
    }

    @Test
    fun `cancel stops a hanging stream at once`() = runBlocking(Dispatchers.Default) {
        val release = CountDownLatch(1)
        server = OneShotServer { _, _, out ->
            out.line("HTTP/1.1 200 OK"); out.line("Connection: close"); out.line(""); out.line("data: first")
            release.await(10, TimeUnit.SECONDS) // сервер «висит»
        }
        val lines = mutableListOf<String>()
        val started = System.nanoTime()
        val job = launch { OkHttpResponsesTransport(http, server!!.url).stream("Bearer x", "{}") { lines += it } }
        withTimeout(3000) { while (lines.isEmpty()) delay(10) }
        job.cancel()
        withTimeout(2000) { job.join() }
        release.countDown()
        assertTrue(job.isCancelled)
        assertEquals(listOf("data: first"), lines)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 4000)
    }
}
