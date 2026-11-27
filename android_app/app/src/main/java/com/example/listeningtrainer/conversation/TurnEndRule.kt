package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.ModelConfig

/**
 * Судья «мысль закончена?» по тексту, который уже распознан (S04). Чистый Kotlin.
 * [name] — для журнала (`J2`).
 */
interface ThoughtJudge {
    val name: String
    fun complete(text: String): Boolean
}

/**
 * J2 пробы S04 (`tools/probe/turn_end.py`, `J2_WORDS`): мысль не закончена, если последнее слово — союз, предлог,
 * артикль, вспомогательный глагол, заполнитель («um»), «I», притяжательное или указательное слово. Исключение —
 * вспомогательный глагол сразу после местоимения: это короткий ответ («Yes, I do.», «It is.», «I can.»).
 * Пустой текст — «не закончена»: модель ещё ничего не написала, ждём.
 *
 * Проба на записях S03b: все 16 пауз обдумывания после служебного слова («about», «I», «is», «that») — «не закончена»,
 * все 41 конец реплики — «закончена». Слабое место — фраза, оборванная после значимого слова («I would like»).
 */
object LastWordJudge : ThoughtJudge {
    override val name = "J2"

    /** Тот же список, что `J2_WORDS` пробы (сверяет `tools/probe/tests/test_turn_end.py`). */
    val UNFINISHED = setOf(
        // союзы
        "and", "but", "or", "so", "because", "that", "if", "when", "then", "than", "while", "although", "until",
        // предлоги
        "to", "of", "in", "on", "at", "for", "with", "about", "from", "by", "into", "after", "before", "near",
        "under", "over", "without", "around", "through", "between",
        // артикли
        "a", "an", "the",
        // вспомогательные и связки
        "is", "am", "are", "was", "were", "be", "been", "being", "do", "does", "did", "have", "has", "had",
        "will", "would", "can", "could", "should", "must", "may", "might", "shall", "going", "gonna", "wanna",
        "i'm", "it's", "i've", "i'll", "i'd", "don't", "didn't", "can't",
        // заполнители и «I»
        "um", "uh", "uhm", "umm", "erm", "er", "hmm", "mm", "ah", "eh", "i",
        // притяжательные и указательные
        "my", "your", "his", "her", "our", "their", "its", "this", "these", "those", "some", "very", "really",
    )

    val AUXILIARIES = setOf(
        "is", "am", "are", "was", "were", "do", "does", "did", "have", "has", "had", "will", "would", "can",
        "could", "should", "must", "may", "might", "shall", "don't", "didn't", "can't",
    )

    val PRONOUNS = setOf("i", "you", "he", "she", "it", "we", "they", "there")

    private val WORD = Regex("[a-z0-9']+")

    fun words(text: String): List<String> =
        WORD.findAll(text.lowercase().replace('’', '\'').replace('…', ' ')).map { it.value }.toList()

    override fun complete(text: String): Boolean {
        val ws = words(text)
        val last = ws.lastOrNull() ?: return false
        if (last in AUXILIARIES && ws.size >= 2 && ws[ws.size - 2] in PRONOUNS) return true
        return last !in UNFINISHED
    }
}

/** Одно решение судьи на паузе — для журнала: `[Turn] pause N ms: judge J2 complete (0 ms) → end`. */
data class TurnDecision(val pauseMs: Long, val judge: String, val complete: Boolean, val judgeMs: Long, val end: Boolean)

/**
 * Конец реплики после речи (S04, проба `turn-end`): тишина [candidateMs] — кандидат, судья по тексту ([text]).
 * «Закончена» — конец ([TurnEnd.JUDGE]); «не закончена» — ждать и судить снова каждые [rejudgeMs] по свежему тексту;
 * тишина [limitMs] — конец без судьи ([TurnEnd.LONG_SILENCE]). Речь посреди паузы начинает отсчёт заново.
 * Время — время звука (мс тишины от детектора), как у [VadTurnDetector]. Чистый Kotlin.
 */
class TurnEndRule(
    private val judge: ThoughtJudge = LastWordJudge,
    private val candidateMs: Long = ModelConfig.TURN_CANDIDATE_MS,
    private val rejudgeMs: Long = ModelConfig.TURN_REJUDGE_MS,
    private val limitMs: Long = ModelConfig.TURN_LIMIT_MS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var nextMarkMs = candidateMs

    /** Речь: следующая пауза судится с кандидата. */
    fun onSpeech() {
        nextMarkMs = candidateMs
    }

    /**
     * Очередной кадр тишины после речи: [silenceMs] — тишина от последнего кадра речи. Не `null` — реплика кончилась.
     * [onDecision] получает каждое решение судьи (журнал).
     */
    fun onSilence(silenceMs: Long, text: () -> String, onDecision: (TurnDecision) -> Unit): TurnEnd? {
        if (silenceMs >= limitMs) return TurnEnd.LONG_SILENCE
        if (silenceMs < nextMarkMs) return null
        val started = nanoTime()
        val complete = judge.complete(text())
        val ms = (nanoTime() - started) / 1_000_000
        onDecision(TurnDecision(silenceMs, judge.name, complete, ms, end = complete))
        nextMarkMs += rejudgeMs
        return if (complete) TurnEnd.JUDGE else null
    }
}
