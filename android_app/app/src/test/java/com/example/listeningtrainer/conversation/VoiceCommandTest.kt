package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandTest {

    @Test
    fun `brief's phrases and the probe's transcripts are commands`() {
        for (t in listOf("Stop.", "stop", "Wait!", "Stop, please.", "Wait a moment.")) assertEquals(t, VoiceCommand.STOP, VoiceCommand.of(t))
        for (t in listOf("Again.", "Repeat", "Say again.", "One more time.", "Again, please.", "Repeat please:", "Say it again.")) {
            assertEquals(t, VoiceCommand.AGAIN, VoiceCommand.of(t))
        }
        for (t in listOf("Slower.", "Slow down.", "More slowly.", "Slower, please.", "Speak slowly.")) {
            assertEquals(t, VoiceCommand.SLOWER, VoiceCommand.of(t))
        }
    }

    @Test
    fun `anything else is a reply to the teacher - What and I don't understand stay with D55`() {
        for (t in listOf("What?", "I don't understand.", "At noon.", "I stopped.", "Wait for me here.", "Yes, again tomorrow.",
            "Stop the car now.", "Slow food.", "", "Please say it again slowly")) {
            assertNull(t, VoiceCommand.of(t))
        }
    }

    @Test
    fun `phrases are lowercase without signs and at most three words`() {
        for ((_, list) in VoiceCommand.PHRASES) for (p in list) {
            assertEquals(p, VoiceCommand.normalize(p), p)
            assertTrue(p, p.split(' ').size <= VoiceCommand.MAX_WORDS)
        }
        val all = VoiceCommand.PHRASES.values.flatten()
        assertEquals(all.size, all.toSet().size)
    }

    /** Слова на правило терпимости (S05b, D84); тот же список — `TOLERANT_CASES` в `tools/probe/tests/test_s05.py`. */
    @Test
    fun `one misheard word is a command (D84)`() {
        for (t in listOf("Slowware.", "Slowlyer.", "Slow.", "slows", "Slowly!")) assertEquals(t, VoiceCommand.SLOWER, VoiceCommand.of(t))
        for (t in listOf("Igain.", "Agane", "Agin.", "Again!", "Egain")) assertEquals(t, VoiceCommand.AGAIN, VoiceCommand.of(t))
        for (t in listOf("Stap.", "Stops.", "Stup", "Stoop.", "Stomp")) assertEquals(t, VoiceCommand.STOP, VoiceCommand.of(t))
    }

    @Test
    fun `ordinary short answers stay replies (D84)`() {
        for (t in listOf("Stopped.", "Spot.", "Shop.", "Top.", "Step on it.", "A gain of five.", "Rain.", "Spain.", "Gain.",
            "Brain.", "Again tomorrow, yes.", "Green.", "Agent.", "Slow food.", "It is slow.", "Stop the car.", "Stone.")) {
            assertNull(t, VoiceCommand.of(t))
        }
    }

    @Test
    fun `faster - the brief's variants and one misheard word on fas (S07)`() {
        for (t in listOf("Faster.", "Speed up!", "Quicker.", "More quickly.", "Faster, please.", "Speak faster.", "A bit faster."))
            assertEquals(t, VoiceCommand.FASTER, VoiceCommand.of(t))
        for (t in listOf("Fast.", "Fastr.", "Fastest!", "Fasta")) assertEquals(t, VoiceCommand.FASTER, VoiceCommand.of(t))
    }

    @Test
    fun `faster - similar short answers stay replies (S07, probe s07)`() {
        for (t in listOf("Fast food.", "Breakfast.", "First.", "Fasting.", "Fast car.", "Last.", "Past.", "Fact.", "Vast.",
            "Forest.", "Fashion.", "Quick lunch.", "Faster than me.", "Festival.", "Feast.")) {
            assertNull(t, VoiceCommand.of(t))
        }
    }

    @Test
    fun `edit distance`() {
        assertEquals(0, VoiceCommand.edits("again", "again"))
        assertEquals(1, VoiceCommand.edits("igain", "again"))
        assertEquals(2, VoiceCommand.edits("agane", "again"))
        assertEquals(2, VoiceCommand.edits("spot", "stop"))
        assertEquals(3, VoiceCommand.edits("stopped", "stop"))
    }

    // ---- поддакивание (S05c, D90) ----

    @Test
    fun `backchannel - the list of the brief with spellings`() {
        for (t in listOf("Mhm.", "Mm-hmm.", "Mmhmm", "Mm hmm", "M-hm.", "Mm.", "Mmm.", "Uh-huh.", "Uh huh.", "Uhhuh", "Aha.", "A-ha!",
            "Oho.", "Hmm.", "Hmmm...", "Hm?", "Uh.", "Uhh", "Um.", "Umm,", "Uhm.", "Mhm. Mhm.", "Mm-hmm, mm-hmm.", "Uh, mhm.")) {
            assertTrue(t, Backchannel.of(t))
        }
    }

    @Test
    fun `backchannel - not replies, commands or questions`() {
        for (t in listOf("", "Yes.", "Okay.", "No.", "Huh?", "Uh, I don't know.", "Hmm, what is a lake?", "Oh.", "Ah.",
            "Stop.", "Again.", "Mhm mhm mhm mhm mhm.", "Home.", "Ohio.", "Hum.")) {
            assertFalse(t, Backchannel.of(t))
        }
    }

    @Test
    fun `backchannel is never a command - a short sound with it is not a command`() {
        for (t in listOf("Mhm.", "Mm-hmm.", "Uh-huh.", "Oho.", "Aha.")) assertNull(t, VoiceCommand.of(t))
    }
}
