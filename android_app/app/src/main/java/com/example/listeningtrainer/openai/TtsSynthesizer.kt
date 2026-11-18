package com.example.listeningtrainer.openai

import com.example.listeningtrainer.conversation.SpeechAudio
import com.example.listeningtrainer.conversation.SpeechAudioFile
import com.example.listeningtrainer.conversation.Synthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Синтез файлом MP3 через `/v1/audio/speech` (модель, голос, скорость — в ModelConfig).
 * Щит Bluetooth: перед текстом строка из точек (~1,2 с тишины в начале файла),
 * поэтому следующий файл запускается на [leadInMs] раньше эталонной паузы.
 */
class TtsSynthesizer(
    private val apiKey: () -> String,
    private val cacheDir: File,
    private val api: OpenAiService = OpenAiClient.api,
) : Synthesizer {

    override val leadInMs: Long = 1000L

    /** Имена файлов по кругу: старые перезаписываются, кэш не растёт. */
    private val counter = AtomicInteger(0)

    override suspend fun synthesize(text: String): SpeechAudio = withContext(Dispatchers.IO) {
        // ** нужны только на экране.
        val ttsInput = text.replace("**", "")
        val res = api.generateSpeech(
            OpenAiClient.bearer(apiKey()),
            TtsRequest(input = BLUETOOTH_SHIELD + ttsInput),
        )
        val file = File(cacheDir, "chunk_${counter.getAndIncrement() % FILE_RING}.mp3")
        res.byteStream().use { i -> FileOutputStream(file).use { o -> i.copyTo(o) } }
        SpeechAudioFile(file)
    }

    private companion object {
        /** 8 точек ≈ 1,2 с тишины: наушники успевают проснуться до первого слова. */
        const val BLUETOOTH_SHIELD = " . . . . . . . . "
        const val FILE_RING = 100
    }
}
