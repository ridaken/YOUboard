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
        store?.record(event, generation)
        // A structured event is safe to mirror to system logs; general log messages stay memory-only.
        android.util.Log.i("YOUBoardDiagnostics", "${event.reason}: ${event.fields()}")
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
    fun clear() { store?.clear() }

    internal fun observerStopped() {
        handler.removeCallbacks(flushSamples)
        if (samples > 0) flushSamples.run()
        lastFold = null
        lastGeneration = -1
    }
}
