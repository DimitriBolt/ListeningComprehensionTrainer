package com.example.listeningtrainer.openai

import com.example.listeningtrainer.config.ModelConfig

/** Проверка ключа коротким запросом к модели учителя. Ключ не логируется. */
class KeyValidator(private val api: OpenAiService = OpenAiClient.api) {
    /** `true` — ключ рабочий. Сетевые ошибки и 401 пробрасываются. */
    suspend fun validate(apiKey: String): Boolean {
        val response = api.checkKey(
            OpenAiClient.bearer(apiKey),
            ChatRequest(model = ModelConfig.TUTOR_MODEL, messages = listOf(Message("user", "Hi"))),
        )
        return response.choices.isNotEmpty()
    }
}
