package com.example.listeningtrainer.eventlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LiveVsFileStoreTest {
    @Test
    fun `one json line per turn, only the last lines are kept`() {
        val dir = Files.createTempDirectory("lvf").toFile()
        val f = dir.resolve("diagnostics/live_vs_file.jsonl")
        var now = 0L
        val store = LiveVsFileStore(f, keep = 3) { now }
        for (i in 1..5) { now = i * 1000L; store.add("live $i", "file \"$i\"", 0.5) }
        val lines = f.readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("{\"time_ms\":3000,\"similarity\":0.5,\"live\":\"live 3\""))
        assertTrue(lines[2].contains("\"file\":\"file \\\"5\\\"\""))
        dir.deleteRecursively()
    }
}
