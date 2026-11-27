package com.example.listeningtrainer.conversation

/**
 * Поправка ошибки ученика отдельным запросом «поправщик» (S02f, D47). Чистый Kotlin (инвариант 3).
 * Копия проверки — `corrector_says_ok`, `containment` в `tools/probe/corrector.py`.
 *
 * Поправщик отвечает исправленной фразой или `OK`. Поправки нет, если пришло `OK` или фраза совпала с
 * репликой ученика без учёта регистра и знаков. Поправка, слишком непохожая на реплику ученика
 * (модель ответила на вопрос, а не поправила его), не берётся.
 */
object Correction {

    sealed interface Verdict {
        /** Поправка: правильная фраза ученика (без кавычек). */
        data class Fix(val text: String) : Verdict
        /** Ошибки нет. */
        data object None : Verdict
        /** Ответ не похож на поправку: доля слов ученика, которые остались в ответе, ниже порога. */
        data class Rejected(val similarity: Double) : Verdict
    }

    /**
     * Порог сходства (проба S02f): в верных поправках остаётся не меньше 0,75 слов ученика («How much it
     * costs?» → «How much does it cost?»: нет только «costs»). Ответ вместо поправки («Where you live?» →
     * «I live in Tucson.») сохраняет мало слов ученика.
     */
    const val MIN_SIMILARITY = 0.5

    private val NON_WORD = Regex("""[^\p{L}\p{N}\s]""")
    private val SPACES = Regex("""\s+""")
    private val LEAD = Regex("""^\s*I think you meant,?\s*""", RegexOption.IGNORE_CASE)
    private val RIGHT_TAG = Regex(""",?\s*right\s*\?\s*$""", RegexOption.IGNORE_CASE)

    /** Без регистра и знаков: «doesn't» → «doesn t». */
    fun normalize(s: String): String =
        s.replace('’', '\'').lowercase().replace(NON_WORD, " ").replace(SPACES, " ").trim()

    private fun words(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /**
     * Доля слов реплики [student], которые остались в [fix]. Поправка добавляет служебные слова и меняет
     * формы, но слова ученика в основном сохраняет: «Gas price?» → «What is the gas price?» — 1,0.
     *
     * S05 (D80): на длинной реплике поправщик отвечает только предложением с ошибкой, и доля от всех слов реплики
     * мала. Поэтому доля считается в лучшем окне подряд идущих слов ученика длиной в поправку (не длиннее реплики):
     * у короткой реплики окно — вся реплика, как раньше.
     */
    fun similarity(fix: String, student: String): Double {
        val sw = words(student)
        if (sw.isEmpty()) return 1.0
        val fixWords = words(fix)
        val fw = fixWords.toSet()
        val n = minOf(sw.size, maxOf(1, fixWords.size))
        var count = (0 until n).count { sw[it] in fw }
        var best = count
        for (i in n until sw.size) {
            if (sw[i] in fw) count++
            if (sw[i - n] in fw) count--
            if (count > best) best = count
        }
        return best.toDouble() / n
    }

    fun check(student: String, answer: String): Verdict {
        var a = answer.trim()
        // На случай, если модель всё же заговорила как учитель: «I think you meant "…", right?».
        if (LEAD.containsMatchIn(a)) a = a.replace(LEAD, "").replace(RIGHT_TAG, "")
        a = a.trim().trim('"', '“', '”').trim()
        val n = normalize(a)
        if (n.isEmpty() || n == "ok" || n == normalize(student)) return Verdict.None
        val sim = similarity(a, student)
        return if (sim < MIN_SIMILARITY) Verdict.Rejected(sim) else Verdict.Fix(a)
    }

    /**
     * Что звучит: «I think you meant "…", right?» (D47). Точка в конце фразы ученика убирается —
     * после кавычек идёт «, right?»; вопросительный знак остаётся.
     */
    fun phrase(fix: String): String = "I think you meant \"${fix.trim().trimEnd('.').trim()}\", right?"
}
