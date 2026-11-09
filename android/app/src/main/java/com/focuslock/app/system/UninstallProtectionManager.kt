package com.focuslock.app.system

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import com.focuslock.app.services.FocusLockDeviceAdminReceiver

/**
 * Temporarily prevents user-initiated uninstall of installed packages during a
 * Device Owner-managed focus session. The exact previous policy is journaled
 * before DPM writes and restored on every terminal/recovery path.
 */
object UninstallProtectionManager {
    private const val PREFS = "focus_uninstall_protection"
    private const val KEY_OWNED = "owned"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_ORIGINALLY_BLOCKED = "originally_blocked"

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context) =
        ComponentName(context, FocusLockDeviceAdminReceiver::class.java)

    @Suppress("DEPRECATION")
    private fun installedPackages(context: Context): Set<String> =
        context.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()

    fun isProtectionOwned(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OWNED, false)

    /** Protect all packages visible to PackageManager, preserving pre-existing blocks. */
    @Synchronized
    fun protectInstalledPackages(context: Context): Result<Unit> = runCatching {
        val policy = dpm(context)
        check(policy.isDeviceOwnerApp(context.packageName)) {
            "FocusLock must be Device Owner to prevent app removal."
        }
        protectPackages(context, installedPackages(context))
    }

    /** Called for PACKAGE_ADDED so apps installed during a session are covered too. */
    @Synchronized
    fun protectNewPackage(context: Context, packageName: String): Result<Unit> = runCatching {
        val policy = dpm(context)
        if (!policy.isDeviceOwnerApp(context.packageName)) return@runCatching
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_OWNED, false)) return@runCatching
        protectPackages(context, setOf(packageName))
    }

    private fun protectPackages(context: Context, candidates: Set<String>) {
        val policy = dpm(context)
        val component = admin(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alreadyTracked = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()
        val newlyTracked = candidates - alreadyTracked
        val originallyBlocked = prefs.getStringSet(KEY_ORIGINALLY_BLOCKED, emptySet()).orEmpty().toMutableSet()

        // Journal package names and original states durably before changing DPM.
        newlyTracked.forEach { packageName ->
            if (policy.isUninstallBlocked(component, packageName)) originallyBlocked.add(packageName)
        }
        if (newlyTracked.isNotEmpty() || !prefs.getBoolean(KEY_OWNED, false)) {
            prefs.edit(commit = true) {
                putStringSet(KEY_PACKAGES, alreadyTracked + candidates)
                putStringSet(KEY_ORIGINALLY_BLOCKED, originallyBlocked)
                putBoolean(KEY_OWNED, true)
            }
        }

        candidates.forEach { packageName ->
            if (!policy.isUninstallBlocked(component, packageName)) {
                policy.setUninstallBlocked(component, packageName, true)
            }
        }
    }

    /**
     * Restore each package to its exact pre-session uninstall-blocked state.
     * Keep the journal intact if any DPM write fails so a later retry is safe.
     */
    @Synchronized
    fun release(context: Context): Result<Unit> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_OWNED, false)) return@runCatching
        val policy = dpm(context)
        if (!policy.isDeviceOwnerApp(context.packageName)) {
            prefs.edit(commit = true) {
                remove(KEY_PACKAGES)
                remove(KEY_ORIGINALLY_BLOCKED)
                putBoolean(KEY_OWNED, false)
            }
            return@runCatching
        }

        val component = admin(context)
        val tracked = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()
        val originallyBlocked = prefs.getStringSet(KEY_ORIGINALLY_BLOCKED, emptySet()).orEmpty().toSet()

        tracked.forEach { packageName ->
            val shouldRemainBlocked = packageName in originallyBlocked
            if (policy.isUninstallBlocked(component, packageName) != shouldRemainBlocked) {
                policy.setUninstallBlocked(component, packageName, shouldRemainBlocked)
            }
        }

        prefs.edit(commit = true) {
            remove(KEY_PACKAGES)
            remove(KEY_ORIGINALLY_BLOCKED)
            putBoolean(KEY_OWNED, false)
        }
    }
}
