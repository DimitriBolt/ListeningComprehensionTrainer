package com.example.listeningtrainer.conversation

/**
 * Тип сетевого сбоя распознавания или учителя (S02b, п. А4). Чистый Kotlin.
 * [retry] — повторить запрос один раз; [message] — короткая английская фраза (A2), которую
 * после «ошибки» произносит голос телефона (D21). `null` — только сигналы.
 */
enum class FailureKind(val retry: Boolean, val message: String?) {
    NO_NETWORK(true, Phrases.NO_NETWORK),
    TIMEOUT(true, Phrases.NO_ANSWER),
    SERVER(true, Phrases.NO_ANSWER),
    RATE_LIMIT(true, Phrases.NO_ANSWER),
    QUOTA(false, Phrases.QUOTA),
    KEY(false, Phrases.KEY),
    BAD_REQUEST(false, null),
    OTHER(false, null);

    object Phrases {
        const val NO_NETWORK = "I can't connect to the internet. Please check your connection."
        const val NO_ANSWER = "The server is not answering. Please try again later."
        const val KEY = "Your OpenAI key does not work. Please check it."
        const val QUOTA = "Your OpenAI account has no money left."
    }
}

/** Запрос не удался (после повтора, если он был). [kind] — для голосовой фразы и журнала. */
class ServiceException(val kind: FailureKind, message: String, cause: Throwable? = null) : Exception(message, cause)
