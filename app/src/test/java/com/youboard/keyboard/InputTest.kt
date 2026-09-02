package com.youboard.keyboard

import android.view.MotionEvent
import com.youboard.keyboard.keyboard.AdaptiveTouchModel
import com.youboard.keyboard.keyboard.KeyDetector
import com.youboard.keyboard.keyboard.KeyboardElement
import com.youboard.keyboard.keyboard.KeyboardSwitcher
import com.youboard.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import com.youboard.keyboard.latin.LatinIME
import com.youboard.keyboard.latin.common.InputPointers
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// todo: expand with swipe & touch stuff
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class InputTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val keyboardSwitcher = KeyboardSwitcher.getInstance()
    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
        // create keyboardView
        keyboardSwitcher.onCreateInputView(latinIME, true)
        // create and set keyboard
        keyboardSwitcher.reloadMainKeyboard()
    }

    @Test fun pressShift() {
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        assertEquals(KeyboardElement.ALPHABET_MANUAL_SHIFTED, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun keyInput() {
        touchKey('a'.code, MotionEvent.ACTION_DOWN)
        touchKey('a'.code, MotionEvent.ACTION_UP)
        assertEquals("a", ShadowInputMethodService.text)
    }

    @Test fun adaptiveTouchCorrectionRecoversIntendedNeighborKey() {
        val keyboard = keyboardSwitcher.keyboard!!
        val intendedKey = keyboard.getKey('a'.code)!!
        val model = AdaptiveTouchModel.inMemory().apply {
            configure(true, 0, false, 0)
        }
        repeat(AdaptiveTouchModel.MIN_CLUSTER_SAMPLES) {
            val pointers = InputPointers(1).apply {
                addPointer(
                    intendedKey.x + intendedKey.width + 1,
                    intendedKey.y + intendedKey.height / 2,
                    0,
                    it,
                )
            }
            model.learnAligned(keyboard, pointers, "a")
        }

        val detectorField = keyboardSwitcher.mainKeyboardView.javaClass
            .getDeclaredField("mKeyDetector").apply { isAccessible = true }
        (detectorField.get(keyboardSwitcher.mainKeyboardView) as KeyDetector)
            .setAdaptiveTouchModel(model)

        touchAt(
            intendedKey.x + intendedKey.width + 1,
            intendedKey.y + intendedKey.height / 2,
            MotionEvent.ACTION_DOWN,
        )
        touchAt(
            intendedKey.x + intendedKey.width + 1,
            intendedKey.y + intendedKey.height / 2,
            MotionEvent.ACTION_UP,
        )

        assertEquals("a", ShadowInputMethodService.text)
    }

    @Test fun holdShift() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        assertEquals(KeyboardElement.ALPHABET_MANUAL_SHIFTED, keyboardSwitcher.keyboard?.mId?.element)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks() // this doesn't actually wait, just results in handling the delayed message immediately
        assertEquals(KeyboardElement.ALPHABET_SHIFT_LOCKED, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun slidingInput() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        touchKey('F'.code, MotionEvent.ACTION_MOVE)
        touchKey('F'.code, MotionEvent.ACTION_UP)
        assertEquals("F", ShadowInputMethodService.text)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun endCapsLockWithShift() {
        // keyboardSwitcher.setAlphabetShiftLockedKeyboard() isn't enough because it doesn't set the fully correct state
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP)

        // need to set event time to prevent mTouchNoiseThresholdTime thing triggering (todo: should be done automatically in the test)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN, 50L)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP, 50L)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun slidingInputFromCapsLock() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP)

        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN, 50L)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
        touchKey('f'.code, MotionEvent.ACTION_MOVE)
        touchKey('f'.code, MotionEvent.ACTION_UP)
        assertEquals("f", ShadowInputMethodService.text)
        assertEquals(KeyboardElement.ALPHABET_SHIFT_LOCKED, keyboardSwitcher.keyboard?.mId?.element)
    }

    private fun touchKey(code: Int, action: Int, eventTime: Long = 0L) {
        val kb = keyboardSwitcher.keyboard!!
        val key = kb.getKey(code)!!
        val x = key.x + key.width / 2
        val y = key.y + key.height / 2
        touchAt(x, y, action, eventTime)
    }

    private fun touchAt(x: Int, y: Int, action: Int, eventTime: Long = 0L) {
        val me = MotionEvent.obtain( // todo: there are many more parameters, also related to pointers
            0L,
            eventTime,
            action,
            x.toFloat(),
            y.toFloat(),
            0
        )
        keyboardSwitcher.mainKeyboardView.onTouchEvent(me)
    }

    @BeforeTest
    fun reset() {
        keyboardSwitcher.reloadMainKeyboard()
        ShadowInputMethodService.reset()
    }
}
