package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.conversation.HeadsetButton.Action
import com.example.listeningtrainer.conversation.HeadsetButton.Companion.KEYCODE_HEADSETHOOK
import com.example.listeningtrainer.conversation.HeadsetButton.Companion.KEYCODE_MEDIA_NEXT
import com.example.listeningtrainer.conversation.HeadsetButton.Companion.KEYCODE_MEDIA_PAUSE
import com.example.listeningtrainer.conversation.HeadsetButton.Companion.KEYCODE_MEDIA_PLAY
import com.example.listeningtrainer.conversation.HeadsetButton.Companion.KEYCODE_MEDIA_PLAY_PAUSE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetButtonTest {

    private fun press(
        button: HeadsetButton,
        state: ConversationState,
        at: Long,
        key: Int = KEYCODE_HEADSETHOOK,
        pressMs: Long = 120,
    ) = button.onRelease(key, pressMs, state, at)

    @Test
    fun `idle and error start talking`() {
        assertEquals(Action.TALK, press(HeadsetButton(), ConversationState.Idle, 1_000).action)
        assertEquals(Action.TALK, press(HeadsetButton(), ConversationState.Error("x"), 1_000).action)
    }

    @Test
    fun `listening finishes the recording`() {
        assertEquals(Action.FINISH_RECORDING, press(HeadsetButton(), ConversationState.Listening, 1_000).action)
    }

    @Test
    fun `thinking and speaking do nothing`() {
        for (state in listOf(ConversationState.Thinking, ConversationState.Speaking)) {
            val d = press(HeadsetButton(), state, 1_000)
            assertEquals(Action.NONE, d.action)
            assertTrue(d.log, "ignored (teacher is thinking or speaking)" in d.log)
            assertTrue(d.consumed) // наше, но без действия: YouTube не должен проснуться
        }
    }

    @Test
    fun `log names the key code and the state`() {
        val d = press(HeadsetButton(), ConversationState.Idle, 1_000, key = KEYCODE_MEDIA_PLAY_PAUSE)
        assertEquals("headset key KEYCODE_MEDIA_PLAY_PAUSE in Idle: Talk", d.log)
        val e = press(HeadsetButton(), ConversationState.Error("secret text"), 1_000)
        assertEquals("headset key KEYCODE_HEADSETHOOK in Error: Talk", e.log) // текст ошибки в журнал не идёт
    }

    @Test
    fun `all middle button codes count`() {
        for (key in listOf(KEYCODE_HEADSETHOOK, KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE)) {
            assertEquals("$key", Action.TALK, press(HeadsetButton(), ConversationState.Idle, 1_000, key = key).action)
        }
    }

    @Test
    fun `other media keys are not the middle button`() {
        val d = press(HeadsetButton(), ConversationState.Idle, 1_000, key = KEYCODE_MEDIA_NEXT)
        assertEquals(Action.NONE, d.action)
        assertFalse(d.consumed)
        assertEquals("headset key KEYCODE_MEDIA_NEXT in Idle: not the middle button, ignored", d.log)
        assertEquals("KEYCODE_24", HeadsetButton.keyName(24))
    }

    @Test
    fun `long press is left to the system`() {
        val d = press(HeadsetButton(), ConversationState.Idle, 1_000, pressMs = 900)
        assertEquals(Action.NONE, d.action)
        assertFalse(d.consumed)
        assertTrue(d.log, "long press (900 ms)" in d.log)
    }

    @Test
    fun `double press counts once`() {
        val button = HeadsetButton()
        assertEquals(Action.TALK, press(button, ConversationState.Idle, 10_000).action)
        // Второе нажатие через 250 мс: круг уже пошёл (Listening), но это дребезг, а не «я закончил».
        val second = press(button, ConversationState.Listening, 10_250)
        assertEquals(Action.NONE, second.action)
        assertTrue(second.log, "repeat after 250 ms, ignored" in second.log)
        // Через полсекунды после первого — уже новое нажатие.
        assertEquals(Action.FINISH_RECORDING, press(button, ConversationState.Listening, 10_500).action)
    }

    @Test
    fun `ignored long press does not start the debounce`() {
        val button = HeadsetButton()
        press(button, ConversationState.Idle, 10_000, pressMs = 1_500)
        assertEquals(Action.TALK, press(button, ConversationState.Idle, 10_100).action)
    }
}
