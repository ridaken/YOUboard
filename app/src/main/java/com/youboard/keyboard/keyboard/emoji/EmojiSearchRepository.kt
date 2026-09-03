// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.keyboard.emoji

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/** Offline emoji names and keywords bundled with the keyboard. */
internal object EmojiSearchRepository {
    private val wordSeparator = Regex("[^\\p{L}\\p{N}]+")
    @Serializable
    internal data class Entry(
        val emoji: String,
        val name: String,
        val keywords: List<String>,
        val base: String = emoji,
        val category: String = "",
    ) {
        @Transient
        val searchTokens: List<String> = tokenize(name) + keywords.flatMap(::tokenize)
    }

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var entries: List<Entry>? = null
    @Volatile private var names: Map<String, String> = emptyMap()

    fun load(context: Context): List<Entry> {
        entries?.let { return it }
        return synchronized(this) {
            entries ?: context.assets.open("emoji_search/search-en.json").bufferedReader().use {
                json.decodeFromString<List<Entry>>(it.readText())
            }.also { loaded ->
                entries = loaded
                names = loaded.associate { normalizeEmoji(it.emoji) to it.name }
            }
        }
    }

    fun search(context: Context, query: String): List<Entry> = searchEntries(load(context), query)

    fun description(emoji: String): String? = names[normalizeEmoji(emoji)]

    internal fun searchEntries(source: List<Entry>, query: String): List<Entry> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        return source.asSequence().mapNotNull { entry ->
            if (terms.any { term -> entry.searchTokens.none { it.startsWith(term) } }) return@mapNotNull null
            val exact = terms.count { term -> term in entry.searchTokens }
            entry to exact
        }.sortedWith(compareByDescending<Pair<Entry, Int>> { it.second })
            .map { it.first }
            .distinctBy { normalizeEmoji(it.base) }
            .toList()
    }

    private fun tokenize(value: String): List<String> = value.lowercase()
        .split(wordSeparator)
        .filter(String::isNotBlank)

    internal fun normalizeEmoji(emoji: String) = emoji.replace("\uFE0E", "").replace("\uFE0F", "")
}
