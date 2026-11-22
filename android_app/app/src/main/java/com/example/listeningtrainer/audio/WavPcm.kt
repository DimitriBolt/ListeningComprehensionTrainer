package com.example.listeningtrainer.audio

import com.example.listeningtrainer.conversation.SpeechAudio
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV (RIFF, PCM 16 бит) → [SpeechAudio] моно. Стерео сводится в моно. Чистый Kotlin. */
object WavPcm {

    fun read(bytes: ByteArray): SpeechAudio {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "not a WAV file" }
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos)
            val declared = b.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            // Потоковые движки пишут размер 0 или 0xFFFFFFFF — тогда данные до конца файла.
            val size = if (declared == 0L || body + declared > bytes.size) (bytes.size - body).toLong() else declared
            when (id) {
                "fmt " -> {
                    require(b.getShort(body).toInt() == 1) { "WAV is not PCM" }
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(rate > 0 && channels in 1..2 && bits == 16) { "unsupported WAV: $channels ch, $bits bit, $rate Hz" }
                    val frames = (size / (2 * channels)).toInt()
                    val pcm = ShortArray(frames) { i ->
                        val at = body + i * 2 * channels
                        if (channels == 1) b.getShort(at)
                        else ((b.getShort(at) + b.getShort(at + 2)) / 2).toShort()
                    }
                    return SpeechAudio(pcm, rate)
                }
            }
            pos = body + size.toInt() + (size.toInt() and 1)
        }
        throw IllegalArgumentException("WAV has no data")
    }

    private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
}
