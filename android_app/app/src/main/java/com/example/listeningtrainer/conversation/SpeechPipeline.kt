package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.audio.Loudness
import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce

/**
 * Озвучка ответа учителя по группам. Чистый Kotlin (инвариант 3).
 *
 * - Группы приходят **потоком** (S02d: учитель ещё пишет ответ) и синтезируются **по порядку**:
 *   первая запрашивается первой и ничего не ждёт, дальше готовыми держатся не больше [lookahead]
 *   групп вперёд (D8, бриф S02 п. 3).
 * - Каждая группа основного синтеза сверяется с его расшифровкой ([TranscriptCheck]).
 *   Расхождение или ошибка → один повтор → снова не так → эта группа читается [fallback].
 * - Основной синтез недоступен целиком (нет сети, 401, 429, сессия) → остаток ответа читает
 *   [fallback]; следующий ответ снова начинается с основного.
 * - Громкость каждой группы приводится к целевой ([Loudness]).
 * - Группа без единой буквы или цифры (`:`, `.`, `!`, `-` после заголовка) в синтез не идёт:
 *   Realtime читал её как «dot». В канал уходит пустой звук — контроллер его не играет, пауза остаётся.
 *
 * В журнал (источник `Audio`) — только номер группы, каким синтезом, времена, длины, громкость;
 * тексты не пишутся.
 */
class SpeechPipeline(
    private val primary: Synthesizer,
    private val fallback: Synthesizer,
    private val log: EventLog,
    private val clock: () -> Long,
    private val lookahead: Int = ModelConfig.SPEECH_LOOKAHEAD,
) {
    init {
        require(lookahead >= 1) { "lookahead must be at least 1" }
    }

    /** Заранее открыть сессию и поднять запасной движок, чтобы это не попадало во время ответа. */
    fun prepare() {
        primary.prepare()
        fallback.prepare()
    }

    fun release() {
        primary.release()
        fallback.release()
    }

    /**
     * Начинает синтез групп, которые приходят в [chunks] (канал закрывается, когда ответ кончился).
     * Звук групп приходит в канал в том же порядке. Ошибка запасного синтеза завершает канал этой
     * ошибкой. Отмена [scope] обрывает синтез.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(
        scope: CoroutineScope,
        chunks: ReceiveChannel<SpeechChunk>,
        /** Скорость речи (S05: «Slower» медленнее эталона). */
        speed: Float = ModelConfig.SPEECH_SPEED,
        /** Готовый звук группы по номеру с 0 (S05: «Again» — звук прошлой реплики); `null` — синтезировать. */
        ready: (Int) -> SpeechAudio? = { null },
    ): ReceiveChannel<SpeechAudio> =
        // Ёмкость lookahead - 1: пока звучит группа i, в канале лежат i+1 … i+lookahead-1, а группа
        // i+lookahead готова и ждёт места — всего lookahead готовых вперёд.
        scope.produce(capacity = lookahead - 1) {
            var primaryAvailable = true
            var index = 0
            for (chunk in chunks) {
                val cached = ready(index)
                if (cached != null) {
                    index++
                    send(cached) // тот же звук, уже приведённый к громкости
                    continue
                }
                val number = "${++index}"
                val text = chunk.text.replace("**", "") // ** нужны только на экране
                if (!hasWords(text)) {
                    log.event(SOURCE, "group $number: no words, not synthesized")
                    send(SpeechAudio(ShortArray(0), ModelConfig.SPEECH_SAMPLE_RATE))
                    continue
                }
                val started = clock()
                val (speech, how) = synthesizeGroup(number, text, primaryAvailable, speed) { primaryAvailable = false }
                val readyMs = clock() - started
                val audio = speech.audio
                val norm = normalize(audio)
                log.event(
                    SOURCE,
                    "group $number: $how, ready in $readyMs ms, ${audio.durationMs} ms of audio, " +
                        "${norm.before} -> ${norm.after} (gain ${"%.1f".format(java.util.Locale.US, norm.gainDb)} dB)",
                )
                send(SpeechAudio(norm.pcm, audio.sampleRate))
            }
        }

    /**
     * Заготовленная фраза голосом основного синтеза (D39, «Let me check.»): со сверкой, той же
     * громкости. Не вышло (ошибка, расхождение) — `null`: фраза просто не прозвучит.
     */
    suspend fun synthesizePrimary(text: String): SpeechAudio? {
        val speech = try {
            primary.synthesize(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.event(SOURCE, "${primary.name} error (${e.message ?: e.javaClass.simpleName})")
            return null
        }
        if (!TranscriptCheck.compare(text, speech.transcript).ok) {
            log.event(SOURCE, "${primary.name} mismatch")
            return null
        }
        val norm = normalize(speech.audio)
        return SpeechAudio(norm.pcm, speech.audio.sampleRate)
    }

    private fun normalize(audio: SpeechAudio) = Loudness.normalize(
        audio.pcm, audio.sampleRate,
        ModelConfig.SPEECH_TARGET_RMS_DBFS, ModelConfig.SPEECH_PEAK_CEILING_DBFS, ModelConfig.SPEECH_MAX_GAIN_DB,
    )

    /**
     * Короткое сообщение голосом телефона (ошибка сети, D21): запасной синтез, та же громкость.
     * Ошибка синтеза пробрасывается — тогда остаются только сигналы.
     */
    suspend fun speakFallback(text: String): SpeechAudio {
        val audio = fallback.synthesize(text).audio
        val norm = normalize(audio)
        return SpeechAudio(norm.pcm, audio.sampleRate)
    }

    /** Звук одной группы и как он получен (для журнала). */
    private suspend fun synthesizeGroup(
        number: String,
        text: String,
        primaryAvailable: Boolean,
        speed: Float,
        markUnavailable: () -> Unit,
    ): Pair<SynthesizedSpeech, String> {
        if (primaryAvailable) {
            for (attempt in 1..ATTEMPTS) {
                val speech = try {
                    primary.synthesize(text, speed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SynthesisException) {
                    if (e.unavailable) {
                        log.event(SOURCE, "${primary.name} unavailable (${e.message}): group $number and the rest of the reply via ${fallback.name}")
                        markUnavailable()
                        return fallbackGroup(number, text, speed)
                    }
                    log.event(SOURCE, "group $number: ${primary.name} error (${e.message}), attempt $attempt of $ATTEMPTS")
                    continue
                } catch (e: Exception) {
                    log.event(SOURCE, "group $number: ${primary.name} error (${e.javaClass.simpleName}), attempt $attempt of $ATTEMPTS")
                    continue
                }
                val verdict = TranscriptCheck.compare(text, speech.transcript)
                if (verdict.ok) {
                    val how = if (attempt == 1) primary.name else "${primary.name} (attempt $attempt)"
                    return speech to how
                }
                log.event(
                    SOURCE,
                    "group $number: ${primary.name} mismatch (${verdict.name.lowercase()}: expected " +
                        "${TranscriptCheck.wordCount(text)} words, heard ${TranscriptCheck.wordCount(speech.transcript)}), " +
                        "attempt $attempt of $ATTEMPTS",
                )
            }
            log.event(SOURCE, "group $number: fallback to ${fallback.name} after $ATTEMPTS attempts")
        }
        return fallbackGroup(number, text, speed)
    }

    private suspend fun fallbackGroup(number: String, text: String, speed: Float): Pair<SynthesizedSpeech, String> {
        // Голос телефона английский: русское слово (D43) он не прочтёт — пропускаем слово, а не группу.
        val spoken = if (ReplyText.hasRussian(text)) ReplyText.withoutRussian(text) else text
        if (spoken != text) {
            log.event(SOURCE, "group $number: russian word skipped by ${fallback.name}")
            if (!hasWords(spoken)) return SynthesizedSpeech(SpeechAudio(ShortArray(0), ModelConfig.SPEECH_SAMPLE_RATE), null) to fallback.name
        }
        val speech = try {
            fallback.synthesize(spoken, speed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.event(SOURCE, "group $number: ${fallback.name} failed (${e.message ?: e.javaClass.simpleName})")
            throw e
        }
        return speech to fallback.name
    }

    private companion object {
        const val SOURCE = "Audio"

        /** Есть что произнести: хотя бы одна буква или цифра. */
        fun hasWords(text: String) = text.any { it.isLetterOrDigit() }
        /** Первая попытка и один повтор. */
        const val ATTEMPTS = 2
    }
}
