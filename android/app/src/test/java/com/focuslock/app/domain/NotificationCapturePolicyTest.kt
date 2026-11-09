package com.focuslock.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCapturePolicyTest {
    @Test fun disabledCaptureNeverStoresContent() {
        assertFalse(NotificationCapturePolicy.shouldCapture(false, false, true))
    }

    @Test fun ownPackageIsNeverCaptured() {
        assertFalse(NotificationCapturePolicy.shouldCapture(true, true, true))
    }

    @Test fun emptyNotificationIsIgnored() {
        assertFalse(NotificationCapturePolicy.shouldCapture(true, false, false))
    }

    @Test fun explicitOptInAndExternalContentAreRequired() {
        assertTrue(NotificationCapturePolicy.shouldCapture(true, false, true))
    }

    @Test fun queuedWriteIsDiscardedWhenUserOptsOutBeforePersistence() {
        assertFalse(NotificationCapturePolicy.shouldPersistQueued(false, 4L, 5L, false, true))
    }

    @Test fun queuedWriteIsDiscardedAcrossOffOnConsentBoundary() {
        // Capture began in epoch 4; user switched off and on, advancing to epoch 6.
        assertFalse(NotificationCapturePolicy.shouldPersistQueued(true, 4L, 6L, false, true))
    }

    @Test fun queuedWriteRequiresSameConsentEpochAndReadableExternalContent() {
        assertFalse(NotificationCapturePolicy.shouldPersistQueued(true, 5L, 5L, true, true))
        assertFalse(NotificationCapturePolicy.shouldPersistQueued(true, 5L, 5L, false, false))
        assertTrue(NotificationCapturePolicy.shouldPersistQueued(true, 5L, 5L, false, true))
    }
}
