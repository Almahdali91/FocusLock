package com.focuslock.app.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.focuslock.app.data.FocusDatabase
import com.focuslock.app.data.FocusRepository
import com.focuslock.app.data.NotificationRecordEntity
import com.focuslock.app.domain.NotificationCapturePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Optional, user-enabled notification capture.
 * Content is persisted only after explicit in-app opt-in; otherwise callbacks are ignored.
 */
class NotificationCaptureService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { FocusRepository(FocusDatabase.get(applicationContext)) }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        val enabledAtCallback = preferences.getBoolean(KEY_CAPTURE_ENABLED, false)
        // Any consent toggle advances the epoch. A queued item from an earlier
        // consent period must never cross an off/on boundary.
        val epochAtCallback = preferences.getLong(KEY_CAPTURE_EPOCH, 0L)
        // Minimize access to notification content: do not even read extras while opted out
        // or for FocusLock's own notifications.
        if (!enabledAtCallback || item.packageName == packageName) return

        val sourcePackage = item.packageName.take(MAX_PACKAGE)
        val receivedAt = item.postTime
        val extras = item.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(MAX_TEXT)
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.take(MAX_TEXT)
        val hasReadableContent = !title.isNullOrBlank() || !text.isNullOrBlank()
        if (!NotificationCapturePolicy.shouldCapture(
                userOptedIn = enabledAtCallback,
                ownPackage = false,
                hasReadableContent = hasReadableContent
            )
        ) return

        scope.launch {
            // The user may turn capture off after the listener callback has extracted
            // content but before this queued IO task reaches Room. Re-check the current
            // preference and policy at the persistence boundary to discard that queued item.
            val enabledAtPersistence = preferences.getBoolean(KEY_CAPTURE_ENABLED, false)
            val epochAtPersistence = preferences.getLong(KEY_CAPTURE_EPOCH, 0L)
            if (!NotificationCapturePolicy.shouldPersistQueued(
                    userOptedInAtPersistence = enabledAtPersistence,
                    epochAtCallback = epochAtCallback,
                    epochAtPersistence = epochAtPersistence,
                    ownPackage = false,
                    hasReadableContent = hasReadableContent
                )
            ) return@launch

            runCatching {
                repository.saveNotification(
                    NotificationRecordEntity(
                        sourcePackage = sourcePackage,
                        title = title,
                        content = text,
                        receivedAt = receivedAt
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "notification_capture"
        const val KEY_CAPTURE_ENABLED = "capture_enabled"
        const val KEY_CAPTURE_EPOCH = "capture_consent_epoch"
        private const val MAX_TEXT = 2_000
        private const val MAX_PACKAGE = 255
    }
}
