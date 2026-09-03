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
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object FoldableUtils {
    enum class State { UNKNOWN, FOLDED, OPEN }

    data class Snapshot(
        val isFoldable: Boolean = false,
        val state: State = State.UNKNOWN,
        val displayId: Int = Display.INVALID_DISPLAY,
        val isInnerDisplay: Boolean = false,
        val shortestDisplayWidthDp: Float = 0f,
        val keyboardWidthDp: Float = 0f,
    ) {
        val canAutomaticallySplit: Boolean get() = isFoldable && state == State.OPEN &&
            isInnerDisplay && shortestDisplayWidthDp >= 600f && keyboardWidthDp >= 600f
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
    val isFolded: Boolean get() = snapshot.state == State.FOLDED

    fun init(context: Context) {
        val feature = parseFeatureState(getFeatureString(context))
        isFoldable = feature != State.UNKNOWN || hasFoldSensor(context)
        snapshot = Snapshot(isFoldable, feature)
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

    internal fun stateFromAngle(angle: Float?): State = when {
        angle == null || !angle.isFinite() || angle !in 0f..180f -> State.UNKNOWN
        angle < 40f -> State.FOLDED
        else -> State.OPEN
    }

    internal fun resolveState(window: State, feature: State, sensor: State): State {
        val known = listOf(window, feature, sensor).filter { it != State.UNKNOWN }
        return if (known.distinct().size == 1) known.first() else State.UNKNOWN
    }

    /** Owned by the IME, including on devices whose only fold signal is WindowManager. */
    class FoldableObserver(private val ime: InputMethodService, private val onChanged: Runnable) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var windowJob: Job? = null
        private var windowState = State.UNKNOWN
        private var sensorState = State.UNKNOWN
        private var unsupportedWindow = false
        private var observedDisplay = Display.INVALID_DISPLAY
        private var observedWidth = 0
        private var observedHeight = 0
        private var closed = false
        private val sm = ime.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val featureObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { refresh() }
        }
        private val sensorListener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) {
                sensorState = stateFromAngle(event.values.firstOrNull())
                refresh()
            }
        }

        init {
            ime.contentResolver.registerContentObserver(displayFeaturesUri, false, featureObserver)
            if (hasFoldSensor(ime)) {
                sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let {
                    sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
                }
            }
            refresh()
        }

        @Suppress("DEPRECATION")
        fun refresh() {
            if (closed) return
            val wm = ime.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            val size = Point().also { display.getRealSize(it) }
            if (observedDisplay != display.displayId || observedWidth != size.x || observedHeight != size.y) {
                if (observedDisplay != display.displayId) sensorState = State.UNKNOWN
                observedDisplay = display.displayId
                observedWidth = size.x
                observedHeight = size.y
                windowState = State.UNKNOWN
                unsupportedWindow = false
                windowJob?.cancel()
                // A display/configuration change invalidates the old window's feature coordinates.
                windowJob = scope.launch {
                    try {
                        WindowInfoTracker.getOrCreate(ime).windowLayoutInfo(ime as Context).collect { info ->
                            val folds = info.displayFeatures.filterIsInstance<FoldingFeature>()
                            unsupportedWindow = folds.size > 1 || folds.any {
                                it.occlusionType == FoldingFeature.OcclusionType.FULL
                            }
                            windowState = if (folds.size == 1 && !unsupportedWindow) State.OPEN else State.UNKNOWN
                            if (folds.isNotEmpty()) isFoldable = true
                            publish(display.displayId, size)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w("FoldableUtils", "Window fold information unavailable", e)
                    }
                }
            }
            publish(display.displayId, size)
        }

        private fun publish(displayId: Int, size: Point) {
            if (closed || displayId != observedDisplay || size.x != observedWidth || size.y != observedHeight) return
            // Device-global fallbacks must never classify a connected monitor as the inner screen.
            val primaryDisplay = displayId == Display.DEFAULT_DISPLAY
            val feature = if (primaryDisplay) parseFeatureState(getFeatureString(ime)) else State.UNKNOWN
            val sensor = if (primaryDisplay) sensorState else State.UNKNOWN
            val state = resolveState(windowState, feature, sensor)
            val density = ime.resources.displayMetrics.density
            val next = Snapshot(isFoldable, state, displayId,
                !unsupportedWindow && state == State.OPEN && (windowState == State.OPEN || primaryDisplay),
                minOf(size.x, size.y) / density, ResourceUtils.getAvailableKeyboardWidth(ime) / density)
            if (next != snapshot) {
                snapshot = next
                onChanged.run()
            }
        }

        fun unregister(context: Context) {
            closed = true
            scope.cancel()
            context.contentResolver.unregisterContentObserver(featureObserver)
            sm.unregisterListener(sensorListener)
            snapshot = Snapshot(isFoldable)
        }
    }
}
