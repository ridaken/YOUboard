// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import android.inputmethodservice.InputMethodService
import android.provider.Settings
import com.youboard.keyboard.latin.utils.FoldableUtils.State
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
        assertEquals(State.FOLDED, FoldableUtils.stateFromAngle(39f))
        assertEquals(State.OPEN, FoldableUtils.stateFromAngle(40f))
        assertEquals(State.OPEN, FoldableUtils.stateFromAngle(180f))
    }

    @Test fun `conflicting sources suppress automatic activation`() {
        assertEquals(State.UNKNOWN, FoldableUtils.resolveState(State.OPEN, State.FOLDED, State.UNKNOWN))
        assertEquals(State.UNKNOWN, FoldableUtils.resolveState(State.OPEN, State.UNKNOWN, State.FOLDED))
        assertEquals(State.UNKNOWN, FoldableUtils.resolveState(State.UNKNOWN, State.UNKNOWN, State.UNKNOWN))
        assertEquals(State.OPEN, FoldableUtils.resolveState(State.OPEN, State.UNKNOWN, State.OPEN))
        assertEquals(State.FOLDED, FoldableUtils.resolveState(State.UNKNOWN, State.FOLDED, State.UNKNOWN))
    }

    @Test fun `observer initializes folded and reports changes without rotation`() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putString(app.contentResolver, "display_features", "")
        FoldableUtils.init(app)
        val service = Robolectric.buildService(InputMethodService::class.java).create()
        var changes = 0
        val observer = FoldableUtils.FoldableObserver(service.get()) { changes++ }
        try {
            assertTrue(FoldableUtils.isFolded)
            assertFalse(FoldableUtils.snapshot.canAutomaticallySplit)
            Settings.Global.putString(app.contentResolver, "display_features", "fold-[350,0,350,800]-flat")
            observer.refresh()
            assertEquals(State.OPEN, FoldableUtils.snapshot.state)
            assertTrue(FoldableUtils.snapshot.canAutomaticallySplit)
            val before = changes
            observer.refresh()
            assertEquals(before, changes)
            Settings.Global.putString(app.contentResolver, "display_features", "")
            observer.refresh()
            assertTrue(FoldableUtils.isFolded)
            assertTrue(changes > before)
        } finally {
            observer.unregister(service.get())
            service.destroy()
            Settings.Global.putString(app.contentResolver, "display_features", null)
            FoldableUtils.init(app)
        }
    }
}
