package com.example.listeningtrainer.conversation

/** Смысловая группа: текст (может содержать `**` для экрана) и пауза после неё. */
data class SpeechChunk(val text: String, val pauseMs: Long)

/**
 * Текст учителя → смысловые группы и паузы. Чистый Kotlin (инвариант 3).
 * Эталон пауз и предел 6 слов — `docs/ARCHITECTURE.md`, «Эталон озвучки».
 */
class SpeechPlanner {

    fun plan(text: String): List<SpeechChunk> {
        // Заголовок — только строка, целиком жирная (с номером пункта или без, D45); жирное
        // слово внутри фразы — обычный текст, без абзацной паузы.
        val paragraphs = text.split(Regex("\\n+")).filter { it.trim().isNotEmpty() }
        val allChunks = mutableListOf<SpeechChunk>()

        paragraphs.forEachIndexed { pIndex, paragraph ->
            val heading = HEADING_LINE_REGEX.matches(paragraph)
            // Номер пункта списка — отдельная группа с короткой паузой (D22): иначе цифра
            // сливается на слух с первым словом заголовка.
            var pText = paragraph
            LIST_NUMBER_REGEX.matchEntire(paragraph)?.let { m ->
                allChunks.add(SpeechChunk(m.groupValues[1], PAUSE_LIST_NUMBER_MS))
                pText = m.groupValues[2]
            }
            // Кавычки — пара (D63): внутри короткой пары не режем, в длинной — не в одном слове от кавычки.
            val pairs = Quotes.scan(pText).pairs

            // [1] Базовый split: знаки препинания и союзы (только целое слово: `sorry` не режется); после `,"` и `."`
            // закрывающей кавычки пары — пауза этого знака (D63).
            val pChunksRaw = mutableListOf<Piece>()
            var from = 0
            for (m in SPLIT_REGEX.findAll(pText)) {
                val at = m.range.first
                if (m.groups[1] != null && pairs.none { it.close == at - 1 }) continue // кавычка без пары — как раньше
                if (!Quotes.cutAllowed(pText, pairs, at)) continue
                pChunksRaw += Piece(pText.substring(from, at), from)
                from = m.range.last + 1
            }
            pChunksRaw += Piece(pText.substring(from), from)
            pChunksRaw.removeAll { it.text.trim().isEmpty() }

            // [2] ВТОРИЧНОЕ ДРОБЛЕНИЕ: если фраза > 6 слов, режем её пополам — по слову, ближайшему к середине, где можно
            // резать по кавычкам (D63); нельзя нигде — фраза целиком.
            val pedagogicalChunks = mutableListOf<Piece>()
            for (rawChunk in pChunksRaw) {
                val words = rawChunk.text.split(" ")
                if (words.size > 6) {
                    val k = halfCut(words, rawChunk.start, pText, pairs)
                    if (k == null) {
                        pedagogicalChunks.add(rawChunk)
                    } else {
                        val head = words.subList(0, k).joinToString(" ")
                        pedagogicalChunks.add(Piece(head, rawChunk.start))
                        pedagogicalChunks.add(Piece(words.subList(k, words.size).joinToString(" "), rawChunk.start + head.length + 1))
                    }
                } else {
                    pedagogicalChunks.add(rawChunk)
                }
            }

            val pChunks = pedagogicalChunks.mapIndexed { cIndex, piece ->
                val trimmed = piece.text.trim()
                val end = endMark(piece, pText, pairs)
                val p = when {
                    cIndex == pedagogicalChunks.size - 1 && pIndex < paragraphs.size - 1 -> PAUSE_PARAGRAPH_MS
                    heading && cIndex == pedagogicalChunks.size - 1 -> PAUSE_HEADING_MS
                    end == '.' || end == '!' || end == '?' -> PAUSE_SENTENCE_MS
                    end == ',' || end == ':' || end == ';' -> PAUSE_COMMA_MS
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

    /** Кусок абзаца и где он начинается в абзаце (для пар кавычек). */
    private class Piece(val text: String, val start: Int)

    /**
     * Где резать фразу длиннее 6 слов: номер слова, с которого начинается вторая половина, — ближайший к середине (при
     * равенстве — раньше), где можно резать по кавычкам; `null` — нигде. Без кавычек — ровно середина, как до S05c.
     */
    private fun halfCut(words: List<String>, start: Int, pText: String, pairs: List<Quotes.Pair>): Int? {
        val mid = words.size / 2
        var offset = start
        val spaceAt = IntArray(words.size) // позиция пробела перед словом k (в абзаце)
        for (k in words.indices) {
            if (k > 0) spaceAt[k] = offset - 1
            offset += words[k].length + 1
        }
        return (1 until words.size).sortedWith(compareBy({ kotlin.math.abs(it - mid) }, { it }))
            .firstOrNull { Quotes.cutAllowed(pText, pairs, spaceAt[it]) }
    }

    /**
     * Знак в конце группы для паузы. Закрывающая кавычка пары (D63): «,» «.» «;» «:» сразу перед ней — пауза этого знака,
     * «?» и «!» внутри кавычек — не конец фразы. Кавычка без пары — как раньше (знак — сама кавычка).
     */
    private fun endMark(piece: Piece, pText: String, pairs: List<Quotes.Pair>): Char? {
        val t = piece.text.trimEnd()
        if (t.isEmpty()) return null
        val last = piece.start + t.length - 1
        val pair = pairs.firstOrNull { it.close == last } ?: return t.last()
        val before = if (last > pair.open + 1) pText[last - 1] else return null
        return when (before) {
            ',', '.', ';', ':' -> before
            else -> null // `?"` `!"` — не конец фразы; слово — как внутри
        }
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
        /**
         * Строка-заголовок (D45): целиком жирная, с номером пункта или без; после `**` может стоять
         * один знак `: . ! ?`. Внутри пары других `**` нет: «**Make** or **do**» — обычный текст.
         */
        private val HEADING_LINE_REGEX = Regex("^[ \\t]*(?:\\d{1,2}\\.[ \\t]+)?\\*\\*(?:(?!\\*\\*)[^\\n])+\\*\\*[ \\t]*[:.!?]?[ \\t]*$")
        /**
         * Где резать группу: после `, ! ? ; :` и `.` (не после номера `2.`), перед союзом (целое слово); группа 1 — после
         * `,"` `."` `;"` `:"` (закрывающая кавычка, D63).
         */
        private val SPLIT_REGEX = Regex("(?<=[,!?;:])\\s+|(?<=[.])(?<!\\b\\d\\.)\\s+|(?<=[,.;:][\"”])(\\s+)|\\s+(?=(?i:and|but|because|if|when|then|after|before|that|which|who|so|or|although|while|since|as)\\b)")
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
