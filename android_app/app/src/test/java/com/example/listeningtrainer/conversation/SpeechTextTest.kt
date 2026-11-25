package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Чистка сносок поиска (D37). Примеры — из `docs/research/02c-results.md`. */
class SpeechTextTest {

    @Test
    fun `footnote from the probe is removed, the rest stays`() {
        val raw = "Regular gas in Tucson is about **\$3.88 per gallon** today, November 23, 2026. Prices can vary by station. " +
            "([whatsthepriceofgas.com](https://whatsthepriceofgas.com/gas-prices/arizona/tucson?utm_source=openai))\n\nDoes that help?"
        assertEquals(
            "Regular gas in Tucson is about **\$3.88 per gallon** today, November 23, 2026. Prices can vary by station.\n\nDoes that help?",
            SpeechText.clean(raw),
        )
    }

    @Test
    fun `footnote after bold and several footnotes in one bracket`() {
        assertEquals(
            "About **\$3.60 per gallon today**. Stations may charge more.",
            SpeechText.clean("About **\$3.60 per gallon today**. Stations may charge more. ([gasprices.aaa.com](https://gasprices.aaa.com/?state=AZ&stream=top))"),
        )
        assertEquals(
            "Two sources agree.",
            SpeechText.clean("Two sources agree. ([a.com](https://a.com/x?utm_source=openai), [b.org](https://b.org/y))"),
        )
    }

    @Test
    fun `inline link keeps its text, bare address goes`() {
        assertEquals("See the AAA page now.", SpeechText.clean("See [the AAA page](https://aaa.com/p?utm_source=openai) now."))
        assertEquals("Visit today.", SpeechText.clean("Visit https://example.com today."))
        assertEquals("Look here.", SpeechText.clean("Look here (www.example.com)."))
    }

    @Test
    fun `plain text is unchanged`() {
        val text = "I think you mean: **“Yesterday, I went to the shop.”**\n\n1. **go → went**\n2. **buy → bought**\n\nWhat did you buy?"
        assertEquals(text, SpeechText.clean(text))
    }

    @Test
    fun `nothing to read aloud is left in any probe reply`() {
        val replies = ProbeReplies.all()
        assertEquals(90, replies.size)
        assertTrue(replies.count { "](" in it } >= 30)
        for (reply in replies) {
            val clean = SpeechText.clean(reply)
            for (bad in listOf("http", "www.", "](", "utm_source")) assertFalse("$bad left in: $clean", bad in clean)
        }
    }

    @Test
    fun `screen text hides an unfinished footnote`() {
        assertEquals("Prices vary.", SpeechText.forScreen("Prices vary. ([whatsthepriceof"))
        assertEquals("Prices vary.", SpeechText.forScreen("Prices vary. ([site.com](https://site.c"))
        assertEquals("Prices vary. Ok", SpeechText.forScreen("Prices vary. ([site.com](https://site.com)) Ok"))
    }

    // ---- S02h, D57: курсив ----------------------------------------------------------------

    @Test
    fun `italics - stars and underscores of emphasis are dropped, the words stay, groups have no stars`() {
        val text = "His book *The Call of the Wild* is set in Alaska."
        assertEquals("His book The Call of the Wild is set in Alaska.", SpeechText.clean(text))
        val groups = SpeechPlanner().plan(SpeechText.clean(text)).map { it.text }
        assertEquals(listOf("His book The Call of", "the Wild is set in Alaska."), groups)
        assertEquals("I read Moby Dick.", SpeechText.clean("I read _Moby Dick_."))
    }

    @Test
    fun `italics - bold stays for the planner, underscores inside words stay`() {
        assertEquals("A **big** dog and *small* cat.".replace("*small*", "small"), SpeechText.clean("A **big** dog and *small* cat."))
        assertEquals("1. **Make a plan**:", SpeechText.clean("1. **Make a plan**:"))
        assertEquals("Call it snake_case or my_var_2.", SpeechText.clean("Call it snake_case or my_var_2."))
    }

    @Test
    fun `italics - an unclosed star in the stream is dropped at once, so streamed groups match the whole text`() {
        val text = "His book *The Call of the Wild* is set in Alaska. Dogs pull sleds there."
        val streaming = StreamingSpeechPlanner()
        val emitted = text.chunked(3).flatMap { streaming.feed(it) } + streaming.finish()
        assertEquals(SpeechPlanner().plan(SpeechText.clean(text)).map { it.text }, emitted.map { it.text })
        assertTrue(emitted.none { '*' in it.text })
        assertTrue(streaming.divergences.isEmpty())
    }
}

/** Ответы учителя из приложения `docs/research/02c-results.md` (тестовый ресурс). */
object ProbeReplies {
    fun all(): List<String> {
        val stream = ProbeReplies::class.java.classLoader!!.getResourceAsStream("02c-replies.txt")!!
        return stream.bufferedReader(Charsets.UTF_8).readText().split("\n=====\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
