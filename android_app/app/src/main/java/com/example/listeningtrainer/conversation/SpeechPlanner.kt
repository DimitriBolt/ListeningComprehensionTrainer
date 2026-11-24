package com.example.listeningtrainer.conversation

/** Смысловая группа: текст (может содержать `**` для экрана) и пауза после неё. */
data class SpeechChunk(val text: String, val pauseMs: Long)

/**
 * Текст учителя → смысловые группы и паузы. Чистый Kotlin (инвариант 3).
 * Эталон пауз и предел 6 слов — `docs/ARCHITECTURE.md`, «Эталон озвучки».
 */
class SpeechPlanner {

    fun plan(text: String): List<SpeechChunk> {
        // Жирный заголовок — отдельный абзац (номер пункта `1. ` в начале строки идёт с ним,
        // а ниже отделяется в свою группу).
        val preparedText = text.replace(HEADING_REGEX, "\n$1\n")
        val paragraphs = preparedText.split(Regex("\\n+")).filter { it.trim().isNotEmpty() }
        val allChunks = mutableListOf<SpeechChunk>()

        // [1] Базовый split: знаки препинания и союзы (только целое слово: `sorry` не режется)
        val splitRegex = Regex("(?<=[,!?;:])\\s+|(?<=[.])(?<!\\b\\d\\.)\\s+|\\s+(?=(?i:and|but|because|if|when|then|after|before|that|which|who|so|or|although|while|since|as)\\b)")

        paragraphs.forEachIndexed { pIndex, paragraph ->
            // Номер пункта списка — отдельная группа с короткой паузой (D22): иначе цифра
            // сливается на слух с первым словом заголовка.
            var pText = paragraph
            LIST_NUMBER_REGEX.matchEntire(paragraph)?.let { m ->
                allChunks.add(SpeechChunk(m.groupValues[1], PAUSE_LIST_NUMBER_MS))
                pText = m.groupValues[2]
            }
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
                    HEADING_CHUNK_REGEX.matches(trimmed) -> PAUSE_HEADING_MS
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
        return joinWordless(allChunks)
    }

    /**
     * Группа без единой буквы или цифры (`:`, `.`, `!`, `-` после заголовка) не создаётся (D23):
     * знак присоединяется к предыдущей группе, пауза — бóльшая из двух. Первая такая группа
     * присоединяется к следующей.
     */
    private fun joinWordless(chunks: List<SpeechChunk>): List<SpeechChunk> {
        val out = mutableListOf<SpeechChunk>()
        var pendingPrefix = ""
        for (chunk in chunks) {
            if (chunk.text.any { it.isLetterOrDigit() }) {
                out.add(if (pendingPrefix.isEmpty()) chunk else chunk.copy(text = "$pendingPrefix ${chunk.text}"))
                pendingPrefix = ""
                continue
            }
            val prev = out.lastOrNull()
            if (prev == null) {
                pendingPrefix = (pendingPrefix + " " + chunk.text).trim()
                continue
            }
            val gap = if (chunk.text.first() in ".,:;!?)…") "" else " "
            out[out.size - 1] = SpeechChunk(prev.text + gap + chunk.text, maxOf(prev.pauseMs, chunk.pauseMs))
        }
        return out
    }

    companion object {
        private val HEADING_REGEX = Regex("(?m)\\s*((?:^[ \\t]*\\d{1,2}\\.[ \\t]+)?\\*\\*.*?\\*\\*)")
        private val HEADING_CHUNK_REGEX = Regex("(?:\\d{1,2}\\.\\s+)?\\*\\*.*\\*\\*")
        /** Абзац, который начинается с номера пункта списка: `1. …`. */
        private val LIST_NUMBER_REGEX = Regex("^\\s*(\\d{1,2}\\.)\\s+(\\S.*)$", RegexOption.DOT_MATCHES_ALL)

        const val PAUSE_PARAGRAPH_MS = 1600L
        const val PAUSE_HEADING_MS = 1100L
        const val PAUSE_SENTENCE_MS = 1000L
        const val PAUSE_COMMA_MS = 600L
        const val PAUSE_INSIDE_MS = 250L
        /** После номера пункта списка, перед заголовком (D22). */
        const val PAUSE_LIST_NUMBER_MS = 400L
    }
}
