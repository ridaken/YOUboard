// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.settings

import android.content.Context
import com.youboard.keyboard.keyboard.internal.KeyboardIconsSet
import com.youboard.keyboard.latin.settings.Settings
import com.youboard.keyboard.latin.utils.SubtypeSettings

// file is meant for making compose previews work

fun initPreview(context: Context) {
    Settings.init(context)
    SubtypeSettings.init(context)
    Settings.getInstance().loadSettings(context)
    SettingsActivity.settingsContainer = SettingsContainer(context)
    KeyboardIconsSet.instance.loadIcons(context)
}
