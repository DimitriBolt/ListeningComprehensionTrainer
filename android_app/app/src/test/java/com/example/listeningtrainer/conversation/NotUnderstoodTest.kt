package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotUnderstoodTest {

    @Test
    fun `explicit markers`() {
        for (t in listOf(
            "What?", "what", "Pardon?", "Pardon me?", "Sorry?", "Sorry, what?", "Huh?", "Excuse me?",
            "I don't understand.", "I don’t understand", "Sorry, I don't understand.", "I do not understand you.",
            "I didn't understand.", "I don't understand the word noon.", "I don't get it.",
            "I don't know what you mean.", "I don't know what is noon.", "What do you mean?", "What did you say?",
            "Can you repeat, please?", "Could you say it again?",
            "Say it again.", "One more time.", "Slowly, please.", "What does noon mean?", "...", "", "  ",
        )) assertTrue(t, NotUnderstood.isMarker(t))
    }

    @Test
    fun `ordinary answers are not markers`() {
        for (t in listOf(
            "I like to cook.", "At noon.", "I don't know.", "Yes.", "No.", "What is the temperature in Tucson?",
            "What time is it?", "Sorry, I was late.", "I understand.", "Pasta.", "I know what you mean.",
        )) assertFalse(t, NotUnderstood.isMarker(t))
    }

    @Test
    fun `again and repeat are commands, not markers (D88)`() {
        for (t in listOf("Again.", "Again, please.", "Repeat.", "Repeat, please.", "Please repeat.")) {
            assertFalse(t, NotUnderstood.isMarker(t))
            assertEquals(t, VoiceCommand.AGAIN, VoiceCommand.of(t))
        }
    }

    @Test
    fun `streak counts markers at the end, any other answer resets it`() {
        assertEquals(0, NotUnderstood.streak(listOf("I like to cook.")))
        assertEquals(1, NotUnderstood.streak(listOf("I like to cook.", "What?")))
        assertEquals(3, NotUnderstood.streak(listOf("I like to cook.", "What?", "I don't understand.", "Sorry, I don't understand.")))
        assertEquals(1, NotUnderstood.streak(listOf("What?", "What?", "Pasta.", "Sorry?")))
        assertEquals(0, NotUnderstood.streak(emptyList()))
    }

    @Test
    fun `note`() {
        assertNull(NotUnderstood.note(0))
        // D58: строка сама говорит, что делать. Те же строки — в tools/probe/tests/test_tutor_dialog.py.
        assertEquals(NOTE_1, NotUnderstood.note(1))
        assertEquals(NOTE_2, NotUnderstood.note(2))
        assertEquals(NOTE_3, NotUnderstood.note(3))
        assertEquals(NOTE_3.replace("3 times", "4 times"), NotUnderstood.note(4))
        // До счёта 3 — ни одной русской буквы в самой строке, кроме запрета; с 3 — пример русского слова.
        for (n in 1..2) assertFalse(NotUnderstood.note(n)!!.contains("Russian word"))
        assertTrue(NotUnderstood.note(3)!!.contains("one key word in Russian"))
    }

    companion object {
        const val NOTE_1 = "(App note: the student did not understand you 1 time in a row. Say the same thing again, simpler and " +
            "with other words. Keep it short: 1 or 2 short sentences, 15 words at most, then your question. Only English: not one " +
            "Russian letter.)"
        const val NOTE_2 = "(App note: the student did not understand you 2 times in a row. Say the same thing again, simpler and " +
            "with other words. Keep it short: 1 or 2 short sentences, 15 words at most, then your question. Only English: not one " +
            "Russian letter.)"
        const val NOTE_3 = "(App note: the student did not understand you 3 times in a row. Say one key word in Russian, like this: " +
            "\"Noon\" is \"полдень\". Then go on in English. Keep it short: 1 or 2 short sentences, 15 words at most, then your question.)"
    }
}
