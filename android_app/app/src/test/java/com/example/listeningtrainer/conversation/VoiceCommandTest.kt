package com.example.listeningtrainer.conversation

import org.junit.Assert.assertEquals
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
}
