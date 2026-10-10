// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.youboard.keyboard.latin.BuildConfig
import java.io.File

object Diagnostics {
    private var store: DiagnosticStore? = null
    private var lastFold: DiagnosticEvent.Fold? = null
    private var lastGeneration = -1L
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var latest: Float? = null
    private var minimum: Float? = null
    private var maximum: Float? = null
    private var samples = 0
    private var invalid = 0
    private var sampleGeneration = 0L
    private var inputSession = 0L
    private var viewActivation = 0L
    private var geometryUpdate = 0L
    internal var displayedGeometry: KeyboardGeometrySignature? = null
        private set
    private var refreshCauses: Set<RefreshCause> = emptySet()
    private val disruption = KeyboardDisruptionTracker { record(it) }
    private val checkDisruption = Runnable { disruption.tick(SystemClock.elapsedRealtime()); scheduleDisruption() }
    private fun scheduleDisruption() {
        handler.removeCallbacks(checkDisruption)
        disruption.nextDeadline?.let { handler.postDelayed(checkDisruption,
            (it - SystemClock.elapsedRealtime()).coerceAtLeast(1)) }
    }

    @JvmStatic fun inputStarted() { inputSession++ }
    @JvmStatic fun viewStarted() { viewActivation++ }
    @JvmStatic fun geometryCauses(causes: Set<RefreshCause>) { refreshCauses = causes.toSet() }
    @JvmStatic fun geometryUpdate() { geometryUpdate++ }
    private val flushSamples = Runnable {
        record(DiagnosticEvent.SensorSummary(latest, minimum, maximum, samples, invalid), sampleGeneration)
        samples = 0; invalid = 0; minimum = null; maximum = null
    }

    fun init(context: Context) {
        if (store != null) return
        val storageContext = storageContext(context)
        store = DiagnosticStore(File(storageContext.noBackupFilesDir, "diagnostics"),
            monotonicClock = SystemClock::elapsedRealtime)
        record(DiagnosticEvent.Lifecycle(DiagnosticReason.PROCESS_START, Build.VERSION.SDK_INT,
            BuildConfig.VERSION_NAME, "${Build.BRAND} ${Build.MODEL}"))
    }

    internal fun storageContext(context: Context): Context =
        if (Build.VERSION.SDK_INT >= 24 && !context.isDeviceProtectedStorage)
            context.createDeviceProtectedStorageContext() else context

    @JvmStatic @JvmOverloads fun record(event: DiagnosticEvent, generation: Long = 0) {
        val correlated = DiagnosticEvent.Correlated(event, inputSession, viewActivation, geometryUpdate, refreshCauses)
        store?.record(correlated, generation)
        // A structured event is safe to mirror to system logs; general log messages stay memory-only.
        if (event is DiagnosticEvent.Disruption && event.phase == BurstPhase.START)
            android.util.Log.w("YOUBoardDiagnostics", "${event.reason}: ${correlated.fields()}")
        else android.util.Log.i("YOUBoardDiagnostics", "${event.reason}: ${correlated.fields()}")
        val now = SystemClock.elapsedRealtime()
        if (event is DiagnosticEvent.Geometry && event.reason == DiagnosticReason.GEOMETRY_APPLIED)
            displayedGeometry = event.after
        if (event is DiagnosticEvent.Geometry && event.reason == DiagnosticReason.GEOMETRY_APPLIED &&
            event.before != null && event.before.split != event.after.split) {
            disruption.observe(DisruptionKind.LAYOUT_REVERSALS, now)
            scheduleDisruption()
        }
        if (event is DiagnosticEvent.Input && event.callback == InputCallback.FINISH &&
            event.stage == CallbackStage.RECEIVED && event.visible) {
            disruption.observe(DisruptionKind.VISIBLE_INPUT_FINISHES, now)
            scheduleDisruption()
        }
    }

    internal fun fold(event: DiagnosticEvent.Fold, generation: Long) {
        val comparable = event.copy(angle = null)
        val old = lastFold
        if (comparable == old && lastGeneration == generation) return
        val reason = when {
            old != null && old.committed != event.committed -> DiagnosticReason.POSTURE_COMMITTED
            old != null && old.candidate != event.candidate -> DiagnosticReason.POSTURE_PENDING
            old != null && old.eligible != event.eligible -> DiagnosticReason.ELIGIBILITY_CHANGED
            else -> DiagnosticReason.SOURCE_OBSERVATION
        }
        lastFold = comparable
        lastGeneration = generation
        record(event.copy(reason = reason), generation)
    }

    internal fun sensor(angle: Float?, generation: Long) {
        if (samples > 0 && sampleGeneration != generation) {
            handler.removeCallbacks(flushSamples)
            flushSamples.run()
        }
        sampleGeneration = generation
        latest = angle
        if (angle == null) invalid++ else {
            minimum = minimum?.let { minOf(it, angle) } ?: angle
            maximum = maximum?.let { maxOf(it, angle) } ?: angle
        }
        if (samples++ == 0) handler.postDelayed(flushSamples, 1000)
    }

    @JvmStatic fun recent(): String = store?.recent() ?: "Diagnostic history has not initialized."
    fun export(): String = store?.export() ?: "Diagnostic history has not initialized."
    fun clear() {
        store?.clear()
        handler.post {
            disruption.clear(); handler.removeCallbacks(checkDisruption)
            handler.removeCallbacks(flushSamples)
            samples = 0; invalid = 0; minimum = null; maximum = null
            lastFold = null; lastGeneration = -1
        }
    }

    internal fun observerStopped() {
        handler.removeCallbacks(flushSamples)
        if (samples > 0) flushSamples.run()
        lastFold = null
        lastGeneration = -1
    }
}
