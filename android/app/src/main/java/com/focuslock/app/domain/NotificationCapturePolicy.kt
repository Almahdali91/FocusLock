package com.focuslock.app.domain

/** Pure opt-in gates for notification content extraction and persistence. */
object NotificationCapturePolicy {
    fun shouldCapture(
        userOptedIn: Boolean,
        ownPackage: Boolean,
        hasReadableContent: Boolean
    ): Boolean = userOptedIn && !ownPackage && hasReadableContent

    /**
     * Re-evaluated by the queued persistence task because the user may opt out
     * after notification extras were read but before the Room write begins.
     */
    fun shouldPersistQueued(
        userOptedInAtPersistence: Boolean,
        epochAtCallback: Long,
        epochAtPersistence: Long,
        ownPackage: Boolean,
        hasReadableContent: Boolean
    ): Boolean = userOptedInAtPersistence &&
        epochAtCallback == epochAtPersistence &&
        !ownPackage &&
        hasReadableContent
}
