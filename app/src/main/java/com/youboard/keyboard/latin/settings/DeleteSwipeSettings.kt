// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit

object DeleteSwipeSettings {
    @JvmStatic fun readMode(prefs: SharedPreferences): Int {
        val mode = prefs.all[Settings.PREF_DELETE_SWIPE_MODE]
        if (mode is Int && mode in 0..2) return mode
        val legacy = prefs.all[Settings.PREF_DELETE_SWIPE] as? Boolean ?: Defaults.PREF_DELETE_SWIPE
        return if (legacy) 1 else 0
    }
    @JvmStatic fun setMode(prefs: SharedPreferences, mode: Int) {
        require(mode in 0..2)
        prefs.edit { putInt(Settings.PREF_DELETE_SWIPE_MODE, mode); putBoolean(Settings.PREF_DELETE_SWIPE, mode != 0) }
    }
    @JvmStatic fun migrate(prefs: SharedPreferences) {
        val mode = readMode(prefs)
        if (prefs.all[Settings.PREF_DELETE_SWIPE_MODE] != mode || prefs.all[Settings.PREF_DELETE_SWIPE] != (mode != 0)) setMode(prefs, mode)
    }
}
