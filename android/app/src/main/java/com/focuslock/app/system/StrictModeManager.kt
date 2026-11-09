package com.focuslock.app.system

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import com.focuslock.app.services.FocusLockDeviceAdminReceiver

/**
 * Strict Mode helpers. Device Owner provisioning is deliberately not performed here.
 * The allowlist snapshot and ownership marker are durable before any DPM mutation,
 * so process death or a partial DPM failure always leaves enough state to recover.
 */
object StrictModeManager {
    private const val PREFS = "strict_lock_task_policy"
    private const val KEY_ORIGINAL_PACKAGES = "original_packages"
    private const val KEY_SESSION_POLICY_OWNED = "session_policy_owned"

    private fun manager(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context) =
        ComponentName(context, FocusLockDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        manager(context).isDeviceOwnerApp(context.packageName)

    fun isLockTaskPermitted(context: Context): Boolean =
        manager(context).isLockTaskPermitted(context.packageName)

    /**
     * Capture the exact prior allowlist and persist recovery ownership BEFORE
     * touching DevicePolicyManager. If preparation fails part-way through, try
     * immediate rollback; if rollback also fails, keep the durable marker so a
     * later explicit recovery or the session service can retry safely.
     */
    fun prepareAllowlist(context: Context): Result<Unit> = runCatching {
        val dpm = manager(context)
        check(dpm.isDeviceOwnerApp(context.packageName)) {
            "FocusLock is not provisioned as Device Owner on this device."
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = dpm.getLockTaskPackages(admin(context)).toSet()

        if (!prefs.getBoolean(KEY_SESSION_POLICY_OWNED, false)) {
            prefs.edit(commit = true) {
                putStringSet(KEY_ORIGINAL_PACKAGES, current)
                putBoolean(KEY_SESSION_POLICY_OWNED, true)
            }
        }

        try {
            dpm.setLockTaskPackages(admin(context), (current + context.packageName).toTypedArray())
            check(dpm.isLockTaskPermitted(context.packageName)) {
                "Android did not permit FocusLock for Lock Task."
            }
        } catch (failure: Exception) {
            val recovery = releaseSessionAllowlist(context)
            recovery.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    /**
     * Device Owner fallback for terminal cleanup when the Activity that called
     * startLockTask() is no longer alive. Removing this package exits Lock Task
     * on Android 6.0+; then the exact previous allowlist is restored.
     *
     * The ownership marker is intentionally cleared only after both DPM writes
     * succeed. Any exception leaves the snapshot intact for a subsequent retry.
     */
    fun releaseSessionAllowlist(context: Context): Result<Unit> =
        releaseSessionAllowlist(context) { dpm, component, packages ->
            dpm.setLockTaskPackages(component, packages.toTypedArray())
        }

    /** Internal policy-writer seam so recovery failures and retries can be tested deterministically. */
    internal fun releaseSessionAllowlist(
        context: Context,
        setLockTaskPackages: (DevicePolicyManager, ComponentName, Set<String>) -> Unit
    ): Result<Unit> = runCatching {
        val dpm = manager(context)
        val component = admin(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Standard sessions and sessions that never acquired our DPM policy need no recovery.
        if (!prefs.getBoolean(KEY_SESSION_POLICY_OWNED, false)) return@runCatching
        val isOwner = dpm.isDeviceOwnerApp(context.packageName)
        if (!isOwner) {
            // Device Owner authority was permanently lost, so no DPM policy can
            // be restored. Clear the durable marker to let terminal cleanup finish
            // instead of retrying an unrecoverable operation forever.
            prefs.edit(commit = true) {
                remove(KEY_ORIGINAL_PACKAGES)
                putBoolean(KEY_SESSION_POLICY_OWNED, false)
            }
            return@runCatching
        }
        val original = prefs.getStringSet(KEY_ORIGINAL_PACKAGES, emptySet()).orEmpty().toSet()
        val current = dpm.getLockTaskPackages(component).toSet()
        val activityState = context.getSharedPreferences("strict_lock_task_state", Context.MODE_PRIVATE)
        val focusLockOwnsActiveLockTask = activityState.getBoolean("owned", false)

        // Never evict FocusLock from a pre-existing administrator allowlist when
        // another controller owns the current kiosk/Lock Task state. The durable
        // Activity ownership marker is the authority for forcing Lock Task exit.
        if (focusLockOwnsActiveLockTask && context.packageName in current) {
            setLockTaskPackages(dpm, component, current - context.packageName)
        }

        // Restore the exact pre-session allowlist even when FocusLock was already
        // permitted before this session. This preserves unrelated kiosk policy.
        if (current != original || focusLockOwnsActiveLockTask) {
            setLockTaskPackages(dpm, component, original)
        }
        prefs.edit(commit = true) {
            remove(KEY_ORIGINAL_PACKAGES)
            putBoolean(KEY_SESSION_POLICY_OWNED, false)
        }
        if (focusLockOwnsActiveLockTask) {
            activityState.edit(commit = true) { putBoolean("owned", false) }
        }
    }

    fun isSessionAllowlistOwned(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SESSION_POLICY_OWNED, false)
}
