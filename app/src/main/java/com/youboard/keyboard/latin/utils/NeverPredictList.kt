// SPDX-License-Identifier: GPL-3.0-only

package com.youboard.keyboard.latin.utils

import android.content.SharedPreferences
import androidx.core.content.edit
import com.youboard.keyboard.latin.common.Constants.Separators
import com.youboard.keyboard.latin.settings.Defaults
import com.youboard.keyboard.latin.settings.Settings

/**
 * Global "never predict / correct" word list.
 *
 * Words are stored lower-cased and matched case-insensitively. A listed word is *demoted*
 * during suggestion ranking (see [com.youboard.keyboard.latin.Suggest]): it is never the
 * auto-correct target and never auto-commits on space, but it still appears in the
 * suggestion strip and can be committed if the user deliberately taps it.
 *
 * The list is global (applies to every language/subtype) and shared between the settings
 * screen and the long-press "never predict" action, so parsing lives here as the single
 * source of truth (also read from [com.youboard.keyboard.latin.settings.SettingsValues]).
 */
object NeverPredictList {
    @JvmStatic
    fun getWords(prefs: SharedPreferences): Set<String> =
        prefs.getString(Settings.PREF_NEVER_PREDICT_LIST, Defaults.PREF_NEVER_PREDICT_LIST)!!
            .split(Separators.SETS)
            .filter { it.isNotBlank() }
            .toHashSet()

    fun addWord(prefs: SharedPreferences, word: String) {
        val normalized = normalize(word) ?: return
        val words = getWords(prefs)
        if (normalized in words) return
        writeWords(prefs, words + normalized)
    }

    fun removeWord(prefs: SharedPreferences, word: String) {
        val normalized = normalize(word) ?: return
        val words = getWords(prefs)
        if (normalized !in words) return
        writeWords(prefs, words - normalized)
    }

    private fun writeWords(prefs: SharedPreferences, words: Collection<String>) {
        prefs.edit { putString(Settings.PREF_NEVER_PREDICT_LIST, words.toSortedSet().joinToString(Separators.SETS)) }
    }

    /** lower-case, trim, and strip the list separator so a word can't corrupt the stored list */
    private fun normalize(word: String): String? =
        word.trim().replace(Separators.SETS, "").lowercase().ifBlank { null }
}
