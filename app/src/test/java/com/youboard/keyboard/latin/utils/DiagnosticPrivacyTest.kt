// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import java.util.concurrent.Executor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DiagnosticPrivacyTest {
    @Test fun `general debug text never enters persistent diagnostics or diagnostic crash tail`() {
        val app = RuntimeEnvironment.getApplication()
        val directory = app.noBackupFilesDir.resolve("privacy-test")
        val s = DiagnosticStore(directory, monotonicClock = { 10 }, executor = Executor { it.run() })
        val sentinel = "secret-typed-word-and-clipboard-content"
        Log.i("debug", sentinel)
        s.record(DiagnosticEvent.Lifecycle(DiagnosticReason.INPUT_SHOW))
        assertTrue(Log.getLog().any { it.message.contains(sentinel) })
        assertFalse(s.export().contains(sentinel))
        assertFalse(s.recent().contains(sentinel))
    }

    @Test fun `diagnostics initialize in backup excluded device protected storage`() {
        val app = RuntimeEnvironment.getApplication()
        val protected = Diagnostics.storageContext(app)
        assertTrue(protected.isDeviceProtectedStorage)
        val directory = protected.noBackupFilesDir.resolve("diagnostics-storage-test")
        val store = DiagnosticStore(directory, monotonicClock = { 1 }, executor = Executor { it.run() })
        store.record(DiagnosticEvent.Lifecycle(DiagnosticReason.PROCESS_START))
        val export = store.export()
        assertTrue(export.contains("PROCESS_START"))
        assertTrue(directory.isDirectory)
    }
}
