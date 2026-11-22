package com.example.listeningtrainer.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Выходы звука Android → [OutputKind] и подписи для журнала. Имена устройств не используются. */
object AudioRoutes {

    // Константы новых версий Android; значения стабильны, сравниваем числа и на старых версиях.
    private const val TYPE_HEARING_AID = 23 // Bluetooth-слуховой аппарат (API 28)
    private const val TYPE_BLE_HEADSET = 26 // API 31
    private const val TYPE_BLE_SPEAKER = 27
    private const val TYPE_BLE_BROADCAST = 30 // API 33

    fun kind(type: Int): OutputKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputKind.EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> OutputKind.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        TYPE_HEARING_AID, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST -> OutputKind.BLUETOOTH
        AudioDeviceInfo.TYPE_HDMI -> OutputKind.HDMI
        else -> OutputKind.OTHER
    }

    /** Подпись выхода для журнала: тип и, для Bluetooth и проводных, вид связи. */
    fun label(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired (headphones)"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired (headset)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth (a2dp)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth (sco)"
        TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST -> "bluetooth (le)"
        TYPE_HEARING_AID -> "bluetooth (hearing aid)"
        else -> kind(type).let { if (it == OutputKind.OTHER) "other (type $type)" else it.label }
    }

    /**
     * Куда сейчас пойдёт звук с атрибутами [attributes]. Android 13+ отвечает точно
     * (`getAudioDevicesForAttributes`); на старых — по подключённым выходам с приоритетом
     * Bluetooth → usb → wired → динамик, как маршрутизирует медиа Android.
     * Возвращает `AudioDeviceInfo.type` выхода; `null` — определить нельзя.
     */
    fun current(audioManager: AudioManager?, attributes: AudioAttributes): Int? {
        val am = audioManager ?: return null
        if (Build.VERSION.SDK_INT >= 33) {
            val routed = try { am.getAudioDevicesForAttributes(attributes) } catch (e: Exception) { emptyList() }
            routed.firstOrNull()?.let { return it.type }
        }
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val priority = listOf(OutputKind.BLUETOOTH, OutputKind.USB, OutputKind.WIRED, OutputKind.SPEAKER)
        for (kind in priority) outputs.firstOrNull { kind(it) == kind }?.let { return it }
        return outputs.firstOrNull()
    }
}
