package com.example.listeningtrainer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.example.listeningtrainer.conversation.AudioOutput
import com.example.listeningtrainer.conversation.Cue
import com.example.listeningtrainer.conversation.SpeechAudio
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Голос учителя — PCM через `AudioTrack` (D9), поток `USAGE_MEDIA`: громкость меняется обычными
 * кнопками, как у музыки. Сигналы — [CuePlayer].
 *
 * Щит Bluetooth — нули в начале каждой группы ([play], `shieldMs`); нужен ли он, решает
 * [prepareReply] по выходу звука перед каждым ответом ([BluetoothShield]).
 */
class AudioTrackOutput(
    context: Context,
    private val log: EventLog,
    pulseGate: PulseEchoGate? = null,
) : AudioOutput {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val cues = CuePlayer(log, pulseGate)

    @Volatile
    private var current: AudioTrack? = null

    @Volatile
    private var stopped = false

    override fun prepareReply(): Long {
        val type = AudioRoutes.current(audioManager, SPEECH_ATTRIBUTES)
        val shield = BluetoothShield.shieldMs(type?.let(AudioRoutes::kind))
        log.event(SOURCE, "reply output: ${type?.let(AudioRoutes::label) ?: "unknown"}, shield $shield ms")
        return shield
    }

    override fun listenPulseAllowed(): Boolean {
        val type = AudioRoutes.current(audioManager, SPEECH_ATTRIBUTES)
        val allowed = ListenPulse.allowed(type?.let(AudioRoutes::kind))
        if (!allowed) log.event(SOURCE, "listen pulse off: output ${type?.let(AudioRoutes::label) ?: "unknown"}")
        return allowed
    }

    override suspend fun play(audio: SpeechAudio, shieldMs: Long, onStarted: () -> Unit) {
        stopped = false
        val rate = audio.sampleRate
        val track = try {
            val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(SPEECH_ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuffer, rate / 5 * 2)) // ≥ 200 мс
                .build()
        } catch (e: Exception) {
            throw IllegalStateException("AudioTrack cannot be created: ${e.javaClass.simpleName}", e)
        }
        current = track
        try {
            if (stopped) return
            track.play()
            onStarted()
            withContext(Dispatchers.IO) {
                val shieldFrames = (shieldMs * rate / 1000).toInt()
                val total = shieldFrames + audio.pcm.size
                val chunk = max(1, rate / 50) // 20 мс
                val zeros = ShortArray(chunk)
                var written = 0
                while (written < total && !stopped) {
                    currentCoroutineContext().ensureActive()
                    val n: Int
                    if (written < shieldFrames) {
                        n = track.write(zeros, 0, min(chunk, shieldFrames - written))
                    } else {
                        val offset = written - shieldFrames
                        n = track.write(audio.pcm, offset, min(chunk, audio.pcm.size - offset))
                    }
                    if (n < 0) throw IllegalStateException("AudioTrack write error $n")
                    written += n
                }
                // Дождаться, пока прозвучит записанное (с запасом на задержку старта).
                val deadline = System.currentTimeMillis() + total * 1000L / rate + END_MARGIN_MS
                while (!stopped && track.playbackHeadPosition < total && System.currentTimeMillis() < deadline) {
                    delay(10)
                }
            }
        } finally {
            current = null
            try { track.stop() } catch (e: IllegalStateException) {}
            track.release()
        }
    }

    override fun playCue(cue: Cue) = cues.play(cue)

    override suspend fun awaitCues() = cues.awaitAll()

    override fun stop() {
        stopped = true
        current?.let { track ->
            try { track.pause(); track.flush() } catch (e: IllegalStateException) {}
        }
    }

    override fun release() {
        stop()
        cues.release() // уже поставленные сигналы дозвучат
    }

    private companion object {
        const val SOURCE = "Audio"
        const val END_MARGIN_MS = 500L

        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
