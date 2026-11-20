package com.example.listeningtrainer.audio

import android.media.MediaPlayer
import com.example.listeningtrainer.conversation.AudioOutput
import com.example.listeningtrainer.conversation.Cue
import com.example.listeningtrainer.conversation.SpeechAudio
import com.example.listeningtrainer.conversation.SpeechAudioFile
import com.example.listeningtrainer.eventlog.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Воспроизведение файлов через один MediaPlayer, сигналы — [CuePlayer].
 * Щит Bluetooth сейчас — тишина в начале самого файла (см. TtsSynthesizer).
 */
class MediaPlayerOutput : AudioOutput {

    private var mediaPlayer: MediaPlayer? = null
    private val cues = CuePlayer()

    @Volatile
    private var stopped = false

    override suspend fun play(audio: SpeechAudio, onStarted: () -> Unit) {
        when (audio) {
            is SpeechAudioFile -> playFile(audio, onStarted)
        }
    }

    private suspend fun playFile(audio: SpeechAudioFile, onStarted: () -> Unit) {
        stopped = false
        val player = mediaPlayer ?: MediaPlayer().also { mediaPlayer = it }
        try {
            player.reset(); player.setDataSource(audio.file.absolutePath); player.prepare()

            // Минимальная задержка перед стартом, основная тишина уже в файле
            delay(50)

            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { stop() }
                player.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                player.setOnErrorListener { _, what, extra ->
                    AppLog.event("Audio", "MediaPlayer error what=$what extra=$extra, group skipped")
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                if (!stopped) {
                    player.start()
                    onStarted()
                } else if (cont.isActive) cont.resume(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Как и раньше: сбой плеера (или остановка посреди подготовки) — идём дальше.
            if (!stopped) AppLog.error("Audio", "MediaPlayer failed, group skipped", e)
        }
    }

    override fun playCue(cue: Cue) = cues.play(cue)

    override suspend fun awaitCues() = cues.awaitAll()

    override fun stop() {
        stopped = true
        try { mediaPlayer?.stop(); mediaPlayer?.reset() } catch (e: IllegalStateException) {}
    }

    override fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
        cues.release() // уже поставленные сигналы дозвучат
    }
}
