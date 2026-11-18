// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.youboard.keyboard.latin.settings.DeleteSwipeSettings
import com.youboard.keyboard.latin.settings.Settings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class DeleteSwipeSettingsTest {
    @Test fun migrationAndOldBackupRestoreDoNotDependOnVersionCodes() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("delete-mode-test", 0)
        prefs.edit { clear(); putBoolean(Settings.PREF_DELETE_SWIPE, false); putInt(Settings.PREF_VERSION_CODE, 99999) }
        DeleteSwipeSettings.migrate(prefs)
        assertEquals(0, DeleteSwipeSettings.readMode(prefs))
        DeleteSwipeSettings.setMode(prefs, 2)
        DeleteSwipeSettings.migrate(prefs)
        assertEquals(2, DeleteSwipeSettings.readMode(prefs))
        prefs.edit { clear(); putBoolean(Settings.PREF_DELETE_SWIPE, true) }
        DeleteSwipeSettings.migrate(prefs)
        assertEquals(1, DeleteSwipeSettings.readMode(prefs))
        prefs.edit { clear() }
        assertEquals(1, DeleteSwipeSettings.readMode(prefs))
    }
}
