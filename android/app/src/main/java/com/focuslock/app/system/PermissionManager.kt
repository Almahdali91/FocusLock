package com.focuslock.app.system

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.focuslock.app.services.StandardProtectionAccessibilityService

/** Read-only checks for special Android access. Android itself owns permission grants. */
object PermissionManager {
    fun isStandardAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, StandardProtectionAccessibilityService::class.java)
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val listedByManager = manager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { info ->
                val service = info.resolveInfo?.serviceInfo
                service?.packageName == expected.packageName && service.name == expected.className
            } == true
        if (listedByManager) return true

        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /**
     * Accessibility is a core requirement only when Standard Protection has
     * at least one selected package to monitor. Notification permission is
     * handled separately because Android does not make it an accessibility
     * protection prerequisite.
     */
    fun isStandardProtectionReady(
        selectedBlockedPackages: Set<String>,
        accessibilityEnabled: Boolean
    ): Boolean = selectedBlockedPackages.isEmpty() || accessibilityEnabled

    /**
     * Strict managed Lock Task does not depend on the Standard Accessibility
     * service. Unknown/non-Strict modes deliberately retain Standard safeguards.
     */
    fun isProtectionReady(
        lockMode: String,
        selectedBlockedPackages: Set<String>,
        accessibilityEnabled: Boolean
    ): Boolean = missingRequirementsForMode(
        lockMode, selectedBlockedPackages, accessibilityEnabled
    ).isEmpty()

    fun missingRequirementsForMode(
        lockMode: String,
        selectedBlockedPackages: Set<String>,
        accessibilityEnabled: Boolean
    ): List<String> = if (
        lockMode == "STRICT" ||
        isStandardProtectionReady(selectedBlockedPackages, accessibilityEnabled)
    ) {
        emptyList()
    } else {
        listOf("standard_accessibility")
    }

    fun standardModeMissingRequirements(
        context: Context,
        selectedBlockedPackages: Set<String>
    ): List<String> {
        return if (isStandardProtectionReady(
                selectedBlockedPackages,
                isStandardAccessibilityEnabled(context)
            )
        ) emptyList() else listOf("standard_accessibility")
    }
}
