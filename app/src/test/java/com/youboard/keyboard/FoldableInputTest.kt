// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard

import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.MotionEvent
import androidx.core.content.edit
import com.youboard.keyboard.keyboard.KeyboardElement
import com.youboard.keyboard.keyboard.AdaptiveTouchModel
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
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLog
import java.time.Duration
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
        resize(false)
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
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        switcher.reloadMainKeyboard()
    }

    @After fun destroy() {
        ShadowInputMethodService.inputViewShown = true
        controller.destroy()
        AndroidSettings.Global.putString(ime.contentResolver, "display_features", null)
        FoldableUtils.init(ime)
    }

    private fun posture(open: Boolean) {
        resize(open)
        AndroidSettings.Global.putString(ime.contentResolver, "display_features",
            if (open) "fold-[350,0,350,800]-flat" else "")
        ime.onConfigurationChanged(ime.resources.configuration)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
    }

    private fun resize(open: Boolean) {
        val qualifiers = if (open) "w700dp-h800dp-mdpi" else "w390dp-h800dp-mdpi"
        ShadowDisplayManager.changeDisplay(0, qualifiers)
        RuntimeEnvironment.setQualifiers(qualifiers)
    }

    private fun cancellations() = ShadowLog.getLogsForTag("YOUBoardDiagnostics")
        .count { it.msg.startsWith("TOUCH_CANCELLED:") }

    @Test fun `legacy noise during a tap neither swaps keyboard nor drops input`() {
        posture(true)
        tap('a'.code)
        val keyboard = switcher.keyboard!!
        val before = cancellations()
        val initialText = ShadowInputMethodService.text
        repeat(100) { i ->
            val key = switcher.keyboard!!.getKey('b'.code)!!
            val x = key.x + key.width / 2f
            val y = key.y + key.height / 2f
            val time = 100L + i * 200
            val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
            switcher.mainKeyboardView.onTouchEvent(down); down.recycle()
            AndroidSettings.Global.putString(ime.contentResolver, "display_features",
                if (i % 2 == 0) "" else "fold-[350,0,350,800]-flat")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            assertTrue(switcher.keyboard === keyboard)
            val up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, x, y, 0)
            switcher.mainKeyboardView.onTouchEvent(up); up.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180))
        }
        assertEquals(before, cancellations())
        assertEquals(initialText + "b".repeat(100), ShadowInputMethodService.text)
    }

    @Test fun `geometry only preference rebuilds and cancels exactly once`() {
        posture(true)
        val old = switcher.keyboard!!
        val text = ShadowInputMethodService.text
        val before = cancellations()
        ime.prefs().edit { putFloat(Settings.PREF_KEY_GAP_SCALE_PREFIX + "_false_false", 1.8f) }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(old === switcher.keyboard)
        assertEquals(old.mId.width, switcher.keyboard!!.mId.width)
        assertEquals(before + 1, cancellations())
        assertEquals(text, ShadowInputMethodService.text)
        ime.prefs().edit { remove(Settings.PREF_KEY_GAP_SCALE_PREFIX + "_false_false") }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `hidden keyboard defers geometry until input is shown again`() {
        posture(true)
        tap('a'.code)
        val keyboard = switcher.keyboard!!
        val before = cancellations()
        val text = ShadowInputMethodService.text
        ShadowInputMethodService.inputViewShown = false
        resize(false)
        AndroidSettings.Global.putString(ime.contentResolver, "display_features", "")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(FoldableUtils.isFolded)
        assertTrue(switcher.keyboard === keyboard)
        assertEquals(before, cancellations())
        assertEquals(text, ShadowInputMethodService.text)
        ShadowInputMethodService.inputViewShown = true
        val editorInfo = ime.currentInputEditorInfo.apply {
            initialSelStart = ShadowInputMethodService.selectionStart
            initialSelEnd = ShadowInputMethodService.selectionEnd
        }
        // Starting input initializes a process-wide model; do not leak its test app directory.
        val modelField = AdaptiveTouchModel::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousModel = modelField.get(null)
        try {
            ime.onStartInputView(editorInfo, true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertFalse(switcher.keyboard!!.mId.isSplitLayout)
            assertEquals(390, switcher.keyboard!!.mId.width)
            assertEquals(text, ShadowInputMethodService.text)
        } finally {
            modelField.set(null, previousModel)
        }
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
        val selection = ShadowInputMethodService.selectionStart to ShadowInputMethodService.selectionEnd
        posture(true)
        assertTrue(switcher.keyboard!!.mId.isSplitLayout, "${FoldableUtils.snapshot}, prefs=${ime.prefs().all}")
        assertEquals(text, ShadowInputMethodService.text)
        assertEquals(composing, ShadowInputMethodService.composingText)
        assertEquals(selection, ShadowInputMethodService.selectionStart to ShadowInputMethodService.selectionEnd)
        posture(false)
        assertFalse(switcher.keyboard!!.mId.isSplitLayout)
        assertEquals(foldedGeometry, geometry())
        assertEquals(text, ShadowInputMethodService.text)
        assertEquals(selection, ShadowInputMethodService.selectionStart to ShadowInputMethodService.selectionEnd)
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
