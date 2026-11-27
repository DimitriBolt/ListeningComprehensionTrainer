package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptMatchTest {
    @Test
    fun `same words - 1, case and punctuation do not matter`() {
        assertEquals(1.0, TranscriptMatch.similarity("I like tea.", "i like TEA"), 0.0)
    }

    @Test
    fun `share of matched words in the longer text, in order`() {
        assertEquals(4.0 / 6, TranscriptMatch.similarity("I like to read books", "I like read books in evening"), 1e-9)
        // порядок важен: «books read» совпадает одним словом из двух
        assertEquals(0.5, TranscriptMatch.similarity("read books", "books read"), 1e-9)
    }

    @Test
    fun `nothing in common - 0, both empty - 1, one empty - 0`() {
        assertEquals(0.0, TranscriptMatch.similarity("At noon.", "Add none"), 0.0)
        assertEquals(1.0, TranscriptMatch.similarity("", " "), 0.0)
        assertEquals(0.0, TranscriptMatch.similarity("", "Yes"), 0.0)
    }
}
