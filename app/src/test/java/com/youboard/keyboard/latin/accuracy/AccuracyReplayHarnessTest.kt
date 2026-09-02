// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.accuracy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccuracyReplayHarnessTest {
    @Test
    fun `replay reports literal and final accuracy separately`() {
        val corpus = listOf(
            AccuracyReplayHarness.Phrase("valid-short", "on", "on", "resting"),
            AccuracyReplayHarness.Phrase("correctable", "the", "teh"),
            AccuracyReplayHarness.Phrase("missed", "word", "wore"),
        )
        val metrics = AccuracyReplayHarness.replay(corpus) { phrase ->
            when (phrase.id) {
                "valid-short" -> AccuracyReplayHarness.Result("in", autoCorrected = true, repeatedCorrection = true, suggestionLatencyNanos = 30)
                "correctable" -> AccuracyReplayHarness.Result("the", autoCorrected = true, suggestionLatencyNanos = 20)
                else -> AccuracyReplayHarness.Result(phrase.typed, suggestionLatencyNanos = 10)
            }
        }

        assertEquals(3.0 / 9.0, metrics.literalCharacterErrorRate, 0.0001)
        assertEquals(2.0 / 3.0, metrics.finalWordErrorRate, 0.0001)
        assertEquals(0.5, metrics.badAutoCorrectRate, 0.0001)
        assertEquals(1, metrics.repeatedCorrectionIncidents)
        assertEquals(0.5, metrics.correctionRecall, 0.0001)
        assertEquals(20.0, metrics.averageSuggestionLatencyNanos)
    }

    @Test
    fun `synthetic nearby-key noise is deterministic`() {
        val phrase = AccuracyReplayHarness.Phrase("sample", "testing", "testing")
        val neighbors = mapOf('t' to listOf('r', 'y'), 'e' to listOf('w', 'r'))
        val first = AccuracyReplayHarness.withSyntheticNearbyKeyNoise(phrase, neighbors, 0.7, 42)
        val second = AccuracyReplayHarness.withSyntheticNearbyKeyNoise(phrase, neighbors, 0.7, 42)

        assertEquals(first.typed, second.typed)
        assertTrue(first.typed != phrase.typed)
    }

    @Test
    fun `unicode edit distance counts code points`() {
        assertEquals(1, AccuracyReplayHarness.editDistance("café", "cafe"))
        assertEquals(1, AccuracyReplayHarness.editDistance("👍", "👎"))
    }

    @Test
    fun `guardrails reject word error and meaningful latency regressions`() {
        val baseline = metrics(wordErrorRate = 0.04, latencyNanos = 1_000_000.0)

        assertTrue(AccuracyReplayHarness.passesRegressionGuardrails(
            baseline,
            metrics(wordErrorRate = 0.04, latencyNanos = 1_040_000.0),
        ))
        assertFalse(AccuracyReplayHarness.passesRegressionGuardrails(
            baseline,
            metrics(wordErrorRate = 0.05, latencyNanos = 1_000_000.0),
        ))
        assertFalse(AccuracyReplayHarness.passesRegressionGuardrails(
            baseline,
            metrics(wordErrorRate = 0.04, latencyNanos = 1_060_000.0),
        ))
    }

    private fun metrics(wordErrorRate: Double, latencyNanos: Double) = AccuracyReplayHarness.Metrics(
        literalCharacterErrorRate = 0.0,
        finalWordErrorRate = wordErrorRate,
        badAutoCorrectRate = 0.0,
        repeatedCorrectionIncidents = 0,
        correctionRecall = 1.0,
        averageSuggestionLatencyNanos = latencyNanos,
    )
}
