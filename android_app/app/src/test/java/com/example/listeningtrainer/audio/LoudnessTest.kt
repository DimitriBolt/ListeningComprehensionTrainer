package com.example.listeningtrainer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class LoudnessTest {

    private val rate = 24_000

    private fun tone(amplitude: Double, ms: Int, hz: Double = 440.0) =
        ShortArray(rate * ms / 1000) { (sin(2 * PI * hz * it / rate) * amplitude * 32767).toInt().toShort() }

    private fun normalize(pcm: ShortArray) = Loudness.normalize(pcm, rate, -16.0, -1.0, 24.0)

    @Test
    fun `sine level - rms and peak`() {
        val level = Loudness.measure(tone(0.5, 500), rate)
        assertEquals(-9.0, level.rmsDbfs, 0.1)
        assertEquals(-6.0, level.peakDbfs, 0.1)
    }

    @Test
    fun `silence around speech does not lower rms`() {
        val speech = tone(0.5, 500)
        val padded = ShortArray(rate) + speech + ShortArray(rate)
        assertEquals(Loudness.measure(speech, rate).rmsDbfs, Loudness.measure(padded, rate).rmsDbfs, 0.1)
    }

    @Test
    fun `quiet voice is raised to the target`() {
        val result = normalize(tone(0.03, 800))
        assertEquals(-16.0, result.after.rmsDbfs, 0.3)
        assertEquals(-33.5, result.before.rmsDbfs, 0.3)
    }

    @Test
    fun `peaks are limited without clipping`() {
        // Тихий тон с редкими острыми пиками: чтобы RMS дошёл до цели, пикам нужен ограничитель.
        val pcm = tone(0.05, 1000)
        for (at in listOf(3000, 9000, 15000)) for (i in 0 until 24) pcm[at + i] = (if (i % 2 == 0) 9000 else -9000).toShort()
        val result = normalize(pcm)
        assertTrue(result.after.toString(), result.after.peakDbfs <= -1.0 + 0.05)
        assertTrue(result.pcm.none { abs(it.toInt()) >= 32767 })
        assertEquals(-16.0, result.after.rmsDbfs, 1.0)
    }

    @Test
    fun `gain changes smoothly - no clicks`() {
        val pcm = tone(0.05, 1000, hz = 200.0)
        for (i in 12000 until 12240) pcm[i] = (pcm[i] * 8).toShort() // короткий громкий кусок
        val out = normalize(pcm).pcm
        // Наибольший скачок между соседними отсчётами — не больше, чем у синуса 200 Гц на потолке.
        val maxStep = (1 until out.size).maxOf { abs(out[it] - out[it - 1]) }
        val sineStep = 2 * PI * 200 / rate * 32767 * 0.89
        assertTrue("step $maxStep", maxStep <= sineStep * 1.5)
    }

    @Test
    fun `gain is capped, silence stays silence`() {
        val faint = normalize(tone(0.0005, 500)) // ≈ −69 dBFS: шум, а не голос — не тянуть на 53 дБ
        assertEquals(24.0, faint.gainDb, 0.01)
        val silence = normalize(ShortArray(2400))
        assertTrue(silence.pcm.all { it.toInt() == 0 })
        assertEquals(0, normalize(ShortArray(0)).pcm.size)
    }

    @Test
    fun `edges fade - no click at start and end`() {
        val out = normalize(ShortArray(4800) { 3000 }).pcm
        assertTrue(abs(out.first().toInt()) < 100)
        assertTrue(abs(out.last().toInt()) < 100)
    }
}
