package com.example.listeningtrainer.audio

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Последняя запись ученика — для кнопки «My Voice». */
fun lastUserSpeechFile(context: Context): File =
    File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "last_user_speech.wav")

/** Копия записи в общую папку Music/ListeningTrainer (отладка, как было до S01). */
fun exportToPublicMusic(context: Context, inputFile: File) {
    try {
        val fileName = "debug_speech_${System.currentTimeMillis()}.wav"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/ListeningTrainer")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let { targetUri ->
            resolver.openOutputStream(targetUri)?.use { outputStream ->
                inputFile.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
}
