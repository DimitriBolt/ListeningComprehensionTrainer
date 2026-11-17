package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Поведение SpeechPlanner. Паузы — эталонные, без опережающего запуска под щит Bluetooth. */
class SpeechPlannerTest {

    private val planner = SpeechPlanner()

    private fun texts(input: String) = planner.plan(input).map { it.text }
    private fun pairs(input: String) = planner.plan(input).map { it.text to it.pauseMs }

    @Test
    fun `single word is one chunk`() {
        assertEquals(listOf("Hello" to 250L), pairs("Hello"))
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
                "Listen carefully." to 1000L,
                "Repeat" to 250L,
                "after me." to 1000L,
                "Say it slowly," to 600L,
                "and" to 250L,
                "then say it fast." to 1000L,
            ),
            pairs("Listen carefully. Repeat after me. Say it slowly, and then say it fast."),
        )
    }

    @Test
    fun `paragraph end pause is 1600`() {
        assertEquals(
            listOf("First line." to 1600L, "Second line." to 1000L),
            pairs("First line.\nSecond line."),
        )
    }

    @Test
    fun `chunk longer than six words is cut in half`() {
        assertEquals(
            listOf("The price is about \$3.29 today." to 1000L, "Do you drive?" to 1000L),
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
        assertEquals(listOf("Speak slowly." to 1600L, "**Tip**" to 1100L), pairs("Speak slowly. **Tip**"))
    }

    @Test
    fun `numbered bold list item is one chunk`() {
        assertEquals(
            listOf(
                "Here are two simple rules:" to 1600L,
                "1. **Make**" to 1600L,
                "We use \"make\"" to 250L,
                "when we create something," to 600L,
                "like a cake" to 250L,
                "or a plan." to 1600L,
                "Can you make a" to 250L,
                "sentence with each word?" to 1000L,
            ),
            pairs(
                "Here are two simple rules:\n1. **Make** We use \"make\" when we create something, " +
                    "like a cake or a plan.\nCan you make a sentence with each word?"
            ),
        )
    }

    @Test
    fun `words that only start with a conjunction are not split`() {
        assertEquals(listOf("I ask you."), texts("I ask you."))
        assertEquals(listOf("I am sorry."), texts("I am sorry."))
        assertEquals(listOf("I spent the whole day here."), texts("I spent the whole day here."))
        assertEquals(listOf("Good afternoon to you."), texts("Good afternoon to you."))
        assertEquals(listOf("In order to win."), texts("In order to win."))
        assertEquals(listOf("I want some tea."), texts("I want some tea."))
        assertEquals(listOf("I like oranges."), texts("I like oranges."))
        assertEquals(listOf("Ask me again."), texts("Ask me again."))
    }

    @Test
    fun `whole conjunction is split regardless of case`() {
        assertEquals(listOf("Yes,", "So we go."), texts("Yes, So we go."))
        assertEquals(listOf("Wait here", "AND listen."), texts("Wait here AND listen."))
    }

    @Test
    fun `split before whole conjunction`() {
        assertEquals(listOf("I stayed home", "because it rained."), texts("I stayed home because it rained."))
        assertEquals(listOf("Tea", "or coffee?"), texts("Tea or coffee?"))
    }

    @Test
    fun `service labels are not spoken`() {
        assertEquals(
            listOf("The price is about \$3.29 today."),
            texts("${SpeechPlanner.LABEL_LIVE_WEB} The price is about \$3.29 today."),
        )
        assertEquals(
            listOf("I could not find it.", "Try again?"),
            texts("${SpeechPlanner.LABEL_SEARCH_FALLBACK} I could not find it. Try again?"),
        )
        assertEquals(listOf("Rules:", "1. **Make**"), texts("${SpeechPlanner.LABEL_LIVE_WEB} Rules:\n1. **Make**"))
    }

    @Test
    fun `labels are matched literally`() {
        assertEquals("(Live Web Data):", SpeechPlanner.LABEL_LIVE_WEB)
        assertEquals("(Search attempted, fallback):", SpeechPlanner.LABEL_SEARCH_FALLBACK)
    }

    @Test
    fun `label-like text in the middle is left alone`() {
        assertEquals(listOf("I said (Live Web Data):", "hi."), texts("I said (Live Web Data): hi."))
    }

    @Test
    fun `every numbered heading in a list stays whole`() {
        val chunks = texts(
            "Here are two simple rules:\n1. **Make** We use \"make\" when we create something.\n" +
                "2. **Do** We use \"do\" for tasks.\n  3. **Have** Easy."
        )
        assertTrue(chunks.toString(), "1. **Make**" in chunks)
        assertTrue(chunks.toString(), "2. **Do**" in chunks)
        assertTrue(chunks.toString(), "3. **Have**" in chunks)
        assertTrue(chunks.toString(), chunks.none { it == "1." || it == "2." || it == "3." })
    }

    @Test
    fun `numbered heading at the very end gets heading pause`() {
        assertEquals(listOf("Rules:" to 1600L, "1. **Make**" to 1100L), pairs("Rules:\n1. **Make**"))
    }

    @Test
    fun `number ending a sentence is not glued to the next heading`() {
        assertEquals(
            listOf("Today is 2026." to 1600L, "**Note**" to 1600L, "this." to 1000L),
            pairs("Today is 2026. **Note** this."),
        )
    }
}
