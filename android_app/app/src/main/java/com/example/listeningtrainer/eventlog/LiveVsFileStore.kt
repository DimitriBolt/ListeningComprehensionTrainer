package com.example.listeningtrainer.eventlog

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Тексты сверки потока и файла (S03d, только debug): одна строка JSON на реплику — время, похожесть, оба
 * текста. Это тексты ученика, поэтому не в журнале, а в отдельном файле в личном хранилище приложения
 * (`files/diagnostics/live_vs_file.jsonl`); его забирает `tools/pull-log.sh` в `diagnostics/` (вне Git).
 * Хранятся последние [keep] строк. Чистый Kotlin.
 */
class LiveVsFileStore(private val file: File, private val keep: Int = 200, private val clock: () -> Long = System::currentTimeMillis) {

    @Synchronized
    fun add(live: String, fileText: String, similarity: Double) {
        val line = buildJsonObject {
            put("time_ms", clock())
            put("similarity", Math.round(similarity * 1000) / 1000.0)
            put("live", live)
            put("file", fileText)
        }.toString()
        file.parentFile?.mkdirs()
        val lines = if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
        file.writeText((lines + line).takeLast(keep).joinToString("\n", postfix = "\n"))
    }

    companion object {
        const val DIR = "diagnostics"
        const val NAME = "live_vs_file.jsonl"
    }
}
