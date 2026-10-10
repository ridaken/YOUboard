// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import android.inputmethodservice.InputMethodService
import android.os.Looper
import android.provider.Settings
import com.youboard.keyboard.latin.utils.FoldableUtils.State
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.FoldingFeature
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.Mockito
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w700dp-h800dp-mdpi")
class FoldableUtilsTest {
    private class HingeSource : FoldableUtils.HingeSignalSource {
        override val available = true
        var succeeds = true
        var unregistered = 0
        val callbacks = mutableListOf<(Float?, Long) -> Unit>()
        override fun register(callback: (Float?, Long) -> Unit): Boolean {
            callbacks.add(callback)
            return succeeds
        }
        override fun unregister() { unregistered++ }
        fun send(angle: Float?, index: Int = callbacks.lastIndex) {
            callbacks[index](angle, android.os.SystemClock.elapsedRealtimeNanos())
        }
    }

    @Test fun `invalid hinge evidence does not erase deadband hysteresis`() {
        val hinge = HingeClassification()
        assertEquals(State.FOLDED, hinge.observe(0f))
        assertEquals(State.UNKNOWN, hinge.observe(null))
        assertEquals(State.FOLDED, hinge.observe(40f))
        assertEquals(State.OPEN, hinge.observe(180f))
        assertEquals(State.UNKNOWN, hinge.observe(Float.NaN))
        assertEquals(State.OPEN, hinge.observe(40f))
    }

    @Test fun `same display resize and show refresh sensor but obsolete callbacks cannot close it`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, "display_features", null)
        FoldableUtils.init(app)
        val controller = Robolectric.buildService(InputMethodService::class.java).create()
        val source = HingeSource()
        val observer = FoldableUtils.FoldableObserver(controller.get(), {}, source) {
            MutableStateFlow(WindowLayoutInfo(emptyList()))
        }
        try {
            source.send(180f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertTrue(FoldableUtils.snapshot.canAutomaticallySplit)
            observer.onInputViewShown()
            assertEquals(2, source.callbacks.size)
            observer.onInputViewShown()
            assertEquals(2, source.callbacks.size)
            ShadowDisplayManager.changeDisplay(0, "w720dp-h820dp-mdpi")
            RuntimeEnvironment.setQualifiers("w720dp-h820dp-mdpi")
            observer.refresh()
            assertEquals(3, source.callbacks.size)
            source.send(0f, 0); source.send(0f, 1)
            source.callbacks.last()(0f, 0L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(State.OPEN, FoldableUtils.snapshot.state)
            source.send(Float.NaN)
            source.send(40f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertEquals(State.OPEN, FoldableUtils.snapshot.state)
            observer.onInputViewHidden(); observer.onInputViewShown()
            assertEquals(4, source.callbacks.size)
            observer.unregister(controller.get())
            source.send(0f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(State.UNKNOWN, FoldableUtils.snapshot.state)
        } finally {
            observer.unregister(controller.get())
            controller.destroy(); FoldableUtils.init(app)
        }
    }

    @Test fun `failed sensor registration is explicit and does not prevent window evidence`() {
        val app = RuntimeEnvironment.getApplication()
        FoldableUtils.init(app)
        val controller = Robolectric.buildService(InputMethodService::class.java).create()
        val source = HingeSource().apply { succeeds = false }
        val observer = FoldableUtils.FoldableObserver(controller.get(), {}, source) {
            MutableStateFlow(foldInfo())
        }
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertTrue(FoldableUtils.snapshot.canAutomaticallySplit)
            assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("YOUBoardDiagnostics")
                .any { it.msg.contains("SENSOR_SUBSCRIPTION") && it.msg.contains("\"registered\":false") })
        } finally { observer.unregister(controller.get()); controller.destroy(); FoldableUtils.init(app) }
    }

    @Test fun `cover to inner display with unchanged identity settles fresh hinge evidence once`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, "display_features", null)
        ShadowDisplayManager.changeDisplay(0, "w390dp-h800dp-mdpi")
        RuntimeEnvironment.setQualifiers("w390dp-h800dp-mdpi")
        FoldableUtils.init(app)
        val controller = Robolectric.buildService(InputMethodService::class.java).create()
        val source = HingeSource()
        val states = mutableListOf<State>()
        val observer = FoldableUtils.FoldableObserver(controller.get(), { states.add(FoldableUtils.snapshot.state) }, source) {
            MutableStateFlow(WindowLayoutInfo(emptyList()))
        }
        try {
            source.send(0f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertEquals(State.FOLDED, FoldableUtils.snapshot.state)
            ShadowDisplayManager.changeDisplay(0, "w700dp-h800dp-mdpi")
            RuntimeEnvironment.setQualifiers("w700dp-h800dp-mdpi")
            observer.refresh()
            assertEquals(State.FOLDED, FoldableUtils.snapshot.state)
            source.send(180f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(149))
            assertFalse(FoldableUtils.snapshot.canAutomaticallySplit)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertTrue(FoldableUtils.snapshot.canAutomaticallySplit)
            source.send(0f, 0)
            repeat(10) { observer.refresh() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(1, states.count { it == State.OPEN })
            assertEquals(State.OPEN, FoldableUtils.snapshot.state)
        } finally { observer.unregister(controller.get()); controller.destroy(); FoldableUtils.init(app) }
    }
    @Test fun `fallback accepts only valid continuous folds`() {
        assertEquals(State.FOLDED, FoldableUtils.parseFeatureState(""))
        assertEquals(State.OPEN, FoldableUtils.parseFeatureState("fold-[350,0,350,800]-flat"))
        assertEquals(State.OPEN, FoldableUtils.parseFeatureState("fold-[350,0,350,800]-half-opened"))
        assertEquals(State.OPEN, FoldableUtils.parseFeatureState("fold-[0,400,700,400]-flat"))
        listOf(null, "invalid", "fold-[0,0,0,0]-flat", "fold-[300,0,350,800]-flat",
            "fold-[350,800,350,0]-flat", "fold-[350,0,350,800]", "hinge-[340,0,360,800]-flat",
            "fold-[350,0,350,800]-flat;fold-[700,0,700,800]-flat", "fold-[999999999999,0,999999999999,800]-flat"
        ).forEach { assertEquals(State.UNKNOWN, FoldableUtils.parseFeatureState(it), it) }
    }

    @Test fun `missing or invalid hinge events never imply open`() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f, 181f).forEach {
            assertEquals(State.UNKNOWN, FoldableUtils.stateFromAngle(it))
        }
        assertEquals(State.FOLDED, FoldableUtils.stateFromAngle(35f))
        assertEquals(State.UNKNOWN, FoldableUtils.stateFromAngle(40f))
        assertEquals(State.OPEN, FoldableUtils.stateFromAngle(45f))
        assertEquals(State.OPEN, FoldableUtils.stateFromAngle(40f, State.OPEN))
        assertEquals(State.FOLDED, FoldableUtils.stateFromAngle(40f, State.FOLDED))
        assertEquals(State.OPEN, FoldableUtils.stateFromAngle(180f))
    }

    @Test fun `trusted sources take priority over conflicting fallback`() {
        assertEquals(State.OPEN, FoldableUtils.resolveState(State.OPEN, State.FOLDED, State.UNKNOWN))
        assertEquals(State.FOLDED, FoldableUtils.resolveState(State.OPEN, State.UNKNOWN, State.FOLDED))
        assertEquals(State.UNKNOWN, FoldableUtils.resolveState(State.UNKNOWN, State.UNKNOWN, State.UNKNOWN))
        assertEquals(State.OPEN, FoldableUtils.resolveState(State.OPEN, State.UNKNOWN, State.OPEN))
        assertEquals(State.FOLDED, FoldableUtils.resolveState(State.UNKNOWN, State.FOLDED, State.UNKNOWN))
    }

    @Test fun `observer settles and ignores empty legacy setting on wide display`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, "display_features", "")
        FoldableUtils.init(app)
        val service = Robolectric.buildService(InputMethodService::class.java).create()
        var changes = 0
        val observer = FoldableUtils.FoldableObserver(service.get()) { changes++ }
        try {
            assertEquals(State.UNKNOWN, FoldableUtils.snapshot.state)
            assertFalse(FoldableUtils.snapshot.canAutomaticallySplit)
            Settings.Global.putString(app.contentResolver, "display_features", "fold-[350,0,350,800]-flat")
            observer.refresh()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(149))
            assertEquals(State.UNKNOWN, FoldableUtils.snapshot.state)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(State.OPEN, FoldableUtils.snapshot.state)
            assertTrue(FoldableUtils.snapshot.canAutomaticallySplit)
            val before = changes
            observer.refresh()
            assertEquals(before, changes)
            Settings.Global.putString(app.contentResolver, "display_features", "")
            observer.refresh()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(State.OPEN, FoldableUtils.snapshot.state)
            assertTrue(FoldableUtils.snapshot.canAutomaticallySplit)
            assertEquals(before, changes)
        } finally {
            observer.unregister(service.get())
            service.destroy()
            Settings.Global.putString(app.contentResolver, "display_features", null)
            FoldableUtils.init(app)
        }
    }

    @Test fun `destroyed observer cannot publish its pending transition`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, "display_features", "fold-[350,0,350,800]-flat")
        FoldableUtils.init(app)
        val service = Robolectric.buildService(InputMethodService::class.java).create()
        val observer = FoldableUtils.FoldableObserver(service.get()) {}
        observer.unregister(service.get())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(State.UNKNOWN, FoldableUtils.snapshot.state)
        service.destroy()
        Settings.Global.putString(app.contentResolver, "display_features", null)
    }

    private fun foldInfo(full: Boolean = false, multiple: Boolean = false): WindowLayoutInfo {
        val fold = Mockito.mock(FoldingFeature::class.java)
        Mockito.`when`(fold.bounds).thenReturn(Rect(350, 0, 350, 800))
        Mockito.`when`(fold.occlusionType).thenReturn(if (full) FoldingFeature.OcclusionType.FULL else FoldingFeature.OcclusionType.NONE)
        return WindowLayoutInfo(if (multiple) listOf(fold, fold) else listOf(fold))
    }

    @Test fun `delayed window source can activate split and unsupported folds disable it immediately`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, "display_features", null)
        FoldableUtils.init(app)
        val service = Robolectric.buildService(InputMethodService::class.java).create()
        val source = MutableStateFlow(WindowLayoutInfo(emptyList()))
        val observer = FoldableUtils.FoldableObserver(service.get(), {}) { source }
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(State.UNKNOWN, FoldableUtils.snapshot.state)
            source.value = foldInfo()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(149))
            assertFalse(FoldableUtils.snapshot.canAutomaticallySplit)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertTrue(FoldableUtils.snapshot.canAutomaticallySplit)
            source.value = foldInfo(full = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(FoldableUtils.snapshot.canAutomaticallySplit)
            source.value = WindowLayoutInfo(emptyList())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertFalse(FoldableUtils.snapshot.canAutomaticallySplit)
            source.value = foldInfo(multiple = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(FoldableUtils.snapshot.canAutomaticallySplit)
        } finally {
            observer.unregister(service.get()); service.destroy()
            FoldableUtils.init(app)
        }
    }

    @Test fun `legacy capability arriving after startup can activate splitting`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, "display_features", null)
        FoldableUtils.init(app)
        val service = Robolectric.buildService(InputMethodService::class.java).create()
        val observer = FoldableUtils.FoldableObserver(service.get(), {}) { MutableStateFlow(WindowLayoutInfo(emptyList())) }
        try {
            Settings.Global.putString(app.contentResolver, "display_features", "fold-[350,0,350,800]-flat")
            observer.refresh()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertTrue(FoldableUtils.snapshot.canAutomaticallySplit)
        } finally {
            observer.unregister(service.get()); service.destroy()
            Settings.Global.putString(app.contentResolver, "display_features", null)
            FoldableUtils.init(app)
        }
    }

    @Test fun `configuration replacement ignores the previous window subscription`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, "display_features", null)
        FoldableUtils.init(app)
        val service = Robolectric.buildService(InputMethodService::class.java).create()
        val sources = mutableListOf<MutableStateFlow<WindowLayoutInfo>>()
        val observer = FoldableUtils.FoldableObserver(service.get(), {}) {
            MutableStateFlow(WindowLayoutInfo(emptyList())).also { sources.add(it) }
        }
        try {
            val original = sources.single()
            ShadowDisplayManager.changeDisplay(0, "w720dp-h820dp-mdpi")
            RuntimeEnvironment.setQualifiers("w720dp-h820dp-mdpi")
            observer.refresh()
            assertEquals(2, sources.size)
            original.value = foldInfo()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(State.UNKNOWN, FoldableUtils.snapshot.state)
            sources.last().value = foldInfo()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertTrue(FoldableUtils.snapshot.canAutomaticallySplit)
        } finally {
            observer.unregister(service.get()); service.destroy()
            FoldableUtils.init(app)
        }
    }
}
