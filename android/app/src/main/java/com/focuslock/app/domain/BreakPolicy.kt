package com.focuslock.app.domain

/**
 * Breaks are earned by completed focus blocks, not elapsed wall time.
 * No break is scheduled for sessions of two hours or less.
 */
object BreakPolicy {
    const val DEFAULT_BREAK_MILLIS = 10 * 60 * 1000L
    const val FIRST_BREAK_AFTER_MILLIS = 2 * 60 * 60 * 1000L
    const val REPEAT_BREAK_EVERY_MILLIS = 60 * 60 * 1000L

    fun breakCount(durationMillis: Long, enabled: Boolean = true): Int {
        if (!enabled || durationMillis <= FIRST_BREAK_AFTER_MILLIS) return 0
        val focusBeyondFirstBlock = durationMillis - FIRST_BREAK_AFTER_MILLIS
        return ((focusBeyondFirstBlock + REPEAT_BREAK_EVERY_MILLIS - 1L) /
            REPEAT_BREAK_EVERY_MILLIS).toInt()
    }

    fun breakAtElapsedMillis(index: Int): Long {
        require(index >= 0)
        return FIRST_BREAK_AFTER_MILLIS + index * REPEAT_BREAK_EVERY_MILLIS
    }

    /** Legacy exact-boundary helper retained for unit tests; production uses repository.startBreakIfDue. */
    fun shouldStartBreak(focusDurationMillis: Long, completedFocusMillis: Long, enabled: Boolean): Boolean {
        if (!enabled || completedFocusMillis <= 0L || completedFocusMillis >= focusDurationMillis) return false
        val nextIndex = (completedFocusMillis - FIRST_BREAK_AFTER_MILLIS) / REPEAT_BREAK_EVERY_MILLIS
        if (completedFocusMillis < FIRST_BREAK_AFTER_MILLIS) return false
        return completedFocusMillis == FIRST_BREAK_AFTER_MILLIS + nextIndex * REPEAT_BREAK_EVERY_MILLIS
    }
}