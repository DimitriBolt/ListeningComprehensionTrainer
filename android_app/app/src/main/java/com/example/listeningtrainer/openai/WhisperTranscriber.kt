package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.RecordedAudio
import com.example.listeningtrainer.conversation.Transcriber
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Распознавание файлом (сейчас `whisper-1`, модель — в ModelConfig). */
class WhisperTranscriber(
    private val apiKey: () -> String,
    private val api: OpenAiService = OpenAiClient.api,
) : Transcriber {

    override suspend fun transcribe(audio: RecordedAudio): String {
        val textType = "text/plain".toMediaType()
        val filePart = MultipartBody.Part.createFormData(
            "file", audio.file.name, audio.file.asRequestBody("audio/wav".toMediaType())
        )
        val response = api.transcribeAudio(
            OpenAiClient.bearer(apiKey()),
            filePart,
            ModelConfig.TRANSCRIBE_MODEL.toRequestBody(textType),
            ModelConfig.TRANSCRIBE_LANGUAGE.toRequestBody(textType),
        )
        return response.text
    }
}
