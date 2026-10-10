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
    INPUT_CALLBACK, ENVIRONMENT, SENSOR_SUBSCRIPTION, EDITOR_RECOVERY, EDITOR_READ_FAILED,
    SUBTYPE_FALLBACK, DISRUPTION,
}

enum class CancellationCause { GEOMETRY, NEW_EDITOR, WINDOW_HIDDEN, INPUT_FINISH, SETTINGS,
    VIEW_REPLACED, MEMORY_RELEASE }
enum class RefreshCause { STARTUP, CONFIGURATION, VIEW_WIDTH, FOLD_STATE, PREFERENCE, RECOVERY }
enum class CallbackStage { RECEIVED, APPLIED, DEFERRED, SUPPRESSED }
enum class InputCallback { START, VIEW_START, FINISH, VIEW_FINISH, HIDDEN }
enum class RecoveryOutcome { SCHEDULED, RETRY, RECOVERED, EXHAUSTED, INVALIDATED }
enum class SubscriptionCause { DISPLAY, METRICS, SHOW }
enum class DisruptionKind { LAYOUT_REVERSALS, VISIBLE_INPUT_FINISHES }
enum class BurstPhase { START, UPDATE, END }
enum class EvidenceSource { SENSOR, WINDOW, LEGACY, NONE }
enum class LegacyFeatureStatus { MISSING, EMPTY, VALID, INVALID }

/** Only these typed, text-free payloads may enter persistent history. */
sealed interface DiagnosticEvent {
    val reason: DiagnosticReason
    val routine: Boolean get() = false
    fun fields(): JsonObject

    data class Correlated(val event: DiagnosticEvent, val inputSession: Long, val viewActivation: Long,
        val geometryUpdate: Long, val causes: Set<RefreshCause>) : DiagnosticEvent {
        override val reason get() = event.reason
        override val routine get() = event.routine
        override fun fields() = buildJsonObject {
            event.fields().forEach { (key, value) -> put(key, value) }
            put("inputSession", inputSession); put("viewActivation", viewActivation)
            put("geometryUpdate", geometryUpdate)
            put("refreshCauses", buildJsonArray { causes.sortedBy { it.ordinal }.forEach { add(it.name) } })
        }
    }

    data class Input(val callback: InputCallback, val stage: CallbackStage, val visible: Boolean,
        val editorAvailable: Boolean, val typeNull: Boolean, val restarting: Boolean = false,
        val finishingInput: Boolean = false) : DiagnosticEvent {
        override val reason = DiagnosticReason.INPUT_CALLBACK
        override val routine get() = stage != CallbackStage.APPLIED
        override fun fields() = buildJsonObject {
            put("callback", callback.name); put("stage", stage.name); put("visible", visible)
            put("editorAvailable", editorAvailable); put("typeNull", typeNull)
            put("restarting", restarting); put("finishingInput", finishingInput)
        }
    }
    data class Cancellation(val cause: CancellationCause, val visible: Boolean,
        val activeWork: Boolean) : DiagnosticEvent {
        override val reason = DiagnosticReason.TOUCH_CANCELLED
        override fun fields() = buildJsonObject {
            put("cause", cause.name); put("visible", visible); put("activeWork", activeWork)
        }
    }
    data class Subscription(val cause: SubscriptionCause, val sensorGeneration: Long,
        val available: Boolean, val registered: Boolean) : DiagnosticEvent {
        override val reason = DiagnosticReason.SENSOR_SUBSCRIPTION
        override fun fields() = buildJsonObject {
            put("cause", cause.name); put("sensorGeneration", sensorGeneration)
            put("available", available); put("registered", registered)
        }
    }
    data class Recovery(val outcome: RecoveryOutcome, val token: Long, val remaining: Int) : DiagnosticEvent {
        override val reason = DiagnosticReason.EDITOR_RECOVERY
        override fun fields() = buildJsonObject {
            put("outcome", outcome.name); put("recoveryGeneration", token); put("remaining", remaining)
        }
    }
    data class EditorReadFailure(val connectionAvailable: Boolean) : DiagnosticEvent {
        override val reason = DiagnosticReason.EDITOR_READ_FAILED
        override val routine = true
        override fun fields() = buildJsonObject { put("connectionAvailable", connectionAvailable) }
    }
    data class SubtypeFallback(val suppressed: Int) : DiagnosticEvent {
        override val reason = DiagnosticReason.SUBTYPE_FALLBACK
        override fun fields() = buildJsonObject { put("suppressed", suppressed) }
    }
    data class Environment(val physicalWidth: Int, val physicalHeight: Int, val windowWidth: Int,
        val resourceWidth: Int, val resourceHeight: Int, val density: Float, val displayId: Int,
        val sampleAgeMs: Long?, val evidence: FoldableUtils.State, val source: EvidenceSource,
        val fold: Fold?, val displayed: KeyboardGeometrySignature?) : DiagnosticEvent {
        override val reason = DiagnosticReason.ENVIRONMENT
        override fun fields() = buildJsonObject {
            put("physicalWidth", physicalWidth); put("physicalHeight", physicalHeight)
            put("windowWidth", windowWidth); put("resourceWidth", resourceWidth); put("resourceHeight", resourceHeight)
            put("density", density); put("displayId", displayId)
            sampleAgeMs?.let { put("hingeSampleAgeMs", it) }
            put("evidence", evidence.name); put("source", source.name)
            fold?.let { put("fold", it.fields()) }; displayed?.let { put("displayed", it.diagnosticFields()) }
        }
    }
    data class Disruption(val kind: DisruptionKind, val phase: BurstPhase, val firstMs: Long,
        val lastMs: Long, val count: Int) : DiagnosticEvent {
        override val reason = DiagnosticReason.DISRUPTION
        override val routine get() = phase == BurstPhase.UPDATE
        override fun fields() = buildJsonObject {
            put("kind", kind.name); put("phase", phase.name); put("firstMs", firstMs)
            put("lastMs", lastMs); put("count", count)
        }
    }

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
