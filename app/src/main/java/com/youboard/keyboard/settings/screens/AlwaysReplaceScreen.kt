// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.youboard.keyboard.latin.AlwaysReplaceRule
import com.youboard.keyboard.latin.AlwaysReplaceStore
import com.youboard.keyboard.latin.R
import com.youboard.keyboard.latin.utils.prefs
import com.youboard.keyboard.settings.SearchScreen
import com.youboard.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.util.UUID

@Composable
fun AlwaysReplaceScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    var state by remember { mutableStateOf(AlwaysReplaceStore.load(prefs)) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(AlwaysReplaceStore.ENABLED_KEY, true)) }
    var selected by remember { mutableStateOf<AlwaysReplaceRule?>(null) }
    fun save(rules: List<AlwaysReplaceRule>) {
        AlwaysReplaceStore.save(prefs, rules)
        state = AlwaysReplaceStore.load(prefs)
    }
    val row: @Composable (AlwaysReplaceRule) -> Unit = { rule ->
        Column(Modifier.fillMaxWidth().clickable { selected = rule }.padding(16.dp)) {
            Text(rule.replacement, style = MaterialTheme.typography.titleMedium)
            Text(rule.triggers.joinToString(" / "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!rule.enabled) Text(stringResource(R.string.always_replace_disabled))
        }
    }
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.always_replace)) },
        filteredItems = { term -> state.rules.filter { rule ->
            rule.replacement.contains(term, true) || rule.triggers.any { it.contains(term, true) }
        } },
        itemContent = row,
        content = {
            ToggleRow(stringResource(R.string.always_replace_enable), enabled) {
                enabled = it
                prefs.edit { putBoolean(AlwaysReplaceStore.ENABLED_KEY, it) }
            }
            Text(stringResource(R.string.always_replace_summary), Modifier.padding(horizontal = 16.dp))
            if (state.error) {
                Text(stringResource(R.string.always_replace_data_error), Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { save(emptyList()) }) {
                    Text(stringResource(R.string.always_replace_reset))
                }
            }
            TextButton(onClick = { selected = AlwaysReplaceRule(UUID.randomUUID().toString(), "", listOf("")) },
                enabled = !state.error) { Text(stringResource(R.string.always_replace_add_rule)) }
            LazyColumn { items(state.rules, key = { it.id }) { row(it) } }
        },
        menu = listOf(stringResource(R.string.always_replace_add_rule) to {
            if (!state.error) selected = AlwaysReplaceRule(UUID.randomUUID().toString(), "", listOf(""))
        })
    )
    selected?.let { rule ->
        RuleEditor(rule, state.rules,
            onDismiss = { selected = null },
            onSave = { edited -> save(state.rules.filter { it.id != edited.id } + edited); selected = null },
            onDelete = { save(state.rules.filter { it.id != rule.id }); selected = null })
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RuleEditor(rule: AlwaysReplaceRule, rules: List<AlwaysReplaceRule>, onDismiss: () -> Unit,
                       onSave: (AlwaysReplaceRule) -> Unit, onDelete: () -> Unit) {
    var draft by remember(rule.id) { mutableStateOf(rule) }
    val normalized = draft.copy(triggers = draft.triggers.map { it.trim() })
    val error = AlwaysReplaceStore.validate(normalized, rules)
    ThreeButtonAlertDialog(
        onDismissRequest = onDismiss,
        onConfirmed = { onSave(normalized) },
        checkOk = { error == null },
        confirmButtonText = stringResource(R.string.save),
        neutralButtonText = if (rules.any { it.id == rule.id }) stringResource(R.string.delete) else null,
        onNeutral = onDelete,
        title = { Text(stringResource(R.string.always_replace)) },
        scrollContent = true,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(draft.replacement, { draft = draft.copy(replacement = it) },
                    label = { Text(stringResource(R.string.always_replace_with)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                draft.triggers.forEachIndexed { index, trigger ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(trigger, { value -> draft = draft.copy(triggers = draft.triggers.toMutableList().apply {
                            this[index] = value
                        }) }, label = { Text(stringResource(R.string.always_replace_when)) },
                            modifier = Modifier.weight(1f), singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                        if (draft.triggers.size > 1) TextButton(onClick = {
                            draft = draft.copy(triggers = draft.triggers.filterIndexed { i, _ -> i != index })
                        }) { Text(stringResource(R.string.always_replace_remove_input)) }
                    }
                }
                TextButton(onClick = { draft = draft.copy(triggers = draft.triggers + "") }) {
                    Text(stringResource(R.string.always_replace_add_input))
                }
                ToggleRow(stringResource(R.string.always_replace_respect_case), draft.respectCapitalization) {
                    draft = draft.copy(respectCapitalization = it)
                }
                ToggleRow(stringResource(R.string.always_replace_rule_enabled), draft.enabled) {
                    draft = draft.copy(enabled = it)
                }
                Text(stringResource(R.string.always_replace_case_summary), style = MaterialTheme.typography.bodySmall)
                if (error != null) Text(when (error.kind) {
                    AlwaysReplaceStore.Error.EMPTY -> stringResource(R.string.always_replace_empty_error)
                    AlwaysReplaceStore.Error.MULTILINE -> stringResource(R.string.always_replace_multiline_error)
                    AlwaysReplaceStore.Error.NO_OP -> stringResource(R.string.always_replace_noop_error)
                    AlwaysReplaceStore.Error.CONFLICT -> stringResource(R.string.always_replace_conflict_error,
                        error.trigger, error.conflictingTrigger)
                }, color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
