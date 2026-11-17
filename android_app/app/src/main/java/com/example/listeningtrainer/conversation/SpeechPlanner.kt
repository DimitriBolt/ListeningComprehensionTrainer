package com.example.listeningtrainer.conversation

/** Смысловая группа: текст (может содержать `**` для экрана) и пауза после неё. */
data class SpeechChunk(val text: String, val pauseMs: Long)

/**
 * Текст учителя → смысловые группы и паузы. Чистый Kotlin (инвариант 3).
 * Эталон пауз и предел 6 слов — `docs/ARCHITECTURE.md`, «Эталон озвучки».
 */
class SpeechPlanner {

    fun plan(text: String): List<SpeechChunk> {
        val preparedText = text.replace(Regex("(?<!\\d\\.)\\s*(\\*\\*.*?\\*\\*)"), "\n$1\n")
        val paragraphs = preparedText.split(Regex("\\n+")).filter { it.trim().isNotEmpty() }
        val allChunks = mutableListOf<SpeechChunk>()

        // [1] Базовый split: знаки препинания и союзы
        val splitRegex = Regex("(?<=[,!?;:])\\s+|(?<=[.])(?<!\\b\\d\\.)\\s+|\\s+(?=(?i)and|but|because|if|when|then|after|before|that|which|who|so|or|although|while|since|as)\\b")

        paragraphs.forEachIndexed { pIndex, pText ->
            val pChunksRaw = pText.split(splitRegex).filter { it.trim().isNotEmpty() }

            // [2] ВТОРИЧНОЕ ДРОБЛЕНИЕ: если фраза > 6 слов, режем её пополам.
            val pedagogicalChunks = mutableListOf<String>()
            for (rawChunk in pChunksRaw) {
                val words = rawChunk.split(" ")
                if (words.size > 6) {
                    val mid = words.size / 2
                    pedagogicalChunks.add(words.subList(0, mid).joinToString(" "))
                    pedagogicalChunks.add(words.subList(mid, words.size).joinToString(" "))
                } else {
                    pedagogicalChunks.add(rawChunk)
                }
            }

            val pChunks = pedagogicalChunks.mapIndexed { cIndex, t ->
                val trimmed = t.trim()
                val p = when {
                    cIndex == pedagogicalChunks.size - 1 && pIndex < paragraphs.size - 1 -> PAUSE_PARAGRAPH_MS
                    trimmed.startsWith("**") && trimmed.endsWith("**") -> PAUSE_HEADING_MS
                    trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") -> PAUSE_SENTENCE_MS
                    trimmed.endsWith(",") || trimmed.endsWith(":") || trimmed.endsWith(";") -> PAUSE_COMMA_MS
                    else -> PAUSE_INSIDE_MS
                }
                // Паузы — строго по эталону. Опережающий запуск под щит Bluetooth вычитает
                // синтез/воспроизведение, а не планировщик.
                SpeechChunk(trimmed, p)
            }
            allChunks.addAll(pChunks)
        }
        return allChunks
    }

    companion object {
        const val PAUSE_PARAGRAPH_MS = 1600L
        const val PAUSE_HEADING_MS = 1100L
        const val PAUSE_SENTENCE_MS = 1000L
        const val PAUSE_COMMA_MS = 600L
        const val PAUSE_INSIDE_MS = 250L
    }
}
