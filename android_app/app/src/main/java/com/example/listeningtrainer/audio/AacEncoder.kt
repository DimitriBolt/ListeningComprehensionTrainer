package com.example.listeningtrainer.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile

/**
 * WAV (PCM 16 бит, моно) → AAC-LC в контейнере `.m4a` встроенными средствами Android
 * (`MediaCodec` + `MediaMuxer`, без библиотек). `gpt-transcribe` принимает `m4a`
 * (документация OpenAI «Speech to text»). Ошибка — исключение: тогда загружается сам WAV.
 */
object AacEncoder {

    fun encode(wav: File, out: File, sampleRate: Int, bitRate: Int) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            RandomAccessFile(wav, "r").use { input ->
                input.seek(WAV_HEADER_SIZE)
                val pcmBytes = input.length() - WAV_HEADER_SIZE
                var readBytes = 0L
                var inputDone = false
                var track = -1
                val info = MediaCodec.BufferInfo()
                val chunk = ByteArray(CHUNK_BYTES)
                while (true) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            buffer.clear()
                            val n = input.read(chunk, 0, minOf(chunk.size, buffer.remaining()))
                            // Время кадра — по уже отданным отсчётам (2 байта на отсчёт).
                            val ptsUs = readBytes / 2 * 1_000_000L / sampleRate
                            if (n <= 0 || readBytes >= pcmBytes) {
                                codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buffer.put(chunk, 0, n)
                                codec.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                                readBytes += n
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val buffer = codec.getOutputBuffer(outIndex)!!
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isConfig && info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (ignored: Exception) {}
            codec.release()
            try { if (muxerStarted) muxer.stop() } catch (ignored: Exception) {}
            muxer.release()
        }
        check(out.length() > 0) { "empty m4a" }
    }

    private const val WAV_HEADER_SIZE = 44L
    private const val CHUNK_BYTES = 8192
    private const val TIMEOUT_US = 10_000L
}
