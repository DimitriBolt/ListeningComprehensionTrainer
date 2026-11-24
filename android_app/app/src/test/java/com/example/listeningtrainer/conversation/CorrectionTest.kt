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
}
