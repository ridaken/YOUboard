// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.youboard.keyboard.latin.common.WordDeletion
import com.youboard.keyboard.latin.settings.SpacingAndPunctuations
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class WordDeletionTest {
    private val spacing get() = SpacingAndPunctuations(ApplicationProvider.getApplicationContext<Context>().resources, false)
    @Test fun wordsConnectorsAndWhitespace() {
        assertEquals(5, WordDeletion.lengthBeforeCursor("hello world", Locale.ENGLISH, spacing))
        assertEquals(2, WordDeletion.lengthBeforeCursor("hello  ", Locale.ENGLISH, spacing))
        assertEquals(5, WordDeletion.lengthBeforeCursor("don't", Locale.ENGLISH, spacing))
        assertEquals(9, WordDeletion.lengthBeforeCursor("hell-bent", Locale.ENGLISH, spacing))
        assertEquals(-6, WordDeletion.steps("hello world", -2, Locale.ENGLISH, spacing))
    }
    @Test fun combiningAndEmojiStayWhole() {
        assertEquals(2, WordDeletion.lengthBeforeCursor("é", Locale.ENGLISH, spacing))
        assertEquals("👨‍👩‍👧".length, WordDeletion.lengthBeforeCursor("hello 👨‍👩‍👧", Locale.ENGLISH, spacing))
        assertEquals(0, WordDeletion.steps("", -1, Locale.ENGLISH, spacing))
    }
}
