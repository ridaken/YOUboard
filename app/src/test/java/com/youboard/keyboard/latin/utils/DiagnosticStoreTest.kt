// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private var now = 1_800_000_000_000L
    private val direct = Executor { it.run() }
    private val event = DiagnosticEvent.Lifecycle(DiagnosticReason.IME_CREATE)
    private fun store(dir: File, executor: Executor = direct, segment: Long = 512 * 1024,
                      total: Long = 16 * 1024 * 1024, retention: Long = 7 * 86400000L,
                      limit: Int = 256) = DiagnosticStore(dir, { now }, { now }, executor,
        segment, total, retention, limit)
    private fun records(text: String) = text.lineSequence().filter { it.startsWith('{') }
        .map { Json.parseToJsonElement(it).jsonObject }.toList()

    @Test fun `restart retains history with distinct sessions and ordered sequences`() {
        val directory = temp.newFolder()
        val first = store(directory)
        repeat(5) { first.record(event, 12) }
        val before = records(first.export())
        assertEquals((1L..5L).toList(), before.map { it.getValue("sequence").jsonPrimitive.content.toLong() })
        now += 1000
        val restarted = store(directory)
        restarted.record(DiagnosticEvent.Lifecycle(DiagnosticReason.PROCESS_START, 36, "4.1", "test-device"))
        val after = records(restarted.export())
        assertEquals(6, after.size)
        assertEquals(before, after.take(5))
        assertTrue(after.first().getValue("session") != after.last().getValue("session"))
        assertEquals("12", before.first().getValue("generation").jsonPrimitive.content)
    }

    @Test fun `byte and daily rotation retain only complete bounded segments`() {
        val directory = temp.newFolder()
        val s = store(directory, segment = 1000, total = 2300)
        repeat(30) { now++; s.record(event) }
        assertTrue(directory.listFiles()!!.fold(0L) { total, file -> total + file.length() } <= 2300)
        assertTrue(directory.listFiles()!!.all { it.length() <= 1000 && it.readText().endsWith('\n') })
        val previous = directory.listFiles()!!.size
        now += 86400000
        s.record(event)
        assertTrue(directory.listFiles()!!.size >= previous)
        val kept = records(s.export())
        assertTrue(kept.isNotEmpty())
        assertEquals(kept.map { it.getValue("sequence").jsonPrimitive.content.toLong() }.sorted(),
            kept.map { it.getValue("sequence").jsonPrimitive.content.toLong() })
    }

    @Test fun `expiry is enforced on startup writes and export`() {
        val directory = temp.newFolder()
        val s = store(directory, retention = 1000)
        s.record(event)
        now += 1001
        assertTrue(records(s.export()).isEmpty())
        s.record(event)
        assertEquals(1, records(s.export()).size)
        now += 1001
        val restarted = store(directory, retention = 1000)
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(records(restarted.export()).isEmpty())
    }

    @Test fun `truncated record is removed and new complete events survive`() {
        val directory = temp.newFolder()
        store(directory).record(event)
        directory.listFiles()!!.single().appendText("{\"partial\":")
        now++
        val restarted = store(directory)
        restarted.record(event)
        val exported = restarted.export()
        assertEquals(2, records(exported).size)
        assertFalse(exported.contains("partial"))
    }

    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        var immediate = false
        override fun execute(command: Runnable) { if (immediate) command.run() else tasks.add(command) }
        fun finish() { while (tasks.isNotEmpty()) tasks.removeFirst().run(); immediate = true }
    }

    @Test fun `queue prefers dropping sensor samples and records the loss`() {
        val executor = ManualExecutor()
        val directory = temp.newFolder()
        val s = store(directory, executor, limit = 2)
        s.record(DiagnosticEvent.SensorSummary(180f, 179f, 180f, 20, 0))
        s.record(event)
        s.record(DiagnosticEvent.Lifecycle(DiagnosticReason.INPUT_HIDE))
        assertTrue(directory.listFiles()!!.isEmpty(), "Caller must not write files")
        executor.finish()
        val saved = records(s.export())
        assertEquals(listOf("IME_CREATE", "INPUT_HIDE", "QUEUE_OVERFLOW"),
            saved.map { it.getValue("reason").jsonPrimitive.content })
        assertEquals("1", saved.last().getValue("data").jsonObject.getValue("count").jsonPrimitive.content)
    }

    @Test fun `overflow sequences remain increasing across batches`() {
        val executor = ManualExecutor()
        val s = store(temp.newFolder(), executor, limit = 3)
        repeat(50) { s.record(event) }
        executor.finish()
        s.record(event)
        val seq = records(s.export()).map { it.getValue("sequence").jsonPrimitive.content.toLong() }
        assertEquals(seq.sorted(), seq)
        assertTrue(s.recent().contains("QUEUE_OVERFLOW"))
    }

    @Test fun `storage failure does not throw on input path and exports memory tail`() {
        val blocked = temp.newFile()
        val s = store(blocked)
        s.record(event)
        val text = s.export()
        assertTrue(text.contains("Persistent history unavailable"))
        assertTrue(text.contains("STORAGE_FAILURE"))
        assertTrue(text.contains("IME_CREATE"))
    }

    @Test fun `partial write failure recovers without corrupting later records or saving exception text`() {
        val directory = temp.newFolder()
        var writes = 0
        val sentinel = "sensitive-exception-message"
        val s = DiagnosticStore(directory, { now }, { now }, direct, writeRecord = { file, bytes, _ ->
            if (writes++ == 0) {
                file.appendText("{\"incomplete\":")
                throw IOException(sentinel)
            }
            FileOutputStream(file, true).use { it.write(bytes) }
        })
        s.record(event)
        s.record(DiagnosticEvent.Lifecycle(DiagnosticReason.INPUT_SHOW))
        val text = s.export()
        val saved = records(text)
        assertEquals(listOf("INPUT_SHOW", "STORAGE_RECOVERED"), saved.map { it.getValue("reason").jsonPrimitive.content })
        assertEquals("1", saved.last().getValue("data").jsonObject.getValue("lostRecordCount").jsonPrimitive.content)
        assertFalse(text.contains(sentinel))
        assertFalse(text.contains("incomplete"))
        assertTrue(s.recent().contains("STORAGE_FAILURE"))
    }

    @Test fun `clear removes disk and memory history while new events continue`() {
        val directory = temp.newFolder()
        val s = store(directory)
        s.record(event)
        s.clear()
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(s.recent().isEmpty())
        s.record(DiagnosticEvent.Lifecycle(DiagnosticReason.INPUT_SHOW))
        assertEquals(listOf("INPUT_SHOW"), records(s.export()).map { it.getValue("reason").jsonPrimitive.content })
    }

    @Test fun `export barrier orders concurrent recording and produces valid records`() {
        val writer = Executors.newSingleThreadExecutor()
        try {
            val s = store(temp.newFolder(), writer)
            repeat(100) { s.record(event) }
            assertEquals(100, records(s.export()).size)
            assertEquals(128.coerceAtMost(100), records(s.recent()).size)
        } finally {
            writer.shutdown()
            assertTrue(writer.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
