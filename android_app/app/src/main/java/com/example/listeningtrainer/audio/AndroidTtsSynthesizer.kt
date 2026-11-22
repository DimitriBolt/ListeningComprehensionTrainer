package com.example.listeningtrainer.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.SynthesisException
import com.example.listeningtrainer.conversation.SynthesizedSpeech
import com.example.listeningtrainer.conversation.Synthesizer
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Запасной синтез — встроенный Android `TextToSpeech` (английский, скорость
 * [ModelConfig.FALLBACK_SPEECH_RATE]). Работает без сети (D21), поэтому им же в S02b будут
 * озвучиваться сообщения об ошибках: [synthesize] отдаёт PCM, играет его обычный `AudioOutput`.
 *
 * Звук получается через `synthesizeToFile` (WAV) — так он проходит ту же нормализацию громкости
 * и тот же проигрыватель, что и Realtime. Нет движка или английского голоса — [SynthesisException]
 * с понятной причиной; это ошибка синтеза («ошибка» и «конец»).
 */
class AndroidTtsSynthesizer(
    private val context: Context,
    private val log: EventLog,
) : Synthesizer {

    override val name = "android"

    private val mutex = Mutex()
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<String?>? = null // null — готов, иначе причина ошибки
    @Volatile
    private var initFailed = false
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val counter = AtomicInteger()

    /** Поднять движок заранее: первый запуск TextToSpeech занимает заметное время. */
    override fun prepare() {
        synchronized(this) { if (ready == null) start() }
    }

    private fun start() {
        val started = System.currentTimeMillis()
        val result = CompletableDeferred<String?>()
        ready = result
        initFailed = false
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            val problem = when {
                status != TextToSpeech.SUCCESS || engine == null -> "engine init failed (status $status)"
                else -> when (engine.setLanguage(Locale.US)) {
                    TextToSpeech.LANG_MISSING_DATA -> "no English voice data"
                    TextToSpeech.LANG_NOT_SUPPORTED -> "English not supported by the engine"
                    else -> {
                        engine.setSpeechRate(ModelConfig.FALLBACK_SPEECH_RATE)
                        engine.setOnUtteranceProgressListener(listener)
                        null
                    }
                }
            }
            val engineName = try { engine?.defaultEngine ?: "-" } catch (e: Exception) { "-" }
            if (problem == null) {
                log.event(SOURCE, "android tts ready in ${System.currentTimeMillis() - started} ms (engine $engineName)")
            } else {
                log.event(SOURCE, "android tts unavailable: $problem (engine $engineName)")
            }
            initFailed = problem != null
            result.complete(problem)
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {}
        override fun onDone(utteranceId: String) { pending.remove(utteranceId)?.complete(null) }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) { pending.remove(utteranceId)?.complete("synthesis error") }
        override fun onError(utteranceId: String, errorCode: Int) {
            pending.remove(utteranceId)?.complete("synthesis error $errorCode")
        }
    }

    override suspend fun synthesize(text: String): SynthesizedSpeech = mutex.withLock {
        val init = synchronized(this) {
            // Движок не поднялся — пробуем заново (могли поставить голос, пока шёл разговор).
            if (ready?.isCompleted == true && initFailed) release()
            if (ready == null) start()
            ready!!
        }
        val problem = try {
            withTimeout(ModelConfig.FALLBACK_INIT_TIMEOUT_MS) { init.await() }
        } catch (e: TimeoutCancellationException) {
            "engine init timeout"
        }
        if (problem != null) throw SynthesisException("android tts: $problem", unavailable = true)

        val id = "group-${counter.incrementAndGet()}"
        val file = File(context.cacheDir, "fallback_tts.wav")
        val done = CompletableDeferred<String?>()
        pending[id] = done
        val queued = tts!!.synthesizeToFile(text, Bundle(), file, id)
        if (queued != TextToSpeech.SUCCESS) {
            pending.remove(id)
            throw SynthesisException("android tts: synthesizeToFile rejected", unavailable = false)
        }
        val error = try {
            withTimeout(ModelConfig.FALLBACK_SYNTH_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            "synthesis timeout"
        }
        if (error != null) throw SynthesisException("android tts: $error", unavailable = false)
        val audio = withContext(Dispatchers.IO) {
            try {
                WavPcm.read(file.readBytes())
            } catch (e: Exception) {
                throw SynthesisException("android tts: bad audio file (${e.message})", unavailable = false, e)
            }
        }
        SynthesizedSpeech(audio, transcript = null)
    }

    override fun release() {
        synchronized(this) {
            tts?.let { try { it.stop(); it.shutdown() } catch (e: Exception) {} }
            tts = null
            ready = null
        }
    }

    private companion object {
        const val SOURCE = "Audio"
    }
}
