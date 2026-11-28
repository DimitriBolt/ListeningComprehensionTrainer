package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Личный множитель пауз (S07, D98): шаг, границы, хранение; сам не меняется. */
class PauseScaleTest {

    @Test
    fun `numbers come from ModelConfig - step 1_15, limits 0_7 to 2_5`() {
        assertEquals(1.15, ModelConfig.PAUSE_SCALE_STEP, 0.0)
        assertEquals(0.7, ModelConfig.PAUSE_SCALE_MIN, 0.0)
        assertEquals(2.5, ModelConfig.PAUSE_SCALE_MAX, 0.0)
    }

    @Test
    fun `new install starts at 1_0`() {
        assertEquals(1.0, PauseScale(MemoryPauseScaleStore()).value, 0.0)
    }

    @Test
    fun `slower - one step up, faster - one step down, back to exactly 1_0`() {
        val s = PauseScale(MemoryPauseScaleStore())
        assertEquals(PauseScale.Change(1.0, 1.15, limit = false), s.slower())
        assertEquals("1.32", PauseScale.format(s.slower().to))
        assertEquals("1.52", PauseScale.format(s.slower().to))
        s.faster(); s.faster(); s.faster()
        assertEquals(1.0, s.value, 0.0)
    }

    @Test
    fun `limits - up to 2_5, down to 0_7, then nothing changes`() {
        val s = PauseScale(MemoryPauseScaleStore())
        repeat(20) { s.slower() }
        assertEquals(2.5, s.value, 0.0)
        val atTop = s.slower()
        assertTrue(atTop.limit)
        assertEquals(2.5, atTop.to, 0.0)
        repeat(30) { s.faster() }
        assertEquals(0.7, s.value, 0.0)
        assertTrue(s.faster().limit)
        assertFalse(s.slower().limit)
    }

    @Test
    fun `kept on the device - a new object after a restart sees the same scale`() {
        val store = MemoryPauseScaleStore()
        val s = PauseScale(store)
        s.slower(); s.slower()
        assertEquals(1.3225, PauseScale(store).value, 0.0)
        // как SharedPreferences: число хранится как float — после загрузки снова 4 знака
        assertEquals(1.3225, PauseScale(MemoryPauseScaleStore(1.3225f.toDouble())).value, 0.0)
        // мусор в хранилище — в границах
        assertEquals(2.5, PauseScale(MemoryPauseScaleStore(9.0)).value, 0.0)
    }

    @Test
    fun `screen format`() {
        assertEquals("1.00", PauseScale.format(1.0))
        assertEquals("1.15", PauseScale.format(1.15))
        assertEquals("0.76", PauseScale.format(1 / 1.15 / 1.15))
    }
}
