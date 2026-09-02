// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.youboard.keyboard.latin.R
import com.youboard.keyboard.latin.utils.NeverPredictList
import com.youboard.keyboard.latin.utils.prefs
import com.youboard.keyboard.settings.SearchScreen
import com.youboard.keyboard.settings.dialogs.ConfirmationDialog
import com.youboard.keyboard.settings.dialogs.TextInputDialog

@Composable
fun NeverPredictScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    var words by remember { mutableStateOf(NeverPredictList.getWords(prefs).sorted()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedWord: String? by remember { mutableStateOf(null) }

    fun refresh() { words = NeverPredictList.getWords(prefs).sorted() }

    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.never_predict_list_title)) },
        filteredItems = { term -> words.filter { it.startsWith(term.trim(), ignoreCase = true) } },
        itemContent = { word ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedWord = word }
                    .padding(vertical = 10.dp, horizontal = 16.dp)
            ) {
                Text(word, style = MaterialTheme.typography.bodyLarge)
                Icon(painterResource(R.drawable.ic_bin), stringResource(R.string.remove))
            }
        }
    )
    ExtendedFloatingActionButton(
        onClick = { showAddDialog = true },
        text = { Text(stringResource(R.string.add)) },
        icon = { Icon(painterResource(R.drawable.ic_plus), stringResource(R.string.add)) },
        modifier = Modifier.wrapContentSize(Alignment.BottomEnd).padding(all = 12.dp)
            .then(Modifier.safeDrawingPadding())
    )
    if (showAddDialog) {
        TextInputDialog(
            onDismissRequest = { showAddDialog = false },
            onConfirmed = {
                NeverPredictList.addWord(prefs, it)
                refresh()
            },
            title = { Text(stringResource(R.string.never_predict_list_title)) },
            description = { Text(stringResource(R.string.never_predict_list_summary)) },
            textInputLabel = { Text(stringResource(R.string.never_predict_add_hint)) },
            checkTextValid = { it.isNotBlank() }
        )
    }
    if (selectedWord != null) {
        val word = selectedWord!!
        ConfirmationDialog(
            onDismissRequest = { selectedWord = null },
            onConfirmed = {
                NeverPredictList.removeWord(prefs, word)
                refresh()
                selectedWord = null
            },
            title = { Text(word) },
            confirmButtonText = stringResource(R.string.remove),
        )
    }
}
