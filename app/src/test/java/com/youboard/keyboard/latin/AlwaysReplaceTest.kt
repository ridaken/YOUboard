// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AlwaysReplaceTest {
    private fun rule(vararg triggers: String, output: String = "you", case: Boolean = false,
                     id: String = "rule", enabled: Boolean = true) =
        AlwaysReplaceRule(id, output, triggers.toList(), case, enabled)

    @Test fun `many inputs use saved output capitalization`() {
        val matcher = AlwaysReplaceMatcher(listOf(rule("ypu", "yuo", "yoi")))
        for (input in listOf("ypu", "Ypu", "YPU", "yuo", "yoi")) {
            assertEquals("you", matcher.match(input)?.replacement)
            assertEquals(input, matcher.match(input)?.original)
        }
    }

    @Test fun `case-sensitive rules distinguish input capitalization`() {
        val matcher = AlwaysReplaceMatcher(listOf(rule("ypu", case = true)))
        assertNotNull(matcher.match("ypu"))
        assertNull(matcher.match("Ypu"))
        val upper = rule("YPU", case = true, id = "upper")
        assertNull(AlwaysReplaceStore.validate(upper, listOf(rule("ypu", case = true), upper)))
    }

    @Test fun `complete tokens Unicode and literal phrase spacing`() {
        val matcher = AlwaysReplaceMatcher(listOf(rule("ypu", "déja vu", "teh cat", "a.b")))
        assertNull(matcher.match("xypu"))
        assertNull(matcher.match("ypu2"))
        assertNull(matcher.match("_ypu"))
        assertNull(matcher.match("'ypu"))
        assertNull(matcher.match("ypu", false))
        assertNotNull(matcher.match("hello, Ypu"))
        assertNotNull(matcher.match("DÉJA VU"))
        assertNotNull(matcher.match("teh cat"))
        assertNotNull(matcher.match("a.b"))
        assertNull(matcher.match("teh  cat"))
        val unicode = AlwaysReplaceMatcher(listOf(rule("σ", "i")))
        assertNotNull(unicode.match("ς"))
        assertNotNull(unicode.match("İ"))
        assertNotNull(unicode.match("ı"))
    }

    @Test fun `phrase prefix protects only possible matches`() {
        val matcher = AlwaysReplaceMatcher(listOf(rule("teh cat")))
        assertTrue(matcher.isPrefix("hello teh "))
        assertTrue(matcher.isPrefix("teh c"))
        assertFalse(matcher.isPrefix("teh dog"))
        assertFalse(matcher.isPrefix("teh cat"))
    }

    @Test fun `conflicts include contained triggers disabled rules and ignore case`() {
        val existing = rule("new york", id = "existing", enabled = false)
        for (trigger in listOf("new", "york", "NEW YORK")) {
            val candidate = rule(trigger, id = "candidate", case = true)
            val error = AlwaysReplaceStore.validate(candidate, listOf(existing, candidate))
            assertEquals(AlwaysReplaceStore.Error.CONFLICT, error?.kind)
            assertEquals("new york", error?.conflictingTrigger)
        }
        assertNull(AlwaysReplaceStore.validate(rule("newspaper"), listOf(existing)))
    }

    @Test fun `duplicate grouped inputs empty and multiline entries are rejected`() {
        assertEquals(AlwaysReplaceStore.Error.CONFLICT,
            AlwaysReplaceStore.validate(rule("ypu", "YPU"), emptyList())?.kind)
        assertEquals(AlwaysReplaceStore.Error.EMPTY,
            AlwaysReplaceStore.validate(rule(" "), emptyList())?.kind)
        assertEquals(AlwaysReplaceStore.Error.MULTILINE,
            AlwaysReplaceStore.validate(rule("ypu\nx"), emptyList())?.kind)
        assertEquals(AlwaysReplaceStore.Error.MULTILINE,
            AlwaysReplaceStore.validate(rule("ypu", output = "you\nx"), emptyList())?.kind)
        assertEquals(AlwaysReplaceStore.Error.NO_OP,
            AlwaysReplaceStore.validate(rule("you"), emptyList())?.kind)
    }

    @Test fun `configuration survives preference backup format and malformed data fails closed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("always-replace-test", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        assertEquals(emptyList(), AlwaysReplaceStore.load(prefs).rules)
        val rules = listOf(rule("ypu", "yuo"))
        AlwaysReplaceStore.save(prefs, rules)
        val serialized = prefs.getString(AlwaysReplaceStore.RULES_KEY, null)
        assertTrue(serialized!!.contains("\"version\":1"))
        val restored = context.getSharedPreferences("always-replace-restored", Context.MODE_PRIVATE)
        restored.edit { clear(); putString(AlwaysReplaceStore.RULES_KEY, serialized) }
        assertEquals(rules, AlwaysReplaceStore.load(restored).rules)
        for (invalid in listOf("broken", "{\"version\":2,\"rules\":[]}")) {
            prefs.edit { putString(AlwaysReplaceStore.RULES_KEY, invalid) }
            assertTrue(AlwaysReplaceStore.load(prefs).error)
            assertEquals(emptyList(), AlwaysReplaceStore.load(prefs).rules)
        }
    }

    @Test fun `session tracks phrases across boundaries without matching existing editor text`() {
        val session = AlwaysReplaceSession()
        session.configure(AlwaysReplaceStore.State(listOf(rule("teh cat"))), true)
        session.beforeInput(7, "old ypu")
        session.afterInput(8, "old ypu ")
        session.beforeInput(8, "old ypu ")
        session.afterInput(11, "old ypu teh")
        assertTrue(session.protectsPrefix)
        session.beforeInput(11, "old ypu teh")
        session.afterInput(15, "old ypu teh cat")
        assertEquals("teh cat", session.pending?.original)
        assertEquals(8, session.pending?.start)
    }

    @Test fun `session bounds memory resets on external edits and invalidates old identities`() {
        val session = AlwaysReplaceSession()
        session.configure(AlwaysReplaceStore.State(listOf(rule("ypu"))), true)
        session.beforeInput(0, "")
        session.afterInput(1004, "x".repeat(1000) + " ypu")
        val pending = assertNotNull(session.pending)
        assertEquals(1001, pending.start)
        assertTrue(session.isCurrent(pending.identity))
        session.beforeInput(1004, "changed text")
        assertNull(session.pending)
        assertFalse(session.isCurrent(pending.identity))
        session.configure(AlwaysReplaceStore.State(listOf(rule("ypu"))), false)
        assertFalse(session.active)
    }
}
