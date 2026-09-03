// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.keyboard.emoji

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.youboard.keyboard.latin.settings.Settings
import com.youboard.keyboard.latin.utils.prefs
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EmojiSearchRepositoryTest {
    private val entries = listOf(
        EmojiSearchRepository.Entry("😀", "grinning face", listOf("happy", "smile", "smiling")),
        EmojiSearchRepository.Entry("😊", "smiling face with smiling eyes", listOf("blush", "happy", "smile")),
        EmojiSearchRepository.Entry("💙", "blue heart", listOf("blue", "heart", "love")),
        EmojiSearchRepository.Entry("❤️", "red heart", listOf("heart", "love")),
    )

    @Test fun `search is case insensitive and supports prefixes`() {
        assertEquals(listOf("😀", "😊"), search("SMIL").map { it.emoji })
    }

    @Test fun `all query words must match`() {
        assertEquals(listOf("💙"), search("heart blue").map { it.emoji })
    }

    @Test fun `exact matches rank before prefixes`() {
        val source = entries + EmojiSearchRepository.Entry("😁", "grin", listOf("grin"))
        assertEquals(listOf("😁", "😀"), EmojiSearchRepository.searchEntries(source, "grin").map { it.emoji })
        assertTrue(search("unknown").isEmpty())
    }

    @Test fun `variation selectors are ignored for deduplication`() {
        val duplicate = entries + EmojiSearchRepository.Entry("❤", "heart", listOf("heart"))
        val result = EmojiSearchRepository.searchEntries(duplicate, "heart")
        assertEquals(1, result.count { EmojiSearchRepository.normalizeEmoji(it.emoji) == "❤" })
    }

    @Test fun `bundled index covers representative searches and broad results`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val records = EmojiSearchRepository.load(context)

        assertEquals(3704, records.size)
        assertTrue(EmojiSearchRepository.searchEntries(records, "smile").size > 18)
        assertEquals(
            listOf("smile", "smiling", "happy", "SMILE", "  smile  ").map {
                EmojiSearchRepository.searchEntries(records, it).isNotEmpty()
            },
            List(5) { true },
        )
        assertEquals("💙", EmojiSearchRepository.searchEntries(records, "blue heart").first().emoji)
        assertTrue(EmojiSearchRepository.searchEntries(records, "no-such-emoji-term").isEmpty())
    }

    @Test fun `search input options tolerate unrelated or malformed editor values`() {
        val prefix = "com.youboard.keyboard.keyboard.emoji.search"
        listOf("unrelated", prefix, "$prefix.invalid,", "$prefix.-1,").forEach { value ->
            val editor = EditorInfo().apply { privateImeOptions = value }
            assertEquals(0, EmojiSearchActivity.decodePrivateImeOptions(editor).height)
        }
        val editor = EditorInfo().apply { privateImeOptions = "$prefix.120," }
        assertEquals(120, EmojiSearchActivity.decodePrivateImeOptions(editor).height)
    }

    private fun search(query: String) = EmojiSearchRepository.searchEntries(entries, query)

    @Test fun `initial results read the current recents format with complete sequences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.init(context)
        val recent = listOf("👋🏾", "👨‍👩‍👧‍👦", "💙")
        context.prefs().edit {
            putString(Settings.PREF_RECENT_EMOJIS, Json.encodeToString(recent))
            putInt(Settings.PREF_EMOJI_MAX_SDK, Int.MAX_VALUE)
        }
        SupportedEmojis.load(context)

        assertEquals(recent, EmojiSearchRepository.initialResults(context))
    }

    @Test fun `initial results filter unsupported recents and fall back to smileys`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.init(context)
        context.prefs().edit { putInt(Settings.PREF_EMOJI_MAX_SDK, 22) }
        SupportedEmojis.load(context)
        RecentEmojis.set(listOf("🙃", "💙"))
        assertEquals(listOf("💙"), EmojiSearchRepository.initialResults(context))

        RecentEmojis.set(listOf("🙃"))
        val fallback = EmojiSearchRepository.initialResults(context)
        assertEquals("😀", fallback.first())
        assertTrue(fallback.none(SupportedEmojis::isUnsupported))
        RecentEmojis.set(emptyList())
        assertEquals(fallback, EmojiSearchRepository.initialResults(context))
    }
}
