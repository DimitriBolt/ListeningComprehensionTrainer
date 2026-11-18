package com.example.listeningtrainer

import android.content.Context

/**
 * Где лежит ключ OpenAI. Сохранённый на устройстве ключ главнее; ключ из BuildConfig
 * есть только в debug-сборке (из credentials.properties), в release там пустая строка.
 * Ключ никуда не логируется.
 */
class ApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun current(): String = (prefs.getString(KEY_API_KEY, "") ?: "").ifEmpty { BuildConfig.DEBUG_API_KEY }

    fun save(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    private companion object {
        const val PREFS_NAME = "trainer_prefs"
        const val KEY_API_KEY = "openai_api_key"
    }
}
