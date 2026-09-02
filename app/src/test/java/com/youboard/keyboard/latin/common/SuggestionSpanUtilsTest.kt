// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.common

import android.text.Spanned
import android.text.style.SuggestionSpan
import androidx.test.core.app.ApplicationProvider
import com.youboard.keyboard.latin.SuggestedWords
import com.youboard.keyboard.latin.SuggestedWords.SuggestedWordInfo
import com.youboard.keyboard.latin.dictionary.Dictionary
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SuggestionSpanUtilsTest {
    @Test
    fun `autocorrect span contains original word and correction flag`() {
        val original = SuggestedWordInfo("hullo", "", 0, SuggestedWordInfo.KIND_TYPED,
            Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        val correction = SuggestedWordInfo("hello", "", 1, SuggestedWordInfo.KIND_CORRECTION,
            Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
        val words = SuggestedWords(arrayListOf(original, correction), null, original,
            false, true, false, SuggestedWords.INPUT_STYLE_TYPING, 0)

        val result = getTextWithSuggestionSpan(
            ApplicationProvider.getApplicationContext(), "hello", words, Locale.ENGLISH, true,
        ) as Spanned
        val span = result.getSpans(0, result.length, SuggestionSpan::class.java).single()

        assertEquals(SuggestionSpan.FLAG_AUTO_CORRECTION, span.flags and SuggestionSpan.FLAG_AUTO_CORRECTION)
        assertContentEquals(arrayOf("hullo"), span.suggestions)
    }
}
