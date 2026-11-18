// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.common

import com.youboard.keyboard.latin.settings.SpacingAndPunctuations
import java.util.Locale

/** Word boundaries are used only by explicit word deletion, never ordinary backspace or recorrection. */
object WordDeletion {
    @JvmStatic fun lengthBeforeCursor(text: CharSequence?, locale: Locale, spacing: SpacingAndPunctuations): Int =
        if (text.isNullOrEmpty()) 1 else -steps(text, -1, locale, spacing)

    @JvmStatic fun steps(text: CharSequence, steps: Int, locale: Locale, spacing: SpacingAndPunctuations): Int {
        if (steps == 0 || text.isEmpty()) return 0
        val iterator = WordBreakIterator.getForSpacingAndPunctuations(spacing, locale)
        iterator.text = text
        var position = if (steps < 0) text.length else 0
        repeat(kotlin.math.abs(steps)) {
            val next = if (steps < 0) iterator.preceding(position) else iterator.following(position)
            position = if (next < 0) { if (steps < 0) 0 else text.length } else next
        }
        return if (steps < 0) position - text.length else position
    }
}
