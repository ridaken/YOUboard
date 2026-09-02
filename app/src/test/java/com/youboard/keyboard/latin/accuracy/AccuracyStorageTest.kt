// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.accuracy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.youboard.keyboard.keyboard.AdaptiveTouchModel
import com.youboard.keyboard.latin.CorrectionFeedbackStore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AccuracyStorageTest {
    @Test
    fun `personalized accuracy data is excluded from Android backup`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val feedback = CorrectionFeedbackStore.create(context)
        val touch = AdaptiveTouchModel.getInstance(context).also { it.attach(context) }
        val diagnostics = AccuracyDiagnosticsRecorder.create(context).also { it.attach(context) }

        assertEquals(context.noBackupFilesDir.canonicalFile, fileField(feedback, "backingFile").parentFile!!.canonicalFile)
        assertEquals(context.noBackupFilesDir.canonicalFile, fileField(touch, "backingFile").parentFile!!.canonicalFile)
        assertEquals(context.noBackupFilesDir.canonicalFile, fileField(diagnostics, "file").parentFile!!.canonicalFile)
    }

    private fun fileField(instance: Any, name: String): File =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as File
}
