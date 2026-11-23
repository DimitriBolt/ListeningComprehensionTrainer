package com.example.listeningtrainer.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM 16 бит, моно → AAC-LC в контейнере `.m4a` встроенными средствами Android
 * (`MediaCodec` + `MediaMuxer`, без библиотек). `gpt-transcribe` принимает `m4a`
 * (документация OpenAI «Speech to text»).
 *
 * Сжатие идёт **во время записи**: [feed] получает каждый кусок с микрофона, а после конца записи
 * [finish] дожимает последние кадры — это доли секунды, сколько бы ни длилась реплика. (В первой
 * версии S02b весь файл сжимался после записи: на телефоне ≈ 0,2 с на секунду речи, 18 с после
 * 90-секундной реплики.) Любая ошибка — исключение: тогда загружается WAV.
 */
class AacEncoder(private val out: File, private val sampleRate: Int, bitRate: Int) {

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var samplesIn = 0L
    private var bytes = ByteArray(0)
    private var released = false

    init {
        out.delete()
        muxer = try {
            MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    /** Очередной кусок записи: [count] отсчётов из [samples]. Забирает готовые кадры, не дожидаясь. */
    fun feed(samples: ShortArray, count: Int) {
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, count)
        var offset = 0
        while (offset < count * 2) {
            val inIndex = codec.dequeueInputBuffer(WAIT_US)
            if (inIndex < 0) {
                drain(WAIT_US) // входы заняты — забрать готовое и попробовать снова
                continue
            }
            val buffer = codec.getInputBuffer(inIndex)!!
            buffer.clear()
            val n = minOf(count * 2 - offset, buffer.remaining()) and 1.inv() // целые отсчёты
            buffer.put(bytes, offset, n)
            codec.queueInputBuffer(inIndex, 0, n, ptsUs(), 0)
            samplesIn += n / 2
            offset += n
        }
        drain(0)
    }

    /** Конец записи: дожать последние кадры и закрыть файл. */
    fun finish(): File {
        var waited = 0L
        while (true) {
            val inIndex = codec.dequeueInputBuffer(WAIT_US)
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(WAIT_US)
            waited += WAIT_US
            check(waited < FINISH_LIMIT_US) { "aac encoder did not accept end of stream" }
        }
        while (!drain(WAIT_US)) {
            waited += WAIT_US
            check(waited < FINISH_LIMIT_US) { "aac encoder did not finish" }
        }
        muxer.stop()
        muxerStarted = false
        release()
        check(out.length() > 0) { "empty m4a" }
        return out
    }

    /** Освободить кодер и файл (запись отменена или сжатие не удалось). Повторный вызов безвреден. */
    fun release() {
        if (released) return
        released = true
        try { codec.stop() } catch (ignored: Exception) {}
        try { codec.release() } catch (ignored: Exception) {}
        try { if (muxerStarted) muxer.stop() } catch (ignored: Exception) {}
        try { muxer.release() } catch (ignored: Exception) {}
    }

    /** Забрать все готовые кадры; первый ждать не дольше [timeoutUs]. `true` — пришёл конец потока. */
    private fun drain(timeoutUs: Long): Boolean {
        var wait = timeoutUs
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, wait)
            wait = 0
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
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
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun ptsUs() = samplesIn * 1_000_000L / sampleRate

    companion object {
        private const val MAX_INPUT_BYTES = 16_384
        /** Короткое ожидание кодера, когда его входы заняты или кадр ещё не готов. */
        private const val WAIT_US = 2_000L
        /** Предохранитель: дожимание конца не дольше 3 с. */
        private const val FINISH_LIMIT_US = 3_000_000L

        /** Готовый WAV-файл целиком в `.m4a` — для проверки на устройстве. */
        fun encodeFile(wav: File, out: File, sampleRate: Int, bitRate: Int): File {
            val encoder = AacEncoder(out, sampleRate, bitRate)
            try {
                RandomAccessFile(wav, "r").use { input ->
                    input.seek(44)
                    val raw = ByteArray(sampleRate * 80 / 1000 * 2) // куски по 80 мс, как у микрофона
                    val shorts = ShortArray(raw.size / 2)
                    while (true) {
                        val n = input.read(raw)
                        if (n <= 0) break
                        ByteBuffer.wrap(raw, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, n / 2)
                        encoder.feed(shorts, n / 2)
                    }
                }
                return encoder.finish()
            } finally {
                encoder.release()
            }
        }
    }
}
