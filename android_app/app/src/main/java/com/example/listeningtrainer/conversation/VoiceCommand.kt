package com.example.listeningtrainer.conversation

/**
 * Команды голосом (S05, D82). Чистый Kotlin (инвариант 3). Узнаются по тексту реплики ученика (поток, после конца):
 * команда — если реплика целиком, без знаков и регистра, не длиннее [MAX_WORDS] слов и совпала с одной из [PHRASES].
 * «What?» и «I don't understand» — не команды: по-прежнему «скажи проще» (D55).
 *
 * Список — только здесь. Варианты подобраны пробой `python tools/probe/probe.py s05 --part commands` (синтез голоса,
 * `gpt-live-transcribe`, `docs/research/05-probes.md`); проба читает этот список из файла.
 */
enum class VoiceCommand(val label: String) {
    /** Учитель замолкает (если ещё говорил), дальше обычная запись: «говори» и слушание. */
    STOP("stop"),
    /** Последняя реплика учителя слово в слово, тем же звуком. */
    AGAIN("again"),
    /** Та же реплика один раз медленнее, с более длинными паузами. */
    SLOWER("slower");

    companion object {
        const val MAX_WORDS = 3

        /** Фразы каждой команды — без знаков, в нижнем регистре. */
        val PHRASES: Map<VoiceCommand, List<String>> = mapOf(
            STOP to listOf(
                "stop", "wait", "stop please", "please stop", "wait please", "please wait", "wait a moment",
                "wait a minute", "wait a second", "stop stop", "wait wait",
            ),
            AGAIN to listOf(
                "again", "repeat", "say again", "one more time", "again please", "please repeat", "repeat please",
                "say it again", "repeat that", "once more", "again again",
            ),
            SLOWER to listOf(
                "slower", "slow down", "more slowly", "slower please", "slowly please", "please slower",
                "slowly", "speak slower", "speak slowly", "say slower", "slow please",
            ),
        )

        private val NON_WORD = Regex("""[^\p{L}\p{N}\s']""")
        private val SPACES = Regex("""\s+""")
        private val byPhrase: Map<String, VoiceCommand> =
            PHRASES.flatMap { (c, list) -> list.map { it to c } }.toMap()

        /** Без регистра и знаков: «Say again!» → «say again». */
        fun normalize(text: String): String =
            text.replace('’', '\'').lowercase().replace(NON_WORD, " ").replace(SPACES, " ").trim()

        /** Команда или `null` — обычная реплика (идёт учителю). */
        fun of(text: String): VoiceCommand? {
            val n = normalize(text)
            if (n.isEmpty() || n.split(' ').size > MAX_WORDS) return null
            return byPhrase[n]
        }
    }
}
