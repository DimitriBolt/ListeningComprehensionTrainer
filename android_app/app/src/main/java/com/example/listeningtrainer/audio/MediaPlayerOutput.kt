package com.example.listeningtrainer.audio

import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import com.example.listeningtrainer.conversation.AudioOutput
import com.example.listeningtrainer.conversation.Cue
import com.example.listeningtrainer.conversation.SpeechAudio
import com.example.listeningtrainer.conversation.SpeechAudioFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Воспроизведение файлов через один MediaPlayer, сигналы — ToneGenerator.
 * Щит Bluetooth сейчас — тишина в начале самого файла (см. TtsSynthesizer).
 */
class MediaPlayerOutput : AudioOutput {

    private var mediaPlayer: MediaPlayer? = null

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
                player.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                if (!stopped) {
                    player.start()
                    onStarted()
                } else if (cont.isActive) cont.resume(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Как и раньше: сбой плеера (или остановка посреди подготовки) — идём дальше.
        }
    }

    override fun playCue(cue: Cue) {
        try {
            val toneGen = when (cue) {
                Cue.PROCESSING -> ToneGenerator(AudioManager.STREAM_MUSIC, 70)
                Cue.YOUR_TURN -> ToneGenerator(AudioManager.STREAM_MUSIC, 50) // чуть тише
            }
            when (cue) {
                Cue.PROCESSING -> toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                Cue.YOUR_TURN -> toneGen.startTone(ToneGenerator.TONE_SUP_PIP, 100) // высокий короткий
            }
        } catch (e: RuntimeException) {}
    }

    override fun stop() {
        stopped = true
        try { mediaPlayer?.stop(); mediaPlayer?.reset() } catch (e: IllegalStateException) {}
    }

    override fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
