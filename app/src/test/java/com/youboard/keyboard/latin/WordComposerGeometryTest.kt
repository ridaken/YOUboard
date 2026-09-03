// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin

import com.youboard.keyboard.event.Event
import com.youboard.keyboard.latin.common.Constants
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class WordComposerGeometryTest {
    @Test fun `completed glide words keep their text and discard their path`() {
        val composer = WordComposer()
        composer.setBatchInputWord("hello")
        composer.inputPointers.addPointer(400, 250, 0, 10)
        composer.invalidateTouchCoordinates()
        assertEquals("hello", composer.typedWord)
        assertFalse(composer.isBatchMode)
        assertEquals(5, composer.inputPointers.pointerSize)
        assertEquals(Constants.NOT_A_COORDINATE, composer.inputPointers.xCoordinates[0])
    }
    @Test fun `layout changes invalidate coordinates without clearing composing text`() {
        val composer = WordComposer()
        "hello".forEach {
            composer.applyProcessedEvent(composer.processEvent(Event.createEventForCodePointFromUnknownSource(it.code)))
        }
        composer.inputPointers.addPointerAt(0, 120, 80, 0, 0)
        val pointerCount = composer.inputPointers.pointerSize
        composer.invalidateTouchCoordinates()
        assertEquals("hello", composer.typedWord)
        assertEquals(pointerCount, composer.inputPointers.pointerSize)
        repeat(pointerCount) {
            assertEquals(Constants.NOT_A_COORDINATE, composer.inputPointers.xCoordinates[it])
            assertEquals(Constants.NOT_A_COORDINATE, composer.inputPointers.yCoordinates[it])
        }
    }
}
