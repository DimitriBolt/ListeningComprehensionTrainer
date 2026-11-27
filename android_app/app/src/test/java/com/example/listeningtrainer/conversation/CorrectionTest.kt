package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionTest {

    @Test
    fun `OK or the same sentence - no correction`() {
        assertEquals(Correction.Verdict.None, Correction.check("Green.", "OK"))
        assertEquals(Correction.Verdict.None, Correction.check("Green.", "OK."))
        assertEquals(Correction.Verdict.None, Correction.check("Green.", " ok "))
        assertEquals(Correction.Verdict.None, Correction.check("Yes, it is hot.", "yes it is hot"))
        assertEquals(Correction.Verdict.None, Correction.check("I don't understand.", "I don’t understand"))
        assertEquals(Correction.Verdict.None, Correction.check("Green.", ""))
    }

    @Test
    fun `a correction - quotes and the teacher's frame are removed`() {
        assertEquals(Correction.Verdict.Fix("I have a dog."), Correction.check("I have dog.", "I have a dog."))
        assertEquals(Correction.Verdict.Fix("I have a dog."), Correction.check("I have dog.", "\"I have a dog.\""))
        assertEquals(Correction.Verdict.Fix("I have a dog"), Correction.check("I have dog.", "I think you meant \"I have a dog\", right?"))
        // Поправка добавляет слова, но слова ученика сохраняет.
        assertEquals(Correction.Verdict.Fix("What is the gas price?"), Correction.check("Gas price?", "What is the gas price?"))
    }

    @Test
    fun `an answer instead of a correction is rejected`() {
        val v = Correction.check("Where you live?", "I live in Tucson.")
        assertTrue(v is Correction.Verdict.Rejected)
        assertEquals(1.0 / 3, (v as Correction.Verdict.Rejected).similarity, 1e-9)
    }

    @Test
    fun `similarity of the probe's corrections stays above the threshold`() {
        // Худшие верные поправки пробы S02f (docs/research/02f-corrector.md).
        for ((student, fix) in listOf(
            "How much it costs?" to "How much does it cost?",
            "She don't like coffee." to "She doesn't like coffee.",
            "Yesterday I go to the shop and buy some apples." to "Yesterday I went to the shop and bought some apples.",
            "I very like this song." to "I really like this song.",
            "I have two cat." to "I have two cats.",
        )) assertTrue("$student → $fix", Correction.similarity(fix, student) >= 0.6)
    }

    @Test
    fun `spoken phrase`() {
        assertEquals("I think you meant \"I have a dog\", right?", Correction.phrase("I have a dog."))
        assertEquals("I think you meant \"Where do you live?\", right?", Correction.phrase("Where do you live?"))
        // Ровно один вопрос в конце — правило первого вопроса (D48) поправку пропускает целиком.
        val p = Correction.phrase("Where do you live?")
        assertEquals(p.length, ReplyText.correctionEnd(p))
    }

    @Test
    fun `long utterance - one corrected sentence is accepted, an answer is still rejected (S05, D80)`() {
        // Склеенная длинная реплика без знаков, как пишет поток.
        val student = "yesterday I got up early and I make coffee then I go to the park with my dog " +
            "it was very nice weather and we walk for one hour after that I come back home and cook breakfast"
        // S05b (D87): из предложения — участок с ошибкой и слова вокруг.
        assertEquals(
            Correction.Verdict.Fix("I went to the park"),
            Correction.check(student, "Then I went to the park with my dog."),
        )
        assertTrue(Correction.check(student, "That sounds lovely! What did you cook?") is Correction.Verdict.Rejected)
        // Поправщик вернул всю реплику целиком — тоже поправка, но только первый участок (одна поправка, D47).
        val whole = student.replace("make", "made").replace(" go ", " went ")
        assertEquals(Correction.Verdict.Fix("I made coffee"), Correction.check(student, whole))
    }

    // ---- S05b (D87): участок с ошибкой у длинной реплики ----------------------------------------------------------

    private val long = "last weekend my son and me went to the lake we wanted to catch fish but the water was very cold " +
        "and the fish don't want to eat so we catched nothing but we had a good time"

    @Test
    fun `short utterance - the whole correction as before`() {
        val student = "Yesterday I go to the shop and buy some apples." // 10 слов
        val fix = "Yesterday I went to the shop and bought some apples."
        assertEquals(Correction.Verdict.Fix(fix), Correction.check(student, fix))
    }

    @Test
    fun `change at the start`() {
        val student = "me and my son went to the lake last weekend and we wanted to catch some fish there"
        val fix = "My son and I went to the lake last weekend and we wanted to catch some fish there"
        assertEquals(Correction.Verdict.Fix("My son and I went"), Correction.check(student, fix))
    }

    @Test
    fun `change in the middle - three words around`() {
        val fix = long.replace("son and me", "son and I")
        assertEquals(Correction.Verdict.Fix("my son and I went"), Correction.check(long, fix))
    }

    @Test
    fun `change at the end`() {
        val student = "we had a very good time at the lake and on the way home my son sleep"
        val fix = "we had a very good time at the lake and on the way home my son slept"
        assertEquals(Correction.Verdict.Fix("my son slept"), Correction.check(student, fix))
    }

    @Test
    fun `two changes - only the first one`() {
        val fix = long.replace("son and me", "son and I").replace("catched", "caught")
        assertEquals(Correction.Verdict.Fix("my son and I went"), Correction.check(long, fix))
        val v = Correction.check(long, long.replace("catched", "caught"))
        assertEquals(Correction.Verdict.Fix("we caught nothing"), v)
    }

    @Test
    fun `long change - context is dropped to fit 12 words, longer than 15 - no correction`() {
        val student = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen"
        // Изменено 10 слов подряд: контекст ужимается до 12 слов всего.
        val ten = student.replace("five six seven eight nine ten eleven twelve thirteen fourteen", "a b c d e f g h i j")
        val v = Correction.excerpt(student, ten) as Correction.Verdict.Fix
        assertEquals(12, v.text.split(' ').size)
        assertTrue(v.text, v.text.contains("a b c d e f g h i j"))
        // 14 слов подряд — один участок без слов вокруг.
        val fourteen = "zero " + student.replace(
            "two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen",
            "b c d e f g h i j k l m n o",
        )
        assertEquals(Correction.Verdict.Fix("b c d e f g h i j k l m n o"), Correction.excerpt("zero $student", fourteen))
        // 16 слов подряд — поправки нет.
        val longFix = "a b c d e f g h i j k l m n o p seventeen"
        assertEquals(Correction.Verdict.SpanTooLong(16), Correction.excerpt(student, longFix))
    }

    @Test
    fun `excerpt starts at a pronoun before the change and does not end on a function word`() {
        val st = "yesterday I got up early and I make coffee then I go to the park with my dog it was very nice weather"
        // «early and I made coffee then I» → «I made coffee».
        assertEquals(Correction.Verdict.Fix("I made coffee"), Correction.check(st, st.replace("make", "made")))
    }

    @Test
    fun `alignment ignores case and signs, words before and after a one-sentence fix are not changes`() {
        val st = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf(Correction.Change(2, 3, 1, 2)), Correction.changes(st, listOf("b", "x", "d")))
        assertEquals(emptyList<Correction.Change>(), Correction.changes(st, listOf("b", "c")))
        assertEquals(Correction.Verdict.None, Correction.excerpt(long, "Last weekend, my son and me went to the lake."))
    }
}
