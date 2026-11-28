package com.example.listeningtrainer.conversation

/**
 * Команды голосом (S05, D82). Чистый Kotlin (инвариант 3). Узнаются по тексту реплики ученика (поток, после конца):
 * команда — если реплика целиком, без знаков и регистра, не длиннее [MAX_WORDS] слов и совпала с одной из [PHRASES].
 * «What?» и «I don't understand» — не команды: по-прежнему «скажи проще» (D55).
 *
 * S05b (D84): короткие слова распознавание пишет с ошибками («Slowware», «Igain»), поэтому одно слово узнаётся терпимо
 * ([tolerant]): на «slow…» — Slower; в [AGAIN_MAX_EDITS] правки от «again» — Again; в [STOP_MAX_EDITS] правку от «stop» —
 * Stop. Чтобы обычный ответ («Rain.», «Spain.», «Shop.», «Top.») не стал командой, у слова-Again первая буква гласная и
 * есть «g», у слова-Stop — начало «st» (проба `s05b --part commands`).
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
        /** Одно слово, которое начинается так, — Slower («slowware», «slowlyer»; «Slow.» как ответ — допустимая цена). */
        const val SLOW_PREFIX = "slow"
        /** Одно слово не дальше стольких правок от «again» — Again («igain», «agane»). */
        const val AGAIN_MAX_EDITS = 2
        /** Одно слово не дальше стольких правок от «stop» — Stop («stap», «stops»). */
        const val STOP_MAX_EDITS = 1

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
            return byPhrase[n] ?: tolerant(n)
        }

        private const val VOWELS = "aeiou"

        /** Одно слово с ошибкой распознавания (D84); `null` — не команда. [word] уже без знаков и регистра. */
        fun tolerant(word: String): VoiceCommand? {
            if (word.isEmpty() || ' ' in word) return null
            val w = word.replace("'", "")
            return when {
                w.startsWith(SLOW_PREFIX) -> SLOWER
                w.first() in VOWELS && 'g' in w && edits(w, "again") <= AGAIN_MAX_EDITS -> AGAIN
                w.startsWith("st") && edits(w, "stop") <= STOP_MAX_EDITS -> STOP
                else -> null
            }
        }

        /** Расстояние правки (Левенштейн): вставка, удаление, замена буквы. */
        fun edits(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
        }
    }
}

/**
 * Поддакивание (S05c, D90): «Mhm», «Mm-hmm», «Uh-huh», «Aha», «Oho», «Hmm», «Uh», «Um» с вариантами написания. Чистый Kotlin
 * (инвариант 3). Полное перебивание, текст которого — только поддакивание, учителю не уходит: учитель продолжает с прерванной
 * группы. Короткий звук (D85) с таким текстом — не команда, как и раньше.
 *
 * Слово сравнивается без регистра, знаков и дефиса, повторы букв сжаты: «Mmm-hmm» → «mhm», «Uhh» → «uh», «Hmmm» → «hm».
 * Реплика — поддакивание, если в ней не больше [MAX_WORDS] слов и каждое из [WORDS] (или вся реплика слитно: «uh huh»).
 * Список — только здесь.
 */
object Backchannel {
    const val MAX_WORDS = 4

    /** Слова после сжатия повторов букв. */
    val WORDS: Set<String> = setOf(
        "m", "mhm", "hm", // mm, mhm, mm-hmm, m-hm, hmm
        "uhuh", // uh-huh, uh huh
        "aha", // aha, a-ha, ahha
        "oho", // oho
        "uh", "um", "uhm", // uh, uhh, um, umm, uhm
    )

    private val NON_WORD = Regex("""[^\p{L}\s]""")
    private val SPACES = Regex("""\s+""")

    /** Без регистра, знаков и дефиса; повторы букв сжаты. */
    private fun squeeze(word: String): String {
        val sb = StringBuilder()
        for (ch in word) if (sb.isEmpty() || sb.last() != ch) sb.append(ch)
        return sb.toString()
    }

    /** Текст — только поддакивание. */
    fun of(text: String): Boolean {
        val n = text.lowercase().replace("-", "").replace(NON_WORD, " ").replace(SPACES, " ").trim()
        if (n.isEmpty()) return false
        val words = n.split(' ')
        if (words.size > MAX_WORDS) return false
        return words.all { squeeze(it) in WORDS } || squeeze(n.replace(" ", "")) in WORDS
    }
}
