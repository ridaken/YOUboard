// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingHistoryQueueTest {
    private var now = 0L
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val writes = mutableListOf<String>()
    private val queue = PendingHistoryQueue { delay, work -> scheduled.add((now + delay) to work) }
    private fun advance(ms: Long) {
        now += ms
        val due = scheduled.filter { it.first <= now }
        scheduled.removeAll(due.toSet())
        due.forEach { it.second() }
    }
    @Test fun writesOnlyAfterTheCorrectionWindow() {
        queue.add(Locale.ENGLISH, "typed", "typed") { writes.add("typed") }
        advance(24_999)
        assertEquals(emptyList(), writes)
        advance(1)
        assertEquals(listOf("typed"), writes)
    }
    @Test fun correctionCancelsOnlyTheLatestOccurrenceInItsLocale() {
        queue.add(Locale.ENGLISH, "word", "word") { writes.add("first") }
        queue.add(Locale.ENGLISH, "word", "word") { writes.add("second") }
        queue.add(Locale.GERMAN, "word", "word") { writes.add("german") }
        queue.cancelLatest(Locale.ENGLISH, "word")
        advance(25_000)
        assertEquals(listOf("first", "german"), writes)
    }
    @Test fun forgettingCancelsOriginalAndNormalizedForms() {
        queue.add(Locale.ENGLISH, "Words", "words") { writes.add("words") }
        queue.forget("WORDS")
        advance(25_000)
        assertEquals(emptyList(), writes)
    }
    @Test fun clearingInvalidatesAlreadyScheduledCallbacks() {
        queue.add(Locale.ENGLISH, "before", "before") { writes.add("before") }
        queue.clear()
        queue.add(Locale.ENGLISH, "after", "after") { writes.add("after") }
        advance(25_000)
        assertEquals(listOf("after"), writes)
    }
}
