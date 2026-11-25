package com.example.listeningtrainer

import android.content.Context
import com.example.listeningtrainer.conversation.StudentLevel

/**
 * Уровень ученика и окно (D56) — в SharedPreferences: переживают «New conversation», «Back», закрытие
 * сервиса и перезапуск приложения. Резервной копии нет (`allowBackup=false`), поэтому новая установка
 * начинается с уровня 1.
 */
class LevelPrefsStore(context: Context) : StudentLevel.Store {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): StudentLevel.State? =
        if (!prefs.contains(KEY_LEVEL)) null
        else StudentLevel.State(prefs.getInt(KEY_LEVEL, 1), prefs.getInt(KEY_TURNS, 0), prefs.getInt(KEY_NOT_UNDERSTOOD, 0))

    override fun save(state: StudentLevel.State) {
        prefs.edit()
            .putInt(KEY_LEVEL, state.level)
            .putInt(KEY_TURNS, state.turns)
            .putInt(KEY_NOT_UNDERSTOOD, state.notUnderstood)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "student_level"
        const val KEY_LEVEL = "level"
        const val KEY_TURNS = "window_turns"
        const val KEY_NOT_UNDERSTOOD = "window_not_understood"
    }
}
