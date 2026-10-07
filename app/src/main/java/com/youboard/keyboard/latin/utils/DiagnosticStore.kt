// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Serialized disk work; callers only construct a bounded record and enqueue it. */
internal class DiagnosticStore(
    private val directory: File,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long,
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "youboard-diagnostics").apply { isDaemon = true }
    },
    private val segmentBytes: Long = 512L * 1024,
    private val totalBytes: Long = 16L * 1024 * 1024,
    private val retentionMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val queueLimit: Int = 256,
    private val writeRecord: (File, ByteArray, Boolean) -> Unit = { file, bytes, sync ->
        FileOutputStream(file, true).use {
            it.write(bytes)
            it.flush()
            if (sync) it.fd.sync()
        }
    },
) {
    private data class Record(val text: String, val routine: Boolean)
    private val lock = Any()
    private val queue = ArrayDeque<Record>()
    private val tail = ArrayDeque<String>()
    private val session = UUID.randomUUID().toString()
    private var sequence = 0L
    private var draining = false
    private var dropped = 0
    private var active: File? = null
    private var activeDay: String? = null
    private var lastSegmentTime = 0L
    private var failedRecords = 0
    @Volatile private var failure: String? = null
    private val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    init {
        executor.execute {
            attempt {
                prepare()
                files().forEach(::recover)
                lastSegmentTime = files().lastOrNull()?.let(::startTime) ?: 0L
                prune()
            }
        }
    }

    private fun encode(event: DiagnosticEvent, generation: Long): String = buildJsonObject {
        put("schemaVersion", 1); put("utc", utc.format(Date(wallClock())))
        put("monotonicMs", monotonicClock()); put("session", session); put("sequence", ++sequence)
        put("generation", generation); put("reason", event.reason.name); put("data", event.fields())
    }.toString()

    fun record(event: DiagnosticEvent, generation: Long = 0) {
        synchronized(lock) {
            val record = Record(encode(event, generation), event.routine)
            remember(record.text)
            if (queue.size >= queueLimit) {
                val sample = queue.firstOrNull { it.routine }
                if (sample != null) queue.remove(sample)
                else if (record.routine) { dropped++; return }
                else queue.removeFirst()
                dropped++
            }
            queue.addLast(record)
            if (!draining) {
                draining = true
                executor.execute(::drain)
            }
        }
    }

    private fun remember(text: String) {
        if (tail.size >= 128) tail.removeFirst()
        tail.addLast(text)
    }

    private fun drain() {
        drainBatch()
        synchronized(lock) {
            // Yield between bounded batches so export/clear cannot starve during a signal storm.
            if (queue.isNotEmpty() || dropped > 0) executor.execute(::drain)
            else draining = false
        }
    }

    private fun drainBatch() {
        val records = synchronized(lock) {
            val batch = queue.toList()
            queue.clear()
            val overflow = if (dropped > 0) {
                val text = encode(DiagnosticEvent.Dropped(dropped), 0)
                dropped = 0
                remember(text)
                Record(text, false)
            } else null
            batch + listOfNotNull(overflow)
        }
        records.forEach { record ->
            val previousFailure = failure
            if (attempt { append(record) }) {
                failure = null
                if (previousFailure != null) {
                    record(DiagnosticEvent.Failure(DiagnosticReason.STORAGE_RECOVERED, previousFailure, failedRecords))
                    failedRecords = 0
                }
            } else failedRecords++
        }
    }

    private fun attempt(action: () -> Unit): Boolean {
        try {
            action()
            return true
        } catch (e: Exception) {
            val type = e.javaClass.simpleName
            if (failure != type) synchronized(lock) {
                remember(encode(DiagnosticEvent.Failure(DiagnosticReason.STORAGE_FAILURE, type), 0))
            }
            failure = type
            return false
        }
    }

    private fun prepare() {
        check(directory.isDirectory || directory.mkdirs()) { "Diagnostic directory unavailable" }
    }

    private fun files() = checkNotNull(directory.listFiles()).filter {
        it.isFile && it.name.matches(Regex("diagnostic-\\d+-[a-f0-9-]+\\.jsonl"))
    }.sortedBy { startTime(it) }

    private fun startTime(file: File) = file.name.substringAfter("diagnostic-").substringBefore('-').toLong()

    private fun recover(file: File) {
        RandomAccessFile(file, "rw").use { input ->
            var length = input.length()
            while (length > 0) {
                input.seek(length - 1)
                if (input.read() == '\n'.code) break
                length--
            }
            input.setLength(length)
        }
    }

    private fun prune(reserve: Long = 0) {
        val files = files()
        var size = files.fold(0L) { size, file -> size + file.length() }
        val cutoff = wallClock() - retentionMs
        files.forEach { file ->
            if (startTime(file) < cutoff || size + reserve > totalBytes) {
                val length = file.length()
                check(file.delete()) { "Diagnostic segment removal failed" }
                size -= length
                if (file == active) active = null
            }
        }
    }

    private fun append(record: Record) {
        prepare()
        val bytes = (record.text + "\n").toByteArray(Charsets.UTF_8)
        check(bytes.size <= segmentBytes && bytes.size <= totalBytes) { "Oversized diagnostic record" }
        prune(bytes.size.toLong())
        val day = record.text.substringAfter("\"utc\":\"").take(10)
        if (active == null || activeDay != day || active!!.length() + bytes.size > segmentBytes) {
            lastSegmentTime = maxOf(wallClock(), lastSegmentTime + 1)
            active = File(directory, "diagnostic-$lastSegmentTime-${UUID.randomUUID()}.jsonl")
            activeDay = day
        }
        recover(active!!.also { if (!it.exists()) check(it.createNewFile()) })
        writeRecord(active!!, bytes, !record.routine)
    }

    fun recent(): String = synchronized(lock) { tail.joinToString("\n") }

    /** Call from an IO worker, never the input/UI thread. The barrier orders export with writes. */
    fun export(): String = onWriter {
        attempt { prepare(); prune() }
        buildString {
            appendLine("YOUBoard diagnostic history (schema 1; retention 7 days / 16 MiB)")
            val notice = failure
            if (notice != null) appendLine("Persistent history unavailable: $notice. In-memory tail follows.")
            var found = false
            try {
                files().forEach { file ->
                    file.bufferedReader().useLines { lines ->
                        lines.forEach { appendLine(it); found = true }
                    }
                }
            } catch (e: Exception) {
                appendLine("History read failed: ${e.javaClass.simpleName}")
            }
            if (!found) appendLine("No retained diagnostic history.")
            if (notice != null) appendLine(recent())
        }
    }

    fun clear() = onWriter {
        prepare()
        files().forEach { check(it.delete()) }
        active = null
        activeDay = null
        failure = null
        synchronized(lock) { tail.clear() }
    }

    private fun <T> onWriter(action: () -> T): T {
        val task = FutureTask { drainBatch(); action() }
        executor.execute(task)
        return task.get()
    }
}
