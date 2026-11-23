package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.RecordedAudio
import com.example.listeningtrainer.conversation.Transcriber
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Распознавание файлом: `/v1/audio/transcriptions`, модель и язык — в ModelConfig (`gpt-transcribe`,
 * `languages[]=en` — так принято в пробе S00b). [prompt] — короткий контекст разговора.
 */
class FileTranscriber(
    private val apiKey: () -> String,
    private val api: OpenAiService = OpenAiClient.transcribeApi,
) : Transcriber {

    override suspend fun transcribe(audio: RecordedAudio, prompt: String): String {
        val textType = "text/plain".toMediaType()
        val filePart = MultipartBody.Part.createFormData(
            "file", audio.upload.name, audio.upload.asRequestBody(mimeOf(audio.upload).toMediaType())
        )
        val response = api.transcribeAudio(
            OpenAiClient.bearer(apiKey()),
            filePart,
            ModelConfig.TRANSCRIBE_MODEL.toRequestBody(textType),
            ModelConfig.TRANSCRIBE_LANGUAGE.toRequestBody(textType),
            prompt.toRequestBody(textType),
        )
        return response.text
    }

    private fun mimeOf(file: File) = if (file.extension == "m4a") "audio/mp4" else "audio/wav"
}
