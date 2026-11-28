package com.example.listeningtrainer.conversation

/**
 * Поле `prompt` для распознавания: кто говорит и последнее предложение учителя — ученик чаще
 * всего отвечает на него. Так модель реже путает слова (`Autouch` вместо `outage`).
 * Чистый Kotlin. В журнал этот текст не пишется.
 */
object TranscriptionPrompt {

    /**
     * Начальный уровень ученика (D42). Про исправление ошибок здесь ничего нет (D47): распознавание
     * должно записать, что сказано, — иначе учителю нечего поправлять.
     */
    const val CONTEXT =
        "An adult beginner English learner is answering an English teacher in a spoken conversation."

    /**
     * Подсказка со словами команд (S05b, D84): короткие команды распознавание писало с ошибками («Slowware», «Igain»).
     * Одна и та же для потока и файла. Проба `s05b --part commands` читает её отсюда.
     */
    const val COMMANDS_HINT = "The student may also say a short command: Stop, Again, Slower, Faster."

    /** [lastTeacherText] — весь последний ответ учителя (может быть `null` в начале разговора). */
    fun build(lastTeacherText: String?, maxTeacherChars: Int): String {
        val sentence = lastTeacherText?.let { lastSentence(it, maxTeacherChars) }.orEmpty()
        val head = "$CONTEXT $COMMANDS_HINT"
        return if (sentence.isEmpty()) head else "$head The teacher just said: \"$sentence\""
    }

    /**
     * Продолжение реплики (S04): ученик заговорил снова после объявленного конца — в `prompt` уже сказанное [said].
     * Тот же вид, что у куска способа б пробы `turn-end`.
     */
    fun continued(prompt: String, said: String): String =
        if (said.isBlank()) prompt else "$prompt The learner has already said: \"${said.trim()}\" and goes on."

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
