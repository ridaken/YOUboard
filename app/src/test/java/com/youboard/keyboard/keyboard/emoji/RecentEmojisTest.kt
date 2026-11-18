// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.keyboard.emoji

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class RecentEmojisTest {
    @Before fun setup() = RecentEmojis.clear()
    @After fun cleanup() = RecentEmojis.clear()
    @Test fun removalPreservesOtherRecents() {
        RecentEmojis.set(listOf("🙂", "💙", "🙂"))
        RecentEmojis.remove("🙂")
        assertEquals(listOf("💙"), RecentEmojis.get())
        RecentEmojis.remove("missing")
        assertEquals(listOf("💙"), RecentEmojis.get())
    }
    @Test fun clearAndReinsert() {
        RecentEmojis.add("💙")
        RecentEmojis.clear()
        assertEquals(emptyList(), RecentEmojis.get())
        RecentEmojis.addCodepoint(0x1f642)
        assertEquals(listOf("🙂"), RecentEmojis.get())
    }
}
