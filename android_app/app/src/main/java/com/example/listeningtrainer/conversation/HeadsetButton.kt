package com.example.listeningtrainer.conversation

/**
 * Средняя кнопка гарнитуры (D29): что делать с отпусканием кнопки. Чистый Kotlin (инвариант 3).
 *
 * Короткое нажатие:
 * - круг не идёт (`Idle`, `Error`) → как «Talk to Teacher»;
 * - идёт запись (`Listening`) → как «Stop Recording»;
 * - учитель думает или говорит → ничего (перебивание — S05).
 *
 * Повтор быстрее [debounceMs] после принятого нажатия игнорируется: двойное нажатие = одно.
 * Долгое нажатие ([longPressMs] и дольше) не наше: на Samsung оно вызывает ассистента.
 * Другие медиа-клавиши (следующий трек, громкость) — не средняя кнопка.
 */
class HeadsetButton(
    private val debounceMs: Long = 500L,
    private val longPressMs: Long = 600L,
) {
    enum class Action { TALK, FINISH_RECORDING, NONE }

    /** [action] — что сделать; [log] — строка журнала (код клавиши, состояние, итог). */
    data class Decision(val action: Action, val log: String)

    private var lastAcceptedMs: Long? = null

    /**
     * Кнопку отпустили. [keyCode] — `KeyEvent.keyCode`, [pressMs] — сколько её держали,
     * [state] — состояние разговора, [nowMs] — время отпускания.
     */
    fun onRelease(keyCode: Int, pressMs: Long, state: ConversationState, nowMs: Long): Decision {
        val key = keyName(keyCode)
        val where = "in ${stateName(state)}"
        if (!isMiddleButton(keyCode)) return Decision(Action.NONE, "headset key $key $where: not the middle button, ignored")
        if (pressMs >= longPressMs) {
            return Decision(Action.NONE, "headset key $key $where: long press ($pressMs ms), not handled")
        }
        lastAcceptedMs?.let { last ->
            val since = nowMs - last
            if (since in 0 until debounceMs) {
                return Decision(Action.NONE, "headset key $key $where: repeat after $since ms, ignored")
            }
        }
        val decision = when (state) {
            ConversationState.Idle, is ConversationState.Error ->
                Decision(Action.TALK, "headset key $key $where: Talk")
            ConversationState.Listening ->
                Decision(Action.FINISH_RECORDING, "headset key $key $where: Stop Recording")
            ConversationState.Thinking, ConversationState.Speaking ->
                Decision(Action.NONE, "headset key $key $where: ignored (teacher is thinking or speaking)")
        }
        lastAcceptedMs = nowMs
        return decision
    }

    companion object {
        // Значения android.view.KeyEvent (стабильны во всех версиях Android).
        const val KEYCODE_HEADSETHOOK = 79
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_MEDIA_STOP = 86
        const val KEYCODE_MEDIA_NEXT = 87
        const val KEYCODE_MEDIA_PREVIOUS = 88
        const val KEYCODE_MEDIA_PLAY = 126
        const val KEYCODE_MEDIA_PAUSE = 127

        /**
         * Средняя кнопка: проводные гарнитуры шлют `HEADSETHOOK`, USB и Bluetooth — обычно
         * `MEDIA_PLAY_PAUSE`, иногда раздельные `MEDIA_PLAY` / `MEDIA_PAUSE`.
         */
        fun isMiddleButton(keyCode: Int) = keyCode in setOf(
            KEYCODE_HEADSETHOOK, KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE,
        )

        fun keyName(keyCode: Int): String = when (keyCode) {
            KEYCODE_HEADSETHOOK -> "KEYCODE_HEADSETHOOK"
            KEYCODE_MEDIA_PLAY_PAUSE -> "KEYCODE_MEDIA_PLAY_PAUSE"
            KEYCODE_MEDIA_STOP -> "KEYCODE_MEDIA_STOP"
            KEYCODE_MEDIA_NEXT -> "KEYCODE_MEDIA_NEXT"
            KEYCODE_MEDIA_PREVIOUS -> "KEYCODE_MEDIA_PREVIOUS"
            KEYCODE_MEDIA_PLAY -> "KEYCODE_MEDIA_PLAY"
            KEYCODE_MEDIA_PAUSE -> "KEYCODE_MEDIA_PAUSE"
            else -> "KEYCODE_$keyCode"
        }

        private fun stateName(state: ConversationState) = when (state) {
            is ConversationState.Error -> "Error"
            else -> state.toString()
        }
    }
}
