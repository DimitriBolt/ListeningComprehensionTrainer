package com.example.listeningtrainer.audio

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.example.listeningtrainer.conversation.HeadsetButton
import com.example.listeningtrainer.eventlog.EventLog

/**
 * Кнопки гарнитуры (D29) через медиа-сеанс: работает и с выключенным экраном, для проводных, USB и
 * Bluetooth-гарнитур (AVRCP приходит теми же кодами).
 *
 * Сеанс активен и «играет» всё время жизни сервиса. С Android 8 медиа-кнопку получает приложение,
 * которое последним играло звук, если его сеанс активен; наши сигналы и голос идут через медиа-поток,
 * поэтому после первого же сигнала кнопка наша, пока звук не включит другое приложение.
 *
 * Нажатие (`ACTION_DOWN`) поглощается, решение — на отпускание (`ACTION_UP`): [onRelease] получает
 * код клавиши и длительность нажатия и возвращает, наше ли это нажатие. Не наше (долгое, другая
 * клавиша) уходит системе как обычно.
 */
class HeadsetButtons(
    context: Context,
    private val log: EventLog,
    private val onRelease: (keyCode: Int, pressMs: Long) -> Boolean,
) {
    private val session = MediaSession(context, "ListeningTrainer")

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event = keyEvent(mediaButtonIntent) ?: return false
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> event.repeatCount == 0 && isOurKey(event.keyCode)
                KeyEvent.ACTION_UP -> {
                    if (event.flags and KeyEvent.FLAG_CANCELED != 0) {
                        log.event(SOURCE, "headset key ${KeyEvent.keyCodeToString(event.keyCode)}: press cancelled by the system")
                        false
                    } else {
                        onRelease(event.keyCode, event.eventTime - event.downTime)
                    }
                }
                else -> false
            }
        }
    }

    fun start() {
        @Suppress("DEPRECATION") // на Android 8+ флаги не нужны, на 7 — нужны
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setCallback(callback, Handler(Looper.getMainLooper()))
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        session.isActive = true
        log.event(SOURCE, "media session active: headset button handled by the app")
    }

    fun release() {
        session.isActive = false
        session.release()
        log.event(SOURCE, "media session released")
    }

    private fun isOurKey(keyCode: Int) = HeadsetButton.isMiddleButton(keyCode)

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    private companion object {
        const val SOURCE = "Service"
    }
}
