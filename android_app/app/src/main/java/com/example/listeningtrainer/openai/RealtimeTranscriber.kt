package com.example.listeningtrainer.openai

import com.example.listeningtrainer.audio.Resampler16To24
import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.LiveResult
import com.example.listeningtrainer.conversation.LiveStats
import com.example.listeningtrainer.conversation.LiveTranscriber
import com.example.listeningtrainer.conversation.LiveTurn
import com.example.listeningtrainer.eventlog.EventLog
import com.example.listeningtrainer.eventlog.NoEventLog
import com.example.listeningtrainer.openai.LiveTranscriptionEvents.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** WebSocket сессии транскрипции; в тестах — подделка. */
interface LiveSocket {
    /** `false` — сокет уже закрыт. До открытия сообщения копятся и уходят сразу после него (OkHttp). */
    fun send(text: String): Boolean
    fun cancel()
}

interface LiveSocketListener {
    fun onMessage(text: String)
    /** Сокет закрыт сервером или сетью. */
    fun onClosed(reason: String)
}

fun interface LiveSocketFactory {
    fun open(listener: LiveSocketListener): LiveSocket
}

/** Настоящий сокет: OkHttp, `wss://…/realtime?intent=transcription`, ключ — в заголовке. */
class OkHttpLiveSocketFactory(
    private val apiKey: () -> String,
    baseClient: OkHttpClient = OpenAiClient.http,
    private val url: String = ModelConfig.LIVE_TRANSCRIBE_URL,
) : LiveSocketFactory {
    private val client = baseClient.newBuilder()
        .pingInterval(ModelConfig.SPEECH_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    override fun open(listener: LiveSocketListener): LiveSocket {
        val ws = client.newWebSocket(
            Request.Builder().url(url).header("Authorization", OpenAiClient.bearer(apiKey())).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    listener.onClosed(if ("invalid_api_key" in reason) "invalid api key" else "closed by server, code $code")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed("closed, code $code")
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    listener.onClosed(response?.code?.let { "http $it" } ?: "network: ${t.javaClass.simpleName}")
            },
        )
        return object : LiveSocket {
            override fun send(text: String) = ws.send(text)
            override fun cancel() = ws.cancel()
        }
    }
}

/**
 * Потоковое распознавание (S03d, D67): `gpt-live-transcribe`, сессия транскрипции Realtime.
 *
 * **Одна сессия на весь разговор.** Открывается заранее ([prepare] — при старте сервиса и в начале каждого
 * хода), служит реплике за репликой: перед репликой — `session.update` с её `prompt`, в конце — `commit`
 * (речь была) или `clear` (не было). Так соединение не добавляет задержку ни к одной реплике, кроме первой
 * после обрыва. Сессия обновляется между репликами через [renewMs] (живёт до 60 мин); после сбоя
 * закрывается, следующая реплика откроет новую.
 *
 * Звук, записанный, пока сессия открывается, копится в очереди сокета и уходит сразу после открытия.
 * События реплики узнаются по `item_id`: дельты, `committed` и `completed` опоздавшей прошлой реплики к
 * новой не попадают.
 *
 * В журнал — только открытие и закрытие сессии; итог реплики пишет контроллер. Ключ и тексты не пишутся.
 */
class RealtimeTranscriber(
    private val sockets: LiveSocketFactory,
    private val log: EventLog = NoEventLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val textTimeoutMs: Long = ModelConfig.LIVE_TEXT_TIMEOUT_MS,
    private val renewMs: Long = ModelConfig.LIVE_SESSION_RENEW_MS,
) : LiveTranscriber {

    private val lock = Any()
    private var session: Session? = null

    override fun prepare() {
        synchronized(lock) {
            val s = session
            if (s != null && s.turns.isEmpty() && clock() - s.createdAt > renewMs) s.close("session renewal")
            current()
        }
    }

    override fun begin(prompt: String, onPartial: (String) -> Unit): LiveTurn =
        synchronized(lock) { current().startTurn(prompt, onPartial) }

    override fun release() {
        synchronized(lock) { session?.close("service stopped") }
    }

    private fun current(): Session = synchronized(lock) {
        session ?: Session().also {
            session = it
            it.connect()
        }
    }

    private inner class Session : LiveSocketListener {
        val createdAt = clock()
        /** Когда пришло `session.created`; `null` — ещё не открыта. */
        var openedAt: Long? = null
        var closedReason: String? = null
        lateinit var socket: LiveSocket
        /**
         * Ходы сессии (S04): обычно один; после объявленного конца — два: прошлый отправил `commit` и ждёт текст, новый
         * (продолжение реплики) уже шлёт звук. События раздаются по `item_id` ([owner]).
         */
        val turns = mutableListOf<Turn>()
        var turnsServed = 0
        /** Чей `item_id`; нет в списке — `item_id` прошлых, уже кончившихся ходов (их события не нужны). */
        val owner = HashMap<String, Turn>()
        val seenItems = HashSet<String>()

        fun connect() {
            socket = sockets.open(this)
        }

        override fun onMessage(text: String) {
            val ev = LiveTranscriptionEvents.parse(text)
            synchronized(lock) {
                when (ev) {
                    Event.Created -> if (openedAt == null) {
                        openedAt = clock()
                        log.event(SOURCE, "live transcription session open in ${clock() - createdAt} ms")
                        turns.toList().forEach { it.onReady() }
                    }
                    is Event.Delta -> deltaOwner(ev.itemId ?: "-")?.onEvent(ev)
                    is Event.Committed -> {
                        val id = ev.itemId ?: "-"
                        seenItems += id
                        // `commit` подтверждается по порядку: это item самого раннего хода, который его ждёт.
                        val t = turns.firstOrNull { it.awaitsCommitted() } ?: return
                        owner[id]?.takeIf { it !== t }?.dropItem(id)
                        owner[id] = t
                        t.onEvent(ev)
                    }
                    else -> turns.toList().forEach { it.onEvent(ev) }
                }
                Unit
            }
        }

        /** Под [lock]. Дельта нового `item_id` — ходу, который сейчас пишет звук (последнему). */
        private fun deltaOwner(id: String): Turn? {
            owner[id]?.let { return it.takeIf { t -> t in turns } }
            if (id in seenItems) return null // item прошлого хода
            seenItems += id
            val t = turns.lastOrNull { !it.isEnded } ?: turns.lastOrNull() ?: return null
            owner[id] = t
            return t
        }

        override fun onClosed(reason: String) = markClosed(reason)

        fun close(reason: String) {
            synchronized(lock) {
                if (closedReason != null) return
                markClosed(reason)
                socket.cancel()
            }
        }

        private fun markClosed(reason: String) {
            synchronized(lock) {
                if (closedReason != null) return
                closedReason = reason
                if (session === this) session = null
                log.event(SOURCE, "live transcription session closed: $reason (open ${clock() - createdAt} ms)")
                turns.toList().forEach { it.fail("closed: $reason", closeSession = false) }
            }
        }

        fun startTurn(prompt: String, onPartial: (String) -> Unit): Turn {
            // Ход, который ещё пишет звук, заменяется; ход после `commit` дожидается своего текста (S04).
            turns.filter { !it.isEnded }.forEach { it.fail("replaced by a new turn", closeSession = false) }
            val t = Turn(this, reused = turnsServed > 0, onPartial = onPartial)
            turnsServed++
            turns += t
            val closed = closedReason
            when {
                closed != null -> t.fail("session closed: $closed", closeSession = false)
                !socket.send(LiveTranscriptionEvents.sessionUpdate(prompt)) -> t.fail("send failed")
                openedAt != null -> t.onReady()
            }
            return t
        }
    }

    private inner class Turn(
        private val s: Session,
        private val reused: Boolean,
        private val onPartial: (String) -> Unit,
    ) : LiveTurn {
        private val startedAt = clock()
        private var readyAt: Long? = null
        private val resampler = Resampler16To24()
        @Volatile private var sentSamples = 0L
        private var ended = false
        val isEnded: Boolean get() = ended
        /** Под [lock]: `commit` отправлен, его `committed` ещё не пришёл. */
        fun awaitsCommitted() = commitAt != null && committedId == null && !done.isCompleted
        private var commitAt: Long? = null
        private var committedId: String? = null
        private var early: Event.Completed? = null
        /** Дельты реплики по `item_id` в порядке прихода (обычно один). */
        private val parts = LinkedHashMap<String, StringBuilder>()
        private var deltas = 0
        private val done = CompletableDeferred<LiveResult>()

        fun onReady() {
            if (readyAt == null) readyAt = clock()
        }

        override fun onPcm(pcm: ShortArray, count: Int) {
            synchronized(lock) { if (ended || done.isCompleted) return }
            val out = resampler.process(pcm, count)
            if (out.isEmpty()) return
            if (!s.socket.send(LiveTranscriptionEvents.append(out))) {
                synchronized(lock) { fail("send failed") }
                return
            }
            sentSamples += out.size
        }

        override fun onEnd(heardSpeech: Boolean) {
            synchronized(lock) {
                if (ended) return
                ended = true
                if (done.isCompleted) return
                if (heardSpeech) {
                    commitAt = clock()
                    if (!s.socket.send(LiveTranscriptionEvents.COMMIT)) fail("send failed")
                } else {
                    s.socket.send(LiveTranscriptionEvents.CLEAR)
                    fail("no speech: cleared", closeSession = false)
                }
            }
        }

        override suspend fun result(): LiveResult {
            val deadline = synchronized(lock) {
                if (done.isCompleted) return done.getCompleted()
                val at = commitAt ?: run {
                    fail("not committed", closeSession = false)
                    return done.getCompleted()
                }
                at + textTimeoutMs
            }
            withTimeoutOrNull((deadline - clock()).coerceAtLeast(0)) { done.await() }?.let { return it }
            synchronized(lock) {
                if (!done.isCompleted) {
                    fail(if (s.openedAt == null) "session not opened in ${clock() - startedAt} ms" else "no text in $textTimeoutMs ms")
                }
                return done.getCompleted()
            }
        }

        override fun cancel() {
            synchronized(lock) {
                if (!ended) {
                    ended = true
                    s.socket.send(LiveTranscriptionEvents.CLEAR)
                }
                fail("cancelled", closeSession = false)
            }
        }

        /** Под [lock]: item оказался чужим (его `committed` подтвердил другой ход). */
        fun dropItem(id: String) {
            if (parts.remove(id) != null) onPartial(parts.values.joinToString("").trim())
        }

        /** Под [lock]. Дельты и `committed` сюда приходят уже своими ([Session.owner]); остальное — всем ходам. */
        fun onEvent(ev: Event) {
            if (done.isCompleted) return
            when (ev) {
                is Event.Delta -> {
                    val id = ev.itemId ?: "-"
                    parts.getOrPut(id) { StringBuilder() }.append(ev.delta)
                    deltas++
                    onPartial(parts.values.joinToString("").trim())
                }
                is Event.Committed -> if (awaitsCommitted()) {
                    committedId = ev.itemId
                    early?.takeIf { it.itemId == ev.itemId }?.let(::complete)
                }
                is Event.Completed -> when {
                    // `completed` раньше `committed` — только у хода, который ждёт подтверждения, и только своего item
                    committedId == null -> if (awaitsCommitted() && s.owner[ev.itemId ?: "-"].let { it == null || it === this }) early = ev
                    ev.itemId == committedId -> complete(ev)
                }
                is Event.Failed -> if (ev.itemId == committedId || ev.itemId in parts) fail(ev.reason)
                is Event.Error -> fail(ev.reason)
                else -> {}
            }
        }

        private fun complete(ev: Event.Completed) {
            val text = ev.transcript.trim()
            val cost = (ev.seconds ?: 0.0) * ModelConfig.LIVE_TRANSCRIBE_PRICE_PER_MIN / 60
            done.complete(LiveResult.Text(text, stats(text.length, cost, commitAt?.let { clock() - it } ?: -1)))
            detach()
        }

        /** Под [lock]. Сбой сессии (ошибка сервера, нет текста) закрывает её: следующая реплика — в новой. */
        fun fail(reason: String, closeSession: Boolean = true) {
            if (done.isCompleted) return
            done.complete(LiveResult.Failed(reason, stats(0, 0.0, -1)))
            detach()
            if (closeSession) s.close(reason)
        }

        private fun detach() {
            s.turns.remove(this)
        }

        private fun stats(chars: Int, cost: Double, commitToTextMs: Long) = LiveStats(
            sessionWaitMs = ((readyAt ?: clock()) - startedAt).coerceAtLeast(0),
            reused = reused,
            audioSentMs = sentSamples * 1000 / ModelConfig.LIVE_TRANSCRIBE_SAMPLE_RATE,
            commitToTextMs = commitToTextMs,
            deltas = deltas,
            chars = chars,
            costUsd = cost,
        )
    }

    private companion object {
        const val SOURCE = "Network"
    }
}
