package com.example.listeningtrainer

import android.app.Application
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.listeningtrainer.openai.KeyValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/**
 * Экран ключа и кнопка «My Voice». Логики диалога здесь нет: разговор ведёт
 * ConversationController в TrainerService, экран практики только показывает его состояние.
 */
class TrainerViewModel(application: Application) : AndroidViewModel(application) {

    var statusText by mutableStateOf("Ready. Please enter API key.")
    var isProcessing by mutableStateOf(false)
    var apiKey by mutableStateOf("")
    var isKeyValid by mutableStateOf(false)

    private val keyStore = ApiKeyStore(application)
    private val keyValidator = KeyValidator()
    private var debugPlayer: MediaPlayer? = null

    init {
        val savedKey = keyStore.current()
        if (savedKey.isNotEmpty()) {
            apiKey = savedKey
            validateKey()
        }
    }

    fun validateKey() {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return
        viewModelScope.launch {
            try {
                isProcessing = true
                statusText = "Validating key..."
                if (keyValidator.validate(cleanKey)) {
                    isKeyValid = true; statusText = "Key valid ✅"
                    keyStore.save(cleanKey)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusText = "Key error: ${e.localizedMessage}"
            } finally {
                isProcessing = false
            }
        }
    }

    /** «My Voice»: прослушать последнюю запись ученика. */
    fun playDebugAudio(file: File) {
        try {
            debugPlayer?.release()
            debugPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        debugPlayer?.release()
    }
}
