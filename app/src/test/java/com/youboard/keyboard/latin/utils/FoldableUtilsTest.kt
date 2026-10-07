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
