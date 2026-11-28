package com.example.listeningtrainer

import android.content.Context
import com.example.listeningtrainer.conversation.PauseScale

/** Личный множитель пауз (S07, D98) в SharedPreferences: переживает перезапуск приложения. */
class PauseScalePrefsStore(context: Context) : PauseScale.Store {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Double? =
        if (!prefs.contains(KEY_SCALE)) null else prefs.getFloat(KEY_SCALE, 1f).toDouble()

    override fun save(scale: Double) {
        prefs.edit().putFloat(KEY_SCALE, scale.toFloat()).apply()
    }

    private companion object {
        const val PREFS_NAME = "pause_scale"
        const val KEY_SCALE = "scale"
    }
}
