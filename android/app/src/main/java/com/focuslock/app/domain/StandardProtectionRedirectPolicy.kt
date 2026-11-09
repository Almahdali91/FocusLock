package com.focuslock.app.domain

/** A delayed redirect fallback may display a blocker only while the same focus session remains active. */
object StandardProtectionRedirectPolicy {
    fun shouldShowFallbackOverlay(
        sessionActive: Boolean,
        redirectPending: Boolean,
        targetMatches: Boolean
    ): Boolean = sessionActive && redirectPending && targetMatches
}
