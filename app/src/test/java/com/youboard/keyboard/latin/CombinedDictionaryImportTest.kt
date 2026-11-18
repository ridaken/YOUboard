// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.makedict

import com.youboard.keyboard.latin.makedict.DictIO
import com.youboard.keyboard.latin.makedict.FusionDictionary
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CombinedDictionaryImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sample = "dictionary=test,locale=en,version=1\n word=hello,f=120\n shortcut=hi,f=14\n".replace("\n ", "\n")

    @Test fun plainAndGzipPreserveWordsAndShortcuts() {
        val plain = temporary.newFile("words.combined").apply { writeText(sample) }
        val compressed = temporary.newFile("words.combined.gz")
        GZIPOutputStream(compressed.outputStream()).bufferedWriter().use { it.write(sample) }
        for (dict in listOf(DictIO.readCombinedDictionary(plain.path), DictIO.readCompressedCombinedDictionary(compressed.path))) {
            assertTrue(dict.hasWord("hello"))
            val word = FusionDictionary.findWordInTree(dict.mRootNodeArray, "hello")
            assertEquals("hi", word.mShortcutTargets.single().mWord)
            val binary = temporary.newFile()
            DictIO.writeBinaryDictionary(binary.path, dict)
            assertTrue(binary.length() > 0)
        }
    }
}
