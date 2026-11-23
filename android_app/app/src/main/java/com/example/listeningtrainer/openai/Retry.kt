package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.FailureKind
import com.example.listeningtrainer.conversation.ServiceException
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Тип сбоя и причина для журнала (без текстов и без ключа). */
class Classified(val kind: FailureKind, val detail: String)

/**
 * Что за сбой. HTTP 401/403 — ключ, 429 с `insufficient_quota` — деньги, прочие 429 — лимит,
 * 5xx и 408 — сервер, остальные 4xx — неверный запрос. Нет связи — сеть, таймаут — таймаут.
 */
fun classify(e: Throwable): Classified = when (e) {
    is ServiceException -> Classified(e.kind, e.message ?: e.kind.name)
    is HttpException -> {
        val code = e.code()
        val body = try { e.response()?.errorBody()?.string().orEmpty() } catch (ignored: Exception) { "" }
        when {
            code == 401 || code == 403 -> Classified(FailureKind.KEY, "http $code")
            code == 429 && "insufficient_quota" in body -> Classified(FailureKind.QUOTA, "http 429 insufficient_quota")
            code == 429 -> Classified(FailureKind.RATE_LIMIT, "http 429")
            code >= 500 || code == 408 -> Classified(FailureKind.SERVER, "http $code")
            else -> Classified(FailureKind.BAD_REQUEST, "http $code")
        }
    }
    is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLException ->
        Classified(FailureKind.NO_NETWORK, e.javaClass.simpleName)
    // SocketTimeoutException и общий предел вызова OkHttp (InterruptedIOException «timeout»).
    is InterruptedIOException -> Classified(FailureKind.TIMEOUT, e.javaClass.simpleName)
    is SocketException -> Classified(FailureKind.NO_NETWORK, e.javaClass.simpleName)
    is IOException -> Classified(FailureKind.SERVER, e.javaClass.simpleName)
    else -> Classified(FailureKind.OTHER, e.javaClass.simpleName)
}

/**
 * Вызов с одним автоматическим повтором (S02b, А4): при сбое сети, таймауте, 5xx и 429 —
 * пауза [pauseMs] и ещё одна попытка; при 400, 401, 403 и `insufficient_quota` — без повтора.
 * Неудача — [ServiceException] с типом сбоя. Отмена пользователем («Stop», «Back») — не сбой:
 * она пробрасывается как есть и не повторяется. Всё — в журнал строками `[Network] <what>: …`.
 */
suspend fun <T> withRetry(
    what: String,
    log: EventLog,
    pauseMs: Long = ModelConfig.RETRY_PAUSE_MS,
    block: suspend () -> T,
): T {
    val error = try {
        return block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive() // OkHttp при отмене бросает IOException «Canceled»
        e
    }
    val first = classify(error)
    if (!first.kind.retry) {
        log.event(SOURCE, "$what: failed (${first.detail}), no retry")
        throw ServiceException(first.kind, first.detail, error)
    }
    log.event(SOURCE, "$what: failed (${first.detail}), retry in $pauseMs ms")
    delay(pauseMs)
    try {
        return block().also { log.event(SOURCE, "$what: retry succeeded") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        val second = classify(e)
        log.event(SOURCE, "$what: retry failed (${second.detail})")
        throw ServiceException(second.kind, second.detail, e)
    }
}

private const val SOURCE = "Network"
