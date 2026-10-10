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
    private val modelField = AdaptiveTouchModel::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousModel: Any? = null

    @Before fun setup() {
        // Robolectric shares this process singleton across otherwise separate app directories.
        previousModel = modelField.get(null)
        modelField.set(null, null)
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
        try {
            ShadowInputMethodService.inputViewShown = true
            controller.destroy()
            AndroidSettings.Global.putString(ime.contentResolver, "display_features", null)
            FoldableUtils.init(ime)
        } finally {
            modelField.set(null, previousModel)
        }
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

    private fun diagnostics() = ShadowLog.getLogsForTag("YOUBoardDiagnostics").map { it.msg }

    @Test fun `finish invalidates queued recovery without editor reads or keyboard replacement`() {
        posture(true)
        val keyboard = switcher.keyboard
        ime.mHandler.postResetCaches(false, 5)
        ime.onFinishInput()
        val reads = ShadowInputMethodService.textReadCount
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(reads, ShadowInputMethodService.textReadCount)
        assertTrue(switcher.keyboard === keyboard)
        assertTrue(diagnostics().any { it.contains("EDITOR_RECOVERY") && it.contains("INVALIDATED") })
        assertTrue(diagnostics().any { it.contains("TOUCH_CANCELLED") && it.contains("INPUT_FINISH") })
    }

    @Test fun `late recovery messages from older input and view generations are rejected`() {
        val field = LatinIME::class.java.getDeclaredField("mEditorRecovery").apply { isAccessible = true }
        val recovery = field.get(ime) as com.youboard.keyboard.latin.utils.EditorRecoveryState
        val messageField = LatinIME.UIHandler::class.java.getDeclaredField("MSG_RESET_CACHES").apply { isAccessible = true }
        val message = messageField.getInt(null)
        val boundaries = listOf<() -> Unit>(
            { ime.onStartInput(ime.currentInputEditorInfo, true) },
            { ime.onFinishInputView(false) },
            { ime.onFinishInput() },
            { ime.onWindowHidden() },
        )
        for (boundary in boundaries) {
            val stale = recovery.generation
            boundary()
            val reads = ShadowInputMethodService.textReadCount
            // Simulate an already delivered old message, independently of queue removal.
            ime.mHandler.sendMessage(ime.mHandler.obtainMessage(message, 0, 5, stale))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(reads, ShadowInputMethodService.textReadCount)
        }
    }

    @Test fun `new editor and geometry transition cancel once`() {
        ShadowInputMethodService.inputViewShown = false
        posture(true)
        val before = cancellations()
        ShadowInputMethodService.inputViewShown = true
        ime.onStartInputView(ime.currentInputEditorInfo, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(switcher.keyboard!!.mId.isSplitLayout)
        assertEquals(before + 1, cancellations())
    }

    @Test fun `hidden geometry preference applies immediately on restarting show`() {
        posture(true)
        val info = ime.currentInputEditorInfo
        ime.onStartInputView(info, false)
        shadowOf(Looper.getMainLooper()).idle()
        val old = switcher.keyboard!!
        ShadowInputMethodService.inputViewShown = false
        ime.onWindowHidden()
        val before = cancellations()
        val key = Settings.PREF_KEY_GAP_SCALE_PREFIX + "_false_false"
        ime.prefs().edit { putFloat(key, 1.8f) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(switcher.keyboard === old)
        ShadowInputMethodService.inputViewShown = true
        ime.onStartInputView(info, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(switcher.keyboard === old)
        assertEquals(1.8f, switcher.keyboard!!.mId.geometrySignature!!.keyGap)
        assertEquals(before + 1, cancellations())
        ime.prefs().edit { remove(key) }
    }

    @Test fun `mode changes with identical geometry remain visible in diagnostics`() {
        val key = Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED
        val keyboard = switcher.keyboard
        val before = cancellations()
        repeat(2) {
            ime.prefs().edit { putBoolean(key, false) }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(diagnostics().last { it.startsWith("GEOMETRY_SKIPPED:") }.contains("\"mode\":\"STANDARD\""))
            ime.prefs().edit { remove(key) }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(diagnostics().last { it.startsWith("GEOMETRY_SKIPPED:") }.contains("\"mode\":\"AUTOMATIC\""))
        }
        assertTrue(switcher.keyboard === keyboard)
        assertEquals(before, cancellations())
    }

    @Test fun `current failed recovery exhausts bounded retries without redundant geometry application`() {
        posture(true)
        val keyboard = switcher.keyboard
        val before = cancellations()
        ShadowInputMethodService.unavailableTextBeforeCursor = true
        val reads = ShadowInputMethodService.textReadCount
        ime.mHandler.postResetCaches(false, 5)
        shadowOf(Looper.getMainLooper()).idle()
        // Six cache attempts plus the existing final cursor-repair fallback read.
        assertEquals(7, ShadowInputMethodService.textReadCount - reads)
        assertEquals(1, diagnostics().count { it.contains("EDITOR_RECOVERY") && it.contains("EXHAUSTED") })
        assertEquals(before, cancellations())
        assertTrue(switcher.keyboard === keyboard)
        assertTrue(diagnostics().any { it.contains("EDITOR_RECOVERY") && it.contains("EXHAUSTED") })
        assertTrue(diagnostics().any { it.contains("EDITOR_READ_FAILED") && it.contains("\"connectionAvailable\":true") })
    }

    @Test fun `null connection failure and hidden input finishes are distinguished`() {
        ShadowInputMethodService.connectionAvailable = false
        ime.mHandler.postResetCaches(false, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(diagnostics().any { it.contains("EDITOR_READ_FAILED") && it.contains("\"connectionAvailable\":false") })
        ShadowInputMethodService.inputViewShown = false
        val before = diagnostics().count { it.startsWith("DISRUPTION:") }
        repeat(10) { ime.onFinishInput() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, diagnostics().count { it.startsWith("DISRUPTION:") })
    }

    @Test fun `showing on already unfolded display records context and stable geometry`() {
        posture(true)
        val info = ime.currentInputEditorInfo
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertTrue(switcher.keyboard!!.mId.isSplitLayout)
        assertTrue(diagnostics().any { it.startsWith("ENVIRONMENT:") && it.contains("physicalWidth") && it.contains("\"fold\"") })
        assertTrue(diagnostics().any { it.contains("INPUT_CALLBACK") && it.contains("viewActivation") })
        val before = cancellations()
        ime.mHandler.postResetCaches(false, 5)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, cancellations())
        tap('a'.code)
        assertEquals("a", ShadowInputMethodService.text)
    }

    @Test fun `lifecycle cancellation records active touch once`() {
        posture(true)
        val key = switcher.keyboard!!.getKey('b'.code)!!
        val x = key.x + key.width / 2f
        val y = key.y + key.height / 2f
        val down = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, x, y, 0)
        switcher.mainKeyboardView.onTouchEvent(down); down.recycle()
        val before = cancellations()
        ime.onFinishInput()
        assertEquals(before + 1, cancellations())
        assertTrue(diagnostics().last { it.startsWith("TOUCH_CANCELLED:") }.contains("\"activeWork\":true"))
    }

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
        ime.onStartInputView(editorInfo, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertFalse(switcher.keyboard!!.mId.isSplitLayout)
        assertEquals(390, switcher.keyboard!!.mId.width)
        assertEquals(text, ShadowInputMethodService.text)
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
