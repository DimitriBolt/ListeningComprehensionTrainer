package com.example.listeningtrainer.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class Resampler16To24Test {

    private fun tone(hz: Double, samples: Int, rate: Int = 16_000, amp: Double = 10_000.0) =
        ShortArray(samples) { (amp * sin(2 * PI * hz * it / rate)).roundToInt().toShort() }

    private fun rms(x: ShortArray, from: Int, to: Int): Double =
        sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    @Test
    fun `one second of 16 kHz gives one and a half times as many samples`() {
        val out = Resampler16To24().process(ShortArray(16_000), 16_000)
        assertTrue("got ${out.size}", out.size in 23_970..24_000)
    }

    @Test
    fun `a 1 kHz tone keeps its frequency and loudness`() {
        val out = Resampler16To24().process(tone(1000.0, 16_000), 16_000)
        // Ожидаемый тон на 24 кГц (задержка фильтра ≈ 0,7 мс не важна для громкости и частоты).
        val inRms = 10_000 / sqrt(2.0)
        assertEquals(inRms, rms(out, 1_000, 23_000), inRms * 0.02)
        // Частота: переходов через ноль — 2 на период, 1000 периодов в секунду.
        var crossings = 0
        for (i in 1_001 until 23_000) if ((out[i - 1] < 0) != (out[i] < 0)) crossings++
        assertEquals(2.0 * 1000 * 22_000 / 24_000, crossings.toDouble(), 4.0)
    }

    @Test
    fun `pieces of any size give the same sound as the whole recording`() {
        val x = tone(440.0, 8_000).also { for (i in it.indices) it[i] = (it[i] + (i * 7919 % 2001 - 1000)).toShort() }
        val whole = Resampler16To24().process(x, x.size)
        val r = Resampler16To24()
        val parts = mutableListOf<Short>()
        var pos = 0
        for (n in generateSequence(1) { it * 3 % 1283 + 1 }) {
            if (pos >= x.size) break
            val k = minOf(n, x.size - pos)
            val piece = x.copyOfRange(pos, pos + k)
            parts += r.process(piece, k).toList()
            pos += k
        }
        assertArrayEquals(whole, parts.toShortArray())
    }

    @Test
    fun `the same sound as resample_poly of the S03b probe - golden from scipy`() {
        // x — 400 отсчётов «тон 440 Гц + 3 кГц + шум» (формула ниже), golden = round(resample_poly(x, 3, 2)) из SciPy
        // (venv tools/probe, S03d). Выход потока выровнен так же; хвоста в ≈ 10 отсчётов у потока нет.
        val x = ShortArray(400) { i -> (6000 * sin(2 * PI * 440 * i / 16_000) + 3000 * sin(2 * PI * 3000 * i / 16_000) + (i * 7919 % 2001 - 1000)).roundToInt().toShort() }
        val out = Resampler16To24().process(x, x.size)
        val golden = javaClass.getResource("/resample/golden_24k.txt")!!.readText().trim().lines().map { it.trim().toInt() }
        var worst = 0
        assertTrue(out.size in 585..600)
        for (n in 0 until 585) worst = maxOf(worst, abs(out[n] - golden[n]))
        assertTrue("max |Δ| = $worst", worst <= 1)
    }

    @Test
    fun `reset starts a new recording`() {
        val r = Resampler16To24()
        val a = r.process(tone(300.0, 1600), 1600)
        r.reset()
        val b = r.process(tone(300.0, 1600), 1600)
        assertArrayEquals(a, b)
        assertTrue(a.maxOf { abs(it.toInt()) } > 9_000)
    }
}
