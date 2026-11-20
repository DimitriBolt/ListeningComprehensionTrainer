package com.example.listeningtrainer.eventlog

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Журнал в файлах [dir]/events.log и [dir]/events.1.log (предыдущий). Когда текущий файл
 * дорастает до [maxBytes], он становится предыдущим, а старый предыдущий удаляется:
 * на диске не больше двух файлов.
 *
 * Строка: `2026-11-19 22:31:05.123 -0700 [Источник] событие`. У ошибки следом идут строки стека,
 * каждая начинается с табуляции.
 *
 * Каждая строка сразу пишется и закрывается: при падении процесса в файле есть всё до падения.
 * Чистый Kotlin, без Android.
 */
class FileEventLog(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    timeZone: TimeZone = TimeZone.getDefault(),
    /** Копия каждой записи (например, в Logcat): источник, текст без времени, ошибка ли. */
    private val mirror: (source: String, text: String, isError: Boolean) -> Unit = { _, _, _ -> },
) : EventLog {

    val current = File(dir, CURRENT_NAME)
    val previous = File(dir, PREVIOUS_NAME)

    private val format = timeFormat(timeZone)

    override fun event(source: String, message: String) = write(source, redact(message), isError = false)

    override fun error(source: String, message: String, error: Throwable?) {
        val text = buildString {
            append("ERROR ").append(redact(message))
            if (error != null) append('\n').append(describeThrowable(error))
        }
        write(source, text, isError = true)
    }

    @Synchronized
    private fun write(source: String, text: String, isError: Boolean) {
        val line = "${format.format(Date(clock()))} [$source] $text\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        try {
            dir.mkdirs()
            if (current.length() > 0 && current.length() + bytes.size > maxBytes) rotate()
            FileOutputStream(current, true).use { it.write(bytes) }
        } catch (e: IOException) {
            // Журнал не должен ронять приложение.
        }
        mirror(source, text, isError)
    }

    private fun rotate() {
        previous.delete()
        current.renameTo(previous)
    }

    companion object {
        const val CURRENT_NAME = "events.log"
        const val PREVIOUS_NAME = "events.1.log"
        const val DEFAULT_MAX_BYTES = 1_000_000L
        private const val MAX_FRAMES = 40
        private const val MAX_MESSAGE = 300

        fun timeFormat(timeZone: TimeZone = TimeZone.getDefault()) =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).apply { this.timeZone = timeZone }

        private val KEY = Regex("sk-[A-Za-z0-9_\\-]{8,}")
        private val BEARER = Regex("Bearer\\s+\\S+")

        /** Страховка: ключ и заголовок авторизации не попадают в журнал, даже если их передали по ошибке. */
        fun redact(text: String): String =
            text.replace(KEY, "sk-***").replace(BEARER, "Bearer ***")

        /**
         * Сообщение исключения без чужих текстов. Ошибки разбора JSON (kotlinx.serialization)
         * дописывают в конец кусок ответа сервера («JSON input: …») — а в нём может быть текст
         * учителя. Всё от этой метки отрезается.
         */
        fun safeMessage(message: String?): String {
            if (message == null) return ""
            val cut = message.substringBefore("JSON input").replace(Regex("\\s+"), " ").trim()
            return redact(cut).take(MAX_MESSAGE)
        }

        /** Тип, сообщение и стек исключения со всеми причинами; каждая строка — с табуляции. */
        fun describeThrowable(error: Throwable): String = buildString {
            var e: Throwable? = error
            var first = true
            val seen = mutableSetOf<Throwable>()
            while (e != null && seen.add(e)) {
                append(if (first) "\t" else "\tCaused by: ")
                append(e.javaClass.name)
                val msg = safeMessage(e.message)
                if (msg.isNotEmpty()) append(": ").append(msg)
                val frames = e.stackTrace
                for (frame in frames.take(MAX_FRAMES)) append("\n\t\tat ").append(frame)
                if (frames.size > MAX_FRAMES) append("\n\t\t... ${frames.size - MAX_FRAMES} more")
                e = e.cause
                first = false
                if (e != null) append('\n')
            }
        }
    }
}
