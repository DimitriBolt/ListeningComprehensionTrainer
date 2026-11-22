package com.example.listeningtrainer.audio

import com.example.listeningtrainer.config.ModelConfig

/** Куда идёт звук — только тип, без имён устройств. Чистый Kotlin. */
enum class OutputKind(val label: String) {
    SPEAKER("speaker"),
    EARPIECE("earpiece"),
    WIRED("wired"),
    USB("usb"),
    BLUETOOTH("bluetooth"),
    HDMI("hdmi"),
    OTHER("other"),
}

/**
 * Щит Bluetooth (D9, D17): наушники Bluetooth засыпают в паузах и съедают первый слог, поэтому
 * перед каждой группой — локальная тишина. Проводным (`usb`, `wired`), динамику и прочим известным
 * выходам тишина не нужна. Неизвестный выход — со щитом: лишняя секунда лучше съеденного слога.
 */
object BluetoothShield {
    fun shieldMs(output: OutputKind?): Long = when (output) {
        OutputKind.BLUETOOTH, OutputKind.OTHER, null -> ModelConfig.BLUETOOTH_SHIELD_MS
        OutputKind.SPEAKER, OutputKind.EARPIECE, OutputKind.WIRED, OutputKind.USB, OutputKind.HDMI -> 0L
    }
}
