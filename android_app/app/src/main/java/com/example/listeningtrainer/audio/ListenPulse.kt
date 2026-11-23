package com.example.listeningtrainer.audio

import com.example.listeningtrainer.conversation.TurnDetector

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
 * Страховка: если микрофон всё же услышит «пульс» (громкие наушники, неплотная посадка), этот звук
 * не должен считаться речью. Проигрыватель сигналов отмечает начало каждого пульса ([pulseStarted]),
 * запись не учитывает куски, которые с ним пересекаются ([masked]). [maskMs] — длина пульса плюс
 * задержки выхода и входа звука. Время — в мс одних и тех же часов.
 */
class PulseEchoGate(private val maskMs: Long) {
    @Volatile
    private var pulseAtMs: Long? = null

    fun pulseStarted(nowMs: Long) {
        pulseAtMs = nowMs
    }

    /** Кусок записи `[windowEndMs - windowMs, windowEndMs]` пересекается с последним пульсом. */
    fun masked(windowEndMs: Long, windowMs: Long): Boolean {
        val at = pulseAtMs ?: return false
        return windowEndMs > at && windowEndMs - windowMs < at + maskMs
    }
}

/**
 * Громкость записи по кускам: пик, экран, правило конца реплики. Куски, в которых звучал «пульс»
 * ([gate]), не идут ни в пик, ни на экран, а [detector] получает их как тишину — так отсчёт тишины
 * продолжается, но пульс никогда не станет «речью».
 */
class RecordingMeter(
    private val detector: TurnDetector,
    private val gate: PulseEchoGate?,
    private val windowMs: Long,
) {
    var peakDb = 0f
        private set
    var maskedWindows = 0
        private set

    fun start(nowMs: Long) = detector.reset(nowMs)

    /** Очередной кусок громкостью [db] прочитан в [nowMs]. `true` — реплика закончена. */
    fun onWindow(db: Float, nowMs: Long, onLevel: (Float) -> Unit): Boolean {
        if (gate?.masked(nowMs, windowMs) == true) {
            maskedWindows++
            return detector.onLevel(SILENT_DB, nowMs)
        }
        if (db > peakDb) peakDb = db
        onLevel(db)
        return detector.onLevel(db, nowMs)
    }

    val heardSpeech: Boolean get() = detector.heardSpeech

    private companion object {
        /** Уровень полной тишины на шкале [PcmLevel] (RMS = 0). */
        const val SILENT_DB = 0f
    }
}
