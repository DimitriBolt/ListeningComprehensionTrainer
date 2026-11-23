package com.example.listeningtrainer.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.AudioInput
import com.example.listeningtrainer.conversation.RecordedAudio
import com.example.listeningtrainer.conversation.TurnDetector
import com.example.listeningtrainer.eventlog.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Микрофон телефона → WAV-файл, PCM 16 кГц, моно, 16 бит (D6). Каждая запись перезаписывает
 * [wavFile]. После записи WAV сжимается в AAC `.m4a` ([m4aFile]) — его и загружает распознавание
 * (≈ 4 КБ на секунду вместо 32). Если сжатие не удалось или выключено в [ModelConfig] —
 * загружается WAV. Формат и размер загрузки — в журнал.
 *
 * Громкость считается кусками по [ModelConfig.RECORD_LEVEL_WINDOW_MS] — как раньше (≈ 80 мс на
 * кусок при 44,1 кГц), чтобы порог 59 дБ и 3 с тишины вели себя по-прежнему.
 * Предохранитель: через [ModelConfig.RECORD_MAX_MS] запись заканчивается сама, как по «Stop Recording».
 */
class AudioRecorder(
    private val wavFile: File,
    private val m4aFile: File,
    private val log: EventLog,
) : AudioInput {

    @Volatile
    private var isRecording = false

    @SuppressLint("MissingPermission") // разрешение проверяет экран до запуска сервиса
    override suspend fun record(turnDetector: TurnDetector, onLevel: (Float) -> Unit): RecordedAudio =
        withContext(Dispatchers.IO) {
            val window = SAMPLE_RATE * ModelConfig.RECORD_LEVEL_WINDOW_MS / 1000
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, window * 2 * 4)
            )
            val maxSamples = SAMPLE_RATE * ModelConfig.RECORD_MAX_MS / 1000

            val outputStream = FileOutputStream(wavFile)
            // Резервируем 44 байта под заголовок WAV
            outputStream.write(ByteArray(WAV_HEADER_SIZE))

            val buffer = ShortArray(window)
            val bytes = ByteArray(window * 2)
            var totalSamples = 0L
            var peakDb = 0f
            var limitReached = false
            isRecording = true
            turnDetector.reset(System.currentTimeMillis())
            try {
                audioRecord.startRecording()
                while (isRecording && isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    for (i in 0 until read) {
                        val v = buffer[i].toInt()
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    outputStream.write(bytes, 0, read * 2)
                    totalSamples += read

                    val db = PcmLevel.db(buffer, read)
                    if (db > peakDb) peakDb = db
                    onLevel(db)
                    if (turnDetector.onLevel(db, System.currentTimeMillis())) isRecording = false
                    if (totalSamples >= maxSamples) {
                        limitReached = true
                        isRecording = false
                    }
                }
            } finally {
                isRecording = false
                outputStream.close()
                try { audioRecord.stop() } catch (e: IllegalStateException) {}
                audioRecord.release()
                writeWavHeader(wavFile, totalSamples * 2)
            }
            ensureActive() // отменённая запись результата не даёт
            val durationMs = totalSamples * 1000 / SAMPLE_RATE
            if (limitReached) {
                log.event(SOURCE, "recording limit ${ModelConfig.RECORD_MAX_MS / 1000} s reached: recording finished")
            }
            val upload = if (turnDetector.heardSpeech) uploadFile(durationMs) else wavFile
            RecordedAudio(
                file = wavFile,
                upload = upload,
                durationMs = durationMs,
                heardSpeech = turnDetector.heardSpeech,
                peakDb = peakDb,
            )
        }

    override fun finish() {
        isRecording = false
    }

    /** Файл для распознавания: сжатый m4a, при сбое — WAV. Формат и размер — в журнал. */
    private fun uploadFile(durationMs: Long): File {
        val seconds = maxOf(1L, durationMs) / 1000.0
        if (ModelConfig.TRANSCRIBE_UPLOAD_COMPRESSED) {
            val started = System.nanoTime()
            try {
                AacEncoder.encode(wavFile, m4aFile, SAMPLE_RATE, ModelConfig.RECORD_AAC_BIT_RATE)
                val ms = (System.nanoTime() - started) / 1_000_000
                log.event(SOURCE, "upload: m4a ${kb(m4aFile.length())} KB (${perSecond(m4aFile.length(), seconds)} KB/s), encoded in $ms ms")
                return m4aFile
            } catch (e: Exception) {
                log.error(SOURCE, "m4a encoding failed, uploading wav", e)
            }
        }
        log.event(SOURCE, "upload: wav ${kb(wavFile.length())} KB (${perSecond(wavFile.length(), seconds)} KB/s)")
        return wavFile
    }

    private fun kb(bytes: Long) = (bytes + 1023) / 1024

    private fun perSecond(bytes: Long, seconds: Double) =
        String.format(java.util.Locale.US, "%.1f", bytes / 1024.0 / seconds)

    private fun writeWavHeader(file: File, totalAudioLen: Long) {
        val byteRate = SAMPLE_RATE * CHANNELS * 2
        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((totalAudioLen + 36).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                  // размер блока fmt
            putShort(1)                 // PCM
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort((CHANNELS * 2).toShort()) // выравнивание блока
            putShort(16)                // бит на отсчёт
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(totalAudioLen.toInt())
        }.array()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }

    private companion object {
        const val SOURCE = "Audio"
        const val SAMPLE_RATE = ModelConfig.RECORD_SAMPLE_RATE
        const val CHANNELS = 1
        const val WAV_HEADER_SIZE = 44
    }
}
