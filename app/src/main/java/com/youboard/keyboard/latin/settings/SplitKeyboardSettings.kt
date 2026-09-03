// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.youboard.keyboard.latin.utils.FoldableUtils

/** Missing preferences follow the device; existing booleans remain explicit user choices. */
object SplitKeyboardSettings {
    enum class Mode { AUTOMATIC, SPLIT, STANDARD }

    @JvmStatic
    fun key(landscape: Boolean, folded: Boolean): String = when {
        folded && landscape -> Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE
        folded -> Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED
        landscape -> Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE
        else -> Settings.PREF_ENABLE_SPLIT_KEYBOARD
    }

    @JvmStatic
    fun mode(prefs: SharedPreferences, key: String): Mode = when {
        !prefs.contains(key) -> Mode.AUTOMATIC
        prefs.getBoolean(key, false) -> Mode.SPLIT
        else -> Mode.STANDARD
    }

    fun write(prefs: SharedPreferences, key: String, mode: Mode) = prefs.edit {
        when (mode) {
            Mode.AUTOMATIC -> remove(key)
            Mode.SPLIT -> putBoolean(key, true)
            Mode.STANDARD -> putBoolean(key, false)
        }
    }

    @JvmStatic
    fun resolve(prefs: SharedPreferences, landscape: Boolean, folded: Boolean,
                environment: FoldableUtils.Snapshot, floating: Boolean, oneHanded: Boolean): Boolean =
        when (mode(prefs, key(landscape, folded))) {
            Mode.SPLIT -> true
            Mode.STANDARD -> false
            Mode.AUTOMATIC -> !folded && !floating && !oneHanded && environment.canAutomaticallySplit
        }

    /** Describes configured profiles, including Automatic while the device is folded. */
    fun hasEnabledProfile(prefs: SharedPreferences, foldable: Boolean): Boolean =
        listOf(false, true).any { landscape ->
            mode(prefs, key(landscape, false)).let {
                it == Mode.SPLIT || (foldable && it == Mode.AUTOMATIC)
            } || mode(prefs, key(landscape, true)) == Mode.SPLIT
        }

    @JvmStatic
    fun affectsGeometry(key: String?): Boolean = key == null ||
        key.startsWith(Settings.PREF_ENABLE_SPLIT_KEYBOARD) ||
        key.startsWith(Settings.PREF_SPLIT_SPACER_SCALE_PREFIX) ||
        key.startsWith(Settings.PREF_ONE_HANDED_MODE_PREFIX) ||
        key.startsWith(Settings.PREF_FLOATING_ENABLED_PREFIX) ||
        key.startsWith(Settings.PREF_FLOATING_WIDTH_PREFIX) ||
        key.startsWith(Settings.PREF_FLOATING_HEIGHT_PREFIX)
}
