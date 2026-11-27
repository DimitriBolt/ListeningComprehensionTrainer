package com.example.listeningtrainer.conversation

/**
 * Похожесть двух расшифровок одной реплики (S03d, сверка потока и файла в debug). Чистый Kotlin.
 *
 * Слова — без регистра и знаков ([Correction.normalize]). Похожесть — доля совпавших слов: сколько слов
 * совпало при выравнивании по порядку (наибольшая общая подпоследовательность), делённое на число слов
 * в более длинном тексте. Одинаковые — 1, ничего общего — 0, оба пустые — 1.
 */
object TranscriptMatch {

    fun words(text: String): List<String> = Correction.normalize(text).split(' ').filter { it.isNotEmpty() }

    fun similarity(a: String, b: String): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        return matched(x, y).toDouble() / maxOf(x.size, y.size)
    }

    /** Длина наибольшей общей подпоследовательности слов. */
    fun matched(x: List<String>, y: List<String>): Int {
        var prev = IntArray(y.size + 1)
        var cur = IntArray(y.size + 1)
        for (i in 1..x.size) {
            for (j in 1..y.size) {
                cur[j] = if (x[i - 1] == y[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[y.size]
    }
}
