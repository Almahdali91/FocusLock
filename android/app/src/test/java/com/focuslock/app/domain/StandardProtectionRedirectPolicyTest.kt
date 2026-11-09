package com.focuslock.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardProtectionRedirectPolicyTest {
    @Test
    fun activeMatchingRedirectMayUseFallbackOverlay() {
        assertTrue(StandardProtectionRedirectPolicy.shouldShowFallbackOverlay(true, true, true))
    }

    @Test
    fun inactiveSessionNeverShowsDelayedFallbackOverlay() {
        assertFalse(StandardProtectionRedirectPolicy.shouldShowFallbackOverlay(false, true, true))
    }

    @Test
    fun staleOrCompletedRedirectNeverShowsFallbackOverlay() {
        assertFalse(StandardProtectionRedirectPolicy.shouldShowFallbackOverlay(true, false, true))
        assertFalse(StandardProtectionRedirectPolicy.shouldShowFallbackOverlay(true, true, false))
    }
}
