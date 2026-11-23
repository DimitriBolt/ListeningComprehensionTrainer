package com.example.listeningtrainer.openai

/**
 * Проверка ключа: `GET /v1/models` — без запроса к модели учителя и без оплаты.
 * Разговор идёт отдельным методом (`OpenAiService.chat`). Ключ не логируется.
 */
class KeyValidator(private val api: OpenAiService = OpenAiClient.api) {
    /** `true` — ключ рабочий. Сетевые ошибки и 401 пробрасываются. */
    suspend fun validate(apiKey: String): Boolean {
        api.listModels(OpenAiClient.bearer(apiKey)).close()
        return true
    }
}
