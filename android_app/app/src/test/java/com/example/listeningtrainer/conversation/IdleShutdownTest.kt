package com.example.listeningtrainer.conversation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleShutdownTest {

    private val tenMin = 10 * 60_000L

    @Test
    fun `no circle and no screen for 10 minutes - closes`() = runTest {
        val running = MutableStateFlow(false)
        val visible = MutableStateFlow(false)
        var closed = 0
        val job = IdleShutdown.watch(this, running, visible, tenMin) { closed++ }
        advanceTimeBy(tenMin - 1); runCurrent()
        assertEquals(0, closed)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, closed)
        job.cancel()
    }

    @Test
    fun `visible screen or running circle keeps the service, and restarts the countdown`() = runTest {
        val running = MutableStateFlow(false)
        val visible = MutableStateFlow(true)
        var closed = 0
        val job = IdleShutdown.watch(this, running, visible, tenMin) { closed++ }
        advanceTimeBy(3 * tenMin); runCurrent()
        assertEquals(0, closed) // экран виден

        visible.value = false
        advanceTimeBy(tenMin - 60_000); runCurrent()
        running.value = true // начался круг (авто-режим, разговор в фоне)
        advanceTimeBy(3 * tenMin); runCurrent()
        assertEquals(0, closed)

        running.value = false // круг кончился — отсчёт с нуля
        advanceTimeBy(tenMin - 1); runCurrent()
        assertEquals(0, closed)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, closed)
        job.cancel()
    }

    @Test
    fun `circle is running in every state except idle and error`() {
        assertFalse(IdleShutdown.isCircleRunning(ConversationState.Idle))
        assertFalse(IdleShutdown.isCircleRunning(ConversationState.Error("x")))
        assertTrue(IdleShutdown.isCircleRunning(ConversationState.Listening))
        assertTrue(IdleShutdown.isCircleRunning(ConversationState.Thinking))
        assertTrue(IdleShutdown.isCircleRunning(ConversationState.Speaking))
    }
}
