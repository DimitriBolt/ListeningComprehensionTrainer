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
            "Can you repeat, please?", "Could you say it again?", "Repeat, please.", "Please repeat.", "Again, please.",
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
        assertEquals("(App note: the student did not understand you 1 time in a row.)", NotUnderstood.note(1))
        assertEquals("(App note: the student did not understand you 3 times in a row.)", NotUnderstood.note(3))
    }
}
