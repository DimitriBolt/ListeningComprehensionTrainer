package com.example.listeningtrainer.conversation

/**
 * Ответ учителя как реплика диалога (S02e, раздел «Методика»). Чистый Kotlin (инвариант 3).
 * Копия в Python — `reply_metrics`, `drop_unfinished`, `first_question_end` в `tools/probe/tutor_dialog.py`.
 */
object ReplyText {

    /** Мера реплики для журнала: без самого текста. */
    data class Metrics(val words: Int, val sentences: Int, val question: Boolean, val russian: Boolean) {
        override fun toString() =
            "words $words, sentences $sentences, question ${yesNo(question)}, russian ${yesNo(russian)}"

        private fun yesNo(v: Boolean) = if (v) "yes" else "no"
    }

    private val WORD = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}]+)?""")
    /** Конец предложения: `. ! ?`, за ним могут стоять кавычки, скобки, `**`; дальше пробел или конец. */
    private val SENTENCE_END = Regex("""[.!?]+["'”»)\]*]*(?=\s|$)""")
    private val TRAILING_CLOSERS = Regex("""["'”»)\]*\s]+$""")
    private val CYRILLIC = Regex("""\p{IsCyrillic}""")
    private val CYRILLIC_WORD = Regex("""[\p{IsCyrillic}]+(?:[-'’][\p{IsCyrillic}]+)*""")

    fun metrics(text: String): Metrics {
        val t = text.trim()
        val words = WORD.findAll(t).count()
        val sentences = if (words == 0) 0 else t.split(Regex("""(?<=[.!?]["'”»)\]*]{0,3})\s+""")).count { s -> WORD.containsMatchIn(s) }
        return Metrics(words, sentences, t.replace(TRAILING_CLOSERS, "").endsWith("?"), hasRussian(t))
    }

    fun hasRussian(text: String) = CYRILLIC.containsMatchIn(text)

    /**
     * Ответ обрезан пределом `max_output_tokens`: отбросить незаконченную фразу в конце. Если в тексте
     * нет ни одного конца предложения, текст остаётся как есть — лучше обрывок, чем тишина.
     */
    fun dropUnfinished(text: String): String {
        val last = SENTENCE_END.findAll(text).lastOrNull() ?: return text
        return text.substring(0, last.range.last + 1)
    }

    /**
     * Реплика кончается первым вопросом (S02e, ручная проверка: учитель задавал два вопроса подряд или
     * сам отвечал на свой уточняющий вопрос). Позиция сразу после первого вопроса — `?`, закрывающие
     * кавычки или скобки и пробел за ними; `null` — такого вопроса ещё нет или решать рано.
     *
     * Поправка ученика в начале реплики (D47) — не вопрос: «I think you meant "…", right?» пропускается
     * целиком, вместе с `?` внутри кавычек. Пока поправка не пришла до конца — `null` (ждём).
     */
    fun firstQuestionEnd(text: String): Int? {
        val from = afterCorrection(text) ?: return null
        val m = QUESTION_END.find(text, from) ?: return null
        return m.range.last + 1
    }

    private const val CORRECTION_START = "i think you meant"
    private val QUESTION_END = Regex("""\?["'”»)\]*]*(?=\s)""")
    private val CORRECTION_QUOTE = Regex("""^(\s*I think you meant,?\s*)["“]""", RegexOption.IGNORE_CASE)
    private val CLOSING_QUOTE = Regex("""["”]""")
    private val RIGHT_TAG = Regex("""^\s*,?\s*right\s*\?""", RegexOption.IGNORE_CASE)

    /**
     * Где кончается поправка «I think you meant "…", right?» в начале [text]: 0 — поправки нет; `null` — решать
     * рано (начало текста — ещё начало поправки, или она не пришла целиком).
     */
    fun correctionEnd(text: String): Int? = afterCorrection(text)

    private fun afterCorrection(text: String): Int? {
        val lead = text.trimStart().lowercase()
        if (!lead.startsWith(CORRECTION_START)) return if (CORRECTION_START.startsWith(lead)) null else 0
        val open = CORRECTION_QUOTE.find(text)
            ?: return if (lead.removePrefix(CORRECTION_START).trim(' ', ',').isEmpty()) null else 0
        val close = CLOSING_QUOTE.find(text, open.range.last + 1) ?: return null
        val afterQuote = close.range.last + 1
        val rest = text.substring(afterQuote)
        RIGHT_TAG.find(rest)?.let { return afterQuote + it.range.last + 1 }
        // «, right?» ещё может прийти: пока хвост — его начало, решать рано.
        val tail = rest.replace(Regex("""[\s,]+"""), "").lowercase()
        return if ("right?".startsWith(tail)) null else afterQuote
    }

    /**
     * Текст для голоса телефона (английского): русские слова выпадают (S02e, п. 3), чтобы движок не
     * ломал группу. Если слов не осталось — пустая строка.
     */
    fun withoutRussian(text: String): String =
        text.replace(CYRILLIC_WORD, "").replace(Regex("""["'“”«»]\s*["'“”«»]"""), "")
            .replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""\s+([.,;:!?])"""), "$1").trim()
}
