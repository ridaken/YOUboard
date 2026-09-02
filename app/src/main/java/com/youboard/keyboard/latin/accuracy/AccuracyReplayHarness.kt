/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.youboard.keyboard.latin.accuracy

import com.youboard.keyboard.latin.common.StringUtils
import kotlin.random.Random

/** Deterministic offline evaluator for tap/correction policy changes. */
object AccuracyReplayHarness {
    data class Phrase(
        val id: String,
        val intended: String,
        val typed: String,
        val previousWord: String = "",
    )

    data class Result(
        val finalText: String,
        val correctionCandidate: String? = null,
        val autoCorrected: Boolean = false,
        val repeatedCorrection: Boolean = false,
        val suggestionLatencyNanos: Long = 0,
    )

    data class Metrics(
        val literalCharacterErrorRate: Double,
        val finalWordErrorRate: Double,
        val badAutoCorrectRate: Double,
        val repeatedCorrectionIncidents: Int,
        val correctionRecall: Double,
        val averageSuggestionLatencyNanos: Double,
    )

    data class Guardrails(
        val maximumWordErrorRateIncrease: Double = 0.0,
        val maximumLatencyIncreaseFraction: Double = 0.05,
    )

    fun interface Decoder {
        fun decode(phrase: Phrase): Result
    }

    fun replay(corpus: List<Phrase>, decoder: Decoder): Metrics {
        if (corpus.isEmpty()) return Metrics(0.0, 0.0, 0.0, 0, 0.0, 0.0)
        var literalErrors = 0
        var intendedCharacters = 0
        var finalWordErrors = 0
        var badAutoCorrects = 0
        var autoCorrectCount = 0
        var repeated = 0
        var correctionOpportunities = 0
        var correctedOpportunities = 0
        var latency = 0L
        corpus.forEach { phrase ->
            val result = decoder.decode(phrase)
            literalErrors += editDistance(phrase.intended, phrase.typed)
            intendedCharacters += phrase.intended.codePointCount(0, phrase.intended.length)
            if (result.finalText != phrase.intended) finalWordErrors++
            if (result.autoCorrected) {
                autoCorrectCount++
                if (phrase.typed == phrase.intended && result.finalText != phrase.intended) badAutoCorrects++
            }
            if (result.repeatedCorrection) repeated++
            if (phrase.typed != phrase.intended) {
                correctionOpportunities++
                if (result.finalText == phrase.intended) correctedOpportunities++
            }
            latency += result.suggestionLatencyNanos
        }
        return Metrics(
            literalCharacterErrorRate = literalErrors.toDouble() / intendedCharacters.coerceAtLeast(1),
            finalWordErrorRate = finalWordErrors.toDouble() / corpus.size,
            badAutoCorrectRate = badAutoCorrects.toDouble() / autoCorrectCount.coerceAtLeast(1),
            repeatedCorrectionIncidents = repeated,
            correctionRecall = correctedOpportunities.toDouble() / correctionOpportunities.coerceAtLeast(1),
            averageSuggestionLatencyNanos = latency.toDouble() / corpus.size,
        )
    }

    fun passesRegressionGuardrails(
        baseline: Metrics,
        candidate: Metrics,
        guardrails: Guardrails = Guardrails(),
    ): Boolean {
        val maximumLatency = if (baseline.averageSuggestionLatencyNanos == 0.0) {
            0.0
        } else {
            baseline.averageSuggestionLatencyNanos * (1.0 + guardrails.maximumLatencyIncreaseFraction)
        }
        return candidate.finalWordErrorRate <=
            baseline.finalWordErrorRate + guardrails.maximumWordErrorRateIncrease &&
            candidate.averageSuggestionLatencyNanos <= maximumLatency
    }

    /** Adds repeatable nearby-key substitutions to an ASCII phrase corpus. */
    fun withSyntheticNearbyKeyNoise(
        phrase: Phrase,
        neighbors: Map<Char, List<Char>>,
        probability: Double,
        seed: Int,
    ): Phrase {
        val random = Random(seed)
        val noisy = buildString(phrase.intended.length) {
            phrase.intended.forEach { char ->
                val alternatives = neighbors[char.lowercaseChar()].orEmpty()
                if (alternatives.isNotEmpty() && random.nextDouble() < probability) {
                    val replacement = alternatives[random.nextInt(alternatives.size)]
                    append(if (char.isUpperCase()) replacement.uppercaseChar() else replacement)
                } else {
                    append(char)
                }
            }
        }
        return phrase.copy(typed = noisy)
    }

    internal fun editDistance(first: String, second: String): Int {
        val a = StringUtils.toCodePointArray(first)
        val b = StringUtils.toCodePointArray(second)
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.size + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[b.size]
    }
}
