// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin

import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** In-memory occurrences only; cancelled entries release their words and callbacks immediately. */
internal class PendingHistoryQueue(
    private val schedule: (Long, () -> Unit) -> Unit = { delay, work ->
        scheduler.schedule({ work() }, delay, TimeUnit.MILLISECONDS)
    }
) {
    private data class Entry(val locale: Locale, val original: String, val learned: String, val write: () -> Unit)
    private val entries = linkedMapOf<Long, Entry>()
    private var sequence = 0L
    @Synchronized fun add(locale: Locale, original: String, learned: String, write: () -> Unit) {
        val id = ++sequence
        entries[id] = Entry(locale, original, learned, write)
        schedule(25_000L) { synchronized(this) { entries.remove(id)?.write?.invoke() } }
    }
    @Synchronized fun cancelLatest(locale: Locale, word: String) {
        val id = entries.entries.lastOrNull { it.value.locale == locale && it.value.original == word }?.key
        if (id != null) entries.remove(id)
    }
    @Synchronized fun forget(word: String) {
        entries.entries.removeAll { it.value.original.equals(word, true) || it.value.learned.equals(word, true) }
    }
    @Synchronized fun clear() = entries.clear()
    companion object {
        private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "history-learning").apply { isDaemon = true }
        }
    }
}
