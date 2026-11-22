package com.example.listeningtrainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.listeningtrainer.conversation.Cue
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Сигналы из [CueTones] через AudioTrack. Один поток: сигналы звучат по очереди и не накладываются.
 * [release] не обрывает очередь — дозвучит всё, что уже поставлено (например, «конец» после «Back»).
 * Каждый сыгранный сигнал — строка журнала `[Audio] cue: END` в момент, когда он начинает звучать.
 */
class CuePlayer(private val log: EventLog) {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "cue-player").apply { isDaemon = true } }
    private val sounds: Map<Cue, ShortArray> = Cue.values().associateWith { CueTones.render(it) }

    /** Завершится, когда прозвучит последний поставленный сигнал. */
    @Volatile
    private var lastDone = CompletableDeferred(Unit)

    fun play(cue: Cue) {
        val done = CompletableDeferred<Unit>()
        lastDone = done
        try {
            executor.execute {
                try {
                    playBlocking(cue)
                } finally {
                    done.complete(Unit)
                }
            }
        } catch (e: RejectedExecutionException) {
            done.complete(Unit) // проигрыватель уже освобождён
        }
    }

    suspend fun awaitAll() = lastDone.await()

    fun release() = executor.shutdown()

    private fun playBlocking(cue: Cue) {
        val pcm = sounds.getValue(cue)
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(CueTones.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            log.event("Audio", "cue: ${cue.name}")
            Thread.sleep(CueTones.durationMs(cue) + TAIL_MS)
            track.stop()
        } catch (e: Exception) {
            log.error("Audio", "cue ${cue.name} failed", e)
        } finally {
            track?.release()
        }
    }

    private companion object {
        /** Запас на задержку старта AudioTrack, чтобы не обрезать конец сигнала. */
        const val TAIL_MS = 60L
    }
}
