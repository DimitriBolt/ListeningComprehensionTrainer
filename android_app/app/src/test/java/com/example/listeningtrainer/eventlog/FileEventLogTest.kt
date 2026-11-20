package com.example.listeningtrainer.eventlog

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.TimeZone

class FileEventLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")
    private var now = 1_763_530_265_123L // 2025-11-19 05:31:05.123 UTC

    private fun newLog(maxBytes: Long = FileEventLog.DEFAULT_MAX_BYTES) =
        FileEventLog(tmp.root.resolve("logs"), maxBytes = maxBytes, clock = { now }, timeZone = utc)

    @Test
    fun `line has time with milliseconds, source and event`() {
        val log = newLog()
        log.event("Controller", "Idle -> Listening (Talk button)")
        assertEquals(
            listOf("2025-11-19 05:31:05.123 +0000 [Controller] Idle -> Listening (Talk button)"),
            log.current.readLines(),
        )
    }

    @Test
    fun `log directory is created on first write`() {
        val log = newLog()
        assertFalse(log.current.parentFile!!.exists())
        log.event("Process", "start")
        assertTrue(log.current.exists())
    }

    @Test
    fun `rotation keeps two files and drops the oldest`() {
        val log = newLog(maxBytes = 200)
        for (i in 1..20) log.event("Test", "event number $i")

        assertTrue(log.current.length() <= 200)
        assertTrue(log.previous.length() <= 200)
        val files = log.current.parentFile!!.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf(FileEventLog.PREVIOUS_NAME, FileEventLog.CURRENT_NAME).sorted(), files)

        val all = log.previous.readLines() + log.current.readLines()
        assertTrue(all.last().endsWith("event number 20"))
        assertFalse(all.any { it.endsWith("event number 1") }) // самое старое ушло
        // порядок сохранён: предыдущий файл, затем текущий
        val numbers = all.map { it.substringAfterLast(' ').toInt() }
        assertEquals(numbers.sorted(), numbers)
    }

    @Test
    fun `key and bearer header are masked`() {
        val log = newLog()
        log.event("Test", "key sk-proj-ABCdef1234567890_xyz and Bearer sk-abc123456789 here")
        val line = log.current.readText()
        assertFalse(line.contains("ABCdef1234567890"))
        assertFalse(line.contains("abc123456789"))
        assertTrue(line.contains("sk-***"))
        assertTrue(line.contains("Bearer ***"))
    }

    @Test
    fun `error has type, message and stack with causes`() {
        val log = newLog()
        val error = IOException("timeout", IllegalStateException("socket closed"))
        log.error("Controller", "transcription failed", error)

        val lines = log.current.readLines()
        assertEquals("2025-11-19 05:31:05.123 +0000 [Controller] ERROR transcription failed", lines[0])
        assertEquals("\tjava.io.IOException: timeout", lines[1])
        assertTrue(lines[2].startsWith("\t\tat "))
        assertTrue(lines.any { it == "\tCaused by: java.lang.IllegalStateException: socket closed" })
    }

    @Test
    fun `server text from JSON parse errors is not written`() {
        val log = newLog()
        val error = SerializationException(
            "Unexpected JSON token at offset 42: Expected quotation mark\n" +
                "JSON input: {\n  \"content\": \"Teacher secret reply\"\n}"
        )
        log.error("Controller", "tutor failed", error)

        val text = log.current.readText()
        assertTrue(text.contains("Unexpected JSON token at offset 42"))
        assertFalse(text.contains("Teacher secret reply"))
        assertFalse(text.contains("JSON input"))
    }

    @Test
    fun `mirror gets every record without time`() {
        val seen = mutableListOf<String>()
        val log = FileEventLog(tmp.root, clock = { now }, timeZone = utc, mirror = { s, t, e -> seen += "$s|$t|$e" })
        log.event("Timing", "stt=300")
        log.error("Service", "boom")
        assertEquals(listOf("Timing|stt=300|false", "Service|ERROR boom|true"), seen)
    }

    @Test
    fun `AppLog forwards to its sink`() {
        val log = newLog()
        val before = AppLog.sink
        try {
            AppLog.sink = log
            AppLog.event("Process", "start")
        } finally {
            AppLog.sink = before
        }
        assertTrue(log.current.readText().contains("[Process] start"))
    }
}
