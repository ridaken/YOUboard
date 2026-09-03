// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard

import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.MotionEvent
import androidx.core.content.edit
import com.youboard.keyboard.keyboard.KeyboardElement
import com.youboard.keyboard.keyboard.KeyboardSwitcher
import com.youboard.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import com.youboard.keyboard.latin.LatinIME
import com.youboard.keyboard.latin.settings.Settings
import com.youboard.keyboard.latin.utils.FoldableUtils
import com.youboard.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w700dp-h800dp-mdpi", shadows = [ShadowInputMethodService::class])
class FoldableInputTest {
    private lateinit var controller: ServiceController<LatinIME>
    private lateinit var ime: LatinIME
    private val switcher get() = KeyboardSwitcher.getInstance()
    private var eventTime = 100L

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.prefs().edit {
            app.prefs().all.keys.filter { it.startsWith("split_") || it.startsWith("one_handed") || it.startsWith("floating_") }
                .forEach { remove(it) }
        }
        AndroidSettings.Global.putString(app.contentResolver, "display_features", "")
        FoldableUtils.init(app)
        controller = Robolectric.buildService(LatinIME::class.java).create()
        ime = controller.get()
        switcher.onCreateInputView(ime, true)
        switcher.reloadMainKeyboard()
        ShadowInputMethodService.reset()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun destroy() {
        controller.destroy()
        AndroidSettings.Global.putString(ime.contentResolver, "display_features", null)
        FoldableUtils.init(ime)
    }

    private fun posture(open: Boolean) {
        AndroidSettings.Global.putString(ime.contentResolver, "display_features",
            if (open) "fold-[350,0,350,800]-flat" else "")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tap(code: Int) {
        val key = switcher.keyboard!!.getKey(code)!!
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(eventTime, eventTime + 10, action,
                key.x + key.width / 2f, key.y + key.height / 2f, 0)
            switcher.mainKeyboardView.onTouchEvent(event)
            event.recycle()
            eventTime += 100
        }
    }

    @Test fun `posture callbacks split and restore folded geometry without changing composing text`() {
        fun geometry() = switcher.keyboard!!.sortedKeys.map { listOf(it.code, it.x, it.y, it.width, it.height) }
        val foldedGeometry = geometry()
        assertFalse(switcher.keyboard!!.mId.isSplitLayout)
        tap('a'.code)
        val text = ShadowInputMethodService.text
        val composing = ShadowInputMethodService.composingText
        posture(true)
        assertTrue(switcher.keyboard!!.mId.isSplitLayout, "${FoldableUtils.snapshot}, prefs=${ime.prefs().all}")
        assertEquals(text, ShadowInputMethodService.text)
        assertEquals(composing, ShadowInputMethodService.composingText)
        posture(false)
        assertFalse(switcher.keyboard!!.mId.isSplitLayout)
        assertEquals(foldedGeometry, geometry())
        assertEquals(text, ShadowInputMethodService.text)
        assertFalse(ime.prefs().contains(Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED))
    }

    @Test fun `one handed mode pauses automatic split and restores it on exit`() {
        posture(true)
        assertTrue(Settings.getValues().mIsSplitKeyboardEnabled, "${FoldableUtils.snapshot}, prefs=${ime.prefs().all}")
        switcher.setOneHandedModeEnabled(true, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Settings.getValues().mOneHandedModeEnabled)
        assertFalse(switcher.keyboard!!.mId.isSplitLayout)
        switcher.setOneHandedModeEnabled(false, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Settings.getValues().mOneHandedModeEnabled)
        assertTrue(switcher.keyboard!!.mId.isSplitLayout)
        assertFalse(ime.prefs().contains(Settings.PREF_ENABLE_SPLIT_KEYBOARD))
    }

    @Test fun `symbol state survives folding and manual split changes`() {
        tap(KeyCode.SYMBOL_ALPHA)
        assertEquals(KeyboardElement.SYMBOLS, switcher.keyboard!!.mId.element)
        posture(true)
        assertEquals(KeyboardElement.SYMBOLS, switcher.keyboard!!.mId.element)
        switcher.toggleSplitKeyboardMode()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(switcher.keyboard!!.mId.isSplitLayout)
        assertEquals(KeyboardElement.SYMBOLS, switcher.keyboard!!.mId.element)
        posture(false)
        posture(true)
        assertFalse(switcher.keyboard!!.mId.isSplitLayout)
    }
}
