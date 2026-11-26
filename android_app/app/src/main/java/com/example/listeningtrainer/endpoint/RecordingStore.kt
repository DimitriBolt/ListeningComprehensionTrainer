package com.example.listeningtrainer.endpoint

import java.io.File

/**
 * Записи для пробы (S03b) в каталоге [dir] (в приложении — `filesDir/recordings`, его скачивает
 * `tools/pull-recordings.sh`). Пара файлов на запись: `ep_<мс>_<условие>_<NN>.wav` и `.json`. В имени —
 * время записи в мс (13 цифр), поэтому новее = больше по алфавиту. Хранятся последние [keep] записей,
 * более старые удаляются парой. Чистый JVM (java.io), без Android.
 */
class RecordingStore(private val dir: File, private val keep: Int = KEEP) {

    /** Имя записи без расширения. */
    fun baseName(startedAtMs: Long, condition: String, line: Int): String =
        "$PREFIX${startedAtMs.toString().padStart(13, '0')}_${condition}_${line.toString().padStart(2, '0')}"

    /** Сколько дублей реплики [line] условия [condition] уже лежит. */
    fun takes(condition: String, line: Int): Int {
        val suffix = "_${condition}_${line.toString().padStart(2, '0')}.json"
        return names().count { it.endsWith(suffix) }
    }

    /** Реплики условия [condition], у которых уже есть хоть одна запись. */
    fun recordedLines(condition: String): Set<Int> {
        val re = Regex("^${Regex.escape(PREFIX)}\\d{13}_${Regex.escape(condition)}_(\\d{2})\\.json$")
        return names().mapNotNull { re.find(it)?.groupValues?.get(1)?.toInt() }.toSet()
    }

    /**
     * Кладёт запись: WAV копируется из [wav], рядом JSON [take]. Возвращает имя без расширения и сколько
     * старых записей удалено.
     */
    fun save(take: EndpointTake, wav: File): Pair<String, Int> {
        dir.mkdirs()
        val base = baseName(take.startedAtMs, take.condition, take.line)
        wav.copyTo(File(dir, "$base.wav"), overwrite = true)
        File(dir, "$base.json").writeText(take.toJson())
        return base to prune()
    }

    /** Удаляет записи сверх [keep]: и WAV, и JSON. Возвращает число удалённых записей. */
    fun prune(): Int {
        val bases = dir.listFiles().orEmpty()
            .map { it.name }
            .filter { it.startsWith(PREFIX) && (it.endsWith(".wav") || it.endsWith(".json")) }
            .map { it.substringBeforeLast('.') }
            .toSet()
        val old = oldRecordings(bases, keep)
        for (base in old) {
            File(dir, "$base.wav").delete()
            File(dir, "$base.json").delete()
        }
        return old.size
    }

    private fun names(): List<String> = dir.listFiles().orEmpty().map { it.name }

    companion object {
        /** Сколько последних записей хранить (бриф S03b). */
        const val KEEP = 200
        const val PREFIX = "ep_"

        /** Какие записи лишние: всё, кроме [keep] самых новых (по имени — в нём время). */
        fun oldRecordings(bases: Collection<String>, keep: Int): List<String> = bases.sortedDescending().drop(keep)
    }
}
