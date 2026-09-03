// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.settings

import android.content.Context
import androidx.core.content.edit
import com.youboard.keyboard.latin.settings.SplitKeyboardSettings.Mode
import com.youboard.keyboard.latin.utils.FoldableUtils.Snapshot
import com.youboard.keyboard.latin.utils.FoldableUtils.State
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SplitKeyboardSettingsTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("split-test", Context.MODE_PRIVATE)
    private val inner = Snapshot(true, State.OPEN, 0, true, 700f, 700f)

    @Before fun clear() { prefs.edit { clear() } }

    private fun resolve(landscape: Boolean = false, folded: Boolean = false, environment: Snapshot = inner,
                        floating: Boolean = false, oneHanded: Boolean = false) =
        SplitKeyboardSettings.resolve(prefs, landscape, folded, environment, floating, oneHanded)

    @Test fun `fresh wide inner screens split in either orientation without writing preferences`() {
        assertTrue(resolve())
        assertTrue(resolve(landscape = true))
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun `only confirmed wide inner screens qualify`() {
        listOf(inner.copy(isFoldable = false), inner.copy(state = State.UNKNOWN),
            inner.copy(state = State.FOLDED), inner.copy(isInnerDisplay = false),
            inner.copy(shortestDisplayWidthDp = 599f), inner.copy(keyboardWidthDp = 599f)
        ).forEach { assertFalse(resolve(environment = it), it.toString()) }
        assertTrue(resolve(environment = inner.copy(shortestDisplayWidthDp = 600f, keyboardWidthDp = 600f)))
        assertTrue(resolve(environment = inner.copy(shortestDisplayWidthDp = 601f, keyboardWidthDp = 601f)))
        // A landscape flip or cover screen is wide, but its short dimension remains compact.
        assertFalse(resolve(landscape = true, environment = inner.copy(shortestDisplayWidthDp = 390f, keyboardWidthDp = 840f)))
    }

    @Test fun `automatic splitting pauses for floating and one handed modes`() {
        assertFalse(resolve(floating = true))
        assertFalse(resolve(oneHanded = true))
        assertTrue(resolve())
    }

    @Test fun `explicit choices retain their semantics in all four profiles`() {
        for (landscape in listOf(false, true)) for (folded in listOf(false, true)) {
            val key = SplitKeyboardSettings.key(landscape, folded)
            prefs.edit { putBoolean(key, false) }
            assertFalse(resolve(landscape, folded))
            prefs.edit { putBoolean(key, true) }
            assertTrue(resolve(landscape, folded, Snapshot(), floating = true, oneHanded = true))
        }
    }

    @Test fun `folded defaults stay off and unfolded edits never touch folded preferences`() {
        assertFalse(resolve(folded = true))
        assertFalse(resolve(landscape = true, folded = true))
        val foldedKey = SplitKeyboardSettings.key(false, true)
        prefs.edit { putBoolean(foldedKey, true); putFloat("unrelated_scale", 1.4f) }
        val before = prefs.all.toMap()
        val portrait = SplitKeyboardSettings.key(false, false)
        SplitKeyboardSettings.write(prefs, portrait, Mode.STANDARD)
        assertFalse(resolve())
        assertTrue(resolve(landscape = true))
        SplitKeyboardSettings.write(prefs, portrait, Mode.AUTOMATIC)
        assertEquals(before, prefs.all)
        assertTrue(resolve())
    }

    @Test fun `saved opt out and automatic mode survive rereading`() {
        val key = SplitKeyboardSettings.key(false, false)
        SplitKeyboardSettings.write(prefs, key, Mode.STANDARD)
        val reread = RuntimeEnvironment.getApplication().getSharedPreferences("split-test", Context.MODE_PRIVATE)
        assertEquals(Mode.STANDARD, SplitKeyboardSettings.mode(reread, key))
        assertFalse(resolve())
        SplitKeyboardSettings.write(prefs, key, Mode.AUTOMATIC)
        assertFalse(prefs.contains(key))
        assertEquals(Mode.AUTOMATIC, SplitKeyboardSettings.mode(reread, key))
    }

    @Test fun `automatic gap controls are available while folded only on foldables`() {
        assertTrue(SplitKeyboardSettings.hasEnabledProfile(prefs, true))
        assertFalse(SplitKeyboardSettings.hasEnabledProfile(prefs, false))
        for (landscape in listOf(false, true))
            SplitKeyboardSettings.write(prefs, SplitKeyboardSettings.key(landscape, false), Mode.STANDARD)
        assertFalse(SplitKeyboardSettings.hasEnabledProfile(prefs, true))
    }
}
