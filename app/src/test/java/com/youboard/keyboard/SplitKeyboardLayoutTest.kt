// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard

import android.view.inputmethod.EditorInfo
import com.youboard.keyboard.keyboard.Key
import com.youboard.keyboard.keyboard.KeyDetector
import com.youboard.keyboard.keyboard.Keyboard
import com.youboard.keyboard.keyboard.KeyboardElement
import com.youboard.keyboard.keyboard.KeyboardId
import com.youboard.keyboard.keyboard.KeyboardLayoutSet
import com.youboard.keyboard.keyboard.internal.KeyboardBuilder
import com.youboard.keyboard.keyboard.internal.KeyboardParams
import com.youboard.keyboard.keyboard.internal.UniqueKeysCache
import com.youboard.keyboard.latin.LatinIME
import com.youboard.keyboard.latin.RichInputMethodSubtype
import com.youboard.keyboard.latin.common.Constants
import com.youboard.keyboard.latin.utils.SubtypeUtilsAdditional
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class])
class SplitKeyboardLayoutTest {
    private val controller = Robolectric.buildService(LatinIME::class.java).create()
    private val ime = controller.get()

    @After fun destroy() { controller.destroy() }

    private fun builder(layout: String = "qwerty", locale: Locale = Locale.ENGLISH,
                        element: KeyboardElement = KeyboardElement.ALPHABET,
                        gap: Float = .25f, split: Boolean = true, numberRow: Boolean = false): KeyboardBuilder<KeyboardParams> {
        val params = KeyboardLayoutSet.Params().apply {
            editorInfo = EditorInfo()
            subtype = RichInputMethodSubtype.get(SubtypeUtilsAdditional.createEmojiCapableAdditionalSubtype(locale, layout, true))
            keyboardWidth = 800
            keyboardHeight = 320
            isSplitLayoutEnabled = split
            splitSpacerRelativeWidth = gap
            numberRowEnabled = numberRow
        }
        return KeyboardBuilder(ime, KeyboardParams(UniqueKeysCache.NO_CACHE)).load(KeyboardId(element, params))
    }

    private fun assertUsable(keyboard: Keyboard) {
        val detector = KeyDetector().apply { setKeyboard(keyboard, 0f, 0f) }
        val rows = keyboard.sortedKeys.groupBy { it.y }
        for (row in rows.values) {
            val real = row.filterNot { it.isSpacer }.sortedBy { it.x }
            real.forEach { key ->
                assertTrue(key.width > 0, "non-positive width: $key")
                assertTrue(key.x >= 0 && key.x + key.width <= keyboard.mOccupiedWidth, "key outside keyboard: $key")
                assertEquals(key.code, detector.detectHitKey(key.x + key.width / 2, key.y + key.height / 2)?.code)
            }
            real.zipWithNext().forEach { (a, b) -> assertTrue(a.x + a.width <= b.x, "overlapping keys") }
            row.filter { it.isSpacer && it.width > 20 && it.x > 0 && it.x + it.width < keyboard.mOccupiedWidth }
                .forEach { assertNull(detector.detectHitKey(it.x + it.width / 2, it.y + it.height / 2), "gap emits a key") }
        }
    }

    @Test fun `qwerty keeps every letter once and both spacebars work`() {
        val keyboard = builder().build()
        val letters = keyboard.sortedKeys.filter { it.code in 'a'.code..'z'.code }.map { it.code }
        assertEquals(('a'..'z').map { it.code }.toSet(), letters.toSet())
        assertEquals(26, letters.size)
        assertEquals(2, keyboard.sortedKeys.count { it.code == Constants.CODE_SPACE })
        assertUsable(keyboard)
    }

    @Test fun `split layouts retain all keys across layouts shifts symbols and gap extremes`() {
        for ((layout, locale) in listOf("qwerty" to Locale.ENGLISH, "dvorak" to Locale.ENGLISH,
            "qwertz+" to Locale.GERMANY, "arabic" to Locale.forLanguageTag("ar"))) {
            for (element in listOf(KeyboardElement.ALPHABET, KeyboardElement.ALPHABET_SHIFT_LOCKED,
                KeyboardElement.SYMBOLS, KeyboardElement.SYMBOLS_SHIFTED)) {
                val original = builder(layout, locale, element, split = false, numberRow = true).build()
                for (gap in listOf(.075f, .35f, .7f)) {
                    val split = builder(layout, locale, element, gap, numberRow = true).build()
                    assertEquals(original.sortedKeys.filterNot { it.isSpacer }.map { it.code }.toSet(),
                        split.sortedKeys.filterNot { it.isSpacer }.map { it.code }.toSet())
                    assertUsable(split)
                }
            }
        }
    }

    @Test fun `empty rows and a space only custom row are safe`() {
        val builder = builder()
        val field = KeyboardBuilder::class.java.getDeclaredField("keysInRows").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rows = field.get(builder) as ArrayList<ArrayList<Key.KeyParams>>
        val space = rows.flatten().first { it.mCode == Constants.CODE_SPACE }
        rows.clear()
        rows.add(arrayListOf())
        rows.add(arrayListOf(space))
        assertUsable(builder.build())
    }

    @Test fun `gap is part of cache identity and unsplit geometry ignores it`() {
        val narrow = builder(gap = .15f).build()
        val wide = builder(gap = .35f).build()
        assertNotEquals(narrow.mId, wide.mId)
        fun geometry(kb: Keyboard) = kb.sortedKeys.map { listOf(it.code, it.x, it.y, it.width, it.height) }
        assertNotEquals(geometry(narrow), geometry(wide))
        assertEquals(geometry(builder(gap = 0f, split = false).build()),
            geometry(builder(gap = .35f, split = false).build()))
    }
}
