// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import com.youboard.keyboard.latin.utils.FoldableUtils.Snapshot
import com.youboard.keyboard.latin.utils.FoldableUtils.State

/** Pure transition policy. The owner schedules [nextDeadline] using a monotonic clock. */
internal class FoldStateReducer {
    data class Observation(
        val foldable: Boolean, val displayId: Int, val primary: Boolean,
        val shortestWidthDp: Float, val keyboardWidthDp: Float,
        val window: State = State.UNKNOWN, val feature: State = State.UNKNOWN,
        val sensor: State = State.UNKNOWN, val unsupported: Boolean = false,
    )

    private var displayId: Int? = null
    private var committed = State.UNKNOWN
    private var candidate = State.UNKNOWN
    private var candidateSince = 0L
    private var wideSince: Long? = null
    private var eligible = false
    private var innerEvidence = false
    var nextDeadline: Long? = null
        private set
    val pendingPosture get() = candidate.takeIf { it != committed && it != State.UNKNOWN }

    fun update(o: Observation, now: Long): Snapshot {
        if (displayId != o.displayId) {
            displayId = o.displayId
            committed = State.UNKNOWN
            candidate = State.UNKNOWN
            wideSince = null
            eligible = false
            innerEvidence = false
        }
        if (o.window == State.OPEN) innerEvidence = true
        if (o.unsupported) innerEvidence = false
        val legacy = if (o.feature == State.FOLDED && o.shortestWidthDp >= EXIT_WIDTH_DP)
            State.UNKNOWN else o.feature
        val evidence = if (o.primary) FoldableUtils.resolveState(o.window, legacy, o.sensor)
            else o.window
        if (evidence == State.UNKNOWN || evidence == committed) {
            candidate = committed
        } else if (candidate != evidence) {
            candidate = evidence
            candidateSince = now
        }
        if (candidate != committed && now - candidateSince >= SETTLE_MS) committed = candidate

        val inner = !o.unsupported && committed == State.OPEN && (o.primary || innerEvidence)
        val validWidths = o.shortestWidthDp.isFinite() && o.keyboardWidthDp.isFinite()
        val safe = o.foldable && !o.unsupported && validWidths &&
            o.shortestWidthDp >= EXIT_WIDTH_DP && o.keyboardWidthDp >= EXIT_WIDTH_DP
        if (!safe || committed == State.FOLDED || (!o.primary && !innerEvidence)) {
            eligible = false
            wideSince = null
        }
        val entering = safe && (committed == State.OPEN || candidate == State.OPEN) &&
            (o.primary || innerEvidence) && o.shortestWidthDp >= ENTER_WIDTH_DP &&
            o.keyboardWidthDp >= ENTER_WIDTH_DP
        if (!eligible) {
            if (!entering) wideSince = null
            else if (wideSince == null) wideSince = now
            if (inner && wideSince != null && now - wideSince!! >= SETTLE_MS) eligible = true
        }
        nextDeadline = listOfNotNull(
            if (candidate != committed) candidateSince + SETTLE_MS else null,
            if (!eligible && wideSince != null) wideSince!! + SETTLE_MS else null,
        ).filter { it > now }.minOrNull()
        return Snapshot(o.foldable, committed, o.displayId, inner, o.shortestWidthDp,
            o.keyboardWidthDp, eligible)
    }

    companion object {
        const val SETTLE_MS = 150L
        const val ENTER_WIDTH_DP = 620f
        const val EXIT_WIDTH_DP = 600f
    }
}
