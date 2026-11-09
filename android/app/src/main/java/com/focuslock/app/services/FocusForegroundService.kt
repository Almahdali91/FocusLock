package com.focuslock.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.focuslock.app.MainActivity
import com.focuslock.app.R
import com.focuslock.app.data.FocusDatabase
import com.focuslock.app.data.FocusRepository
import com.focuslock.app.domain.BreakPolicy
import com.focuslock.app.system.StrictModeManager
import com.focuslock.app.system.UninstallProtectionManager
import com.focuslock.app.system.SessionClockStore
import kotlinx.coroutines.*

class FocusForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engineJob: Job? = null
    private val repository by lazy { FocusRepository(FocusDatabase.get(applicationContext)) }
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestTerminalCleanup(startId)
            return START_STICKY
        }
        val requestedId = intent?.getLongExtra(EXTRA_SESSION_ID, 0L) ?: 0L
        val sessionId = if (requestedId > 0L) requestedId else preferences.getLong(EXTRA_SESSION_ID, 0L)
        if (sessionId <= 0L) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        preferences.edit(commit = true) { putLong(EXTRA_SESSION_ID, sessionId) }
        createNotificationChannel()
        startAsForeground(buildNotification(false, 0L))
        engineJob?.cancel()
        engineJob = serviceScope.launch {
            UninstallProtectionManager.protectInstalledPackages(applicationContext)
                .onFailure { Log.e(TAG, "Could not activate Device Owner uninstall protection for this session.", it) }
            while (isActive) {
                val session = repository.activeSession()?.takeIf { it.id == sessionId }
                if (session == null) {
                    val recovery = releaseSessionPolicies()
                    if (recovery.isSuccess) {
                        clearAndStop()
                        break
                    }
                    Log.e(TAG, "Strict Lock Task recovery failed; keeping the foreground service alive for retry.", recovery.exceptionOrNull())
                    updateRecoveryNotification()
                    delay(RECOVERY_RETRY_MILLIS)
                    continue
                }
                val nowWall = System.currentTimeMillis()
                val nowElapsed = SystemClock.elapsedRealtime()
                val sessionStartElapsed = SessionClockStore.sessionStartElapsed(applicationContext, session)
                val currentBreak = repository.activeBreak(sessionId)
                if (currentBreak != null) {
                    val breakStartElapsed = SessionClockStore.breakStartElapsed(applicationContext, currentBreak)
                    val actualBreakElapsed = (nowElapsed - breakStartElapsed).coerceAtLeast(0L)
                    val left = (currentBreak.durationMillis - actualBreakElapsed).coerceAtLeast(0L)
                    updateNotification(true, left)
                    if (left == 0L) {
                        repository.completeActiveBreak(sessionId, nowWall, actualBreakElapsed)
                        SessionClockStore.clearBreak(applicationContext)
                    } else {
                        delay(minOf(1_000L, left))
                    }
                    continue
                }

                val completedBreaks = repository.breaksForSession(sessionId)
                    .filter { it.breakStatus == "COMPLETED" }
                val completedBreakMillis = completedBreaks.sumOf { it.durationMillis }
                val focusElapsed = (nowElapsed - sessionStartElapsed - completedBreakMillis).coerceAtLeast(0L)
                val focusRemaining = (session.durationMillis - focusElapsed).coerceAtLeast(0L)

                if (focusRemaining == 0L) {
                    repository.finishSession(sessionId, nowWall, "COMPLETED")
                    val recovery = releaseSessionPolicies()
                    if (recovery.isSuccess) {
                        SessionClockStore.clearSession(applicationContext)
                        clearAndStop()
                        break
                    }
                    Log.e(TAG, "Strict Lock Task recovery failed after session completion; retrying.", recovery.exceptionOrNull())
                    updateRecoveryNotification()
                    delay(RECOVERY_RETRY_MILLIS)
                    continue
                }

                val startedBreak = if (session.breakEnabled) {
                    repository.startBreakIfDue(sessionId, nowWall, BreakPolicy.DEFAULT_BREAK_MILLIS, focusElapsed)
                } else null

                if (startedBreak != null) {
                    SessionClockStore.beginBreak(applicationContext, startedBreak.id)
                    updateNotification(true, startedBreak.durationMillis)
                    continue
                }

                val nextBoundary = if (session.breakEnabled) {
                    BreakPolicy.breakAtElapsedMillis(completedBreaks.size)
                } else Long.MAX_VALUE
                val untilBoundary = (nextBoundary - focusElapsed).coerceAtLeast(0L)
                val waitMillis = minOf(focusRemaining, if (untilBoundary > 0L) untilBoundary else 1_000L, 1_000L)
                updateNotification(false, focusRemaining)
                delay(waitMillis.coerceAtLeast(250L))
            }
        }
        return START_STICKY
    }

    private fun releaseSessionPolicies(): Result<Unit> = runCatching {
        StrictModeManager.releaseSessionAllowlist(applicationContext).getOrThrow()
        UninstallProtectionManager.release(applicationContext).getOrThrow()
    }

    /**
     * Do not discard recovery state or stop the foreground service until Android
     * confirms that FocusLock's owned Lock Task policy has been released.
     */
    private fun requestTerminalCleanup(startId: Int) {
        engineJob?.cancel()
        createNotificationChannel()
        startAsForeground(buildRecoveryNotification())
        engineJob = serviceScope.launch {
            while (isActive) {
                val recovery = releaseSessionPolicies()
                if (recovery.isSuccess) {
                    SessionClockStore.clearSession(applicationContext)
                    preferences.edit(commit = true) { clear() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return@launch
                }
                Log.e(TAG, "Strict Lock Task recovery failed during explicit stop; retrying.", recovery.exceptionOrNull())
                updateRecoveryNotification()
                delay(RECOVERY_RETRY_MILLIS)
            }
        }
    }

    private suspend fun clearAndStop() {
        SessionClockStore.clearSession(applicationContext)
        preferences.edit(commit = true) { clear() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateRecoveryNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildRecoveryNotification())
    }

    private fun buildRecoveryNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.lock_recovery_pending))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(onBreak: Boolean, remaining: Long) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(onBreak, remaining))
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(onBreak: Boolean, remainingMillis: Long): Notification {
        val seconds = remainingMillis.coerceAtLeast(0L) / 1_000L
        val clock = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L)
        val openApp = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (onBreak) getString(R.string.app_name) + " • " + getString(R.string.break_notification_title)
                    else getString(R.string.app_name)
        val body = if (onBreak) getString(R.string.break_notification_remaining, clock)
                   else getString(R.string.focus_notification_remaining, clock)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title).setContentText(body).setContentIntent(openApp)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.focus_notification_channel),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.focus_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        engineJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.focuslock.app.action.START_FOCUS"
        const val ACTION_STOP = "com.focuslock.app.action.STOP_FOCUS"
        const val EXTRA_SESSION_ID = "session_id"
        private const val PREFS_NAME = "focus_service_state"
        private const val CHANNEL_ID = "focus_session"
        private const val NOTIFICATION_ID = 4101
        private const val RECOVERY_RETRY_MILLIS = 5_000L
        private const val TAG = "FocusLockService"
    }
}
