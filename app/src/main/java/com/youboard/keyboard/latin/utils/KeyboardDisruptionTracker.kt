// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

/** Text-free, deterministic burst detection. The owner schedules [nextDeadline]. */
internal class KeyboardDisruptionTracker(private val emit: (DiagnosticEvent.Disruption) -> Unit) {
    private data class Burst(val times: java.util.ArrayDeque<Long> = java.util.ArrayDeque(),
        var active: Boolean = false, var total: Int = 0, var first: Long = 0,
        var last: Long = 0, var reported: Long = 0, var reportedCount: Int = 0)
    private val bursts = DisruptionKind.entries.associateWith { Burst() }
    val nextDeadline: Long? get() = bursts.values.filter { it.active }.flatMap {
        listOf(it.last + 5000, it.reported + 1000)
    }.minOrNull()

    fun observe(kind: DisruptionKind, now: Long) {
        tick(now)
        val b = bursts.getValue(kind)
        val threshold = if (kind == DisruptionKind.LAYOUT_REVERSALS) 3 else 5
        while (b.times.isNotEmpty() && now - b.times.first > 5000) b.times.removeFirst()
        b.times.addLast(now)
        if (b.times.size > threshold) b.times.removeFirst()
        b.last = now
        if (b.active) b.total++
        else if (b.times.size >= threshold) {
            b.active = true; b.total = b.times.size; b.reported = now; b.first = b.times.first
            b.reportedCount = b.total
            emit(DiagnosticEvent.Disruption(kind, BurstPhase.START, b.first, now, b.total))
        }
    }

    fun tick(now: Long) {
        bursts.forEach { (kind, b) ->
            if (!b.active) return@forEach
            if (now - b.last >= 5000) {
                emit(DiagnosticEvent.Disruption(kind, BurstPhase.END, b.first, b.last, b.total))
                b.active = false; b.times.clear(); b.total = 0
            } else if (now - b.reported >= 1000) {
                if (b.total != b.reportedCount)
                    emit(DiagnosticEvent.Disruption(kind, BurstPhase.UPDATE, b.first, b.last, b.total))
                b.reportedCount = b.total
                b.reported = now
            }
        }
    }
    fun clear() { bursts.values.forEach { it.times.clear(); it.active = false; it.total = 0 } }
}

/** A deferred editor operation belongs to one lifecycle generation, never the next editor. */
class EditorRecoveryState {
    var generation = 0L
        private set
    var pendingReload = false
    fun invalidate(): Boolean {
        generation++
        val pending = pendingReload
        pendingReload = false
        return pending
    }
    fun accepts(token: Long) = token == generation
    fun consumeReload(token: Long): Boolean {
        if (!accepts(token) || !pendingReload) return false
        pendingReload = false
        return true
    }
}

/** Invalid evidence must not erase the hysteresis memory used by the next valid sample. */
internal class HingeClassification {
    private var lastValid = FoldableUtils.State.UNKNOWN
    fun observe(angle: Float?): FoldableUtils.State {
        val state = FoldableUtils.stateFromAngle(angle, lastValid)
        if (state != FoldableUtils.State.UNKNOWN) lastValid = state
        return state
    }
}
