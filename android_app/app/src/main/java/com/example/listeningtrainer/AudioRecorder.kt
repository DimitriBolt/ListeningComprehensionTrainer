package com.example.listeningtrainer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.sqrt

class AudioRecorder(private val outputFile: File) {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    @SuppressLint("MissingPermission")
    suspend fun startRecording(
        pauseThresholdMs: Long,
        silenceThresholdDb: Float,
        onSilenceDetected: (File) -> Unit,
        onVolumeChanged: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        val outputStream = FileOutputStream(outputFile)
        // Резервируем 44 байта под заголовок WAV
        outputStream.write(ByteArray(44)) 
        
        val buffer = ShortArray(bufferSize)
        var lastSpeechTime = System.currentTimeMillis()
        isRecording = true
        audioRecord?.startRecording()

        var totalAudioLen: Long = 0

        try {
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
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
                    val db = if (rms > 0) 20 * log10(rms) else 0.0
                    onVolumeChanged(db.toFloat())

                    if (db > silenceThresholdDb) {
                        lastSpeechTime = System.currentTimeMillis()
                    } else {
                        val silenceDuration = System.currentTimeMillis() - lastSpeechTime
                        if (silenceDuration > pauseThresholdMs) {
                            isRecording = false
                        }
                    }
                }
            }
        } finally {
            outputStream.close()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            // ЗАПИСЫВАЕМ ЗАГОЛОВОК поверх резерва
            updateWavHeader(outputFile, totalAudioLen)
            
            if (!isRecording) {
                onSilenceDetected(outputFile)
            }
        }
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        val raf = RandomAccessFile(file, "rw")
        val sampleRate = 44100L
        val channels = 1
        val byteRate = sampleRate * channels * 2
        val totalDataLen = totalAudioLen + 36

        val header = ByteArray(44)
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = 2
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        raf.seek(0)
        raf.write(header)
        raf.close()
    }

    fun stop() {
        isRecording = false
    }
}
