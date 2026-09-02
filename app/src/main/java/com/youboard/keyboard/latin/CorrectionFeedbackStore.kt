/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.youboard.keyboard.latin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Remembers correction pairs that a user rejected without retaining surrounding text.
 *
 * The backing file lives under noBackupFilesDir intentionally: correction feedback is local to
 * this installation and is not included in Android or YOUboard backups.
 */
class CorrectionFeedbackStore internal constructor(
    private var backingFile: File?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Key(
        val localeTag: String,
        val precedingWord: String,
        val typedWord: String,
        val replacement: String,
    )

    data class Entry(var rejectionCount: Int, var lastRejectedAt: Long)

    private val persisted = LinkedHashMap<Key, Entry>()
    private val rejectedThisSession = HashSet<Key>()

    init {
        load()
    }

    @Synchronized
    fun attach(context: Context) {
        if (backingFile != null) return
        backingFile = runCatching { File(context.noBackupFilesDir, "correction_feedback.json") }.getOrNull()
        load()
    }

    @Synchronized
    fun startSession() {
        rejectedThisSession.clear()
        purgeExpired(writeChanges = true)
    }

    @Synchronized
    fun shouldSuppress(locale: Locale?, precedingWord: CharSequence?, typedWord: String, replacement: String): Boolean {
        val key = key(locale, precedingWord, typedWord, replacement)
        purgeExpired(writeChanges = true)
        return key in rejectedThisSession || (persisted[key]?.rejectionCount ?: 0) >= PERSIST_AFTER_REJECTIONS
    }

    @Synchronized
    fun recordRejection(locale: Locale?, precedingWord: CharSequence?, typedWord: String, replacement: String) {
        if (typedWord.isBlank() || replacement.isBlank() || typedWord.equals(replacement, ignoreCase = false)) return
        val key = key(locale, precedingWord, typedWord, replacement)
        rejectedThisSession.add(key)
        val now = clock()
        val entry = persisted[key]
        if (entry == null) {
            persisted[key] = Entry(1, now)
        } else {
            entry.rejectionCount = (entry.rejectionCount + 1).coerceAtMost(PERSIST_AFTER_REJECTIONS)
            entry.lastRejectedAt = now
        }
        trimToCapacity()
        save()
    }

    /** Explicitly selecting a replacement is positive evidence and reverses one rejection. */
    @Synchronized
    fun recordAcceptance(locale: Locale?, precedingWord: CharSequence?, typedWord: String, replacement: String) {
        val key = key(locale, precedingWord, typedWord, replacement)
        rejectedThisSession.remove(key)
        val entry = persisted[key] ?: return
        entry.rejectionCount--
        if (entry.rejectionCount <= 0) persisted.remove(key)
        save()
    }

    @Synchronized
    fun clearAll() {
        rejectedThisSession.clear()
        persisted.clear()
        backingFile?.let { if (it.exists()) it.delete() }
    }

    @Synchronized
    internal fun snapshot(): Map<Key, Entry> = persisted.mapValues { Entry(it.value.rejectionCount, it.value.lastRejectedAt) }

    private fun key(locale: Locale?, precedingWord: CharSequence?, typedWord: String, replacement: String) = Key(
        locale?.toLanguageTag()?.lowercase(Locale.ROOT).orEmpty(),
        precedingWord?.toString()?.lowercase(locale ?: Locale.ROOT).orEmpty(),
        typedWord.lowercase(locale ?: Locale.ROOT),
        replacement.lowercase(locale ?: Locale.ROOT),
    )

    private fun purgeExpired(writeChanges: Boolean) {
        val cutoff = clock() - RETENTION_MILLIS
        val changed = persisted.entries.removeAll { it.value.lastRejectedAt < cutoff }
        if (changed && writeChanges) save()
    }

    private fun trimToCapacity() {
        while (persisted.size > MAX_ENTRIES) {
            val oldest = persisted.minByOrNull { it.value.lastRejectedAt }?.key ?: break
            persisted.remove(oldest)
            rejectedThisSession.remove(oldest)
        }
    }

    private fun load() {
        val file = backingFile ?: return
        if (!file.isFile) return
        runCatching {
            val entries = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                val key = Key(
                    item.optString("locale"), item.optString("previous"),
                    item.optString("typed"), item.optString("replacement"),
                )
                persisted[key] = Entry(item.optInt("count", 1), item.optLong("time", 0L))
            }
            purgeExpired(writeChanges = false)
            trimToCapacity()
        }.onFailure {
            // Corrupt learning data must never make the keyboard unavailable.
            persisted.clear()
        }
    }

    private fun save() {
        val file = backingFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val entries = JSONArray()
            persisted.forEach { (key, value) ->
                entries.put(JSONObject().apply {
                    put("locale", key.localeTag)
                    put("previous", key.precedingWord)
                    put("typed", key.typedWord)
                    put("replacement", key.replacement)
                    put("count", value.rejectionCount)
                    put("time", value.lastRejectedAt)
                })
            }
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(JSONObject().put("version", 1).put("entries", entries).toString())
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
    }

    companion object {
        internal const val MAX_ENTRIES = 512
        internal const val PERSIST_AFTER_REJECTIONS = 2
        internal const val RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000

        @JvmStatic
        fun create(context: Context): CorrectionFeedbackStore = CorrectionFeedbackStore(
            runCatching { File(context.noBackupFilesDir, "correction_feedback.json") }.getOrNull(),
        )

        internal fun inMemory(clock: () -> Long = System::currentTimeMillis) = CorrectionFeedbackStore(null, clock)
    }
}
