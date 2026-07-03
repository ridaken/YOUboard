// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.settings.preferences

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.youboard.keyboard.keyboard.KeyboardSwitcher
import com.youboard.keyboard.latin.utils.prefs
import com.youboard.keyboard.settings.Setting
import com.youboard.keyboard.settings.dialogs.TextInputDialog
import androidx.core.content.edit
import com.youboard.keyboard.latin.R

@Composable
fun TextInputPreference(setting: Setting, default: String, info: String? = null, checkTextValid: (String) -> Boolean = { true }) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val prefs = LocalContext.current.prefs()
    Preference(
        name = setting.title,
        onClick = { showDialog = true },
        description = prefs.getString(setting.key, default)?.takeIf { it.isNotEmpty() }
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = {
                prefs.edit { putString(setting.key, it) }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            initialText = prefs.getString(setting.key, default) ?: "",
            title = { Text(setting.title) },
            description = if (info == null) null else { { Text(info) } },
            checkTextValid = checkTextValid,
            onNeutral = { prefs.edit { remove(setting.key) }; showDialog = false },
            neutralButtonText = stringResource(R.string.button_default)
        )
    }
}
