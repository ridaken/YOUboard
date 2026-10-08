// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Point
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow

object FoldableUtils {
    enum class State { UNKNOWN, FOLDED, OPEN }

    data class Snapshot(
        val isFoldable: Boolean = false,
        val state: State = State.UNKNOWN,
        val displayId: Int = Display.INVALID_DISPLAY,
        val isInnerDisplay: Boolean = false,
        val shortestDisplayWidthDp: Float = 0f,
        val keyboardWidthDp: Float = 0f,
        val automaticSplitEligible: Boolean = false,
        val generation: Long = 0,
    ) {
        val canAutomaticallySplit: Boolean get() = automaticSplitEligible && isFoldable &&
            state == State.OPEN && isInnerDisplay && shortestDisplayWidthDp >= 600f && keyboardWidthDp >= 600f
    }

    private val snapshotFlow = MutableStateFlow(Snapshot())
    val snapshots = snapshotFlow.asStateFlow()
    var snapshot = Snapshot()
        private set(value) {
            field = value
            snapshotFlow.value = value
        }
    var isFoldable = false
        private set
    private var generationCounter = 0L
    val isFolded: Boolean get() = snapshot.state == State.FOLDED

    fun init(context: Context) {
        val feature = parseFeatureState(getFeatureString(context))
        isFoldable = feature != State.UNKNOWN || hasFoldSensor(context)
        snapshot = Snapshot(isFoldable)
    }

    private fun hasFoldSensor(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

    private const val DISPLAY_FEATURES = "display_features"
    private val displayFeaturesUri = Settings.Global.getUriFor(DISPLAY_FEATURES)
    private val featurePattern = Regex("(fold|hinge)-\\[(\\d+),(\\d+),(\\d+),(\\d+)]-(flat|half-opened)")

    fun getFeatureString(context: Context): String? = try {
        Settings.Global.getString(context.contentResolver, DISPLAY_FEATURES)
    } catch (_: SecurityException) { null }

    /** This legacy OEM setting is a fallback, never proof that an arbitrary display is an inner screen. */
    internal fun parseFeatureState(value: String?): State {
        if (value == null) return State.UNKNOWN
        if (value.isEmpty()) return State.FOLDED
        // Multiple folds and physically occluding hinges need a dedicated layout.
        val match = featurePattern.matchEntire(value.trim()) ?: return State.UNKNOWN
        val (type, left, top, right, bottom) = match.destructured
        val l = left.toIntOrNull() ?: return State.UNKNOWN
        val t = top.toIntOrNull() ?: return State.UNKNOWN
        val r = right.toIntOrNull() ?: return State.UNKNOWN
        val b = bottom.toIntOrNull() ?: return State.UNKNOWN
        if (type != "fold" || r < l || b < t || (r == l) == (b == t)) return State.UNKNOWN
        return State.OPEN
    }

    internal fun featureCoordinates(value: String?): List<Int> =
        if (parseFeatureState(value) == State.OPEN)
            featurePattern.matchEntire(value!!.trim())!!.groupValues.drop(2).take(4).map { it.toInt() }
        else emptyList()

    internal fun stateFromAngle(angle: Float?, previous: State = State.UNKNOWN): State = when {
        angle == null || !angle.isFinite() || angle !in 0f..180f -> State.UNKNOWN
        angle <= 35f -> State.FOLDED
        angle >= 45f -> State.OPEN
        else -> previous
    }

    internal fun resolveState(window: State, feature: State, sensor: State): State {
        return listOf(sensor, window, feature).firstOrNull { it != State.UNKNOWN } ?: State.UNKNOWN
    }

    /** Owned by the IME, including on devices whose only fold signal is WindowManager. */
    class FoldableObserver internal constructor(
        private val ime: InputMethodService, private val onChanged: Runnable,
        private val windowInfo: (Context) -> Flow<WindowLayoutInfo>,
    ) {
        constructor(ime: InputMethodService, onChanged: Runnable) : this(ime, onChanged, {
            WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it)
        })
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var windowJob: Job? = null
        private var windowState = State.UNKNOWN
        private var sensorState = State.UNKNOWN
        private var unsupportedWindow = false
        private var observedDisplay = Display.INVALID_DISPLAY
        private var observedWidth = 0
        private var observedHeight = 0
        private var observedDensity = 0f
        private var generation = ++generationCounter
        private var foldBounds: List<Int> = emptyList()
        private var sensorStartNanos = 0L
        private var hingeAngle: Float? = null
        private val reducer = FoldStateReducer()
        private val handler = Handler(Looper.getMainLooper())
        private var settle: Runnable? = null
        private var closed = false
        private val sm = ime.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val featureObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { refresh() }
        }
        private val sensorListener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) {
                if (closed || event.timestamp < sensorStartNanos) return
                hingeAngle = event.values.firstOrNull()?.takeIf { it.isFinite() && it in 0f..180f }
                sensorState = stateFromAngle(hingeAngle, sensorState)
                Diagnostics.sensor(hingeAngle, generation)
                refresh()
            }
        }

        init {
            ime.contentResolver.registerContentObserver(displayFeaturesUri, false, featureObserver)
            refresh()
        }

        @Suppress("DEPRECATION")
        fun refresh() {
            if (closed) return
            val wm = ime.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            val size = Point().also { display.getRealSize(it) }
            val density = ime.resources.displayMetrics.density
            if (observedDisplay != display.displayId || observedWidth != size.x || observedHeight != size.y || observedDensity != density) {
                val displayChanged = observedDisplay != display.displayId
                generation = ++generationCounter
                settle?.let(handler::removeCallbacks)
                if (displayChanged) {
                    sensorState = State.UNKNOWN
                    hingeAngle = null
                    sm.unregisterListener(sensorListener)
                    sensorStartNanos = SystemClock.elapsedRealtimeNanos()
                    if (hasFoldSensor(ime)) sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let {
                        sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
                    }
                }
                observedDisplay = display.displayId
                observedWidth = size.x
                observedHeight = size.y
                observedDensity = density
                windowState = State.UNKNOWN
                foldBounds = emptyList()
                if (displayChanged) unsupportedWindow = false
                windowJob?.cancel()
                val subscriptionGeneration = generation
                // A display/configuration change invalidates the old window's feature coordinates.
                windowJob = scope.launch {
                    try {
                        windowInfo(ime).collect { info ->
                            if (closed || generation != subscriptionGeneration) return@collect
                            val folds = info.displayFeatures.filterIsInstance<FoldingFeature>()
                            foldBounds = folds.take(4).flatMap {
                                listOf(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom)
                            }
                            if (folds.isNotEmpty()) {
                                unsupportedWindow = folds.size > 1 || folds.any {
                                    it.occlusionType == FoldingFeature.OcclusionType.FULL
                                }
                            }
                            windowState = if (folds.size == 1 && !unsupportedWindow) State.OPEN else State.UNKNOWN
                            if (folds.isNotEmpty()) isFoldable = true
                            publish(display.displayId, size, subscriptionGeneration)
                        }
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (e !is Exception && e !is NotImplementedError) throw e
                        Log.w("FoldableUtils", "Window fold information unavailable", e)
                        if (!closed && generation == subscriptionGeneration)
                            Diagnostics.record(DiagnosticEvent.Failure(DiagnosticReason.WINDOW_UNAVAILABLE, e.javaClass.simpleName), generation)
                    }
                }
            }
            publish(display.displayId, size, generation)
        }

        private fun publish(displayId: Int, size: Point, callbackGeneration: Long) {
            if (closed || callbackGeneration != generation || displayId != observedDisplay || size.x != observedWidth || size.y != observedHeight) return
            // Device-global fallbacks must never classify a connected monitor as the inner screen.
            val primaryDisplay = displayId == Display.DEFAULT_DISPLAY
            val featureString = if (primaryDisplay) getFeatureString(ime) else null
            val feature = parseFeatureState(featureString)
            if (primaryDisplay && feature == State.OPEN) isFoldable = true
            val sensor = if (primaryDisplay) sensorState else State.UNKNOWN
            val density = ime.resources.displayMetrics.density
            val now = SystemClock.uptimeMillis()
            val next = reducer.update(FoldStateReducer.Observation(isFoldable, displayId, primaryDisplay,
                minOf(size.x, size.y) / density, ResourceUtils.getAvailableKeyboardWidth(ime) / density,
                windowState, feature, sensor, unsupportedWindow), now).copy(generation = generation)
            Diagnostics.fold(DiagnosticEvent.Fold(windowState, feature, sensor, next.state,
                reducer.pendingPosture, next.canAutomaticallySplit, displayId,
                next.shortestDisplayWidthDp, next.keyboardWidthDp, density, hingeAngle,
                unsupportedWindow, when {
                    featureString == null -> LegacyFeatureStatus.MISSING
                    featureString.isEmpty() -> LegacyFeatureStatus.EMPTY
                    feature == State.OPEN -> LegacyFeatureStatus.VALID
                    else -> LegacyFeatureStatus.INVALID
                }, foldBounds = foldBounds.ifEmpty { featureCoordinates(featureString) }), generation)
            settle?.let(handler::removeCallbacks)
            reducer.nextDeadline?.let {
                val timerGeneration = generation
                val timer = Runnable { if (!closed && generation == timerGeneration) refresh() }
                settle = timer
                handler.postAtTime(timer, it)
            }
            if (next != snapshot) {
                snapshot = next
                onChanged.run()
            }
        }

        fun unregister(context: Context) {
            closed = true
            generation = ++generationCounter
            handler.removeCallbacksAndMessages(null)
            scope.cancel()
            Diagnostics.observerStopped()
            context.contentResolver.unregisterContentObserver(featureObserver)
            sm.unregisterListener(sensorListener)
            snapshot = Snapshot(isFoldable)
        }
    }
}
