package com.example.listeningtrainer.conversation

/**
 * Сноски поиска в тексте учителя (D37). Чистый Kotlin (инвариант 3).
 *
 * Responses с `web_search` вставляет сноску прямо в текст: `… by station. ([site.com](https://site.com/…?utm_source=openai))`.
 * Вслух её не читаем: текст чистится **до** нарезки. Источники показываются на экране отдельно
 * (поле `annotations`). Перенос `clean_for_speech` из `tools/probe/speech_planner.py` (проба S02c).
 */
object SpeechText {

    /** `([site](url))`, в том числе несколько через запятую, — сноска целиком. */
    private val PAREN_LINK = Regex("""[ \t]*\(\s*\[[^\]\n]*]\([^)\s]*\)(?:\s*,?\s*\[[^\]\n]*]\([^)\s]*\))*\s*\)""")
    /** `[текст](url)` внутри фразы: текст остаётся, адрес уходит. */
    private val INLINE_LINK = Regex("""\[([^\]\n]*)]\([^)\s]*\)""")
    /** Голый адрес, возможно в скобках. */
    private val BARE_URL = Regex("""[ \t]*\(?\s*(?:https?://|www\.)\S+?\s*\)?(?=[\s.,;:!?]*(?:\s|$))""")
    private val SPACE_BEFORE_PUNCT = Regex("""[ \t]+([.,;:!?])""")
    private val MULTI_SPACE = Regex("""[ \t]{2,}""")

    /** Убирает сноски, ссылки и адреса, которые пришли в тексте ответа. */
    fun clean(text: String): String = text
        .replace(PAREN_LINK, "")
        .replace(INLINE_LINK) { it.groupValues[1] }
        .replace(BARE_URL, "")
        .replace(SPACE_BEFORE_PUNCT) { it.groupValues[1] }
        .replace(MULTI_SPACE, " ")

    /**
     * Позиция первой незакрытой `[` или `(` (сноска ещё идёт) или длина текста. Текст до неё можно
     * показывать и резать: сноску нельзя вычистить, пока она не пришла целиком.
     */
    fun openBracketLimit(text: String): Int {
        var square = 0
        var round = 0
        var firstOpen = -1
        text.forEachIndexed { i, ch ->
            when (ch) {
                '[' -> { if (square == 0 && round == 0) firstOpen = i; square++ }
                ']' -> if (square > 0) square--
                '(' -> { if (square == 0 && round == 0) firstOpen = i; round++ }
                ')' -> if (round > 0) round--
            }
            if (square == 0 && round == 0) firstOpen = -1
        }
        return if (firstOpen >= 0) firstOpen else text.length
    }

    /** Текст для экрана, пока ответ идёт: без хвоста незаконченной сноски и без сносок. */
    fun forScreen(text: String): String = clean(text.substring(0, openBracketLimit(text))).trimEnd()
}
