package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поведение SpeechPlanner. Паузы в тестах — с вычетом 1000 мс на щит Bluetooth,
 * как в прежнем TrainerViewModel.splitIntoChunks.
 */
class SpeechPlannerTest {

    private val planner = SpeechPlanner()

    private fun texts(input: String) = planner.plan(input).map { it.text }
    private fun pairs(input: String) = planner.plan(input).map { it.text to it.pauseMs }

    @Test
    fun `single word is one chunk`() {
        assertEquals(listOf("Hello" to 10L), pairs("Hello"))
    }

    @Test
    fun `empty text gives no chunks`() {
        assertEquals(emptyList<SpeechChunk>(), planner.plan(""))
        assertEquals(emptyList<SpeechChunk>(), planner.plan("  \n\n "))
    }

    @Test
    fun `sentences and commas are split with their pauses`() {
        assertEquals(
            listOf(
                "Listen carefully." to 10L,
                "Repeat" to 10L,
                "after me." to 10L,
                "Say it slowly," to 10L,
                "and" to 10L,
                "then say it fast." to 10L,
            ),
            pairs("Listen carefully. Repeat after me. Say it slowly, and then say it fast."),
        )
    }

    @Test
    fun `paragraph end pause is 1600 minus shield`() {
        assertEquals(
            listOf("First line." to 600L, "Second line." to 10L),
            pairs("First line.\nSecond line."),
        )
    }

    @Test
    fun `chunk longer than six words is cut in half`() {
        assertEquals(
            listOf("The price is about \$3.29 today." to 10L, "Do you drive?" to 10L),
            pairs("The price is about \$3.29 today. Do you drive?"),
        )
        assertEquals(
            listOf("Can you make a", "sentence with each word?"),
            texts("Can you make a sentence with each word?"),
        )
    }

    @Test
    fun `no chunk is longer than six words`() {
        val reply = "Here are two simple rules:\n1. **Make** We use \"make\" when we create something, " +
            "like a cake or a plan.\nPeople often connect words, and then they sound like one long word."
        for (chunk in planner.plan(reply)) {
            assertTrue(chunk.text, chunk.text.split(" ").size <= 6)
        }
    }

    @Test
    fun `bold heading at end of text gets heading pause`() {
        assertEquals(listOf("Speak slowly." to 600L, "**Tip**" to 100L), pairs("Speak slowly. **Tip**"))
    }

    @Test
    fun `numbered bold list - current behavior`() {
        assertEquals(
            listOf(
                "Here are two simple rules:" to 600L,
                "1." to 600L,
                "**Make**" to 600L,
                "We use \"make\"" to 10L,
                "when we create" to 10L,
                "something," to 10L,
                "like a cake" to 10L,
                "or a plan." to 600L,
                "Can you make a" to 10L,
                "sentence with each word?" to 10L,
            ),
            pairs(
                "Here are two simple rules:\n1. **Make** We use \"make\" when we create something, " +
                    "like a cake or a plan.\nCan you make a sentence with each word?"
            ),
        )
    }

    @Test
    fun `conjunction split - current behavior`() {
        assertEquals(listOf("I", "ask you."), texts("I ask you."))
        assertEquals(listOf("I am", "sorry."), texts("I am sorry."))
        assertEquals(listOf("I spent the", "whole day here."), texts("I spent the whole day here."))
        assertEquals(listOf("Good", "afternoon to you."), texts("Good afternoon to you."))
        assertEquals(listOf("In", "order to win."), texts("In order to win."))
        assertEquals(listOf("I want", "some tea."), texts("I want some tea."))
    }

    @Test
    fun `split before whole conjunction`() {
        assertEquals(listOf("I stayed home", "because it rained."), texts("I stayed home because it rained."))
        assertEquals(listOf("Tea", "or coffee?"), texts("Tea or coffee?"))
    }

    @Test
    fun `service labels - current behavior`() {
        assertEquals(
            listOf("(Live Web Data):", "The price is about \$3.29 today."),
            texts("(Live Web Data): The price is about \$3.29 today."),
        )
        assertEquals(
            listOf("(Search attempted,", "fallback):", "I could not find it."),
            texts("(Search attempted, fallback): I could not find it."),
        )
    }
}
