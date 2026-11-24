package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Потоковая нарезка (D38, инвариант 1): ответ, нарезанный на куски любого размера, даёт те же
 * группы и паузы, что готовый текст. Паузы проверяются так, как их берёт контроллер: в момент,
 * когда отдана следующая группа (или ответ кончился).
 */
class StreamingSpeechPlannerTest {

    private val planner = SpeechPlanner()

    /** Прогон потока: группы и пауза каждой группы в момент, когда пришла следующая. */
    private fun stream(pieces: List<String>): Pair<List<SpeechChunk>, StreamingSpeechPlanner> {
        val sp = StreamingSpeechPlanner()
        val texts = mutableListOf<String>()
        val pauses = mutableListOf<Long>()
        fun take(new: List<SpeechChunk>) {
            for (c in new) {
                if (texts.isNotEmpty()) pauses.add(sp.pauseAfter(texts.size - 1))
                texts.add(c.text)
            }
        }
        for (p in pieces) take(sp.feed(p))
        take(sp.finish())
        if (texts.isNotEmpty()) pauses.add(sp.pauseAfter(texts.size - 1))
        return texts.zip(pauses).map { (t, p) -> SpeechChunk(t, p) } to sp
    }

    private fun pieces(text: String, rnd: Random, maxSize: Int): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val n = 1 + rnd.nextInt(maxSize)
            out.add(text.substring(i, minOf(text.length, i + n)))
            i += n
        }
        return out
    }

    private fun bySize(text: String, size: Int) = text.chunked(size)

    @Test
    fun `every probe reply streamed in pieces of any size gives the same groups and pauses as the whole text`() {
        val replies = ProbeReplies.all()
        var compared = 0
        for ((n, reply) in replies.withIndex()) {
            val whole = planner.plan(SpeechText.clean(reply))
            val ways = listOf(1, 2, 3, 5, 8, 13, 40, reply.length).map { bySize(reply, it) } +
                (0 until 10).map { pieces(reply, Random(n * 100 + it), 1 + it * 4) }
            for (way in ways) {
                val (got, sp) = stream(way)
                assertEquals("reply ${n + 1}", whole, got)
                assertTrue("reply ${n + 1}: divergences ${sp.divergences}", sp.divergences.isEmpty())
                compared++
            }
        }
        assertEquals(90 * 18, compared)
    }

    @Test
    fun `group before a bold heading line gets the paragraph pause even when the heading arrives piece by piece`() {
        val text = "Here is one tip.\n**Speak slowly, please**\nTry it now."
        val (got, _) = stream(bySize(text, 1))
        assertEquals(SpeechChunk("Here is one tip.", 1600), got.first())
        assertEquals(1600L, got[2].pauseMs)
        assertEquals(planner.plan(text), got)
    }

    @Test
    fun `bold inside a phrase arriving piece by piece keeps the plain pause (D45)`() {
        // Случай пробы S02c (в5 B4 #1): до D45 здесь была абзацная пауза перед жирной датой.
        val text = "Sunset in Tucson today, **Monday, November 23, 2026**, is at about **5:20 PM**.\n\nWill you watch it?"
        val (got, _) = stream(bySize(text, 1))
        assertEquals(SpeechChunk("Sunset in Tucson today,", 600), got.first())
        assertEquals(planner.plan(text), got)
    }

    @Test
    fun `nothing is emitted until the text after a safe point has started`() {
        val sp = StreamingSpeechPlanner()
        assertEquals(emptyList<SpeechChunk>(), sp.feed("Hello, "))
        assertEquals(emptyList<SpeechChunk>(), sp.feed("my friend. "))
        // Теперь после «friend. » пришёл знак — «Hello,» больше не изменится.
        assertEquals(listOf("Hello,"), sp.feed("H").map { it.text })
        assertEquals(listOf("my friend.", "H"), sp.finish().map { it.text })
    }

    @Test
    fun `footnote is never spoken and its address does not cut the text`() {
        val reply = "Gas is about **\$3.88** today. Prices vary. ([site.com](https://site.com/a?utm_source=openai))\n\nDoes that help?"
        val (got, _) = stream(bySize(reply, 1))
        assertEquals(planner.plan(SpeechText.clean(reply)), got)
        assertFalse(got.any { "site" in it.text })
    }

    @Test
    fun `list number before a heading is not a safe point`() {
        assertEquals("Rules:\n".length, StreamingSpeechPlanner.safeCut("Rules:\n2. **Do"))
        val text = "Rules:\n1. **Make** We use it.\n2. **Do** We use it too."
        val (got, _) = stream(bySize(text, 1))
        assertEquals(planner.plan(text), got)
    }

    @Test
    fun `no safe point inside a bold pair or before an unclosed one`() {
        assertEquals("a, ".length, StreamingSpeechPlanner.safeCut("a, **b, c"))
        assertEquals("a, ".length, StreamingSpeechPlanner.safeCut("a, **b, c**"))
        assertEquals("a, **b, c**, ".length, StreamingSpeechPlanner.safeCut("a, **b, c**, d"))
    }

    @Test
    fun `screen text grows with the reply`() {
        val sp = StreamingSpeechPlanner()
        sp.feed("Gas is cheap. ([site")
        assertEquals("Gas is cheap.", sp.screenText())
        sp.feed(".com](https://site.com)) Ok?")
        sp.finish()
        assertEquals("Gas is cheap. Ok?", sp.screenText())
    }

    @Test
    fun `changed text of an emitted group is counted, the rest continues`() {
        // Искусственно: планировщик, который на длинном тексте режет иначе.
        val tricky = { text: String ->
            if (text.length > 20) listOf(SpeechChunk("X", 250), SpeechChunk("Y", 250), SpeechChunk("Z", 250))
            else planner.plan(text)
        }
        val sp = StreamingSpeechPlanner(tricky)
        assertEquals(listOf("One,"), sp.feed("One, two, t").map { it.text })
        assertEquals(listOf("Y", "Z"), sp.feed("hree and more words here.").let { it + sp.finish() }.map { it.text })
        assertEquals(listOf(1), sp.divergences)
    }
}
