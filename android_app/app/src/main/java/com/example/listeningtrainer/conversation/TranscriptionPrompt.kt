package com.example.listeningtrainer.conversation

/**
 * Поле `prompt` для распознавания: кто говорит и последнее предложение учителя — ученик чаще
 * всего отвечает на него. Так модель реже путает слова (`Autouch` вместо `outage`).
 * Чистый Kotlin. В журнал этот текст не пишется.
 */
object TranscriptionPrompt {

    const val CONTEXT =
        "An adult English learner (level A2-B1) is answering an English teacher in a spoken conversation."

    /** [lastTeacherText] — весь последний ответ учителя (может быть `null` в начале разговора). */
    fun build(lastTeacherText: String?, maxTeacherChars: Int): String {
        val sentence = lastTeacherText?.let { lastSentence(it, maxTeacherChars) }.orEmpty()
        return if (sentence.isEmpty()) CONTEXT else "$CONTEXT The teacher just said: \"$sentence\""
    }

    /** Последнее предложение без разметки, не длиннее [maxChars] (обрезка по слову с начала). */
    fun lastSentence(text: String, maxChars: Int): String {
        val clean = text.replace("**", "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return ""
        // Предложения кончаются на . ! ? — берём последнее непустое.
        val sentences = clean.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        var last = sentences.lastOrNull()?.trim().orEmpty()
        if (last.length > maxChars) {
            val tail = last.takeLast(maxChars)
            val space = tail.indexOf(' ')
            last = if (space in 0 until tail.length - 1) tail.substring(space + 1) else tail
        }
        return last
    }
}
