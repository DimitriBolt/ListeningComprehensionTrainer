package com.example.listeningtrainer.audio

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.listeningtrainer.eventlog.AppLog
import java.io.File

/** Последняя запись ученика — для кнопки «My Voice». */
fun lastUserSpeechFile(context: Context): File =
    File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "last_user_speech.wav")

/** Сколько последних копий записей держать в Music/ListeningTrainer. */
const val DEBUG_COPIES_KEEP = 20

private const val COPY_FOLDER = "ListeningTrainer"
private const val COPY_PREFIX = "debug_speech_"

/**
 * Копия записи в общую папку Music/ListeningTrainer — для отладки, вызывается только в debug-сборке.
 * Остаются последние [DEBUG_COPIES_KEEP] копий, более старые удаляются. Нужен Android 10+
 * (MediaStore с относительным путём); на старых версиях копия не делается.
 */
fun exportToPublicMusic(context: Context, inputFile: File) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    try {
        val fileName = "$COPY_PREFIX${System.currentTimeMillis()}.wav"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$COPY_FOLDER")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let { targetUri ->
            resolver.openOutputStream(targetUri)?.use { outputStream ->
                inputFile.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
            }
        }
        val deleted = deleteOldCopies(context)
        AppLog.event("Debug", "recording copied to Music/$COPY_FOLDER, old copies deleted: $deleted")
    } catch (e: Exception) {
        AppLog.error("Debug", "copy of the recording to Music failed", e)
    }
}

/**
 * Удаляет копии сверх [DEBUG_COPIES_KEEP]. Видны и удаляются только файлы, созданные этой
 * установкой приложения (Android не даёт трогать чужие без отдельного разрешения).
 */
private fun deleteOldCopies(context: Context): Int {
    val resolver = context.contentResolver
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val copies = mutableMapOf<String, Long>()
    resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
        arrayOf("${Environment.DIRECTORY_MUSIC}/$COPY_FOLDER/", "$COPY_PREFIX%"),
        null,
    )?.use { cursor ->
        while (cursor.moveToNext()) copies[cursor.getString(1)] = cursor.getLong(0)
    }
    var deleted = 0
    for (name in oldCopies(copies.keys, DEBUG_COPIES_KEEP)) {
        deleted += resolver.delete(ContentUris.withAppendedId(collection, copies.getValue(name)), null, null)
    }
    return deleted
}

/**
 * Какие копии лишние: всё, кроме [keep] самых новых. В имени — время создания в мс,
 * поэтому новее = больше по алфавиту (у всех имён одинаковое число цифр).
 */
fun oldCopies(names: Collection<String>, keep: Int): List<String> = names.sortedDescending().drop(keep)
