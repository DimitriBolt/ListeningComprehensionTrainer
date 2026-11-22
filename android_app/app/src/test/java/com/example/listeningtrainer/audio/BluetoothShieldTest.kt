package com.example.listeningtrainer.audio

import com.example.listeningtrainer.config.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothShieldTest {

    @Test
    fun `shield only for Bluetooth`() {
        assertEquals(ModelConfig.BLUETOOTH_SHIELD_MS, BluetoothShield.shieldMs(OutputKind.BLUETOOTH))
        assertEquals(1200L, ModelConfig.BLUETOOTH_SHIELD_MS)
    }

    @Test
    fun `no shield for wired, usb and the speaker`() {
        for (kind in listOf(OutputKind.USB, OutputKind.WIRED, OutputKind.SPEAKER, OutputKind.EARPIECE, OutputKind.HDMI)) {
            assertEquals("$kind", 0L, BluetoothShield.shieldMs(kind))
        }
    }

    @Test
    fun `unknown output - shield, to be safe`() {
        assertEquals(ModelConfig.BLUETOOTH_SHIELD_MS, BluetoothShield.shieldMs(OutputKind.OTHER))
        assertEquals(ModelConfig.BLUETOOTH_SHIELD_MS, BluetoothShield.shieldMs(null))
    }
}
