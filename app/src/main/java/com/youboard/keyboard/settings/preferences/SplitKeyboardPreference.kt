// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.settings.preferences

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.youboard.keyboard.latin.R
import com.youboard.keyboard.latin.settings.Defaults
import com.youboard.keyboard.latin.settings.SplitKeyboardSettings
import com.youboard.keyboard.latin.settings.SplitKeyboardSettings.Mode
import com.youboard.keyboard.latin.utils.FoldableUtils
import com.youboard.keyboard.latin.utils.prefs
import com.youboard.keyboard.settings.dialogs.ListPickerDialog
import com.youboard.keyboard.settings.dialogs.ThreeButtonAlertDialog

@Composable
fun SplitKeyboardPreference() {
    val prefs = LocalContext.current.prefs()
    val environment by FoldableUtils.snapshots.collectAsState()
    val foldable = environment.isFoldable
    var preferenceVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("split_keyboard")) preferenceVersion++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val modes = remember(prefs, preferenceVersion) {
        listOf(false, true).flatMap { landscape ->
            listOf(false, true).map { folded -> SplitKeyboardSettings.key(landscape, folded) }
        }.associateWith { SplitKeyboardSettings.mode(prefs, it) }
    }
    var show by remember { mutableStateOf(false) }
    var selectedProfile by remember { mutableStateOf<Pair<String, String>?>(null) }
    val profiles = listOfNotNull(
        SplitKeyboardSettings.key(false, false) to stringResource(if (foldable) R.string.split_unfolded_portrait else R.string.button_default),
        SplitKeyboardSettings.key(true, false) to stringResource(if (foldable) R.string.split_unfolded_landscape else R.string.landscape),
        if (foldable) SplitKeyboardSettings.key(false, true) to stringResource(R.string.folded) else null,
        if (foldable) SplitKeyboardSettings.key(true, true) to "${stringResource(R.string.folded)} / ${stringResource(R.string.landscape)}" else null,
    )
    Preference(
        name = stringResource(R.string.enable_split_keyboard),
        description = profiles.mapNotNull { (key, label) ->
            val mode = modes.getValue(key)
            when {
                mode == Mode.SPLIT -> label
                foldable && key in profiles.take(2).map { it.first } && mode == Mode.AUTOMATIC ->
                    "$label: ${stringResource(R.string.split_mode_automatic)}"
                else -> null
            }
        }.joinToString(", ").ifEmpty { null },
        onClick = { show = true }
    )
    if (show) ThreeButtonAlertDialog(
        onDismissRequest = { show = false },
        onConfirmed = {},
        confirmButtonText = null,
        cancelButtonText = stringResource(R.string.dialog_close),
        content = {
            Column {
                if (foldable) Text(stringResource(R.string.split_automatic_summary))
                profiles.forEachIndexed { index, profile ->
                    if (foldable && index < 2) {
                        Preference(name = profile.second,
                            description = splitModeName(modes.getValue(profile.first)),
                            onClick = { selectedProfile = profile })
                    } else {
                        SwitchPreference(name = profile.second, key = profile.first,
                            default = Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
                    }
                }
            }
        }
    )
    selectedProfile?.let { (key, title) ->
        ListPickerDialog(
            onDismissRequest = { selectedProfile = null },
            title = { Text(title) },
            items = Mode.entries,
            selectedItem = modes.getValue(key),
            getItemName = { splitModeName(it) },
            onItemSelected = { SplitKeyboardSettings.write(prefs, key, it) },
        )
    }
}

@Composable
private fun splitModeName(mode: Mode) = stringResource(when (mode) {
    Mode.AUTOMATIC -> R.string.split_mode_automatic
    Mode.SPLIT -> R.string.split
    Mode.STANDARD -> R.string.split_mode_standard
})
