/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.youboard.keyboard.latin

/**
 * An explainable result from the tap auto-correction policy.
 *
 * Scores are kept in the decoder's native scale. [confidenceMargin] is the normalized
 * confidence of the candidate above the configured absolute threshold.
 */
data class CorrectionDecision(
    val typedWord: String,
    val candidate: String?,
    val typedWordIsValid: Boolean,
    val spatialScore: Int?,
    val contextScore: Int?,
    val confidenceMargin: Float?,
    val allowsAutoCorrection: Boolean,
    val autoCorrect: Boolean,
    val reason: Reason,
) {
    enum class Reason {
        NO_CANDIDATE,
        WORD_NOT_ELIGIBLE,
        CORRECTION_DISABLED,
        NOT_COMPOSING,
        NO_SUGGESTIONS,
        CONTAINS_DIGITS,
        MIXED_CAPS,
        RESUMED_COMPOSITION,
        NO_MAIN_DICTIONARY,
        SHORT_VALID_WORD,
        BELOW_CONFIDENCE_THRESHOLD,
        DISALLOWED_CANDIDATE,
        CANDIDATE_SCORE_TOO_LOW,
        TYPED_WORD_SCORE_WINS,
        DIFFERENT_DICTIONARY_LOCALE,
        INSUFFICIENT_CONTEXT_MARGIN,
        INSUFFICIENT_RUNNER_UP_MARGIN,
        REJECTED_BY_USER,
        AUTO_CORRECT,
    }

    fun asLegacyPair(): Pair<Boolean, Boolean> = allowsAutoCorrection to autoCorrect
}
