package com.example.listeningtrainer.openai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkLogInterceptorTest {

    @Test
    fun `OpenAI call shows path and upload size, not headers`() {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer sk-secret")
            .post(ByteArray(2048).toRequestBody("audio/wav".toMediaType()))
            .build()
        assertEquals("POST api.openai.com/v1/audio/transcriptions, up 2 KB", NetworkLogInterceptor.describe(request))
    }

    @Test
    fun `search and weather show only the host - their URLs contain the student's words`() {
        val search = Request.Builder().url("https://duckduckgo.com/lite/?q=my+secret+question").build()
        val weather = Request.Builder().url("https://wttr.in/Tucson?format=%l").build()
        assertEquals("GET duckduckgo.com", NetworkLogInterceptor.describe(search))
        assertEquals("GET wttr.in", NetworkLogInterceptor.describe(weather))
    }
}
