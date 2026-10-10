// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import org.junit.Test
import kotlin.test.*

class KeyboardDisruptionTest {
    @Test fun `reversals require three changes within five seconds and end after quiet`() {
        val events = mutableListOf<DiagnosticEvent.Disruption>()
        val tracker = KeyboardDisruptionTracker(events::add)
        tracker.observe(DisruptionKind.LAYOUT_REVERSALS, 0)
        tracker.observe(DisruptionKind.LAYOUT_REVERSALS, 2500)
        assertTrue(events.isEmpty())
        tracker.observe(DisruptionKind.LAYOUT_REVERSALS, 5000)
        assertEquals(BurstPhase.START, events.single().phase)
        tracker.observe(DisruptionKind.LAYOUT_REVERSALS, 5100)
        tracker.tick(5999)
        assertEquals(1, events.size)
        tracker.tick(6000)
        assertEquals(4, events.last().count)
        tracker.tick(10099)
        assertEquals(2, events.size)
        tracker.tick(10100)
        assertEquals(BurstPhase.END, events.last().phase)
        assertEquals(0, events.last().firstMs)
        assertNull(tracker.nextDeadline)
    }
    @Test fun `old observations expire and summaries do not repeat unchanged counts`() {
        val events = mutableListOf<DiagnosticEvent.Disruption>()
        val tracker = KeyboardDisruptionTracker(events::add)
        tracker.observe(DisruptionKind.LAYOUT_REVERSALS, 0)
        tracker.observe(DisruptionKind.LAYOUT_REVERSALS, 1)
        tracker.observe(DisruptionKind.LAYOUT_REVERSALS, 5002)
        assertTrue(events.isEmpty())
        tracker.observe(DisruptionKind.LAYOUT_REVERSALS, 5003)
        tracker.observe(DisruptionKind.LAYOUT_REVERSALS, 5004)
        tracker.tick(7004)
        assertEquals(1, events.size)
        tracker.clear()
        assertNull(tracker.nextDeadline)
    }
    @Test fun `visible input finishes use a separate five event threshold`() {
        val events = mutableListOf<DiagnosticEvent.Disruption>()
        val tracker = KeyboardDisruptionTracker(events::add)
        repeat(4) { tracker.observe(DisruptionKind.VISIBLE_INPUT_FINISHES, it.toLong()) }
        assertTrue(events.isEmpty())
        tracker.observe(DisruptionKind.VISIBLE_INPUT_FINISHES, 4)
        assertEquals(5, events.single().count)
        tracker.observe(DisruptionKind.LAYOUT_REVERSALS, 5)
        assertEquals(1, events.size)
    }
    @Test fun `sensor or lifecycle storm preserves totals with bounded summary traffic`() {
        val events = mutableListOf<DiagnosticEvent.Disruption>()
        val tracker = KeyboardDisruptionTracker(events::add)
        repeat(10000) { tracker.observe(DisruptionKind.VISIBLE_INPUT_FINISHES, 0) }
        assertEquals(1, events.size)
        tracker.tick(1000)
        assertEquals(10000, events.last().count)
        assertEquals(2, events.size)
        tracker.tick(5000)
        assertEquals(10000, events.last().count)
        assertEquals(BurstPhase.END, events.last().phase)
    }
    @Test fun `a recovery reload ticket is consumed once and obsolete tokens are rejected`() {
        val state = EditorRecoveryState()
        val old = state.generation
        state.pendingReload = true
        assertTrue(state.invalidate())
        assertFalse(state.accepts(old))
        state.pendingReload = true
        assertFalse(state.consumeReload(old))
        assertTrue(state.consumeReload(state.generation))
        assertFalse(state.consumeReload(state.generation))
    }
    @Test fun `fallback warnings suppress duplicates without retaining a persistent payload`() {
        val state = FallbackWarningState()
        assertNull(state.observe(null))
        assertEquals(0, state.observe("invalid selection" to listOf(1)))
        repeat(10) { assertNull(state.observe("invalid selection" to listOf(1))) }
        assertEquals(10, state.observe("invalid selection" to listOf(2)))
        assertNull(state.observe(null))
        assertEquals(0, state.observe("invalid selection" to listOf(2)))
    }
    @Test fun `export summarizes bursts and correlation and identifies older incomplete history`() {
        val summary = DiagnosticExportSummary()
        summary.observe("""{"utc":"2026-10-10T18:00:00Z","session":"one","reason":"DISRUPTION","data":{"kind":"LAYOUT_REVERSALS","phase":"START","count":3,"inputSession":2,"viewActivation":4,"geometryUpdate":6}}""")
        summary.observe("""{"reason":"QUEUE_OVERFLOW","data":{"count":10}}""")
        summary.observe("truncated")
        val text = summary.text()
        assertTrue(text.contains("LAYOUT_REVERSALS"))
        assertTrue(text.contains("input=2 view=4 update=6"))
        assertTrue(text.contains("incomplete"))
        assertTrue(text.contains("predate"))
    }
}
