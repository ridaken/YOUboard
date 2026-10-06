// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AlwaysReplaceRule(
    val id: String,
    val replacement: String,
    val triggers: List<String>,
    val respectCapitalization: Boolean = false,
    val enabled: Boolean = true,
)

/** Explicit configuration, separate from dictionary shortcuts and learned corrections. */
object AlwaysReplaceStore {
    const val RULES_KEY = "always_replace_rules"
    const val ENABLED_KEY = "always_replace_enabled"
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class Document(val version: Int = 1, val rules: List<AlwaysReplaceRule>)

    data class State(val rules: List<AlwaysReplaceRule>, val error: Boolean = false)
    enum class Error { EMPTY, MULTILINE, NO_OP, CONFLICT }
    data class ValidationError(val kind: Error, val trigger: String = "", val conflictingTrigger: String = "")

    @JvmStatic
    fun load(prefs: SharedPreferences): State {
        return runCatching {
            val raw = prefs.getString(RULES_KEY, null) ?: return State(emptyList())
            val document = json.decodeFromString<Document>(raw)
            require(document.version == 1)
            require(document.rules.map { it.id }.toSet().size == document.rules.size)
            document.rules.forEach { require(validate(it, document.rules) == null) }
            State(document.rules)
        }.getOrElse { State(emptyList(), error = true) }
    }

    fun save(prefs: SharedPreferences, rules: List<AlwaysReplaceRule>) {
        require(rules.map { it.id }.toSet().size == rules.size)
        rules.forEach { require(validate(it, rules) == null) }
        prefs.edit { putString(RULES_KEY, json.encodeToString(Document(rules = rules))) }
    }

    fun validate(rule: AlwaysReplaceRule, rules: List<AlwaysReplaceRule>): ValidationError? {
        if (rule.id.isBlank() || rule.replacement.isBlank() || rule.triggers.isEmpty()
            || rule.triggers.any { it.isBlank() }) return ValidationError(Error.EMPTY)
        if ((rule.triggers + rule.replacement).any { it.contains('\n') || it.contains('\r') })
            return ValidationError(Error.MULTILINE)
        val triggers = rule.triggers.map { it.trim() }
        for ((index, trigger) in triggers.withIndex()) {
            if (trigger == rule.replacement) return ValidationError(Error.NO_OP, trigger)
            val others = rules.filter { it.id != rule.id }.flatMap { other ->
                other.triggers.map { it to other.respectCapitalization }
            } + triggers.take(index).map { it to rule.respectCapitalization }
            for ((other, respectCase) in others) {
                val ignoreCase = !rule.respectCapitalization || !respectCase
                if (containsAtBoundary(trigger, other, ignoreCase)
                    || containsAtBoundary(other, trigger, ignoreCase))
                    return ValidationError(Error.CONFLICT, trigger, other)
            }
        }
        return null
    }

    private fun containsAtBoundary(text: String, part: String, ignoreCase: Boolean): Boolean {
        if (part.length > text.length) return false
        for (offset in 0..text.length - part.length) {
            if (text.regionMatches(offset, part, 0, part.length, ignoreCase)
                && AlwaysReplaceMatcher.boundaryBefore(text, offset)
                && AlwaysReplaceMatcher.boundaryAfter(text, offset + part.length)) return true
        }
        return false
    }
}

/** No decoder scores, locale dependency, or recursive expansion. UTF-16 offsets match InputConnection. */
class AlwaysReplaceMatcher(rules: List<AlwaysReplaceRule>) {
    private data class Entry(val rule: AlwaysReplaceRule, val trigger: String)
    private val entries = rules.filter { it.enabled }.flatMap { rule ->
        rule.triggers.map { Entry(rule, it.trim()) }
    }
    private val index = entries.groupBy { fold(it.trigger.codePointAt(0)) }
    val maxLength: Int = entries.maxOfOrNull { it.trigger.length } ?: 0

    data class Match(val ruleId: String, val original: String, val replacement: String, val offset: Int)

    fun match(text: String, startsAtBoundary: Boolean = true): Match? {
        for (offset in text.indices) {
            if (!(if (offset == 0) startsAtBoundary else boundaryBefore(text, offset))) continue
            val candidates = index[fold(text.codePointAt(offset))] ?: continue
            for (entry in candidates) {
                if (text.length - offset == entry.trigger.length
                    && text.regionMatches(offset, entry.trigger, 0, entry.trigger.length, !entry.rule.respectCapitalization))
                    return Match(entry.rule.id, text.substring(offset), entry.rule.replacement, offset)
            }
        }
        return null
    }

    fun isPrefix(text: String, startsAtBoundary: Boolean = true): Boolean {
        for (offset in text.indices) {
            if (!(if (offset == 0) startsAtBoundary else boundaryBefore(text, offset))) continue
            val candidates = index[fold(text.codePointAt(offset))] ?: continue
            val length = text.length - offset
            if (candidates.any { length < it.trigger.length && text.regionMatches(
                    offset, it.trigger, 0, length, !it.rule.respectCapitalization) }) return true
        }
        return false
    }

    companion object {
        private fun fold(codePoint: Int): Int = Character.toLowerCase(Character.toUpperCase(codePoint))
        private fun wordCharacter(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint)
            || when (Character.getType(codePoint)) {
                Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(), Character.CONNECTOR_PUNCTUATION.toInt() -> true
                else -> false
            }
            || codePoint == '\''.code || codePoint == '’'.code

        @JvmStatic fun boundaryBefore(text: String, offset: Int): Boolean = offset == 0
            || !wordCharacter(text.codePointBefore(offset))
        @JvmStatic fun boundaryAfter(text: String, offset: Int): Boolean = offset == text.length
            || !wordCharacter(text.codePointAt(offset))
    }
}

/** Tracks only fresh keyboard input. Existing editor text is used solely to verify the suffix. */
class AlwaysReplaceSession {
    private var state = AlwaysReplaceStore.State(emptyList())
    private var enabled = false
    private var matcher = AlwaysReplaceMatcher(emptyList())
    private var start = -1
    private var expectedEnd = -1
    private var text = ""
    private var startsAtBoundary = true
    private var initialBoundary = true
    private var generation = 0
    var pending: Pending? = null
        private set

    data class Pending(val identity: Int, val ruleId: String, val original: String,
                       val replacement: String, val start: Int, val end: Int)

    val readLength: Int get() = matcher.maxLength + 2
    val active: Boolean get() = enabled && matcher.maxLength > 0
    val protectsPrefix: Boolean get() = active && matcher.isPrefix(text, startsAtBoundary)

    fun configure(newState: AlwaysReplaceStore.State, newEnabled: Boolean) {
        if (state == newState && enabled == newEnabled) return
        state = newState
        enabled = newEnabled && !newState.error
        matcher = AlwaysReplaceMatcher(if (enabled) newState.rules else emptyList())
        reset()
    }

    fun reset() {
        start = -1
        expectedEnd = -1
        text = ""
        pending = null
        generation++
    }

    fun beforeInput(cursor: Int, before: CharSequence?) {
        if (!active || cursor < 0 || before == null) { reset(); return }
        if (cursor != expectedEnd || !before.endsWith(text)) reset()
        if (start < 0) {
            start = cursor
            initialBoundary = AlwaysReplaceMatcher.boundaryBefore(before.toString(), before.length)
            expectedEnd = cursor
        }
    }

    fun afterInput(cursor: Int, before: CharSequence?) {
        if (!active || start < 0 || cursor < start || before == null) { reset(); return }
        val length = minOf(cursor - start, matcher.maxLength + 1)
        if (before.length < length) { reset(); return }
        text = before.takeLast(length).toString()
        startsAtBoundary = if (cursor - start <= matcher.maxLength + 1) initialBoundary else false
        expectedEnd = cursor
        generation++
        pending = matcher.match(text, startsAtBoundary)?.let {
            Pending(generation, it.ruleId, it.original, it.replacement, cursor - it.original.length, cursor)
        }
    }

    fun continuesWith(codePoint: Int): Boolean = active && codePoint >= 0
        && matcher.isPrefix(text + String(Character.toChars(codePoint)), startsAtBoundary)

    fun isCurrent(identity: Int): Boolean = pending?.identity == identity
}
