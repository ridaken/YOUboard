// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin

import android.content.Context
import android.os.Looper
import android.provider.UserDictionary
import androidx.test.core.app.ApplicationProvider
import com.youboard.keyboard.latin.dictionary.Dictionary
import com.youboard.keyboard.latin.dictionary.DictionarySourceMonitor
import com.youboard.keyboard.latin.dictionary.ExpandableBinaryDictionary
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class DictionarySourceMonitorTest {
    @Test fun changesAreCoalescedAndDetachedDictionariesAreNotRefreshed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = mock(ExpandableBinaryDictionary::class.java)
        val second = mock(ExpandableBinaryDictionary::class.java)
        for (dict in listOf(first, second)) {
            ReflectionHelpers.setField(dict, "mDictType", Dictionary.TYPE_USER)
            ReflectionHelpers.setField(dict, "mContext", context)
            DictionarySourceMonitor.attach(dict)
        }
        try {
            repeat(3) { context.contentResolver.notifyChange(UserDictionary.Words.CONTENT_URI, null) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
            verify(first, times(1)).onSourceChanged()
            verify(second, times(1)).onSourceChanged()
            DictionarySourceMonitor.detach(first)
            context.contentResolver.notifyChange(UserDictionary.Words.CONTENT_URI, null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
            verify(first, times(1)).onSourceChanged()
            verify(second, times(2)).onSourceChanged()
        } finally {
            DictionarySourceMonitor.detach(first)
            DictionarySourceMonitor.detach(second)
        }
    }
}
