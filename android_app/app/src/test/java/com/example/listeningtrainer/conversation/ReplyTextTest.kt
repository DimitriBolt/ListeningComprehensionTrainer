package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Мера реплики, обрезка и русское слово (S02e). */
class ReplyTextTest {

    @Test
    fun `metrics - words, sentences, question at the end, russian`() {
        assertEquals(ReplyText.Metrics(9, 2, question = true, russian = false), ReplyText.metrics("It is 15 degrees. Is that warm or cold?"))
        // Поправка ученика с вопросом внутри кавычек — одно предложение, конец — вопрос.
        val m = ReplyText.metrics("I think you meant \"What is the temperature in Tucson?\", right? At night or at noon?")
        assertEquals(ReplyText.Metrics(16, 2, question = true, russian = false), m)
        assertEquals(ReplyText.Metrics(4, 2, question = false, russian = true), ReplyText.metrics("\"Noon\" is \"полдень\". Okay."))
        assertEquals(ReplyText.Metrics(0, 0, question = false, russian = false), ReplyText.metrics("  "))
        assertTrue(ReplyText.metrics("Do you like **tea**?**").question)
        assertEquals("words 3, sentences 1, question yes, russian no", ReplyText.metrics("Do you agree?").toString())
        assertEquals(3, ReplyText.metrics("I don't know.").words)
    }

    @Test
    fun `cut reply - unfinished sentence dropped, nothing finished - kept`() {
        assertEquals("It is warm. Do you like it?", ReplyText.dropUnfinished("It is warm. Do you like it? And the wind"))
        assertEquals("He said \"Hi.\"", ReplyText.dropUnfinished("He said \"Hi.\" And then"))
        assertEquals("It is 3.5 degrees.", ReplyText.dropUnfinished("It is 3.5 degrees. Then it"))
        assertEquals("No end here", ReplyText.dropUnfinished("No end here"))
        assertEquals("Done!", ReplyText.dropUnfinished("Done!"))
    }

    @Test
    fun `russian word - found and dropped for the english phone voice`() {
        assertTrue(ReplyText.hasRussian("\"Noon\" is \"полдень\"."))
        assertFalse(ReplyText.hasRussian("Noon is at twelve."))
        assertEquals("\"Noon\" is.", ReplyText.withoutRussian("\"Noon\" is \"полдень\"."))
        assertEquals("Noon is, okay?", ReplyText.withoutRussian("Noon is полдень, okay?"))
        assertTrue(ReplyText.withoutRussian("«полдень».").none { it.isLetterOrDigit() })
        assertEquals("", ReplyText.withoutRussian("полдень"))
    }

    @Test
    fun `reply ends at the first question - the correction and its right are not a question`() {
        fun cut(t: String) = ReplyText.firstQuestionEnd(t)?.let { t.substring(0, it) }
        assertEquals("Now or tonight?", cut("Now or tonight? It is 64 degrees."))
        assertEquals("Do you like it?\"", cut("Do you like it?\" I do."))
        assertNull(cut("Now or tonight?")) // следующего знака ещё нет — решать рано
        assertNull(cut("It is 15 degrees."))
        val fix = "I think you meant \"What is the temperature in Tucson?\", right?"
        assertEquals("$fix At night or at noon?", cut("$fix At night or at noon? It is 68."))
        assertNull(cut("$fix At night")) // вопроса после поправки ещё нет
        assertNull(cut("I think you meant \"What is the temperature in Tucson? ")) // поправка ещё идёт
        assertNull(cut("I think you meant \"What is the temperature in Tucson?\", ri")) // «right?» ещё идёт
        assertNull(cut("I think you me"))
        assertEquals("I think you meant “I went home.” Where?", cut("I think you meant “I went home.” Where? Then"))
        assertEquals("I think it is hot. Do you?", cut("I think it is hot. Do you? Yes."))
    }
}
