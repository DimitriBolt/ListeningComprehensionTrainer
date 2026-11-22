package com.example.listeningtrainer.conversation

/**
 * Сверка: прочитал ли синтезатор группу дословно. Чистый Kotlin, перенос `compare_texts`
 * из `tools/probe/probe.py` (пробы S00b, 50 из 50 групп): сравнение после нормализации —
 * регистр, пунктуация, `**`, кавычки, числа словами или цифрами, `$`, `%`; сокращения
 * (I'm = I am) тоже считаются совпадением.
 */
object TranscriptCheck {

    enum class Verdict(val ok: Boolean) {
        /** Совпало после нормализации. */
        VERBATIM(true),
        /** Совпало после раскрытия сокращений (I'm = I am). */
        CONTRACTIONS(true),
        /** Слова различаются. */
        MISMATCH(false),
        /** Расшифровки нет. */
        EMPTY(false),
    }

    fun compare(expected: String, heard: String?): Verdict {
        if (heard.isNullOrBlank()) return Verdict.EMPTY
        val ev = variants(expected)
        val hv = variants(heard)
        if (ev.any { it in hv }) return Verdict.VERBATIM
        val ec = ev.map(::expandContractions).toSet()
        if (hv.any { expandContractions(it) in ec }) return Verdict.CONTRACTIONS
        return Verdict.MISMATCH
    }

    /** Число слов после нормализации — для журнала (тексты туда не пишутся). */
    fun wordCount(text: String?): Int = if (text.isNullOrBlank()) 0 else variants(text).first().split(' ').count { it.isNotEmpty() }

    // ---- нормализация ----------------------------------------------------------------------

    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val ORD_IRREGULAR = mapOf(
        "one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
        "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth",
    )
    private val CONTRACTIONS = mapOf(
        "i'm" to "i am", "you're" to "you are", "we're" to "we are", "they're" to "they are",
        "it's" to "it is", "that's" to "that is", "there's" to "there is", "what's" to "what is",
        "he's" to "he is", "she's" to "she is", "let's" to "let us", "i've" to "i have",
        "you've" to "you have", "we've" to "we have", "i'll" to "i will", "you'll" to "you will",
        "we'll" to "we will", "it'll" to "it will", "i'd" to "i would", "you'd" to "you would",
        "don't" to "do not", "doesn't" to "does not", "didn't" to "did not", "can't" to "cannot",
        "won't" to "will not", "isn't" to "is not", "aren't" to "are not", "wasn't" to "was not",
        "weren't" to "were not", "haven't" to "have not", "hasn't" to "has not",
        "couldn't" to "could not", "wouldn't" to "would not", "shouldn't" to "should not",
    )
    private val SYNONYMS = mapOf("okay" to "ok", "per cent" to "percent")
    private const val VARIANT_LIMIT = 256

    private fun below100(n: Int): String =
        if (n < 20) ONES[n] else TENS[n / 10] + if (n % 10 == 0) "" else " " + ONES[n % 10]

    internal fun numberWords(n: Long, useAnd: Boolean = false): String {
        if (n < 100) return below100(n.toInt())
        if (n < 1000) {
            val h = (n / 100).toInt()
            val r = (n % 100).toInt()
            val s = ONES[h] + " hundred"
            return if (r == 0) s else s + (if (useAnd) " and " else " ") + below100(r)
        }
        for ((size, name) in listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1000L to "thousand")) {
            if (n >= size) {
                val s = numberWords(n / size, useAnd) + " " + name
                val r = n % size
                if (r == 0L) return s
                val sep = if (useAnd && r < 100) " and " else " "
                return s + sep + numberWords(r, useAnd)
            }
        }
        return n.toString()
    }

    /** 1990 → «nineteen ninety», 2026 → «twenty twenty six»; 2000–2009 — `null`. */
    private fun yearWords(n: Long): String? {
        if (n !in 1100..2099 || n in 2000..2009) return null
        val hi = (n / 100).toInt()
        val lo = (n % 100).toInt()
        return when {
            lo == 0 -> below100(hi) + " hundred"
            lo < 10 -> below100(hi) + " oh " + ONES[lo]
            else -> below100(hi) + " " + below100(lo)
        }
    }

    internal fun ordinalWords(n: Long): String {
        val words = numberWords(n).split(" ")
        val last = words.last()
        val ord = ORD_IRREGULAR[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        return (words.dropLast(1) + ord).joinToString(" ")
    }

    private fun numberVariants(n: Long): List<String> {
        val out = mutableListOf(numberWords(n), numberWords(n, useAnd = true), n.toString())
        if (n in 1..31) out += ordinalWords(n) // «November 16» читают как «November sixteenth»
        yearWords(n)?.let { out += it }
        return out.distinct()
    }

    private fun preNormalize(text: String): String {
        var s = text.lowercase().replace("**", " ")
            .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        s = s.replace(Regex("\\b([ap])\\.\\s?m\\."), "$1m")
        s = s.replace(Regex("\\$(\\d[\\d,]*(?:\\.\\d+)?)"), "$1 dollars")
        s = s.replace("%", " percent ").replace("&", " and ")
        s = s.replace(Regex("(\\d),(\\d{3})"), "$1$2")
        s = s.replace(Regex("(\\d):(\\d\\d)"), "$1 $2")
        s = s.replace(Regex("(\\d)\\.(\\d)"), "$1 point $2")
        s = s.replace(Regex("[-–—/]"), " ")
        for ((a, b) in SYNONYMS) s = s.replace(Regex("\\b$a\\b"), b)
        return s
    }

    private val TOKEN = Regex("\\d+(?:st|nd|rd|th)\\b|\\d+|[a-z]+(?:'[a-z]+)?")
    private val ORDINAL = Regex("(\\d+)(st|nd|rd|th)")

    /** Слова текста; у числа — несколько вариантов прочтения. */
    private fun tokenAlternatives(text: String): List<List<String>> =
        TOKEN.findAll(preNormalize(text)).map { m ->
            val t = m.value
            val ord = ORDINAL.matchEntire(t)
            when {
                ord != null -> listOf(ordinalWords(ord.groupValues[1].toLong()))
                t.all { it.isDigit() } -> if (t.length > 12) listOf(t) else numberVariants(t.toLong())
                else -> listOf(t)
            }
        }.toList()

    /** Все допустимые нормализованные прочтения (перебор ограничен [VARIANT_LIMIT]). */
    internal fun variants(text: String): List<String> {
        var alts = tokenAlternatives(text)
        var total = 1L
        for (a in alts) { total *= a.size; if (total > VARIANT_LIMIT) break }
        if (total > VARIANT_LIMIT) alts = alts.map { listOf(it.first()) }
        var out = listOf("")
        for (a in alts) out = out.flatMap { prefix -> a.map { if (prefix.isEmpty()) it else "$prefix $it" } }
        return out.map { it.split(' ').filter { w -> w.isNotEmpty() }.joinToString(" ") }
    }

    private fun expandContractions(s: String): String = s.split(' ').joinToString(" ") { CONTRACTIONS[it] ?: it }
}
