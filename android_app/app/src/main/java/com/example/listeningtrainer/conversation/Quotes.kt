package com.example.listeningtrainer.conversation

/**
 * Кавычки — пара для нарезки (S05c, D63). Чистый Kotlin (инвариант 3). Копия в Python — `quote_pairs` в
 * `tools/probe/speech_planner.py`.
 *
 * Пара — двойные кавычки `"…"` или `“…”` (закрывающая — `"` или `”`) **в одном абзаце** и не дальше [PAIR_MAX_WORDS] слов от
 * открывающей. Кавычки ищутся слева направо: открывающая без закрывающей (модель не закрыла, или до неё конец абзаца или
 * предел слов) — кавычка без пары, правила как без кавычек; поиск идёт со следующего знака. Одиночная `”` — не открывающая.
 *
 * Пока текст приходит потоком, пара может быть ещё не решена: у открывающей нет закрывающей, а конец абзаца, предел слов
 * или конец ответа ещё не наступили ([Scan.undecided]). Дальше неё нарезка не режет — ждёт закрывающую или предел.
 */
object Quotes {
    /** Пара с таким числом слов внутри и меньше не режется внутри (название, короткая цитата). */
    const val SHORT_MAX_WORDS = 8
    /** Закрывающая кавычка дальше стольких слов от открывающей — открывающая без пары. */
    const val PAIR_MAX_WORDS = 30

    /** Пара: позиции открывающей и закрывающей кавычки, слов внутри. */
    data class Pair(val open: Int, val close: Int, val words: Int) {
        val short: Boolean get() = words <= SHORT_MAX_WORDS

        /** Позиция [at] строго внутри пары (между кавычками). */
        fun inside(at: Int) = at > open && at <= close
    }

    /** Пары и позиция первой открывающей кавычки, пара которой ещё не решена (`-1` — такой нет). */
    data class Scan(val pairs: List<Pair>, val undecided: Int)

    private val WORD = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}]+)?""")

    fun isOpening(ch: Char) = ch == '"' || ch == '“'
    fun isClosing(ch: Char) = ch == '"' || ch == '”'

    /** Слов в [text] между [from] и [to]. */
    fun words(text: CharSequence, from: Int, to: Int): Int = if (to <= from) 0 else WORD.findAll(text.subSequence(from, to)).count()

    /**
     * Пары кавычек в [text]. [final] — текст целиком (конец ответа или нарезка готового текста): открывающая без закрывающей —
     * без пары. Иначе (поток) — она [Scan.undecided], и дальше неё пар не ищем.
     */
    fun scan(text: CharSequence, final: Boolean = true): Scan {
        val pairs = mutableListOf<Pair>()
        var i = 0
        while (i < text.length) {
            if (!isOpening(text[i])) { i++; continue }
            val open = i
            var j = open + 1
            var close = -1
            var limit = false // конец абзаца или предел слов: без пары
            var wordsSoFar = 0
            var inWord = false
            while (j < text.length) {
                val ch = text[j]
                if (ch == '\n') { limit = true; break }
                if (isClosing(ch)) { close = j; break }
                val letter = ch.isLetterOrDigit()
                if (letter && !inWord) {
                    wordsSoFar++
                    if (wordsSoFar > PAIR_MAX_WORDS) { limit = true; break }
                }
                inWord = letter || (inWord && (ch == '\'' || ch == '’'))
                j++
            }
            when {
                close >= 0 -> {
                    pairs += Pair(open, close, words(text, open + 1, close))
                    i = close + 1
                }
                limit || final -> i = open + 1 // без пары
                else -> return Scan(pairs, open) // ещё не решено: ждать закрывающую или предел
            }
        }
        return Scan(pairs, -1)
    }

    /**
     * Можно ли резать группу в позиции [at] (между знаками) по кавычкам: не внутри короткой пары и не в одном слове от
     * кавычки внутри длинной.
     */
    fun cutAllowed(text: CharSequence, pairs: List<Pair>, at: Int): Boolean = pairs.none { p ->
        p.inside(at) && (p.short || words(text, p.open + 1, at) <= 1 || words(text, at, p.close) <= 1)
    }
}
