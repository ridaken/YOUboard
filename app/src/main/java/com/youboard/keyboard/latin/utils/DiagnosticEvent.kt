// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import com.youboard.keyboard.latin.settings.SplitKeyboardSettings

enum class DiagnosticReason {
    PROCESS_START, IME_CREATE, IME_DESTROY, INPUT_SHOW, INPUT_HIDE, CONFIGURATION,
    SOURCE_OBSERVATION, POSTURE_PENDING, POSTURE_COMMITTED, ELIGIBILITY_CHANGED,
    SENSOR_SUMMARY, WINDOW_UNAVAILABLE, MANUAL_SPLIT, MANUAL_ONE_HANDED, MANUAL_FLOATING,
    GEOMETRY_APPLIED, GEOMETRY_SKIPPED, TOUCH_CANCELLED, STORAGE_FAILURE, STORAGE_RECOVERED, QUEUE_OVERFLOW,
}
enum class LegacyFeatureStatus { MISSING, EMPTY, VALID, INVALID }

/** Only these typed, text-free payloads may enter persistent history. */
sealed interface DiagnosticEvent {
    val reason: DiagnosticReason
    val routine: Boolean get() = false
    fun fields(): JsonObject

    data class Lifecycle @JvmOverloads constructor(override val reason: DiagnosticReason, val sdk: Int = 0,
                         val version: String? = null, val device: String? = null) : DiagnosticEvent {
        override fun fields() = buildJsonObject {
            put("sdk", sdk)
            version?.let { put("version", it.take(64)) }
            device?.let { put("device", it.take(128)) }
        }
    }
    data class Fold(
        val window: FoldableUtils.State, val feature: FoldableUtils.State,
        val sensor: FoldableUtils.State, val committed: FoldableUtils.State,
        val candidate: FoldableUtils.State?, val eligible: Boolean, val displayId: Int,
        val shortestWidthDp: Float, val keyboardWidthDp: Float, val density: Float,
        val angle: Float?, val unsupported: Boolean, val legacyStatus: LegacyFeatureStatus,
        override val reason: DiagnosticReason = DiagnosticReason.SOURCE_OBSERVATION,
        val foldBounds: List<Int> = emptyList(),
    ) : DiagnosticEvent {
        override val routine get() = reason == DiagnosticReason.SOURCE_OBSERVATION || reason == DiagnosticReason.POSTURE_PENDING
        override fun fields() = buildJsonObject {
            put("window", window.name); put("legacy", feature.name); put("sensor", sensor.name)
            put("committed", committed.name); put("candidate", candidate?.name ?: "NONE")
            put("eligible", eligible); put("displayId", displayId)
            if (shortestWidthDp.isFinite()) put("shortestWidthDp", shortestWidthDp)
            if (keyboardWidthDp.isFinite()) put("keyboardWidthDp", keyboardWidthDp)
            if (density.isFinite()) put("density", density)
            angle?.takeIf { it.isFinite() }?.let { put("angle", it) }
            put("unsupported", unsupported); put("legacyStatus", legacyStatus.name)
            put("foldBounds", buildJsonArray { foldBounds.take(16).forEach { add(it) } })
            put("disagreement", listOf(window, feature, sensor)
                .filter { it != FoldableUtils.State.UNKNOWN }.distinct().size > 1)
        }
    }
    data class SensorSummary(val latest: Float?, val minimum: Float?, val maximum: Float?,
                             val count: Int, val invalidCount: Int) : DiagnosticEvent {
        override val reason = DiagnosticReason.SENSOR_SUMMARY
        override val routine = true
        override fun fields() = buildJsonObject {
            latest?.let { put("latest", it) }; minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }; put("count", count); put("invalidCount", invalidCount)
            put("suppressed", (count - 1).coerceAtLeast(0))
        }
    }
    data class Geometry @JvmOverloads constructor(override val reason: DiagnosticReason, val before: KeyboardGeometrySignature?,
                        val after: KeyboardGeometrySignature, val folded: Boolean,
                        val automaticEligible: Boolean, val mode: SplitKeyboardSettings.Mode? = null) : DiagnosticEvent {
        override fun fields() = buildJsonObject {
            before?.let { put("before", it.diagnosticFields()) }
            put("after", after.diagnosticFields()); put("folded", folded)
            put("automaticEligible", automaticEligible); mode?.let { put("mode", it.name) }
        }
    }
    data class Mode @JvmOverloads constructor(override val reason: DiagnosticReason, val enabled: Boolean,
                    val landscape: Boolean, val folded: Boolean,
                    val selection: SplitKeyboardSettings.Mode? = null) : DiagnosticEvent {
        override fun fields() = buildJsonObject {
            put("enabled", enabled); put("landscape", landscape); put("folded", folded)
            selection?.let { put("selection", it.name) }
        }
    }
    data class Failure(override val reason: DiagnosticReason, val exceptionType: String,
                       val lostRecordCount: Int = 0) : DiagnosticEvent {
        override fun fields() = buildJsonObject {
            put("exceptionType", exceptionType.filter { it.isLetterOrDigit() || it == '_' }.take(64))
            put("lostRecordCount", lostRecordCount)
        }
    }
    data class Dropped(val count: Int) : DiagnosticEvent {
        override val reason = DiagnosticReason.QUEUE_OVERFLOW
        override fun fields() = buildJsonObject { put("count", count) }
    }
}
