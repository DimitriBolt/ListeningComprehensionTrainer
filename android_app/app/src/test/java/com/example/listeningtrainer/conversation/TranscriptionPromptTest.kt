package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionPromptTest {

    @Test
    fun `start of conversation - only the context`() {
        assertEquals(TranscriptionPrompt.CONTEXT, TranscriptionPrompt.build(null, 200))
        assertEquals(TranscriptionPrompt.CONTEXT, TranscriptionPrompt.build("   ", 200))
    }

    @Test
    fun `last teacher sentence without markup and service label`() {
        val text = "${SpeechPlanner.LABEL_SEARCH_FALLBACK} There was a big **outage** today.\n\nHave you heard about the outage?"
        assertEquals("Have you heard about the outage?", TranscriptionPrompt.lastSentence(text, 200))
        assertEquals(
            "${TranscriptionPrompt.CONTEXT} The teacher just said: \"Have you heard about the outage?\"",
            TranscriptionPrompt.build(text, 200),
        )
    }

    @Test
    fun `long sentence is cut to the limit at a word boundary, keeping the end`() {
        val long = "word ".repeat(60) + "and what do you think?"
        val s = TranscriptionPrompt.lastSentence(long, 200)
        assertTrue(s.length <= 200)
        assertTrue(s.endsWith("and what do you think?"))
        assertTrue(s.startsWith("word"))
    }
}
