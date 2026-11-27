package com.example.listeningtrainer.audio

/**
 * «Пульс» слушания (D28) — чистый Kotlin. Сам звук — [CueTones], когда играть — контроллер.
 */
object ListenPulse {
    /**
     * Пульс только в наушниках: проводные, USB, Bluetooth. На динамике (и на неизвестном выходе)
     * его услышит микрофон, и запись не закончится по тишине.
     */
    fun allowed(output: OutputKind?): Boolean = when (output) {
        OutputKind.WIRED, OutputKind.USB, OutputKind.BLUETOOTH -> true
        OutputKind.SPEAKER, OutputKind.EARPIECE, OutputKind.HDMI, OutputKind.OTHER, null -> false
    }
}

/**
 * Начало каждого «пульса»: проигрыватель сигналов отмечает его, режим записи для пробы пишет моменты в JSON.
 * Маски микрофона после пульса больше нет (D68): микрофон слышит его на 2–3 дБ над фоном, Silero — не речь.
 */
class PulseMarks {
    @Volatile
    var onPulse: ((Long) -> Unit)? = null

    fun pulseStarted(nowMs: Long) {
        onPulse?.invoke(nowMs)
    }
}
