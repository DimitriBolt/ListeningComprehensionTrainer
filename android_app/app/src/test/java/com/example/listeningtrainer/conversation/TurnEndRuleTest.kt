package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnEndRuleTest {

    @Test
    fun `last word judge - thinking pauses of the S03b scenario are unfinished, answers are finished`() {
        for (t in listOf("I wanted to tell you about", "Yesterday I", "My favorite food is", "I think that", "We cooked dinner, um", "")) {
            assertFalse(t, LastWordJudge.complete(t))
        }
        for (t in listOf("At noon.", "Yes, I do.", "My dog is five", "I like to read books in the evening", "It is", "I can")) {
            assertTrue(t, LastWordJudge.complete(t))
        }
    }

    private class Scripted(var answer: Boolean) : ThoughtJudge {
        override val name = "JX"
        val texts = mutableListOf<String>()
        override fun complete(text: String): Boolean { texts += text; return answer }
    }

    @Test
    fun `candidate at 1000 ms - complete ends with JUDGE`() {
        val rule = TurnEndRule(Scripted(true), candidateMs = 1000, rejudgeMs = 1000, limitMs = 8000)
        val decisions = mutableListOf<TurnDecision>()
        assertNull(rule.onSilence(992, { "at noon" }, decisions::add))
        assertEquals(TurnEnd.JUDGE, rule.onSilence(1024, { "at noon" }, decisions::add))
        assertEquals(listOf(TurnDecision(1024, "JX", complete = true, judgeMs = decisions[0].judgeMs, end = true)), decisions)
    }

    @Test
    fun `incomplete - judged again every 1000 ms with fresh text, limit ends with LONG_SILENCE`() {
        val judge = Scripted(false)
        val rule = TurnEndRule(judge, candidateMs = 1000, rejudgeMs = 1000, limitMs = 5000)
        var text = "yesterday i"
        var end: TurnEnd? = null
        var ms = 0L
        while (end == null) {
            ms += 32
            end = rule.onSilence(ms, { text }) {}
        }
        assertEquals(TurnEnd.LONG_SILENCE, end)
        assertEquals(5024L, ms)
        assertEquals(4, judge.texts.size) // 1000, 2000, 3000, 4000 мс
        // речь посреди паузы — снова с кандидата
        rule.onSpeech()
        judge.answer = true
        text = "yesterday i went to the shop"
        assertNull(rule.onSilence(512, { text }) {})
        assertEquals(TurnEnd.JUDGE, rule.onSilence(1024, { text }) {})
    }
}
