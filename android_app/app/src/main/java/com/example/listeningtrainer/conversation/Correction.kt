package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.ModelConfig

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
        /** Длинная реплика (S05b, D87): изменённый участок длиннее [ModelConfig.CORRECTION_SPAN_REJECT_WORDS] слов. */
        data class SpanTooLong(val words: Int) : Verdict
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
        if (sim < MIN_SIMILARITY) return Verdict.Rejected(sim)
        return excerpt(student, a)
    }

    /** Начало участка поправки — с такого слова, если оно есть среди слов до изменения (S05b). */
    private val SUBJECTS = setOf("i", "we", "you", "he", "she", "they", "it", "my", "our", "his", "her", "their")
    /** Служебные слова: в конце участка поправки не стоят (S05b). */
    private val FUNCTION_WORDS = SUBJECTS + setOf(
        "a", "an", "the", "to", "and", "or", "but", "so", "then", "of", "in", "on", "at", "for", "with",
        "is", "are", "was", "were",
    )

    /** Слово для сравнения: без регистра и знаков («Doesn't,» → «doesnt»). */
    private fun key(token: String): String = token.lowercase().filter { it.isLetterOrDigit() }

    private fun tokens(s: String): List<String> = s.trim().split(SPACES).filter { key(it).isNotEmpty() }

    /** Изменённый участок: слова ученика `[i1, i2)` заменены словами поправки `[j1, j2)`. */
    data class Change(val i1: Int, val i2: Int, val j1: Int, val j2: Int)

    /**
     * Участки, где поправка [fix] отличается от реплики [student] (по словам, без регистра и знаков), по порядку.
     * Выравнивание — наибольшая общая подпоследовательность слов. Слова ученика до начала и после конца поправки
     * (поправщик вернул одно предложение из длинной реплики) изменением не считаются. Копия — `changes` в
     * `tools/probe/corrector.py`.
     */
    fun changes(student: List<String>, fix: List<String>): List<Change> {
        val n = student.size
        val m = fix.size
        // lcs[i][j] — длина общей подпоследовательности student[i:] и fix[j:].
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (student[i] == fix[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = mutableListOf<Change>()
        var i = 0
        var j = 0
        var start: Pair<Int, Int>? = null
        fun close() {
            start?.let { (si, sj) -> out += Change(si, i, sj, j) }
            start = null
        }
        while (i < n || j < m) {
            if (i < n && j < m && student[i] == fix[j]) {
                close(); i++; j++
            } else {
                if (start == null) start = i to j
                if (j >= m || (i < n && lcs[i + 1][j] >= lcs[i][j + 1])) i++ else j++
            }
        }
        close()
        if (out.isNotEmpty() && out.first().let { it.i1 == 0 && it.j1 == 0 && it.j2 == 0 }) out.removeAt(0)
        if (out.isNotEmpty() && out.last().let { it.i2 == n && it.j2 == m && it.j1 == m }) out.removeAt(out.lastIndex)
        return out
    }

    /**
     * Поправка длинной реплики — участок с ошибкой (S05b, D87). Реплика не длиннее [ModelConfig.CORRECTION_MAX_WORDS]
     * слов — поправка целиком, как раньше. Иначе — первый изменённый участок поправки и до
     * [ModelConfig.CORRECTION_CONTEXT_WORDS] слов вокруг, всего не больше [ModelConfig.CORRECTION_MAX_WORDS] (слова вокруг
     * убираются, пока не уложится); сам участок длиннее [ModelConfig.CORRECTION_SPAN_REJECT_WORDS] — поправки нет.
     */
    fun excerpt(student: String, fix: String): Verdict {
        val st = tokens(student)
        if (st.size <= ModelConfig.CORRECTION_MAX_WORDS) return Verdict.Fix(fix)
        val ft = tokens(fix)
        val change = changes(st.map(::key), ft.map(::key)).firstOrNull() ?: return Verdict.None
        val core = change.j2 - change.j1
        if (core > ModelConfig.CORRECTION_SPAN_REJECT_WORDS) return Verdict.SpanTooLong(core)
        var before = minOf(ModelConfig.CORRECTION_CONTEXT_WORDS, change.j1)
        var after = minOf(ModelConfig.CORRECTION_CONTEXT_WORDS, ft.size - change.j2)
        // Длиннее предела — убрать слова вокруг, сначала дальние после участка.
        while (core + before + after > ModelConfig.CORRECTION_MAX_WORDS && before + after > 0) {
            if (after >= before) after-- else before--
        }
        // Понятнее сам по себе (проба S05b): слова до участка — с местоимения, если оно там есть («early and I made» →
        // «I made»); служебные слова в конце после участка не нужны («coffee then I» → «coffee»).
        val lead = (change.j1 - before until change.j1).firstOrNull { key(ft[it]) in SUBJECTS }
        if (lead != null) before = change.j1 - lead
        while (after > 0 && key(ft[change.j2 + after - 1]) in FUNCTION_WORDS) after--
        val words = ft.subList(change.j1 - before, change.j2 + after)
        if (words.isEmpty()) return Verdict.None
        return Verdict.Fix(words.joinToString(" ").trimEnd(',', ';', ':', '-', ' '))
    }

    /**
     * Что звучит: «I think you meant "…", right?» (D47). Точка в конце фразы ученика убирается —
     * после кавычек идёт «, right?»; вопросительный знак остаётся.
     */
    fun phrase(fix: String): String = "I think you meant \"${fix.trim().trimEnd('.').trim()}\", right?"
}
