package com.example.listeningtrainer.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmTest {

    private fun wav(samples: ShortArray, rate: Int, channels: Int, dataSize: Int? = null, extraChunk: Boolean = false): ByteArray {
        val extra = if (extraChunk) 8 + 3 + 1 else 0 // нечётный размер — с выравниванием
        val b = ByteBuffer.allocate(44 + extra + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + extra + samples.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        if (extraChunk) b.put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0))
        b.put("data".toByteArray()).putInt(dataSize ?: (samples.size * 2))
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    @Test
    fun `mono 16 bit`() {
        val audio = WavPcm.read(wav(shortArrayOf(1, -2, 300), 22_050, 1))
        assertEquals(22_050, audio.sampleRate)
        assertArrayEquals(shortArrayOf(1, -2, 300), audio.pcm)
    }

    @Test
    fun `stereo is mixed to mono`() {
        val audio = WavPcm.read(wav(shortArrayOf(100, 300, -100, -300), 16_000, 2))
        assertArrayEquals(shortArrayOf(200, -200), audio.pcm)
    }

    @Test
    fun `streaming engines write size 0 or -1 - data to the end`() {
        assertArrayEquals(shortArrayOf(5, 6), WavPcm.read(wav(shortArrayOf(5, 6), 24_000, 1, dataSize = 0)).pcm)
        assertArrayEquals(shortArrayOf(5, 6), WavPcm.read(wav(shortArrayOf(5, 6), 24_000, 1, dataSize = -1)).pcm)
    }

    @Test
    fun `other chunks are skipped`() {
        assertArrayEquals(shortArrayOf(7, 8), WavPcm.read(wav(shortArrayOf(7, 8), 24_000, 1, extraChunk = true)).pcm)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `not a wav`() {
        WavPcm.read(ByteArray(50))
    }
}
