package com.example.listeningtrainer.conversation

/**
 * Потоковая нарезка (D38). Чистый Kotlin (инвариант 3). Текст учителя приходит кусками;
 * группа уходит в синтез, как только её текст уже не изменит продолжение. [SpeechPlanner] не
 * меняется — меняется только, **когда** его вызывают. Правило — `docs/research/02c-results.md`,
 * «Как в S02d подавать поток в SpeechPlanner», с поправкой S02d (п. 2, жирный текст):
 *
 * 1. Безопасная точка — после `, ; : . ! ?` и пробелов за ним или после перевода строки, и только
 *    если после неё уже пришёл непробельный знак. Точка после одиночной цифры (`2. `) — не она.
 * 2. Незакрытые `[` или `(` (сноска) останавливают точку перед собой. Жирный `**…**` не режется
 *    посередине: точка внутри пары — не точка, незакрытая `**` на последней строке — граница.
 *    (В пробе 5 из 63 ответов разрезали заголовок посередине — пауза перед ним выходила 600 мс
 *    вместо 1600.)
 * 3. Префикс до точки чистится от сносок ([SpeechText.clean]) и режется [SpeechPlanner.plan];
 *    отдаются все группы, кроме последней — её ещё может изменить продолжение.
 * 4. Конец ответа ([finish]) — режется весь текст, отдаётся остаток.
 *
 * **Пауза определяется позже** ([pauseAfter]): группа уходит в синтез с текстом, а пауза после
 * неё берётся из самой свежей нарезки в момент, когда группа уже прозвучала.
 *
 * Если текст уже отданной группы разошёлся с новой нарезкой, это считается ([divergences]);
 * отданное не переотдаётся, нарезка продолжается со следующей группы.
 */
class StreamingSpeechPlanner(
    /** Нарезка готового текста — [SpeechPlanner.plan]; в тестах можно подменить. */
    private val plan: (String) -> List<SpeechChunk> = SpeechPlanner()::plan,
) {

    private val raw = StringBuilder()
    private val emitted = mutableListOf<SpeechChunk>()
    /** Нарезка самого длинного обработанного префикса (после [finish] — всего текста). */
    private var latest: List<SpeechChunk> = emptyList()
    private var lastCut = 0

    var finished = false
        private set

    /** Номера групп (с 1), текст которых разошёлся с новой нарезкой. */
    val divergences = mutableListOf<Int>()

    /** Сколько групп отдано. */
    val emittedCount: Int get() = emitted.size

    /** Весь пришедший текст (со сносками). */
    val rawText: String get() = raw.toString()

    /** Весь текст после [finish] (без сносок; при обрезке — без незаконченной фразы). */
    private var finalText = ""

    /** Текст для экрана: без сносок и без хвоста незаконченной сноски. */
    fun screenText(): String = if (finished) finalText else SpeechText.forScreen(raw.toString())

    /** Добавить кусок текста; вернуть новые группы, готовые к синтезу. */
    fun feed(delta: String): List<SpeechChunk> {
        check(!finished) { "stream already finished" }
        raw.append(delta)
        val text = raw.toString()
        val cut = safeCut(text)
        if (cut <= lastCut) return emptyList()
        lastCut = cut
        val chunks = plan(SpeechText.clean(text.substring(0, cut)))
        latest = chunks
        return emitFrom(chunks.dropLast(1))
    }

    /**
     * Ответ кончился: отдать остаток. [dropUnfinished] — ответ обрезан пределом длины (S02e):
     * незаконченная фраза в конце не звучит (уже отданные группы не отзываются).
     */
    fun finish(dropUnfinished: Boolean = false): List<SpeechChunk> {
        if (finished) return emptyList()
        finished = true
        val clean = SpeechText.clean(raw.toString()).trim()
        finalText = if (dropUnfinished) ReplyText.dropUnfinished(clean) else clean
        val chunks = plan(finalText)
        latest = chunks
        return emitFrom(chunks)
    }

    /**
     * Пауза после отданной группы [index] (с 0) — по самой свежей нарезке. Вызывать, когда группа
     * прозвучала и пришла следующая (или ответ кончился).
     */
    fun pauseAfter(index: Int): Long =
        latest.getOrNull(index)?.takeIf { it.text == emitted[index].text }?.pauseMs ?: emitted[index].pauseMs

    private fun emitFrom(chunks: List<SpeechChunk>): List<SpeechChunk> {
        val n = minOf(emitted.size, chunks.size)
        for (i in 0 until n) {
            if (chunks[i].text != emitted[i].text && (i + 1) !in divergences) divergences.add(i + 1)
        }
        if (chunks.size <= emitted.size) return emptyList()
        val new = chunks.subList(emitted.size, chunks.size).toList()
        emitted.addAll(new)
        return new
    }

    companion object {
        private val SAFE = Regex("""(?<=[,;:!?])\s+|(?<=[.])(?<!\b\d\.)\s+|\n\s*""")
        private val BOLD_PAIR = Regex("""\*\*.*?\*\*""")
        private val BOLD_MARK = Regex("""\*\*""")

        /** Пробел в смысле `\s` регулярных выражений (как у [SpeechPlanner]). */
        private fun isRegexSpace(ch: Char) = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\u000B' || ch == '\u000C' || ch == '\r'

        /** Позиция последней безопасной точки (правило в описании класса); 0 — её нет. */
        fun safeCut(text: String): Int {
            var limit = SpeechText.openBracketLimit(text)
            // Жирные пары — так же, как их находит SpeechPlanner (в пределах строки, слева направо).
            val pairs = BOLD_PAIR.findAll(text).map { it.range }.toList()
            val lastLineStart = text.lastIndexOf('\n') + 1
            for (mark in BOLD_MARK.findAll(text)) {
                val inPair = pairs.any { mark.range.first in it }
                if (!inPair && mark.range.first >= lastLineStart) limit = minOf(limit, mark.range.first)
            }
            var cut = 0
            for (m in SAFE.findAll(text.substring(0, limit))) {
                val end = m.range.last + 1
                if (end >= text.length || isRegexSpace(text[end])) continue
                if (pairs.any { end > it.first && end <= it.last }) continue // внутри **…**
                cut = end
            }
            return cut
        }
    }
}
