package com.example.listeningtrainer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class LoudnessTest {

    private val rate = 24_000

    private fun tone(amplitude: Double, ms: Int, hz: Double = 440.0) =
        ShortArray(rate * ms / 1000) { (sin(2 * PI * hz * it / rate) * amplitude * 32767).toInt().toShort() }

    private fun normalize(pcm: ShortArray) = Loudness.normalize(pcm, rate, -13.0, -1.0, 24.0)

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
        assertEquals(-13.0, result.after.rmsDbfs, 0.5)
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
        assertEquals(-13.0, result.after.rmsDbfs, 1.0)
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

    /**
     * «Речь»: слоги по 150 мс с паузами 50 мс, у каждого огибающая sin², громкость слогов разная,
     * тон с обертонами. У такой «речи» пик на ≈ 12–15 дБ выше RMS, как у настоящей.
     */
    private fun speech(): ShortArray {
        val levels = listOf(0.04, 0.09, 0.03, 0.12, 0.05, 0.07, 0.10, 0.04, 0.08, 0.06)
        val syllable = rate * 150 / 1000
        val gap = rate * 50 / 1000
        val out = ShortArray(levels.size * (syllable + gap))
        levels.forEachIndexed { k, a ->
            for (i in 0 until syllable) {
                val env = sin(PI * i / syllable).let { it * it }
                val t = i.toDouble() / rate
                val v = sin(2 * PI * 180 * t) + 0.5 * sin(2 * PI * 360 * t) + 0.3 * sin(2 * PI * 720 * t)
                out[k * (syllable + gap) + i] = (a * env * v / 1.8 * 32767).toInt().toShort()
            }
        }
        return out
    }

    private fun rmsDb(pcm: ShortArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += pcm[i].toDouble() * pcm[i]
        return 20 * log10(sqrt(sum / (to - from)) / 32768)
    }

    @Test
    fun `speech reaches -13 dBFS with peaks at most -1 dBFS and almost no limiting`() {
        val pcm = speech()
        val before = Loudness.measure(pcm, rate)
        assertTrue(before.toString(), before.peakDbfs - before.rmsDbfs > 10) // настоящий пик-фактор речи
        val result = normalize(pcm)
        assertEquals(-13.0, result.after.rmsDbfs, 1.0)
        assertTrue(result.after.toString(), result.after.peakDbfs <= -1.0 + 0.05)
        // Без хрипа: на потолке (срезано ограничителем) — меньше 1 % отсчётов.
        val ceiling = 32768 * Math.pow(10.0, -1.0 / 20)
        val atCeiling = result.pcm.count { abs(it.toInt()) >= ceiling * 0.98 }
        assertTrue("at ceiling: $atCeiling of ${result.pcm.size}", atCeiling < result.pcm.size / 100)
    }

    @Test
    fun `compression is soft - louder syllables stay louder, steady tone does not pump`() {
        val result = normalize(speech())
        val syllable = rate * 150 / 1000
        val step = rate * 200 / 1000
        val loud = rmsDb(result.pcm, 3 * step, 3 * step + syllable)  // слог 0.12
        val quiet = rmsDb(result.pcm, 2 * step, 2 * step + syllable) // слог 0.03
        assertTrue("loud $loud, quiet $quiet", loud - quiet > 3.0) // 12 дБ разницы сжаты, но не стёрты
        // Ровный тон: уровень по кускам 50 мс одинаков — нет «качания».
        val tone = normalize(tone(0.02, 1500, hz = 200.0)).pcm
        val w = rate * 50 / 1000
        val levels = (2 until tone.size / w - 2).map { rmsDb(tone, it * w, (it + 1) * w) }
        assertTrue(levels.toString(), levels.max() - levels.min() < 0.5)
    }
}
