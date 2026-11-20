package com.example.listeningtrainer.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.listeningtrainer.conversation.AudioInput
import com.example.listeningtrainer.conversation.RecordedAudio
import com.example.listeningtrainer.conversation.TurnDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Микрофон телефона → WAV-файл, 44,1 кГц, моно, 16 бит (частота прежняя; 16 кГц — S03).
 * Каждая новая запись перезаписывает [outputFile].
 */
class AudioRecorder(private val outputFile: File) : AudioInput {

    @Volatile
    private var isRecording = false

    @SuppressLint("MissingPermission") // разрешение проверяет экран до запуска сервиса
    override suspend fun record(turnDetector: TurnDetector, onLevel: (Float) -> Unit): RecordedAudio =
        withContext(Dispatchers.IO) {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )

            val outputStream = FileOutputStream(outputFile)
            // Резервируем 44 байта под заголовок WAV
            outputStream.write(ByteArray(WAV_HEADER_SIZE))

            val buffer = ShortArray(bufferSize)
            var totalAudioLen = 0L
            var peakDb = 0f
            isRecording = true
            turnDetector.reset(System.currentTimeMillis())
            try {
                audioRecord.startRecording()
                while (isRecording && isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val byteBuffer = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val v = buffer[i].toInt()
                        byteBuffer[i * 2] = (v and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    outputStream.write(byteBuffer)
                    totalAudioLen += byteBuffer.size

                    var sum = 0.0
                    for (i in 0 until read) sum += buffer[i] * buffer[i]
                    val rms = sqrt(sum / read)
                    val db = (if (rms > 0) 20 * log10(rms) else 0.0).toFloat()
                    if (db > peakDb) peakDb = db
                    onLevel(db)
                    if (turnDetector.onLevel(db, System.currentTimeMillis())) isRecording = false
                }
            } finally {
                isRecording = false
                outputStream.close()
                try { audioRecord.stop() } catch (e: IllegalStateException) {}
                audioRecord.release()
                writeWavHeader(outputFile, totalAudioLen)
            }
            ensureActive() // отменённая запись результата не даёт
            RecordedAudio(
                file = outputFile,
                durationMs = totalAudioLen * 1000 / (SAMPLE_RATE * CHANNELS * 2),
                heardSpeech = turnDetector.heardSpeech,
                peakDb = peakDb,
            )
        }

    override fun finish() {
        isRecording = false
    }

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
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 1
        const val WAV_HEADER_SIZE = 44
    }
}
