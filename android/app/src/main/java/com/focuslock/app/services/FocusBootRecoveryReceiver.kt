package com.focuslock.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.focuslock.app.data.FocusDatabase
import com.focuslock.app.data.FocusRepository
import com.focuslock.app.system.SessionClockStore
import com.focuslock.app.system.StrictModeManager
import com.focuslock.app.system.UninstallProtectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fail-open recovery after a device reboot.
 *
 * Lock Task itself does not survive a reboot, but a Device Owner allowlist and
 * FocusLock's durable ownership snapshot do. Treat a reboot as an interruption:
 * close any persisted active session, clear its monotonic anchors, and restore
 * the exact pre-session DPM allowlist before discarding recovery state.
 */
class FocusBootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                recoverAfterBoot(appContext)
            } catch (failure: Exception) {
                Log.e(TAG, "Unexpected failure during FocusLock boot recovery.", failure)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "FocusLockBootRecovery"
        private const val SERVICE_PREFS = "focus_service_state"
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val RETRY_DELAY_MILLIS = 750L

        /** Shared by the real boot receiver and deterministic recovery tests. */
        internal suspend fun recoverAfterBoot(context: Context) {
            val appContext = context.applicationContext
            val repository = FocusRepository(FocusDatabase.get(appContext))

            // Do not discard monotonic/service recovery state if Room cannot
            // durably mark the session interrupted. A later recovery attempt
            // must still be able to reconcile the active database row.
            val sessionRecovered = runCatching {
                val active = repository.activeSession()
                if (active != null) {
                    check(repository.finishSession(active.id, System.currentTimeMillis(), "INTERRUPTED")) {
                        "The active focus session could not be marked INTERRUPTED."
                    }
                }
            }
            if (sessionRecovered.isFailure) {
                Log.e(TAG, "Could not durably mark the pre-reboot focus session interrupted; recovery markers were retained.",
                    sessionRecovered.exceptionOrNull())
                return
            }

            SessionClockStore.clearSession(appContext)

            var recovered = false
            for (attempt in 0 until MAX_RECOVERY_ATTEMPTS) {
                val result = runCatching {
                    StrictModeManager.releaseSessionAllowlist(appContext).getOrThrow()
                    UninstallProtectionManager.release(appContext).getOrThrow()
                }
                if (result.isSuccess) {
                    appContext.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
                        .edit(commit = true) { clear() }
                    recovered = true
                    break
                }
                Log.e(TAG, "Device Owner policy recovery failed after reboot (attempt ${attempt + 1}).",
                    result.exceptionOrNull())
                if (attempt + 1 < MAX_RECOVERY_ATTEMPTS) delay(RETRY_DELAY_MILLIS)
            }
            if (!recovered) {
                Log.e(TAG, "Boot recovery remains pending; durable DPM recovery state was retained.")
            }
        }
    }
}
