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
 * - Группы синтезируются **по порядку**: первая запрашивается первой и ничего не ждёт, дальше
 *   готовыми держатся не больше [lookahead] групп вперёд (D8, бриф S02 п. 3).
 * - Каждая группа основного синтеза сверяется с его расшифровкой ([TranscriptCheck]).
 *   Расхождение или ошибка → один повтор → снова не так → эта группа читается [fallback].
 * - Основной синтез недоступен целиком (нет сети, 401, 429, сессия) → остаток ответа читает
 *   [fallback]; следующий ответ снова начинается с основного.
 * - Громкость каждой группы приводится к целевой ([Loudness]).
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
     * Начинает синтез групп [chunks] в [scope]. Звук групп приходит в канал в том же порядке.
     * Ошибка запасного синтеза завершает канал этой ошибкой. Отмена [scope] обрывает синтез.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope, chunks: List<SpeechChunk>): ReceiveChannel<SpeechAudio> =
        // Ёмкость lookahead - 1: пока звучит группа i, в канале лежат i+1 … i+lookahead-1, а группа
        // i+lookahead готова и ждёт места — всего lookahead готовых вперёд.
        scope.produce(capacity = lookahead - 1) {
            var primaryAvailable = true
            chunks.forEachIndexed { index, chunk ->
                val number = "${index + 1}/${chunks.size}"
                val text = chunk.text.replace("**", "") // ** нужны только на экране
                val started = clock()
                val (speech, how) = synthesizeGroup(number, text, primaryAvailable) { primaryAvailable = false }
                val readyMs = clock() - started
                val audio = speech.audio
                val norm = Loudness.normalize(
                    audio.pcm, audio.sampleRate,
                    ModelConfig.SPEECH_TARGET_RMS_DBFS, ModelConfig.SPEECH_PEAK_CEILING_DBFS, ModelConfig.SPEECH_MAX_GAIN_DB,
                )
                log.event(
                    SOURCE,
                    "group $number: $how, ready in $readyMs ms, ${audio.durationMs} ms of audio, " +
                        "${norm.before} -> ${norm.after} (gain ${"%.1f".format(java.util.Locale.US, norm.gainDb)} dB)",
                )
                send(SpeechAudio(norm.pcm, audio.sampleRate))
            }
        }

    /** Звук одной группы и как он получен (для журнала). */
    private suspend fun synthesizeGroup(
        number: String,
        text: String,
        primaryAvailable: Boolean,
        markUnavailable: () -> Unit,
    ): Pair<SynthesizedSpeech, String> {
        if (primaryAvailable) {
            for (attempt in 1..ATTEMPTS) {
                val speech = try {
                    primary.synthesize(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SynthesisException) {
                    if (e.unavailable) {
                        log.event(SOURCE, "${primary.name} unavailable (${e.message}): group $number and the rest of the reply via ${fallback.name}")
                        markUnavailable()
                        return fallbackGroup(number, text)
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
        return fallbackGroup(number, text)
    }

    private suspend fun fallbackGroup(number: String, text: String): Pair<SynthesizedSpeech, String> {
        val speech = try {
            fallback.synthesize(text)
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
        /** Первая попытка и один повтор. */
        const val ATTEMPTS = 2
    }
}
