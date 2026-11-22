package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.SpeechAudio
import com.example.listeningtrainer.conversation.SynthesisException
import com.example.listeningtrainer.conversation.SynthesizedSpeech
import com.example.listeningtrainer.conversation.Synthesizer
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Синтез на Realtime API (D8, D9): `gpt-realtime-2.1-mini` в роли читающего движка.
 *
 * Одна WebSocket-сессия на время жизни сервиса: открывается заранее ([prepare] — при старте сервиса
 * и в начале каждой записи ученика), после обрыва переоткрывается при следующем [prepare] или
 * [synthesize], закрывается в [release]. Ответы на группы идут по одному: сколько их можно вести
 * параллельно, документация не говорит, а группа готова за ~0,5 с — быстрее, чем звучит.
 *
 * Недоступность (нет сети, 401, 429, обрыв, таймаут) — [SynthesisException] с `unavailable = true`;
 * ответ без звука или с ошибкой модели — `unavailable = false` (повтор группы решает конвейер).
 * Если ответ прерван (Stop, Back), сокет закрывается: незаконченный ответ не смешается со следующим.
 *
 * В журнал (`Network`) — время подключения, время ответа и токены из `response.done`; ключ и тексты
 * не пишутся.
 */
class RealtimeSynthesizer(
    private val apiKey: () -> String,
    private val log: EventLog,
    baseClient: OkHttpClient = OpenAiClient.http,
    private val clock: () -> Long = System::currentTimeMillis,
) : Synthesizer {

    override val name = "realtime"

    private val client = baseClient.newBuilder()
        .pingInterval(ModelConfig.SPEECH_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    /** Один ответ за раз. */
    private val mutex = Mutex()
    private val lock = Any()
    private var connection: Connection? = null
    private val counter = AtomicInteger()

    override fun prepare() {
        synchronized(lock) {
            val c = connection
            if (c != null && clock() - c.openedAt > ModelConfig.SPEECH_SESSION_RENEW_MS && !mutex.isLocked) {
                c.close("session renewal")
            }
        }
        connect()
    }

    override suspend fun synthesize(text: String): SynthesizedSpeech = mutex.withLock {
        val c = connect()
        try {
            withTimeout(ModelConfig.SPEECH_CONNECT_TIMEOUT_MS) { c.ready.await() }
        } catch (e: TimeoutCancellationException) {
            c.close("connect timeout")
            throw SynthesisException("connect timeout", unavailable = true)
        }
        while (c.events.tryReceive().isSuccess) { /* старые события — не наши */ }

        val tag = "g${counter.incrementAndGet()}"
        val started = clock()
        if (!c.ws.send(RealtimeEvents.responseCreate(text, tag))) {
            c.close("send failed")
            throw SynthesisException("send failed", unavailable = true)
        }
        val collector = RealtimeEvents.ResponseCollector(tag)
        var firstAudioMs = -1L
        var finished = false
        try {
            val outcome = withTimeout(ModelConfig.SPEECH_RESPONSE_TIMEOUT_MS) {
                var result: RealtimeEvents.Outcome? = null
                while (result == null) {
                    val event = c.events.receive() // сокет закрылся — SynthesisException
                    if (firstAudioMs < 0 && RealtimeEvents.type(event) == "response.output_audio.delta") {
                        firstAudioMs = clock() - started
                    }
                    result = collector.onEvent(event)
                }
                result
            }
            finished = true
            when (outcome) {
                is RealtimeEvents.Outcome.Done -> {
                    log.event(
                        SOURCE,
                        "realtime group: first audio $firstAudioMs ms, done ${clock() - started} ms, " +
                            (outcome.usage?.toString() ?: "no usage"),
                    )
                    SynthesizedSpeech(SpeechAudio(outcome.pcm, ModelConfig.SPEECH_SAMPLE_RATE), outcome.transcript)
                }
                is RealtimeEvents.Outcome.Failed -> {
                    log.event(SOURCE, "realtime group failed after ${clock() - started} ms: ${outcome.reason}")
                    throw SynthesisException(outcome.reason, outcome.unavailable)
                }
            }
        } catch (e: TimeoutCancellationException) {
            c.close("response timeout")
            throw SynthesisException("response timeout", unavailable = true)
        } finally {
            if (!finished) c.close("response interrupted")
        }
    }

    override fun release() {
        synchronized(lock) { connection?.close("service stopped") }
    }

    /** Текущая сессия или новая, если её нет. */
    private fun connect(): Connection = synchronized(lock) {
        connection ?: Connection().also { connection = it }
    }

    /** Одна WebSocket-сессия. После закрытия не используется — создаётся новая. */
    private inner class Connection : WebSocketListener() {
        val openedAt = clock()
        val ready = CompletableDeferred<Unit>()
        val events = Channel<JsonObject>(Channel.UNLIMITED)
        @Volatile
        private var closed = false
        val ws: WebSocket = client.newWebSocket(
            Request.Builder()
                .url(ModelConfig.SPEECH_REALTIME_URL + ModelConfig.SPEECH_MODEL)
                .header("Authorization", OpenAiClient.bearer(apiKey()))
                .build(),
            this,
        )

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(RealtimeEvents.sessionUpdate())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = RealtimeEvents.parse(text) ?: return
            when (RealtimeEvents.type(event)) {
                "session.updated" -> if (!ready.isCompleted) {
                    log.event(SOURCE, "realtime session ready in ${clock() - openedAt} ms")
                    ready.complete(Unit)
                }
                "session.created" -> {}
                else -> if (!ready.isCompleted && RealtimeEvents.type(event) == "error") {
                    val reason = (RealtimeEvents.ResponseCollector("").onEvent(event) as? RealtimeEvents.Outcome.Failed)?.reason
                    close("session error: $reason")
                } else {
                    events.trySend(event)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            // Неверный ключ: сервер закрывает кодом 3000 с причиной invalid_api_key (проба S00b).
            val why = if ("invalid_api_key" in reason) "invalid api key" else "closed by server, code $code"
            markClosed(why)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = markClosed("closed, code $code")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val why = response?.code?.let { "http $it" } ?: "network: ${t.javaClass.simpleName}"
            markClosed(why)
        }

        /** Закрыть по своей инициативе. */
        fun close(reason: String) {
            if (closed) return
            ws.cancel()
            markClosed(reason)
        }

        private fun markClosed(reason: String) {
            synchronized(lock) {
                if (closed) return
                closed = true
                if (connection === this) connection = null
            }
            log.event(SOURCE, "realtime session closed: $reason (open ${clock() - openedAt} ms)")
            val error = SynthesisException(reason, unavailable = true)
            ready.completeExceptionally(error)
            events.close(error)
        }
    }

    private companion object {
        const val SOURCE = "Network"
    }
}
