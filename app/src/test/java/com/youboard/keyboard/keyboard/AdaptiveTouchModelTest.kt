// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveTouchModelTest {
    @Test
    fun `cold model applies no offset`() {
        val offset = AdaptiveTouchModel.inMemory().getOffset("portrait-qwerty", 0)
        assertEquals(0f, offset.x)
        assertEquals(0f, offset.y)
        assertEquals(0, offset.sampleCount)
    }

    @Test
    fun `cluster starts adapting only after enough trusted taps`() {
        val model = AdaptiveTouchModel.inMemory()
        repeat(AdaptiveTouchModel.MIN_CLUSTER_SAMPLES - 1) {
            model.record("portrait-qwerty", 2, 0.12f, -0.08f)
        }
        assertEquals(0f, model.getOffset("portrait-qwerty", 2).x)

        model.record("portrait-qwerty", 2, 0.12f, -0.08f)
        val offset = model.getOffset("portrait-qwerty", 2)
        assertTrue(kotlin.math.abs(offset.x - 0.12f) < 0.0001f)
        assertTrue(kotlin.math.abs(offset.y + 0.08f) < 0.0001f)
    }

    @Test
    fun `partitions and clusters remain isolated`() {
        val model = AdaptiveTouchModel.inMemory()
        repeat(AdaptiveTouchModel.MIN_CLUSTER_SAMPLES) {
            model.record("portrait-qwerty", 0, 0.1f, 0f)
            model.record("landscape-qwerty", 0, -0.1f, 0f)
        }

        assertTrue(model.getOffset("portrait-qwerty", 0).x > 0f)
        assertTrue(model.getOffset("landscape-qwerty", 0).x < 0f)
        assertEquals(0, model.getOffset("portrait-qwerty", 1).sampleCount)
    }

    @Test
    fun `rolling buckets bound history to eight hundred taps`() {
        val model = AdaptiveTouchModel.inMemory()
        repeat(1_100) { model.record("portrait-qwerty", 3, 0.05f, 0.02f) }

        assertTrue(model.getOffset("portrait-qwerty", 3).sampleCount <= 800)
    }

    @Test
    fun `reset removes every partition`() {
        val model = AdaptiveTouchModel.inMemory()
        model.record("portrait-qwerty", 0, 0.1f, 0f)
        model.record("landscape-qwerty", 0, 0.1f, 0f)
        model.reset()

        assertEquals(0, model.partitionCount())
    }
}
