package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.conversation.TranscriptCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCheckTest {

    private fun check(expected: String, heard: String?) = TranscriptCheck.compare(expected, heard)

    @Test
    fun `case, punctuation and quotes do not matter`() {
        assertEquals(Verdict.VERBATIM, check("I think you meant \"Yesterday", "I think you meant \"Yesterday\""))
        assertEquals(Verdict.VERBATIM, check("some apples,\" right?", "Some apples, right?"))
        assertEquals(Verdict.VERBATIM, check("That’s a great question.", "That's a great question"))
    }

    @Test
    fun `numbers as digits or words`() {
        assertEquals(Verdict.VERBATIM, check("November 16,", "November sixteenth"))
        assertEquals(Verdict.VERBATIM, check("2026.", "twenty twenty six"))
        assertEquals(Verdict.VERBATIM, check("so we have 38 days.", "So we have thirty-eight days."))
        assertEquals(Verdict.VERBATIM, check("The holidays start on December 24th,", "The holidays start on December twenty-fourth"))
        assertEquals(Verdict.VERBATIM, check("today is about $3.29 per gallon.", "today is about $3.29 per gallon."))
        assertEquals(Verdict.VERBATIM, check("1.", "One."))
    }

    @Test
    fun `bold markers are ignored`() {
        assertEquals(Verdict.VERBATIM, check("1. **Make**", "1. Make"))
    }

    @Test
    fun `contractions count as a match`() {
        assertEquals(Verdict.CONTRACTIONS, check("I'm fine, don't worry.", "I am fine, do not worry."))
        assertEquals(true, Verdict.CONTRACTIONS.ok)
    }

    @Test
    fun `answering instead of reading is a mismatch`() {
        assertEquals(Verdict.MISMATCH, check("What do you think?", "I think it is a great idea."))
        assertEquals(Verdict.MISMATCH, check("Tell me more.", "Sure, tell me more."))
        assertEquals(Verdict.MISMATCH, check("and bought", "and brought"))
    }

    @Test
    fun `no transcript`() {
        assertEquals(Verdict.EMPTY, check("Hello.", null))
        assertEquals(Verdict.EMPTY, check("Hello.", "  "))
    }

    @Test
    fun `word counts for the log`() {
        assertEquals(4, TranscriptCheck.wordCount("What do you think?"))
        assertEquals(0, TranscriptCheck.wordCount(null))
    }
}
