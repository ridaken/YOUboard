// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

/** The key stays in memory; only the suppression count is safe to persist. */
internal class FallbackWarningState {
    private var previous: Any? = null
    private var suppressed = 0
    @Synchronized fun observe(key: Any?): Int? {
        if (key == null) { previous = null; suppressed = 0; return null }
        if (key == previous) { suppressed++; return null }
        val count = suppressed
        previous = key; suppressed = 0
        return count
    }
}
