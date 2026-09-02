/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.youboard.keyboard.keyboard

import android.content.Context
import com.youboard.keyboard.latin.common.ComposedData
import com.youboard.keyboard.latin.common.Constants
import com.youboard.keyboard.latin.common.InputPointers
import com.youboard.keyboard.latin.common.StringUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Small, on-device model of a typist's systematic touch-center offsets.
 *
 * It stores only aggregate normalized x/y offsets. It never stores words, key codes, or raw touch
 * sequences. Seven broad spatial clusters share data between nearby keys. Four rotating buckets of
 * 200 samples bound the history at 800 observations per keyboard partition.
 */
class AdaptiveTouchModel internal constructor(private var backingFile: File?) {
    data class Offset(val x: Float, val y: Float, val sampleCount: Int)

    private data class Bucket(
        val counts: IntArray = IntArray(CLUSTER_COUNT),
        val xSums: FloatArray = FloatArray(CLUSTER_COUNT),
        val ySums: FloatArray = FloatArray(CLUSTER_COUNT),
        var total: Int = 0,
    ) {
        fun clear() {
            counts.fill(0)
            xSums.fill(0f)
            ySums.fill(0f)
            total = 0
        }
    }

    private data class PartitionState(
        val buckets: Array<Bucket> = Array(BUCKET_COUNT) { Bucket() },
        var currentBucket: Int = 0,
    )

    private val partitions = ConcurrentHashMap<String, PartitionState>()
    private val storageExecutor by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "AdaptiveTouchStorage").apply { isDaemon = true }
        }
    }
    @Volatile private var enabled = true
    @Volatile private var orientation = 0
    @Volatile private var oneHandedSide = 0

    init {
        load()
    }

    @Synchronized
    fun attach(context: Context) {
        if (backingFile != null) return
        backingFile = runCatching { File(context.noBackupFilesDir, "adaptive_touch.json") }.getOrNull()
        load()
    }

    fun configure(enabled: Boolean, orientation: Int, oneHandedEnabled: Boolean, oneHandedGravity: Int) {
        this.enabled = enabled
        this.orientation = orientation
        oneHandedSide = if (oneHandedEnabled) oneHandedGravity else 0
    }

    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun getOffset(keyboard: Keyboard, key: Key): Offset {
        if (!enabled || !isLearnableKey(key)) return Offset(0f, 0f, 0)
        return getOffset(partitionId(keyboard), clusterFor(keyboard, key))
    }

    @Synchronized
    internal fun getOffset(partition: String, cluster: Int): Offset {
        val state = partitions[partition] ?: return Offset(0f, 0f, 0)
        var count = 0
        var x = 0f
        var y = 0f
        state.buckets.forEach {
            count += it.counts[cluster]
            x += it.xSums[cluster]
            y += it.ySums[cluster]
        }
        if (count < MIN_CLUSTER_SAMPLES) return Offset(0f, 0f, count)
        return Offset(x / count, y / count, count)
    }

    fun adjustTouch(keyboard: Keyboard, key: Key?, x: Int, y: Int): IntArray {
        if (key == null) return intArrayOf(x, y)
        return adjustTouch(partitionId(keyboard), keyboard, key, x, y)
    }

    private fun adjustTouch(partition: String, keyboard: Keyboard, key: Key, x: Int, y: Int): IntArray {
        val offset = getOffset(partition, clusterFor(keyboard, key))
        return intArrayOf(
            x - (offset.x * key.width).roundToInt(),
            y - (offset.y * key.height).roundToInt(),
        )
    }

    /** Returns decoder input with personalized coordinates; the original snapshot is unchanged. */
    fun adjustForDecoder(composedData: ComposedData, keyboard: Keyboard): ComposedData {
        if (!enabled || composedData.mIsBatchMode || composedData.mInputPointers.pointerSize == 0) return composedData
        val codePoints = StringUtils.toCodePointArray(composedData.mTypedWord)
        val source = composedData.mInputPointers
        if (codePoints.size != source.pointerSize) return composedData
        val adjusted = InputPointers(source.pointerSize)
        val partition = partitionId(keyboard)
        for (index in 0 until source.pointerSize) {
            val key = findKey(keyboard, codePoints[index])
            val point = if (key == null) {
                intArrayOf(source.xCoordinates[index], source.yCoordinates[index])
            } else {
                adjustTouch(partition, keyboard, key, source.xCoordinates[index], source.yCoordinates[index])
            }
            adjusted.addPointer(point[0], point[1], source.pointerIds[index], source.times[index])
        }
        return ComposedData(adjusted, false, composedData.mTypedWord)
    }

    /** Learn an intended word only when its code points align one-to-one with the recorded taps. */
    fun learnAligned(keyboard: Keyboard, inputPointers: InputPointers, intendedWord: String) {
        if (!enabled) return
        val codePoints = StringUtils.toCodePointArray(intendedWord)
        if (codePoints.size != inputPointers.pointerSize) return
        val partition = partitionId(keyboard)
        for (index in codePoints.indices) {
            val key = findKey(keyboard, codePoints[index]) ?: continue
            val x = inputPointers.xCoordinates[index]
            val y = inputPointers.yCoordinates[index]
            if (x == Constants.NOT_A_COORDINATE || y == Constants.NOT_A_COORDINATE) continue
            val centerX = key.x + key.width / 2f
            val centerY = key.y + key.height / 2f
            record(
                partition,
                clusterFor(keyboard, key),
                ((x - centerX) / key.width).coerceIn(-MAX_ABSOLUTE_OFFSET, MAX_ABSOLUTE_OFFSET),
                ((y - centerY) / key.height).coerceIn(-MAX_ABSOLUTE_OFFSET, MAX_ABSOLUTE_OFFSET),
            )
        }
        scheduleSave()
    }

    @Synchronized
    internal fun record(partition: String, cluster: Int, normalizedX: Float, normalizedY: Float) {
        if (cluster !in 0 until CLUSTER_COUNT) return
        val state = partitions.getOrPut(partition) { PartitionState() }
        var bucket = state.buckets[state.currentBucket]
        if (bucket.total >= SAMPLES_PER_BUCKET) {
            state.currentBucket = (state.currentBucket + 1) % BUCKET_COUNT
            bucket = state.buckets[state.currentBucket]
            bucket.clear()
        }
        bucket.counts[cluster]++
        bucket.xSums[cluster] += normalizedX.coerceIn(-MAX_ABSOLUTE_OFFSET, MAX_ABSOLUTE_OFFSET)
        bucket.ySums[cluster] += normalizedY.coerceIn(-MAX_ABSOLUTE_OFFSET, MAX_ABSOLUTE_OFFSET)
        bucket.total++
    }

    @Synchronized
    fun reset() {
        partitions.clear()
        backingFile?.let { file ->
            storageExecutor.execute { if (file.exists()) file.delete() }
        }
    }

    internal fun partitionCount(): Int = partitions.size

    private fun partitionId(keyboard: Keyboard): String {
        // The hash distinguishes layout geometry without retaining text or a typing history.
        var geometryHash = 17
        keyboard.sortedKeys.asSequence().filter(::isLearnableKey).forEach { key ->
            geometryHash = 31 * geometryHash + key.code
            geometryHash = 31 * geometryHash + key.x
            geometryHash = 31 * geometryHash + key.y
            geometryHash = 31 * geometryHash + key.width
            geometryHash = 31 * geometryHash + key.height
        }
        return "$geometryHash:${keyboard.mOccupiedWidth}x${keyboard.mOccupiedHeight}:$orientation:$oneHandedSide"
    }

    private fun clusterFor(keyboard: Keyboard, key: Key): Int {
        val normalizedX = (key.x + key.width / 2f) / keyboard.mOccupiedWidth.coerceAtLeast(1)
        val normalizedY = (key.y + key.height / 2f) / keyboard.mOccupiedHeight.coerceAtLeast(1)
        if (normalizedY >= 0.66f) return 6
        val column = when {
            normalizedX < 0.34f -> 0
            normalizedX < 0.67f -> 1
            else -> 2
        }
        return if (normalizedY < 0.42f) column else 3 + column
    }

    private fun isLearnableKey(key: Key): Boolean = key.code > 0 && Character.isLetter(key.code)

    private fun findKey(keyboard: Keyboard, codePoint: Int): Key? =
        keyboard.getKey(codePoint) ?: keyboard.getKey(Character.toLowerCase(codePoint))

    private fun load() {
        val file = backingFile ?: return
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            val jsonPartitions = root.optJSONArray("partitions") ?: JSONArray()
            for (index in 0 until jsonPartitions.length()) {
                val item = jsonPartitions.getJSONObject(index)
                val state = PartitionState(currentBucket = item.optInt("current", 0).coerceIn(0, BUCKET_COUNT - 1))
                val buckets = item.optJSONArray("buckets") ?: JSONArray()
                for (bucketIndex in 0 until minOf(buckets.length(), BUCKET_COUNT)) {
                    val source = buckets.getJSONObject(bucketIndex)
                    val target = state.buckets[bucketIndex]
                    target.total = source.optInt("total", 0)
                    copyArray(source.optJSONArray("counts"), target.counts)
                    copyArray(source.optJSONArray("x"), target.xSums)
                    copyArray(source.optJSONArray("y"), target.ySums)
                }
                partitions[item.getString("id")] = state
            }
        }.onFailure { partitions.clear() }
    }

    private fun scheduleSave() {
        if (backingFile == null) return
        storageExecutor.execute(::save)
    }

    private fun save() {
        val (file, contents) = serializedState() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(contents)
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
    }

    @Synchronized
    private fun serializedState(): Pair<File, String>? {
        val file = backingFile ?: return null
        val jsonPartitions = JSONArray()
        partitions.forEach { (id, state) ->
            val buckets = JSONArray()
            state.buckets.forEach { bucket ->
                buckets.put(JSONObject().apply {
                    put("total", bucket.total)
                    put("counts", JSONArray(bucket.counts.toList()))
                    put("x", JSONArray(bucket.xSums.toList()))
                    put("y", JSONArray(bucket.ySums.toList()))
                })
            }
            jsonPartitions.put(JSONObject().put("id", id).put("current", state.currentBucket).put("buckets", buckets))
        }
        return file to JSONObject().put("version", 1).put("partitions", jsonPartitions).toString()
    }

    private fun copyArray(source: JSONArray?, target: IntArray) {
        if (source == null) return
        for (index in 0 until minOf(source.length(), target.size)) target[index] = source.optInt(index)
    }

    private fun copyArray(source: JSONArray?, target: FloatArray) {
        if (source == null) return
        for (index in 0 until minOf(source.length(), target.size)) target[index] = source.optDouble(index).toFloat()
    }

    companion object {
        internal const val CLUSTER_COUNT = 7
        internal const val BUCKET_COUNT = 4
        internal const val SAMPLES_PER_BUCKET = 200
        internal const val MIN_CLUSTER_SAMPLES = 12
        private const val MAX_ABSOLUTE_OFFSET = 0.35f

        @Volatile private var instance: AdaptiveTouchModel? = null

        @JvmStatic
        fun getInstance(context: Context): AdaptiveTouchModel = instance ?: synchronized(this) {
            instance ?: AdaptiveTouchModel(
                runCatching { File(context.noBackupFilesDir, "adaptive_touch.json") }.getOrNull(),
            ).also { instance = it }
        }

        @JvmStatic
        fun getCurrent(): AdaptiveTouchModel? = instance

        internal fun inMemory(): AdaptiveTouchModel = AdaptiveTouchModel(null)
    }
}
