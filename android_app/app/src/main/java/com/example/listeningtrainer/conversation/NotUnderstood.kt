package com.example.listeningtrainer.conversation

/**
 * Сколько раз подряд ученик явно сказал, что не понял учителя (S02f, D43). Чистый Kotlin (инвариант 3).
 * Копия — `is_marker`, `streak` в `tools/probe/tutor_dialog.py`.
 *
 * Русское слово учитель может дать только при числе 3 и больше — после двух упрощений. Модель сама
 * считает плохо (в пробе S02e русское слово приходило на шаг раньше), поэтому счёт ведёт приложение и
 * добавляет его к запросу учителя служебной строкой [note].
 *
 * Явные маркеры: «What?», «Pardon?», «Sorry?», «I don't understand…», «I don't know what…», «What do you
 * mean?», «Can you repeat?», «Again, please», пустая реплика или «...». Ответ не по теме без маркера
 * оценивает сам учитель и русского слова не даёт. Молчание до учителя не доходит и счёт не меняет.
 */
object NotUnderstood {

    private val NON_WORD = Regex("""[^\p{L}\p{N}\s]""")
    private val SPACES = Regex("""\s+""")
    /** Слова-вежливость и заминки по краям реплики: «Sorry, what?» = «what», «Say it again» = «say it». */
    private val LEAD = Regex("""^(?:(?:sorry|excuse me|oh|um|uh|hmm|teacher|please|but|i m sorry)\s+)+""")
    private val TRAIL = Regex("""(?:\s+(?:please|sorry|teacher|again))+$""")

    private val WHOLE = setOf(
        "", "what", "pardon", "pardon me", "sorry", "huh", "excuse me", "again", "repeat", "what what",
        "say", "say it", "say that", "say it again", "one more time", "slower", "slowly", "more slowly", "i m lost",
    )
    private val START = Regex(
        "^(?:" +
            """i (?:don t|do not|didn t|did not|can t|cannot|could not|couldn t) (?:understand|get it|follow|catch)""" +
            """|i (?:don t|do not|didn t|did not) know what""" +
            """|i (?:don t|do not) know this word""" +
            """|what (?:do|did) you (?:mean|say)""" +
            """|what is (?:that|this) word""" +
            """|what does .+ mean""" +
            """|(?:can|could) you (?:repeat|say (?:it|that)|speak (?:more )?slowly|say it slower)""" +
            """|(?:please )?repeat""" +
            ")(?:\\s|$)",
    )

    fun normalize(s: String): String =
        s.replace('’', '\'').lowercase().replace(NON_WORD, " ").replace(SPACES, " ").trim()

    /** Реплика ученика — явное «не понял»? */
    fun isMarker(text: String): Boolean {
        var n = normalize(text)
        n = n.replace(LEAD, "").replace(TRAIL, "").trim()
        return n in WHOLE || START.containsMatchIn(n)
    }

    /** Сколько реплик подряд с конца [studentTexts] (последняя — текущая) — явное «не понял». */
    fun streak(studentTexts: List<String>): Int = studentTexts.asReversed().takeWhile { isMarker(it) }.size

    /** С какого счёта разрешено одно русское слово (D43, D55): после двух упрощений. */
    const val RUSSIAN_FROM = 3

    /**
     * Служебная строка к реплике ученика; `null` — счёт 0, строки нет. Строка сама говорит модели, что
     * делать (D58): выбирать между ней и строкой длины модели не нужно — строки длины при ней нет.
     * Копия — `note` в `tools/probe/tutor_dialog.py`.
     */
    fun note(count: Int): String? {
        if (count <= 0) return null
        val head = "(App note: the student did not understand you $count ${if (count == 1) "time" else "times"} in a row."
        return if (count < RUSSIAN_FROM) "$head $SIMPLER Only English: not one Russian letter.)"
        else "$head Say one key word in Russian, like this: \"Noon\" is \"полдень\". Then go on in English. $SHORT)"
    }

    private const val SHORT = "Keep it short: 1 or 2 short sentences, 15 words at most, then your question."
    private const val SIMPLER = "Say the same thing again, simpler and with other words. $SHORT"
}
