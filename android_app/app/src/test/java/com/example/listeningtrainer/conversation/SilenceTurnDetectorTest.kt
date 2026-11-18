package com.example.listeningtrainer.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceTurnDetectorTest {

    private val detector = SilenceTurnDetector().apply { reset(0) }

    @Test
    fun `speech keeps the turn open`() {
        for (t in 0L..10_000L step 100) assertFalse(detector.onLevel(70f, t))
    }

    @Test
    fun `more than 3 s of silence after speech ends the turn`() {
        assertFalse(detector.onLevel(70f, 1000))
        assertFalse(detector.onLevel(40f, 3000))
        assertFalse(detector.onLevel(40f, 4000))
        assertTrue(detector.onLevel(40f, 4001))
    }

    @Test
    fun `silence from the very start also ends the turn`() {
        assertFalse(detector.onLevel(30f, 3000))
        assertTrue(detector.onLevel(30f, 3001))
    }

    @Test
    fun `level equal to threshold counts as silence`() {
        assertFalse(detector.onLevel(59f, 3000))
        assertTrue(detector.onLevel(59f, 3001))
    }

    @Test
    fun `reset starts a new turn`() {
        assertTrue(detector.onLevel(30f, 5000))
        detector.reset(5000)
        assertFalse(detector.onLevel(30f, 7000))
    }
}
