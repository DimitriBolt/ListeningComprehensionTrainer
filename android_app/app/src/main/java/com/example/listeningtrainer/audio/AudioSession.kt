package com.example.listeningtrainer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.listeningtrainer.eventlog.EventLog

/**
 * Звук на время разговора (от первого «Talk» до «Back»): аудиофокус и наблюдение за выходами.
 *
 * Фокус берётся, чтобы знать, когда его забирают (звонок, другое приложение): потеря и возврат
 * только пишутся в журнал, поведение разговора от них не меняется (решение пользователя S01b).
 * Подключение и отключение наушников пишутся по типу выхода — без имён устройств.
 */
class AudioSession(context: Context, private val log: EventLog) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var firstDeviceReport = true

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        log.event(SOURCE, "audio focus: ${focusChangeName(change)}")
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // Первый вызов приходит сразу после регистрации — это список выходов на старте.
            val what = if (firstDeviceReport) "audio outputs at start" else "audio output connected"
            firstDeviceReport = false
            logOutputs(what, addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            logOutputs("audio output disconnected", removedDevices)
        }
    }

    fun start() {
        val am = audioManager ?: return log.event(SOURCE, "no AudioManager")
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, handler)
                .build()
            focusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        log.event(SOURCE, "audio focus requested: ${requestResultName(result)}")
        am.registerAudioDeviceCallback(deviceCallback, handler)
    }

    fun stop() {
        val am = audioManager ?: return
        am.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
        log.event(SOURCE, "audio focus abandoned")
    }

    private fun logOutputs(what: String, devices: Array<out AudioDeviceInfo>) {
        val outputs = devices.filter { it.isSink }.map { outputKind(it.type) }.distinct()
        if (outputs.isNotEmpty()) log.event(SOURCE, "$what: ${outputs.joinToString()}")
    }

    private companion object {
        const val SOURCE = "Audio"

        /** Только тип выхода: динамик, проводные, Bluetooth… Имена устройств не пишутся. */
        fun outputKind(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired (headphones)"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired (headset)"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth (a2dp)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth (sco)"
            TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER -> "bluetooth (le)"
            AudioDeviceInfo.TYPE_HDMI -> "hdmi"
            else -> "other (type $type)"
        }

        // Константы Android 12 (API 31); значения стабильны, сравниваем числа и на старых версиях.
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27

        fun focusChangeName(change: Int): String = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "gained"
            AudioManager.AUDIOFOCUS_LOSS -> "lost (permanent)"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "lost (transient, e.g. a call)"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "lost (can duck)"
            else -> "change $change"
        }

        fun requestResultName(result: Int): String = when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "failed"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "delayed"
            else -> "result $result"
        }
    }
}
