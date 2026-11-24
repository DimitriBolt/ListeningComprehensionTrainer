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
    fun `bold heading line at end of text gets heading pause`() {
        assertEquals(listOf("Speak slowly." to 1600L, "**Tip**" to 1100L), pairs("Speak slowly.\n**Tip**"))
    }

    @Test
    fun `whole bold line is a heading - with a number or a sign after it`() {
        assertEquals(listOf("**Tips for you:**" to 1600L, "Speak slowly." to 1000L), pairs("**Tips for you:**\nSpeak slowly."))
        assertEquals(listOf("2." to 400L, "**Do**:" to 1600L, "We do tasks." to 1000L), pairs("2. **Do**:\nWe do tasks."))
    }

    @Test
    fun `bold inside a phrase is plain text without a paragraph pause (D45)`() {
        assertEquals(
            listOf("We **make** a cake," to 600L, "but we **do** homework." to 1000L, "Can you try?" to 1000L),
            pairs("We **make** a cake, but we **do** homework. Can you try?"),
        )
        assertEquals(listOf("Speak slowly." to 1000L, "**Tip**" to 250L), pairs("Speak slowly. **Tip**"))
        assertEquals(listOf("**Make**" to 250L, "or **do**" to 1600L, "Use them." to 1000L), pairs("**Make** or **do**\nUse them."))
        assertTrue(planner.plan("I **make** tea and **do** sports every day.").none { it.pauseMs == SpeechPlanner.PAUSE_PARAGRAPH_MS })
    }

    @Test
    fun `number of a list item with a bold word is its own group with 400 ms, the bold word is plain text`() {
        assertEquals(
            listOf(
                "Here are two simple rules:" to 1600L,
                "1." to 400L,
                "**Make** We use \"make\"" to 250L,
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
    fun `every number in a list is its own group right before its heading`() {
        val chunks = pairs(
            "Here are two simple rules:\n1. **Make**\nWe use \"make\" when we create something.\n" +
                "2. **Do**\nWe use \"do\" for tasks.\n  3. **Have**\nEasy."
        )
        for ((number, heading) in listOf("1." to "**Make**", "2." to "**Do**", "3." to "**Have**")) {
            val i = chunks.indexOf(number to 400L)
            assertTrue(chunks.toString(), i >= 0)
            assertEquals(chunks.toString(), heading, chunks[i + 1].first)
        }
    }

    @Test
    fun `numbered heading at the very end gets heading pause`() {
        assertEquals(listOf("Rules:" to 1600L, "1." to 400L, "**Make**" to 1100L), pairs("Rules:\n1. **Make**"))
    }

    @Test
    fun `plain numbered list item - number separate too`() {
        assertEquals(
            listOf("1." to 400L, "Listen every day." to 1600L, "2." to 400L, "Repeat out loud." to 1000L),
            pairs("1. Listen every day.\n2. Repeat out loud."),
        )
    }

    @Test
    fun `number inside a sentence is not a list number`() {
        for (text in listOf("We won 2. Then we lost.", "In 2026, prices went up.", "Today is November 16.")) {
            val chunks = planner.plan(text)
            assertTrue("$text -> $chunks", chunks.none { it.pauseMs == SpeechPlanner.PAUSE_LIST_NUMBER_MS })
        }
    }

    @Test
    fun `punctuation is never a group of its own - it joins the previous group`() {
        // Раньше после заголовка оставалась группа ":" — Realtime читал её как «dot».
        assertEquals(
            listOf("1." to 400L, "**Make a plan**:" to 600L, "write it down." to 1000L),
            pairs("1. **Make a plan**: write it down."),
        )
        for (sign in listOf(".", "!", "-", "—")) {
            val chunks = planner.plan("**Tip**$sign Speak slowly.")
            assertTrue("$sign -> $chunks", chunks.all { c -> c.text.any { it.isLetterOrDigit() } })
        }
        assertTrue(planner.plan("- Speak slowly.").all { c -> c.text.any { it.isLetterOrDigit() } })
    }

    @Test
    fun `number ending a sentence is not glued to the next heading`() {
        assertEquals(
            listOf("Today is 2026." to 1000L, "**Note** this." to 1000L),
            pairs("Today is 2026. **Note** this."),
        )
    }
}
