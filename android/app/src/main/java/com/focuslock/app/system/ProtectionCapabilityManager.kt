package com.focuslock.app.system

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Read-only capability discovery for the universal Android protection path.
 * Detection never grants permissions, changes policy, or starts kiosk mode.
 */
enum class ProtectionTier {
    ENTERPRISE_LOCK_TASK,
    STANDARD_ACCESSIBILITY,
    MANUAL_ONLY
}

data class ProtectionCapabilities(
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val accessibilityEnabled: Boolean,
    val usageAccessGranted: Boolean,
    val deviceOwner: Boolean,
    val lockTaskPermitted: Boolean,
    val selectedTier: ProtectionTier
)

object ProtectionCapabilityManager {
    fun resolveTier(
        accessibilityEnabled: Boolean,
        deviceOwner: Boolean,
        lockTaskPermitted: Boolean
    ): ProtectionTier = when {
        deviceOwner && lockTaskPermitted -> ProtectionTier.ENTERPRISE_LOCK_TASK
        accessibilityEnabled -> ProtectionTier.STANDARD_ACCESSIBILITY
        else -> ProtectionTier.MANUAL_ONLY
    }

    fun detect(context: Context): ProtectionCapabilities {
        val policy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val owner = runCatching { policy.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
        val lockTask = runCatching { policy.isLockTaskPermitted(context.packageName) }.getOrDefault(false)
        val accessibility = PermissionManager.isStandardAccessibilityEnabled(context)
        val usage = isUsageAccessGranted(context)
        return ProtectionCapabilities(
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            accessibilityEnabled = accessibility,
            usageAccessGranted = usage,
            deviceOwner = owner,
            lockTaskPermitted = lockTask,
            selectedTier = resolveTier(accessibility, owner, lockTask)
        )
    }

    /** Usage Access is supplemental telemetry, not a replacement for Accessibility. */
    fun isUsageAccessGranted(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
