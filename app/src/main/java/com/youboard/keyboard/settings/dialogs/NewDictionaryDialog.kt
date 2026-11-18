// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.settings.dialogs

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import android.util.AtomicFile
import com.youboard.keyboard.latin.makedict.DictIO
import com.youboard.keyboard.latin.makedict.CombinedInputOutput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.youboard.keyboard.compat.locale
import com.youboard.keyboard.dictionarypack.DictionaryPackConstants
import com.youboard.keyboard.latin.dictionary.Dictionary
import com.youboard.keyboard.latin.R
import com.youboard.keyboard.latin.dictionary.ReadOnlyBinaryDictionary
import com.youboard.keyboard.latin.common.LocaleUtils.constructLocale
import com.youboard.keyboard.latin.common.LocaleUtils.localizedDisplayName
import com.youboard.keyboard.latin.makedict.DictionaryHeader
import com.youboard.keyboard.latin.utils.DictionaryInfoUtils
import com.youboard.keyboard.latin.utils.ScriptUtils.script
import com.youboard.keyboard.latin.utils.SubtypeSettings
import com.youboard.keyboard.latin.utils.locale
import com.youboard.keyboard.settings.DropDownField
import com.youboard.keyboard.settings.WithSmallTitle
import java.io.File
import java.util.Locale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import com.youboard.keyboard.latin.RichInputMethodManager

@Composable
fun NewDictionaryDialog(
    onDismissRequest: () -> Unit,
    cachedFile: File,
    mainLocale: Locale?
) {
    val preparedFile = remember(cachedFile) { File(cachedFile.parentFile, "prepared-" + java.util.UUID.randomUUID() + ".dict") }
    var checked by remember(cachedFile) { mutableStateOf<Pair<Int?, DictionaryHeader?>?>(null) }
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(cachedFile) {
        try {
            checked = withContext(Dispatchers.IO) {
                runCatching {
                    cachedFile.copyTo(preparedFile, overwrite = true)
                    convertDictionary(preparedFile)
                    checkDict(preparedFile)
                }.getOrElse { R.string.dictionary_file_error to null }
            }
        } finally {
            if (!isActive) withContext(NonCancellable + Dispatchers.IO) { preparedFile.delete() }
        }
    }
    DisposableEffect(cachedFile) { onDispose { cachedFile.delete(); preparedFile.delete() } }
    if (checked == null) {
        AlertDialog(onDismissRequest = onDismissRequest,
            title = { Text(stringResource(R.string.add_new_dictionary_title)) },
            text = { CircularProgressIndicator() }, confirmButton = {})
        return
    }
    val (error, header) = checked!!
    if (installError) {
        InfoDialog(stringResource(R.string.dictionary_load_error), onDismissRequest)
        return
    }
    if (error != null) {
        InfoDialog(stringResource(error), onDismissRequest)
        cachedFile.delete()
    } else if (header != null) {
        val ctx = LocalContext.current
        val dictLocale = header.mLocaleString.constructLocale()
        val enabledLocales = SubtypeSettings.getEnabledSubtypes().map { it.locale() }
        val enabledLanguages = enabledLocales.map { it.language }
        val comparer = compareBy<Locale>(
            { it != mainLocale },
            { it !in enabledLocales },
            { it != dictLocale },
            { it.language !in enabledLanguages },
            { it.script() != dictLocale.script() }
        )
        val locales = SubtypeSettings.getAvailableSubtypeLocales()
            .filter { it.script() == dictLocale.script() || it.script() == mainLocale?.script() }
            .sortedWith(comparer)
        var locale by remember {
            mutableStateOf(mainLocale
                ?: RichInputMethodManager.getInstance().findSubtypeForHintLocale(dictLocale)?.locale()
                ?: dictLocale.takeIf { it in locales }
                ?: locales.first())
        }
        val cacheDir = DictionaryInfoUtils.getCacheDirectoryForLocale(locale, ctx)
        val dictFile = File(cacheDir, header.mIdString.substringBefore(":") + "_" + DictionaryInfoUtils.USER_DICTIONARY_SUFFIX)
        val type = header.mIdString.substringBefore(":")
        val info = header.info(LocalConfiguration.current.locale())
        ThreeButtonAlertDialog(
            onDismissRequest = { if (!installing) onDismissRequest() },
            onConfirmed = {
                if (!installing) {
                    installing = true
                    scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            runCatching {
                                val target = AtomicFile(dictFile)
                                val output = target.startWrite()
                                try {
                                    preparedFile.inputStream().use { it.copyTo(output) }
                                    target.finishWrite(output)
                                } catch (failure: Throwable) {
                                    target.failWrite(output)
                                    throw failure
                                }
                                if (type == Dictionary.TYPE_MAIN)
                                    File(cacheDir, DictionaryInfoUtils.MAIN_DICT_FILE_NAME).delete()
                            }.isSuccess
                        }
                        installing = false
                        if (success) {
                            ctx.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION).setPackage(ctx.packageName))
                            onDismissRequest()
                        } else installError = true
                    }
                }
            },
            confirmButtonText = stringResource(if (dictFile.exists()) R.string.replace_dictionary else android.R.string.ok),
            title = { Text(stringResource(R.string.add_new_dictionary_title)) },
            content = {
                Column {
                    Text(info, Modifier.padding(bottom = 10.dp))
                    WithSmallTitle(stringResource(R.string.button_select_language)) {
                        DropDownField(
                            selectedItem = locale,
                            onSelected = { locale = it },
                            items = locales
                        ) { Text(it.localizedDisplayName(LocalResources.current)) }
                    }
                    if (locale.script() != dictLocale.script()) {
                        // whatever, still allow it if the user wants
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.dictionary_file_wrong_script),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
                        )
                    }
                    if (dictFile.exists()) {
                        val oldInfo = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(dictFile)?.info(LocalConfiguration.current.locale())
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.replace_dictionary_message, type, oldInfo ?: "(no info)", info),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            scrollContent = true,
        )
    }
}

private fun checkDict(file: File): Pair<Int?, DictionaryHeader?> {
    val newHeader = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(file)
        ?: return R.string.dictionary_file_error to null

    val locale = newHeader.mLocaleString.constructLocale()
    val dict = ReadOnlyBinaryDictionary(file.absolutePath, 0, file.length(), false, locale, "test")
    return try {
        if (dict.isValidDictionary) null to newHeader else R.string.dictionary_load_error to null
    } finally { dict.close() }
}

private fun convertDictionary(file: File) {
    if (DictionaryInfoUtils.getDictionaryFileHeaderOrNull(file) != null) return
    val converted = File(file.parentFile, file.name + ".converted")
    try {
        val dictionary = if (CombinedInputOutput.isCombinedDictionary(file.path))
            DictIO.readCombinedDictionary(file.path)
        else DictIO.readCompressedCombinedDictionary(file.path)
        DictIO.writeBinaryDictionary(converted.path, dictionary)
        require(checkDict(converted).first == null)
        converted.copyTo(file, overwrite = true)
    } finally { converted.delete() }
}
