/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.youboard.keyboard.latin.accuracy

import android.content.Context
import com.youboard.keyboard.keyboard.Keyboard
import com.youboard.keyboard.latin.CorrectionDecision
import com.youboard.keyboard.latin.LastComposedWord
import com.youboard.keyboard.latin.common.Constants
import com.youboard.keyboard.latin.common.StringUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Opt-in local JSON-lines diagnostics for building deterministic replay corpora. */
class AccuracyDiagnosticsRecorder private constructor(private var file: File?) {
    @Volatile private var enabled = false

    fun configure(enabled: Boolean) {
        this.enabled = enabled
    }

    fun attach(context: Context) {
        if (file == null) file = runCatching { File(context.noBackupFilesDir, "accuracy_replay.jsonl") }.getOrNull()
    }

    @Synchronized
    fun record(
        keyboard: Keyboard,
        composedWord: LastComposedWord,
        intendedWord: String,
        candidate: String?,
        decision: CorrectionDecision?,
        locale: Locale?,
        outcome: String,
        suggestionLatencyNanos: Long,
    ) {
        val destination = file ?: return
        if (!enabled || composedWord.mInputPointers.pointerSize == 0) return
        val offsets = JSONArray()
        val intendedCodePoints = StringUtils.toCodePointArray(intendedWord)
        if (intendedCodePoints.size == composedWord.mInputPointers.pointerSize) {
            intendedCodePoints.indices.forEach { index ->
                val key = keyboard.getKey(intendedCodePoints[index]) ?: return@forEach
                val x = composedWord.mInputPointers.xCoordinates[index]
                val y = composedWord.mInputPointers.yCoordinates[index]
                if (x == Constants.NOT_A_COORDINATE || y == Constants.NOT_A_COORDINATE) return@forEach
                offsets.put(JSONArray().put((x - key.x - key.width / 2f) / key.width)
                    .put((y - key.y - key.height / 2f) / key.height))
            }
        }
        val record = JSONObject().apply {
            put("version", 1)
            put("timestamp", System.currentTimeMillis())
            put("locale", locale?.toLanguageTag().orEmpty())
            put("typed", composedWord.mTypedWord)
            put("intended", intendedWord)
            put("candidate", candidate ?: JSONObject.NULL)
            put("spatialScore", decision?.spatialScore ?: JSONObject.NULL)
            put("contextScore", decision?.contextScore ?: JSONObject.NULL)
            put("confidenceMargin", decision?.confidenceMargin ?: JSONObject.NULL)
            put("decisionReason", decision?.reason?.name ?: JSONObject.NULL)
            put("outcome", outcome)
            put("latencyNanos", suggestionLatencyNanos)
            put("touchOffsets", offsets)
        }
        destination.parentFile?.mkdirs()
        destination.appendText(record.toString() + "\n")
        trimIfNeeded()
    }

    fun clear() {
        file?.let { if (it.exists()) it.delete() }
    }

    private fun trimIfNeeded() {
        val destination = file ?: return
        if (destination.length() <= MAX_FILE_BYTES) return
        val retained = destination.readLines().takeLast(MAX_RETAINED_RECORDS)
        destination.writeText(retained.joinToString("\n", postfix = "\n"))
    }

    companion object {
        private const val MAX_FILE_BYTES = 1_048_576L
        private const val MAX_RETAINED_RECORDS = 1_000

        @JvmStatic
        fun create(context: Context) = AccuracyDiagnosticsRecorder(
            runCatching { File(context.noBackupFilesDir, "accuracy_replay.jsonl") }.getOrNull(),
        )
    }
}
