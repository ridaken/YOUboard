// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import com.youboard.keyboard.latin.utils.FoldableUtils.State
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FoldStateReducerTest {
    private val open = FoldStateReducer.Observation(true, 0, true, 700f, 700f, sensor = State.OPEN)

    @Test fun `duplicates preserve the original 150 ms deadline`() {
        val r = FoldStateReducer()
        assertEquals(State.UNKNOWN, r.update(open, 0).state)
        assertEquals(150L, r.nextDeadline)
        repeat(149) { assertEquals(State.UNKNOWN, r.update(open, it + 1L).state) }
        val result = r.update(open, 150)
        assertEquals(State.OPEN, result.state)
        assertTrue(result.canAutomaticallySplit)
        assertEquals(null, r.nextDeadline)
    }

    @Test fun `return to committed posture cancels pending close`() {
        val r = FoldStateReducer()
        r.update(open, 0); r.update(open, 150)
        r.update(open.copy(sensor = State.FOLDED), 200)
        assertEquals(State.FOLDED, r.pendingPosture)
        r.update(open, 349)
        assertEquals(null, r.nextDeadline)
        assertTrue(r.update(open, 350).canAutomaticallySplit)
        r.update(open.copy(sensor = State.FOLDED), 400)
        assertEquals(State.FOLDED, r.update(open.copy(sensor = State.FOLDED), 550).state)
    }

    @Test fun `missing sources retain posture but never bootstrap open`() {
        val r = FoldStateReducer()
        val missing = open.copy(sensor = State.UNKNOWN)
        assertEquals(State.UNKNOWN, r.update(missing, 0).state)
        assertFalse(r.update(missing, 10000).canAutomaticallySplit)
        r.update(open, 10000); r.update(open, 10150)
        assertTrue(r.update(missing, 1000000).canAutomaticallySplit)
    }

    @Test fun `weak empty legacy setting cannot close a wide screen`() {
        val r = FoldStateReducer()
        val fallback = open.copy(sensor = State.UNKNOWN, feature = State.OPEN)
        r.update(fallback, 0); r.update(fallback, 150)
        repeat(1000) { i ->
            val o = fallback.copy(feature = if (i % 2 == 0) State.FOLDED else State.OPEN,
                window = if (i % 3 == 0) State.OPEN else State.UNKNOWN)
            assertTrue(r.update(o, 200L + i * 200).canAutomaticallySplit)
        }
        val cover = fallback.copy(feature = State.FOLDED, shortestWidthDp = 390f, keyboardWidthDp = 390f)
        assertFalse(r.update(cover, 300000).canAutomaticallySplit)
        assertEquals(State.FOLDED, r.update(cover, 300150).state)
    }

    @Test fun `width hysteresis disables immediately and prevents repeated reentry`() {
        val r = FoldStateReducer()
        r.update(open, 0); r.update(open, 150)
        assertTrue(r.update(open.copy(keyboardWidthDp = 600f), 200).canAutomaticallySplit)
        assertFalse(r.update(open.copy(keyboardWidthDp = 599.9f), 201).canAutomaticallySplit)
        repeat(100) { assertFalse(r.update(open.copy(keyboardWidthDp = 601f), 400L + it * 200).canAutomaticallySplit) }
        val boundary = open.copy(keyboardWidthDp = 620f, shortestWidthDp = 620f)
        assertFalse(r.update(boundary, 30000).canAutomaticallySplit)
        assertFalse(r.update(boundary, 30149).canAutomaticallySplit)
        assertTrue(r.update(boundary, 30150).canAutomaticallySplit)
    }

    @Test fun `interrupted wide interval restarts eligibility timer`() {
        val r = FoldStateReducer()
        r.update(open, 0)
        r.update(open.copy(keyboardWidthDp = 619f), 100)
        assertFalse(r.update(open, 150).canAutomaticallySplit)
        assertFalse(r.update(open, 299).canAutomaticallySplit)
        assertTrue(r.update(open, 300).canAutomaticallySplit)
    }

    @Test fun `display change resets evidence while same display resize retains posture`() {
        val r = FoldStateReducer()
        r.update(open, 0); r.update(open, 150)
        assertEquals(State.OPEN, r.update(open.copy(shortestWidthDp = 750f), 200).state)
        val monitor = open.copy(displayId = 2, primary = false)
        assertEquals(State.UNKNOWN, r.update(monitor, 201).state)
        assertFalse(r.update(monitor, 1000).canAutomaticallySplit)
        assertFalse(r.update(monitor.copy(window = State.OPEN), 1001).canAutomaticallySplit)
        assertTrue(r.update(monitor.copy(window = State.OPEN), 1151).canAutomaticallySplit)
    }

    @Test fun `unsupported layouts and invalid dimensions cannot automatically split`() {
        for (bad in listOf(open.copy(unsupported = true), open.copy(foldable = false),
            open.copy(shortestWidthDp = Float.NaN), open.copy(keyboardWidthDp = Float.POSITIVE_INFINITY))) {
            val r = FoldStateReducer()
            r.update(open, 0); r.update(open, 150)
            assertFalse(r.update(bad, 151).canAutomaticallySplit)
        }
    }
}
