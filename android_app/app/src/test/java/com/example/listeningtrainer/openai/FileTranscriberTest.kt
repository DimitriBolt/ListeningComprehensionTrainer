package com.example.listeningtrainer.openai

import com.example.listeningtrainer.conversation.FailureKind
import com.example.listeningtrainer.conversation.RecordedAudio
import com.example.listeningtrainer.conversation.ServiceException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.File

class FileTranscriberTest {

    /** Подставной API: запоминает загруженные файлы и поля, отвечает по [answer]. */
    private class FakeApi(val answer: (fileName: String) -> String) : OpenAiService {
        val files = mutableListOf<String>()
        val fields = mutableListOf<Map<String, String>>()
        override suspend fun listModels(authHeader: String): ResponseBody = error("not used")
        override suspend fun transcribeAudio(
            authHeader: String, file: MultipartBody.Part, model: RequestBody, language: RequestBody, prompt: RequestBody,
        ): TranscriptionResponse {
            val name = file.headers!!["Content-Disposition"]!!.substringAfter("filename=\"").substringBefore('"')
            files += name
            fields += mapOf("model" to text(model), "languages[]" to text(language), "prompt" to text(prompt),
                "type" to file.body.contentType().toString())
            return TranscriptionResponse(answer(name))
        }
        private fun text(body: RequestBody) = Buffer().also { body.writeTo(it) }.readUtf8()
    }

    private fun audio(dir: File): RecordedAudio {
        val wav = File(dir, "rec.wav").apply { writeBytes(ByteArray(100)) }
        val m4a = File(dir, "rec.m4a").apply { writeBytes(ByteArray(20)) }
        return RecordedAudio(wav, m4a, durationMs = 1000, heardSpeech = true, peakDb = 70f)
    }

    private val dir = createTempDir()

    @Test
    fun `m4a is uploaded with gpt-transcribe, languages en and the prompt`() = runTest {
        val api = FakeApi { "Hello." }
        val text = FileTranscriber({ "sk" }, api).transcribe(audio(dir), "context")
        assertEquals("Hello.", text)
        assertEquals(listOf("rec.m4a"), api.files)
        assertEquals("gpt-transcribe", api.fields[0]["model"])
        assertEquals("en", api.fields[0]["languages[]"])
        assertEquals("context", api.fields[0]["prompt"])
        assertEquals("audio/mp4", api.fields[0]["type"])
    }

    @Test
    fun `m4a rejected with 400 - the same request once with wav`() = runTest {
        val api = FakeApi { name ->
            if (name.endsWith(".m4a")) throw HttpException(Response.error<Any>(400, "{}".toResponseBody("application/json".toMediaType())))
            "Hello."
        }
        assertEquals("Hello.", FileTranscriber({ "sk" }, api, retryPauseMs = 0).transcribe(audio(dir), "p"))
        assertEquals(listOf("rec.m4a", "rec.wav"), api.files)
        assertEquals("audio/wav", api.fields[1]["type"])
    }

    @Test
    fun `401 is not retried and not sent as wav`() = runTest {
        val api = FakeApi { throw HttpException(Response.error<Any>(401, "{}".toResponseBody("application/json".toMediaType()))) }
        try {
            FileTranscriber({ "sk" }, api, retryPauseMs = 0).transcribe(audio(dir), "p")
            fail("expected ServiceException")
        } catch (e: ServiceException) {
            assertEquals(FailureKind.KEY, e.kind)
        }
        assertEquals(listOf("rec.m4a"), api.files)
    }
}
