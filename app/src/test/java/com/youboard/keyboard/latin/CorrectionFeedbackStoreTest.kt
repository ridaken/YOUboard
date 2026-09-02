// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorrectionFeedbackStoreTest {
    private var now = 1_000_000L
    private val store = CorrectionFeedbackStore.inMemory { now }

    @Test
    fun `one rejection suppresses only the current editor session`() {
        store.recordRejection(Locale.ENGLISH, "resting", "on", "in")
        assertTrue(store.shouldSuppress(Locale.ENGLISH, "resting", "on", "in"))

        store.startSession()
        assertFalse(store.shouldSuppress(Locale.ENGLISH, "resting", "on", "in"))
    }

    @Test
    fun `two rejections persist suppression across editor sessions`() {
        repeat(2) { store.recordRejection(Locale.ENGLISH, "resting", "on", "in") }
        store.startSession()
        assertTrue(store.shouldSuppress(Locale.ENGLISH, "resting", "on", "in"))
    }

    @Test
    fun `suppression is isolated by locale and preceding word`() {
        repeat(2) { store.recordRejection(Locale.ENGLISH, "resting", "on", "in") }
        store.startSession()

        assertTrue(store.shouldSuppress(Locale.ENGLISH, "resting", "on", "in"))
        assertFalse(store.shouldSuppress(Locale.FRENCH, "resting", "on", "in"))
        assertFalse(store.shouldSuppress(Locale.ENGLISH, "turn", "on", "in"))
    }

    @Test
    fun `explicit acceptance reverses persistent suppression`() {
        repeat(2) { store.recordRejection(Locale.ENGLISH, "resting", "on", "in") }
        store.recordAcceptance(Locale.ENGLISH, "resting", "on", "in")
        store.startSession()

        assertFalse(store.shouldSuppress(Locale.ENGLISH, "resting", "on", "in"))
        assertEquals(1, store.snapshot().values.single().rejectionCount)
    }

    @Test
    fun `expired feedback is discarded`() {
        repeat(2) { store.recordRejection(Locale.ENGLISH, "resting", "on", "in") }
        now += CorrectionFeedbackStore.RETENTION_MILLIS + 1
        store.startSession()

        assertFalse(store.shouldSuppress(Locale.ENGLISH, "resting", "on", "in"))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `feedback is capped at bounded capacity`() {
        repeat(CorrectionFeedbackStore.MAX_ENTRIES + 20) { index ->
            now++
            store.recordRejection(Locale.ENGLISH, "before$index", "typed", "replacement")
        }

        assertEquals(CorrectionFeedbackStore.MAX_ENTRIES, store.snapshot().size)
        assertFalse(store.snapshot().keys.any { it.precedingWord == "before0" })
    }
}
