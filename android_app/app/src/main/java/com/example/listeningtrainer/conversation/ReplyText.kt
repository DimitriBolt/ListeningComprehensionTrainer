package com.example.listeningtrainer.conversation

/**
 * Ответ учителя как реплика диалога (S02e, раздел «Методика»). Чистый Kotlin (инвариант 3).
 * Копия в Python — `reply_metrics` и `drop_unfinished` в `tools/probe/probe.py` (проба `tutor-dialog`).
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
     * Текст для голоса телефона (английского): русские слова выпадают (S02e, п. 3), чтобы движок не
     * ломал группу. Если слов не осталось — пустая строка.
     */
    fun withoutRussian(text: String): String =
        text.replace(CYRILLIC_WORD, "").replace(Regex("""["'“”«»]\s*["'“”«»]"""), "")
            .replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""\s+([.,;:!?])"""), "$1").trim()
}
